package org.opencodemobile.shared.application.composer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.chat.ChatPrompt
import org.opencodemobile.shared.domain.chat.ComposerDraftStore
import org.opencodemobile.shared.domain.chat.OpenCodeChatGateway
import org.opencodemobile.shared.domain.chat.TranscriptMessage
import org.opencodemobile.shared.domain.dictation.DictationAvailability
import org.opencodemobile.shared.domain.dictation.DictationEvent
import org.opencodemobile.shared.domain.dictation.DictationProvider
import org.opencodemobile.shared.domain.dictation.DictationUnavailableReason
import org.opencodemobile.shared.domain.dictation.FailClosedDictationProvider

private class RecordingChatGateway : OpenCodeChatGateway {
    val sent: MutableList<ChatPrompt> = mutableListOf()

    override suspend fun transcript(sessionId: String): List<TranscriptMessage> = emptyList()

    override suspend fun sendPrompt(sessionId: String, prompt: ChatPrompt) {
        sent += prompt
    }
}

private class FakeDictationProvider(
    private val availabilityFor: (String) -> DictationAvailability = { DictationAvailability.Available(it) },
) : DictationProvider {
    val requestedLocales: MutableList<String> = mutableListOf()
    val events: Channel<DictationEvent> = Channel(Channel.UNLIMITED)
    var cancelCount: Int = 0

    override suspend fun availability(locale: String): DictationAvailability {
        requestedLocales += locale
        return availabilityFor(locale)
    }

    override fun transcribe(locale: String): Flow<DictationEvent> = events.receiveAsFlow()

    override suspend fun cancel() {
        cancelCount++
    }

    /** Delivers a cumulative partial; the session stays open. */
    suspend fun partial(text: String) {
        events.send(DictationEvent.Partial(text))
    }

    /** Delivers a final transcript and ends the session (as the platforms do). */
    suspend fun completeWithFinal(text: String) {
        events.send(DictationEvent.Final(text))
        events.close()
    }

    /** Fails closed and ends the session (as the platforms do). */
    suspend fun failWith(reason: DictationUnavailableReason) {
        events.send(DictationEvent.Unavailable(reason))
        events.close()
    }
}

private class InMemoryDraftStore : ComposerDraftStore {
    private val drafts = mutableMapOf<String, String>()

    override suspend fun loadDraft(sessionId: String): String = drafts[sessionId].orEmpty()

