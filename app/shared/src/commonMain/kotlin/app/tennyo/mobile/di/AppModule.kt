package app.tennyo.mobile.di

import app.tennyo.mobile.root.RealRootComponent
import app.tennyo.mobile.root.RootComponent
import com.arkivanov.decompose.ComponentContext
import org.koin.dsl.module

/**
 * App navigation. A platform opts into the local workspace by binding a
 * [app.tennyo.mobile.feature.local.LocalWorkspaceEntry].
 */
val appModule = module {
    factory<RootComponent> { (componentContext: ComponentContext) ->
        RealRootComponent(
            componentContext = componentContext,
            sessions = get(),
            terminalPreferences = get(),
            knownHostDiscovery = get(),
            appSettingsStore = get(),
            sessionStore = get(),
            sessionController = get(),
            localWorkspace = getOrNull(),
            discoveryGateway = get(),
            pairingProbe = get(),
        )
    }
}
