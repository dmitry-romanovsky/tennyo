package app.tennyo.host.engine.pairing

import app.tennyo.crypto.ProtectedPayloads
import app.tennyo.host.engine.rpc.HostJson

fun unprotectPairMaterial(
    protectedPayload: String,
    responseKey: ByteArray,
): RememberedPairMaterial? =
    ProtectedPayloads.open(protectedPayload, responseKey)?.let { plaintext ->
        runCatching {
            HostJson.decodeFromString<RememberedPairMaterial>(plaintext.decodeToString())
        }.getOrNull()
    }
