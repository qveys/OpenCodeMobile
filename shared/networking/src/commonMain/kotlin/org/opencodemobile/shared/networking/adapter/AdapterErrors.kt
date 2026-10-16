package org.opencodemobile.shared.networking.adapter

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import org.opencodemobile.shared.domain.interaction.InteractionNotConnectedException
import org.opencodemobile.shared.domain.session.ServerUnavailableException
import org.opencodemobile.shared.domain.session.SessionNotConnectedException
import org.opencodemobile.shared.domain.session.SessionNotFoundException
import org.opencodemobile.shared.domain.session.SessionRejectedException

/** Keep transport failures inside the networking boundary. */
internal suspend fun <T> sessionCall(sessionId: String? = null, block: suspend () -> T): T = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (notConnected: InteractionNotConnectedException) {
    throw SessionNotConnectedException(cause = notConnected)
} catch (clientError: ClientRequestException) {
    if (clientError.response.status == HttpStatusCode.NotFound) {
        throw SessionNotFoundException(sessionId = sessionId.orEmpty(), cause = clientError)
    }
    throw SessionRejectedException(message = "The server refused the session request", cause = clientError)
} catch (failure: ResponseException) {
    throw ServerUnavailableException(cause = failure)
} catch (failure: IOException) {
    throw ServerUnavailableException(cause = failure)
} catch (failure: SerializationException) {
    throw ServerUnavailableException(cause = failure)
}

/** Only an absent catalog route is optional; outages must remain visible. */
internal suspend fun <T> optionalSurface(block: suspend () -> T): T? = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: ClientRequestException) {
    if (failure.response.status == HttpStatusCode.NotFound) null else throw failure
}
