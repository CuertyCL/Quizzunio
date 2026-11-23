package cl.quizzunio

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import cl.quizzunio.auth.AuthViewModel
import cl.quizzunio.auth.SessionState
import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.study.AnonymousQuizRecord
import cl.quizzunio.study.AnonymousQuizStore
import cl.quizzunio.study.StudyQuiz
import cl.quizzunio.study.StudyQuizViewModel
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import cl.quizzunio.ui.screens.QuizResultScreen
import cl.quizzunio.ui.theme.QuizzunioTheme
import cl.quizzunio.ui.theme.StudyRedLight
import cl.quizzunio.ui.theme.StudyGreenLight
import cl.quizzunio.ui.theme.QuizzunioDarkError
import cl.quizzunio.ui.theme.QuizzunioSuccessDark
import cl.quizzunio.settings.AppSettings
import cl.quizzunio.settings.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class QuizExperienceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun models(): Pair<AuthViewModel, StudyQuizViewModel> {
        lateinit var auth: AuthViewModel
        lateinit var study: StudyQuizViewModel
        ui.runOnIdle {
            auth = ViewModelProvider(ui.activity)[AuthViewModel::class.java]
            study = ViewModelProvider(ui.activity)[StudyQuizViewModel::class.java]
        }
        ui.waitUntil(15_000) { auth.state.value.session !is SessionState.Restoring && !study.state.value.isBusy }
        assertSame("La prueba requiere un espacio anónimo abierto.", SessionState.Anonymous, auth.state.value.session)
        return auth to study
    }

    @Test fun cardActionsAndBothBackPathsRespectDurableAnonymousProgress() {
        val (_, study) = models()
        val store = AnonymousQuizStore(ui.activity)
        val id = UUID.randomUUID().toString()
        val questions = List(2) { index -> QuizQuestion("¿Pregunta ${index + 1}?", listOf("Árbol", "Bote", "Cielo", "Duna"), 0) }
        val first = StudyQuiz(id, "Árbol y océano 🌊 ".repeat(10) + id.take(5), "", questions)
        val partial = StudyQuiz("$id-p", "Parcial ${id.take(5)}", "", questions)
        val complete = StudyQuiz("$id-c", "Finalizado ${id.take(5)}", "", questions, completed = true)
        val fixture = listOf(
            AnonymousQuizRecord(first),
            AnonymousQuizRecord(partial, listOf(2, null)),
            AnonymousQuizRecord(complete, listOf(2, 3), 1)
        )
        val ids = fixture.map { it.quiz.id }.toSet()
        val existing = store.load()
        store.update { fixture + it }
        try {
            ui.runOnIdle { study.retryLocalLoad() }
            ui.waitUntil(15_000) { !study.state.value.isBusy && study.state.value.quizzes.any { it.id == id } }
            fun card(quiz: StudyQuiz): SemanticsNodeInteraction {
                val tag = "quiz-card-${quiz.id}"
                ui.onNodeWithTag("quiz-list").performScrollToNode(hasTestTag(tag))
                return ui.onNodeWithTag(tag)
            }
            fun deleteAction(quiz: StudyQuiz) = ui.onNode(
                hasText("Eliminar") and hasClickAction() and hasAnyAncestor(hasTestTag("quiz-card-${quiz.id}"))
            )
            card(first).assertTextContains("Por intentar", substring = true)
            card(first).assertTextContains(first.title)
            ui.onNodeWithText("Anónimo ${first.title}").assertDoesNotExist()
            card(partial).assertTextContains("En progreso", substring = true)
            card(complete).assertTextContains("Completado", substring = true)
            deleteAction(first).assertExists()
            card(partial)
            deleteAction(partial).assertExists()
            card(complete)
            deleteAction(complete).assertExists()
            ui.onNodeWithText("Eliminar del dispositivo").assertDoesNotExist()
            card(partial)
            deleteAction(partial).performClick()
            ui.onNodeWithTag("quiz-play-title").assertDoesNotExist()
            ui.onNodeWithText("Cancelar").performClick()
            assertEquals(fixture, store.load().filter { it.quiz.id in ids })

            card(first).performClick()
            val title = ui.onNodeWithTag("quiz-play-title").assertTextEquals(first.title)
            title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { layout ->
                val results = mutableListOf<TextLayoutResult>()
                assertTrue(layout(results))
                assertEquals(1, results.single().lineCount)
                assertTrue(results.single().isLineEllipsized(0))
            }
            ui.onNodeWithText("Guardar en dispositivo y salir").assertDoesNotExist()
            ui.onNodeWithText("Cielo").performScrollTo().performClick()
            ui.waitUntil(15_000) { !study.state.value.isBusy }
            ui.onNodeWithContentDescription("Volver").performClick()
            ui.waitUntil(15_000) { !study.state.value.isBusy }
            card(first).assertTextContains("En progreso", substring = true)
            assertEquals(listOf(2, null), store.load().first { it.quiz.id == id }.answers)
            ui.activityRule.scenario.recreate()
            card(first).performClick()
            ui.onNodeWithText("Siguiente").performScrollTo().performClick()
            ui.waitUntil(15_000) { !study.state.value.isBusy }
            ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
            ui.waitUntil(15_000) { !study.state.value.isBusy }
            card(first).assertExists()
            assertEquals(1, store.load().first { it.quiz.id == id }.currentIndex)

            card(complete).performClick()
            ui.onNodeWithText("¡Sigue practicando!").assertExists()
            ui.onNodeWithText("0%").assertExists()
            ui.onNodeWithText("Ver respuestas").performScrollTo().performClick()
            ui.waitUntil(15_000) { !study.state.value.isBusy }
            ui.onNodeWithContentDescription("Volver").performClick()
            ui.waitUntil(15_000) { !study.state.value.isBusy }
            card(complete).performClick()
            ui.onNodeWithText("Reintentar").performScrollTo().performClick()
            ui.waitUntil(15_000) { !study.state.value.isBusy }
            ui.onNodeWithContentDescription("Volver").performClick()
            ui.waitUntil(15_000) { !study.state.value.isBusy }
            card(complete).assertTextContains("Por intentar", substring = true)

            card(partial)
            deleteAction(partial).performClick()
            ui.onNode(hasText("Eliminar") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            ui.waitUntil(15_000) { !study.state.value.isBusy }
            assertNull(store.load().firstOrNull { it.quiz.id == partial.id })
            assertTrue(store.load().any { it.quiz.id == first.id })
            assertTrue(store.load().any { it.quiz.id == complete.id })
            assertEquals(existing, store.load().filterNot { it.quiz.id in ids })
        } finally {
            store.update { records -> records.filterNot { it.quiz.id in ids } }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun resultMessagesAndRingColorsUseTheSameBandInBothThemes() {
        val dark = mutableStateOf(false)
        val correct = mutableStateOf(0)
        ui.runOnUiThread {
            ui.activity.setContent {
                QuizzunioTheme(darkTheme = dark.value) {
                    QuizResultScreen(correctAnswers = correct.value, totalQuestions = 50)
                }
            }
        }
        for (darkMode in listOf(false, true)) {
            for ((score, heading) in listOf(0 to "¡Sigue practicando!", 20 to "¡Buen trabajo!", 35 to "¡Excelente!", 50 to "¡Excelente!")) {
                ui.runOnIdle { dark.value = darkMode; correct.value = score }
                ui.onNodeWithText(heading).assertExists()
                ui.onNodeWithText("${score * 2}%").assertExists()
                val scoreRing = ui.onNodeWithTag("quiz-result-score").performScrollTo().captureToImage().toPixelMap()
                val expected = when {
                    score == 0 && darkMode -> QuizzunioDarkError
                    score == 0 -> StudyRedLight
                    darkMode -> QuizzunioSuccessDark
                    else -> StudyGreenLight
                }
                assertTrue((0..(scoreRing.height / 10)).any { y -> scoreRing[scoreRing.width / 2, y] == expected })
            }
        }
    }

    @Test fun anonymousSettingsKeepsServerControlsAndInsetsWithLoginKeyboard() {
        models()
        assertSystemBars()
        ui.onNodeWithContentDescription("Ajustes").performClick()
        val settings = AppSettings.getInstance(ui.activity)
        val stored = runBlocking { settings.getString(AppSettings.Keys.KEY_THEME_MODE).first() }
        fun back() {
            ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
            ui.waitForIdle()
        }
        try {
            for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
                ui.onNodeWithText("Tema de la aplicación").performScrollTo().performClick()
                ui.onNode(hasText(mode.label) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
                ui.waitUntil(10_000) { runBlocking { settings.themeMode.first() } == mode }
                ui.onNode(hasText("Servidor") and hasClickAction()).assertExists().performClick()
                ui.onNodeWithText("Configuración de servidor").assertExists()
                ui.onNodeWithText("URL del servidor").assertExists()
                ui.onNodeWithText("Aplicar servidor").assertExists()
                ui.onNodeWithText("Comprobar estado del servidor").assertExists()
                ui.onNodeWithText("Se recomienda HTTPS", substring = true).assertExists()
                ui.onNodeWithText("Nombre de usuario: Anónimo").assertDoesNotExist()
                assertSystemBars()
                captureSettings(mode, "server")
                ui.onNodeWithContentDescription("Volver").performClick()
                ui.onNodeWithText("URL del servidor").assertDoesNotExist()
                ui.onNode(hasText("Cuenta") and hasClickAction()).performClick()
                ui.onNodeWithText("Nombre de usuario: Anónimo").assertExists()
                ui.onNodeWithText("Cerrar sesión").assertDoesNotExist()
                ui.onNodeWithText("Suscripción:", substring = true).assertDoesNotExist()
                ui.onNodeWithText("URL del servidor").assertDoesNotExist()
                assertSystemBars()
                captureSettings(mode, "account")
                back()
                ui.onNodeWithText("Nombre de usuario: Anónimo").assertDoesNotExist()
            }
            ui.activityRule.scenario.recreate()
            ui.onNode(hasText("Servidor") and hasClickAction()).assertExists()
            ui.onNode(hasText("Cuenta") and hasClickAction()).performClick()
        } finally {
            runBlocking {
                if (stored.isEmpty()) settings.removePreference(AppSettings.Keys.KEY_THEME_MODE)
                else settings.setPreference(AppSettings.Keys.KEY_THEME_MODE, stored)
            }
        }
        ui.onNode(hasText("Iniciar sesión") and hasClickAction()).performScrollTo().performClick()
        ui.onNodeWithText("¿No tienes cuenta? Regístrate").assertExists()
        ui.onAllNodes(hasSetTextAction())[0].performScrollTo().performTouchInput { click() }
        ui.waitUntil(10_000) {
            var imeVisible = false
            ui.runOnIdle {
                imeVisible = ViewCompat.getRootWindowInsets(ui.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            imeVisible
        }
        ui.onNode(hasText("Iniciar sesión") and hasClickAction()).performScrollTo().assertIsDisplayed()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
        ).use { it.readBytes() }
        ui.waitForIdle()
        back()
        ui.onNodeWithText("Nombre de usuario: Anónimo").assertExists()
    }

    @Test fun rotationAndDocumentPickerReturnRestoreSystemBars() {
        models()
        ui.runOnIdle { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.waitUntil(15_000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        assertSystemBars()
        ui.runOnIdle { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(15_000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        ui.onNodeWithText("Nuevo cuestionario").performClick()
        ui.onNodeWithText("Toca para agregar archivos").performScrollTo().performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.waitUntil(15_000) {
            automation.rootInActiveWindow?.packageName?.contains("documentsui", ignoreCase = true) == true
        }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("input keyevent KEYCODE_BACK")
        ).use { it.readBytes() }
        ui.waitUntil(15_000) {
            automation.rootInActiveWindow?.packageName?.toString() == ui.activity.packageName
        }
        assertSystemBars()
        ui.onNodeWithText("Toca para agregar archivos").assertExists()
    }

    private fun assertSystemBars() {
        ui.waitUntil(10_000) {
            var barsAreCorrect = false
            ui.runOnIdle {
                val insets = ViewCompat.getRootWindowInsets(ui.activity.window.decorView)
                barsAreCorrect = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) == true &&
                    !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
            }
            barsAreCorrect
        }
    }

    private fun captureSettings(mode: ThemeMode, section: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(instrumentation.targetContext.cacheDir, "gpt-settings-${mode.key}-$section.png")
            .outputStream().use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            }
    }
}
