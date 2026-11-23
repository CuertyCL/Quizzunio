package cl.quizzunio.ui

import cl.quizzunio.ui.screens.QuizProgress
import cl.quizzunio.ui.screens.ResultBand
import cl.quizzunio.ui.screens.quizProgress
import cl.quizzunio.ui.screens.quizResult
import org.junit.Assert.assertEquals
import org.junit.Test

class QuizPresentationTest {
    @Test fun progressUsesAnswersAndGivesPriorityToCompletion() {
        assertEquals(QuizProgress.NotStarted, quizProgress(false, emptyList()))
        assertEquals(QuizProgress.NotStarted, quizProgress(false, listOf(null, null)))
        assertEquals(QuizProgress.InProgress, quizProgress(false, listOf(0)))
        assertEquals(QuizProgress.InProgress, quizProgress(false, listOf(null, 3, null)))
        assertEquals(QuizProgress.Completed, quizProgress(true, listOf(3, 3)))
        assertEquals(QuizProgress.Completed, quizProgress(true, emptyList()))
    }

    @Test fun resultBandsUseInclusiveBoundariesAndTheirOwnHeading() {
        val expected = mapOf(0 to ResultBand.Practice, 39 to ResultBand.Practice, 40 to ResultBand.Good,
            69 to ResultBand.Good, 70 to ResultBand.Excellent, 100 to ResultBand.Excellent)
        expected.forEach { (score, band) ->
            val result = quizResult(score, 100)
            assertEquals(score, result.percentage)
            assertEquals(band, result.band)
        }
        assertEquals("¡Sigue practicando!", ResultBand.Practice.heading)
        assertEquals("¡Buen trabajo!", ResultBand.Good.heading)
        assertEquals("¡Excelente!", ResultBand.Excellent.heading)
    }

    @Test fun historicalCountsAndInvalidTotalsUseTheActualCount() {
        assertEquals(40, quizResult(20, 50).percentage)
        assertEquals(70, quizResult(35, 50).percentage)
        assertEquals(0, quizResult(2, 0).total)
        assertEquals(0, quizResult(2, -1).percentage)
        assertEquals(100, quizResult(Int.MAX_VALUE, Int.MAX_VALUE).percentage)
    }
}
