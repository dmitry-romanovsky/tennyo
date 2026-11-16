package app.tennyo.host.intellij

import app.tennyo.host.engine.pairing.PersistentPairStore
import app.tennyo.host.engine.pairing.RememberedPairRecord
import app.tennyo.host.engine.rpc.HostJson
import app.tennyo.host.engine.rpc.hostJson
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable

internal fun globalRememberedPairAttributes(): CredentialAttributes =
    hostCredentialAttributes("rememberedPairs")

/** Remembered pairs in the IDE's own credential store, which is where a user expects to revoke them. */
internal class PasswordSafePairStore(
    private val attributes: CredentialAttributes,
) : PersistentPairStore() {
    override fun load(): List<RememberedPairRecord> {
        val stored = PasswordSafe.instance[attributes]?.password?.toString() ?: return emptyList()
        return runCatching {
            HostJson.decodeFromString<RememberedPairStoreSnapshot>(stored).records
        }.onFailure { LOG.warn("Unable to read Tennyo Companion remembered pair records", it) }
            .getOrDefault(emptyList())
    }

    override fun store(records: List<RememberedPairRecord>) {
        PasswordSafe.instance[attributes] = attributes.credentials(hostJson(RememberedPairStoreSnapshot(records)))
    }

    private companion object {
        private val LOG = Logger.getInstance(PasswordSafePairStore::class.java)
    }
}

@Serializable
private data class RememberedPairStoreSnapshot(
    val records: List<RememberedPairRecord> = emptyList(),
)
