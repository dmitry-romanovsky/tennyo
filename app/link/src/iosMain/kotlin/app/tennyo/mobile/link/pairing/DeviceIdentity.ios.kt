package app.tennyo.mobile.link.pairing
import app.tennyo.crypto.P256Spki
import app.tennyo.crypto.sha256Url
import app.tennyo.crypto.toBase64Url
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.Foundation.NSUUID
import platform.Security.SecItemCopyMatching
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyRef
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnRef
import platform.UIKit.UIDevice

@OptIn(ExperimentalForeignApi::class)
actual class DeviceIdentity actual constructor() {
    private val privateKey: SecKeyRef by lazy { loadOrCreatePrivateKey() }
    private val publicKeyBytes: ByteArray by lazy { x509EncodedPublicKey(privateKey) }

    actual val publicKeyBase64: String by lazy { publicKeyBytes.toBase64Url() }
    actual val fingerprint: String by lazy { sha256Url(publicKeyBase64) }
    actual val clientId: String
        get() = "ios-${fingerprint.take(12)}"
    actual val clientName: String
        get() = iosDeviceName()

    actual fun newNonce(): String = NSUUID().UUIDString

    actual fun sign(message: ByteArray): String {
        val messageData = message.toCfData()
        val signature = SecKeyCreateSignature(
            privateKey,
            kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
            messageData,
            null,
        ) ?: run {
            CFRelease(messageData)
            throw IllegalStateException("Unable to sign with the iOS device key.")
        }
        return try {
            signature.toByteArray().toBase64Url()
        } finally {
            CFRelease(signature)
            CFRelease(messageData)
        }
    }

    private fun iosDeviceName(): String {
        val device = UIDevice.currentDevice
        val model = device.model.trim()
        val systemName = device.systemName.trim()
        val systemVersion = device.systemVersion.trim()
        return listOf(model, systemName, systemVersion)
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")
            ?: FALLBACK_DEVICE_NAME
    }

    private fun loadOrCreatePrivateKey(): SecKeyRef = loadPrivateKey() ?: createPrivateKey()

    private fun loadPrivateKey(): SecKeyRef? = memScoped {
        val query = CFDictionaryCreateMutable(null, 0, null, null)
            ?: throw IllegalStateException("Unable to create iOS Keychain query.")
        val applicationTag = KEY_TAG.toCfData()
        CFDictionarySetValue(query, kSecClass, kSecClassKey)
        CFDictionarySetValue(query, kSecAttrApplicationTag, applicationTag)
        CFDictionarySetValue(query, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
        CFDictionarySetValue(query, kSecAttrKeyClass, kSecAttrKeyClassPrivate)
        CFDictionarySetValue(query, kSecReturnRef, kCFBooleanTrue)

        val result = alloc<COpaquePointerVar>()
        try {
            when (val status = SecItemCopyMatching(query, result.ptr.reinterpret())) {
                errSecSuccess -> result.value?.reinterpret()
                errSecItemNotFound -> null
                else -> throw IllegalStateException("Unable to load iOS Tennyo Companion device key: $status")
            }
        } finally {
            CFRelease(query)
            CFRelease(applicationTag)
        }
    }

    private fun createPrivateKey(): SecKeyRef = memScoped {
        val attributes = CFDictionaryCreateMutable(null, 0, null, null)
            ?: throw IllegalStateException("Unable to create iOS key attributes.")
        val privateAttributes = CFDictionaryCreateMutable(null, 0, null, null)
            ?: throw IllegalStateException("Unable to create iOS private key attributes.")
        val keySize = CFNumberCreate(null, kCFNumberIntType, intArrayOf(256).usePinned { it.addressOf(0) })
            ?: throw IllegalStateException("Unable to create iOS key size attribute.")
        val applicationTag = KEY_TAG.toCfData()
        CFDictionarySetValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
        CFDictionarySetValue(attributes, kSecAttrKeySizeInBits, keySize)
        CFDictionarySetValue(privateAttributes, kSecAttrIsPermanent, kCFBooleanTrue)
        CFDictionarySetValue(privateAttributes, kSecAttrApplicationTag, applicationTag)
        CFDictionarySetValue(privateAttributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        CFDictionarySetValue(attributes, kSecPrivateKeyAttrs, privateAttributes)

        try {
            SecKeyCreateRandomKey(attributes, null)
                ?: throw IllegalStateException("Unable to create iOS Tennyo Companion device key.")
        } finally {
            CFRelease(keySize)
            CFRelease(privateAttributes)
            CFRelease(attributes)
            CFRelease(applicationTag)
        }
    }

    private fun x509EncodedPublicKey(privateKey: SecKeyRef): ByteArray {
        val publicKey = SecKeyCopyPublicKey(privateKey)
            ?: throw IllegalStateException("Unable to access iOS Tennyo Companion public key.")
        val rawPublicKeyData = SecKeyCopyExternalRepresentation(publicKey, null)
            ?: run {
                CFRelease(publicKey)
                throw IllegalStateException("Unable to export iOS Tennyo Companion public key.")
            }
        return try {
            P256Spki.fromRawPoint(rawPublicKeyData.toByteArray())
        } finally {
            CFRelease(rawPublicKeyData)
            CFRelease(publicKey)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun String.toCfData() = encodeToByteArray().toCfData()

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toCfData() = usePinned { pinned ->
    CFDataCreate(null, pinned.addressOf(0).reinterpret<UByteVar>(), size.convert())
        ?: throw IllegalStateException("Unable to create iOS CoreFoundation data.")
}

@OptIn(ExperimentalForeignApi::class)
internal fun CPointer<*>.toByteArray(): ByteArray {
    val length = CFDataGetLength(reinterpret()).toInt()
    val bytes = CFDataGetBytePtr(reinterpret()) ?: return ByteArray(0)
    return ByteArray(length) { index -> bytes[index].toByte() }
}

private val KEY_TAG = "app.tennyo.mobile.link-device-key".encodeToByteArray()

internal actual val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default

/** Sent to the host as this device's name when UIKit reports none; the IDE shows it in its session list. */
private const val FALLBACK_DEVICE_NAME = "iOS device"
