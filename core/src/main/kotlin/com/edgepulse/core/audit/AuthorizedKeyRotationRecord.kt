package com.edgepulse.core.audit

/** The outgoing signer must append this into the same chain before the new signer is used. */
object AuthorizedKeyRotationRecord {
    fun create(
        oldSigner: AuditSigner,
        newPublicKeySpkiBase64: String,
        previousHash: String,
        sessionId: String,
        sequence: Long,
        bootId: String,
        elapsedMs: Long,
        wallMs: Long,
    ): SignedAuditRecord {
        require(newPublicKeySpkiBase64 != oldSigner.publicKeySpkiBase64)
        LocalAuditVerifier.requireP256PublicKey(newPublicKeySpkiBase64)
        return EventSignatureChain(oldSigner, previousHash).append(AuditPayload(
            "ROTATION", sessionId, sequence, bootId, elapsedMs, wallMs,
            mapOf("new_public_key_spki_b64" to newPublicKeySpkiBase64),
        ))
    }
}
