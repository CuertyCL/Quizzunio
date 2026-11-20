package cl.quizzunio.auth

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cl.quizzunio.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class AuthUiState(val session: SessionState = SessionState.Restoring, val busy: Boolean = false,
                       val message: String? = null, val status: String? = null, val server: String = "https://10.0.2.2:8443")

class AuthViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("server_configuration", Context.MODE_PRIVATE)
    private val development = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = mutableState.asStateFlow()
    var client: ApiClient = createClient(preferences.getString("base_url", null) ?: "https://10.0.2.2:8443")
        private set
    var session: SessionRepository = createSession()
        private set
    private var observer: Job? = null
    private var noticeObserver: Job? = null

    init { observeSession(); runOperation { session.restore() } }

    private fun createClient(url: String) = ApiClient(ApiConfiguration(url, development), UrlConnectionTransport())
    private fun createSession() = SessionRepository(AuthApi(client), KeystoreTokenStore(appContext), client.configuration.baseUrl)
    private fun observeSession() {
        observer?.cancel()
        noticeObserver?.cancel()
        observer = scope.launch { session.state.collect { value -> mutableState.update { it.copy(session = value, server = client.configuration.baseUrl) } } }
        noticeObserver = scope.launch {
            session.sessionNotice.collect { notice ->
                if (notice != null) {
                    showMessage(notice)
                    session.clearSessionNotice()
                }
            }
        }
    }

    fun login(identifier: String, password: String) = runOperation { session.login(identifier, password) }
    fun register(username: String, email: String, password: String, confirmation: String) = runOperation {
        require(password == confirmation) { "Las contraseñas no coinciden." }; session.register(username, email, password)
    }
    fun restore() = runOperation { session.restore() }
    fun logout() = runOperation { session.logout(); showMessage("El servidor confirmó la revocación de todas las sesiones.") }
    fun abandon() = runOperation { session.abandon(); showMessage("Sesión abandonada en este dispositivo. No se confirmó revocación remota.") }
    fun changePassword(current: String, replacement: String) = runOperation {
        session.changePassword(current, replacement); showMessage("Contraseña cambiada y sesiones revocadas. Vuelve a iniciar sesión.")
    }
    fun deleteAccount(current: String) = runOperation {
        session.deleteAccount(current); showMessage("El servidor confirmó la eliminación de la cuenta.")
    }
    fun checkStatus() = runOperation {
        val status = StatusApi(client).read()
        mutableState.update { it.copy(status = status.description) }
    }
    fun configureServer(value: String) = runOperation {
        check(state.value.session is SessionState.Anonymous) { "Abandona o cierra la sesión antes de cambiar de servidor." }
        val replacement = createClient(value)
        withContext(Dispatchers.IO) { check(preferences.edit().putString("base_url", replacement.configuration.baseUrl).commit()) }
        client = replacement; session = createSession(); observeSession(); session.restore()
        mutableState.update { it.copy(status = null) }
    }
    fun interaction() {
        if (state.value.session !is SessionState.Authenticated || state.value.busy) return
        scope.launch {
            try { session.recordInteraction() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // Solo notificar si la sesión fue revocada o expiró; los fallos transitorios sin conexión no deben interrumpir el estudio local.
                if (session.state.value is SessionState.Anonymous) {
                    showMessage("Tu sesión expiró o fue revocada. Continúas en modo anónimo.")
                }
            }
        }
    }
    fun showMessage(message: String) { mutableState.update { it.copy(message = message) } }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }
    private fun runOperation(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, message = null) }
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showMessage(error.message ?: "La operación no pudo completarse.") }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    override fun onCleared() { scope.cancel(); super.onCleared() }
}

val LocalAuthViewModel = staticCompositionLocalOf<AuthViewModel?> { null }

@Composable
fun rememberAuthViewModel(activity: ComponentActivity): AuthViewModel = remember(activity) {
    val factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AuthViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return AuthViewModel(activity.applicationContext) as T
        }
    }
    ViewModelProvider(activity, factory)[AuthViewModel::class.java]
}
