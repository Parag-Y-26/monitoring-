package com.edgepulse.core.audit

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Protocol v1 uses UTF-8, unsigned 32-bit big-endian lengths and decimal integers. */
object AuditCanonical {
    const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
    private val fieldName = Regex("[a-z][a-z0-9_]{0,63}")
    private val digest = Regex("[0-9a-f]{64}")

    private fun frame(parts: List<String>): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { out ->
            for (part in parts) {
                val bytes = part.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 65_536) { "Audit field too large" }
                out.writeInt(bytes.size)
                out.write(bytes)
            }
        }
        buffer.toByteArray()
    }

    fun payload(payload: AuditPayload): ByteArray {
        require(payload.sequence > 0 && payload.elapsedMs >= 0 && payload.wallMs >= 0)
        require(payload.sessionId.isNotBlank() && payload.bootId.isNotBlank())
        require(payload.fields.size <= 64 && payload.fields.keys.all(fieldName::matches))
        return frame(buildList {
            add("EdgePulse/audit-payload/v1")
            add(payload.kind); add(payload.sessionId); add(payload.sequence.toString())
            add(payload.bootId); add(payload.elapsedMs.toString()); add(payload.wallMs.toString())
            add(payload.fields.size.toString())
            payload.fields.toSortedMap().forEach { (name, value) -> add(name); add(value) }
        })
    }

    fun record(payload: AuditPayload, previousHash: String, publicKey: String): ByteArray {
        require(digest.matches(previousHash))
        val prefix = frame(listOf("EdgePulse/audit-record/v1", previousHash, publicKey))
        return prefix + payload(payload)
    }

    fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    fun validHash(value: String) = digest.matches(value)
}
