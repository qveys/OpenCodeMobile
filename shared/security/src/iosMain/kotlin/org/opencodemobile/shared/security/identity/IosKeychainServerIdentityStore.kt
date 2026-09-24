package org.opencodemobile.shared.security.identity

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import platform.CoreFoundation.CFBridgingRelease
import platform.CoreFoundation.CFBridgingRetain
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
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
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks

/**
 * iOS [ServerIdentityStore] backed by the Keychain (B2).
 *
 * The pinned fingerprint is stored as a generic-password item scoped to the
 * server profile id, marked [kSecAttrAccessibleWhenUnlockedThisDeviceOnly] so it
 * is never synced to iCloud or restored onto another device. This is the same
 * Keychain boundary used for server credentials, per
 * `docs/ARCHITECTURE.md` §"Server identity verification (T1)".
 */
@OptIn(ExperimentalForeignApi::class)
public class IosKeychainServerIdentityStore : ServerIdentityStore {

    override suspend fun pinnedFingerprint(profileId: String): ServerFingerprint? = memScoped {
        val query = baseQuery(profileId)
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)

        val result = alloc<COpaquePointerVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status != errSecSuccess) return@memScoped null

        val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
        ServerFingerprint.fromHex(data.toByteArray().decodeToString())
    }

    override suspend fun storePinnedFingerprint(
        profileId: String,
        fingerprint: ServerFingerprint,
    ) {
        val value = fingerprint.hex.encodeToByteArray().toNSData()
        val query = baseQuery(profileId)
        val attributes = valueAttributes(value)

        var status = SecItemUpdate(query, attributes)
        if (status == errSecItemNotFound) {
            val addQuery = baseQuery(profileId)
            CFDictionarySetValue(addQuery, kSecValueData, CFBridgingRetain(value))
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
        check(status == errSecSuccess) { "Keychain store of the server identity pin failed ($status)" }
    }

    override suspend fun clearPinnedFingerprint(profileId: String) {
        val query = baseQuery(profileId)
        SecItemDelete(query)
        CFRelease(query)
    }

    private fun baseQuery(profileId: String): platform.CoreFoundation.CFMutableDictionaryRef {
        val query = CFDictionaryCreateMutable(
            null,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, CFBridgingRetain(KEYCHAIN_SERVICE))
        CFDictionarySetValue(query, kSecAttrAccount, CFBridgingRetain(profileId))
        return query
    }

    private fun valueAttributes(value: NSData): platform.CoreFoundation.CFMutableDictionaryRef {
        val attributes = CFDictionaryCreateMutable(
            null,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionarySetValue(attributes, kSecValueData, CFBridgingRetain(value))
        return attributes
    }

    private companion object {
        const val KEYCHAIN_SERVICE = "org.opencodemobile.serverIdentity"
    }
}