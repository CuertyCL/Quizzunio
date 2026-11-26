package cl.quizzunio.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.quizzunio.ui.theme.QuizzunioTheme
import cl.quizzunio.ui.theme.extendedColors
import cl.quizzunio.study.LocalStudyQuizViewModel
import cl.quizzunio.study.GenerationStage
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.CircularProgressIndicator

/**
 * Pantalla 07: Procesamiento IA
 * Maqueta: docs/maquetas/claro/07_Procesamiento_IA.drawio y docs/maquetas/oscuro/07_Procesamiento_IA.drawio
 */
@Composable
fun AiProcessingScreen(
    onProcessingComplete: () -> Unit = {}
) = AiProcessingScreen(onProcessingComplete, onHomeClick = null)

@Composable
internal fun AiProcessingScreen(
    onProcessingComplete: () -> Unit,
    onHomeClick: (() -> Unit)?
) {
    val model = LocalStudyQuizViewModel.current
    val stage = model?.generationStage?.collectAsState()?.value ?: GenerationStage.Idle
    val study = model?.state?.collectAsState()?.value
    val uncertain = model?.accountState?.collectAsState()?.value?.generationUncertain == true
    val canRetry = stage == GenerationStage.Failed && !uncertain && study?.isBusy == false
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Generando con IA",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = when (stage) {
                        GenerationStage.Validated -> "Cuestionario recibido y validado"
                        GenerationStage.Failed -> if (uncertain) "No se confirmó la generación" else "No se pudo generar el cuestionario"
                        GenerationStage.Idle -> "No hay una generación en curso"
                        GenerationStage.Preparing -> "Consultando el límite de texto del servidor"
                        else -> "Solicitud de generación en curso"
                    },
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 28.sp
                    ),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = when {
                        stage == GenerationStage.Validated -> "El cuestionario está guardado y listo para estudiar."
                        uncertain -> "Si se pierde la conexión, el servidor puede completar y guardar el trabajo. Consulta la nube antes de repetirlo."
                        else -> "El servidor comprobará el texto extraído antes de generar."
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp
                    ),
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(36.dp))

                // Círculo central con emblema IA
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .border(BorderStroke(3.dp, MaterialTheme.colorScheme.tertiary), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (stage == GenerationStage.Requesting || stage == GenerationStage.Preparing) CircularProgressIndicator()
                    Text(
                        text = "✦",
                        fontSize = 58.sp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                Spacer(modifier = Modifier.height(40.dp))

                // Lista de Pasos
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ProcessingStepItem(
                        text = "Preparar material extraído",
                        status = if (stage in listOf(GenerationStage.Requesting, GenerationStage.Received, GenerationStage.Validated)) StepStatus.COMPLETED else StepStatus.PENDING
                    )
                    ProcessingStepItem(
                        text = "Esperar respuesta del servidor",
                        status = if (stage == GenerationStage.Requesting) StepStatus.IN_PROGRESS else if (stage in listOf(GenerationStage.Received, GenerationStage.Validated)) StepStatus.COMPLETED else StepStatus.PENDING
                    )
                    ProcessingStepItem(
                        text = "Recibir cuestionario",
                        status = if (stage in listOf(GenerationStage.Received, GenerationStage.Validated)) StepStatus.COMPLETED else StepStatus.PENDING
                    )
                    ProcessingStepItem(
                        text = "Validar y adaptar cuestionario",
                        status = if (stage == GenerationStage.Validated) StepStatus.COMPLETED else StepStatus.PENDING
                    )
                }
            }

            // Pie: Notificación / Botón de simulación
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Las preguntas se basan en tus archivos.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Botón interactivo para avanzar cuando finalice la generación
                Button(
                    onClick = { if (canRetry) model?.generateRemote {} else onProcessingComplete() },
                    enabled = stage == GenerationStage.Validated || canRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = if (stage == GenerationStage.Failed && !uncertain) "Reintentar" else "Continuar al cuestionario",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
                if (stage == GenerationStage.Validated && study?.isBusy == false &&
                    study.activeQuiz != null && onHomeClick != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onHomeClick,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Text("Volver a Inicio", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}

enum class StepStatus {
    COMPLETED,
    IN_PROGRESS,
    PENDING
}

@Composable
private fun ProcessingStepItem(
    text: String,
    status: StepStatus
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (status) {
            StepStatus.COMPLETED -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Completado",
                    tint = MaterialTheme.extendedColors.success,
                    modifier = Modifier.size(24.dp)
                )
            }
            StepStatus.IN_PROGRESS -> {
                Text(
                    text = "◉",
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
            StepStatus.PENDING -> {
                Text(
                    text = "○",
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }

        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = if (status == StepStatus.IN_PROGRESS) FontWeight.Bold else FontWeight.Normal
            ),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun AiProcessingScreenPreview() {
    QuizzunioTheme {
        AiProcessingScreen()
    }
}
