package it.drone.mesh.security

import android.content.Context
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.hybrid.HybridKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.signature.SignatureKeyTemplates
import java.security.GeneralSecurityException
import java.security.MessageDigest

class SharedPreferencesMeshPeerTrustStore(context: Context) : MeshPeerTrustStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        TRUST_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    override fun trustOrVerify(peerId: String, publicIdentityKeyset: ByteArray) {
        val fingerprint = fingerprint(publicIdentityKeyset)
        val preferenceKey = TRUST_KEY_PREFIX + peerId
        val existing = preferences.getString(preferenceKey, null)
        when {
            existing == null -> {
                if (!preferences.edit().putString(preferenceKey, fingerprint).commit()) {
                    throw GeneralSecurityException("Unable to persist gateway identity")
                }
            }

            existing != fingerprint -> throw GeneralSecurityException(
                "Gateway identity changed for mesh node $peerId",
            )
        }
    }

    private fun fingerprint(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val TRUST_PREFERENCES = "mesh_peer_trust"
        const val TRUST_KEY_PREFIX = "gateway_identity_"
    }
}

object AndroidMeshSecurity {
    private const val KEYSET_PREFERENCES = "mesh_security_keysets"
    private const val HYBRID_KEYSET_NAME = "gateway_hybrid_keyset"
    private const val SIGNING_KEYSET_NAME = "gateway_signing_keyset"
    private const val MASTER_KEY_URI = "android-keystore://blufinet_tink_master_key"

    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun create(context: Context): SecureMeshProtocol {
        TinkConfig.register()
        val applicationContext = context.applicationContext
        val hybridManager = AndroidKeysetManager.Builder()
            .withSharedPref(applicationContext, HYBRID_KEYSET_NAME, KEYSET_PREFERENCES)
            .withKeyTemplate(HybridKeyTemplates.ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM)
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
        val signingManager = AndroidKeysetManager.Builder()
            .withSharedPref(applicationContext, SIGNING_KEYSET_NAME, KEYSET_PREFERENCES)
            .withKeyTemplate(SignatureKeyTemplates.ECDSA_P256)
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
        requireHardwareProtectedStorage(hybridManager, signingManager)
        return SecureMeshProtocol(
            hybridManager.keysetHandle,
            signingManager.keysetHandle,
            SharedPreferencesMeshPeerTrustStore(applicationContext),
        )
    }

    private fun requireHardwareProtectedStorage(vararg managers: AndroidKeysetManager) {
        if (managers.any { !it.isUsingKeystore }) {
            throw GeneralSecurityException("Android Keystore is unavailable; secure mesh is disabled")
        }
    }
}