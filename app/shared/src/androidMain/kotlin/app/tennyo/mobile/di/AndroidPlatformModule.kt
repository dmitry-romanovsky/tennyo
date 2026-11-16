package app.tennyo.mobile.di

import android.content.Context
import app.tennyo.mobile.AndroidBuildConfig
import app.tennyo.mobile.feature.local.localWorkspaceModule
import app.tennyo.mobile.feature.settings.AppSettingsStore
import app.tennyo.mobile.link.pairing.AndroidLocalNetworkDiscoveryGateway
import app.tennyo.mobile.link.pairing.AndroidRememberedPairStore
import app.tennyo.mobile.link.pairing.LocalNetworkDiscoveryGateway
import app.tennyo.mobile.link.pairing.RememberedPairStore
import app.tennyo.mobile.link.session.AndroidSecureCredentialStorage
import app.tennyo.mobile.link.session.SecureCredentialStorage
import app.tennyo.mobile.link.session.SessionNotification
import app.tennyo.mobile.link.transport.createOkHttpLinkClient
import app.tennyo.mobile.platform.AndroidSessionNotification
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** Android services, plus the on-device workspace, which only Android offers. */
val androidPlatformModule = module {
    includes(localWorkspaceModule)
    single<Settings> {
        SharedPreferencesSettings(androidContext().getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE))
    }
    single { AppSettingsStore(get(), dynamicColorAvailable = true) }
    single { createOkHttpLinkClient(get(), allowUnsafeTraffic = AndroidBuildConfig.allowUnsafeTraffic) }
    single<RememberedPairStore> { AndroidRememberedPairStore(androidContext()) }
    single<SecureCredentialStorage> { AndroidSecureCredentialStorage(androidContext()) }
    single<LocalNetworkDiscoveryGateway> { AndroidLocalNetworkDiscoveryGateway(androidContext()) }
    single<SessionNotification> { AndroidSessionNotification(androidContext()) }
}

private const val SETTINGS_FILE = "tennyo_settings"
