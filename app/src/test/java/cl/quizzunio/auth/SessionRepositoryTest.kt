package cl.quizzunio.auth

import cl.quizzunio.network.ApiException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Pruebas de componente: AuthGateway y TokenStore sustituyen transporte y Keystore. */
class SessionRepositoryTest {
    private class MemoryTokens : TokenStore {
        var saved: TokenPair? = null
        var failClear = false
        override fun read(server: String) = saved
        override fun write(server: String, tokens: TokenPair) { saved = tokens }
        override fun clear() {
            if (failClear) throw java.io.IOException("Disco no disponible")
            saved = null
        }
    }
    private open class Gateway : AuthGateway {
        val refreshes = AtomicInteger()
        var failRefresh: Exception? = null
        var failMe: Exception? = null
        var failLogout = false
        var failActivity: Exception? = null
        var activities = 0
        var logouts = 0
        var passwordChanges = 0
        var deletions = 0
        var logins = 0
        var registrations = 0
        var profiles = 0
        fun pair(access: String) = TokenPair(access, "refresh-$access", Instant.parse("2026-12-01T00:00:00Z"),
            Instant.parse("2026-12-20T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"))
        override suspend fun login(identifier: String, password: String): TokenPair {
            logins++
            return pair("first")
        }
        override suspend fun register(username: String, email: String, password: String): TokenPair {
            registrations++
            return pair("first")
        }
        override suspend fun refresh(refreshToken: String): TokenPair {
            assertEquals("refresh-first", refreshToken)
            refreshes.incrementAndGet(); delay(5)
            failRefresh?.let { throw it }
            return pair("second")
        }
        override suspend fun me(token: String): UserIdentity {
            profiles++
            failMe?.let { throw it }; return UserIdentity(17, "Account", "account@example.com", UserTier.Premium)
        }
        override suspend fun logout(token: String) {
            logouts++; if (failLogout) throw ApiException(0, "NETWORK_ERROR", "No confirmado")
        }
        override suspend fun activity(token: String) {
            failActivity?.let { throw it }
            activities++
        }
        override suspend fun changePassword(token: String, current: String, replacement: String) { passwordChanges++ }
        override suspend fun deleteAccount(token: String, current: String) { deletions++ }
    }
    private fun session(api: Gateway, store: MemoryTokens) = SessionRepository(api, store, "https://example.com")

    @Test fun freshInstallIsAnonymousWithoutContactingTheServer() = runBlocking {
        val api = object : Gateway() { override suspend fun me(token: String): UserIdentity = error("No debe solicitar identidad sin tokens") }
        val session = session(api, MemoryTokens()); session.restore()
        assertSame(SessionState.Anonymous, session.state.value)
        assertEquals(0, api.refreshes.get()); assertEquals(0, api.activities)
    }

    @Test fun concurrentUnauthorizedRequestsConsumeRefreshOnlyOnce() = runBlocking {
        val api = Gateway(); val store = MemoryTokens(); val session = session(api, store)
        session.restore(); session.login("Account", "password")
        val results = (1..12).map { async(Dispatchers.Default) {
            session.authorized { token ->
                if (token == "first") throw ApiException(401, "INVALID_TOKEN", "Expirado")
                token
            }
        } }.awaitAll()
        assertEquals(List(12) { "second" }, results)
        assertEquals(1, api.refreshes.get()); assertEquals("refresh-second", store.saved?.refreshToken)
    }

    @Test fun lostRefreshResponseInvalidatesSessionAndCannotReuseTheToken() = runBlocking {
        val api = Gateway().apply { failRefresh = ApiException(0, "NETWORK_ERROR", "Respuesta perdida") }
        val store = MemoryTokens(); val session = session(api, store); session.login("Account", "password")
        try { session.authorized<Unit> { throw ApiException(401, "INVALID_TOKEN", "Expirado") }; fail("Debe fallar") }
        catch (error: ApiException) { assertEquals("NETWORK_ERROR", error.code) }
        assertSame(SessionState.Anonymous, session.state.value); assertNull(store.saved)
        try { session.authorized { fail("No debe ejecutar solicitudes después de invalidarse") }; fail("Debe exigir sesión") }
        catch (error: IllegalStateException) { assertEquals(1, api.refreshes.get()) }
    }

