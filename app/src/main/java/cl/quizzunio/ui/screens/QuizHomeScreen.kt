package cl.quizzunio.ui.screens

import cl.quizzunio.ui.theme.studyColors
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

import androidx.compose.runtime.collectAsState
import cl.quizzunio.study.LocalStudyQuizViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.quizzunio.ui.theme.QuizzunioTheme
import cl.quizzunio.ui.theme.extendedColors
import cl.quizzunio.auth.LocalAuthViewModel
import cl.quizzunio.auth.SessionState
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator

/**
 * Modelo de datos simple para la lista de cuestionarios
 */
data class QuizItem(
    val id: String,
    val title: String,
    val questionCount: Int,
    val status: String,
    val isDraft: Boolean = false
)

/**
 * Pantalla 04: Inicio Cuestionarios
 * Maqueta: docs/maquetas/claro/04_Inicio_cuestionarios.drawio y docs/maquetas/oscuro/04_Inicio_cuestionarios.drawio
 */
@Composable
fun QuizHomeScreen(
    onCreateQuizClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onQuizClick: (String) -> Unit = {}
) {
    val studyState = LocalStudyQuizViewModel.current?.state?.collectAsState()?.value
    val studyModel = LocalStudyQuizViewModel.current
    val account = studyModel?.accountState?.collectAsState()?.value
    val anonymous = studyModel?.anonymousRecords?.collectAsState()?.value.orEmpty()
    val auth = LocalAuthViewModel.current?.state?.collectAsState()?.value
    val user = (auth?.session as? SessionState.Authenticated)?.user
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilterIndex by remember { mutableIntStateOf(0) }
    val filters = listOf("Todos", "Recientes", "Completados")

    val previewQuizzes = listOf(
        QuizItem("1", "Historia de Francia", 10, "Completado", false),
        QuizItem("2", "Biología celular", 15, "Borrador", true),
        QuizItem("3", "Matemáticas - Unidad 2", 8, "Completado", false),
        QuizItem("4", "Física - Movimiento", 12, "Completado", false)
    )

    val attempts = if (user == null) anonymous.associate { it.quiz.id to it.answers }
        else account?.records.orEmpty().associate { it.quiz.id to it.answers }
    val sampleQuizzes = (studyState?.quizzes?.map { quiz ->
        val answers = attempts[quiz.id]
        val progress = quizProgress(quiz.completed, answers.orEmpty())
        QuizItem(quiz.id, quiz.title, quiz.questions.size, progress.label)
    } ?: previewQuizzes).filter { quiz ->
        quiz.title.contains(searchQuery.trim(), ignoreCase = true) &&
            (selectedFilterIndex != 2 || quiz.status == "Completado")
    }.let { quizzes -> if (selectedFilterIndex == 1) quizzes.take(10) else quizzes }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Título de la app
                Text(
                    text = "Quizzunio",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )

                // Botón Ajustes
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Ajustes",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 20.dp)
                .testTag("quiz-list"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column {
                    Text(if (user == null) "Modo anónimo Free · Cuestionarios y progreso guardados en este dispositivo, sin sincronización automática."
                        else "${user.username} · ${user.tier.name} · Búsqueda y filtros sobre los cuestionarios cargados.")
                    Spacer(modifier = Modifier.height(12.dp))

                    if (studyState?.isBusy == true || account?.busy == true) CircularProgressIndicator()
                    if (user == null) TextButton(onClick = { studyModel?.retryLocalLoad() }, enabled = studyState?.isBusy != true) { Text("Volver a leer cuestionarios del dispositivo") }
                    if (user != null) {
                        AnonymousImportActions()
                        Row {
                            TextButton(onClick = { studyModel?.refreshCloud() }, enabled = studyState?.isBusy != true) { Text("Actualizar nube") }
                            TextButton(onClick = { studyModel?.refreshCloud(true) }, enabled = studyState?.isBusy != true && account?.hasMore == true) { Text("Cargar más") }
                        }
                        account?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (account?.generationUncertain == true) {
                            Text("La generación anterior no tiene confirmación. Actualiza la nube y revisa todos los resultados antes de repetirla.")
                            TextButton(onClick = { studyModel?.confirmGenerationReconciled() }, enabled = account.page > 0 && account.error == null) { Text("Revisé la nube y concilié la generación") }
                        }
                    }
                    if (sampleQuizzes.isEmpty() && studyState?.isBusy != true) Text("No hay cuestionarios que coincidan con la búsqueda y el filtro.")

                    // Botón Crear Cuestionario
                    Button(
                        onClick = onCreateQuizClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "Nuevo cuestionario",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Campo de Búsqueda
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Buscar cuestionarios...") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Buscar",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Chips de Filtro
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        filters.forEachIndexed { index, label ->
                            FilterChip(
                                selected = selectedFilterIndex == index,
                                onClick = { selectedFilterIndex = index },
                                label = { Text(label) },
                                shape = RoundedCornerShape(20.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onBackground,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Título de Sección
                    Text(
                        text = "Mis cuestionarios",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
            items(sampleQuizzes, key = { it.id }) { quiz ->
                val record = account?.records?.firstOrNull { it.quiz.id == quiz.id }
                QuizCard(
                    quiz = quiz,
                    onClick = { onQuizClick(quiz.id) },
                    actions = {
                        if (user == null || record != null) QuizContextActions(quiz.id, quiz.title,
                            authenticated = user != null, remote = record?.remote != null)
                    }
                )
                record?.let { AccountQuizActions(it, showDelete = false) }
            }
        }
    }
}

@Composable
fun QuizCard(
    quiz: QuizItem,
    onClick: () -> Unit
) = QuizCard(quiz, onClick, actions = {})

@Composable
private fun QuizCard(
    quiz: QuizItem,
    onClick: () -> Unit,
    actions: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("quiz-card-${quiz.id}")
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
                Text(
                    text = quiz.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(4.dp))

                val colors = MaterialTheme.studyColors
                val statusColor = when (quiz.status) {
                    QuizProgress.Completed.label -> colors.completed
                    QuizProgress.InProgress.label -> colors.inProgress
                    QuizProgress.NotStarted.label -> colors.notStarted
                    else -> if (quiz.isDraft) colors.inProgress else colors.completed
                }

                Text(
                    text = "${quiz.questionCount} preguntas  •  ${quiz.status}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = statusColor
                )
            }

            Text(
                text = "›",
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            actions()
        }
    }
}

@Preview(showBackground = true)
@Composable
fun QuizHomeScreenPreview() {
    QuizzunioTheme {
        QuizHomeScreen()
    }
}
