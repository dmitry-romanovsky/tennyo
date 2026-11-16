package app.tennyo.mobile.link.pairing

import app.tennyo.mobile.link.LinkTicket
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

@OptIn(ExperimentalForeignApi::class)
class IosRememberedPairStore : RememberedPairStore {
    override fun load(artifact: LinkTicket): RememberedPair? {
        val companionId = artifact.companionId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val canonicalAccount = artifact.canonicalRememberedPairStorageKey()?.let(::account) ?: return null
        val encoded = read(canonicalAccount) ?: return null
        val parts = encoded.split('\n')
        if (parts.size != STORED_FIELD_COUNT) {
            removeByCompanionId(companionId)
            return null
        }
        val expiresAt = parts[2].toLongOrNull() ?: run {
            removeByCompanionId(companionId)
            return null
        }
        if (expiresAt <= kotlin.time.Clock.System.now().toEpochMilliseconds()) {
            removeByCompanionId(companionId)
            return null
        }
        return RememberedPair(
            pairId = parts[0],
            pairSecret = parts[1],
            expiresAtEpochMillis = expiresAt,
        )
    }

    override fun save(artifact: LinkTicket, material: RememberedPairMaterial) {
        val companionId = material.companionId?.trim()?.takeIf { it.isNotBlank() }
            ?: artifact.companionId?.trim()?.takeIf { it.isNotBlank() }
            ?: return
        val canonicalAccount = account(companionRememberedPairStorageKey(companionId))
        write(
            account = canonicalAccount,
            value = listOf(
                material.pairId,
                material.pairSecret,
                material.expiresAtEpochMillis.toString(),
            ).joinToString("\n"),
        )
    }

    override fun remove(artifact: LinkTicket) {
        artifact.companionId?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::removeByCompanionId)
    }

    override fun removeByCompanionId(companionId: String) {
        delete(account(companionRememberedPairStorageKey(companionId)))
    }

    private fun read(account: String): String? = memScoped {
        val query = CFDictionaryCreateMutable(null, 0, null, null)
            ?: throw IllegalStateException("Unable to create iOS Keychain query.")
        val service = SERVICE.toCfString()
        val accountValue = account.toCfString()
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, service)
        CFDictionarySetValue(query, kSecAttrAccount, accountValue)
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<COpaquePointerVar>()
        try {
            when (SecItemCopyMatching(query, result.ptr.reinterpret())) {
                errSecSuccess -> result.value?.let { value ->
                    try {
                        value.toByteArray().decodeToString()
                    } finally {
                        CFRelease(value)
                    }
                }

                errSecItemNotFound -> null
                else -> null
            }
        } finally {
            CFRelease(query)
            CFRelease(service)
            CFRelease(accountValue)
        }
    }

    private fun write(account: String, value: String) {
        delete(account)
        memScoped {
            val query = CFDictionaryCreateMutable(null, 0, null, null)
                ?: throw IllegalStateException("Unable to create iOS Keychain query.")
            val service = SERVICE.toCfString()
            val accountValue = account.toCfString()
            val data = value.encodeToByteArray().toCfData()
            try {
                CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(query, kSecAttrService, service)
                CFDictionarySetValue(query, kSecAttrAccount, accountValue)
                CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                CFDictionarySetValue(query, kSecValueData, data)
                SecItemAdd(query, null)
            } finally {
                CFRelease(data)
                CFRelease(query)
                CFRelease(service)
                CFRelease(accountValue)
            }
        }
    }

    private fun delete(account: String) {
        memScoped {
            val query = CFDictionaryCreateMutable(null, 0, null, null)
                ?: throw IllegalStateException("Unable to create iOS Keychain query.")
            val service = SERVICE.toCfString()
            val accountValue = account.toCfString()
            try {
                CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(query, kSecAttrService, service)
                CFDictionarySetValue(query, kSecAttrAccount, accountValue)
                SecItemDelete(query)
            } finally {
                CFRelease(query)
                CFRelease(service)
                CFRelease(accountValue)
            }
        }
    }

    private fun account(storageKey: String): String = storageKey

    private fun String.toCfString(): CPointer<*> =
        CFStringCreateWithCString(null, this, kCFStringEncodingUTF8)
            ?: throw IllegalStateException("Unable to create iOS Keychain string.")

    private fun ByteArray.toCfData() = usePinned { pinned ->
        CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.convert())
            ?: throw IllegalStateException("Unable to create iOS Keychain data.")
    }

    private fun CPointer<*>.toByteArray(): ByteArray {
        val length = CFDataGetLength(reinterpret()).toInt()
        val bytes = CFDataGetBytePtr(reinterpret()) ?: return ByteArray(0)
        return ByteArray(length) { index -> bytes[index].toByte() }
    }

    private companion object {
        private const val SERVICE = "app.tennyo.mobile.link-pairs"

        /** A stored pair is the pair id, its secret and its expiry, one per line. */
        private const val STORED_FIELD_COUNT = 3
    }
}
