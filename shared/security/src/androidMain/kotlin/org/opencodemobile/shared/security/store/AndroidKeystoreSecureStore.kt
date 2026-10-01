package org.opencodemobile.shared.security.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android [SecureStore] backed by the Android Keystore (B2).
 *
 * The dedicated AES-256 key is generated on first write and never leaves the
 * Keystore; only AES/GCM ciphertext (IV + tag + payload) is persisted, in the
 * app's private preferences. There is no plaintext fallback: an unusable key
 * fails the operation instead of writing readable data.
 *
 * The preferences file carries no Android auto-backup exemption of its own;
 * the ciphertext is device-bound, so a backup restored onto another device
 * cannot be decrypted and surfaces as [SecureStoreException.CorruptedEntry]
 * rather than as a leaked secret.
 *
 * @param namespace isolates this store from sibling stores (own preferences
 *   file and own Keystore alias).
 */
public class AndroidKeystoreSecureStore(
    context: Context,
    namespace: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SecureStore {

    init {
        require(namespace.isNotBlank()) { "SecureStore namespace must not be blank" }
    }

    private val preferences: android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(
            "$PREFERENCES_PREFIX.$namespace",
            Context.MODE_PRIVATE,
        )

    private val keyAlias: String = "$KEY_ALIAS_PREFIX.$namespace"

    private val mutex = Mutex()

    /**
     * The dedicated key is created on first write, so the hardware-backing
     * report is derived from the Keystore on every read rather than asserted at
     * construction time. Before the key exists there is nothing to inspect and
     * the descriptor reports `hardwareBacked = false`.
     */
    override val keyDescriptor: SecureKeyDescriptor
        get() = SecureKeyDescriptor(
            alias = keyAlias,
            algorithm = TRANSFORMATION,
            hardwareBacked = isHardwareBacked(),
        )

    override suspend fun get(key: String): String? = withContext(ioDispatcher) {
        mutex.withLock {
            val stored = preferences.getString(key, null) ?: return@withLock null
            decrypt(key, stored)
        }
    }

    override suspend fun put(key: String, value: String): Unit = withContext(ioDispatcher) {
        mutex.withLock {
            val persisted = preferences.edit()
                .putString(key, encrypt(value))
                .commit()
            if (!persisted) {
                throw SecureStoreException.WriteFailed("Failed to persist an encrypted entry under key '$key'")
            }
        }
    }

    override suspend fun remove(key: String) {
        withContext(ioDispatcher) {
            mutex.withLock {
                if (!preferences.edit().remove(key).commit()) {
                    throw SecureStoreException.WriteFailed("Failed to remove the entry under key '$key'")
                }
            }
        }
    }

    override suspend fun destroy(): Unit = withContext(ioDispatcher) {
        mutex.withLock {
            preferences.edit().clear().commit()
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        }
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(createIfMissing = true))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            SEPARATOR +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(key: String, stored: String): String {
        val parts = stored.split(SEPARATOR)
        if (parts.size != 2) {
            throw SecureStoreException.CorruptedEntry(key, "Malformed ciphertext for key '$key'")
        }
        return decryptParts(key, parts[0], parts[1])
    }

    private fun decryptParts(key: String, encodedIv: String, encodedCiphertext: String): String =
        try {
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            val ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(createIfMissing = false), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (failure: GeneralSecurityException) {
            throw SecureStoreException.CorruptedEntry(key, "Could not decrypt the entry for key '$key'", failure)
        } catch (failure: IllegalArgumentException) {
            throw SecureStoreException.CorruptedEntry(key, "Could not decode the entry for key '$key'", failure)
        }

    /**
     * Reads the dedicated key from the Keystore without creating it. Returns
     * `null` when the alias is absent; a genuine Keystore failure propagates as
     * a [GeneralSecurityException] so callers can fail closed.
     */
    private fun readSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    /**
     * Reports whether the dedicated key is held by hardware-backed storage, as
     * the platform [KeyInfo] sees it. A software Keystore answers `false`; a
     * missing key or an unreadable descriptor also answers `false` rather than
     * asserting a protection the key may not have.
     *
     * `minSdk` is 31, so `securityLevel` is preferred over the deprecated
     * `isInsideSecureHardware` / `isStrongBoxBacked` pair (both deprecated in
     * API 31) and reports the TEE and StrongBox cases alike.
     */
    private fun isHardwareBacked(): Boolean {
        // A missing key or an unreadable descriptor is the documented "not
        // hardware-backed" answer, not a failure: the exceptions are expected
        // and intentionally discarded (detekt names this `expected`).
        val key = try {
            readSecretKey()
        } catch (expected: GeneralSecurityException) {
            null
        } catch (expected: IOException) {
            null
        } ?: return false

        return try {
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            when (keyInfo.securityLevel) {
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> true
                else -> false
            }
        } catch (expected: GeneralSecurityException) {
            false
        } catch (expected: IllegalArgumentException) {
            false
        }
    }

    private fun secretKey(createIfMissing: Boolean): SecretKey {
        return try {
            readSecretKey()?.let { return it }
            if (!createIfMissing) {
                throw SecureStoreException.KeyUnavailable(
                    "The dedicated key '$keyAlias' is missing; the stored entry is unrecoverable",
                )
            }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build(),
            )
            generator.generateKey()
        } catch (failure: GeneralSecurityException) {
            throw SecureStoreException.KeyUnavailable(
                "The dedicated key '$keyAlias' is unavailable; refusing to fall back to plaintext",
                failure,
            )
        }
    }

    private companion object {
        const val PREFERENCES_PREFIX = "opencodemobile_secure_store"
        const val KEY_ALIAS_PREFIX = "opencodemobile.securestore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val SEPARATOR = ":"
    }
}
