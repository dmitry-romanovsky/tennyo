package app.tennyo.host.intellij


import app.tennyo.contract.LaunchCandidate
import app.tennyo.contract.LaunchConfiguration
import app.tennyo.contract.LaunchConfigurationsState
import app.tennyo.host.engine.panes.IdeLaunchPort
import app.tennyo.host.engine.panes.LaunchTabInfo
import app.tennyo.host.engine.panes.LaunchTabOutputSource
import app.tennyo.host.engine.panes.LaunchTabWatcher
import app.tennyo.host.engine.panes.LaunchTabsSnapshot
import app.tennyo.host.engine.panes.StoppedLaunch
import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.impl.ExecutionManagerImpl
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.ui.RunContentManagerImpl
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.Content
import com.intellij.util.concurrency.ThreadingAssertions

/**
 * Launch configurations through [RunManager] and run tabs through the IDE's run-content services. Descriptor reads run
 * on the EDT, as the run-content API requires.
 */
internal class IntelliJLaunchPort(private val project: Project) : IdeLaunchPort {
    override fun configurations(): LaunchConfigurationsState {
        val runManager = RunManager.getInstance(project)
        return LaunchConfigurationsState(
            configurations = runManager.allSettings.map { setting ->
                LaunchConfiguration(
                    id = setting.uniqueID,
                    name = setting.name,
                    type = setting.type.displayName,
                    folder = setting.folderName,
                    isRunning = isLaunchConfigurationRunning(setting.uniqueID),
                )
            },
            selectedConfigurationId = runManager.selectedConfiguration?.uniqueID,
        )
    }

    override fun candidatesForFile(path: String): List<LaunchCandidate> {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        val launchName = fileName.substringBeforeLast('.', fileName)
        return RunManager.getInstance(project).allSettings
            .filter { it.name.equals(launchName, ignoreCase = true) }
            .map { LaunchCandidate(it.uniqueID, it.name, it.type.displayName) }
    }

    override fun selectConfiguration(id: String?) {
        invokeOnEdtAndWait {
            val runManager = RunManager.getInstance(project)
            if (id == null) {
                runManager.selectedConfiguration = null
            } else {
                runManager.selectedConfiguration =
                    runManager.allSettings.firstOrNull { it.uniqueID == id } ?: runManager.selectedConfiguration
            }
        }
    }

    override fun start(id: String): String? {
        val setting = RunManager.getInstance(project).allSettings.firstOrNull { it.uniqueID == id } ?: return null
        val executor = DefaultRunExecutor.getRunExecutorInstance() ?: return null
        ApplicationManager.getApplication().invokeLater {
            ProgramRunnerUtil.executeConfiguration(setting, executor)
        }
        return setting.name
    }

    override fun stop(id: String): List<StoppedLaunch> {
        val manager = ExecutionManagerImpl.getInstanceIfCreated(project) ?: return emptyList()
        return ExecutionManagerImpl.getAllDescriptors(project).mapNotNull { descriptor ->
            val matches = manager.getExecutionEnvironments(descriptor)
                .any { it.runnerAndConfigurationSettings?.uniqueID == id }
            val processHandler = descriptor.processHandler
            if (matches && processHandler != null && !processHandler.isProcessTerminated) {
                ApplicationManager.getApplication().invokeLater {
                    if (!processHandler.isProcessTerminated) {
                        processHandler.destroyProcess()
                    }
                }
                StoppedLaunch(displayName = descriptor.displayName.orEmpty(), tabId = descriptor.remoteLaunchTabId())
            } else {
                null
            }
        }
    }

    override fun tabs(watcher: LaunchTabWatcher): LaunchTabsSnapshot? =
        runCatching {
            invokeOnEdtAndWait {
                val manager = RunContentManager.getInstanceIfCreated(project)
                    ?: return@invokeOnEdtAndWait LaunchTabsSnapshot(emptyList(), null)
                val descriptors = manager.allDescriptors.filterNot(Disposer::isDisposed)
                descriptors.forEach { watch(it, watcher) }
                LaunchTabsSnapshot(
                    tabs = descriptors.map { it.toLaunchTabInfo() },
                    selectedTabId = manager.selectedContent?.remoteId,
                )
            }
        }.getOrElse {
            LOG.warn("Unable to read IDE run tabs for Tennyo Companion session in ${project.name}", it)
            null
        }

    override fun hasTab(id: String): Boolean = findRunContentDescriptor(id) != null

    override fun selectTab(id: String) {
        val descriptor = findRunContentDescriptor(id) ?: return
        invokeOnEdtAndWait {
            RunContentManager.getInstance(project).selectRunContent(descriptor)
        }
    }

    override fun closeTab(id: String): Boolean {
        val descriptor = findRunContentDescriptor(id) ?: return false
        val executor = ExecutionManagerImpl.getInstanceIfCreated(project)
            ?.getExecutionEnvironments(descriptor)
            ?.firstOrNull()
            ?.executor
            ?: DefaultRunExecutor.getRunExecutorInstance()
            ?: return false
        descriptor.processHandler?.let(::terminateLaunchProcessSilently)
        invokeOnEdtAndWait {
            removeRunContentSilently(executor, descriptor)
        }
        return true
    }

