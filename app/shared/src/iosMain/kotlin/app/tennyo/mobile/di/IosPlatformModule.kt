package app.tennyo.mobile.di

import app.tennyo.mobile.feature.settings.AppSettingsStore
import app.tennyo.mobile.link.pairing.IosLocalNetworkDiscoveryGateway
import app.tennyo.mobile.link.pairing.IosRememberedPairStore
import app.tennyo.mobile.link.pairing.LocalNetworkDiscoveryGateway
import app.tennyo.mobile.link.pairing.RememberedPairStore
import app.tennyo.mobile.link.session.IosSecureCredentialStorage
import app.tennyo.mobile.link.session.SecureCredentialStorage
import app.tennyo.mobile.link.session.SessionNotification
import app.tennyo.mobile.link.transport.createLinkHttpClient
import app.tennyo.mobile.link.transport.pinnedChallengeHandler
import app.tennyo.mobile.platform.IosSessionNotification
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import io.ktor.client.engine.darwin.Darwin
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSUserDefaults

/** iOS binds no local workspace entry, so Home shows the remote flow only. */
fun iosPlatformModule(allowUnsafeTraffic: Boolean): Module = module {
    single<Settings> { NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults) }
    single { AppSettingsStore(get(), dynamicColorAvailable = false) }
    single {
        createLinkHttpClient(Darwin, get()) {
            handleChallenge(pinnedChallengeHandler(allowUnsafeTraffic))
        }
    }
    single<RememberedPairStore> { IosRememberedPairStore() }
    single<SecureCredentialStorage> { IosSecureCredentialStorage() }
    single<LocalNetworkDiscoveryGateway> { IosLocalNetworkDiscoveryGateway() }
    single<SessionNotification> { IosSessionNotification() }
}
