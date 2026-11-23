package cl.quizzunio

import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.study.StudyQuiz
import cl.quizzunio.study.StudyQuizRepository
import org.junit.Assert.*
import org.junit.Test
import android.content.Context
import android.content.ContextWrapper
import cl.quizzunio.study.AnonymousQuizStore
import cl.quizzunio.study.AnonymousStudyCoordinator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

class AnonymousStorageTest {
    @Test fun anonymousPersistsInItsOwnSpaceWithoutLoadingHistoricalQuizzes() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.noBackupFilesDir, "anonymous-legacy-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val repository = StudyQuizRepository(context)
        val quiz = StudyQuiz("anonymous-test", "Temporal", "", listOf(QuizQuestion("Pregunta", listOf("A", "B", "C", "D"), 0)))
        assertFalse(repository.canPersist)
        assertTrue(repository.load().quizzes.isEmpty())
        assertThrows(IllegalStateException::class.java) { repository.save(quiz) }
        assertTrue(StudyQuizRepository(context).load().quizzes.isEmpty())
        val coordinator = AnonymousStudyCoordinator(AnonymousQuizStore(context))
        coordinator.load(); coordinator.add(quiz)
        assertEquals(quiz, AnonymousQuizStore(context).load().single().quiz)
    }
}
