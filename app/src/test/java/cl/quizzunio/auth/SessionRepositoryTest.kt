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
        override fun read(server: String) = saved
        override fun write(server: String, tokens: TokenPair) { saved = tokens }
        override fun clear() { saved = null }
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
        fun pair(access: String) = TokenPair(access, "refresh-$access", Instant.parse("2026-12-01T00:00:00Z"),
            Instant.parse("2026-12-20T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"))
        override suspend fun login(identifier: String, password: String) = pair("first")
        override suspend fun register(username: String, email: String, password: String) = pair("first")
        override suspend fun refresh(refreshToken: String): TokenPair {
            assertEquals("refresh-first", refreshToken)
            refreshes.incrementAndGet(); delay(5)
            failRefresh?.let { throw it }
            return pair("second")
        }
        override suspend fun me(token: String): UserIdentity {
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
        try { session.login("Account", "password"); fail("Falta identidad") } catch (error: ApiException) { assertEquals(503, error.status) }
        assertTrue(session.state.value is SessionState.Unavailable)
        assertFalse(AccessPolicy.canGenerateRemotely(session.state.value)); assertNotNull(store.saved)
        api.failMe = null; session.restore(); assertTrue(session.state.value is SessionState.Authenticated)
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
