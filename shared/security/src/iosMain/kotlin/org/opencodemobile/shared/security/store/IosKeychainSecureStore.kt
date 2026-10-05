package org.opencodemobile.shared.security.store

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import org.opencodemobile.shared.security.identity.toByteArray
import org.opencodemobile.shared.security.identity.toNSData
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS [SecureStore] backed by the Keychain (B2).
 *
 * Each entry is a generic-password item scoped to [service] and the entry key,
 * marked [kSecAttrAccessibleWhenUnlockedThisDeviceOnly] so secrets are never
 * synced to iCloud or restored onto another device. The Keychain performs the
 * dedicated key management: item encryption keys never leave the Secure
 * Enclave / device, and [destroy] removes every item under the service.
 *
 * @param service Keychain service name; it also plays the role of the
 *   dedicated key alias and isolates this store from sibling stores.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosKeychainSecureStore(
    private val service: String,
) : SecureStore {

    init {
        require(service.isNotBlank()) { "SecureStore service must not be blank" }
    }

    override val keyDescriptor: SecureKeyDescriptor = SecureKeyDescriptor(
        alias = service,
        algorithm = KEYCHAIN_ALGORITHM,
        hardwareBacked = true,
    )

    override suspend fun get(key: String): String? = memScoped {
        val query = baseQuery(key)
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)

        val result = alloc<COpaquePointerVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status == errSecItemNotFound) return@memScoped null
        if (status != errSecSuccess) {
            throw SecureStoreException.KeyUnavailable(
                "Keychain read failed (status $status); failing closed",
                osStatus = status,
            )
        }

        val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
        data.toByteArray().decodeToString()
    }

    override suspend fun put(key: String, value: String) {
        val encoded = value.encodeToByteArray().toNSData()
        val query = baseQuery(key)
        val attributes = valueAttributes(encoded)

        var status = SecItemUpdate(query, attributes)
        if (status == errSecItemNotFound) {
            val addQuery = baseQuery(key)
            CFDictionarySetValue(addQuery, kSecValueData, CFBridgingRetain(encoded))
            CFDictionarySetValue(
                addQuery,
                kSecAttrAccessible,
                kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            )
            status = SecItemAdd(addQuery, null)
            CFRelease(addQuery)
        }

        CFRelease(attributes)
        CFRelease(query)
        if (status != errSecSuccess) {
            throw SecureStoreException.KeyUnavailable(
                "Keychain store failed (status $status)",
                osStatus = status,
            )
        }
    }

    override suspend fun remove(key: String) {
        val query = baseQuery(key)
        SecItemDelete(query)
        CFRelease(query)
    }

    override suspend fun destroy() {
        val query = requireNotNull(
            CFDictionaryCreateMutable(
                null,
                0,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            ),
        ) { "CFDictionaryCreateMutable returned null" }
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, CFBridgingRetain(service))
        SecItemDelete(query)
        CFRelease(query)
    }

    private fun baseQuery(account: String): platform.CoreFoundation.CFMutableDictionaryRef {
        val query = requireNotNull(
            CFDictionaryCreateMutable(
                null,
                0,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            ),
        ) { "CFDictionaryCreateMutable returned null" }
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, CFBridgingRetain(service))
        CFDictionarySetValue(query, kSecAttrAccount, CFBridgingRetain(account))
        return query
    }

    private fun valueAttributes(value: NSData): platform.CoreFoundation.CFMutableDictionaryRef {
        val attributes = requireNotNull(
            CFDictionaryCreateMutable(
                null,
                0,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            ),
        ) { "CFDictionaryCreateMutable returned null" }
        CFDictionarySetValue(attributes, kSecValueData, CFBridgingRetain(value))
        return attributes
    }

    private companion object {
        const val KEYCHAIN_ALGORITHM = "iOS Keychain (Secure Enclave-protected class key)"
    }
}
