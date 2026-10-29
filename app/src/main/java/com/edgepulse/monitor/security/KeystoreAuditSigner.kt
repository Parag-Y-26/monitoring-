package com.edgepulse.monitor.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.edgepulse.core.audit.AuditSigner
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class KeystoreAuditSigner(private val alias: String = "edgepulse.audit.v1") : AuditSigner {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** Challenge is provided by a verifier; null means no attestation claim at all. */
    fun enroll(verifierChallenge: ByteArray? = null) {
        check(!store.containsAlias(alias)) { "Audit identity already exists" }
        verifierChallenge?.let { require(it.size in 16..128) }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
        verifierChallenge?.let { spec.setAttestationChallenge(it) }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(spec.build()); generateKeyPair()
        }
    }

    fun isEnrolled(): Boolean = store.containsAlias(alias)
    override val publicKeySpkiBase64: String
        get() = Base64.getEncoder().encodeToString(checkNotNull(store.getCertificate(alias)).publicKey.encoded)

    override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(store.getKey(alias, null) as java.security.PrivateKey)
        update(message)
        sign()
    }

    fun certificateChainPem(): String = checkNotNull(store.getCertificateChain(alias)).joinToString("\n") {
        "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(it.encoded) + "\n-----END CERTIFICATE-----"
    }
}
