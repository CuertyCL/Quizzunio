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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appSettings = AppSettings.getInstance(applicationContext)
        setContent {
            val isDarkMode by appSettings.isDarkMode.collectAsState(initial = false)
            QuizzunioTheme(darkTheme = isDarkMode) {
                AppNavHost(modifier = Modifier.fillMaxSize())
            }
        }
    }
}