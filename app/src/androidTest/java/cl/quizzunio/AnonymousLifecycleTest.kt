package cl.quizzunio

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.auth.AuthViewModel
import cl.quizzunio.auth.SessionState
import cl.quizzunio.study.*
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Preparación y verificación separadas: el host realiza force-stop/reboot entre ellas. */
class AnonymousLifecycleTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val run get() = requireNotNull(InstrumentationRegistry.getArguments().getString("anonymousRun")) {
        "Esta aceptación exige un run explícito y orquestación externa, sin borrar datos."
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("anonymous_acceptance_$run", 0)
    private fun study(): StudyQuizViewModel = ViewModelProvider(ui.activity)[StudyQuizViewModel::class.java]
    private fun ready() {
        val auth = ViewModelProvider(ui.activity)[AuthViewModel::class.java]
        ui.waitUntil(30_000) { auth.state.value.session !is SessionState.Restoring && !study().state.value.isBusy }
        assertSame("No alterar una sesión ajena para esta prueba", SessionState.Anonymous, auth.state.value.session)
    }
    private fun waitWrite() { ui.waitUntil(30_000) { !study().state.value.isBusy }; ui.waitForIdle() }
    private fun main(block: () -> Unit) { ui.runOnIdle(block); waitWrite() }
    private fun fingerprint(record: AnonymousQuizRecord): String = MessageDigest.getInstance("SHA-256")
        .digest(LocalQuizSnapshotCodec.encode(record).toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test fun aPrepareDurableQuizzesAndVerifyRotation() {
        ready()
        val before = AnonymousQuizStore(context).load()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "anonymous-$run.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        val material = """
            La fotosíntesis es el proceso que transforma la energía luminosa en energía química para las plantas.
            La mitocondria es un orgánulo que produce energía para las actividades celulares mediante respiración.
            La clorofila es un pigmento que absorbe la luz solar en los tejidos verdes de las plantas.
            La membrana es una estructura que regula el intercambio de sustancias entre las células y su entorno.
            El ribosoma es una estructura responsable de fabricar proteínas siguiendo la información genética.
            El cromosoma es una estructura que contiene material genético organizado para la reproducción celular.
        """.trimIndent()
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(material.toByteArray(Charsets.UTF_8)) }
        fun generate(label: String) {
            main { study().updateQuizName("Anónimo 😀 $run $label"); study().updateTopic("Unidad n\u0303\t漢"); study().changeQuestionCount(2 - study().state.value.questionCount) }
            main { study().importDocuments(listOf(uri)) }
            main { study().generateQuiz {} }
            assertEquals(2, study().state.value.activeQuiz!!.questions.size)
        }
        generate("parcial")
        val partialId = study().state.value.activeQuiz!!.id
        main { study().selectAnswer((study().state.value.activeQuiz!!.questions.first().correctAnswer + 1) % 4) }
        main { assertFalse(study().nextQuestion()) }
        val paused = CountDownLatch(1)
        main { study().pauseQuiz { paused.countDown() } }
        assertTrue(paused.await(5, TimeUnit.SECONDS))
        main { study().clearMessage() }
        generate("completado")
        val completedId = study().state.value.activeQuiz!!.id
        main { study().selectAnswer(study().state.value.activeQuiz!!.questions.first().correctAnswer) }
        main { assertFalse(study().nextQuestion()) }
        main { study().selectAnswer((study().state.value.activeQuiz!!.questions.last().correctAnswer + 1) % 4) }
        val finished = CountDownLatch(1)
        main { study().finishQuiz { finished.countDown() } }
        assertTrue(finished.await(5, TimeUnit.SECONDS)); assertEquals(1, study().state.value.correctAnswers)
        val stored = AnonymousQuizStore(context).load()
        val partial = stored.single { it.quiz.id == partialId }; val completed = stored.single { it.quiz.id == completedId }
        assertEquals(1, partial.currentIndex); assertNull(partial.answers.last()); assertTrue(completed.quiz.completed)
        before.forEach { original -> assertEquals(original, stored.single { it.quiz.id == original.quiz.id }) }
        assertTrue(preferences.edit().putString("partial", partialId).putString("completed", completedId)
            .putString("partialHash", fingerprint(partial)).putString("completedHash", fingerprint(completed)).commit())
        ui.runOnIdle { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.waitUntil(15_000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        assertEquals(completedId, study().state.value.activeQuiz!!.id)
        ui.runOnIdle { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(15_000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        ui.activityRule.scenario.recreate(); waitWrite()
        assertEquals(stored.map { it.quiz }, study().state.value.quizzes)
        assertEquals(completedId, study().state.value.activeQuiz!!.id)
        // Cerrar definitivamente la actividad y abrir otra instancia exige una nueva carga de disco.
        ui.activityRule.scenario.close()
        val reopened = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var recovered: StudyQuizViewModel
            reopened.onActivity { recovered = ViewModelProvider(it)[StudyQuizViewModel::class.java] }
            val deadline = System.currentTimeMillis() + 15_000
            while (recovered.state.value.isBusy && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertFalse(recovered.state.value.isBusy)
            assertEquals(stored.map { it.quiz }, recovered.state.value.quizzes)
            assertTrue(recovered.selectQuiz(partialId))
            assertEquals(partial.answers, recovered.state.value.answers)
            assertEquals(1, recovered.state.value.currentQuestionIndex)
        } finally { reopened.close() }
    }

    @Test fun bVerifyAfterActualProcessDeath() { verifySavedRecords() }
    @Test fun cVerifyAfterDeviceReboot() { verifySavedRecords() }

    private fun verifySavedRecords() {
        ready()
        val stored = AnonymousQuizStore(context).load()
        listOf("partial", "completed").forEach { key ->
            val id = requireNotNull(preferences.getString(key, null)) { "Ejecutar preparación antes de terminar el proceso" }
            val record = stored.single { it.quiz.id == id }
            assertEquals(preferences.getString("${key}Hash", null), fingerprint(record))
            assertTrue(study().state.value.quizzes.any { it.id == id })
            main { assertTrue(study().selectQuiz(id)) }
            assertEquals(record.answers, study().state.value.answers)
            assertEquals(record.currentIndex, study().state.value.currentQuestionIndex)
            assertEquals(record.quiz.completed, study().state.value.activeQuiz!!.completed)
            if (key == "completed") {
                assertEquals(1, study().state.value.correctAnswers)
                ui.onNodeWithText(record.quiz.title).performScrollTo().performClick()
                ui.onNodeWithText("Resultados").assertExists()
                ui.onNodeWithText("1 / 2").assertExists()
            }
        }
    }
}
