package app.tennyo.host.engine.platform

import app.tennyo.crypto.verifyEcdsaP256Sha256
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostTestSignerTest {
    @Test
    fun signaturesVerifyThroughTheProductionProviderOnlyForTheSignedMessage() {
        val key = HostTestSigner.newKey()
        val message = "GET /api/tennyo/readFile".encodeToByteArray()
        val signature = HostTestSigner.sign(key, message)

        assertTrue(verifyEcdsaP256Sha256(key.publicKeySpki, message, signature))
        assertFalse(verifyEcdsaP256Sha256(key.publicKeySpki, "GET /api/tennyo/fileTree".encodeToByteArray(), signature))
    }
}
