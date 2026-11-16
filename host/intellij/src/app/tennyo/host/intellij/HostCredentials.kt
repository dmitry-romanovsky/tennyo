package app.tennyo.host.intellij

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName

/**
 * Every secret the host keeps, each under its own PasswordSafe service. The credential stores keep one entry per
 * service, whatever the user name, so secrets sharing a service would overwrite one another.
 */
internal fun hostCredentialAttributes(secretName: String): CredentialAttributes =
    CredentialAttributes(generateServiceName("Tennyo", secretName))

internal fun CredentialAttributes.credentials(secret: String): Credentials = Credentials(userName, secret)
