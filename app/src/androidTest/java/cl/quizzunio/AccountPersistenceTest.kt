package cl.quizzunio

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.auth.*
import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.study.*
import java.io.File
import java.time.Instant
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountPersistenceTest {
    private fun isolatedContext(): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "test-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
    }

    @Test fun durableAnswersAreIsolatedByAccountAndServer() {
        val context = isolatedContext(); val server = "https://example.com"
        val quiz = StudyQuiz("local-123", "Biología", "Células", listOf(QuizQuestion("¿Unidad?", listOf("A", "B", "C", "D"), 0)))
        val record = QuizRecord(quiz, 17, QuizOrigin.Local, answers = listOf(2))
        AccountQuizStore(context, server, 17).save(listOf(record))
        assertEquals(listOf(record), AccountQuizStore(context, server, 17).load())
        assertTrue(AccountQuizStore(context, server, 18).load().isEmpty())
        assertTrue(AccountQuizStore(context, "https://other.example.com", 17).load().isEmpty())
        assertTrue(StudyQuizRepository(context).load().quizzes.isEmpty())
    }

    @Test fun remoteCodecKeepsIdsAndSendsAllUnansweredQuestions() {
        val quiz = RemoteQuiz(81, 17, "Prueba ñ", "2026-11-19 00:00:00.000", 8,
            listOf(RemoteQuestion(905, 81, "¿Primera?", listOf("A", "B", "C", "D"), 0, -1),
                RemoteQuestion(12, 81, "¿Segunda?", listOf("E", "F", "G", "H"), 2, 3)))
        assertEquals(quiz, QuizCodec.decode(JSONObject(QuizCodec.encode(quiz).toString())))
        val request = QuizCodec.answers(quiz, listOf(null, 2))
        assertEquals(8L, request.getLong("version"))
        val answers = request.getJSONArray("answers")
        assertEquals(2, answers.length()); assertEquals(905L, answers.getJSONObject(0).getLong("questionId"))
        assertEquals(-1, answers.getJSONObject(0).getInt("current_answer")); assertEquals(12L, answers.getJSONObject(1).getLong("questionId"))
        assertThrows(IllegalArgumentException::class.java) { QuizCodec.answers(quiz, listOf(0)) }
        val content = listOf(EditedQuestion(null, "¿Célula?", listOf("Uno", "Dos", "Tres", "Cuatro"), 0))
        assertEquals("¿Célula?", QuizCodec.content("Biología", content, false).getJSONArray("questions").getJSONObject(0).getString("question"))
        assertThrows(IllegalArgumentException::class.java) { QuizCodec.content("Biología", content, true, 8) }
    }

    @Test fun keystoreEncryptsTokensAndBindsCiphertextToTheServer() {
        val context = isolatedContext(); val server = "https://example.com"
        val pair = TokenPair("A".repeat(43), "B".repeat(43), Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2026-12-20T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"))
        val store = KeystoreTokenStore(context); store.write(server, pair)
        assertEquals(pair.accessToken, KeystoreTokenStore(context).read(server)?.accessToken)
        val stored = File(context.noBackupFilesDir, "session.enc").readBytes()
        assertFalse(String(stored, Charsets.ISO_8859_1).contains(pair.accessToken))
        assertThrows(java.io.IOException::class.java) { store.read("https://other.example.com") }
        store.clear(); assertNull(store.read(server))
    }
}
