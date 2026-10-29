package com.edgepulse.core.audit

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class AuditChainTest {
    private fun key(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun signer(key: KeyPair) = object : AuditSigner {
        override val publicKeySpkiBase64 = Base64.getEncoder().encodeToString(key.public.encoded)
        override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private); update(message); sign()
        }
    }

    private fun event(sequence: Long, elapsed: Long = sequence * 100) = AuditPayload(
        kind = "EVENT", sessionId = "session-1", sequence = sequence,
        bootId = "boot-1", elapsedMs = elapsed, wallMs = 1_800_000_000_000 + elapsed,
        fields = mapOf("direction" to "OUT", "bag_weight" to "1", "running_out_total" to sequence.toString(),
            "track_id" to sequence.toString(), "webp_sha256" to "a".repeat(64)),
    )

    @Test fun `valid events verify from external trust anchor`() {
        val s = signer(key()); val chain = EventSignatureChain(s)
        val records = (1L..3L).map { chain.append(event(it)) }
        val result = LocalAuditVerifier.verify(records, s.publicKeySpkiBase64)
        assertTrue(result.valid)
        assertEquals("CONTINUOUS_VERIFIED", result.keyContinuityStatus)
        assertEquals(3L, result.verifiedOutCount)
    }

    @Test fun `tampered signed fields and reordered records fail`() {
        val s = signer(key()); val chain = EventSignatureChain(s)
        val a = chain.append(event(1)); val b = chain.append(event(2))
        assertFalse(LocalAuditVerifier.verify(listOf(a.copy(payload = a.payload.copy(wallMs = 42)), b), s.publicKeySpkiBase64).valid)
        assertFalse(LocalAuditVerifier.verify(listOf(b, a), s.publicKeySpkiBase64).valid)
        assertFalse(LocalAuditVerifier.verify(listOf(a.copy(signatureBase64 = "!")), s.publicKeySpkiBase64).valid)
    }

    @Test fun `unexpected key raises tamper alert even if signature is mathematically valid`() {
        val old = signer(key()); val replacement = signer(key())
        val a = EventSignatureChain(old).append(event(1))
        val b = EventSignatureChain(replacement, a.chainHash).append(event(2))
        val result = LocalAuditVerifier.verify(listOf(a, b), old.publicKeySpkiBase64)
        assertFalse(result.valid)
        assertEquals("PUBKEY_ROTATED_TAMPER_ALERT", result.keyContinuityStatus)
    }

    @Test fun `old key signed rotation authorizes exactly the named new key`() {
        val old = signer(key()); val replacement = signer(key()); val chain = EventSignatureChain(old)
        val a = chain.append(event(1))
        val rotation = chain.append(event(2).copy(kind = "ROTATION", fields = mapOf("new_public_key_spki_b64" to replacement.publicKeySpkiBase64)))
        val b = EventSignatureChain(replacement, rotation.chainHash).append(event(3).copy(fields = event(3).fields + ("running_out_total" to "2")))
        val result = LocalAuditVerifier.verify(listOf(a, rotation, b), old.publicKeySpkiBase64)
        assertTrue(result.valid)
        assertEquals("AUTHORIZED_KEY_ROTATION", result.keyContinuityStatus)
        assertEquals(2L, result.verifiedOutCount)
        assertFalse(LocalAuditVerifier.verify(listOf(a, rotation.copy(payload = rotation.payload.copy(fields = mapOf("new_public_key_spki_b64" to signer(key()).publicKeySpkiBase64))), b), old.publicKeySpkiBase64).valid)
    }

    @Test fun `same boot monotonic regression fails and explicit boot boundary permits reset`() {
        val s = signer(key()); val chain = EventSignatureChain(s)
        val a = chain.append(event(1, 1000)); val b = chain.append(event(2, 10))
        assertFalse(LocalAuditVerifier.verify(listOf(a, b), s.publicKeySpkiBase64).valid)
        val reset = EventSignatureChain(s, a.chainHash).append(event(2, 10).copy(bootId = "boot-2"))
        assertTrue(LocalAuditVerifier.verify(listOf(a, reset), s.publicKeySpkiBase64).valid)
    }

    @Test fun `anchored tip detects suffix deletion and empty chain fails`() {
        val s = signer(key()); val chain = EventSignatureChain(s)
        val a = chain.append(event(1)); val b = chain.append(event(2))
        assertFalse(LocalAuditVerifier.verify(listOf(a), s.publicKeySpkiBase64, b.chainHash).valid)
        assertFalse(LocalAuditVerifier.verify(emptyList(), s.publicKeySpkiBase64).valid)
        assertFalse(LocalAuditVerifier.verify(listOf(a), signer(key()).publicKeySpkiBase64).valid)
    }

    @Test fun `framed canonical bytes distinguish delimiter collisions and ignore map order`() {
        val a = event(1).copy(fields = linkedMapOf("ab" to "c", "d" to "e"))
        val b = event(1).copy(fields = linkedMapOf("a" to "bc", "d" to "e"))
        assertFalse(AuditCanonical.payload(a).contentEquals(AuditCanonical.payload(b)))
        assertArrayEquals(AuditCanonical.payload(a), AuditCanonical.payload(a.copy(fields = a.fields.entries.reversed().associate { it.toPair() })))
    }

    @Test fun `malformed event values fail closed`() {
        val s = signer(key()); val chain = EventSignatureChain(s)
        val malformed = chain.append(event(1).copy(fields = event(1).fields + ("bag_weight" to "100")))
        assertFalse(LocalAuditVerifier.verify(listOf(malformed), s.publicKeySpkiBase64).valid)
    }

    @Test fun `export ephemeral JVM signed fixtures for independent Python verification`() {
        val old = signer(key()); val replacement = signer(key()); val chain = EventSignatureChain(old)
        val a = chain.append(event(1))
        val b = chain.append(event(2))
        val unsigned = EventSignatureChain(replacement, a.chainHash).append(event(2))
        val rotation = EventSignatureChain(old, a.chainHash).append(event(2).copy(kind = "ROTATION", fields = mapOf("new_public_key_spki_b64" to replacement.publicKeySpkiBase64)))
        val rotated = EventSignatureChain(replacement, rotation.chainHash).append(event(3).copy(fields = event(3).fields + ("running_out_total" to "2")))
        val folder = File("build/audit-fixtures").apply { mkdirs() }
        val bundles = File("build/audit-bundles").apply { mkdirs() }
        fun fixture(name: String, records: List<SignedAuditRecord>, valid: Boolean, continuity: String) {
            val value = buildJsonObject {
                put("trustedKey", old.publicKeySpkiBase64)
                put("records", Json.encodeToJsonElement(records))
                put("expectedValid", valid)
                put("expectedContinuity", continuity)
            }
            File(folder, "$name.json").writeText(Json.encodeToString(value))
            File(bundles, "$name.json").writeText(Json.encodeToString(AuditBundle(records = records)))
            File(bundles, "$name.anchor").writeText(old.publicKeySpkiBase64)
        }
        fixture("valid", listOf(a, b), true, "CONTINUOUS_VERIFIED")
        fixture("authorized", listOf(a, rotation, rotated), true, "AUTHORIZED_KEY_ROTATION")
        fixture("unsigned-change", listOf(a, unsigned), false, "PUBKEY_ROTATED_TAMPER_ALERT")
        fixture("tampered", listOf(a.copy(payload = a.payload.copy(wallMs = 42))), false, "CONTINUOUS_VERIFIED")
    }

    @Test fun `checkpoint wire envelope always includes the protocol version`() {
        val wire = Json.parseToJsonElement(Json.encodeToString(AuditBundle(records = emptyList()))).jsonObject
        assertEquals(JsonPrimitive(1), wire["version"])
    }

    @Test fun `signed gap durations verify and modified reasons fail`() {
        val s = signer(key())
        val p = event(1, 1000).copy(kind = "GAP", fields = mapOf("reason" to "OS_KILL_DETECTED",
            "gap_start_elapsed_ms" to "100", "gap_end_elapsed_ms" to "900", "gap_start_wall_ms" to "1800000000000"))
        val gap = EventSignatureChain(s).append(p)
        assertEquals(800L, LocalAuditVerifier.verify(listOf(gap), s.publicKeySpkiBase64).gapDurationMs)
        assertFalse(LocalAuditVerifier.verify(listOf(gap.copy(payload = p.copy(fields = p.fields + ("reason" to "PAUSE")))), s.publicKeySpkiBase64).valid)
        val overlap = EventSignatureChain(s, gap.chainHash).append(p.copy(sequence = 2, elapsedMs = 2000))
        assertFalse(LocalAuditVerifier.verify(listOf(gap, overlap), s.publicKeySpkiBase64).valid)
    }

    @Test fun `signed but inconsistent running counts fail`() {
        val s = signer(key())
        val p = event(1).copy(fields = event(1).fields + ("running_out_total" to "99"))
        assertFalse(LocalAuditVerifier.verify(listOf(EventSignatureChain(s).append(p)), s.publicKeySpkiBase64).valid)
    }

    @Test fun `rotation helper rejects invalid destination keys before signing`() {
        val old = signer(key())
        assertThrows(Exception::class.java) {
            AuthorizedKeyRotationRecord.create(old, "invalid-key", AuditCanonical.GENESIS_HASH, "session", 1, "boot", 1, 1)
        }
    }
}
