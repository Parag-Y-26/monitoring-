package com.edgepulse.monitor

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edgepulse.core.audit.LocalAuditVerifier
import com.edgepulse.monitor.data.local.*
import com.edgepulse.monitor.security.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuditStorageInstrumentedTest {
    private class ScratchContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(root, name)
    }

    @Test fun signedEncryptedEventAndGapSurviveRepositoryRecreation() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "audit-storage-${UUID.randomUUID()}").apply { mkdirs() }
        val context = ScratchContext(base, root)
        val wrapAlias = "edgepulse.test.wrap.${UUID.randomUUID()}"
        val signAlias = "edgepulse.test.sign.${UUID.randomUUID()}"
        val replacementAlias = "edgepulse.test.sign.${UUID.randomUUID()}"
        val keys = TinkKeyManager(context, wrapAlias)
        val settings = SecureSettingsStore(context, keys)
        val signer = KeystoreAuditSigner(signAlias).apply { enroll() }
        val db = EdgePulseDatabase.open(context, settings)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            db.monitoringDao().insertSession(MonitoringSessionEntity("session", "CEMENT", boot_id = "boot", pubkey_spki_b64 = signer.publicKeySpkiBase64,
                attestation_cert_pem = signer.certificateChainPem(), attestation_nonce_mode = "NONE", key_continuity_status = "CONTINUOUS_VERIFIED",
                started_elapsed_ms = 1, started_wall_ms = 1_800_000_000_000))
            val archive = WebpCircularArchiver(context, db, keys.streamingAead)
            val repository = MonitoringAuditRepository(db, archive, signer, signer.publicKeySpkiBase64)
            repository.recordCrossing("session", 1, "OUT", 2, bitmap, 100, 1_800_000_000_100)
            repository.recordGap("session", "OS_KILL_DETECTED", 200, 500, 1_800_000_000_200, 1_800_000_000_500)
            val recreated = MonitoringAuditRepository(db, archive, signer, signer.publicKeySpkiBase64)
            recreated.recordCrossing("session", 2, "IN", 1, bitmap, 600, 1_800_000_000_600)
            val records = recreated.records()
            val verified = LocalAuditVerifier.verify(records, signer.publicKeySpkiBase64)
            assertTrue(verified.valid)
            assertEquals(2L, verified.verifiedOutCount)
            assertEquals(1L, verified.verifiedInCount)
            assertEquals(300L, verified.gapDurationMs)
            assertEquals(3, records.size)
            assertEquals(2L, db.monitoringDao().session("session")?.total_out_count)
            assertTrue(db.monitoringDao().archiveBytes() > 0)
            val replacement = KeystoreAuditSigner(replacementAlias).apply { enroll() }
            recreated.authorizeRotation("session", replacement.publicKeySpkiBase64, 700, 1_800_000_000_700)
            val nextSession = checkNotNull(db.monitoringDao().session("session")).copy(session_id = "next-session",
                pubkey_spki_b64 = replacement.publicKeySpkiBase64, authorized_rotation_records_json = "[]",
                started_elapsed_ms = 800, total_out_count = 0, total_in_count = 0)
            db.monitoringDao().insertSession(nextSession)
            val replacementRepository = MonitoringAuditRepository(db, archive, replacement, signer.publicKeySpkiBase64)
            replacementRepository.recordCrossing("next-session", 1, "OUT", 1, bitmap, 900, 1_800_000_000_900)
            val rotated = LocalAuditVerifier.verify(replacementRepository.records(), signer.publicKeySpkiBase64)
            assertTrue(rotated.valid)
            assertEquals("AUTHORIZED_KEY_ROTATION", rotated.keyContinuityStatus)
            assertEquals(3L, rotated.verifiedOutCount)
        } finally {
            bitmap.recycle(); db.close(); settings.close()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(wrapAlias); deleteEntry(signAlias); deleteEntry(replacementAlias) }
            check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }
}
