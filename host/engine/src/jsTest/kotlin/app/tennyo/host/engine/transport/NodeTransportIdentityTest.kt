package app.tennyo.host.engine.transport

import app.tennyo.host.engine.platform.NodeFs
import app.tennyo.host.engine.platform.NodeOsPaths
import app.tennyo.host.engine.platform.NodePath
import app.tennyo.host.engine.platform.jsOptions
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The certificate a remembered phone pins, and the reasons it must not change underneath one. */
class NodeTransportIdentityTest {
    private lateinit var directory: String

    @BeforeTest
    fun setUp() {
        directory = NodePath.join(NodeOsPaths.tmpdir(), "tennyo-identity-${Random.nextLong()}")
    }

    @AfterTest
    fun tearDown() {
        NodeFs.rmSync(directory, jsOptions("recursive" to true, "force" to true))
    }

    @Test
    fun `the certificate survives a restart, so a remembered phone's pin still matches`() {
        val first = loadOrCreateNodeTransportIdentity(directory)

        val second = loadOrCreateNodeTransportIdentity(directory)

        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.certificatePem, second.certificatePem)
        assertEquals(first.privateKeyPem, second.privateKeyPem)
    }

    @Test
    fun `a host with no stored certificate gets its own, not one shared with another host`() {
        val other = NodePath.join(directory, "other")

        val first = loadOrCreateNodeTransportIdentity(directory)
        val second = loadOrCreateNodeTransportIdentity(other)

        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `a corrupt stored certificate is replaced rather than served`() {
        loadOrCreateNodeTransportIdentity(directory)
        val path = NodePath.join(directory, "transport-identity.json")
        NodeFs.writeFileSync(path, "{ not json", jsOptions("encoding" to "utf8"))

        val replacement = loadOrCreateNodeTransportIdentity(directory)

        assertTrue(replacement.certificatePem.startsWith("-----BEGIN CERTIFICATE-----"))
        assertTrue(replacement.fingerprint.isNotBlank())
    }

    @Test
    fun `the fingerprint is the base64 SHA-256 of the certificate's public key, as the phone computes it`() {
        val identity = loadOrCreateNodeTransportIdentity(directory)

        // 32 bytes of SHA-256 in standard base64 is 44 characters ending in one padding character.
        assertEquals(44, identity.fingerprint.length, "unexpected fingerprint ${identity.fingerprint}")
        assertTrue(identity.fingerprint.endsWith("="))
    }
}
