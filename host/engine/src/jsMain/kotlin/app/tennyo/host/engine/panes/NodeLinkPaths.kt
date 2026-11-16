package app.tennyo.host.engine.panes

/**
 * A path in the link's form: absolute and `/`-separated on every OS, with a Windows drive letter upper-cased.
 *
 * Every Node-side host must name a file the same way, because the buffer overlay and the editor-tab list match paths
 * as strings: `c:/work/Main.kt` from one API and `C:\work\Main.kt` from another are the same file, and a host that
 * compared them raw would show the phone a stale buffer without ever failing.
 */
fun String.asLinkPath(): String {
    val forwardSlashed = replace('\\', '/')
    return if (forwardSlashed.hasDriveLetter()) {
        forwardSlashed[0].uppercaseChar() + forwardSlashed.substring(1)
    } else {
        forwardSlashed
    }
}

private fun String.hasDriveLetter(): Boolean = length >= 2 && this[1] == ':' && this[0].isLetter()
