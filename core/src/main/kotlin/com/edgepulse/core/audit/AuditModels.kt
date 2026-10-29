package com.edgepulse.core.audit

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
data class AuditPayload(
    val kind: String,
    val sessionId: String,
    val sequence: Long,
    val bootId: String,
    val elapsedMs: Long,
    val wallMs: Long,
    val fields: Map<String, String>,
)

@Serializable
data class SignedAuditRecord(
    val payload: AuditPayload,
    val previousHash: String,
    val publicKeySpkiBase64: String,
    val chainHash: String,
    val signatureBase64: String,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class AuditBundle(@EncodeDefault val version: Int = 1, val records: List<SignedAuditRecord>)

@Serializable
data class AuditVerificationResult(
    val valid: Boolean,
    val keyContinuityStatus: String,
    val verifiedRecords: Int,
    val verifiedOutCount: Long,
    val verifiedInCount: Long,
    val gapDurationMs: Long,
    val tipHash: String?,
    val error: String? = null,
    val externallyAnchoredTip: Boolean = false,
)

interface AuditSigner {
    val publicKeySpkiBase64: String
    fun sign(message: ByteArray): ByteArray
}
