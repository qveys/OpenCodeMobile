package org.opencodemobile.shared.security.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.opencodemobile.shared.domain.connection.DomainError
import org.opencodemobile.shared.domain.connection.ServerProfile

class SecureServerProfileStoreTest {

    @Test
    fun profileWithLabelRoundTrips() = runTest {
        val profiles = SecureServerProfileStore(InMemorySecureStore())
        val profile = ServerProfile(
            id = "profile-1",
            host = "192.168.1.10",
            port = 4096,
            label = "Home server",
        )

        profiles.save(profile)

        assertEquals(profile, profiles.load())
    }

    @Test
    fun profileWithoutLabelRoundTripsAsNull() = runTest {
        val profiles = SecureServerProfileStore(InMemorySecureStore())
        val profile = ServerProfile(id = "p1", host = "opencode.tailnet.ts.net", port = 8443)

        profiles.save(profile)

        assertEquals(profile, profiles.load())
    }

    @Test
    fun plaintextHttpTransportIsPreserved() = runTest {
        val profiles = SecureServerProfileStore(InMemorySecureStore())
        val profile = ServerProfile(
            id = "p1",
            host = "10.0.0.4",
            port = 80,
            tls = ServerProfile.TlsMode.PlaintextHttp,
        )

        profiles.save(profile)

        val loaded = profiles.load()
        assertEquals(ServerProfile.TlsMode.PlaintextHttp, loaded?.tls)
        assertEquals("http://10.0.0.4:80", loaded?.baseUrl)
    }

    @Test
    fun saveReplacesTheSingleStoredProfile() = runTest {
        val profiles = SecureServerProfileStore(InMemorySecureStore())

        profiles.save(ServerProfile(id = "p1", host = "one.local", port = 4096))
        profiles.save(ServerProfile(id = "p2", host = "two.local", port = 4096))

        assertEquals("two.local", profiles.load()?.host)
    }

    @Test
    fun clearRemovesTheStoredProfile() = runTest {
        val profiles = SecureServerProfileStore(InMemorySecureStore())
        profiles.save(ServerProfile(id = "p1", host = "one.local", port = 4096))

        profiles.clear()

        assertNull(profiles.load())
    }

    @Test
    fun fieldSeparatorsAndNewlinesInTheLabelSurviveTheEnvelope() = runTest {
        val profiles = SecureServerProfileStore(InMemorySecureStore())
        val trickyLabel = "line one\nline two\\backslash=equals"

        profiles.save(
            ServerProfile(id = "p1", host = "one.local", port = 4096, label = trickyLabel),
        )

        assertEquals(trickyLabel, profiles.load()?.label)
    }

    @Test
    fun unsupportedFormatFailsClosed() = runTest {
        val store = InMemorySecureStore()
        store.put(SecureServerProfileStore.DEFAULT_ENTRY_KEY, "v0\nid=p1\nhost=h\nport=1\ntls=Https\nlabel=")
        val profiles = SecureServerProfileStore(store)

        assertFailsWith<DomainError.StorageFailure> { profiles.load() }
    }

    @Test
    fun invalidPortFailsClosed() = runTest {
        val store = InMemorySecureStore()
        store.put(SecureServerProfileStore.DEFAULT_ENTRY_KEY, "v1\nid=p1\nhost=h\nport=not-a-port\ntls=Https\nlabel=")
        val profiles = SecureServerProfileStore(store)

        assertFailsWith<DomainError.StorageFailure> { profiles.load() }
    }
}
