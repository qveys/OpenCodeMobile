package org.opencodemobile.shared.security.store

import org.opencodemobile.shared.domain.connection.DomainError

/**
 * Maps a low-level [SecureStoreException] to the app's typed storage failure.
 *
 * `shared/domain` cannot reference `SecureStoreException` — it must not depend
 * on `shared/security` (architecture rule §5.2.1) — so the bridge lives in the
 * layer that raises the exception. The secure-store adapters apply it so a
 * Keystore or ciphertext failure reaches the controller as
 * `DomainError.StorageFailure` (retryable) instead of `DomainError.Unknown`
 * (OPE-145 finding 2).
 */
public fun SecureStoreException.toDomainError(): DomainError =
    DomainError.StorageFailure(message ?: "Secure store failure", this)
