package cl.quizzunio.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "quizzunio_settings")

/**
 * Gestor centralizado de configuración de la aplicación basado en Jetpack DataStore Preferences.
 *
 * Implementa almacenamiento Clave-Valor con reactividad mediante Coroutines y Flow.
 * Diseñado para ser extensible a cualquier tipo de preferencia y consumible
 * por inyección de dependencias o mediante [AppSettings.getInstance].
 */
open class AppSettings(
    protected val dataStore: DataStore<Preferences>
) {
    /**
     * Constructor secundario conveniente a partir de un [Context] de Android.
     */
    constructor(context: Context) : this(context.applicationContext.appDataStore)

    /**
     * Flujo base seguro de preferencias con captura de [IOException] según las guías oficiales.
     */
    protected val preferencesFlow: Flow<Preferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    // =========================================================================
    // MÉTODOS GENÉRICOS DE EXTENSIÓN PARA CUALQUIER PREFERENCIA CLAVE-VALOR
    // =========================================================================

    /**
     * Obtiene un [Flow] reactivo para cualquier clave de preferencia con su valor por defecto.
     */
    fun <T> getPreference(key: Preferences.Key<T>, defaultValue: T): Flow<T> {
        return preferencesFlow.map { preferences ->
            preferences[key] ?: defaultValue
        }
    }

    /**
     * Guarda o actualiza un valor para cualquier clave de preferencia.
     */
    suspend fun <T> setPreference(key: Preferences.Key<T>, value: T) {
        dataStore.edit { preferences ->
            preferences[key] = value
        }
    }

    /**
     * Elimina una clave de preferencia específica.
     */
    suspend fun <T> removePreference(key: Preferences.Key<T>) {
        dataStore.edit { preferences ->
            preferences.remove(key)
        }
    }

    /**
     * Limpia todas las configuraciones almacenadas en el DataStore.
     */
    suspend fun clearAll() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    /**
     * Lectura única puntual (snapshot) sin suscripción continua a Flow.
     */
    suspend fun <T> getPreferenceOnce(key: Preferences.Key<T>, defaultValue: T): T {
        return getPreference(key, defaultValue).first()
    }

    // =========================================================================
    // HELPERS TIPADOS PARA EXTENDER FÁCILMENTE CON TIPOS BÁSICOS
    // =========================================================================

    fun getBoolean(key: Preferences.Key<Boolean>, defaultValue: Boolean = false): Flow<Boolean> =
        getPreference(key, defaultValue)

    suspend fun setBoolean(key: Preferences.Key<Boolean>, value: Boolean) =
        setPreference(key, value)

    fun getString(key: Preferences.Key<String>, defaultValue: String = ""): Flow<String> =
        getPreference(key, defaultValue)

    suspend fun setString(key: Preferences.Key<String>, value: String) =
        setPreference(key, value)

    fun getInt(key: Preferences.Key<Int>, defaultValue: Int = 0): Flow<Int> =
        getPreference(key, defaultValue)

    suspend fun setInt(key: Preferences.Key<Int>, value: Int) =
        setPreference(key, value)

    fun getLong(key: Preferences.Key<Long>, defaultValue: Long = 0L): Flow<Long> =
        getPreference(key, defaultValue)

    suspend fun setLong(key: Preferences.Key<Long>, value: Long) =
        setPreference(key, value)

    fun getFloat(key: Preferences.Key<Float>, defaultValue: Float = 0f): Flow<Float> =
        getPreference(key, defaultValue)

    suspend fun setFloat(key: Preferences.Key<Float>, value: Float) =
        setPreference(key, value)

    fun getDouble(key: Preferences.Key<Double>, defaultValue: Double = 0.0): Flow<Double> =
        getPreference(key, defaultValue)

    suspend fun setDouble(key: Preferences.Key<Double>, value: Double) =
        setPreference(key, value)

    fun getStringSet(key: Preferences.Key<Set<String>>, defaultValue: Set<String> = emptySet()): Flow<Set<String>> =
        getPreference(key, defaultValue)

    suspend fun setStringSet(key: Preferences.Key<Set<String>>, value: Set<String>) =
        setPreference(key, value)

    // =========================================================================
    // CONFIGURACIÓN ESPECÍFICA: MODO OSCURO (DARK MODE)
    // =========================================================================

    /**
     * Flujo que emite si el modo oscuro está activado.
     */
    val isDarkMode: Flow<Boolean> = getPreference(Keys.KEY_DARK_MODE, false)

    /**
     * Activa o desactiva el modo oscuro en las preferencias.
     */
    suspend fun setDarkMode(enabled: Boolean) {
        setPreference(Keys.KEY_DARK_MODE, enabled)
    }

    /**
     * Alterna el estado actual del modo oscuro.
     */
    suspend fun toggleDarkMode() {
        val current = isDarkMode.first()
        setDarkMode(!current)
    }

    // =========================================================================
    // DEFINICIÓN DE CLAVES REUTILIZABLES Y EXTENSIBLES
    // =========================================================================

    object Keys {
        val KEY_DARK_MODE = booleanPreferencesKey("dark_mode_enabled")
        val KEY_LANGUAGE = stringPreferencesKey("app_language")
        val KEY_NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val KEY_SOUND_EFFECTS = booleanPreferencesKey("sound_effects_enabled")
    }

    companion object {
        @Volatile
        private var instance: AppSettings? = null

        /**
         * Obtiene la instancia singleton de [AppSettings] garantizando un único DataStore activo.
         */
        fun getInstance(context: Context): AppSettings {
            return instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
        }
    }
}
