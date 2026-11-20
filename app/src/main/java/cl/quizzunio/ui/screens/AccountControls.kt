package cl.quizzunio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cl.quizzunio.auth.*
import cl.quizzunio.network.ApiConfiguration
import cl.quizzunio.study.*
import androidx.compose.ui.platform.testTag

val LocalQuizEditorNavigation = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun AccountSettingsPanel() {
    val model = LocalAuthViewModel.current ?: return
    val auth by model.state.collectAsState()
    val user = (auth.session as? SessionState.Authenticated)?.user
    var endpoint by remember(auth.server) { mutableStateOf(auth.server) }
    var pendingHttpUrl by remember { mutableStateOf<String?>(null) }
    var currentPassword by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var abandoning by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (user == null) "Modo anónimo · Free" else "${user.username} · ${user.tier.name}")
        if (user != null) Text(user.email)
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("URL del servidor") },
            enabled = !auth.busy && user == null, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Se recomienda HTTPS con certificado válido (predeterminado https://10.0.2.2:8443). HTTP sin cifrado requiere confirmación explícita y solo debe usarse en redes controladas (LAN o VPN).")
        if (user == null) TextButton(onClick = {
            val candidate = runCatching { ApiConfiguration(endpoint, false) }.getOrNull()
            if (candidate != null && candidate.isCleartext) pendingHttpUrl = candidate.baseUrl
            else model.configureServer(endpoint)
        }, enabled = !auth.busy) { Text("Aplicar servidor") }
        TextButton(onClick = { model.checkStatus() }, enabled = !auth.busy) { Text("Comprobar estado del servidor") }
        auth.status?.let { Text(it) }
        if (user != null) {
            OutlinedTextField(currentPassword, { if (CredentialValidator.ascii(it)) currentPassword = it },
                label = { Text("Contraseña actual") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !auth.busy)
            OutlinedTextField(replacement, { if (CredentialValidator.ascii(it)) replacement = it },
                label = { Text("Nueva contraseña (12–128 ASCII)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !auth.busy)
            TextButton(onClick = { model.changePassword(currentPassword, replacement); currentPassword = ""; replacement = "" }, enabled = !auth.busy) { Text("Cambiar contraseña y revocar sesiones") }
            TextButton(onClick = { deleting = true }, enabled = !auth.busy && currentPassword.isNotEmpty()) { Text("Eliminar cuenta y sus cuestionarios") }
            TextButton(onClick = { abandoning = true }, enabled = !auth.busy) { Text("Abandonar sesión solo en este dispositivo") }
        }
    }
    pendingHttpUrl?.let { target -> AlertDialog(onDismissRequest = { pendingHttpUrl = null }, title = { Text("Conexión HTTP sin cifrado") },
        text = { Text("La dirección «$target» usa HTTP sin cifrado de transporte. Tus credenciales, tokens y cuestionarios se enviarán sin cifrar por la red.\n\nÚsala únicamente en una red controlada (LAN o VPN de confianza).") },
        confirmButton = { TextButton(onClick = { pendingHttpUrl = null; model.configureServer(target, confirmCleartext = true) }) { Text("Confirmar uso de HTTP") } },
        dismissButton = { TextButton(onClick = { pendingHttpUrl = null }) { Text("Cancelar") } }) }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Eliminar cuenta") },
        text = { Text("Se eliminarán tu cuenta, cuestionarios y sesiones del servidor. Esta operación no se puede deshacer.") },
        confirmButton = { TextButton(onClick = { deleting = false; model.deleteAccount(currentPassword); currentPassword = "" }) { Text("Confirmar eliminación") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } })
    if (abandoning) AlertDialog(onDismissRequest = { abandoning = false }, title = { Text("Abandonar sesión local") },
        text = { Text("Se retirarán los tokens de este dispositivo. Las sesiones del servidor no se revocarán y los datos de esta cuenta quedarán ocultos.") },
        confirmButton = { TextButton(onClick = { abandoning = false; model.abandon() }) { Text("Abandonar") } },
        dismissButton = { TextButton(onClick = { abandoning = false }) { Text("Cancelar") } })
}

@Composable
fun AccountQuizActions(record: QuizRecord) {
    val model = LocalStudyQuizViewModel.current ?: return
    val state by model.state.collectAsState()
    val edit = LocalQuizEditorNavigation.current
    var delete by remember { mutableStateOf(false) }
    var replace by remember { mutableStateOf(false) }
    Column(Modifier.testTag("quiz-actions-${record.quiz.id}")) {
        Text(if (record.remote == null) "Local · Solo en este dispositivo" else "Nube · Versión ${record.remote.version} · ${when (record.sync) {
            QuizSyncState.Synced -> "Sincronizado"
            QuizSyncState.Pending -> "Respuestas pendientes"
            QuizSyncState.Conflict -> "Conflicto de versión"
            QuizSyncState.LocalOnly -> "Local"
        }}")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (record.remote == null) {
                TextButton(onClick = { model.uploadQuiz(record.quiz.id) }, enabled = !state.isBusy) { Text("Guardar en nube") }
            } else {
                TextButton(onClick = { edit(record.quiz.id) }, enabled = !state.isBusy) { Text("Editar") }
                TextButton(onClick = { model.synchronizeQuiz(record.quiz.id) }, enabled = !state.isBusy) { Text("Sincronizar") }
            }
            TextButton(onClick = { delete = true }, enabled = !state.isBusy) { Text("Eliminar") }
        }
        if (record.remote != null) {
            TextButton(onClick = { model.inspectServerVersion(record.quiz.id) }, enabled = !state.isBusy) { Text("Consultar versión del servidor") }
            TextButton(onClick = { replace = true }, enabled = !state.isBusy) { Text("Sustituir por versión del servidor") }
        }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Eliminar cuestionario") },
        text = { Text("¿Eliminar «${record.quiz.title}»${if (record.remote != null) " de tu cuenta del servidor" else " de este dispositivo"}?") },
        confirmButton = { TextButton(onClick = { delete = false; model.deleteQuiz(record.quiz.id) }) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancelar") } })
    if (replace) AlertDialog(onDismissRequest = { replace = false }, title = { Text("Sustituir estado local") },
        text = { Text("Se descartarán las respuestas pendientes de este cuestionario y se cargará el contenido y las respuestas actuales del servidor.") },
        confirmButton = { TextButton(onClick = { replace = false; model.acceptServerVersion(record.quiz.id) }) { Text("Cargar y sustituir") } },
        dismissButton = { TextButton(onClick = { replace = false }) { Text("Conservar mis respuestas") } })
}
