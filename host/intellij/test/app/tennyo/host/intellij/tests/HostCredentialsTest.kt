package app.tennyo.host.intellij.tests

import app.tennyo.host.intellij.credentials
import app.tennyo.host.intellij.hostCredentialAttributes
import com.intellij.credentialStore.keePass.InMemoryCredentialStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Host secrets must not overwrite one another in the IDE's credential store, KeePass being the Windows default. */
internal class HostCredentialsTest {
    @Test
    fun `writing one host secret keeps the others`() {
        val store = InMemoryCredentialStore()
        val pairs = hostCredentialAttributes("rememberedPairs")
        val identity = hostCredentialAttributes("companionIdentity")

        store[pairs] = pairs.credentials("pairs")
        store[identity] = identity.credentials("identity")

        assertEquals("pairs", store[pairs]?.getPasswordAsString())
        assertEquals("identity", store[identity]?.getPasswordAsString())
    }
}
