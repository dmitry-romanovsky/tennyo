package app.tennyo.mobile.di

import app.tennyo.mobile.feature.terminal.TerminalPreferences
import app.tennyo.mobile.link.LinkSessions
import app.tennyo.mobile.link.pairing.DeviceIdentity
import app.tennyo.mobile.link.pairing.FirstPairing
import app.tennyo.mobile.link.pairing.KnownHostDiscovery
import app.tennyo.mobile.link.pairing.KnownHostTargetStore
import app.tennyo.mobile.link.pairing.KnownHosts
import app.tennyo.mobile.link.pairing.LocalDiscovery
import app.tennyo.mobile.link.pairing.PairingProbe
import app.tennyo.mobile.link.pairing.RememberedPairResume
import app.tennyo.mobile.link.pairing.RememberedPairs
import app.tennyo.mobile.link.pairing.SessionEndpoints
import app.tennyo.mobile.link.pairing.SettingsKnownHostTargetStore
import app.tennyo.mobile.link.session.LinkServices
import app.tennyo.mobile.link.session.SessionCredentialStore
import app.tennyo.mobile.link.transport.LinkChannels
import app.tennyo.mobile.link.transport.LinkHttp
import app.tennyo.mobile.link.transport.linkJson
import app.tennyo.mobile.ui.appVersionName
import org.koin.dsl.module

/**
 * The host connection: transport, device identity, pairing and discovery, the session per active ticket, and the
 * pane clients. Needs the platform module's HTTP client, secure stores, discovery gateway, session notification and
 * [com.russhwolf.settings.Settings].
 */
val linkModule = module {
    single { linkJson() }
    single { DeviceIdentity() }
    single { LinkHttp(httpClient = get(), deviceIdentity = get()) }
    single { LinkChannels(httpClient = get(), deviceIdentity = get()) }
    single { SessionCredentialStore(get()) }
    single<KnownHostTargetStore> { SettingsKnownHostTargetStore(get()) }
    single { RememberedPairs(get()) }
    single { KnownHosts(store = get(), rememberedPairs = get()) }
    single {
        SessionEndpoints(
            http = get(),
            deviceIdentity = get(),
            rememberedPairs = get(),
            knownHosts = get(),
            clientVersion = appVersionName,
        )
    }
    single { PairingProbe(http = get()) }
    single { FirstPairing(http = get(), deviceIdentity = get(), rememberedPairs = get(), knownHosts = get()) }
    single { RememberedPairResume(http = get(), deviceIdentity = get(), rememberedPairs = get(), knownHosts = get()) }
    single<KnownHostDiscovery> {
        LocalDiscovery(
            http = get(),
            deviceIdentity = get(),
            rememberedPairs = get(),
            knownHosts = get(),
            gateway = get(),
        )
    }
    single {
        LinkSessions(
            LinkServices(
                http = get(),
                endpoints = get(),
                firstPairing = get(),
                rememberedPairResume = get(),
                channels = get(),
                credentials = get(),
                notification = get(),
            ),
        )
    }
    single { TerminalPreferences(get()) }
}
