package cl.quizzunio

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.settings.AppSettings
import cl.quizzunio.settings.ThemeMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Se ejecuta en dos instrumentaciones separadas por force-stop desde ADB. */
class ThemePersistenceTest {
    @Test fun prepareThemePreference() = withSettings { settings, file ->
        assertFalse("Usa un identificador de prueba nuevo", file.exists())
        assertEquals(ThemeMode.System, settings.themeMode.first())
        settings.setBoolean(AppSettings.Keys.KEY_DARK_MODE, false)
        assertEquals(ThemeMode.Light, settings.themeMode.first())
        settings.setBoolean(AppSettings.Keys.KEY_DARK_MODE, true)
        assertEquals(ThemeMode.Dark, settings.themeMode.first())
        settings.setString(AppSettings.Keys.KEY_LANGUAGE, "es")
        settings.setThemeMode(ThemeMode.System)
        assertEquals(ThemeMode.System, settings.themeMode.first())
    }

    @Test fun verifyThemePreferenceAfterProcessDeath() = withSettings { settings, _ ->
        assertEquals(ThemeMode.System, settings.themeMode.first())
        assertEquals(true, settings.getBoolean(AppSettings.Keys.KEY_DARK_MODE).first())
        assertEquals("es", settings.getString(AppSettings.Keys.KEY_LANGUAGE).first())
    }

    private fun withSettings(block: suspend (AppSettings, File) -> Unit) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("themeRun"))
        require(run.matches(Regex("[a-zA-Z0-9-]{1,40}")))
        val file = File(instrumentation.targetContext.noBackupFilesDir, "theme-test-$run.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            block(AppSettings(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })), file)
        } finally {
            scope.cancel()
        }
    }
}