    override fun outputSource(id: String, maxChars: Int, watcher: LaunchTabWatcher): LaunchTabOutputSource? {
        ThreadingAssertions.assertBackgroundThread()
        val descriptor = findRunContentDescriptor(id) ?: return null
        watch(descriptor, watcher)
        return LaunchTabOutputSource(
            configurationId = descriptor.runConfigurationId(),
            isRunning = descriptor.processHandler?.isProcessTerminated == false,
            consoleText = consoleText(descriptor, maxChars),
        )
    }

    private fun isLaunchConfigurationRunning(id: String): Boolean {
        val manager = ExecutionManagerImpl.getInstanceIfCreated(project) ?: return false
        return ExecutionManagerImpl.getAllDescriptors(project).any { descriptor ->
            val processHandler = descriptor.processHandler ?: return@any false
            !processHandler.isProcessTerminated && manager.getExecutionEnvironments(descriptor)
                .any { it.runnerAndConfigurationSettings?.uniqueID == id }
        }
    }

    private fun findRunContentDescriptor(id: String): RunContentDescriptor? =
        runCatching {
            invokeOnEdtAndWait {
                RunContentManager.getInstanceIfCreated(project)?.allDescriptors?.firstOrNull { it.remoteId == id }
            }
        }.getOrNull()

    private fun watch(descriptor: RunContentDescriptor, watcher: LaunchTabWatcher) {
        val id = descriptor.remoteId
        if (!watcher.shouldWatch(id)) return
        // The list is also read from inside a tab's own close, when its descriptor is already disposed.
        Disposer.tryRegister(descriptor) { watcher.onTabClosed(id) }
    }

    private fun RunContentDescriptor.toLaunchTabInfo(): LaunchTabInfo {
        val configurationId = runConfigurationId()
        return LaunchTabInfo(
            id = remoteId,
            configurationId = configurationId,
            displayName = displayName,
            runProfileName = runProfileName(),
            configurationName = runConfigurationName(configurationId),
            isRunning = processHandler?.isProcessTerminated == false,
        )
    }

    private fun terminateLaunchProcessSilently(handler: ProcessHandler) {
        handler.putUserData(RunContentManagerImpl.ALWAYS_USE_DEFAULT_STOPPING_BEHAVIOUR_KEY, true)
        handler.putUserData(ProcessHandler.TERMINATION_REQUESTED, true)
        if (handler.isProcessTerminated) return
        if (!handler.isProcessTerminating) {
            handler.destroyProcess()
        }
        runCatching {
            handler.waitFor(PROCESS_TERMINATION_WAIT_MILLIS)
        }
    }

    private fun removeRunContentSilently(executor: Executor, descriptor: RunContentDescriptor) {
        val content = descriptor.attachedContent
        val previousTemporaryRemoved = content?.getUserData(Content.TEMPORARY_REMOVED_KEY)
        content?.putUserData(Content.TEMPORARY_REMOVED_KEY, true)
        try {
            RunContentManager.getInstance(project).removeRunContent(executor, descriptor)
        } finally {
            content?.putUserData(Content.TEMPORARY_REMOVED_KEY, previousTemporaryRemoved)
        }
    }

    /** What the tab's console shows now, read off the EDT because console documents allow background reads. */
    private fun consoleText(descriptor: RunContentDescriptor, maxChars: Int): String? {
        val consoleDocument = invokeOnEdtAndWait {
            val console = descriptor.executionConsole
            ((console as? ConsoleViewImpl)?.editor
                ?: console?.component?.let { component ->
                    DataManager.getInstance().getDataContext(component).getData(CommonDataKeys.EDITOR)
                })?.document
        } ?: return null
        return consoleDocument.immutableCharSequence
            .takeLast(maxChars)
            .toString()
    }

    private fun RunContentDescriptor.runConfigurationId(): String? =
        ExecutionManagerImpl.getInstanceIfCreated(project)
            ?.getExecutionEnvironments(this)
            ?.firstNotNullOfOrNull { it.runnerAndConfigurationSettings?.uniqueID }

    private fun RunContentDescriptor.runProfileName(): String? =
        ExecutionManagerImpl.getInstanceIfCreated(project)
            ?.getExecutionEnvironments(this)
            ?.firstNotNullOfOrNull { it.runProfile.name }

    private fun runConfigurationName(configurationId: String?): String? =
        configurationId?.let { id ->
            RunManager.getInstance(project).allSettings.firstOrNull { settings ->
                settings.uniqueID == id
            }?.name
        }

    private val RunContentDescriptor.remoteId: String
        get() = remoteLaunchTabId()

    private companion object {
        const val PROCESS_TERMINATION_WAIT_MILLIS = 500L
        val LOG = Logger.getInstance(IntelliJLaunchPort::class.java)
    }
}

internal fun RunContentDescriptor.remoteLaunchTabId(): String =
    processHandler?.remoteLaunchTabId() ?: executionId.takeIf { it != 0L }?.toString() ?: System.identityHashCode(this)
        .toString()

internal fun ProcessHandler.remoteLaunchTabId(): String =
    "process-${System.identityHashCode(this)}"

/** Factory for creating IntelliJ run port instances. */
internal class IntelliJLaunchPortFactory(private val project: Project) :
    app.tennyo.host.engine.panes.IdeLaunchPortFactory {
    override fun create(): app.tennyo.host.engine.panes.LaunchPortLease? {
        return app.tennyo.host.engine.panes.LaunchPortLease(IntelliJLaunchPort(project)) {}
    }

    override fun isAvailable(): Boolean = true
}
