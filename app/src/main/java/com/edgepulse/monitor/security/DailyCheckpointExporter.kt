package com.edgepulse.monitor.security

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.edgepulse.core.audit.AuditBundle
import com.edgepulse.core.audit.AuditVerificationResult
import com.edgepulse.core.audit.LocalAuditVerifier
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.edgepulse.core.audit.AuditCanonical
import com.edgepulse.monitor.vision.ml.BenchmarkReport

/** Exports complete signed history through a user-selected SAF tree, never arbitrary file paths. */
class DailyCheckpointExporter(private val resolver: ContentResolver) {
    private val exportJson = Json { encodeDefaults = true }
    suspend fun exportBenchmark(treeUri: Uri, report: BenchmarkReport): Uri = withContext(Dispatchers.IO) {
        LocalSafFolder.requireLocalTree(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        val destination = checkNotNull(DocumentsContract.createDocument(resolver, parent, "application/json", "benchmark_$timestamp.json"))
        try {
            checkNotNull(resolver.openOutputStream(destination, "wt")).use { it.write(exportJson.encodeToString(report).toByteArray()) }
        } catch (failure: Exception) {
            DocumentsContract.deleteDocument(resolver, destination)
            throw failure
        }
        destination
    }

    suspend fun export(treeUri: Uri, bundle: AuditBundle, externallyEnrolledPublicKey: String): Pair<Uri, AuditVerificationResult> = withContext(Dispatchers.IO) {
        LocalSafFolder.requireLocalTree(treeUri)
        val verification = LocalAuditVerifier.verify(bundle.records, externallyEnrolledPublicKey)
        check(verification.valid) { "Refusing to export an invalid audit chain" }
        val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        val destination = checkNotNull(DocumentsContract.createDocument(resolver, document, "application/json", "checkpoint_${timestamp}.sig.json"))
        try {
            checkNotNull(resolver.openOutputStream(destination, "wt")).use {
                it.write(Json.encodeToString(bundle).toByteArray())
            }
        } catch (failure: Exception) {
            DocumentsContract.deleteDocument(resolver, destination)
            throw failure
        }
        destination to verification
    }

    suspend fun exportIdentity(treeUri: Uri, signer: KeystoreAuditSigner, settings: SecureSettings): Uri = withContext(Dispatchers.IO) {
        LocalSafFolder.requireLocalTree(treeUri)
        check(signer.isEnrolled() && settings.trustedAuditPublicKey == signer.publicKeySpkiBase64) { "PUBKEY_ROTATED_TAMPER_ALERT" }
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        val document = buildJsonObject {
            put("format", "EdgePulse-public-identity-v1")
            put("public_key_spki_b64", signer.publicKeySpkiBase64)
            put("attestation_cert_pem", signer.certificateChainPem())
            put("attestation_nonce_mode", settings.attestationNonceMode)
            put("verifier_challenge_sha256", AuditCanonical.hex(settings.verifierChallengeSha256))
            put("attestation_verified", false)
        }
        val destination = checkNotNull(DocumentsContract.createDocument(resolver, parent, "application/json", "identity_$timestamp.json"))
        try {
            checkNotNull(resolver.openOutputStream(destination, "wt")).use { it.write(document.toString().toByteArray()) }
        } catch (failure: Exception) {
            DocumentsContract.deleteDocument(resolver, destination)
            throw failure
        }
        destination
    }
}
