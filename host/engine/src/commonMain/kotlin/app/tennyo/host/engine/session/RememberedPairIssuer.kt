package app.tennyo.host.engine.session

import app.tennyo.host.engine.pairing.RememberedPairRecord
import app.tennyo.host.engine.pairing.newRememberedPair
import app.tennyo.host.engine.pairing.protectPairMaterial

/**
 * Mints and rotates the remembered pair a device keeps between sessions. Every record it stores carries this
 * session's project, so the phone can name what it last connected to, and the material it returns is encrypted
 * under the key the device just agreed on rather than under the pair secret that authenticated the exchange.
 */
internal class RememberedPairIssuer(private val session: HostSession) {

    /** A brand new pair for a device that just finished pairing. */
    fun issue(deviceLabel: String, deviceKeyHash: String, responseKey: ByteArray): String {
        val (material, record) = newRememberedPair(deviceLabel = deviceLabel, deviceKeyHash = deviceKeyHash)
        session.owner.saveRememberedPair(stamped(record))
        return protectPairMaterial(
            material = material.copy(companionId = session.artifact.companionId),
            responseKey = responseKey,
        )
    }

    /** A replacement pair for [previousRecord], or `null` when another rotation won the race. */
    fun rotate(previousRecord: RememberedPairRecord, deviceLabel: String, responseKey: ByteArray): String? {
        val (material, replacement) = newRememberedPair(
            deviceLabel = deviceLabel.ifBlank { previousRecord.deviceLabel },
            deviceKeyHash = previousRecord.deviceKeyHash,
        )
        val replaced = session.owner.replaceRememberedPair(
            pairId = previousRecord.pairId,
            expectedPairSecret = previousRecord.pairSecret,
            replacement = stamped(replacement),
        )
        if (!replaced) return null
        return protectPairMaterial(
            material = material.copy(companionId = session.artifact.companionId),
            responseKey = responseKey,
        )
    }

    private fun stamped(record: RememberedPairRecord): RememberedPairRecord =
        record.copy(
            companionId = session.artifact.companionId,
            lastProjectName = session.artifact.projectName,
            lastHostVersion = session.artifact.hostVersion,
            lastUsedAtEpochMillis = session.monotonicNow(),
            lastKnownNetworkLabel = session.artifact.projectPath,
        )
}
