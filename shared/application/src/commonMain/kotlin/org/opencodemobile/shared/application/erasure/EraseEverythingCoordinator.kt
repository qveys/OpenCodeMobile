package org.opencodemobile.shared.application.erasure

import kotlin.coroutines.cancellation.CancellationException
import org.opencodemobile.shared.application.notification.LocalNotificationCoordinator
import org.opencodemobile.shared.domain.cache.LocalCacheEraser
import org.opencodemobile.shared.domain.connection.OpenCodeGateway
import org.opencodemobile.shared.domain.connection.ServerCredentialStore
import org.opencodemobile.shared.domain.connection.ServerIdentityStore
import org.opencodemobile.shared.domain.connection.ServerProfileStore

/**
 * The local data categories "Tout effacer" erases (ADR 0009 §2.1).
 *
 * The enum is the contract the UI renders ("what will be erased") and the unit
 * test asserts ("all stores empty"), so the screen and the test cannot drift.
 */
public enum class ErasedCategory {
    /**
     * The live authenticated session. This is not a store: it drops the
     * in-memory credential permit and closes the gateway, so the erased
     * credential cannot keep authorising requests (ADR 0009 §2.4).
     */
    Session,

    /** SecureStore credentials / secrets. */
    Credentials,

    /** The locally stored server profile (host, port, label, transport). */
    Profile,

    /** The trust-on-first-use server identity pin. */
    IdentityPin,

    /** The encrypted cache database, its `-wal`/`-shm` sidecars, and its key. */
    Cache,

    /** Local notifications posted by the app. */
    Notifications,
}

/** One category that could not be erased, with the failure that stopped it. */
public data class EraseFailure(
    public val category: ErasedCategory,
    public val cause: Throwable,
)

/**
 * What an erasure actually did. [complete] is the only success signal the UI
 * may show as done; a non-empty [failures] must be surfaced because it is a
 * residue, not a detail to swallow.
 */
public data class EraseEverythingReport(
    public val erased: Set<ErasedCategory>,
    public val failures: List<EraseFailure> = emptyList(),
) {
    public val complete: Boolean get() = failures.isEmpty()
}

/**
 * Erases every local sensitive store in one user-confirmed action
 * ("Tout effacer", ADR 0009).
 *
 * It composes existing wipe primitives — it introduces no new storage layer:
 * the live session, the profile and credential secure stores, the TOFU identity
 * pin, the encrypted cache (via [LocalCacheEraser]) and the local notification
 * surface.
 *
 * Design rules:
 * - **Best-effort and complete.** Each category is attempted independently; a
 *   failure in one does not stop the others, so an erase never leaves more
 *   residue than necessary. Every failure is reported.
 * - **Session first.** [OpenCodeGateway.disconnect] drops the in-memory
 *   credential permit and closes the transport before anything else, so a
 *   partially erased device is never still authenticated (ADR 0009 §2.4).
 * - **Never silent.** The coordinator itself is only a composition; the explicit
 *   user confirmation is enforced by the caller
 *   ([org.opencodemobile.features.settings.EraseEverythingController]). Nothing
 *   here runs on connect, disconnect, or a background event.
 * - **Server untouched.** No method mutates server data: erasing is local-only.
 *
 * The credential and pin are keyed by the profile id, so the profile is read
 * first and its id used before the profile itself is cleared. With OP3 (one
 * profile) this covers every stored secret; a future multi-profile store must
 * enumerate ids here.
 */
public class EraseEverythingCoordinator(
    private val gateway: OpenCodeGateway,
    private val profileStore: ServerProfileStore,
    private val credentialStore: ServerCredentialStore,
    private val identityStore: ServerIdentityStore,
    private val cacheEraser: LocalCacheEraser,
    private val notifications: LocalNotificationCoordinator,
) {
    /**
     * Erases the local data categories and returns what was actually removed.
     *
     * Cancellation is never swallowed: a cancelled erase propagates so the
     * caller knows it is incomplete.
     */
    @Suppress("TooGenericExceptionCaught") // each category fails independently; the failure is captured, not swallowed
    public suspend fun erase(): EraseEverythingReport {
        val erased = linkedSetOf<ErasedCategory>()
        val failures = mutableListOf<EraseFailure>()

        // Stop authenticated traffic first: disconnect() clears the in-memory
        // credential permit and closes the client, so the erased credential can
        // no longer authorise a request even if a later step fails.
        attempt(ErasedCategory.Session, erased, failures) {
            gateway.disconnect()
        }

        var profileReadFailure: Throwable? = null
        val profile = try {
            profileStore.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            profileReadFailure = failure
            failures += EraseFailure(ErasedCategory.Profile, failure)
            null
        }

        val readFailure = profileReadFailure
        when {
            profile != null -> {
                attempt(ErasedCategory.Credentials, erased, failures) {
                    credentialStore.clearCredential(profile.id)
                }
                attempt(ErasedCategory.IdentityPin, erased, failures) {
                    identityStore.clearPinnedFingerprint(profile.id)
                }
            }

            // A readable but absent profile means no per-profile secret exists.
            readFailure == null -> {
                erased += ErasedCategory.Credentials
                erased += ErasedCategory.IdentityPin
            }

            // An unreadable profile means the id is unknown, so we cannot claim
            // the credential or the pin were erased. Report a residue instead of
            // a false success: a corrupted entry must not hide a secret (F2).
            else -> {
                failures += EraseFailure(ErasedCategory.Credentials, readFailure)
                failures += EraseFailure(ErasedCategory.IdentityPin, readFailure)
            }
        }

        attempt(ErasedCategory.Profile, erased, failures) {
            profileStore.clear()
        }
        attempt(ErasedCategory.Cache, erased, failures) {
            if (!cacheEraser.eraseLocalCache()) {
                throw IllegalStateException("The local cache left a residue after the wipe")
            }
        }
        attempt(ErasedCategory.Notifications, erased, failures) {
            notifications.cancelAll()
        }

        return EraseEverythingReport(erased = erased, failures = failures)
    }

    private suspend fun attempt(
        category: ErasedCategory,
        erased: MutableSet<ErasedCategory>,
        failures: MutableList<EraseFailure>,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            erased += category
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            failures += EraseFailure(category, failure)
        }
    }
}
