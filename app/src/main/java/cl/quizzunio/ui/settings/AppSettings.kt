package cl.quizzunio.ui.settings

import cl.quizzunio.settings.AppSettings as CoreAppSettings

/**
 * Alias de tipo para garantizar compatibilidad con referencias en [cl.quizzunio.ui.settings].
 * La implementación principal de Jetpack DataStore reside en [cl.quizzunio.settings.AppSettings].
 */
typealias AppSettings = CoreAppSettings
