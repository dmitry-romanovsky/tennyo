package app.tennyo.host.engine.platform

import kotlinx.coroutines.CoroutineDispatcher

/** Where work that blocks on I/O runs: a thread pool sized for blocking on the JVM, the event loop on Node. */
expect val hostBlockingDispatcher: CoroutineDispatcher
