package app.tennyo.mobile.feature.local

import app.tennyo.mobile.feature.git.GitIdentity
import app.tennyo.mobile.link.store.SettingKey
import app.tennyo.mobile.link.store.get
import app.tennyo.mobile.link.store.set
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable

/** The author commits made on this device are recorded under; `null` until the user sets one. */
class GitIdentityPreferences(private val settings: Settings) {
    private val mutableIdentity = MutableStateFlow(settings[IdentityKey]?.toIdentity())
    val identity: StateFlow<GitIdentity?> = mutableIdentity.asStateFlow()

    fun save(identity: GitIdentity) {
        val stored = StoredIdentity(identity.name.trim(), identity.email.trim())
        settings[IdentityKey] = stored.takeIf { it.name.isNotEmpty() && it.email.isNotEmpty() }
        mutableIdentity.value = settings[IdentityKey]?.toIdentity()
    }

    private companion object {
        val IdentityKey = SettingKey.json("git.identity", StoredIdentity.serializer().nullable, default = null)
    }
}

@Serializable
private data class StoredIdentity(val name: String, val email: String) {
    fun toIdentity() = GitIdentity(name, email)
}
