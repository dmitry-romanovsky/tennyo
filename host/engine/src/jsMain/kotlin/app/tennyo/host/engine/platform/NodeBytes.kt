package app.tennyo.host.engine.platform

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

/** Kotlin/JS backs `ByteArray` with an `Int8Array`, so both directions are views over the same memory, not copies. */
internal fun ByteArray.asUint8Array(): Uint8Array {
    val view = unsafeCast<Int8Array>()
    return Uint8Array(view.buffer, view.byteOffset, view.length)
}

internal fun Uint8Array.asByteArray(): ByteArray = Int8Array(buffer, byteOffset, length).unsafeCast<ByteArray>()

/** A plain JS object for a Node options parameter. */
internal fun jsOptions(vararg entries: Pair<String, Any?>): dynamic {
    val options = js("{}")
    entries.forEach { (key, value) -> options[key] = value }
    return options
}

/** A Node `Buffer` as the UTF-8 text it holds. */
internal fun Uint8Array.asText(): String = asByteArray().decodeToString()
