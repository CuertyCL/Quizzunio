package cl.quizzunio.auth

import cl.quizzunio.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializa operaciones protegidas y la rotación estricta del par de tokens. */
class SessionRepository(private val api: AuthGateway, private val store: TokenStore, val server: String) {
    private val mutex = Mutex()
    private var tokens: TokenPair? = null
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    private val mutableSessionNotice = MutableStateFlow<String?>(null)
    val sessionNotice: StateFlow<String?> = mutableSessionNotice.asStateFlow()
    private var lastActivityNanos: Long? = null
    private var cleanupPending = false
    private var identityRecoveryMessage = "No se pudo verificar la identidad de la sesión guardada."

    fun clearSessionNotice() { mutableSessionNotice.value = null }

    suspend fun restore() = mutex.withLock {
        mutableState.value = SessionState.Restoring

        try {
            if (cleanupPending) {
                abandonLocked()
                return@withLock
            }
            tokens = withContext(Dispatchers.IO) { store.read(server) }
            if (tokens == null) mutableState.value = SessionState.Anonymous
            else establishIdentity()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (mutableState.value is SessionState.Anonymous) {
                mutableSessionNotice.value = "Tu sesión anterior expiró o fue revocada. Continúas en modo anónimo."
            } else if (mutableState.value !is SessionState.Unavailable) {
                mutableState.value = SessionState.Unavailable(error.message ?: "No se pudo restaurar la sesión.")
            }
        }
    }

    suspend fun login(identifier: String, password: String) = mutex.withLock {
        obtainSession("El servidor aceptó el inicio de sesión, pero no se pudo verificar tu identidad.") {
            api.login(identifier, password)
        }
    }

    suspend fun register(username: String, email: String, password: String) = mutex.withLock {
        obtainSession("El servidor aceptó el registro, pero no se pudo verificar tu identidad.") {
            api.register(username, email, password)
        }
    }

    private suspend fun obtainSession(profileError: String, request: suspend () -> TokenPair) {
        check(tokens == null && !cleanupPending &&
            (state.value is SessionState.Anonymous || state.value is SessionState.Restoring)) {
            "Recupera o abandona la sesión anterior antes de iniciar otra."
        }
        val previous = state.value
        mutableState.value = SessionState.ObtainingTokens
        val pair = try {
            request()
        } catch (error: Exception) {
            mutableState.value = previous
            throw error
        }
        accept(pair)
        identityRecoveryMessage = profileError
        establishIdentity()
    }

    private suspend fun establishIdentity() {
        mutableState.value = SessionState.VerifyingIdentity
        try {
            mutableState.value = SessionState.Authenticated(authenticated {
                api.me(it)
            })
        } catch (cancelled: CancellationException) {
            if (mutableState.value is SessionState.VerifyingIdentity)
                mutableState.value = SessionState.Unavailable(identityRecoveryMessage)
            throw cancelled
        } catch (error: Exception) {
            if (mutableState.value is SessionState.VerifyingIdentity)
                mutableState.value = SessionState.Unavailable(identityRecoveryMessage)
            throw error
        }
    }

    suspend fun <T> authorized(action: suspend (String) -> T): T = mutex.withLock {
        check(mutableState.value is SessionState.Authenticated) {
            "Inicia sesión y verifica tu identidad antes de usar la nube."
        }

        authenticated(action)
    }

    private suspend fun <T> authenticated(action: suspend (String) -> T): T {
        val current = tokens ?: throw ApiException(401, "INVALID_TOKEN", "Inicia sesión.")

        try {
            return action(current.accessToken)
        } catch (error: ApiException) {
            if (error.status != 401 || error.code != "INVALID_TOKEN") throw error

            val replacement = try {
                api.refresh(current.refreshToken)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    abandonLocked()
                }
                throw cancelled
            } catch (failure: ApiException) {
                if (failure.status == 401 || failure.status == 0 || failure.code == "INVALID_RESPONSE")
                    abandonLocked()
                throw failure
            } catch (failure: Exception) {
                withContext(NonCancellable) {
                    abandonLocked()
                }
                throw failure
            }

            accept(replacement)

            try {
                return action(replacement.accessToken)
            } catch (failure: ApiException) {
                if (failure.status == 401 && failure.code == "INVALID_TOKEN")
                    abandonLocked()
                throw failure
            }
        }
    }

    private suspend fun accept(pair: TokenPair) {
        try {
            withContext(Dispatchers.IO) {
                store.write(server, pair)
            }
            tokens = pair
        } catch (error: Exception) {
            withContext(NonCancellable) {
                abandonLocked() }
            throw error
        }
    }

    suspend fun logout() = mutex.withLock {
        authenticated {
            api.logout(it)
        }

        abandonLocked()
    }

    suspend fun changePassword(current: String, replacement: String) = mutex.withLock {
        authenticated {
            api.changePassword(it, current, replacement)
        }
        abandonLocked()
    }

    suspend fun deleteAccount(current: String) = mutex.withLock {
        authenticated {
            api.deleteAccount(it, current)
        }
        abandonLocked()
    }

    suspend fun abandon() = mutex.withLock { abandonLocked() }

    private suspend fun abandonLocked() {
        cleanupPending = true
        mutableState.value = SessionState.Unavailable("Retirando la sesión de este dispositivo…")
        withContext(NonCancellable) {
            try {
                withContext(Dispatchers.IO) { store.clear() }
            } catch (error: Exception) {
                mutableState.value = SessionState.Unavailable("No se pudo retirar la sesión local. Reintenta antes de iniciar otra sesión.")
                throw error
            }
            tokens = null
            lastActivityNanos = null
            cleanupPending = false
            identityRecoveryMessage = "No se pudo verificar la identidad de la sesión guardada."
            mutableState.value = SessionState.Anonymous
        }
    }

    /** Solo invocado por eventos de interacción, nunca desde restauración o refresh. */
    suspend fun recordInteraction() = mutex.withLock {
        if (mutableState.value !is SessionState.Authenticated) return@withLock

        val now = System.nanoTime()

        if (lastActivityNanos?.let { now - it < 300_000_000_000L } == true) return@withLock

        lastActivityNanos = now
        authenticated {
            api.activity(it)
        }
    }
}
