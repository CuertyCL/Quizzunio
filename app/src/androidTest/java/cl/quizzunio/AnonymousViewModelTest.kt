package cl.quizzunio

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.study.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AnonymousViewModelTest {
    private fun context(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.noBackupFilesDir, "anonymous-vm-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = root
            override fun getFilesDir() = File(root, "legacy").apply { mkdirs() }
        }
    }
    private fun quiz(id: String) = StudyQuiz(id, id, "Unidad", List(2) { QuizQuestion("¿$id $it?", listOf("A", "B", "C", "D"), 0) })
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("Esperar una escritura/carga real", condition())
    }
    private fun model(context: Context): StudyQuizViewModel {
        lateinit var vm: StudyQuizViewModel
        main { vm = StudyQuizViewModel(StudyQuizRepository(context), StudyDocumentReader(context), LocalQuizGenerator()) }
        waitFor { !vm.state.value.isBusy }; return vm
    }
    private class NoGenerationRemote(private val gate: CompletableDeferred<Unit>? = null) : RemoteQuizGateway {
        val listing = CompletableDeferred<Unit>()
        override suspend fun list(page: Int, pageSize: Int): RemotePage { listing.complete(Unit); gate?.await(); return RemotePage(page, pageSize, emptyList()) }
        override suspend fun get(id: Long): RemoteQuiz = error("Sin red")
        override suspend fun generate(title: String, text: String, count: Int): RemoteQuiz = error("No usar IA")
        override suspend fun importLocal(record: QuizRecord): RemoteQuiz = error("No importar al enlazar")
        override suspend fun answers(quiz: RemoteQuiz, answers: List<Int?>): RemoteQuiz = error("No sincronizar anónimos")
        override suspend fun edit(quiz: RemoteQuiz, title: String, questions: List<EditedQuestion>): RemoteQuiz = error("Sin edición")
        override suspend fun delete(quiz: RemoteQuiz) = error("Sin eliminación remota")
    }

    @Test fun queuedAnswerSurvivesLoginAndLogoutAndServerIdentitySeparatesEqualUserIds() {
        val context = context(); val anonymousQuiz = quiz("anonymous-source")
        AnonymousQuizStore(context).update { listOf(AnonymousQuizRecord(anonymousQuiz)) }
        val vm = model(context)
        val aStore = AccountQuizStore(context, "https://a.example", 17)
        val bStore = AccountQuizStore(context, "https://b.example", 17)
        aStore.save(listOf(QuizRecord(quiz("account-a"), 17, QuizOrigin.Local)))
        bStore.save(listOf(QuizRecord(quiz("account-b"), 17, QuizOrigin.Local)))
        val a = AccountStudyCoordinator(17, NoGenerationRemote(), aStore)
        val b = AccountStudyCoordinator(17, NoGenerationRemote(), bStore)
        main { assertTrue(vm.selectQuiz(anonymousQuiz.id)); vm.selectAnswer(3); vm.bindAccount(a) }
        waitFor { !vm.state.value.isBusy && vm.state.value.quizzes.map { it.id } == listOf("account-a") }
        assertEquals(listOf(3, null), AnonymousQuizStore(context).load().single().answers)
        assertNull(aStore.load().single().answers.first())
        main { vm.bindAccount(b) }
        waitFor { !vm.state.value.isBusy && vm.state.value.quizzes.map { it.id } == listOf("account-b") }
        assertEquals(bStore.workspaceKey, vm.workspace.value)
        main { vm.bindAccount(null) }
        waitFor { !vm.state.value.isBusy }
        main { assertTrue(vm.selectQuiz(anonymousQuiz.id)) }
        assertEquals(listOf(3, null), vm.state.value.answers)
        assertEquals(listOf(anonymousQuiz), vm.state.value.quizzes)
    }

    @Test fun lateAccountResponseCannotPublishIntoANewerWorkspace() = runBlocking {
        val context = context(); val vm = model(context)
        val gate = CompletableDeferred<Unit>(); val slow = NoGenerationRemote(gate)
        val aStore = AccountQuizStore(context, "https://a.example", 17)
        aStore.save(listOf(QuizRecord(quiz("only-a"), 17, QuizOrigin.Local)))
        main { vm.bindAccount(AccountStudyCoordinator(17, slow, aStore)) }
        slow.listing.await()
        main { vm.bindAccount(null) }
        assertTrue(vm.state.value.quizzes.isEmpty())
        gate.complete(Unit)
        waitFor { !vm.state.value.isBusy }
        assertTrue(vm.state.value.quizzes.isEmpty()); assertNull(vm.accountOwner.value)
        assertEquals("only-a", aStore.load().single().quiz.id)
    }

    @Test fun pauseAndFinishDoNotExitAfterAnAnonymousDiskFailure() {
        val context = context(); val quiz = quiz("disk-failure")
        AnonymousQuizStore(context).update { listOf(AnonymousQuizRecord(quiz, listOf(0, 1), 1)) }
        val vm = model(context); main { assertTrue(vm.selectQuiz(quiz.id)) }
        val temporary = File(context.noBackupFilesDir, "anonymous/quizzes.json.new")
        temporary.mkdirs(); File(temporary, "blocked").writeText("fixture")
        var exited = false
        main { vm.pauseQuiz { exited = true } }
        waitFor { !vm.state.value.isBusy }
        assertFalse(exited); assertNotNull(vm.state.value.message)
        main { vm.clearMessage(); vm.saveAndLeaveQuiz { exited = true } }
        waitFor { !vm.state.value.isBusy }
        assertFalse(exited)
        assertEquals(listOf(0, 1), vm.state.value.answers)
        assertNotNull(vm.state.value.message)
        main { vm.clearMessage(); vm.finishQuiz { exited = true } }
        waitFor { !vm.state.value.isBusy }
        assertFalse(exited); assertFalse(vm.state.value.activeQuiz!!.completed)
        assertEquals(listOf(0, 1), vm.state.value.answers)
    }

    @Test fun restoredResultsReviewResetPauseAndFinishPersistTheCurrentAttempt() {
        val context = context(); val quiz = quiz("results-and-reset")
        AnonymousQuizStore(context).update { listOf(AnonymousQuizRecord(quiz.copy(completed = true), listOf(0, 3), 1)) }
        val vm = model(context); main { assertTrue(vm.selectQuiz(quiz.id)) }
        assertEquals(1, vm.state.value.correctAnswers)
        main { vm.reviewAnswers() }; waitFor { !vm.state.value.isBusy }
        assertTrue(vm.isReview.value)
        assertEquals(0, AnonymousQuizStore(context).load().single().currentIndex)
        main { vm.selectAnswer(2) }; assertEquals(listOf(0, 3), vm.state.value.answers)
        main { vm.resetAttempt() }; waitFor { !vm.state.value.isBusy }
        val reset = AnonymousQuizStore(context).load().single()
        assertFalse(reset.quiz.completed); assertEquals(listOf(null, null), reset.answers)
        assertFalse(vm.state.value.quizzes.single().completed)
        main { vm.selectAnswer(0) }; waitFor { !vm.state.value.isBusy }
        main { assertFalse(vm.nextQuestion()) }; waitFor { !vm.state.value.isBusy }
        var paused = false
        main { vm.pauseQuiz { paused = true } }; waitFor { !vm.state.value.isBusy }
        assertTrue(paused)
        val reopened = model(context); main { assertTrue(reopened.selectQuiz(quiz.id)) }
        assertEquals(listOf(0, null), reopened.state.value.answers); assertEquals(1, reopened.state.value.currentQuestionIndex)
        main { reopened.selectAnswer(1) }; waitFor { !reopened.state.value.isBusy }
        var finished = false
        main { reopened.finishQuiz { finished = true } }; waitFor { !reopened.state.value.isBusy }
        assertTrue(finished); assertEquals(1, reopened.state.value.correctAnswers)
        assertTrue(AnonymousQuizStore(context).load().single().quiz.completed)
        main { reopened.deleteAnonymousQuiz(quiz.id) }; waitFor { !reopened.state.value.isBusy }
        assertTrue(AnonymousQuizStore(context).load().isEmpty()); assertNull(reopened.state.value.activeQuiz)
    }
}
