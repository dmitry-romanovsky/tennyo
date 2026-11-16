package app.tennyo.mobile.platform

import android.content.Context
import app.tennyo.mobile.link.session.SessionNotification
import app.tennyo.mobile.link.session.SessionNotificationInfo
import app.tennyo.mobile.session.LinkSessionService
class AndroidSessionNotification(
    private val context: Context,
) : SessionNotification {
    override val endSessionRequests = LinkSessionService.endSessionRequests

    override fun start(info: SessionNotificationInfo) {
        LinkSessionService.start(
            context = context,
            sessionId = info.sessionId,
            projectName = info.projectName.orEmpty(),
            status = info.status,
            startedAtEpochMillis = info.startedAtEpochMillis,
        )
    }

    override fun update(info: SessionNotificationInfo) {
        LinkSessionService.update(
            context = context,
            sessionId = info.sessionId,
            projectName = info.projectName.orEmpty(),
            status = info.status,
            startedAtEpochMillis = info.startedAtEpochMillis,
        )
    }

    override fun startIdleKeepAlive(info: SessionNotificationInfo) {
        LinkSessionService.startIdleKeepAlive(
            context = context,
            sessionId = info.sessionId,
            projectName = info.projectName.orEmpty(),
            status = info.status,
            startedAtEpochMillis = info.startedAtEpochMillis,
        )
    }

    override fun stopIdleKeepAlive(sessionId: String) {
        LinkSessionService.stopIdleKeepAlive(context, sessionId)
    }

    override fun stop(sessionId: String) {
        LinkSessionService.removeNotification(context)
    }

    override fun endSession() {
        LinkSessionService.end(context)
    }
}
