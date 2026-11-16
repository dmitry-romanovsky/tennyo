package app.tennyo.mobile.link.pairing
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.tennyo.crypto.secureRandomBytes
import app.tennyo.crypto.sha256Url
import app.tennyo.crypto.toBase64Url
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Locale

actual class DeviceIdentity actual constructor() {
    private val keyPair: KeyPair by lazy { loadOrCreateKeyPair() }
    private val encodedPublicKey: String by lazy { keyPair.public.encoded.toBase64Url() }
    private val deviceFingerprint: String by lazy { sha256Url(encodedPublicKey) }

    actual val publicKeyBase64: String
        get() = encodedPublicKey

    actual val fingerprint: String
        get() = deviceFingerprint

    actual val clientId: String
        get() = "android-${deviceFingerprint.take(12)}"

    actual val clientName: String
        get() = androidDeviceDisplayName(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE)

    actual fun newNonce(): String = secureRandomBytes(NONCE_BYTES).toBase64Url()

    actual fun sign(message: ByteArray): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(keyPair.private)
        signature.update(message)
        return signature.sign().toBase64Url()
    }

    private fun loadOrCreateKeyPair(): KeyPair {
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                generateAndroidKeyStorePair()
            }
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
            KeyPair(keyStore.getCertificate(KEY_ALIAS).publicKey, privateKey)
        }.getOrElse { error ->
            throw IllegalStateException("Unable to create Android device key for Tennyo Companion pairing.", error)
        }
    }

    private fun generateAndroidKeyStorePair() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        generator.generateKeyPair()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "link_device_key"
        private const val NONCE_BYTES = 24
    }
}

internal actual val cryptoDispatcher: CoroutineDispatcher = Dispatchers.IO

internal fun androidDeviceDisplayName(
    manufacturer: String?,
    model: String?,
    androidVersion: String? = null,
): String {
    val maker = manufacturer.normalizedNamePart()
    val modelName = model.normalizedNamePart()
    val deviceName = when {
        maker.isEmpty() && modelName.isEmpty() -> "Android device"
        // Some makers already lead the model with their name ("Motorola Edge"); saying it twice reads as a glitch.
        maker.isEmpty() || modelName.startsWith(maker, ignoreCase = true) -> modelName.titleCased()
        modelName.isEmpty() -> maker.titleCased()
        else -> "${maker.titleCased()} ${modelName.titleCased()}"
    }
    val version = androidVersion?.trim().orEmpty()
    return if (version.isEmpty()) deviceName else "$deviceName Android $version"
}

private val Whitespace = Regex("\\s+")

private fun String?.normalizedNamePart(): String = orEmpty().trim().replace(Whitespace, " ")

/** Capitalizes each word of an all-lowercase name; a name with capitals is already styled by its maker. */
private fun String.titleCased(): String =
    if (any(Char::isUpperCase)) {
        this
    } else {
        split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.US) } }
    }
