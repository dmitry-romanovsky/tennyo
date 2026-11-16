package app.tennyo.mobile.feature.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.tennyo.mobile.feature.git.GitRepositorySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.eclipse.jgit.api.Git
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The local workspace on Android. Workspaces and their installed Python packages live in app-private storage
 * (`filesDir/workspaces`, `filesDir/python-packages`), so no storage permission is involved and uninstalling the
 * app removes them; Git credentials sit outside backups.
 */
val localWorkspaceModule = module {
    single {
        val filesDir = androidContext().filesDir.toOkioPath()
        WorkspaceFiles(FileSystem.SYSTEM, filesDir / WORKSPACES_DIRECTORY, filesDir / PACKAGES_DIRECTORY)
    }
    single<LocalWorkspaceStore> {
        JsonLocalWorkspaceStore(FileSystem.SYSTEM, androidContext().filesDir.toOkioPath() / WORKSPACE_INDEX_FILE, get())
    }
    single<GitCredentialStore> {
        EncryptedGitCredentialStore(
            fileSystem = FileSystem.SYSTEM,
            file = androidContext().noBackupFilesDir.toOkioPath() / GIT_CREDENTIALS_FILE,
            cipher = KeystoreSecretCipher(GIT_CREDENTIAL_KEY_ALIAS),
        )
    }
    single { GitIdentityPreferences(get()) }
    single { JGitTransferExecutor(get(), get()) }
    single<GitTransferScheduler> { WorkManagerGitTransferScheduler(androidContext(), get()) }
    single<LocalGitRepositories> { JGitLocalRepositories(get(), get(), get()) }
    // The runner comes from the application shell, which alone can carry the Python runtime.
    single<LocalWorkspaceEntry> {
        RealLocalWorkspaceEntry(get(), get(), get(), get(), get(), get(), getOrNull<PythonRunner>())
    }
}

class JGitLocalRepositories(
    private val files: WorkspaceFiles,
    private val identity: GitIdentityPreferences,
    private val transfers: GitTransferScheduler,
) : LocalGitRepositories {
    override fun source(workspaceSlug: String): GitRepositorySource = JGitRepositorySource(
        workspaceSlug = workspaceSlug,
        directory = files.root(workspaceSlug).toFile(),
        identity = { identity.identity.value },
        saveIdentity = identity::save,
        transfers = transfers,
    )

    override suspend fun init(workspaceSlug: String) = withContext(Dispatchers.IO) {
        Git.init().setDirectory(files.root(workspaceSlug).toFile()).call().close()
    }
}

/** AES-GCM with a non-exportable Android Keystore key under [alias]; the IV is stored in front of the ciphertext. */
class KeystoreSecretCipher(private val alias: String) : SecretCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(AAD)
        }
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > IV_SIZE_BYTES) { "Ciphertext is too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_SIZE_BITS, ciphertext, 0, IV_SIZE_BYTES))
            updateAAD(AAD)
        }
        return cipher.doFinal(ciphertext, IV_SIZE_BYTES, ciphertext.size - IV_SIZE_BYTES)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE_BYTES = 12
        const val TAG_SIZE_BITS = 128
        const val KEY_SIZE_BITS = 256
        val AAD = "tennyo.git-credentials:1".encodeToByteArray()
    }
}

private const val WORKSPACES_DIRECTORY = "workspaces"
private const val PACKAGES_DIRECTORY = "python-packages"
private const val WORKSPACE_INDEX_FILE = "workspace-index.json"
private const val GIT_CREDENTIALS_FILE = "git-credentials.bin"
private const val GIT_CREDENTIAL_KEY_ALIAS = "git_credential_key"