    @Test fun incorrectCurrentPasswordDoesNotRefreshOrDestroySession() = runBlocking {
        val api = Gateway(); val store = MemoryTokens(); val session = session(api, store); session.login("Account", "password")
        try { session.authorized<Unit> { throw ApiException(401, "INVALID_CREDENTIALS", "Contraseña incorrecta") }; fail("Debe fallar") }
        catch (error: ApiException) { assertEquals("INVALID_CREDENTIALS", error.code) }
        assertTrue(session.state.value is SessionState.Authenticated); assertNotNull(store.saved); assertEquals(0, api.refreshes.get())
    }

    @Test fun restorationUsesServerIdentityAndDoesNotDeclareActivity() = runBlocking {
        val api = Gateway(); val store = MemoryTokens().apply { saved = api.pair("first") }
        val session = session(api, store); session.restore()
        assertEquals(UserTier.Premium, (session.state.value as SessionState.Authenticated).user.tier)
        assertEquals(0, api.activities)
        session.recordInteraction(); session.recordInteraction(); assertEquals(1, api.activities)
    }

    @Test fun failedLogoutKeepsSessionAndExplicitAbandonmentIsLocal() = runBlocking {
        val api = Gateway().apply { failLogout = true }; val store = MemoryTokens(); val session = session(api, store)
        session.login("Account", "password")
        try { session.logout(); fail("No debe fingir revocación") } catch (error: ApiException) { assertEquals(0, error.status) }
        assertTrue(session.state.value is SessionState.Authenticated)
        session.abandon(); assertSame(SessionState.Anonymous, session.state.value); assertNull(store.saved); assertEquals(1, api.logouts)
    }

    @Test fun confirmedAccountOperationsInvalidateLocalTokens() = runBlocking {
        val api = Gateway(); val store = MemoryTokens(); val session = session(api, store)
        session.login("Account", "password"); session.changePassword("old", "replacement")
        assertEquals(1, api.passwordChanges); assertNull(store.saved); assertSame(SessionState.Anonymous, session.state.value)
        session.login("Account", "replacement"); session.deleteAccount("replacement")
        assertEquals(1, api.deletions); assertNull(store.saved); assertSame(SessionState.Anonymous, session.state.value)
        session.login("Account", "replacement"); session.logout()
        assertEquals(1, api.logouts); assertNull(store.saved); assertSame(SessionState.Anonymous, session.state.value)
    }

    @Test fun unavailableIdentityNeverInventsAPlan() = runBlocking {
        val api = Gateway().apply { failMe = ApiException(503, "AUTH_UNAVAILABLE", "No disponible") }
        val store = MemoryTokens(); val session = session(api, store)
        session.restore()
        try { session.login("Account", "password"); fail("Falta identidad") } catch (error: ApiException) { assertEquals(503, error.status) }
        assertTrue(session.state.value is SessionState.Unavailable)
        assertFalse(AccessPolicy.canGenerateRemotely(session.state.value)); assertNotNull(store.saved)
        api.failMe = null; session.restore(); assertTrue(session.state.value is SessionState.Authenticated)
    }

