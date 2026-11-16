package app.tennyo.mobile.feature.connect

import app.tennyo.contract.Discovery
import app.tennyo.mobile.link.discoveryDebug
import app.tennyo.mobile.link.pairing.KnownHostAnswer
import app.tennyo.mobile.link.pairing.KnownHostDiscovery
import app.tennyo.mobile.link.pairing.KnownHostReach
import app.tennyo.mobile.link.pairing.KnownHostTarget
import app.tennyo.mobile.link.pairing.LocalNetworkDiscoveryGateway
import app.tennyo.mobile.link.pairing.LocalNetworkServiceAdvertisement
import app.tennyo.mobile.link.pairing.PairingProbe
import app.tennyo.mobile.link.pairing.PairingTarget
import app.tennyo.mobile.link.pairing.ProbeOutcome
import app.tennyo.mobile.link.pane.nowEpochMillis
import app.tennyo.mobile.link.runCatchingCancellable
import app.tennyo.mobile.link.store.FeatureStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Keeps the pairing screen's two lists current while it is on screen: the hosts this device remembers, and the
 * hosts in pairing mode nearby.
 *
 * This is the one surface in the product that polls rather than listens, and it has to be — a host the phone has
 * never paired with has no channel to push over. The cost is bounded deliberately: it runs only while the screen is
 * resumed, one browse and one round of probes per pass with a second between passes, at most [MAX_CONCURRENT_PROBES]
 * probes in flight, one per host, and a host whose
 * probe is still running is skipped rather than queued. A row survives [MISSES_BEFORE_DROP] consecutive misses, so
 * one dropped packet does not make the list flicker.
 *
 * A known host DNS-SD misses is dialed at its stored addresses on the first pass and then at most once per
 * [DIRECT_DIAL_INTERVAL_MILLIS], so a phone on cellular is not probed every second. The passes between dials would
 * report such a host as gone, so its last away answer is held for twice that interval.
 */
