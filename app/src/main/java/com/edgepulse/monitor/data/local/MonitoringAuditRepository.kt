package com.edgepulse.monitor.data.local

import android.graphics.Bitmap
import androidx.room.withTransaction
import com.edgepulse.core.audit.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One serialized writer per monitoring owner; the DB head check rejects competing writers. */
class MonitoringAuditRepository(
    private val database: EdgePulseDatabase,
    private val archiver: WebpCircularArchiver,
    private val signer: AuditSigner,
    private val trustedInitialPublicKey: String,
) {
    private val mutex = Mutex()
    private val dao get() = database.monitoringDao()
    private var initialized = false
    private var sequence = 0L
    private var previousHash = AuditCanonical.GENESIS_HASH
    private var expectedSigner = trustedInitialPublicKey
    private val clocks = mutableMapOf<String, Long>()
    private val gapEnds = mutableMapOf<String, Long>()
    private val outCounts = mutableMapOf<String, Long>()
    private val inCounts = mutableMapOf<String, Long>()

    suspend fun records(): List<SignedAuditRecord> = mutex.withLock {
        val records = loadRecords()
        if (records.isNotEmpty()) check(LocalAuditVerifier.verify(records, trustedInitialPublicKey).valid) { "Invalid persisted audit history" }
        records
    }

    suspend fun recordCrossing(
        sessionId: String, trackId: Long, direction: String, bagWeight: Int, bitmap: Bitmap,
        elapsedMs: Long, wallMs: Long,
    ): SignedAuditRecord = mutex.withLock {
        initialize()
        require(trackId >= 0 && direction in setOf("OUT", "IN") && bagWeight in 1..2)
        val session = requireSession(sessionId, elapsedMs)
        val out = Math.addExact(outCounts[sessionId] ?: 0, if (direction == "OUT") bagWeight.toLong() else 0)
        val inbound = Math.addExact(inCounts[sessionId] ?: 0, if (direction == "IN") bagWeight.toLong() else 0)
        var signed: SignedAuditRecord? = null
        archiver.archive(bitmap) { keyframe ->
            assertCurrentHead()
            val record = next(AuditPayload("EVENT", sessionId, sequence + 1, session.boot_id, elapsedMs, wallMs,
                mapOf("track_id" to trackId.toString(), "direction" to direction, "bag_weight" to bagWeight.toString(),
                    "running_out_total" to out.toString(), "webp_sha256" to keyframe.plaintextSha256)))
            val id = dao.insertEvent(InventoryEventEntity(session_id = sessionId, track_id = trackId, direction = direction,
                bag_weight = bagWeight, running_out_total = out, enc_webp_path = keyframe.filename,
                webp_sha256 = keyframe.plaintextSha256, monotonic_elapsed_ms = elapsedMs, wall_clock_ms = wallMs,
                prev_chain_hash = record.previousHash, chain_hash = record.chainHash, ecdsa_sig_b64 = record.signatureBase64,
                audit_sequence = record.payload.sequence, audit_record_json = Json.encodeToString(record)))
            dao.updateCounts(sessionId, out, inbound)
            signed = record
            id
        }
        checkNotNull(signed).also(::advance)
    }

    suspend fun recordGap(
        sessionId: String, reason: String, startElapsedMs: Long, endElapsedMs: Long,
        startWallMs: Long, endWallMs: Long,
    ): SignedAuditRecord = mutex.withLock {
        initialize()
        require(reason in setOf("THERMAL_SEVERE", "OS_KILL_DETECTED", "CAMERA_ERROR", "PAUSE"))
        val session = requireSession(sessionId, endElapsedMs)
        require(startElapsedMs >= session.started_elapsed_ms && endElapsedMs >= startElapsedMs && startWallMs >= 0)
        require(startElapsedMs >= (gapEnds[session.boot_id] ?: 0))
        val record = next(AuditPayload("GAP", sessionId, sequence + 1, session.boot_id, endElapsedMs, endWallMs,
            mapOf("reason" to reason, "gap_start_elapsed_ms" to startElapsedMs.toString(),
                "gap_end_elapsed_ms" to endElapsedMs.toString(), "gap_start_wall_ms" to startWallMs.toString())))
        database.withTransaction {
            assertCurrentHead()
            dao.insertGap(MonitoringGapEntity(session_id = sessionId, gap_start_elapsed_ms = startElapsedMs,
                gap_end_elapsed_ms = endElapsedMs, gap_start_wall_ms = startWallMs, reason = reason,
                prev_chain_hash = record.previousHash, chain_hash = record.chainHash, ecdsa_sig_b64 = record.signatureBase64,
                audit_sequence = record.payload.sequence, audit_record_json = Json.encodeToString(record)))
        }
        record.also(::advance)
    }

    suspend fun authorizeRotation(sessionId: String, newPublicKey: String, elapsedMs: Long, wallMs: Long): SignedAuditRecord = mutex.withLock {
        initialize()
        val session = requireSession(sessionId, elapsedMs)
        check(!session.is_session_active) { "Stop the session before authorizing a key rotation" }
        check(signer.publicKeySpkiBase64 == expectedSigner) { "PUBKEY_ROTATED_TAMPER_ALERT" }
        require(elapsedMs >= (clocks[session.boot_id] ?: 0))
        val record = AuthorizedKeyRotationRecord.create(signer, newPublicKey, previousHash, sessionId,
            sequence + 1, session.boot_id, elapsedMs, wallMs)
        database.withTransaction {
            assertCurrentHead()
            val current = checkNotNull(dao.session(sessionId))
            val rotations = Json.decodeFromString<List<SignedAuditRecord>>(current.authorized_rotation_records_json)
            dao.saveRotationRecords(sessionId, Json.encodeToString(rotations + record))
        }
        record.also(::advance)
    }

    private suspend fun requireSession(id: String, elapsedMs: Long): MonitoringSessionEntity {
        val session = checkNotNull(dao.session(id)) { "Monitoring session does not exist" }
        require(session.mode == "CEMENT") { "Only construction sessions can receive new records" }
        require(elapsedMs >= session.started_elapsed_ms)
        check(session.pubkey_spki_b64 == signer.publicKeySpkiBase64) { "Session belongs to a different audit identity" }
        return session
    }

    private suspend fun initialize() {
        if (initialized) return
        val records = loadRecords()
        if (records.isNotEmpty()) {
            check(LocalAuditVerifier.verify(records, trustedInitialPublicKey).valid) { "Invalid persisted audit history" }
            records.forEach(::advance)
        } else check(signer.publicKeySpkiBase64 == trustedInitialPublicKey) { "PUBKEY_ROTATED_TAMPER_ALERT" }
        initialized = true
    }

    private suspend fun loadRecords(): List<SignedAuditRecord> = AuditHistory.read(database)

    private suspend fun assertCurrentHead() {
        val eventOrGap = dao.latestEventOrGapJson()?.let { Json.decodeFromString<SignedAuditRecord>(it) }
        val rotations = dao.rotationRecordsJson().flatMap { Json.decodeFromString<List<SignedAuditRecord>>(it) }
        val latest = (rotations + listOfNotNull(eventOrGap)).maxByOrNull { it.payload.sequence }
        check((latest?.payload?.sequence ?: 0L) == sequence && (latest?.chainHash ?: AuditCanonical.GENESIS_HASH) == previousHash) {
            "Audit head changed; reopen the repository before retrying"
        }
    }

    private fun next(payload: AuditPayload): SignedAuditRecord {
        check(signer.publicKeySpkiBase64 == expectedSigner) { "PUBKEY_ROTATED_TAMPER_ALERT" }
        require(payload.elapsedMs >= (clocks[payload.bootId] ?: 0))
        return EventSignatureChain(signer, previousHash).append(payload)
    }

    private fun advance(record: SignedAuditRecord) {
        val payload = record.payload
        sequence = payload.sequence
        previousHash = record.chainHash
        expectedSigner = if (payload.kind == "ROTATION") payload.fields.getValue("new_public_key_spki_b64") else record.publicKeySpkiBase64
        clocks[payload.bootId] = payload.elapsedMs
        if (payload.kind == "GAP") gapEnds[payload.bootId] = payload.fields.getValue("gap_end_elapsed_ms").toLong()
        if (payload.kind == "EVENT") {
            val counts = if (payload.fields.getValue("direction") == "OUT") outCounts else inCounts
            counts[payload.sessionId] = Math.addExact(counts[payload.sessionId] ?: 0, payload.fields.getValue("bag_weight").toLong())
        }
    }
}
