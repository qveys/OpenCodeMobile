package org.opencodemobile.shared.application.interaction

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` that does **not** turn coroutine cancellation into a failure.
 *
 * Cancellation is structured concurrency, not a domain failure: swallowing it
 * would let a cancelled scope keep running mutation code (the same invariant the
 * realtime layer states in `FailFastMutationSender`).
 */
internal suspend fun <T> runCatchingNonCancellable(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

/** A message safe to show the user, falling back to the exception type name. */
internal fun Throwable.messageOrType(): String =
    message ?: this::class.simpleName ?: "interaction failed"

