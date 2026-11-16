package app.tennyo.host.intellij

import app.tennyo.host.engine.discovery.HostIdentityStore
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe

internal class PasswordSafeIdentityStore(
    private val attributes: CredentialAttributes = persistentIdentityAttributes(),
) : HostIdentityStore {
    override fun load(): String? =
        PasswordSafe.instance[attributes]?.password
            ?.toString()
            ?.trim()
            ?.takeIf(String::isNotBlank)

    override fun save(companionId: String) {
        PasswordSafe.instance[attributes] = attributes.credentials(companionId)
    }
}

internal fun persistentIdentityAttributes(): CredentialAttributes =
    hostCredentialAttributes("companionIdentity")
