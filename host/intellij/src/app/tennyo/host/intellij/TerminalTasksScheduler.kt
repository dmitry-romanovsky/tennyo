package app.tennyo.host.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class TerminalTasksScheduler(parentDisposable: Disposable) {
    private val scheduledTasks = ConcurrentHashMap.newKeySet<ScheduledFuture<*>>()

    @Volatile
    private var disposed = false

    init {
        Disposer.register(parentDisposable) {
            disposed = true
            scheduledTasks.forEach { it.cancel(false) }
            scheduledTasks.clear()
        }
    }

    fun schedule(delayMillis: Int, task: () -> Unit): Boolean {
        if (isDisposed()) return false
        val taskRef = AtomicReference<ScheduledFuture<*>?>()
        val future = AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                taskRef.get()?.let(scheduledTasks::remove)
                if (!isDisposed()) {
                    task()
                }
            },
            delayMillis.toLong(),
            TimeUnit.MILLISECONDS,
        )
        taskRef.set(future)
        scheduledTasks += future
        if (isDisposed()) {
            if (scheduledTasks.remove(future)) {
                future.cancel(false)
            }
            return false
        }
        return true
    }

    fun isDisposed(): Boolean = disposed
}
