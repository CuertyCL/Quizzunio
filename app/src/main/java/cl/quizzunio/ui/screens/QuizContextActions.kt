package cl.quizzunio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cl.quizzunio.study.LocalStudyQuizViewModel
import cl.quizzunio.ui.theme.studyColors
import cl.quizzunio.ui.theme.QuizCardShape

/** Estado del menú ligado al ID y al espacio completo; nunca al índice visible. */
@Composable
internal fun QuizContextActions(id: String, title: String, authenticated: Boolean, remote: Boolean) {
    val model = LocalStudyQuizViewModel.current ?: return
    val state by model.state.collectAsState()
    val workspace by model.workspace.collectAsState()
    key(workspace, id) {
        var expanded by remember { mutableStateOf(false) }
        var renaming by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf(title) }
        var error by remember { mutableStateOf<String?>(null) }

        QuizDeleteAction(id, title, authenticated, remote) { requestDelete ->
            Box {
                IconButton(onClick = { expanded = true }, enabled = !state.isBusy,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("quiz-options-$id")) {
                    Icon(Icons.Default.MoreHoriz, contentDescription = "Opciones del cuestionario",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    shape = QuizCardShape,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 0.dp
                ) {
                    DropdownMenuItem(text = { Text("Renombrar") }, enabled = !state.isBusy, onClick = {
                        expanded = false; name = title; error = null; renaming = true
                    })
                    val colors = MaterialTheme.studyColors
                    DropdownMenuItem(text = { Text("Eliminar") }, enabled = !state.isBusy,
                        modifier = Modifier.background(colors.destructive),
                        colors = MenuDefaults.itemColors(textColor = colors.onDestructive), onClick = {
                            expanded = false; requestDelete()
                        })
                }
            }
        }

        if (renaming) AlertDialog(
            onDismissRequest = { if (!state.isBusy) renaming = false },
            title = { Text("Renombrar cuestionario") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it; error = null },
                    label = { Text("Nombre del cuestionario") }, singleLine = true, enabled = !state.isBusy,
                    isError = error != null, supportingText = {
                        error?.let { Text(it) }
                        if (state.isBusy) Text("Guardando…")
                    })
            },
            confirmButton = {
                TextButton(enabled = !state.isBusy, onClick = {
                    if (name == title) renaming = false
                    else model.renameQuiz(
                        id,
                        name,
                        workspace,
                        onComplete = { renaming = false },
                        onError = { error = it }
                    )
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(enabled = !state.isBusy, onClick = { renaming = false }) { Text("Cancelar") } }
        )
    }
}
