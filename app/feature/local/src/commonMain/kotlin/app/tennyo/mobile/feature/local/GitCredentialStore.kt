package app.tennyo.mobile.feature.local

import app.tennyo.mobile.feature.git.GitCredentials
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.coroutines.CoroutineContext

/** HTTPS credentials for Git remotes, one user name and secret per host. */
interface GitCredentialStore {
    /** The hosts that have credentials, sorted. */
    val hosts: Flow<List<String>>

    suspend fun get(host: String): GitCredentials?

    suspend fun save(host: String, credentials: GitCredentials)

    suspend fun delete(host: String)
}

/** Encrypts and decrypts one secret blob with a key the platform keeps out of the app's files. */
interface SecretCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * [GitCredentialStore] as a single file encrypted through [cipher]. A file that no longer decrypts (the key was lost,
 * say, after a restore to another device) reads as no credentials; the user is asked again.
 */
class EncryptedGitCredentialStore(
    private val fileSystem: FileSystem,
    private val file: Path,
    private val cipher: SecretCipher,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : GitCredentialStore {
    private val mutex = Mutex()
    private val entries = MutableStateFlow<Map<String, StoredCredentials>?>(null)

    override val hosts: Flow<List<String>> = flow {
        loaded()
        emitAll(entries.map { it.orEmpty().keys.sorted() })
    }

    override suspend fun get(host: String): GitCredentials? =
        loaded()[host.lowercase()]?.let { GitCredentials(it.username, it.secret) }

    override suspend fun save(host: String, credentials: GitCredentials) = mutex.withLock {
        write(loadedLocked() + (host.lowercase() to StoredCredentials(credentials.username, credentials.secret)))
    }

    override suspend fun delete(host: String) = mutex.withLock {
        write(loadedLocked() - host.lowercase())
    }

    private suspend fun loaded(): Map<String, StoredCredentials> = entries.value ?: mutex.withLock { loadedLocked() }

    private suspend fun loadedLocked(): Map<String, StoredCredentials> = entries.value ?: withContext(ioContext) {
        if (!fileSystem.exists(file)) return@withContext emptyMap()
        @Suppress("TooGenericExceptionCaught") // A platform cipher reports a bad key or tag with its own exceptions.
        try {
            val plaintext = cipher.decrypt(fileSystem.read(file) { readByteArray() })
            CredentialsJson.decodeFromString(CredentialFile.serializer(), plaintext.decodeToString()).hosts
        } catch (failure: Exception) {
            Logger.w(failure) { "Stored Git credentials could not be read; starting without them" }
            emptyMap()
        }
    }.also { entries.value = it }

    private suspend fun write(updated: Map<String, StoredCredentials>) {
        withContext(ioContext) {
            val temporary = file.parent?.div("${file.name}.tmp") ?: file
            try {
                file.parent?.let(fileSystem::createDirectories)
                val plaintext = CredentialsJson.encodeToString(CredentialFile.serializer(), CredentialFile(updated))
                fileSystem.write(temporary) { write(cipher.encrypt(plaintext.encodeToByteArray())) }
                if (temporary != file) fileSystem.atomicMove(temporary, file)
            } catch (failure: IOException) {
                fileSystem.delete(temporary, mustExist = false)
                throw failure
            }
        }
        entries.value = updated
    }
}

@Serializable
private data class CredentialFile(val hosts: Map<String, StoredCredentials>)

@Serializable
private data class StoredCredentials(val username: String, val secret: String)

private val CredentialsJson = Json { ignoreUnknownKeys = true }
