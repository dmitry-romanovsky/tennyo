package app.tennyo.mobile.link.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-backed, no-backup storage for the one reconnect credential. */
class AndroidSecureCredentialStorage(context: Context) : SecureCredentialStorage {
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)
    private val activeFile = File(directory, FILE_NAME)
    private val temporaryFile = File(directory, "$FILE_NAME.tmp")

    override suspend fun readActive(): CredentialReadResult = withContext(Dispatchers.IO) {
        runCatching {
            if (!activeFile.exists()) return@runCatching CredentialReadResult.NotFound
            val bytes = activeFile.readBytes()
            if (bytes.size <= IV_SIZE_BYTES) return@runCatching CredentialReadResult.Corrupt
            val plaintext = cipher(Cipher.DECRYPT_MODE, bytes.copyOfRange(0, IV_SIZE_BYTES))
                .doFinal(bytes.copyOfRange(IV_SIZE_BYTES, bytes.size))
            CredentialReadResult.Found(plaintext.decodeToString())
        }.getOrElse { error ->
            if (error.isCorruption()) CredentialReadResult.Corrupt
            else CredentialReadResult.IoFailure
        }
    }

    override suspend fun write(record: String): CredentialWriteResult = withContext(Dispatchers.IO) {
        runCatching {
            if (!directory.exists() && !directory.mkdirs()) return@runCatching CredentialWriteResult.IoFailure
            val encryptingCipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
                updateAAD(AAD)
            }
            val encrypted = encryptingCipher.doFinal(record.encodeToByteArray())
            FileOutputStream(temporaryFile).use { output ->
                output.write(encryptingCipher.iv)
                output.write(encrypted)
                output.fd.sync()
            }
            if (!temporaryFile.renameTo(activeFile)) {
                throw java.io.IOException("Unable to atomically replace remote credential")
            }
            CredentialWriteResult.Stored
        }.getOrElse {
            temporaryFile.delete()
            CredentialWriteResult.IoFailure
        }
    }

    override suspend fun clear(): CredentialClearResult = withContext(Dispatchers.IO) {
        runCatching {
            temporaryFile.delete()
            if (activeFile.exists() && !activeFile.delete()) CredentialClearResult.IoFailure
            else CredentialClearResult.Cleared
        }.getOrElse { CredentialClearResult.IoFailure }
    }

    private fun cipher(mode: Int, iv: ByteArray): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(mode, secretKey(), GCMParameterSpec(TAG_SIZE_BITS, iv))
        updateAAD(AAD)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AES_KEY_BITS)
                    .build(),
            )
        }.generateKey()
    }

    private fun Throwable.isCorruption(): Boolean =
        this is javax.crypto.AEADBadTagException ||
                this is java.security.GeneralSecurityException ||
                cause?.isCorruption() == true

    private companion object {
        const val DIRECTORY_NAME = "remote-credentials"
        const val FILE_NAME = "active.v1"
        const val KEY_ALIAS = "link_credential_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE_BYTES = 12
        const val TAG_SIZE_BITS = 128
        const val AES_KEY_BITS = 256
        val AAD = "tennyo.remote-credential:1".encodeToByteArray()
    }
}
