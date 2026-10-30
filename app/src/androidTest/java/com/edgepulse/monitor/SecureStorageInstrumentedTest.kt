package com.edgepulse.monitor

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edgepulse.monitor.data.local.*
import com.edgepulse.monitor.security.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.edgepulse.core.cloud.CloudProvider
import com.edgepulse.core.cloud.ProviderSetup
import com.edgepulse.core.cloud.CloudModel
import com.edgepulse.core.cloud.CloudResponse
import com.edgepulse.core.cloud.ApiUsage
import com.edgepulse.core.vision.ItemProfile
import kotlinx.serialization.json.Json
import java.net.URI
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.Environment

@RunWith(AndroidJUnit4::class)
class SecureStorageInstrumentedTest {
    @Test fun cachedModelsAndSelectionSurviveOfflineReopen() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "catalog-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = ScratchContext(base, root)
        val alias = "edgepulse.test.catalog.${UUID.randomUUID()}"
        var settings = SecureSettingsStore(context, TinkKeyManager(context, alias))
        try {
            val models = listOf(CloudModel("owner/image", "Image model", true, 1000), CloudModel("owner/text", "Text model", false))
            val setup = ProviderSetup(CloudProvider.OPENROUTER, URI("https", "example.invalid", "/key", null).toString(), "owner/image")
            val key = "fixture-credential".toByteArray()
            val repository = ApiKeyRepository(settings)
            assertTrue(repository.validateAndStore(setup, key, models) { true })
            assertTrue(key.all { it == 0.toByte() })
            repository.selectModel(CloudProvider.OPENROUTER, "owner/text")
            var invalidSelectionRejected = false
            try { repository.selectModel(CloudProvider.OPENROUTER, "invented-model") }
            catch (_: IllegalArgumentException) { invalidSelectionRejected = true }
            assertTrue(invalidSelectionRejected)
            val profile = ItemProfile(UUID.randomUUID().toString(), "Fixture bottle", classId = 39)
            settings.dataStore.updateData { it.copy(itemProfiles = listOf(profile), selectedItemId = profile.id) }
            ApiUsageRepository(settings).record(CloudProvider.OPENROUTER, "ITEM_PHOTO_DESCRIPTION", "owner/image", 1,
                CloudResponse(200, """{"usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}"""), "HTTP_200")
            settings.close()
            settings = SecureSettingsStore(context, TinkKeyManager(context, alias))
            val state = settings.dataStore.data.first()
            assertEquals(CloudProvider.OPENROUTER, state.activeCloudProvider)
            val cached = ApiKeyRepository(settings).connections(state).single()
            assertEquals(models, cached.models)
            assertEquals("owner/text", cached.model)
            assertTrue(cached.fetchedWallMs > 0)
            assertTrue(settings.isUnlockedOffline())
            assertEquals(profile, state.itemProfiles.single())
            assertEquals(profile.id, state.selectedItemId)
            val usage = Json.decodeFromString<List<ApiUsage>>(state.apiUsageJson).single()
            assertEquals(20L, usage.totalTokens); assertEquals(1, usage.images)
            ApiKeyRepository(settings).clear(CloudProvider.OPENROUTER)
            assertFalse(settings.isUnlockedOffline())
        } finally {
            settings.close()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }
    @Test fun itemReferenceIsEncryptedAndRejectsWholeFileTampering(): Unit = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "reference-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = ScratchContext(base, root)
        val alias = "edgepulse.test.reference.${UUID.randomUUID()}"
        val store = ReferenceImageStore(context, TinkKeyManager(context, alias))
        val bitmap = Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            val name = store.save(bitmap)
            val file = File(context.noBackupFilesDir,"item-references/$name")
            assertFalse(file.readBytes().take(4).toByteArray().toString(Charsets.US_ASCII) == "RIFF")
            store.load(name).also { assertEquals(32,it.width); it.recycle() }
            val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            assertThrows(Exception::class.java) { store.load(name) }
            assertThrows(IllegalArgumentException::class.java) { store.load("../outside") }
        } finally {
            bitmap.recycle(); KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile); root.deleteRecursively()
        }
    }
    @Test fun bundledLiteRtRuntimeLoadsWithoutModelDelivery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Environment.create(context).use { environment ->
            assertTrue(Accelerator.CPU in environment.getAvailableAccelerators())
        }
    }
    private class ScratchContext(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(directory, "no_backup").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(directory, name)
    }

    @Test fun databaseAndSettingsAreEncryptedAndOfflineUnlockSurvivesReopen() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "storage-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = ScratchContext(base, root)
        val alias = "edgepulse.test.${UUID.randomUUID()}"
        val marker = "SENSITIVE_TEST_MARKER_${UUID.randomUUID()}"
        val keys = TinkKeyManager(context, alias)
        var settings = SecureSettingsStore(context, keys)
        var db: EdgePulseDatabase? = null
        try {
            db = EdgePulseDatabase.open(context, settings)
            val names = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertTrue(names.containsAll(setOf("monitoring_sessions", "inventory_events", "monitoring_gaps", "archive_index")))
            assertEquals(4, names.count { it !in setOf("room_master_table", "android_metadata", "sqlite_sequence") })
            db.monitoringDao().insertSession(MonitoringSessionEntity("storage", boot_id = "test", pubkey_spki_b64 = marker,
                attestation_cert_pem = "", attestation_nonce_mode = "NONE", key_continuity_status = "UNVERIFIED",
                started_elapsed_ms = 1, started_wall_ms = 1))
            val supplied = marker.toByteArray()
            val setup = ProviderSetup(CloudProvider.GEMINI, URI("https", "example.invalid", "/validate", null).toString())
            assertTrue(ApiKeyRepository(settings).validateAndStore(setup, supplied) { it.contentEquals(marker.toByteArray()) })
            assertTrue(supplied.all { it == 0.toByte() })
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
            db.close(); db = null
            for (file in root.walkTopDown().filter { it.isFile }) {
                assertFalse("Plaintext leaked to ${file.name}", file.readBytes().toString(Charsets.ISO_8859_1).contains(marker))
            }
            val databaseFile = context.getDatabasePath("edgepulse.db")
            assertFalse(databaseFile.readBytes().take(16).toByteArray().toString(Charsets.ISO_8859_1).startsWith("SQLite format 3"))
            // Android's corruption handler may delete a non-SQLite file; probe a disposable copy.
            val nativeProbe = File(root, "native-probe.db")
            databaseFile.copyTo(nativeProbe)
            assertThrows(android.database.sqlite.SQLiteException::class.java) {
                android.database.sqlite.SQLiteDatabase.openDatabase(nativeProbe.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {
                    it.rawQuery("SELECT name FROM sqlite_master", null).use { cursor -> cursor.moveToFirst() }
                }
            }
            settings.close()
            settings = SecureSettingsStore(context, TinkKeyManager(context, alias))
            assertTrue(settings.isUnlockedOffline())
            db = EdgePulseDatabase.open(context, settings)
            assertEquals(marker, db.monitoringDao().session("storage")?.pubkey_spki_b64)
        } finally {
            db?.close(); settings.close()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }

    @Test fun encryptedStillImagesRoundTripRejectTamperingAndEvictOldest() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "archive-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = ScratchContext(base, root)
        val alias = "edgepulse.test.${UUID.randomUUID()}"
        val keys = TinkKeyManager(context, alias)
        val settings = SecureSettingsStore(context, keys)
        val db = EdgePulseDatabase.open(context, settings)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        try {
            val dao = db.monitoringDao()
            dao.insertSession(MonitoringSessionEntity("session", boot_id = "test", pubkey_spki_b64 = "test",
                attestation_cert_pem = "", attestation_nonce_mode = "NONE", key_continuity_status = "UNVERIFIED",
                started_elapsed_ms = 1, started_wall_ms = 1))
            val archive = WebpCircularArchiver(context, db, keys.streamingAead, quotaBytes = 4_000)
            var sequence = 0L
            suspend fun write() = archive.archive(bitmap) { value ->
                sequence++
                dao.insertEvent(InventoryEventEntity(session_id = "session", track_id = sequence, direction = "OUT", bag_weight = 1,
                    running_out_total = sequence, enc_webp_path = value.filename, webp_sha256 = value.plaintextSha256,
                    monotonic_elapsed_ms = sequence, wall_clock_ms = sequence, prev_chain_hash = "0".repeat(64),
                    chain_hash = "a".repeat(64), ecdsa_sig_b64 = "test", audit_sequence = sequence, audit_record_json = "test"))
            }
            val first = write()
            val plaintext = archive.read(first.filename) { it.readBytes() }
            assertEquals("RIFF", plaintext.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            val file = File(context.noBackupFilesDir, "keyframes/${first.filename}")
            assertFalse(file.readBytes().take(4).toByteArray().contentEquals(plaintext.take(4).toByteArray()))
            val second = write()
            assertFalse(file.exists())
            assertTrue(dao.archiveBytes() <= 4_000)
            assertEquals(second.filename, dao.oldestArchive()?.enc_webp_path)
            val secondFile = File(context.noBackupFilesDir, "keyframes/${second.filename}")
            val encrypted = secondFile.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            secondFile.writeBytes(encrypted)
            var rejected = false
            try { archive.read(second.filename) { it.readBytes() } } catch (_: Exception) { rejected = true }
            assertTrue("Modified ciphertext must fail authentication", rejected)
        } finally {
            bitmap.recycle(); db.close(); settings.close()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }
}
