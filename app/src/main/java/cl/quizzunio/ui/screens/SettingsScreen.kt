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
import cl.quizzunio.settings.ThemeMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.staticCompositionLocalOf

internal enum class SettingsDestination { Server, Account }
internal val LocalSettingsNavigation = staticCompositionLocalOf<(SettingsDestination) -> Unit> { {} }

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
    var information by remember { mutableStateOf<String?>(null) }

    var previewTheme by remember { mutableStateOf(ThemeMode.System) }
    var choosingTheme by remember { mutableStateOf(false) }
    val appSettings = remember(context) {
        if (!isInspection) AppSettings.getInstance(context) else null
    }
    val storedTheme = appSettings?.themeMode?.collectAsState(initial = ThemeMode.System)?.value
    val selectedTheme = storedTheme ?: previewTheme
    val navigate = LocalSettingsNavigation.current

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
                SettingsItemCard(
                    icon = "◎",
                    title = "Servidor",
                    trailingValue = "›",
                    onClick = { navigate(SettingsDestination.Server) }
                )
                SettingsItemCard(
                    icon = "♙",
                    title = "Cuenta",
                    trailingValue = "›",
                    onClick = { navigate(SettingsDestination.Account) }
                )
                // Opción: Tema de la aplicación
                SettingsItemCard(
                    icon = "☼",
                    title = "Tema de la aplicación",
                    trailingValue = selectedTheme.label,
                    onClick = { choosingTheme = true }
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
                    onClick = { information = "Adjunta TXT, PDF con texto, DOCX o PPTX para generar ejercicios locales. Los PDF escaneados necesitan OCR externo. El tema es un metadato y no filtra el material. Sin cuenta, los cuestionarios y su progreso se guardan en este dispositivo. Free permite guardarlos en la nube y Premium añade generación IA en servidor." }
                )

                // Opción: Acerca de Quizzunio
                SettingsItemCard(
                    icon = "ⓘ",
                    title = "Acerca de Quizzunio",
                    trailingValue = "›",
                    onClick = { information = "Quizzunio convierte tus materiales en cuestionarios de estudio. El generador local es extractivo y funciona sin IA. La generación remota Premium envía texto al servidor, nunca los archivos originales." }
                )
            }

        }
    }
    if (choosingTheme) AlertDialog(
        onDismissRequest = { choosingTheme = false },
        title = { Text("Tema de la aplicación") },
        text = {
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = mode == selectedTheme,
                            role = Role.RadioButton,
                            onClick = {
                                choosingTheme = false
                                if (appSettings == null) {
                                    previewTheme = mode
                                    onThemeToggle()
                                } else coroutineScope.launch {
                                    try {
                                        appSettings.setThemeMode(mode)
                                        onThemeToggle()
                                    } catch (error: java.io.IOException) {
                                        information = "No se pudo guardar el tema. Inténtalo de nuevo."
                                    }
                                }
                            }
                        ).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(selected = mode == selectedTheme, onClick = null)
                        Text(mode.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosingTheme = false }) { Text("Cancelar") } }
    )
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
