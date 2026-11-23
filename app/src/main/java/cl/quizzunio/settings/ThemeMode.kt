package cl.quizzunio.settings

enum class ThemeMode(val key: String, val label: String) {
    System("system", "Sistema"),
    Light("light", "Claro"),
    Dark("dark", "Oscuro");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }

    companion object {
        internal fun fromPreferences(saved: String?, legacyDark: Boolean?): ThemeMode =
            entries.firstOrNull { it.key == saved } ?: when (legacyDark) {
                true -> Dark
                false -> Light
                null -> System
            }
    }
}
