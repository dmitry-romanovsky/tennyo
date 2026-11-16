package app.tennyo.mobile.link.session

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
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
class IosSecureCredentialStorage : SecureCredentialStorage {
    override suspend fun readActive(): CredentialReadResult = withContext(Dispatchers.Default) {
        memScoped {
            val query = query() ?: return@memScoped CredentialReadResult.IoFailure
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            val result = alloc<COpaquePointerVar>()
            try {
                when (SecItemCopyMatching(query, result.ptr.reinterpret())) {
                    errSecSuccess -> result.value?.let { value ->
                        try {
                            CredentialReadResult.Found(value.toByteArray().decodeToString())
                        } catch (_: Throwable) {
                            CredentialReadResult.Corrupt
                        } finally {
                            CFRelease(value)
                        }
                    } ?: CredentialReadResult.Corrupt

                    errSecItemNotFound -> CredentialReadResult.NotFound
                    else -> CredentialReadResult.IoFailure
                }
            } finally {
                CFRelease(query)
            }
        }
    }

    override suspend fun write(record: String): CredentialWriteResult = withContext(Dispatchers.Default) {
        memScoped {
            val query = query() ?: return@memScoped CredentialWriteResult.IoFailure
            val attributes = newRetainingDictionary()
                ?: return@memScoped CredentialWriteResult.IoFailure
            val data = record.encodeToByteArray().toCfData()
            try {
                CFDictionarySetValue(attributes, kSecValueData, data)
                when (SecItemUpdate(query, attributes)) {
                    errSecSuccess -> CredentialWriteResult.Stored
                    errSecItemNotFound -> {
                        CFDictionarySetValue(
                            query,
                            kSecAttrAccessible,
                            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
                        )
                        CFDictionarySetValue(query, kSecValueData, data)
                        if (SecItemAdd(query, null) == errSecSuccess) CredentialWriteResult.Stored
                        else CredentialWriteResult.IoFailure
                    }

                    else -> CredentialWriteResult.IoFailure
                }
            } finally {
                CFRelease(data); CFRelease(attributes); CFRelease(query)
            }
        }
    }

    override suspend fun clear(): CredentialClearResult = withContext(Dispatchers.Default) {
        memScoped {
            val query = query() ?: return@memScoped CredentialClearResult.IoFailure
            try {
                when (SecItemDelete(query)) {
                    errSecSuccess, errSecItemNotFound -> CredentialClearResult.Cleared
                    else -> CredentialClearResult.IoFailure
                }
            } finally {
                CFRelease(query)
            }
        }
    }

    private fun query(): CFMutableDictionaryRef? {
        val query = newRetainingDictionary() ?: return null
        val service = SERVICE.toCfString() ?: return null
        val account = ACCOUNT.toCfString() ?: return null
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, service)
        CFDictionarySetValue(query, kSecAttrAccount, account)
        CFRelease(service); CFRelease(account)
        return query
    }

    /**
     * Keychain queries retain their CF string/data values until the Security call completes.
     * Without these callbacks, releasing a value after insertion leaves a dangling pointer.
     */
    private fun newRetainingDictionary(): CFMutableDictionaryRef? =
        CFDictionaryCreateMutable(
            null,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )

    private fun String.toCfString(): CPointer<*>? = CFStringCreateWithCString(null, this, kCFStringEncodingUTF8)
    private fun ByteArray.toCfData() =
        usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), size.convert())!! }

    private fun CPointer<*>.toByteArray(): ByteArray {
        val length = CFDataGetLength(reinterpret()).toInt()
        val bytes = CFDataGetBytePtr(reinterpret()) ?: return ByteArray(0)
        return ByteArray(length) { bytes[it].toByte() }
    }

    private companion object {
        const val SERVICE = "app.tennyo.mobile.link-credential"
        const val ACCOUNT = "active"
    }
}
