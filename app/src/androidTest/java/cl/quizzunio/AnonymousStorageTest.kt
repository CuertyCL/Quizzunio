package cl.quizzunio

import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.study.StudyQuiz
import cl.quizzunio.study.StudyQuizRepository
import org.junit.Assert.*
import org.junit.Test

class AnonymousStorageTest {
    @Test fun anonymousCannotPersistOrLoadHistoricalQuizzes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = StudyQuizRepository(context)
        val quiz = StudyQuiz("anonymous-test", "Temporal", "", listOf(QuizQuestion("Pregunta", listOf("A", "B", "C", "D"), 0)))
        assertFalse(repository.canPersist)
        assertTrue(repository.load().quizzes.isEmpty())
        assertThrows(IllegalStateException::class.java) { repository.save(quiz) }
        assertTrue(StudyQuizRepository(context).load().quizzes.isEmpty())
    }
}
