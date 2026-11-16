package app.tennyo.mobile.link

import app.tennyo.contract.PairingChallenge
import app.tennyo.contract.PairingWords

/** Where the link to the host stands. The workspace renders it; logic branches on the type, never on text. */
sealed interface LinkState {

    /** Before the host has accepted a session for this device. */
    data class Connecting(val step: ConnectStep) : LinkState

    /** The host asked for the pairing words shown in the IDE. */
    data class Pairing(val confirmation: PairingConfirmation) : LinkState

    /** Connected; [sync] says whether live events are flowing. */
    data class Live(val sync: LiveSync) : LinkState

    /** Connected earlier but not now. */
    data class Degraded(val reason: DegradedReason) : LinkState

    /** Over. Only a new ticket can start another session. */
    data class Ended(val reason: EndReason) : LinkState
}

enum class ConnectStep {
    /** Reaching the host at the ticket's addresses. */
    Reaching,

    /** Picking up a session this device already holds, e.g. after the app was in the background. */
    Resuming,

    /** The host answered; the IDE has not approved this device yet. */
    AwaitingApproval,
}

enum class LiveSync {
    /** Attaching to the event stream for the first time. */
    Attaching,

    /** Events are flowing. */
    Ready,

    /** The stream dropped a while ago and is being re-attached; the session itself is fine. */
    Reattaching,
}

sealed interface DegradedReason {
    /** The connection dropped and a reconnect is under way. */
    data object Reconnecting : DegradedReason

    /** Reconnecting gave up. [fault] is the last failure. */
    data class Lost(val fault: LinkFault) : DegradedReason

    /** A connect attempt failed before the session was established. */
    data class Failed(val fault: LinkFault) : DegradedReason
}

sealed interface EndReason {
    /** The user ended the session from the app. */
    data object ByUser : EndReason

    /** The session was ended from the platform session notification. */
    data object FromNotification : EndReason

    /** The host did not answer for longer than the approved idle timeout. */
    data object TimedOut : EndReason

    /** Nobody entered the pairing words in time. */
    data object PairingWordsExpired : EndReason

    /** The host stopped accepting pairing attempts after too many wrong answers. */
    data object PairingStopped : EndReason

    /** The host ended the session and said why, when it did. */
    data class ByHost(val message: String?) : EndReason

    /** A failure that leaves no session to go back to, such as an expired session or a protocol mismatch. */
    data class Fault(val fault: LinkFault) : EndReason
}

/**
 * The tap-to-confirm pairing step.
 *
 * [selectedWords] is ordered by tap and that order is the secret — never sort it, and never derive it from grid
 * order. Doing either would make the answer readable off the grid and collapse the search space 24-fold.
 */
data class PairingConfirmation(
    val challenge: PairingChallenge,
    val selectedWords: List<String> = emptyList(),
    val issue: PairingIssue? = null,
    val isConfirming: Boolean = false,
) {
    val wordGrid: List<String> get() = challenge.wordGrid

    val isComplete: Boolean get() = selectedWords.size == PairingWords.SECRET_LENGTH

    /** Tapping a selected word removes it; tapping past the secret length is ignored. */
    fun toggled(word: String): PairingConfirmation = when {
        word in selectedWords -> copy(selectedWords = selectedWords - word, issue = null)
        isComplete -> this
        word !in wordGrid -> this
        else -> copy(selectedWords = selectedWords + word, issue = null)
    }
}

/** Why the pairing words were not accepted. */
sealed interface PairingIssue {
    data object ConfirmationFailed : PairingIssue
    data object IncorrectSelection : PairingIssue

    /** The host's own explanation, shown as received. */
    data class Reported(val text: String) : PairingIssue
}

/**
 * Whether the host sent a grid the pairing screen can actually complete. Duplicate tiles matter as much as the count:
 * selection is keyed by word, so a repeated word would toggle two tiles at once.
 */
fun PairingChallenge.hasUsableWordGrid(): Boolean =
    wordGrid.size == PairingWords.GRID_SIZE && wordGrid.toSet().size == wordGrid.size
