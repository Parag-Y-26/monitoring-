package com.edgepulse.core.audit

import java.util.Base64

/** One instance is owned by the serialized DB transaction that persists the returned record. */
class EventSignatureChain(
    private val signer: AuditSigner,
    initialPreviousHash: String = AuditCanonical.GENESIS_HASH,
) {
    private var previousHash = initialPreviousHash

    @Synchronized
    fun append(payload: AuditPayload): SignedAuditRecord {
        val hash = AuditCanonical.hash(AuditCanonical.record(payload, previousHash, signer.publicKeySpkiBase64))
        val record = SignedAuditRecord(
            payload, previousHash, signer.publicKeySpkiBase64,
            AuditCanonical.hex(hash), Base64.getEncoder().encodeToString(signer.sign(hash)),
        )
        previousHash = record.chainHash
        return record
    }
}
