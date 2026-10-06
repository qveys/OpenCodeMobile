package org.opencodemobile.shared.security.cache

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencodemobile.shared.domain.cache.CacheKeyStore

/**
 * Android [CacheKeyStore] backed by the Android Keystore (B2).
 *
 * A random passphrase is generated once, encrypted with an AES/GCM key that
 * never leaves the Keystore, and only the wrapped ciphertext is persisted in the
 * app's private preferences. The passphrase is never stored in cleartext
 * (OPE-107 acceptance).
 *
 * If the Keystore key is invalidated — common after a biometric re-enrollment —
 * decryption fails. That is a **cache miss, not a fatal error**: the stale entry
 * is dropped and [existingPassphrase] returns null, so the caller wipes and
 * rebuilds the disposable cache (`docs/ARCHITECTURE.md` §"Local cache encryption
 * at rest").
 */
public class AndroidKeystoreCacheKeyStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CacheKeyStore {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    // A passphrase that cannot be decrypted is unusable key material, not an
    // error to surface: the entry is dropped and the caller rebuilds the cache
    // (the same key-loss-is-a-cache-miss rule as CacheDatabase).
    @Suppress("SwallowedException")
    override suspend fun existingPassphrase(): ByteArray? = withContext(ioDispatcher) {
        mutex.withLock {
            val stored = preferences.getString(ENTRY_KEY, null) ?: return@withLock null
            try {
                decrypt(stored)
            } catch (invalidated: GeneralSecurityException) {
                // Key loss (e.g. invalidated by biometric re-enrollment): drop the
                // unreadable entry so the caller can rebuild the cache.
                preferences.edit().remove(ENTRY_KEY).commit()
                null
            } catch (malformed: IllegalArgumentException) {
                preferences.edit().remove(ENTRY_KEY).commit()
                null
            }
        }
    }

    override suspend fun createPassphrase(): ByteArray = withContext(ioDispatcher) {
        mutex.withLock {
            val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
            val persisted = preferences.edit()
                .putString(ENTRY_KEY, encrypt(passphrase))
                .commit()
            check(persisted) { "Failed to persist the cache passphrase; failing closed" }
            passphrase
        }
    }

    override suspend fun deletePassphrase(): Unit = withContext(ioDispatcher) {
        mutex.withLock {
            val persisted = preferences.edit().remove(ENTRY_KEY).commit()
            // Fail closed like createPassphrase: a failed prefs write would
            // otherwise be reported as a successful key wipe while the wrapped
            // passphrase is still on disk (review F3).
            check(persisted) { "Failed to remove the cache passphrase; failing closed" }
            // Also drop the wrapping Keystore key, so no cache key material
            // remains at all (ADR 0009 §2.1.3, review F4).
            deleteWrappingKey()
        }
    }

    private fun deleteWrappingKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext)
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            SEPARATOR +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): ByteArray {
        val parts = stored.split(SEPARATOR)
        require(parts.size == 2) { "Malformed keystore entry" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "opencodemobile_cache_key"
        const val ENTRY_KEY = "cache_passphrase"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "opencodemobile.cache.passphrase_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SEPARATOR = ":"
        const val PASSPHRASE_BYTES = 32
    }
}
