package cl.quizzunio.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.quizzunio.settings.AppSettings
import cl.quizzunio.ui.theme.QuizzunioTheme
import kotlinx.coroutines.launch
import cl.quizzunio.auth.LocalAuthViewModel
import cl.quizzunio.auth.SessionState

/**
 * Pantalla 05: Ajustes
 * Maqueta: docs/maquetas/claro/05_Ajustes.drawio y docs/maquetas/oscuro/05_Ajustes.drawio
 */
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit = {},
    onThemeToggle: () -> Unit = {},
    onLogoutClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isInspection = LocalInspectionMode.current
    val authModel = LocalAuthViewModel.current
    val auth = authModel?.state?.collectAsState()?.value
    var information by remember { mutableStateOf<String?>(null) }

    var previewDarkTheme by remember { mutableStateOf(false) }
    val appSettings = remember(context) {
        if (!isInspection) AppSettings.getInstance(context) else null
    }
    val isDarkStored by (appSettings?.isDarkMode?.collectAsState(initial = false)
        ?: remember { mutableStateOf(false) })

    val isDarkTheme = if (isInspection) previewDarkTheme else isDarkStored

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }

                Text(
                    text = "Ajustes",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AccountSettingsPanel()
                // Opción: Tema de la aplicación
                SettingsItemCard(
                    icon = "☼",
                    title = "Tema de la aplicación",
                    trailingValue = if (isDarkTheme) "Oscuro" else "Claro",
                    onClick = {
                        if (isInspection) {
                            previewDarkTheme = !previewDarkTheme
                        } else {
                            coroutineScope.launch {
                                try { appSettings?.setDarkMode(!isDarkTheme) }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (error: java.io.IOException) { information = "No se pudo guardar el tema. Inténtalo de nuevo." }
                            }
                        }
                        onThemeToggle()
                    }
                )

                // Opción: Idioma
                SettingsItemCard(
                    icon = "◎",
                    title = "Idioma",
                    trailingValue = "Español",
                    onClick = { information = "La interfaz de esta versión está disponible en español." }
                )

                // Opción: Ayuda y soporte
                SettingsItemCard(
                    icon = "?",
                    title = "Ayuda y soporte",
                    trailingValue = "›",
                    onClick = { information = "Adjunta TXT, PDF con texto, DOCX o PPTX para generar ejercicios locales. Los PDF escaneados necesitan OCR externo. El tema es un metadato y no filtra el material. Sin cuenta, tus cuestionarios solo viven en memoria. Free permite guardarlos en la nube; Premium añade generación IA en servidor. Puedes gestionar cuenta y conexión en esta pantalla." }
                )

                // Opción: Acerca de Quizzunio
                SettingsItemCard(
                    icon = "ⓘ",
                    title = "Acerca de Quizzunio",
                    trailingValue = "›",
                    onClick = { information = "Quizzunio convierte tus materiales en cuestionarios de estudio. El generador local es extractivo y funciona sin IA. La generación remota Premium envía texto al servidor, nunca los archivos originales." }
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Botón Cerrar Sesión (Peligro)
            OutlinedButton(
                onClick = onLogoutClick,
                enabled = auth?.busy != true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    text = if (auth?.session is SessionState.Authenticated) "Cerrar todas las sesiones" else "Iniciar sesión o registrarse",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
    information?.let { message -> androidx.compose.material3.AlertDialog(onDismissRequest = { information = null }, title = { Text("Quizzunio") },
        text = { Text(message) }, confirmButton = { androidx.compose.material3.TextButton(onClick = { information = null }) { Text("Entendido") } }) }
}

@Composable
private fun SettingsItemCard(
    icon: String,
    title: String,
    trailingValue: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = icon,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Text(
                text = trailingValue,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp
                ),
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    QuizzunioTheme(
        darkTheme = true
    ) {
        SettingsScreen()
    }
}
