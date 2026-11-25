package cl.quizzunio 

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import cl.quizzunio.settings.AppSettings
import cl.quizzunio.ui.navigation.AppNavHost
import cl.quizzunio.ui.theme.EmptyActivityTheme
import cl.quizzunio.ui.theme.QuizzunioTheme
import cl.quizzunio.auth.LocalAuthViewModel
import cl.quizzunio.auth.rememberAuthViewModel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cl.quizzunio.settings.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appSettings = AppSettings.getInstance(applicationContext)
        setContent {
            val authModel = rememberAuthViewModel(this)
            val themeMode by appSettings.themeMode.collectAsState(initial = ThemeMode.System)
            val isDarkMode = themeMode.isDark(isSystemInDarkTheme())

            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightStatusBars = !isDarkMode
            }

            QuizzunioTheme(darkTheme = isDarkMode) {
                CompositionLocalProvider(LocalAuthViewModel provides authModel) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                        AppNavHost(modifier = Modifier.fillMaxSize(), startDestination = "home")
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                show(WindowInsetsCompat.Type.statusBars())
                hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
}