    @Test fun registrationAndLoginFromAnonymousRecoverProfileWithoutRepeatingPost() = runBlocking {
        for (register in listOf(false, true)) {
            for ((status, code) in listOf(404 to "HTTP_ERROR", 503 to "AUTH_UNAVAILABLE",
                0 to "NETWORK_ERROR", 200 to "INVALID_RESPONSE")) {
                val store = MemoryTokens()
                lateinit var session: SessionRepository
                val api = object : Gateway() {
                    override suspend fun me(token: String): UserIdentity {
                        assertSame(SessionState.VerifyingIdentity, session.state.value)
                        assertNotNull(store.saved)
                        return super.me(token)
                    }
                }
                api.failMe = ApiException(status, code, "Perfil no disponible")
                session = session(api, store)
                session.restore()
                try {
                    if (register) session.register("Account", "account@example.com", "password")
                    else session.login("Account", "password")
                    fail("Debe propagar la causa técnica")
                } catch (error: ApiException) { assertEquals(code, error.code) }
                val failed = session.state.value as SessionState.Unavailable
                assertTrue(failed.message.contains(if (register) "aceptó el registro" else "aceptó el inicio"))
                assertFalse(AccessPolicy.canUseCloud(failed))
                api.failMe = null
                session.restore()
                assertTrue(session.state.value is SessionState.Authenticated)
                assertEquals(if (register) 0 else 1, api.logins)
                assertEquals(if (register) 1 else 0, api.registrations)
                assertEquals(2, api.profiles)
                assertEquals(0, api.logouts)
            }
        }
    }

    @Test fun pendingIdentitySurvivesReopenAndFailedAbandonNeverPublishesAnonymous() = runBlocking {
        val store = MemoryTokens()
        val api = Gateway().apply { failMe = ApiException(404, "HTTP_ERROR", "Falta perfil") }
        val first = session(api, store)
        first.restore()
        try { first.login("Account", "password"); fail("Debe fallar el perfil") }
        catch (error: ApiException) { assertEquals(404, error.status) }
        val reopened = session(api, store)
        reopened.restore()
        assertTrue(reopened.state.value is SessionState.Unavailable)
        store.failClear = true
        try { reopened.abandon(); fail("Debe informar el fallo de limpieza") }
        catch (error: java.io.IOException) { assertNotNull(store.saved) }
        assertTrue(reopened.state.value is SessionState.Unavailable)
        api.failMe = null
        reopened.restore()
        assertTrue(reopened.state.value is SessionState.Unavailable)
        assertEquals(2, api.profiles)
        store.failClear = false
        reopened.restore()
        assertSame(SessionState.Anonymous, reopened.state.value)
        assertNull(store.saved)
        assertEquals(0, api.logouts)
        reopened.login("Account", "password")
        assertTrue(reopened.state.value is SessionState.Authenticated)
        assertEquals(2, api.logins)
    }

    @Test fun abandonmentSurvivesRepositoryRecreationAndBlocksRefresh() = runBlocking {
        class Journal : SessionCleanupJournal {
            var pending = false
            var failBegin = false
            var failFinish = false
            override fun isPending() = pending
            override fun begin() {
                if (failBegin) throw java.io.IOException("No se puede guardar la intención")
                pending = true
            }
            override fun finish() {
                if (failFinish) throw java.io.IOException("Interrupción al terminar")
                pending = false
            }
        }
        val api = Gateway()
        val store = MemoryTokens()
        val journal = Journal()
        fun reopen() = session(api, store).apply { cleanupJournal = journal }
        val first = reopen()
        first.login("Account", "password")
        journal.failBegin = true
        assertTrue(runCatching { first.abandon() }.exceptionOrNull() is java.io.IOException)
        assertFalse(journal.pending)
        assertNotNull(store.saved)
        assertTrue(first.state.value is SessionState.Unavailable)

        journal.failBegin = false
        store.failClear = true
        first.restore()
        assertTrue(journal.pending)
        val second = reopen()
        second.restore()
        assertTrue(second.state.value is SessionState.Unavailable)
        assertTrue(runCatching { second.authorized { fail("No se debe usar el par") } }.isFailure)
        assertEquals(1, api.profiles)
        assertEquals(0, api.refreshes.get())

        store.failClear = false
        journal.failFinish = true
        second.restore()
        assertNull(store.saved)
        assertTrue(journal.pending)
        assertTrue(second.state.value is SessionState.Unavailable)
        journal.failFinish = false
        val third = reopen()
        third.restore()
        assertSame(SessionState.Anonymous, third.state.value)
        assertFalse(journal.pending)
        assertEquals(0, api.logouts)
        third.login("Account", "password")
        assertTrue(third.state.value is SessionState.Authenticated)
    }

