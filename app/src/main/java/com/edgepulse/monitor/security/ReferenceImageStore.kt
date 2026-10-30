package com.edgepulse.monitor.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.edgepulse.core.vision.ItemProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Retained encrypted setup references; separate from the 200 MB circular event-evidence archive. */
@Singleton
class ReferenceImageStore @Inject constructor(@ApplicationContext context: Context, private val keys: TinkKeyManager) {
    private val directory = File(context.noBackupFilesDir, "item-references").apply { check(mkdirs() || isDirectory) }
    private fun file(name: String): File {
        require(name.matches(Regex("[0-9a-f-]{36}\\.webp\\.enc")))
        return File(directory, name)
    }
    suspend fun save(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        require(bitmap.width in 1..640 && bitmap.height in 1..640)
        check(directory.listFiles().orEmpty().sumOf { it.length() } <= 11_000_000L) { "Remove unused item references first" }
        val name = "${UUID.randomUUID()}.webp.enc"
        val temporary = File(directory, "$name.part")
        try {
            FileOutputStream(temporary).use { raw ->
                keys.streamingAead.newEncryptingStream(raw, "EdgePulse/reference/v1/$name".toByteArray()).use { encrypted ->
                    val format = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                    check(bitmap.compress(format, 90, encrypted))
                }
            }
            check(temporary.length() in 1..1_000_000)
            check(temporary.renameTo(file(name)))
            name
        } finally { temporary.delete() }
    }
    fun load(name: String): Bitmap = file(name).also { require(it.length() in 1..1_000_000) }.inputStream().use { input ->
        keys.streamingAead.newDecryptingStream(input, "EdgePulse/reference/v1/$name".toByteArray()).use { plaintext ->
            val bytes = plaintext.readBytes()
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                require(options.outWidth in 1..640 && options.outHeight in 1..640)
                checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            } finally { bytes.fill(0) }
        }
    }
    fun remove(name: String) { file(name).delete() }
}
