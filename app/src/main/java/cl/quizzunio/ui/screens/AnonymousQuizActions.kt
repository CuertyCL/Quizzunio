package cl.quizzunio.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cl.quizzunio.auth.LocalAuthViewModel
import cl.quizzunio.auth.SessionState
import cl.quizzunio.study.AnonymousQuizRecord
import cl.quizzunio.study.AnonymousImportIdentity
import cl.quizzunio.study.LocalStudyQuizViewModel

@Composable
fun AnonymousQuizActions(id: String, title: String) {
    QuizDeleteAction(id, title, authenticated = false, remote = false)
}

@Composable
internal fun QuizDeleteAction(id: String, title: String, authenticated: Boolean, remote: Boolean) {
    val model = LocalStudyQuizViewModel.current ?: return
    val state by model.state.collectAsState()
    var deleting by remember(id) { mutableStateOf(false) }
    DestructiveButton("Eliminar", enabled = !state.isBusy) { deleting = true }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Eliminar cuestionario") },
        text = {
            Text(when {
                !authenticated -> "¿Eliminar «$title» y su progreso de este dispositivo? Las copias importadas a cuentas se conservan."
                remote -> "¿Eliminar «$title» de tu cuenta del servidor?"
                else -> "¿Eliminar «$title» y su progreso local de este dispositivo?"
            })
        },
        confirmButton = {
            DestructiveButton("Eliminar", enabled = !state.isBusy) {
                deleting = false
                if (authenticated) model.deleteQuiz(id) else model.deleteAnonymousQuiz(id)
            }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } })
}

@Composable
fun AnonymousImportActions() {
    val model = LocalStudyQuizViewModel.current ?: return
    val auth = LocalAuthViewModel.current?.state?.collectAsState()?.value ?: return
    val user = (auth.session as? SessionState.Authenticated)?.user ?: return
    val state by model.state.collectAsState()
    val records by model.anonymousRecords.collectAsState()
    val account by model.accountState.collectAsState()
    var selecting by remember(user.userId, auth.server) { mutableStateOf(false) }
    var selected by remember(user.userId, auth.server) { mutableStateOf<AnonymousQuizRecord?>(null) }
    TextButton(onClick = { selecting = true; model.loadAnonymousForImport() }, enabled = !state.isBusy) {
        Text("Importar cuestionario del dispositivo")
    }
    if (selecting) AlertDialog(onDismissRequest = { selecting = false }, title = { Text("Cuestionarios anónimos del dispositivo") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("Destino: ${user.username} · ${auth.server}. El original se conservará.")
                if (state.isBusy) Text("Cargando...")
                else if (records.isEmpty()) Text("No hay cuestionarios anónimos disponibles.")
                records.forEach { record -> TextButton(onClick = { selected = record; selecting = false }, enabled = !state.isBusy) {
                    Text("Importar «${record.quiz.title}» · ${record.quiz.questions.size} preguntas")
                } }
            }
        }, confirmButton = { TextButton(onClick = { selecting = false }) { Text("Cerrar") } })
    selected?.let { record -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("Importar y subir a mi cuenta") },
        text = { Text(if (account.records.any { it.quiz.id == AnonymousImportIdentity.localId(record.quiz.id) })
            "Ya existe una copia de «${record.quiz.title}» en ${user.username} (${auth.server}). Se continuará esa misma operación, conservando el intento actual de la copia. No se reemplazarán cambios de la copia ni del original anónimo, ni se creará otra importación. La posición se conserva localmente."
            else "Se copiará «${record.quiz.title}» a ${user.username} (${auth.server}) y se enviará inmediatamente al servidor, conservando todas tus respuestas y resultados. El original anónimo permanecerá intacto. La posición se conserva localmente; el servidor almacena las respuestas, sin un campo de posición o finalización independiente.") },
        confirmButton = { TextButton(onClick = { selected = null; model.importAnonymousQuiz(record.quiz.id) }, enabled = !state.isBusy) { Text("Confirmar importación y subida") } },
        dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancelar") } }) }
}