    @Test fun verifiesRegisteredIdentityOnRetryAndAfterReopening() = runBlocking {
        var profile = UserIdentity(17, "josé", "account@example.com", UserTier.Free)
        val api = object : Gateway() {
            override suspend fun me(token: String): UserIdentity = profile
        }
        val store = MemoryTokens()
        val first = session(api, store)
        val error = runCatching { first.register("Jose\u0301", "Account@EXAMPLE.COM", "password") }.exceptionOrNull() as ApiException
        assertEquals("REGISTRATION_IDENTITY_MISMATCH", error.code)
        assertNotNull(store.saved?.registrationIdentity)
        profile = profile.copy(username = "José", email = "other@example.com")
        val reopened = session(api, store)
        reopened.restore()
        assertTrue(reopened.state.value is SessionState.Unavailable)
        profile = profile.copy(email = "account@example.com")
        reopened.restore()
        assertEquals(profile, (reopened.state.value as SessionState.Authenticated).user)
        assertNull(store.saved?.registrationIdentity)
        assertEquals(1, api.registrations)
        assertEquals(0, api.logouts)
    }

    @Test fun uncertainRegisterOffersLoginWithoutRetryButDefinitiveErrorsArePreserved() = runBlocking {
        for ((status, code) in listOf(0 to "NETWORK_ERROR", 200 to "INVALID_RESPONSE", 504 to "HTTP_ERROR",
            400 to "INVALID_REQUEST", 409 to "IDENTITY_CONFLICT")) {
            var requests = 0
            val api = object : Gateway() {
                override suspend fun register(username: String, email: String, password: String): TokenPair {
                    requests++
                    throw ApiException(status, code, "Registro")
                }
            }
            val store = MemoryTokens()
            val repository = session(api, store)
            repository.restore()
            val result = runCatching { repository.register("Account", "account@example.com", "password") }
            if (status in listOf(400, 409)) assertEquals(code, (result.exceptionOrNull() as ApiException).code)
            else assertTrue(result.exceptionOrNull() is RegistrationUnconfirmed)
            assertSame(SessionState.Anonymous, repository.state.value)
            assertNull(store.saved)
            repository.login("Account", "password")
            assertTrue(repository.state.value is SessionState.Authenticated)
            assertEquals(1, requests)
        }
    }

    @Test fun expiredRestorationClearsTokensAndEmitsSessionNotice() = runBlocking {
        val api = Gateway().apply {
            failMe = ApiException(401, "INVALID_TOKEN", "Expirado")
            failRefresh = ApiException(401, "INVALID_TOKEN", "Revocado")
        }
        val store = MemoryTokens().apply { saved = api.pair("first") }
        val session = session(api, store)
        session.restore()
        assertSame(SessionState.Anonymous, session.state.value)
        assertNull(store.saved)
        assertNotNull(session.sessionNotice.value)
        session.clearSessionNotice()
        assertNull(session.sessionNotice.value)
    }

    @Test fun interactionFailureDistinguishesNetworkErrorFromRevocation() = runBlocking {
        val api = Gateway()
        val store = MemoryTokens()
        val session = session(api, store)
        session.login("Account", "password")

        api.failActivity = ApiException(0, "NETWORK_ERROR", "Sin conexión")
        try { session.recordInteraction(); fail("Debe propagar error de red") }
        catch (error: ApiException) { assertEquals(0, error.status) }
        assertTrue(session.state.value is SessionState.Authenticated)
        assertNotNull(store.saved)
    }
}
