package cl.quizzunio.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Immutable
data class ExtendedColors(
    val success: Color,
    val successContainer: Color,
    val warning: Color
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        success = Color.Unspecified,
        successContainer = Color.Unspecified,
        warning = Color.Unspecified
    )
}

val MaterialTheme.extendedColors: ExtendedColors
    @Composable
    get() = LocalExtendedColors.current

private val DarkColorScheme = darkColorScheme(
    primary = QuizzunioDarkPrimary,
    onPrimary = QuizzunioDarkOnPrimary,
    primaryContainer = QuizzunioDarkPrimaryContainer,
    onPrimaryContainer = QuizzunioDarkOnPrimaryContainer,
    secondary = QuizzunioDarkSecondary,
    onSecondary = QuizzunioDarkOnSecondary,
    secondaryContainer = QuizzunioDarkSecondaryContainer,
    onSecondaryContainer = QuizzunioDarkOnSecondaryContainer,
    tertiary = QuizzunioDarkTertiary,
    onTertiary = QuizzunioDarkOnTertiary,
    tertiaryContainer = QuizzunioDarkTertiaryContainer,
    onTertiaryContainer = QuizzunioDarkOnTertiaryContainer,
    background = QuizzunioDarkBackground,
    onBackground = QuizzunioDarkOnBackground,
    surface = QuizzunioDarkSurface,
    onSurface = QuizzunioDarkOnSurface,
    surfaceVariant = QuizzunioDarkSurfaceVariant,
    onSurfaceVariant = QuizzunioDarkOnSurfaceVariant,
    outline = QuizzunioDarkOutline,
    outlineVariant = QuizzunioDarkOutlineVariant,
    error = QuizzunioDarkError,
    onError = QuizzunioDarkOnError,
    errorContainer = QuizzunioDarkErrorContainer,
    onErrorContainer = QuizzunioDarkOnErrorContainer
)

val DarkExtendedColors = ExtendedColors(
    success = QuizzunioSuccessDark,
    successContainer = QuizzunioSuccessContainerDark,
    warning = QuizzunioWarningDark
)

private val LightColorScheme = lightColorScheme(
    primary = QuizzunioLightPrimary,
    onPrimary = QuizzunioLightOnPrimary,
    primaryContainer = QuizzunioLightPrimaryContainer,
    onPrimaryContainer = QuizzunioLightOnPrimaryContainer,
    secondary = QuizzunioLightSecondary,
    onSecondary = QuizzunioLightOnSecondary,
    secondaryContainer = QuizzunioLightSecondaryContainer,
    onSecondaryContainer = QuizzunioLightOnSecondaryContainer,
    tertiary = QuizzunioLightTertiary,
    onTertiary = QuizzunioLightOnTertiary,
    tertiaryContainer = QuizzunioLightTertiaryContainer,
    onTertiaryContainer = QuizzunioLightOnTertiaryContainer,
    background = QuizzunioLightBackground,
    onBackground = QuizzunioLightOnBackground,
    surface = QuizzunioLightSurface,
    onSurface = QuizzunioLightOnSurface,
    surfaceVariant = QuizzunioLightSurfaceVariant,
    onSurfaceVariant = QuizzunioLightOnSurfaceVariant,
    outline = QuizzunioLightOutline,
    outlineVariant = QuizzunioLightOutlineVariant,
    error = QuizzunioLightError,
    onError = QuizzunioLightOnError,
    errorContainer = QuizzunioLightErrorContainer,
    onErrorContainer = QuizzunioLightOnErrorContainer
)

val LightExtendedColors = ExtendedColors(
    success = QuizzunioSuccessLight,
    successContainer = QuizzunioSuccessContainerLight,
    warning = QuizzunioWarningLight
)

@Composable
fun EmptyActivityTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

/**
 * Tema principal de Quizzunio que utiliza los esquemas de color
 * extraídos directamente de las maquetas de docs/maquetas/ (claro y oscuro).
 */
@Composable
fun QuizzunioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Selecciona los colores extendidos según el tema actual
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
