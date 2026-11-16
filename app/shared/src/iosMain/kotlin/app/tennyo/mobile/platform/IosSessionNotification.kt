package app.tennyo.mobile.platform

import app.tennyo.mobile.link.session.SessionNotification
import app.tennyo.mobile.link.session.SessionNotificationInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class IosSessionNotification : SessionNotification {
    private val endRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)

    override val endSessionRequests: Flow<String> = endRequests

    override fun start(info: SessionNotificationInfo) = Unit

    override fun update(info: SessionNotificationInfo) = Unit

    override fun startIdleKeepAlive(info: SessionNotificationInfo) = Unit

    override fun stopIdleKeepAlive(sessionId: String) = Unit

    override fun stop(sessionId: String) = Unit

    // No stored ticket on iOS: a blank artifact names whichever session is live.
    override fun endSession() {
        endRequests.tryEmit("")
    }
}
