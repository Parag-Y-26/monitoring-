package com.edgepulse.monitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edgepulse.core.audit.*
import com.edgepulse.monitor.security.KeystoreAuditSigner
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.net.Uri
import com.edgepulse.monitor.security.LocalSafFolder

@RunWith(AndroidJUnit4::class)
class KeystoreAuditInstrumentedTest {
    @Test fun offlineAuditExportsRejectCloudDocumentProviders() {
        fun tree(authority: String) = Uri.Builder().scheme("content").authority(authority)
            .appendPath("tree").appendPath("primary:Documents/EdgePulse").build()
        LocalSafFolder.requireLocalTree(tree("com.android.externalstorage.documents"))
        assertThrows(IllegalArgumentException::class.java) { LocalSafFolder.requireLocalTree(tree("cloud.example.documents")) }
    }
    @Test fun keystoreP256SignaturesVerifyAndRotationNeedsTheOutgoingKey() {
        val aliasA = "edgepulse.test.audit.${UUID.randomUUID()}"
        val aliasB = "edgepulse.test.audit.${UUID.randomUUID()}"
        try {
            val old = KeystoreAuditSigner(aliasA).apply { enroll() }
            val replacement = KeystoreAuditSigner(aliasB).apply { enroll() }
            fun event(sequence: Long, total: Long) = AuditPayload("EVENT", "test-session", sequence, "test-boot",
                sequence * 100, 1_800_000_000_000 + sequence * 100, mapOf("direction" to "OUT", "bag_weight" to "1",
                    "running_out_total" to total.toString(), "track_id" to sequence.toString(), "webp_sha256" to "a".repeat(64)))
            val first = EventSignatureChain(old).append(event(1, 1))
            assertTrue(LocalAuditVerifier.verify(listOf(first), old.publicKeySpkiBase64).valid)
            assertTrue(old.certificateChainPem().contains("BEGIN CERTIFICATE"))
            val unsignedChange = EventSignatureChain(replacement, first.chainHash).append(event(2, 2))
            val invalid = LocalAuditVerifier.verify(listOf(first, unsignedChange), old.publicKeySpkiBase64)
            assertFalse(invalid.valid)
            assertEquals("PUBKEY_ROTATED_TAMPER_ALERT", invalid.keyContinuityStatus)
            val rotation = AuthorizedKeyRotationRecord.create(old, replacement.publicKeySpkiBase64, first.chainHash,
                "test-session", 2, "test-boot", 200, 1_800_000_000_200)
            val next = EventSignatureChain(replacement, rotation.chainHash).append(event(3, 2))
            val valid = LocalAuditVerifier.verify(listOf(first, rotation, next), old.publicKeySpkiBase64)
            assertTrue(valid.valid)
            assertEquals("AUTHORIZED_KEY_ROTATION", valid.keyContinuityStatus)
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(aliasA); deleteEntry(aliasB) }
        }
    }
}
