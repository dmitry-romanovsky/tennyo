package app.tennyo.host.vscode.panes

import app.tennyo.host.engine.panes.asLinkPath
import app.tennyo.host.vscode.api.Uri

/**
 * A `Uri` as the link names files. `fsPath` is the platform's own form, so it goes through the same normalisation
 * every Node-side host uses; comparing it raw against a path from `fs` would miss on Windows.
 */
fun Uri.linkPath(): String = (asDynamic().fsPath as? String)?.asLinkPath() ?: ""
