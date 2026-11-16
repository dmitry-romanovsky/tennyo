// An external declaration names the JS signature it binds to; nothing in Kotlin reads those parameters.
@file:Suppress("UnusedParameter")

package app.tennyo.host.engine.platform

import org.khronos.webgl.Uint8Array

/**
 * The synchronous half of Node's `fs`. The link's file port answers a call while the caller waits, so it reads and
 * writes synchronously rather than turning the whole port into suspending calls for one platform.
 */
@JsModule("node:fs")
@JsNonModule
external object NodeFs {
    fun realpathSync(path: String): String

    fun statSync(path: String): NodeStats

    fun lstatSync(path: String): NodeStats

    fun readlinkSync(path: String): String

    fun readdirSync(path: String): Array<String>

    fun readFileSync(path: String): Uint8Array

    fun writeFileSync(path: String, data: String, options: dynamic)

    fun accessSync(path: String, mode: Int)

    fun mkdirSync(path: String, options: dynamic)

    fun rmSync(path: String, options: dynamic)

    fun symlinkSync(target: String, path: String, type: String)

    val constants: NodeFsConstants
}

external interface NodeStats {
    val size: Number
    val mtimeMs: Number

    fun isDirectory(): Boolean

    fun isSymbolicLink(): Boolean
}

external interface NodeFsConstants {
    @JsName("W_OK")
    val writeOk: Int
}

@JsModule("node:path")
@JsNonModule
external object NodePath {
    fun resolve(base: String, relative: String): String

    fun join(base: String, relative: String): String
}

@JsModule("node:os")
@JsNonModule
external object NodeOsPaths {
    fun tmpdir(): String

    fun homedir(): String
}
