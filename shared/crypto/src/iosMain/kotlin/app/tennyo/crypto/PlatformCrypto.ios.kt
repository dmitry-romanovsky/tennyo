@file:OptIn(ExperimentalForeignApi::class)

package app.tennyo.crypto

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
import platform.CoreCrypto.CCCryptorCreateWithMode
import platform.CoreCrypto.CCCryptorRefVar
import platform.CoreCrypto.CCCryptorRelease
import platform.CoreCrypto.CCCryptorUpdate
import platform.CoreCrypto.ccNoPadding
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCModeCTR
import platform.CoreCrypto.kCCModeOptionCTR_BE
import platform.CoreCrypto.kCCSuccess
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFNumberIntType
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyKeyExchangeResult
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.SecKeyVerifySignature
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDHKeyExchangeStandard
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecRandomDefault
import platform.posix.size_tVar

// Security.framework imports and exports raw X9.63 points while the protocol speaks SPKI, so every key crosses
// P256Spki on the way in and out. Getting this backwards fails only at the final step of pairing, and only on iOS.

actual fun verifyEcdsaP256Sha256(spki: ByteArray, message: ByteArray, derSignature: ByteArray): Boolean {
    val publicKey = (if (derSignature.isEmpty()) null else importPublicKey(spki)) ?: return false
    val messageData = message.toCfData()
    val signatureData = derSignature.toCfData()
    return try {
        SecKeyVerifySignature(
            publicKey,
            kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
            messageData,
            signatureData,
            null,
        )
    } finally {
        CFRelease(signatureData)
        CFRelease(messageData)
        CFRelease(publicKey)
    }
}

actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (size == 0) return bytes
    val status = bytes.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, size.convert(), pinned.addressOf(0))
    }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed with status $status." }
    return bytes
}

actual fun aes256Ctr(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
    require(key.size == AES_256_KEY_BYTES && iv.size == AES_BLOCK_BYTES) {
        "AES-256-CTR needs a 32-byte key and a 16-byte IV"
    }
    if (input.isEmpty()) return ByteArray(0)
    val output = ByteArray(input.size)
    memScoped {
        val cryptor = alloc<CCCryptorRefVar>()
        val created = key.usePinned { keyPin ->
            iv.usePinned { ivPin ->
                CCCryptorCreateWithMode(
                    kCCEncrypt,
                    kCCModeCTR,
                    kCCAlgorithmAES,
                    ccNoPadding,
                    ivPin.addressOf(0),
                    keyPin.addressOf(0),
                    key.size.convert(),
                    null,
                    0u,
                    0,
                    kCCModeOptionCTR_BE,
                    cryptor.ptr,
                )
            }
        }
        check(created == kCCSuccess) { "CCCryptorCreateWithMode failed with status $created." }
        try {
            val moved = alloc<size_tVar>()
            val status = input.usePinned { inputPin ->
                output.usePinned { outputPin ->
                    CCCryptorUpdate(
                        cryptor.value,
                        inputPin.addressOf(0),
                        input.size.convert(),
                        outputPin.addressOf(0),
                        output.size.convert(),
                        moved.ptr,
                    )
                }
            }
            check(status == kCCSuccess && moved.value.toInt() == input.size) {
            "CCCryptorUpdate failed with status $status."
        }
        } finally {
            CCCryptorRelease(cryptor.value)
        }
    }
    return output
}

/** Created with `kSecAttrIsPermanent = false`: the key never reaches the keychain and disappears with the attempt. */
actual class EphemeralKeyAgreement actual constructor() : AutoCloseable {
    private val privateKey: SecKeyRef = createEphemeralPrivateKey()

    actual val publicKeySpki: ByteArray = P256Spki.fromRawPoint(exportRawPublicKey(privateKey))

    actual fun sharedSecret(peerSpki: ByteArray): ByteArray? {
        val peerKey = importPublicKey(peerSpki) ?: return null
        val exchangeParameters = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
        val secret = SecKeyCopyKeyExchangeResult(
            privateKey,
            kSecKeyAlgorithmECDHKeyExchangeStandard,
            peerKey,
            exchangeParameters,
            null,
        )
        CFRelease(exchangeParameters)
        CFRelease(peerKey)
        return secret?.let {
            try {
                it.toByteArray()
            } finally {
                CFRelease(it)
            }
        }
    }

    /** Releases the Security key ref; without this every attempt would leak one. */
    actual override fun close() {
        CFRelease(privateKey)
    }
}

private fun importPublicKey(spki: ByteArray): SecKeyRef? {
    val rawPoint = P256Spki.rawPoint(spki) ?: return null
    val attributes = CFDictionaryCreateMutable(kCFAllocatorDefault, 2, null, null)
    CFDictionarySetValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
    CFDictionarySetValue(attributes, kSecAttrKeyClass, kSecAttrKeyClassPublic)
    val pointData = rawPoint.toCfData()
    val key = SecKeyCreateWithData(pointData, attributes, null)
    CFRelease(pointData)
    CFRelease(attributes)
    return key
}

private fun createEphemeralPrivateKey(): SecKeyRef {
    val privateKeyAttributes = CFDictionaryCreateMutable(kCFAllocatorDefault, 1, null, null)
    CFDictionarySetValue(privateKeyAttributes, kSecAttrIsPermanent, kCFBooleanFalse)
    val keySize = intArrayOf(P256_KEY_BITS).usePinned { CFNumberCreate(null, kCFNumberIntType, it.addressOf(0)) }
        ?: error("Unable to create the ephemeral key size attribute.")
    val attributes = CFDictionaryCreateMutable(kCFAllocatorDefault, 3, null, null)
    CFDictionarySetValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
    CFDictionarySetValue(attributes, kSecAttrKeySizeInBits, keySize)
    CFDictionarySetValue(attributes, kSecPrivateKeyAttrs, privateKeyAttributes)
    val key = SecKeyCreateRandomKey(attributes, null)
    CFRelease(keySize)
    CFRelease(attributes)
    CFRelease(privateKeyAttributes)
    return key ?: error("Unable to create an ephemeral P-256 key.")
}

private fun exportRawPublicKey(privateKey: SecKeyRef): ByteArray {
    val publicKey = SecKeyCopyPublicKey(privateKey) ?: error("Unable to access the ephemeral public key.")
    val rawData = SecKeyCopyExternalRepresentation(publicKey, null)
    CFRelease(publicKey)
    checkNotNull(rawData) { "Unable to export the ephemeral public key." }
    return try {
        rawData.toByteArray()
    } finally {
        CFRelease(rawData)
    }
}

private fun ByteArray.toCfData(): CFDataRef =
    usePinned { pinned ->
        val bytes = if (isEmpty()) null else pinned.addressOf(0).reinterpret<UByteVar>()
        CFDataCreate(null, bytes, size.convert())
    } ?: error("Unable to create CoreFoundation data.")

private fun CPointer<*>.toByteArray(): ByteArray {
    val data: CFDataRef = reinterpret()
    val length = CFDataGetLength(data).toInt()
    val bytes = CFDataGetBytePtr(data) ?: return ByteArray(0)
    return ByteArray(length) { index -> bytes[index].toByte() }
}

private const val P256_KEY_BITS = 256
