package com.edgepulse.monitor.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore failures are fatal; there is no plaintext keyset fallback. */
class TinkKeyManager(context: Context, private val alias: String = "edgepulse.wrapping.v1") {
    private val directory = File(context.noBackupFilesDir, "keysets").apply { mkdirs() }
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val master: Aead

    init {
        AeadConfig.register()
        StreamingAeadConfig.register()
        if (!keyStore.containsAlias(alias)) {
            check(directory.listFiles().isNullOrEmpty()) { "Keystore key missing for existing encrypted state" }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).build())
                generateKey()
            }
        }
        master = KeystoreAead(keyStore.getKey(alias, null) as SecretKey)
    }

    val aead: Aead by lazy { keyset("settings", "AES256_GCM").getPrimitive(RegistryConfiguration.get(), Aead::class.java) }
    val streamingAead: StreamingAead by lazy {
        keyset("archive", "AES256_GCM_HKDF_4KB").getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    @Synchronized
    private fun keyset(name: String, template: String): KeysetHandle {
        val atomic = AtomicFile(File(directory, "$name.keyset.enc"))
        val aad = "EdgePulse/keyset/v1/$name".toByteArray()
        if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()) {
            return atomic.openRead().use { TinkProtoKeysetFormat.parseEncryptedKeyset(it.readBytes(), master, aad, RegistryConfiguration.get()) }
        }
        val handle = KeysetHandle.generateNew(KeyTemplates.get(template))
        val output = atomic.startWrite()
        try {
            output.write(TinkProtoKeysetFormat.serializeEncryptedKeyset(handle, master, aad, RegistryConfiguration.get()))
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
        return handle
    }

    private class KeystoreAead(private val key: SecretKey) : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(associatedData)
            return cipher.iv + cipher.doFinal(plaintext)
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            if (ciphertext.size < 28) throw GeneralSecurityException("Truncated ciphertext")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext, 0, 12))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
        }
    }
}
