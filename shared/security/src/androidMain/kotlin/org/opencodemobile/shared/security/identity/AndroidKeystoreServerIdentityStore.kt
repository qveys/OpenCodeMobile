package org.opencodemobile.shared.security.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencodemobile.shared.domain.connection.ServerFingerprint
import org.opencodemobile.shared.domain.connection.ServerIdentityStore

/**
 * Android [ServerIdentityStore] backed by the Android Keystore (B2).
 *
 * The pinned fingerprint is encrypted with an AES/GCM key that never leaves the
 * Keystore, then persisted as ciphertext in the app's private preferences. No
 * plaintext fingerprint is ever written to disk, matching the "same
 * Keychain/Keystore boundary already used for credentials" requirement in
 * `docs/ARCHITECTURE.md` §"Server identity verification (T1)".
 */
public class AndroidKeystoreServerIdentityStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ServerIdentityStore {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun pinnedFingerprint(profileId: String): ServerFingerprint? =
        withContext(ioDispatcher) {
            mutex.withLock {
                val stored = preferences.getString(entryKey(profileId), null) ?: return@withLock null
                ServerFingerprint.fromHex(decrypt(stored))
            }
        }

    override suspend fun storePinnedFingerprint(
        profileId: String,
        fingerprint: ServerFingerprint,
    ): Unit = withContext(ioDispatcher) {
        mutex.withLock {
            val persisted = preferences.edit()
                .putString(entryKey(profileId), encrypt(fingerprint.hex))
                .commit()
            check(persisted) { "Failed to persist the server identity pin; failing closed" }
        }
    }

    override suspend fun clearPinnedFingerprint(profileId: String): Unit =
        withContext(ioDispatcher) {
            mutex.withLock {
                val cleared = preferences.edit().remove(entryKey(profileId)).commit()
                check(cleared) { "Failed to clear the server identity pin" }
            }
        }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            SEPARATOR +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val parts = stored.split(SEPARATOR)
        require(parts.size == 2) { "Malformed keystore entry" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
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

    private fun entryKey(profileId: String): String = "server_identity_pin:$profileId"

    private companion object {
        const val PREFERENCES_NAME = "opencodemobile_server_identity"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "opencodemobile.server_identity.pin_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SEPARATOR = ":"
    }
}