package cl.quizzunio

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.auth.*
import cl.quizzunio.study.*
import cl.quizzunio.ui.navigation.AppNavHost
import cl.quizzunio.ui.theme.QuizzunioTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Cliente y disco reales contra HTTP local controlado; no certifica QuizzunioServer/MariaDB. */
class QuizRenameHttpTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun remoteRenamePreservesIdsVersionAndAnswersAndRetainsInputAfterHttpError() {
        val storage = IsolatedStudyUiStorage(ui.activity)
        val context = storage.context
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val original = RemoteQuiz(81, 731, "Prueba HTTP Ñ", "2026-11-23 00:00:00.000", 3,
            List(2) { RemoteQuestion(900L + it, 81, "¿Pregunta $it?", listOf("Á", "B", "漢", "😀"), 0, if (it == 0) 2 else -1) })
        val document = AtomicReference(original)
        val requestError = AtomicReference<Throwable?>(null)
        val failFirstEdit = AtomicBoolean(true)
        val edits = AtomicInteger()
        val expiry = java.time.Instant.now().plusSeconds(3600).toString()
        val endpoint = "http://127.0.0.1:${socket.localPort}"
        executor.submit {
            try {
                while (!socket.isClosed) socket.accept().use { client ->
                    client.soTimeout = 10_000
                    val input = client.getInputStream()
                    val header = ByteArrayOutputStream()
                    var suffix = 0
                    while (suffix != 0x0d0a0d0a) {
                        val byte = input.read(); check(byte >= 0)
                        header.write(byte); suffix = (suffix shl 8) or byte
                    }
                    val lines = header.toString("UTF-8").split("\r\n")
                    val request = lines.first()
                    val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val bytes = ByteArray(length); var received = 0
                    while (received < length) { val count = input.read(bytes, received, length - received); check(count > 0); received += count }
                    val authorized = lines.any { it.equals("Authorization: Bearer ${"A".repeat(43)}", true) }
                    val (status, body) = when {
                        request.startsWith("POST /auth/login ") -> "200 OK" to
                            """{"tokenType":"Bearer","accessToken":"${"A".repeat(43)}","refreshToken":"${"E".repeat(43)}","accessExpiresAt":"$expiry","refreshExpiresAt":"$expiry","absoluteExpiresAt":"$expiry"}"""
                        !authorized -> "401 Unauthorized" to """{"code":"INVALID_TOKEN"}"""
                        request.startsWith("GET /auth/me ") -> "200 OK" to """{"userId":731,"username":"Prueba renombrado","email":"rename@example.test","tier":"Free"}"""
                        request.startsWith("GET /quizzes?") -> "200 OK" to JSONObject().put("page", 1).put("pageSize", 20)
                            .put("quizzes", JSONArray().put(QuizCodec.encode(document.get()))).toString()
                        request.startsWith("GET /quizzes/81 ") -> "200 OK" to QuizCodec.encode(document.get()).toString()
                        request.startsWith("PUT /quizzes/81 ") -> {
                            edits.incrementAndGet()
                            val sent = JSONObject(String(bytes, Charsets.UTF_8))
                            assertEquals(setOf("title", "version", "questions"), sent.keys().asSequence().toSet())
                            assertEquals(document.get().version, sent.getLong("version"))
                            val questions = sent.getJSONArray("questions")
                            assertEquals(2, questions.length())
                            original.questions.forEachIndexed { index, q ->
                                val item = questions.getJSONObject(index)
                                assertEquals(setOf("questionId", "question", "options", "correctAnswerIndex"), item.keys().asSequence().toSet())
                                assertEquals(q.questionId, item.getLong("questionId")); assertEquals(q.question, item.getString("question"))
                                assertEquals(q.options, (0..3).map { item.getJSONArray("options").getString(it) })
                                assertEquals(q.correctAnswerIndex, item.getInt("correctAnswerIndex"))
                            }
                            if (failFirstEdit.getAndSet(false)) "503 Service Unavailable" to """{"code":"QUIZ_UNAVAILABLE"}"""
                            else {
                                document.set(document.get().copy(title = sent.getString("title"), version = document.get().version + 1))
                                "200 OK" to QuizCodec.encode(document.get()).toString()
                            }
                        }
                        else -> throw AssertionError("Solicitud fuera del contrato de renombrado: ${request.substringBefore(" HTTP")}")
                    }
                    val payload = body.toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(payload); flush()
                    }
                }
            } catch (error: Throwable) { if (!socket.isClosed) requestError.set(error) }
        }
        try {
            val auth = AuthViewModel(context)
            ui.waitUntil(15_000) { auth.state.value.session is SessionState.Anonymous && !auth.state.value.busy }
            auth.configureServer(endpoint, confirmCleartext = true)
            ui.waitUntil(15_000) { auth.state.value.server == endpoint && !auth.state.value.busy }
            auth.login("rename", "PruebaLocal-123!")
            ui.waitUntil(15_000) { auth.state.value.session is SessionState.Authenticated && !auth.state.value.busy }
            lateinit var model: StudyQuizViewModel
            ui.runOnIdle { model = storage.model(ui.activity) }
            ui.setContent {
                QuizzunioTheme {
                    CompositionLocalProvider(LocalAuthViewModel provides auth, LocalContext provides context) {
                        AppNavHost(startDestination = "home")
                    }
                }
            }
            ui.waitUntil(15_000) { !model.state.value.isBusy && model.accountState.value.records.any { it.remote?.quizId == 81L } }
            ui.runOnIdle { assertTrue(model.selectQuiz("remote-81")); model.nextQuestion() }
            ui.waitUntil(15_000) { !model.state.value.isBusy }
            val before = model.accountState.value.records.single()
            ui.onNodeWithTag("quiz-list").performScrollToNode(hasTestTag("quiz-card-remote-81"))
            ui.onNodeWithTag("quiz-options-remote-81").performClick()
            ui.onNodeWithText("Editar").assertDoesNotExist()
            ui.onNodeWithText("Renombrar").performClick()
            val title = "Título remoto ñ漢😀 e\u0301"
            ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement(title)
            ui.onNodeWithText("Guardar").performClick()
            ui.waitUntil(15_000) { !model.state.value.isBusy }
            ui.onNodeWithText("Renombrar cuestionario").assertExists()
            ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).assertTextContains(title)
            assertEquals(before, model.accountState.value.records.single())
            ui.onNodeWithText("Guardar").performClick()
            ui.waitUntil(15_000) { !model.state.value.isBusy }
            ui.onNodeWithText("Renombrar cuestionario").assertDoesNotExist()
            val expected = before.copy(quiz = before.quiz.copy(title = title), remote = original.copy(title = title, version = 4))
            assertEquals(expected, model.accountState.value.records.single())
            assertEquals(listOf(expected), AccountQuizStore(context, endpoint, 731).load())
            assertEquals(expected.remote, runBlocking { RemoteQuizRepository(auth.client, auth.session).get(81) })
            assertEquals(2, edits.get()); assertNull(requestError.get())
        } finally { socket.close(); executor.shutdownNow() }
    }
}
