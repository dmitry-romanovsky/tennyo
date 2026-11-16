package app.tennyo.mobile.link.session

/** Mirrors the session into the platform notification that keeps it alive while the app is in the background. */
internal class NotificationBridge(
    private val core: SessionCore,
    private val notification: SessionNotification,
    private val status: ConnectionStatus,
) {
    fun info(status: SessionStatus, projectName: String = core.projectName): SessionNotificationInfo {
        val ticket = core.ticket.value
        return SessionNotificationInfo(
            sessionId = ticket?.sessionId.orEmpty(),
            projectName = projectName.ifBlank { ticket?.projectName.orEmpty().ifBlank { core.projectName } },
            status = status,
            startedAtEpochMillis = this.status.notificationStartedAtEpochMillis,
        )
    }

    fun start(status: SessionStatus) = notification.start(info(status))

    fun update(status: SessionStatus, projectName: String = core.projectName) {
        notification.update(info(status, projectName))
    }

    fun startIdleKeepAlive(projectName: String) =
        notification.startIdleKeepAlive(info(SessionStatus.Connected, projectName))

    fun stopIdleKeepAlive() = notification.stopIdleKeepAlive(core.ticket.value?.sessionId.orEmpty())

    fun remove() {
        notification.stop(core.ticket.value?.sessionId.orEmpty())
    }
}
