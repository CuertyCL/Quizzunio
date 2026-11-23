package cl.quizzunio.settings

import org.junit.Assert.*
import org.junit.Test

class ThemeModeTest {
    @Test fun defaultAndLegacyPreferencesDistinguishAbsenceFromExplicitLight() {
        assertEquals(ThemeMode.System, ThemeMode.fromPreferences(null, null))
        assertEquals(ThemeMode.Light, ThemeMode.fromPreferences(null, false))
        assertEquals(ThemeMode.Dark, ThemeMode.fromPreferences(null, true))
        assertEquals(ThemeMode.System, ThemeMode.fromPreferences("system", true))
        assertEquals(ThemeMode.Light, ThemeMode.fromPreferences("light", true))
        assertEquals(ThemeMode.Dark, ThemeMode.fromPreferences("dark", false))
    }

    @Test fun systemFollowsBothConfigurationsAndExplicitModesOverrideThem() {
        assertTrue(ThemeMode.System.isDark(true))
        assertFalse(ThemeMode.System.isDark(false))
        assertFalse(ThemeMode.Light.isDark(true))
        assertTrue(ThemeMode.Dark.isDark(false))
    }
}
