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

    @Test fun renamePersistsOnlyTitleAndReportsDiskFailureWithoutLosingInputOrProgress() {
        val context = context(); val first = AnonymousQuizRecord(quiz("rename-first").copy(completed = true), listOf(2, 1), 1)
        val other = AnonymousQuizRecord(quiz("rename-other"))
        AnonymousQuizStore(context).update { listOf(first, other) }
        val vm = model(context); main { assertTrue(vm.selectQuiz(first.quiz.id)) }
        val title = "Nuevo título ñ漢😀 e\u0301"
        var completed = false; var error: String? = null
        main { vm.renameQuiz(first.quiz.id, title, null, { completed = true }, { error = it }) }
        waitFor { !vm.state.value.isBusy }
        assertTrue(completed); assertNull(error)
        val expected = first.copy(quiz = first.quiz.copy(title = title))
        assertEquals(listOf(expected, other), AnonymousQuizStore(context).load())
        val reopened = model(context); main { assertTrue(reopened.selectQuiz(first.quiz.id)) }
        assertEquals(title, reopened.state.value.activeQuiz!!.title)
        assertEquals(first.answers, reopened.state.value.answers); assertEquals(1, reopened.state.value.currentQuestionIndex)
        assertTrue(reopened.state.value.activeQuiz!!.completed)
        val base = File(context.noBackupFilesDir, "anonymous/quizzes.json")
        val bytes = base.readBytes()
        completed = false
        main { reopened.renameQuiz(first.quiz.id, title, null, { completed = true }, { error = it }) }
        waitFor { !reopened.state.value.isBusy }
        assertTrue(completed); assertArrayEquals(bytes, base.readBytes())
        File(context.noBackupFilesDir, "anonymous/quizzes.json.new").apply { mkdirs(); File(this, "blocked").writeText("fixture") }
        completed = false
        main { reopened.renameQuiz(first.quiz.id, "Reintentar 😀", null, { completed = true }, { error = it }) }
        waitFor { !reopened.state.value.isBusy }
        assertFalse(completed); assertNotNull(error)
        assertEquals(title, reopened.state.value.activeQuiz!!.title)
        assertEquals(listOf(expected, other), AnonymousQuizStore(context).load())
    }

    @Test fun renameCannotPublishOrModifyTheNextOwnerWithTheSameLocalId() {
        val context = context(); val source = quiz("shared-rename-id")
        AnonymousQuizStore(context).update { listOf(AnonymousQuizRecord(source)) }
        val vm = model(context)
        val store = AccountQuizStore(context, "https://rename.example", 17)
        val accountRecord = QuizRecord(source.copy(title = "Título exclusivo de la cuenta"), 17, QuizOrigin.Local)
        store.save(listOf(accountRecord))
        var callback = false
        main {
            vm.renameQuiz(source.id, "Nuevo anónimo", null, { callback = true }, { fail(it) })
            vm.bindAccount(AccountStudyCoordinator(17, NoGenerationRemote(), store))
        }
        waitFor { !vm.state.value.isBusy && vm.accountOwner.value == 17L }
        assertEquals(listOf(accountRecord), store.load())
        assertEquals(listOf(accountRecord.quiz), vm.state.value.quizzes)
        assertFalse(callback)
        main { vm.renameQuiz(source.id, "No autorizado desde el espacio anterior", null, { fail("No debe aceptar otro espacio") }, { fail(it) }) }
        assertEquals(listOf(accountRecord), store.load())
    }
}
