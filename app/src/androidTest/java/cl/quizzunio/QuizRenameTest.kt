package cl.quizzunio

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cl.quizzunio.auth.AuthViewModel
import cl.quizzunio.auth.LocalAuthViewModel
import cl.quizzunio.auth.SessionState
import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.settings.AppSettings
import cl.quizzunio.settings.ThemeMode
import cl.quizzunio.study.*
import cl.quizzunio.ui.theme.DestructiveBackground
import cl.quizzunio.ui.theme.QuizzunioTheme
import cl.quizzunio.ui.navigation.AppNavHost
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QuizRenameTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test fun contextualMenuRenamesOnlyTheSelectedQuizAndKeepsItsAttempt() {
        lateinit var model: StudyQuizViewModel
        val storage = IsolatedStudyUiStorage(ui.activity)
        val auth = AuthViewModel(storage.context)
        val dark = mutableStateOf(false)
        ui.runOnIdle { model = storage.model(ui.activity) }
        fun render() = ui.runOnUiThread {
            ui.activity.setContent {
                QuizzunioTheme(darkTheme = dark.value) {
                    CompositionLocalProvider(LocalAuthViewModel provides auth) {
                        AppNavHost(startDestination = "home")
                    }
                }
            }
        }
        render()
        ui.waitUntil(15_000) { !model.state.value.isBusy && auth.state.value.session is SessionState.Anonymous }
        assertSame(SessionState.Anonymous, auth.state.value.session)
        val id = "rename-ui-${UUID.randomUUID()}"
        val questions = List(2) { QuizQuestion("¿Pregunta $it?", listOf("Á", "B", "C", "D"), 0) }
        val original = AnonymousQuizRecord(StudyQuiz(id, "Prueba de menú $id", "Tema", questions), listOf(2, null), 1)
        val other = AnonymousQuizRecord(original.quiz.copy(id = "$id-other", title = "Otro $id"))
        val store = AnonymousQuizStore(storage.context)
        val before = store.load()
        store.update { it + listOf(original, other) }
        try {
            ui.runOnIdle { model.retryLocalLoad() }
            ui.waitUntil(15_000) { !model.state.value.isBusy && model.state.value.quizzes.any { it.id == id } }
            fun options() {
                ui.onNodeWithTag("quiz-list").performScrollToNode(hasTestTag("quiz-card-$id"))
                ui.onNodeWithTag("quiz-options-$id").performClick()
            }
            for (theme in listOf(ThemeMode.Light, ThemeMode.Dark)) {
                ui.runOnIdle { dark.value = theme == ThemeMode.Dark }
                options()
                ui.onNodeWithTag("quiz-play-title").assertDoesNotExist()
                val rename = ui.onNodeWithText("Renombrar").assertIsDisplayed()
                ui.onNodeWithText("Editar").assertDoesNotExist()
                val delete = ui.onNode(hasText("Eliminar") and hasClickAction()).assertIsDisplayed()
                assertTrue(rename.fetchSemanticsNode().boundsInRoot.top < delete.fetchSemanticsNode().boundsInRoot.top)
                assertEquals(DestructiveBackground, delete.captureToImage().toPixelMap()[2, 2])
                val expectedSurface = if (theme == ThemeMode.Dark) cl.quizzunio.ui.theme.QuizzunioDarkSurfaceVariant
                    else cl.quizzunio.ui.theme.QuizzunioLightSurfaceVariant
                assertEquals(expectedSurface, rename.captureToImage().toPixelMap()[6, 6])
                val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                java.io.File(instrumentation.targetContext.cacheDir, "gpt-quiz-context-${theme.key}.png")
                    .outputStream().use { output ->
                        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                    }
                val outside = ui.onNodeWithText("Quizzunio").fetchSemanticsNode().boundsInWindow.center
                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                        .executeShellCommand("input tap ${outside.x.toInt()} ${outside.y.toInt()}")
                ).use { it.readBytes() }
                ui.waitForIdle()
                ui.onNodeWithText("Renombrar").assertDoesNotExist()
            }
            options(); ui.onNodeWithText("Renombrar").performClick()
            ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).assertTextContains(original.quiz.title)
            ui.onNodeWithText("Cancelar").performClick()
            assertEquals(original, store.load().single { it.quiz.id == id })
            options(); ui.onNodeWithText("Renombrar").performClick()
            for (invalid in listOf("", "   ", "😀".repeat(256))) {
                ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement(invalid)
                ui.onNodeWithText("Guardar").performClick()
                ui.waitUntil(15_000) { !model.state.value.isBusy }
                ui.onNodeWithText("Renombrar cuestionario").assertExists()
                ui.onNodeWithText("Escribe un título válido de hasta 255 puntos de código.").assertExists()
                assertEquals(original, store.load().single { it.quiz.id == id })
            }
            val title = "Renombrado Ñ 😀 漢 e\u0301 " + "contenido ".repeat(15)
            ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement(title)
            ui.onNodeWithText("Guardar").performClick()
            ui.waitUntil(15_000) { !model.state.value.isBusy }
            ui.onNodeWithText("Renombrar cuestionario").assertDoesNotExist()
            val expected = original.copy(quiz = original.quiz.copy(title = title))
            assertEquals(expected, store.load().single { it.quiz.id == id })
            assertEquals(other, store.load().single { it.quiz.id == other.quiz.id })
            assertEquals(before, store.load().filterNot { it.quiz.id == id || it.quiz.id == other.quiz.id })
            ui.onNodeWithTag("quiz-card-$id").assertTextContains(title).assertTextContains("En progreso", substring = true)
            ui.onNodeWithText("Eliminar").assertDoesNotExist()
            options(); ui.onNodeWithText("Renombrar").performClick()
            ui.onNodeWithText("Guardar").performClick() // Sin cambios: cierre sin escrituras.
            assertEquals(expected, store.load().single { it.quiz.id == id })
            ui.onNodeWithTag("quiz-list").performScrollToIndex(0)
            ui.onNode(hasSetTextAction()).performTextReplacement("Renombrado Ñ")
            ui.onNodeWithTag("quiz-card-$id").assertExists()
            ui.onNodeWithTag("quiz-card-${other.quiz.id}").assertDoesNotExist()
            ui.onNodeWithText("Completados").performClick()
            ui.onNodeWithTag("quiz-card-$id").assertDoesNotExist()
            ui.onNodeWithText("No hay cuestionarios que coincidan con la búsqueda y el filtro.").assertExists()
            ui.onNodeWithText("Recientes").performClick()
            ui.onNodeWithTag("quiz-card-$id").assertExists()
            ui.onNodeWithText("Todos").performClick()
            ui.onNode(hasSetTextAction()).performTextReplacement("")
            assertEquals(expected, store.load().single { it.quiz.id == id })
            ui.onNodeWithTag("quiz-list").performScrollToNode(hasTestTag("quiz-card-${other.quiz.id}"))
            ui.onNodeWithTag("quiz-options-${other.quiz.id}").performClick()
            ui.onNodeWithText("Eliminar").performClick()
            ui.onNodeWithText("Cancelar").performClick()
            assertEquals(other, store.load().single { it.quiz.id == other.quiz.id })
            ui.onNodeWithTag("quiz-options-${other.quiz.id}").performClick()
            ui.onNodeWithText("Eliminar").performClick()
            ui.onNode(hasText("Eliminar") and hasAnyAncestor(isDialog())).performClick()
            ui.waitUntil(15_000) { !model.state.value.isBusy }
            assertEquals(before + expected, store.load())
            ui.activityRule.scenario.recreate()
            render()
            ui.onNodeWithTag("quiz-list").performScrollToNode(hasTestTag("quiz-card-$id"))
            ui.onNodeWithTag("quiz-card-$id").performClick()
            ui.onNodeWithTag("quiz-play-title").assertTextEquals(title)
            assertEquals(original.answers, model.state.value.answers)
            assertEquals(original.currentIndex, model.state.value.currentQuestionIndex)
        } finally {
            store.update { records -> records.filterNot { it.quiz.id == id || it.quiz.id == other.quiz.id } }
        }
    }
}
