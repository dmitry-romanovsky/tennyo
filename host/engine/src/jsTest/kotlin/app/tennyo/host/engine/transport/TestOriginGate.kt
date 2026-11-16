package app.tennyo.host.engine.transport

/** A gate that admits every connection, for tests that talk to the listener over loopback and are not about origin. */
internal fun openOriginGate(): OriginGate =
    OriginGate(ListenAddress("127.0.0.1", ListenKind.LAN, null, prefixLength = 8), { true }, { emptySet() })
