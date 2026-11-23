package cl.quizzunio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.auth.AuthViewModel
import cl.quizzunio.auth.SessionState
import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.study.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** El host debe terminar el proceso entre estos dos métodos, sin reinstalar ni borrar datos. */
class QuizRenameLifecycleTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val run get() = requireNotNull(InstrumentationRegistry.getArguments().getString("renameRun"))
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun ready(): StudyQuizViewModel {
        val model = ViewModelProvider(ui.activity)[StudyQuizViewModel::class.java]
        val auth = ViewModelProvider(ui.activity)[AuthViewModel::class.java]
        ui.waitUntil(15_000) { !model.state.value.isBusy && auth.state.value.session !is SessionState.Restoring }
        assertSame(SessionState.Anonymous, auth.state.value.session)
        return model
    }

    @Test fun aPrepareRenamedQuizForExternalProcessDeath() {
        val model = ready(); val store = AnonymousQuizStore(context); val id = "rename-process-$run"
        val original = AnonymousQuizRecord(StudyQuiz(id, "Prueba de renombrado $run", "Tema 漢", List(2) {
            QuizQuestion("¿Pregunta $it?", listOf("Á", "B", "C", "D"), 0)
        }, completed = true), listOf(0, 2), 1)
        store.update { records -> check(records.none { it.quiz.id == id }); records + original }
        ui.runOnIdle { model.retryLocalLoad() }
        ui.waitUntil(15_000) { !model.state.value.isBusy }
        ui.onNodeWithTag("quiz-list").performScrollToNode(hasTestTag("quiz-card-$id"))
        ui.onNodeWithTag("quiz-options-$id").performClick()
        ui.onNodeWithText("Renombrar").performClick()
        val title = "Persistente 😀 ñ漢 e\u0301 $run"
        ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement(title)
        ui.onNodeWithText("Guardar").performClick()
        ui.waitUntil(15_000) { !model.state.value.isBusy }
        val expected = original.copy(quiz = original.quiz.copy(title = title))
        assertEquals(expected, store.load().single { it.quiz.id == id })
        assertTrue(context.getSharedPreferences("rename_test_$run", 0).edit()
            .putString("expected", LocalQuizSnapshotCodec.encode(expected).toString()).commit())
    }

    @Test fun bRecoverRenamedQuizAfterExternalProcessDeath() {
        val model = ready(); val store = AnonymousQuizStore(context)
        val text = requireNotNull(context.getSharedPreferences("rename_test_$run", 0).getString("expected", null))
        val expected = LocalQuizSnapshotCodec.decode(JSONObject(text))
        assertEquals(expected, store.load().single { it.quiz.id == expected.quiz.id })
        ui.onNodeWithTag("quiz-list").performScrollToNode(hasTestTag("quiz-card-${expected.quiz.id}"))
        ui.onNodeWithTag("quiz-card-${expected.quiz.id}").assertTextContains(expected.quiz.title).performClick()
        ui.onNodeWithText("1 / 2").assertExists()
        assertEquals(expected.quiz, model.state.value.activeQuiz)
        assertEquals(expected.answers, model.state.value.answers)
        assertEquals(expected.currentIndex, model.state.value.currentQuestionIndex)
        store.update { records -> records.filterNot { it.quiz.id == expected.quiz.id } }
    }
}
