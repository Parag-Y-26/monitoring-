package com.edgepulse.core.audit

import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object LocalAuditVerifier {
    internal fun requireP256PublicKey(encoded: String) { publicKey(encoded) }
    private fun publicKey(encoded: String): ECPublicKey {
        require(encoded.length in 1..2048)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded))) as ECPublicKey
        val expected = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        require(key.params.order == expected.order && key.params.generator == expected.generator && key.params.curve == expected.curve)
        return key
    }

    /** Trust anchor and optional expected tip must come from the supervisor, not the bundle. */
    fun verify(records: List<SignedAuditRecord>, trustedPublicKey: String, expectedTipHash: String? = null): AuditVerificationResult {
        var continuity = "CONTINUOUS_VERIFIED"
        var count = 0
        var out = 0L
        var inbound = 0L
        var gaps = 0L
        var previous = AuditCanonical.GENESIS_HASH
        var expectedKey = trustedPublicKey
        val clocks = mutableMapOf<String, Long>()
        val sessionOutCounts = mutableMapOf<String, Long>()
        val gapEnds = mutableMapOf<String, Long>()
        fun failed(error: String) = AuditVerificationResult(false, continuity, count, 0, 0, 0, previous.takeIf { count > 0 }, error)
        if (records.isEmpty()) return failed("EMPTY_CHAIN")
        try {
            publicKey(trustedPublicKey)
            for ((index, record) in records.withIndex()) {
                if (record.publicKeySpkiBase64 != expectedKey) {
                    continuity = "PUBKEY_ROTATED_TAMPER_ALERT"
                    return failed("UNAUTHORIZED_KEY_CHANGE")
                }
                val p = record.payload
                require(p.sequence == index.toLong() + 1) { "SEQUENCE_MISMATCH" }
                require(record.previousHash == previous) { "BROKEN_LINK" }
                require(AuditCanonical.validHash(record.chainHash)) { "BAD_HASH_ENCODING" }
                val hash = AuditCanonical.hash(AuditCanonical.record(p, record.previousHash, record.publicKeySpkiBase64))
                require(AuditCanonical.hex(hash) == record.chainHash) { "HASH_MISMATCH" }
                val signature = Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey(record.publicKeySpkiBase64)); update(hash)
                    verify(Base64.getDecoder().decode(record.signatureBase64))
                }
                require(signature) { "BAD_SIGNATURE" }
                require(p.elapsedMs >= (clocks[p.bootId] ?: 0L)) { "MONOTONIC_REGRESSION" }
                clocks[p.bootId] = p.elapsedMs
                when (p.kind) {
                    "EVENT" -> {
                        require(p.fields.keys == setOf("direction", "bag_weight", "running_out_total", "track_id", "webp_sha256"))
                        val weight = p.fields.getValue("bag_weight").toLong()
                        require(weight == 1L || weight == 2L)
                        require(p.fields.getValue("track_id").toLong() >= 0)
                        val runningOut = p.fields.getValue("running_out_total").toLong()
                        require(AuditCanonical.validHash(p.fields.getValue("webp_sha256")))
                        when (p.fields.getValue("direction")) {
                            "OUT" -> out = Math.addExact(out, weight)
                            "IN" -> inbound = Math.addExact(inbound, weight)
                            else -> error("BAD_DIRECTION")
                        }
                        val sessionOut = Math.addExact(sessionOutCounts[p.sessionId] ?: 0L, if (p.fields.getValue("direction") == "OUT") weight else 0L)
                        require(runningOut == sessionOut) { "RUNNING_COUNT_MISMATCH" }
                        sessionOutCounts[p.sessionId] = sessionOut
                    }
                    "GAP" -> {
                        require(p.fields.keys == setOf("reason", "gap_start_elapsed_ms", "gap_end_elapsed_ms", "gap_start_wall_ms"))
                        require(p.fields.getValue("reason") in setOf("THERMAL_SEVERE", "OS_KILL_DETECTED", "CAMERA_ERROR", "PAUSE"))
                        val start = p.fields.getValue("gap_start_elapsed_ms").toLong()
                        val end = p.fields.getValue("gap_end_elapsed_ms").toLong()
                        require(start >= 0 && end >= start && end <= p.elapsedMs)
                        require(start >= (gapEnds[p.bootId] ?: 0L)) { "OVERLAPPING_GAPS" }
                        require(p.fields.getValue("gap_start_wall_ms").toLong() >= 0)
                        gaps = Math.addExact(gaps, end - start)
                        gapEnds[p.bootId] = end
                    }
                    "ROTATION" -> {
                        require(p.fields.keys == setOf("new_public_key_spki_b64"))
                        val newKey = p.fields.getValue("new_public_key_spki_b64")
                        require(newKey != expectedKey)
                        publicKey(newKey)
                        expectedKey = newKey
                        continuity = "AUTHORIZED_KEY_ROTATION"
                    }
                    else -> error("UNKNOWN_RECORD_KIND")
                }
                previous = record.chainHash
                count++
            }
            require(expectedTipHash == null || previous == expectedTipHash) { "EXTERNAL_TIP_MISMATCH" }
        } catch (_: Exception) {
            return failed("INVALID_AUDIT_RECORD")
        }
        return AuditVerificationResult(true, continuity, count, out, inbound, gaps, previous, externallyAnchoredTip = expectedTipHash != null)
    }
}
