package cl.quizzunio

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.auth.AuthViewModel
import cl.quizzunio.auth.KeystoreTokenStore
import cl.quizzunio.auth.LocalAuthViewModel
import cl.quizzunio.auth.SessionState
import cl.quizzunio.auth.UserTier
import cl.quizzunio.study.*
import cl.quizzunio.ui.navigation.AppNavHost
import cl.quizzunio.ui.theme.QuizzunioTheme
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI y transporte reales con respuestas de desarrollo locales, sin IA ni cuentas existentes. */
class AuthUiCorrectionsTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun loginRecoversPremiumThenBothGenerationButtonsRunDirectly() = Fixture(UserTier.Premium, 404).use { server ->
        val (auth, study, context) = start(server, "login", dark = true)
        login()
        ui.onNodeWithText("El servidor aceptó el inicio de sesión, pero no se pudo verificar tu identidad.").assertIsDisplayed()
        ui.onNodeWithText("Nuevo cuestionario").assertDoesNotExist()
        assertNull(study.accountOwner.value)
        assertNotNull(KeystoreTokenStore(context).read(server.url))
        capture("auth-recovery")
        click("Reintentar verificación")
        ready(study)
        assertEquals(1, server.count("POST /auth/login"))
        assertEquals(2, server.count("GET /auth/me"))
        assertEquals(UserTier.Premium, (auth.state.value.session as SessionState.Authenticated).user.tier)
        assertSimplifiedHome()
        capture("premium-home")
        click("Nuevo cuestionario")
        ui.onNodeWithText("Servidor · Premium").assertDoesNotExist()
        attach(study, context, "Premium local")
        val local = ui.onNodeWithText("Generar local").performScrollTo().assertIsEnabled()
        val ai = ui.onNodeWithText("Generar con IA").assertIsEnabled()
        assertTrue(local.fetchSemanticsNode().boundsInRoot.left < ai.fetchSemanticsNode().boundsInRoot.left)
        capture("premium-create")
        local.performClick()
        ui.onNodeWithTag("quiz-play-title").assertTextEquals("Premium local")
        assertEquals(0, server.count("POST /quizzes"))
        ui.onNodeWithContentDescription("Volver").performClick()
        ui.waitUntil(15_000) { !study.state.value.isBusy }
        ui.onNodeWithText("Premium local").assertExists()
        repeat(2) { index ->
            click("Nuevo cuestionario")
            val title = "Premium IA ${index + 1}"
            attach(study, context, title)
            click("Generar con IA")
            ui.waitUntil(20_000) { study.generationStage.value == GenerationStage.Validated && !study.state.value.isBusy }
            ui.onNodeWithText("Continuar al cuestionario").assertIsEnabled()
            ui.onNodeWithText("Volver a Inicio").assertIsEnabled()
            val requests = server.count("GET /quizzes")
            if (index == 0) {
                capture("ai-completed")
                click("Volver a Inicio")
                ui.onNodeWithTag("quiz-play-title").assertDoesNotExist()
                ui.onNode(hasText(title) and hasText("Por intentar", substring = true)).assertExists()
                click(title)
            } else click("Continuar al cuestionario")
            ui.onNodeWithTag("quiz-play-title").assertTextEquals(title)
            assertEquals(index + 1, server.count("POST /quizzes"))
            assertEquals(requests, server.count("GET /quizzes"))
            ui.onNodeWithContentDescription("Volver").performClick()
            ui.waitUntil(15_000) { !study.state.value.isBusy }
            ui.onNodeWithText("Generando con IA").assertDoesNotExist()
        }
    }

    @Test fun registrationRecoveryAndLocalAbandonKeepFreePermissionsAndPostsSeparate() =
        Fixture(UserTier.Free, 503, 404).use { server ->
            val (auth, study, context) = start(server, "register", dark = false)
            field(0, "audit_user")
            field(1, "audit@example.test")
            field(2, "Prueba sintética 123")
            field(3, "Prueba sintética 123")
            click("Registrarse")
            ui.onNodeWithText("El servidor aceptó el registro, pero no se pudo verificar tu identidad.").assertIsDisplayed()
            click("Reintentar verificación")
            ui.onNodeWithText("El servidor aceptó el registro, pero no se pudo verificar tu identidad.").assertExists()
            assertEquals(1, server.count("POST /auth/register"))
            assertEquals(2, server.count("GET /auth/me"))
            click("Abandonar sesión local y continuar como anónimo")
            ui.waitUntil(15_000) { auth.state.value.session is SessionState.Anonymous && !auth.state.value.busy }
            assertNull(KeystoreTokenStore(context).read(server.url))
            assertEquals(0, server.count("POST /auth/logout"))
            click("Entendido")
            click("¿Ya tienes cuenta? Inicia sesión")
            login()
            ready(study)
            assertEquals(UserTier.Free, (auth.state.value.session as SessionState.Authenticated).user.tier)
            assertEquals(1, server.count("POST /auth/login"))
            assertSimplifiedHome()
            capture("free-home")
            click("Nuevo cuestionario")
            ui.onNodeWithText("Generar localmente").performScrollTo().assertIsEnabled()
            ui.onNodeWithText("Usar IA Premium ✦").assertIsNotEnabled()
            ui.onNodeWithText("Generar con IA").assertDoesNotExist()
            capture("free-create")
        }

    @Test fun successfulRegistrationOpensFreeHomeAndLoadsTheNextPage() =
        Fixture(UserTier.Free, multiplePages = true).use { server ->
            val (auth, study, context) = start(server, "register", dark = false)
            field(0, "audit_user")
            field(1, "audit@example.test")
            field(2, "Prueba sintética 123")
            field(3, "Prueba sintética 123")
            click("Registrarse")
            ready(study)
            assertEquals(UserTier.Free, (auth.state.value.session as SessionState.Authenticated).user.tier)
            assertNotNull(KeystoreTokenStore(context).read(server.url))
            val list = ui.onNodeWithTag("quiz-list")
            list.performScrollToNode(hasText("Cargar más"))
            click("Cargar más")
            ui.waitUntil(15_000) { study.accountState.value.page == 2 }
            list.performScrollToNode(hasText("Último remoto"))
            ui.onNodeWithText("Último remoto").assertIsDisplayed()
            assertEquals(1, server.count("POST /auth/register"))
            assertEquals(0, server.count("POST /auth/login"))
            assertEquals(2, server.count("GET /quizzes"))
            list.performScrollToIndex(0)
            list.performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.8f) }
            ui.waitUntil(15_000) { server.count("GET /quizzes") == 3 && study.accountState.value.page == 1 }
        }

    private fun login() {
        field(0, "audit_user")
        field(1, "Prueba sintética 123")
        click("Iniciar sesión")
    }

    private fun field(index: Int, text: String) {
        ui.onAllNodes(hasSetTextAction())[index].performScrollTo().performTextReplacement(text)
    }

    private fun click(text: String) {
        val node = ui.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
    }

    private fun ready(study: StudyQuizViewModel) {
        ui.waitUntil(15_000) { study.accountState.value.page == 1 && !study.accountState.value.busy && !study.state.value.isBusy }
        ui.onNodeWithText("Nuevo cuestionario").assertExists()
    }

    private fun assertSimplifiedHome() {
        ui.onNodeWithText("Remoto de prueba").assertExists()
        ui.onNodeWithText("Local de prueba").assertExists()
        for (text in listOf("Importar cuestionario del dispositivo", "Actualizar nube", "Editar", "Sincronizar",
            "Consultar versión del servidor", "Sustituir por versión del servidor", "Nube · Versión")) {
            ui.onNodeWithText(text, substring = true).assertDoesNotExist()
        }
    }

    private fun start(server: Fixture, route: String, dark: Boolean): Triple<AuthViewModel, StudyQuizViewModel, Context> {
        val storage = IsolatedStudyUiStorage(ui.activity)
        val context = storage.context
        val wire = Fixture.quiz(1, "Local de prueba", 1).toStudyQuiz("fixture-local")
        AccountQuizStore(context, server.url, 731).save(listOf(QuizRecord(wire, 731, QuizOrigin.Local)))
        val auth = AuthViewModel(context)
        ui.waitUntil(15_000) { auth.state.value.session is SessionState.Anonymous && !auth.state.value.busy }
        ui.runOnIdle { auth.configureServer(server.url, confirmCleartext = true) }
        ui.waitUntil(15_000) { auth.state.value.server == server.url && !auth.state.value.busy }
        lateinit var study: StudyQuizViewModel
        ui.runOnIdle { study = storage.model(ui.activity) }
        ui.setContent {
            QuizzunioTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAuthViewModel provides auth, LocalContext provides context) {
                    AppNavHost(startDestination = route)
                }
            }
        }
        return Triple(auth, study, context)
    }

    private fun attach(study: StudyQuizViewModel, context: Context, title: String) {
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "auth-ui-${UUID.randomUUID()}.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
        }))
        try {
            resolver.openOutputStream(uri)!!.use {
                it.write(("La fotosíntesis es el proceso que transforma energía luminosa en energía química.\n" +
                    "La mitocondria es un orgánulo que produce energía para las células mediante respiración.\n" +
                    "La clorofila es un pigmento que absorbe la luz en los tejidos verdes.\n" +
                    "El ribosoma es una estructura que fabrica proteínas mediante información genética.").toByteArray())
            }
            ui.runOnIdle { study.changeQuestionCount(2 - study.state.value.questionCount); study.importDocuments(listOf(uri), forServer = true) }
            ui.waitUntil(15_000) { !study.state.value.isBusy && study.state.value.documents.isNotEmpty() }
            field(0, title)
        } finally { resolver.delete(uri, null, null) }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, "auth-ui-$name.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private class Fixture(private val tier: UserTier, vararg failures: Int, private val multiplePages: Boolean = false) : AutoCloseable {
        private val requests = ConcurrentLinkedQueue<String>()
        private val statuses = ConcurrentLinkedQueue(failures.toList())
        private val serial = AtomicInteger(1)
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val url = "http://127.0.0.1:${socket.localPort}"

        init {
            executor.submit {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: java.net.SocketException) { break }
                    client.use {
                        it.soTimeout = 5_000
                        val input = BufferedInputStream(it.getInputStream())
                        val target = line(input).split(' ').take(2).joinToString(" ")
                        val request = target.substringBefore('?')
                        var header = line(input)
                        var length = 0
                        var bearer = false
                        while (header.isNotEmpty()) {
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                            if (header.equals("Authorization: Bearer ${"A".repeat(43)}", true)) bearer = true
                            header = line(input)
                        }
                        val body = input.readNBytes(length).toString(Charsets.UTF_8)
                        requests.add(request)
                        val page = target.substringAfter("page=", "1").substringBefore('&').toInt()
                        val (code, response) = answer(request, bearer, body, page)
                        val bytes = response.toByteArray(Charsets.UTF_8)
                        it.getOutputStream().apply {
                            write("HTTP/1.1 $code Fixture\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }

        private fun line(input: BufferedInputStream): String = buildString {
            while (true) {
                val byte = input.read()
                if (byte < 0 || byte == 10) break
                if (byte != 13) append(byte.toChar())
            }
        }

        fun count(request: String) = requests.count { it == request }

        private fun answer(request: String, bearer: Boolean, body: String, page: Int): Pair<Int, String> {
            if (request == "POST /auth/login" || request == "POST /auth/register") {
                val expire = Instant.now().plusSeconds(1800)
                return (if (request.endsWith("register")) 201 else 200) to
                    """{"tokenType":"Bearer","accessToken":"${"A".repeat(43)}","refreshToken":"${"E".repeat(43)}","accessExpiresAt":"$expire","refreshExpiresAt":"$expire","absoluteExpiresAt":"$expire"}"""
            }
            if (!bearer) return 401 to """{"code":"INVALID_TOKEN"}"""
            return when (request) {
                "GET /auth/me" -> statuses.poll()?.let { it to """{"code":"FIXTURE_PROFILE_FAILURE"}""" }
                    ?: (200 to """{"userId":731,"username":"audit_user","email":"audit@example.test","tier":"${tier.name}"}""")
                "POST /auth/activity" -> 204 to ""
                "GET /quizzes" -> {
                    val rows = when {
                        page == 2 -> listOf(quiz(21, "Último remoto", 2))
                        multiplePages -> (1L..20L).map { quiz(it, "Remoto $it", 2) }
                        else -> listOf(quiz(1, "Remoto de prueba", 2))
                    }
                    200 to JSONObject().put("page", page).put("pageSize", 20)
                        .put("quizzes", JSONArray(rows.map(QuizCodec::encode))).toString()
                }
                "GET /quizzes/limits" -> 200 to """{"maxTextBytes":65536,"encoding":"UTF-8"}"""
                "POST /quizzes" -> {
                    if (tier != UserTier.Premium) return 403 to """{"code":"PREMIUM_REQUIRED"}"""
                    val data = JSONObject(body)
                    val quiz = quiz(serial.incrementAndGet().toLong(), data.getString("title"), data.getInt("questionCount"))
                    201 to QuizCodec.encode(quiz).toString()
                }
                else -> 404 to "{}"
            }
        }

        override fun close() {
            socket.close()
            executor.shutdownNow()
        }

        companion object {
            fun quiz(id: Long, title: String, count: Int) =
                RemoteQuiz(id, 731, title, "2026-11-26 00:00:00.000", 1, List(count) {
                    RemoteQuestion(id * 20 + it, id, "¿Pregunta ${it + 1}?", listOf("A", "B", "C", "D"), 0, -1)
                })
        }
    }
}
