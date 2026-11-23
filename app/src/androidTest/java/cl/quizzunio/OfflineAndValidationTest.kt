package cl.quizzunio

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.network.ApiException
import cl.quizzunio.study.AccountQuizStore
import cl.quizzunio.study.AccountStudyCoordinator
import cl.quizzunio.study.EditedQuestion
import cl.quizzunio.study.LocalQuizGenerator
import cl.quizzunio.study.QuizOrigin
import cl.quizzunio.study.QuizRecord
import cl.quizzunio.study.QuizRecordStore
import cl.quizzunio.study.QuizSyncState
import cl.quizzunio.study.RemotePage
import cl.quizzunio.study.RemoteQuestion
import cl.quizzunio.study.RemoteQuiz
import cl.quizzunio.study.RemoteQuizGateway
import cl.quizzunio.study.StudyDocumentReader
import cl.quizzunio.study.StudyQuizRepository
import cl.quizzunio.study.StudyQuizViewModel
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifica validaciones previas de generación remota y persistencia sin conexión en StudyQuizViewModel. */
@RunWith(AndroidJUnit4::class)
class OfflineAndValidationTest {
    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.noBackupFilesDir, "vm-test-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(dir, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(dir, "nobackup").apply { mkdirs() }
        }
    }

    private class OfflineRemoteGateway : RemoteQuizGateway {
        val remoteQuiz = RemoteQuiz(
            quizId = 42L,
            userId = 9L,
            title = "Remote Quiz",
            creationDate = "2026-11-19 00:00:00.000",
            version = 1L,
            questions = listOf(
                RemoteQuestion(101L, 42L, "Question one?", listOf("A", "B", "C", "D"), 0, -1),
                RemoteQuestion(102L, 42L, "Question two?", listOf("E", "F", "G", "H"), 1, -1)
            )
        )
        var failSync = true

        override suspend fun list(page: Int, pageSize: Int): RemotePage = RemotePage(page, pageSize, listOf(remoteQuiz))
        override suspend fun get(id: Long): RemoteQuiz = remoteQuiz
        override suspend fun generate(title: String, text: String, count: Int): RemoteQuiz = remoteQuiz
        override suspend fun importLocal(record: QuizRecord): RemoteQuiz = remoteQuiz
        override suspend fun answers(quiz: RemoteQuiz, answers: List<Int?>): RemoteQuiz {
            if (failSync) throw ApiException(0, "NETWORK_ERROR", "No se recibió confirmación.")
            return quiz
        }
        override suspend fun edit(quiz: RemoteQuiz, title: String, questions: List<EditedQuestion>): RemoteQuiz = remoteQuiz
        override suspend fun delete(quiz: RemoteQuiz) = Unit
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    @Test fun startRemoteGenerationValidatesPreconditionsBeforeNavigating() {
        val context = isolatedContext()
        val viewModel = StudyQuizViewModel(StudyQuizRepository(context), StudyDocumentReader(context), LocalQuizGenerator())
        val remote = OfflineRemoteGateway()
        val store = AccountQuizStore(context, "https://example.com", 9L)
        val coordinator = AccountStudyCoordinator(9L, remote, store)
        onMain { viewModel.bindAccount(coordinator) }
        waitUntil { !viewModel.state.value.isBusy }

        var navigated = false
        onMain { viewModel.startRemoteGeneration { navigated = true } }
        assertFalse(navigated)
        assertTrue(viewModel.state.value.message.orEmpty().contains("título"))

        onMain {
            viewModel.updateQuizName("Título á")
            viewModel.startRemoteGeneration { navigated = true }
        }
        assertFalse(navigated)
        assertTrue(viewModel.state.value.message.orEmpty().contains("Adjunta"))

        onMain {
            viewModel.updateQuizName("Valid Title")
            viewModel.changeQuestionCount(10)
            viewModel.startRemoteGeneration { navigated = true }
        }
        assertFalse(navigated)
        assertEquals(15, viewModel.state.value.questionCount)
    }

    @Test fun pauseAndFinishPersistPendingAnswersLocallyWhenOffline() {
        val context = isolatedContext()
        val remote = OfflineRemoteGateway().apply { failSync = true }
        val store = AccountQuizStore(context, "https://example.com", 9L)
        val initialQuiz = remote.remoteQuiz.toStudyQuiz()
        store.save(
            listOf(
                QuizRecord(
                    quiz = initialQuiz,
                    ownerId = 9L,
                    origin = QuizOrigin.Server,
                    remote = remote.remoteQuiz,
                    sync = QuizSyncState.Synced
                )
            )
        )
        val coordinator = AccountStudyCoordinator(9L, remote, store)
        val viewModel = StudyQuizViewModel(StudyQuizRepository(context), StudyDocumentReader(context), LocalQuizGenerator())
        onMain { viewModel.bindAccount(coordinator) }
        waitUntil { !viewModel.state.value.isBusy && viewModel.state.value.quizzes.isNotEmpty() }

        onMain {
            assertTrue(viewModel.selectQuiz(initialQuiz.id))
            viewModel.selectAnswer(0)
        }

        val pauseLatch = CountDownLatch(1)
        waitUntil { !viewModel.state.value.isBusy }
        onMain { viewModel.pauseQuiz { pauseLatch.countDown() } }
        assertTrue("pauseQuiz debe salir tras guardar localmente aunque falle la sincronización", pauseLatch.await(5, TimeUnit.SECONDS))
        waitUntil { !viewModel.state.value.isBusy }
        assertTrue(viewModel.state.value.message.orEmpty().contains("pendiente de sincronizar"))

        val afterPause = store.load().single()
        assertEquals(listOf(0, null), afterPause.answers)
        assertEquals(QuizSyncState.Pending, afterPause.sync)

        onMain {
            assertTrue(viewModel.selectQuiz(initialQuiz.id))
            assertFalse(viewModel.nextQuestion())
        }
        waitUntil { !viewModel.state.value.isBusy }
        onMain {
            viewModel.selectAnswer(1)
        }
        waitUntil { !viewModel.state.value.isBusy }
        onMain {
            assertTrue(viewModel.nextQuestion())
        }

        val finishLatch = CountDownLatch(1)
        onMain { viewModel.finishQuiz { finishLatch.countDown() } }
        assertTrue("finishQuiz debe mostrar resultados tras guardar localmente aunque falle la sincronización", finishLatch.await(5, TimeUnit.SECONDS))
        waitUntil { !viewModel.state.value.isBusy }
        assertTrue(viewModel.state.value.activeQuiz?.completed == true)
        assertTrue(viewModel.state.value.message.orEmpty().contains("pendiente de sincronizar"))

        val afterFinish = store.load().single()
        assertEquals(listOf(0, 1), afterFinish.answers)
        assertTrue(afterFinish.quiz.completed)
        assertEquals(QuizSyncState.Pending, afterFinish.sync)
    }

    @Test fun diskFailureOnFinishDoesNotCompleteQuiz() {
        val context = isolatedContext()
        val remote = OfflineRemoteGateway()
        val quiz = remote.remoteQuiz.toStudyQuiz()
        val failingStore = object : QuizRecordStore {
            var records = listOf(QuizRecord(quiz, 9L, QuizOrigin.Server, remote.remoteQuiz, sync = QuizSyncState.Synced))
            var failWrites = false
            override fun load(): List<QuizRecord> = records
            override fun save(records: List<QuizRecord>) {
                if (failWrites) throw IOException("Disco lleno")
                this.records = records
            }
            override fun loadGenerationUncertain(): Boolean = false
            override fun saveGenerationUncertain(records: List<QuizRecord>, value: Boolean) = save(records)
        }
        val coordinator = AccountStudyCoordinator(9L, remote, failingStore)
        val viewModel = StudyQuizViewModel(StudyQuizRepository(context), StudyDocumentReader(context), LocalQuizGenerator())
        onMain { viewModel.bindAccount(coordinator) }
        waitUntil { !viewModel.state.value.isBusy && viewModel.state.value.quizzes.isNotEmpty() }

        onMain {
            assertTrue(viewModel.selectQuiz(quiz.id))
            viewModel.selectAnswer(0)
        }
        waitUntil { !viewModel.state.value.isBusy }
        onMain {
            assertFalse(viewModel.nextQuestion())
        }
        waitUntil { !viewModel.state.value.isBusy }
        onMain {
            viewModel.selectAnswer(1)
        }
        waitUntil { !viewModel.state.value.isBusy }
        onMain {
            assertTrue(viewModel.nextQuestion())
        }
        waitUntil { failingStore.records.single().answers == listOf(0, 1) }

        failingStore.failWrites = true
        var completed = false
        onMain { viewModel.finishQuiz { completed = true } }
        waitUntil { !viewModel.state.value.isBusy && viewModel.state.value.message != null }
        assertFalse(completed)
        assertNotNull(viewModel.state.value.message)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("La condición no se cumplió dentro del tiempo esperado.")
    }
}