    override suspend fun saveDraft(sessionId: String, draft: String) {
        drafts[sessionId] = draft
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerControllerTest {

    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        chat: OpenCodeChatGateway = RecordingChatGateway(),
        dictation: DictationProvider = FakeDictationProvider(),
        drafts: ComposerDraftStore? = null,
    ): ComposerController = ComposerController(
        chat = chat,
        dictation = dictation,
        drafts = drafts,
        sessionId = "ses_1",
        scope = scope,
    )

    @Test
    fun finalTranscriptionWritesDraftAndNeverSends() = runTest {
        val chat = RecordingChatGateway()
        val dictation = FakeDictationProvider()
        val composer = controller(this, chat = chat, dictation = dictation)

        composer.onLocaleChanged("en")
        advanceUntilIdle()
        composer.startDictation()
        advanceUntilIdle()
        assertTrue(composer.state.value.dictating)

        dictation.partial("ship")
        advanceUntilIdle()
        assertEquals("ship", composer.state.value.draft)

        dictation.completeWithFinal("ship the fix")
        advanceUntilIdle()

        assertEquals("ship the fix", composer.state.value.draft)
        assertFalse(composer.state.value.dictating)
        // The dictation path must not send: no gateway call happened.
        assertTrue(chat.sent.isEmpty())
    }

    @Test
    fun partialsReplaceTheSessionTextInsteadOfAppending() = runTest {
        val dictation = FakeDictationProvider()
        val composer = controller(this, dictation = dictation)
        composer.onLocaleChanged("en")
        advanceUntilIdle()
        composer.startDictation()
        advanceUntilIdle()

        dictation.partial("hello")
        advanceUntilIdle()
        dictation.partial("hello world")
        advanceUntilIdle()

        assertEquals("hello world", composer.state.value.draft)
        composer.stopDictation()
        advanceUntilIdle()
    }

    @Test
    fun dictationAppendsToAnExistingDraft() = runTest {
        val dictation = FakeDictationProvider()
        val composer = controller(this, dictation = dictation)
        composer.onLocaleChanged("en")
        advanceUntilIdle()
        composer.onDraftChange("Existing prompt")
        composer.startDictation()
        advanceUntilIdle()

        dictation.completeWithFinal("and more")
        advanceUntilIdle()

        assertEquals("Existing prompt and more", composer.state.value.draft)
    }

    @Test
    fun sendRequiresAnExplicitTapAndClearsTheDraft() = runTest {
        val chat = RecordingChatGateway()
        val drafts = InMemoryDraftStore()
        val composer = controller(this, chat = chat, drafts = drafts)
        composer.onLocaleChanged("en")
        advanceUntilIdle()
        composer.onDraftChange("  deploy the server  ")
        advanceUntilIdle()

        assertTrue(composer.state.value.canSend)
        composer.send()
        advanceUntilIdle()

        assertEquals(1, chat.sent.size)
        assertEquals("deploy the server", chat.sent.single().text)
        assertEquals("", composer.state.value.draft)
        assertEquals("", drafts.loadDraft("ses_1"))
    }

    @Test
    fun changingLocaleRechecksOnDeviceCapability() = runTest {
        val dictation = FakeDictationProvider { locale ->
            if (locale == "fr") {
                DictationAvailability.Unavailable(DictationUnavailableReason.LocaleNotSupported)
            } else {
                DictationAvailability.Available(locale)
            }
        }
        val composer = controller(this, dictation = dictation)

        composer.onLocaleChanged("en")
        advanceUntilIdle()
        assertIs<DictationAvailability.Available>(composer.state.value.dictation)
        assertTrue(composer.state.value.canDictate)

        composer.onLocaleChanged("fr")
        advanceUntilIdle()
        assertEquals(
            DictationAvailability.Unavailable(DictationUnavailableReason.LocaleNotSupported),
            composer.state.value.dictation,
        )
        assertFalse(composer.state.value.canDictate)
        assertEquals(listOf("en", "fr"), dictation.requestedLocales)
    }

    @Test
    fun startDictationIsRefusedWhenUnavailable() = runTest {
        val dictation = FakeDictationProvider {
            DictationAvailability.Unavailable(DictationUnavailableReason.OnDeviceModelMissing)
        }
        val composer = controller(this, dictation = dictation)
        composer.onLocaleChanged("fr")
        advanceUntilIdle()

        composer.startDictation()
        advanceUntilIdle()

        assertFalse(composer.state.value.dictating)
    }

    @Test
    fun unavailableEventDisablesDictationWithoutNetworkRetry() = runTest {
        val dictation = FakeDictationProvider()
        val composer = controller(this, dictation = dictation)
        composer.onLocaleChanged("en")
        advanceUntilIdle()
        composer.startDictation()
        advanceUntilIdle()

        dictation.failWith(DictationUnavailableReason.OnDeviceModelMissing)
        advanceUntilIdle()

        assertEquals(
            DictationAvailability.Unavailable(DictationUnavailableReason.OnDeviceModelMissing),
            composer.state.value.dictation,
        )
        assertFalse(composer.state.value.canDictate)
        assertFalse(composer.state.value.dictating)
    }

    @Test
    fun failClosedProviderNeverOffersDictation() = runTest {
        assertEquals(
            DictationAvailability.Unavailable(DictationUnavailableReason.NotWired),
            FailClosedDictationProvider.availability("en"),
        )
        assertFalse(
            controller(this, dictation = FailClosedDictationProvider).let { composer ->
                composer.onLocaleChanged("en")
                advanceUntilIdle()
                composer.state.value.canDictate
            },
        )
    }
}
