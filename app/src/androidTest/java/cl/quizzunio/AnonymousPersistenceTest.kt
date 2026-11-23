package cl.quizzunio

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.study.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnonymousPersistenceTest {
    private fun context(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.noBackupFilesDir, "anonymous-test-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getFilesDir(): File = File(directory, "legacy").apply { mkdirs() }
        }
    }
    private fun quiz(id: String = UUID.randomUUID().toString(), count: Int = 3) = StudyQuiz(id,
        "Biología 😀 n\u0303 漢字", "Células\t𝄞", List(count) { QuizQuestion("¿Célula $it?\nEspacio  y\ttab ñ", listOf("Á", "漢", "😀", "n\u0303"), it % 4) })
    private fun file(context: Context) = File(context.noBackupFilesDir, "anonymous/quizzes.json")

    @Test fun partialCompletedResetAndIndividualDeletionSurviveNewInstances() = runBlocking {
        val context = context()
        val first = quiz(); val second = quiz(); val third = quiz()
        val coordinator = AnonymousStudyCoordinator(AnonymousQuizStore(context))
        coordinator.load(); coordinator.add(first); coordinator.add(second); coordinator.add(third)
        val partial = AnonymousQuizRecord(first, listOf(2, null, 1), 2)
        val completed = AnonymousQuizRecord(second.copy(completed = true), listOf(0, 3, 2), 2)
        coordinator.capture(partial); coordinator.capture(completed)
        assertEquals(partial, AnonymousQuizStore(context).load().single { it.quiz.id == first.id })
        assertEquals(completed, AnonymousQuizStore(context).load().single { it.quiz.id == second.id })
        assertEquals(2, completed.answers.indices.count { completed.answers[it] == second.questions[it].correctAnswer })
        val reset = AnonymousQuizRecord(second)
        val recovered = AnonymousStudyCoordinator(AnonymousQuizStore(context)); recovered.load()
        recovered.capture(reset); recovered.delete(first.id)
        val records = AnonymousQuizStore(context).load()
        assertEquals(setOf(second.id, third.id), records.map { it.quiz.id }.toSet())
        assertEquals(reset, records.single { it.quiz.id == second.id })
        assertTrue(file(context).readText(Charsets.UTF_8).contains("😀"))
    }

    @Test fun historicalCountsAndBlankFieldsRemainReadableWithoutWeakeningNewCreations() = runBlocking {
        val context = context(); val store = AnonymousQuizStore(context)
        val historical = listOf(16, 50).map { count -> AnonymousQuizRecord(quiz(count = count).copy(
            questions = List(count) { QuizQuestion(" \n", listOf("", " ", "😀", "ñ"), 2) })) }
        store.update { historical }
        assertEquals(historical, AnonymousQuizStore(context).load())
        val coordinator = AnonymousStudyCoordinator(store); coordinator.load()
        val attempt = historical.last().copy(answers = List(50) { if (it == 49) 3 else null }, currentIndex = 49)
        coordinator.capture(attempt)
        assertEquals(attempt, AnonymousQuizStore(context).load().last())
        assertThrows(IllegalArgumentException::class.java) { LocalQuizGenerator().generate("material", 16) }
        Unit
    }

    @Test fun backupWithoutBaseAndInterruptedWriteRecoverTheLastCommittedSnapshot() = runBlocking {
        val context = context(); val coordinator = AnonymousStudyCoordinator(AnonymousQuizStore(context))
        coordinator.load(); coordinator.add(quiz())
        val expected = AnonymousQuizStore(context).load()
        val base = file(context)
        assertTrue(base.renameTo(File(base.path + ".bak")))
        assertFalse(base.exists())
        assertEquals(expected, AnonymousQuizStore(context).load())
        val atomic = AtomicFile(base)
        val stream = atomic.startWrite()
        stream.write("{\"incomplete\":".toByteArray(Charsets.UTF_8))
        stream.close() // Simula interrupción sin finishWrite; la siguiente lectura recupera el commit.
        assertEquals(expected, AnonymousQuizStore(context).load())
    }

    @Test fun corruptionAndIncompatibleSnapshotsCannotBeReplacedByNewWrites() {
        val valid = LocalQuizSnapshotCodec.encode(AnonymousQuizRecord(quiz()))
        val corruptions = listOf<ByteArray>(
            "{".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28),
            JSONObject().put("formatVersion", 2).put("workspace", "anonymous").put("records", JSONArray()).toString().toByteArray(),
            root(JSONArray().put(valid).put(valid)),
            root(JSONArray().put(JSONObject(valid.toString()).put("currentIndex", 99))),
            root(JSONArray().put(JSONObject(valid.toString()).put("answers", JSONArray(listOf(0, 4, -1))))),
            root(JSONArray().put(JSONObject(valid.toString()).put("title", 123))),
            root(JSONArray().put(JSONObject(valid.toString()).put("answers", JSONArray(listOf("0", -1, -1))))),
            JSONObject().put("formatVersion", 1).put("workspace", "account").put("records", JSONArray()).toString().toByteArray()
        )
        corruptions.forEach { bytes ->
            val context = context(); val target = file(context)
            target.parentFile!!.mkdirs(); target.writeBytes(bytes)
            val store = AnonymousQuizStore(context)
            assertThrows(IOException::class.java) { store.load() }
            assertThrows(IOException::class.java) { store.update { emptyList() } }
            assertArrayEquals(bytes, target.readBytes())
        }
    }

    @Test fun twoStoreInstancesSerializeMutationsAndAccountDataRemainIsolated() = runBlocking {
        val context = context(); val first = AnonymousQuizStore(context); val second = AnonymousQuizStore(context)
        val quizzes = List(20) { quiz() }
        quizzes.mapIndexed { index, quiz -> async(Dispatchers.IO) {
            (if (index % 2 == 0) first else second).update { it + AnonymousQuizRecord(quiz) }
        } }.awaitAll()
        assertEquals(quizzes.map { it.id }.toSet(), first.load().map { it.quiz.id }.toSet())
        AccountQuizStore(context, "https://a.example", 17).save(listOf(QuizRecord(quiz(), 17, QuizOrigin.Local)))
        assertEquals(20, second.load().size)
        assertTrue(AccountQuizStore(context, "https://a.example", 18).load().isEmpty())
        assertTrue(AccountQuizStore(context, "https://b.example", 17).load().isEmpty())
        assertTrue(StudyQuizRepository(context).load().quizzes.isEmpty())
    }

    @Test fun failedDiskWriteDoesNotPublishOrDiscardThePreviousAttempt() = runBlocking {
        val context = context(); val coordinator = AnonymousStudyCoordinator(AnonymousQuizStore(context))
        coordinator.load(); val quiz = quiz(); coordinator.add(quiz)
        val before = file(context).readBytes()
        // Un directorio en la ruta temporal impide startWrite sin tocar el snapshot válido.
        assertTrue(File(file(context).path + ".new").mkdir())
        File(file(context).path + ".new/blocked").writeText("Prueba de fallo de almacenamiento")
        try { coordinator.capture(AnonymousQuizRecord(quiz, listOf(0, null, null))); fail("Debe fallar la escritura") }
        catch (expected: IOException) { assertNotNull(expected.message) }
        assertEquals(listOf(null, null, null), coordinator.record(quiz.id)!!.answers)
        assertArrayEquals(before, file(context).readBytes())
    }

    private fun root(records: JSONArray): ByteArray = JSONObject().put("formatVersion", 1).put("workspace", "anonymous")
        .put("records", records).toString().toByteArray(Charsets.UTF_8)
}
