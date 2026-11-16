package app.tennyo.mobile.link.pairing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.tennyo.mobile.link.LinkTicket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidRememberedPairStore(
    context: Context,
) : RememberedPairStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(artifact: LinkTicket): RememberedPair? {
        val companionId = artifact.companionId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val canonicalStorageKey = artifact.canonicalRememberedPairStorageKey() ?: return null
        val canonicalPrefix = preferencePrefix(canonicalStorageKey)
        val storedPair = readStoredPair(canonicalPrefix) ?: return null

        if (storedPair.expiresAtEpochMillis <= System.currentTimeMillis()) {
            removeByCompanionId(companionId)
            return null
        }
        val pairSecret = decrypt(storedPair.encryptedSecret) ?: run {
            removeByCompanionId(companionId)
            return null
        }
        return RememberedPair(
            pairId = storedPair.pairId,
            pairSecret = pairSecret,
            expiresAtEpochMillis = storedPair.expiresAtEpochMillis,
        )
    }

    override fun save(artifact: LinkTicket, material: RememberedPairMaterial) {
        val companionId = material.companionId?.trim()?.takeIf { it.isNotBlank() }
            ?: artifact.companionId?.trim()?.takeIf { it.isNotBlank() }
            ?: return
        val storageKey = companionRememberedPairStorageKey(companionId)
        val prefix = preferencePrefix(storageKey)
        val editor = preferences.edit()
            .putString("$prefix.pairId", material.pairId)
            .putString("$prefix.secret", encrypt(material.pairSecret))
            .putLong("$prefix.expiresAt", material.expiresAtEpochMillis)
            .putString("$prefix.deviceLabel", material.deviceLabel)
        editor.apply()
    }

    override fun remove(artifact: LinkTicket) {
        artifact.companionId?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::removeByCompanionId)
    }

    override fun removeByCompanionId(companionId: String) {
        removeByKey(companionRememberedPairStorageKey(companionId))
    }

    private fun removeByKey(storageKey: String) {
        val prefix = preferencePrefix(storageKey)
        preferences.edit()
            .remove("$prefix.pairId")
            .remove("$prefix.secret")
            .remove("$prefix.expiresAt")
            .remove("$prefix.deviceLabel")
            .apply()
    }

    private fun readStoredPair(prefix: String): StoredPair? {
        val pairId = preferences.getString("$prefix.pairId", null) ?: return null
        val encryptedSecret = preferences.getString("$prefix.secret", null) ?: return null
        return StoredPair(
            pairId = pairId,
            encryptedSecret = encryptedSecret,
            expiresAtEpochMillis = preferences.getLong("$prefix.expiresAt", 0L),
            deviceLabel = preferences.getString("$prefix.deviceLabel", null),
        )
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        return "${cipher.iv.base64Url()}.${ciphertext.base64Url()}"
    }

    private fun decrypt(value: String): String? =
        runCatching {
            val iv = value.substringBefore('.').base64UrlDecode()
            val ciphertext = value.substringAfter('.', "").base64UrlDecode()
            if (iv.isEmpty() || ciphertext.isEmpty()) return@runCatching null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext).decodeToString()
        }.getOrNull()

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun preferencePrefix(storageKey: String): String =
        "pair.${storageKey.sha256Hex()}"

    private fun String.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

    private fun ByteArray.base64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private fun String.base64UrlDecode(): ByteArray {
        val standard = replace('-', '+').replace('_', '/')
        val padded = standard + "=".repeat((4 - standard.length % 4) % 4)
        return Base64.getDecoder().decode(padded)
    }

    private companion object {
        private const val PREFERENCES_NAME = "link_pairs"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "link_pair_store_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val AES_KEY_BITS = 256
        private const val BYTE_MASK = 0xff
    }
}

private data class StoredPair(
    val pairId: String,
    val encryptedSecret: String,
    val expiresAtEpochMillis: Long,
    val deviceLabel: String?,
)
