package com.edgepulse.monitor.data.local

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.room.withTransaction
import com.google.crypto.tink.StreamingAead
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import com.edgepulse.core.audit.AuditCanonical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ArchivedKeyframe(val filename: String, val encryptedBytes: Long, val plaintextSha256: String)

/** Stores encrypted still images only. The callback commits the signed event/observation atomically with its index. */
class WebpCircularArchiver(
    context: Context,
    private val database: EdgePulseDatabase,
    private val streamingAead: StreamingAead,
    private val quotaBytes: Long = 200_000_000L,
) {
    private val directory = File(context.noBackupFilesDir, "keyframes").apply { check(mkdirs() || isDirectory) }
    private val mutex = Mutex()
    private var recovered = false
    private val namePattern = Regex("[0-9a-f-]{36}\\.webp\\.enc")
    private val maxKeyframeBytes = minOf(20_000_000L, quotaBytes)
    private val dao get() = database.monitoringDao()

    init { require(quotaBytes > 0) }

    suspend fun archive(
        bitmap: Bitmap,
        sourceTable: String,
        persistSource: suspend (ArchivedKeyframe) -> Long,
    ): ArchivedKeyframe = withContext(Dispatchers.IO) {
        require(sourceTable == "EVENT" || sourceTable == "OBS")
        require(bitmap.width in 1..4096 && bitmap.height in 1..4096)
        mutex.withLock {
            if (!recovered) { recoverLocked(); recovered = true }
            pruneTo(quotaBytes - maxKeyframeBytes)
            val name = "${UUID.randomUUID()}.webp.enc"
            val staged = File(directory, "$name.part")
            val target = checkedFile(name)
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                FileOutputStream(staged).use { raw ->
                    // The AEAD stream closes its wrapper; retain the file descriptor for fsync.
                    val limited = LimitedOutputStream(raw, maxKeyframeBytes)
                    streamingAead.newEncryptingStream(limited, associatedData(name)).use { encrypted ->
                        DigestOutputStream(encrypted, digest).use { plaintext ->
                            val format = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                            check(bitmap.compress(format, 80, plaintext)) { "WebP encoding failed" }
                        }
                    }
                    raw.fd.sync()
                }
                check(staged.renameTo(target)) { "Atomic keyframe rename failed" }
                val archived = ArchivedKeyframe(name, target.length(), AuditCanonical.hex(digest.digest()))
                database.withTransaction {
                    val sourceId = persistSource(archived)
                    require(sourceId > 0)
                    dao.insertArchive(ArchiveIndexEntity(name, archived.encryptedBytes,
                        SystemClock.elapsedRealtime(), dao.nextArchiveOrder(), sourceTable, sourceId))
                    check(dao.archiveBytes() <= quotaBytes)
                }
                archived
            } catch (failure: Exception) {
                staged.delete()
                target.delete()
                throw failure
            }
        }
    }

    /** The plaintext stream is scoped and never written to disk. */
    suspend fun <T> read(filename: String, consume: (InputStream) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkedFile(filename).inputStream().use { raw ->
                streamingAead.newDecryptingStream(raw, associatedData(filename)).use { decrypted ->
                    val result = consume(decrypted)
                    val scratch = ByteArray(16 * 1024)
                    while (decrypted.read(scratch) >= 0) { /* Authenticate the full stream, including its final segment. */ }
                    result
                }
            }
        }
    }

    suspend fun recover() = withContext(Dispatchers.IO) { mutex.withLock { recoverLocked() } }

    private suspend fun recoverLocked() {
        val indexed = dao.activeArchives().associateBy { it.enc_webp_path }
        for (file in directory.listFiles().orEmpty()) {
            val valid = namePattern.matches(file.name)
            val partial = file.name.endsWith(".webp.enc.part") && namePattern.matches(file.name.removeSuffix(".part"))
            if (partial || (valid && file.name !in indexed)) check(file.delete()) { "Orphan cleanup failed" }
        }
        for (entry in indexed.values) {
            if (!checkedFile(entry.enc_webp_path).exists()) markPurged(entry.enc_webp_path)
        }
        pruneTo(quotaBytes)
    }

    private suspend fun pruneTo(limit: Long) {
        while (dao.archiveBytes() > limit) {
            val oldest = checkNotNull(dao.oldestArchive())
            val file = checkedFile(oldest.enc_webp_path)
            check(!file.exists() || file.delete()) { "Archive deletion failed; quota enforcement blocked" }
            markPurged(oldest.enc_webp_path)
        }
    }

    private suspend fun markPurged(name: String) = database.withTransaction {
        dao.markArchivePurged(name)
        dao.markEventPurged(name)
        dao.markObservationPurged(name)
        dao.clearPlantThumbnail(name)
    }

    private fun checkedFile(name: String): File {
        require(namePattern.matches(name)) { "Invalid archive identifier" }
        val file = File(directory, name).canonicalFile
        check(file.parentFile == directory.canonicalFile)
        return file
    }

    private fun associatedData(name: String) = "EdgePulse/keyframe/v1/$name".toByteArray()

    private class LimitedOutputStream(out: OutputStream, private val limit: Long) : FilterOutputStream(out) {
        private var count = 0L
        override fun write(value: Int) { require(count + 1 <= limit); out.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(count + length <= limit)
            out.write(bytes, offset, length); count += length
        }
        override fun close() { flush() }
    }
}
