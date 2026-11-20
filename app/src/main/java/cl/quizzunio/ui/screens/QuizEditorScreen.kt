package cl.quizzunio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cl.quizzunio.study.*

@Composable
fun QuizEditorScreen(localId: String, onBack: () -> Unit) {
    val model = LocalStudyQuizViewModel.current ?: return
    val account by model.accountState.collectAsState()
    val study by model.state.collectAsState()
    val record = account.records.firstOrNull { it.quiz.id == localId }
    val remote = record?.remote
    if (remote == null) {
        Column { Text("No hay un cuestionario remoto disponible para editar."); TextButton(onClick = onBack) { Text("Volver") } }
        return
    }
    var title by remember(localId, remote.version) { mutableStateOf(remote.title) }
    var questions by remember(localId, remote.version) { mutableStateOf(remote.questions.map { EditedQuestion(it.questionId, it.question, it.options, it.correctAnswerIndex) }) }
    var discard by remember { mutableStateOf(false) }
    var saveConfirmation by remember { mutableStateOf(false) }
    fun update(index: Int, value: EditedQuestion) { questions = questions.toMutableList().apply { set(index, value) } }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Editar cuestionario · Versión ${remote.version}", style = MaterialTheme.typography.titleLarge)
        Text("El contrato de edición exige ASCII. El texto Unicode recibido se conserva; no se quitarán acentos automáticamente. Las respuestas existentes se mantienen por ID de pregunta.")
        if (record.sync == QuizSyncState.Conflict) Text("Conflicto: tu borrador permanece aquí. Consulta la versión del servidor antes de decidir.", color = MaterialTheme.colorScheme.error)
        OutlinedTextField(title, { title = it }, label = { Text("Título") }, enabled = !study.isBusy, modifier = Modifier.fillMaxWidth())
        questions.forEachIndexed { index, question ->
            Text("Pregunta ${index + 1} · ${question.questionId?.let { "ID $it" } ?: "Nueva"}")
            OutlinedTextField(question.question, { update(index, question.copy(question = it)) }, label = { Text("Enunciado") }, enabled = !study.isBusy, modifier = Modifier.fillMaxWidth())
            question.options.forEachIndexed { option, text ->
                OutlinedTextField(text, { value -> update(index, question.copy(options = question.options.toMutableList().apply { set(option, value) })) },
                    label = { Text("Alternativa ${option + 1}") }, enabled = !study.isBusy, modifier = Modifier.fillMaxWidth())
            }
            Row { (0..3).forEach { answer -> FilterChip(selected = question.correctAnswerIndex == answer,
                onClick = { update(index, question.copy(correctAnswerIndex = answer)) }, label = { Text("${answer + 1}") }, enabled = !study.isBusy) } }
            Text("Selecciona el número de la respuesta correcta.")
            TextButton(onClick = { questions = questions.filterIndexed { position, _ -> position != index } }, enabled = !study.isBusy && questions.size > 1) { Text("Quitar pregunta del cuestionario") }
        }
        TextButton(onClick = { questions = questions + EditedQuestion(null, "", List(4) { "" }, 0) }, enabled = !study.isBusy && questions.size < 15) { Text("Agregar pregunta") }
        Button(onClick = { saveConfirmation = true }, enabled = !study.isBusy) { Text("Revisar y guardar cambios") }
        TextButton(onClick = { model.inspectServerVersion(localId) }, enabled = !study.isBusy) { Text("Consultar versión actual del servidor") }
        TextButton(onClick = { discard = true }, enabled = !study.isBusy) { Text("Cancelar edición") }
    }
    if (saveConfirmation) AlertDialog(onDismissRequest = { saveConfirmation = false }, title = { Text("Guardar edición") },
        text = { Text("Se enviarán el título y ${questions.size} preguntas con versión ${remote.version}. Las preguntas quitadas se eliminarán del cuestionario remoto al confirmar.") },
        confirmButton = { TextButton(onClick = { saveConfirmation = false; model.editQuiz(localId, title, questions) { model.requestEditorClose() } }) { Text("Guardar en servidor") } },
        dismissButton = { TextButton(onClick = { saveConfirmation = false }) { Text("Seguir editando") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Descartar borrador") }, text = { Text("Los cambios de esta edición todavía no se han enviado.") },
        confirmButton = { TextButton(onClick = { discard = false; onBack() }) { Text("Descartar y volver") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Seguir editando") } })
}
