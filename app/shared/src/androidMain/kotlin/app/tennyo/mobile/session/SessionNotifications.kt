package app.tennyo.mobile.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.tennyo.mobile.MainActivity
import app.tennyo.mobile.R
import app.tennyo.mobile.link.session.SessionStatus

internal const val SESSION_NOTIFICATION_ID = 2

private const val CHANNEL_ID = "remote_session_channel"
private const val REQUEST_OPEN = 20
private const val REQUEST_END = 21
private const val ACTION_OPEN = "app.tennyo.mobile.session.action.OPEN"

/** What the session notification says right now. */
internal data class SessionNotificationState(
    val projectName: String,
    val status: SessionStatus,
    val startedAtEpochMillis: Long,
    val ended: Boolean,
    val sessionId: String?,
)

/**
 * The ongoing notification the session service runs in the foreground with: it names the project, tells the user
 * how the session is doing, and offers opening the app or ending the session.
 */
internal class SessionNotifications(private val context: Context) {
    private val manager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.session_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.session_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(state: SessionNotificationState): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                state.projectName.ifBlank { context.getString(R.string.session_notification_default_project) },
            )
            .setContentText(context.getString(state.status.notificationText))
            .setOngoing(!state.ended)
            .setAutoCancel(state.ended)
            .setOnlyAlertOnce(true)
            .setWhen(state.startedAtEpochMillis)
            .setUsesChronometer(!state.ended)

        if (!state.ended) {
            builder
                .setContentIntent(openIntent())
                .addAction(
                    android.R.drawable.ic_menu_view,
                    context.getString(R.string.session_notification_action_open),
                    openIntent(),
                )
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.session_notification_action_end),
                    endIntent(state.sessionId),
                )
        }
        return builder.build()
    }

    fun cancel() {
        manager.cancel(SESSION_NOTIFICATION_ID)
    }

    private fun openIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun endIntent(sessionId: String?): PendingIntent {
        val intent = Intent(context, LinkSessionService::class.java)
            .setAction(LinkSessionService.ACTION_END)
            .putExtra(LinkSessionService.EXTRA_SESSION_ID, sessionId)
        return PendingIntent.getService(
            context,
            REQUEST_END,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
