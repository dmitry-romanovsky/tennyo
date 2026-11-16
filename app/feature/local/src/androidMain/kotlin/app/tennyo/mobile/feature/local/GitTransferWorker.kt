package app.tennyo.mobile.feature.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import app.tennyo.mobile.feature.git.GitCredentials
import app.tennyo.mobile.feature.git.GitFault
import app.tennyo.mobile.feature.git.GitRejection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Runs transfers as unique WorkManager work per workspace, in the foreground with a progress notification. */
class WorkManagerGitTransferScheduler(
    context: Context,
    private val executor: JGitTransferExecutor,
) : GitTransferScheduler {
    private val workManager = WorkManager.getInstance(context)

    override fun start(transfer: GitTransfer): Flow<GitTransferState> = flow {
        val request = OneTimeWorkRequestBuilder<GitTransferWorker>().setInputData(transfer.toData()).build()
        // A workspace runs one transfer at a time; a second request finds no work of its own and reports busy.
        workManager.enqueueUniqueWork(uniqueTransferName(transfer.workspaceSlug), ExistingWorkPolicy.KEEP, request)
            .await()
        emitAll(
            workManager.getWorkInfoByIdFlow(request.id)
                .map { it.toTransferState() }
                .transformWhile { state ->
                    emit(state)
                    !state.isFinished
                },
        )
    }

    override fun cancel(transfer: GitTransfer) {
        workManager.cancelUniqueWork(uniqueTransferName(transfer.workspaceSlug))
    }

    override fun stageCredentials(host: String, credentials: GitCredentials) = executor.stage(host, credentials)
}

class GitTransferWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    private val executor: JGitTransferExecutor by inject()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var lastNotifiedAt = 0L

    override suspend fun doWork(): Result {
        val transfer = inputData.toTransfer()
            ?: return Result.failure(GitFault.Rejected(GitRejection.UNEXPECTED).toData())
        createChannel()
        setForeground(foregroundInfo(transfer, progress = null))
        val monitor = TransferProgressMonitor(isCancelled = { isStopped }) { progress ->
            setProgressAsync(progressData(progress))
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotifiedAt >= NOTIFICATION_INTERVAL_MILLIS) {
                lastNotifiedAt = now
                notifications.notify(NOTIFICATION_ID, notification(transfer, progress))
            }
        }
        return try {
            withContext(Dispatchers.IO) { executor.execute(transfer, monitor) }
            Result.success()
        } catch (fault: GitFault) {
            Result.failure(fault.toData())
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(inputData.toTransfer() ?: GitTransfer.Fetch(""), progress = null)

    private fun foregroundInfo(transfer: GitTransfer, progress: Float?) =
        ForegroundInfo(NOTIFICATION_ID, notification(transfer, progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(transfer: GitTransfer, progress: Float?): Notification {
        val title = applicationContext.getString(
            when (transfer) {
                is GitTransfer.Clone -> R.string.git_transfer_cloning
                is GitTransfer.Fetch -> R.string.git_transfer_fetching
                is GitTransfer.Push -> R.string.git_transfer_pushing
            },
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_git_transfer)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(PROGRESS_MAX, ((progress ?: 0f) * PROGRESS_MAX).toInt(), progress == null)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                applicationContext.getString(R.string.git_transfer_cancel),
                GitTransferNotificationReceiver.cancelIntent(applicationContext, transfer.workspaceSlug),
            )
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.git_transfer_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = applicationContext.getString(R.string.git_transfer_channel_description) }
        notifications.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "git_transfers"
        const val NOTIFICATION_ID = 0x617
        const val PROGRESS_MAX = 100
        const val NOTIFICATION_INTERVAL_MILLIS = 500L
    }
}

/** The notification's cancel action: stops the workspace's running transfer. */
class GitTransferNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CANCEL) return
        val slug = intent.getStringExtra(EXTRA_WORKSPACE_SLUG) ?: return
        WorkManager.getInstance(context).cancelUniqueWork(uniqueTransferName(slug))
    }

    internal companion object {
        const val ACTION_CANCEL = "app.tennyo.mobile.feature.local.action.CANCEL_GIT_TRANSFER"
        const val EXTRA_WORKSPACE_SLUG = "workspace_slug"

        fun cancelIntent(context: Context, workspaceSlug: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            workspaceSlug.hashCode(),
            Intent(context, GitTransferNotificationReceiver::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_WORKSPACE_SLUG, workspaceSlug),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
