package app.tennyo.host.engine.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val hostBlockingDispatcher: CoroutineDispatcher = Dispatchers.Default