internal class PairingDiscoveryLoop(
    private val store: FeatureStore<ConnectState, ConnectMsg, ConnectEffect>,
    private val discovery: KnownHostDiscovery,
    private val gateway: LocalNetworkDiscoveryGateway,
    private val probe: PairingProbe,
    private val backgroundDispatcher: CoroutineDispatcher,
    /** The targets this pass found, so selecting a row has the offer and fingerprint it was built from. */
    private val onTargetsFound: (List<PairingTarget>) -> Unit,
    private val now: () -> Long = ::nowEpochMillis,
) {
    private var loop: Job? = null
    private var lastDialAtMillis: Long? = null
    private val heldAway = mutableMapOf<String, HeldAnswer>()
    private val misses = mutableMapOf<String, Int>()
    private val inFlight = mutableSetOf<String>()

    /** Starts passing while the screen is resumed. Starting again while running is a no-op. */
    fun start() {
        if (loop?.isActive == true) return
        discoveryDebug { "pairing discovery loop started" }
        loop = store.scope.launch {
            while (isActive) {
                pass()
                delay(PASS_INTERVAL_MILLIS.milliseconds)
            }
        }
    }

    /** Stops every pass. Nothing probes from the background: the screen leaving the foreground ends the loop. */
    fun stop() {
        discoveryDebug { "pairing discovery loop stopped" }
        loop?.cancel()
        loop = null
        inFlight.clear()
        misses.clear()
        lastDialAtMillis = null
        heldAway.clear()
    }

    /**
     * Both lists are probed from one browse, because a pass gets one: Android answers a second DNS-SD session for a
     * service type it is still tearing down with nothing. The remembered hosts are listed before
     * the browse, which can take [BROWSE_TIMEOUT_MILLIS], so they show the moment the screen opens.
     */
    private suspend fun pass() {
        val knownTargets = runCatchingCancellable { loadKnownHostTargets() }.getOrDefault(emptyList())
        store.dispatch(ConnectMsg.Discovery.KnownHostsLoaded(knownRows(knownTargets)))
        val advertisements = runCatchingCancellable { browse() }.getOrDefault(emptyList())
        probeOffers(advertisements)
        probeKnownHosts(knownTargets, advertisements)
    }

    private suspend fun browse(): List<LocalNetworkServiceAdvertisement> =
        gateway.discoverServices(serviceType = Discovery.SERVICE_TYPE, timeoutMillis = BROWSE_TIMEOUT_MILLIS)

    /** One probe per advertised host per pass, bounded; a host already being probed is skipped, never queued. */
    private suspend fun probeOffers(advertisements: List<LocalNetworkServiceAdvertisement>) = coroutineScope {
        val due = advertisements
            .filter { it.address() !in inFlight }
            .take(MAX_CONCURRENT_PROBES)
        if (due.isEmpty()) return@coroutineScope
        inFlight.addAll(due.map { it.address() })
        val outcomes = due
            .map { advertisement ->
                async {
                    advertisement.address() to (
                        withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) { probe.probe(advertisement) }
                            ?: ProbeOutcome.Unreachable
                        )
                }
            }
            .awaitAll()
        inFlight.removeAll(due.map { it.address() }.toSet())
        store.dispatch(ConnectMsg.Pairing.HostsProbed(pairingRows(outcomes)))
    }

    private suspend fun probeKnownHosts(
        targets: List<KnownHostTarget>,
        advertisements: List<LocalNetworkServiceAdvertisement>,
    ) {
        if (targets.isEmpty()) return
        val dialStored = lastDialAtMillis?.let { now() - it >= DIRECT_DIAL_INTERVAL_MILLIS } ?: true
        if (dialStored) lastDialAtMillis = now()
        val responses = holdingAway(
            runCatchingCancellable { discovery.discoverCandidates(targets, advertisements, dialStored) }
                .getOrDefault(emptyMap()),
        )
        store.dispatch(
            ConnectMsg.Discovery.Finished(
                mergeKnownTargetRows(
                    current = store.state.value.knownHostTargets,
                    storedTargets = targets,
                    discoveryAvailability = responses.mapValues { (_, answer) ->
                        answer?.response?.status.toUiAvailability()
                    },
                    discoveryReach = responses.mapValues { (_, answer) -> answer.toUiReach() },
                    removeMissingTargets = true,
                    selectedTargetId = store.state.value.selectedDiscoveryTargetId,
                ),
            ),
        )
    }

    /** Substitutes the held away answer for a host the pass did not answer, until it is [HOLD_AWAY_MILLIS] old. */
    private fun holdingAway(answers: Map<String, KnownHostAnswer?>): Map<String, KnownHostAnswer?> =
        answers.also { heldAway.keys.retainAll(it.keys) }.mapValues { (companionId, answer) ->
            when {
                answer == null -> heldAway[companionId]
                    ?.takeIf { now() - it.atMillis < HOLD_AWAY_MILLIS }
                    ?.answer
                    .also { if (it == null) heldAway.remove(companionId) }

                answer.reach == KnownHostReach.AWAY -> {
                    heldAway[companionId] = HeldAnswer(answer, now())
                    answer
                }

                else -> {
                    heldAway.remove(companionId)
                    answer
                }
            }
        }

    /**
     * Folds this pass's outcomes into the rows, keeping a host that missed fewer than [MISSES_BEFORE_DROP] times in
     * a row. A host not seen in this pass at all counts as a miss, so an expired window drops off on its own.
     */
    private fun pairingRows(outcomes: List<Pair<String, ProbeOutcome>>): List<PairingHostUiState> {
        val found = outcomes.mapNotNull { (_, outcome) -> (outcome as? ProbeOutcome.Found)?.target }
        onTargetsFound(found)
        val blocked = outcomes.filter { (_, outcome) -> outcome is ProbeOutcome.Blocked }.map { it.first }
        val seen = found.map { it.hostShortId }.toSet()
        found.forEach { misses.remove(it.hostShortId) }
        val kept = store.state.value.pairingHosts
            .filter { it.shortId !in seen }
            .filter { row -> recordMiss(row.shortId) < MISSES_BEFORE_DROP }
        misses.keys.retainAll((seen + kept.map { it.shortId }).toSet())
        return found.map { it.toRow() } + kept.map { it.copy(isBlocked = it.endpoint in blocked) }
    }

    /** Counts one consecutive miss for [shortId] and reports the new count. */
    private fun recordMiss(shortId: String): Int {
        val missed = (misses[shortId] ?: 0) + 1
        misses[shortId] = missed
        return missed
    }

    private fun knownRows(targets: List<KnownHostTarget>): List<KnownHostTargetUiState> =
        mergeKnownTargetRows(
            current = store.state.value.knownHostTargets,
            storedTargets = targets,
            removeMissingTargets = true,
            selectedTargetId = store.state.value.selectedDiscoveryTargetId,
        )

    private suspend fun loadKnownHostTargets(): List<KnownHostTarget> =
        withContext(backgroundDispatcher) { discovery.knownHostTargets() }

    private fun LocalNetworkServiceAdvertisement.address(): String = "$host:$port"

    private class HeldAnswer(val answer: KnownHostAnswer, val atMillis: Long)

    private companion object {
        const val PASS_INTERVAL_MILLIS = 1_000L
        const val HOLD_AWAY_MILLIS = 2 * DIRECT_DIAL_INTERVAL_MILLIS

        /** The pass's only browse, so it runs for the time both probe lists share. */
        const val BROWSE_TIMEOUT_MILLIS = 3_000L
        const val MAX_CONCURRENT_PROBES = 4
        const val MISSES_BEFORE_DROP = 3
        const val PROBE_TIMEOUT_MILLIS = 1_500L
    }
}

private fun PairingTarget.toRow(): PairingHostUiState =
    PairingHostUiState(
        shortId = hostShortId,
        displayShortId = displayShortId,
        hostName = hostName,
        hostLabel = hostLabel,
        endpoint = endpoint,
        expiresAtEpochMillis = expiresAtEpochMillis,
        isBlocked = false,
    )

/** The least time between two passes that dial a known host's stored addresses. */
internal const val DIRECT_DIAL_INTERVAL_MILLIS = 15_000L
