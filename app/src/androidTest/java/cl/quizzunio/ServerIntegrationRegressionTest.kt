package cl.quizzunio

import android.content.ContentValues
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.auth.*
import cl.quizzunio.network.*
import cl.quizzunio.study.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regresión E2E contra QuizzunioServer real, MariaDB desechable y proveedor IA sintético (sin llamadas IA reales). */
@RunWith(AndroidJUnit4::class)
class ServerIntegrationRegressionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var auth: AuthViewModel
    private lateinit var study: StudyQuizViewModel
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val runId get() = requireNotNull(arguments.getString("geminiRun"))
    private val baseUrl get() = requireNotNull(arguments.getString("geminiServerUrl"))
    private val controlKey get() = requireNotNull(arguments.getString("geminiControl"))
    private val password = "Gemini-Valid-123!"

    @Before fun connect() {
        Assume.assumeTrue("Requiere fixture aislado y argumentos gemini explícitos.", arguments.getString("geminiServerUrl") != null)
        require(baseUrl.startsWith("http://127.0.0.1:")) { "Se exige fixture aislado mediante túnel ADB." }
        ui.runOnIdle {
            auth = ViewModelProvider(ui.activity)[AuthViewModel::class.java]
            study = ViewModelProvider(ui.activity)[StudyQuizViewModel::class.java]
        }
        ui.waitUntil(30_000) { auth.state.value.session !is SessionState.Restoring && !auth.state.value.busy }
        if (auth.state.value.session is SessionState.Unavailable) {
            click("Abandonar sesión local y continuar como anónimo")
            ui.waitUntil(30_000) { auth.state.value.session is SessionState.Anonymous && !auth.state.value.busy }
            dismissMessages()
        }
        ui.waitUntil(30_000) { !study.state.value.isBusy }
        if (auth.state.value.session is SessionState.Anonymous && auth.state.value.server != baseUrl) {
            configureServer(baseUrl)
        }
    }

    private fun click(text: String) {
        val node = ui.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick(); ui.waitForIdle()
    }

    private fun field(index: Int, value: String) {
        ui.onAllNodes(hasSetTextAction())[index].performScrollTo().performTextReplacement(value)
    }

    private fun dismissMessages() {
        repeat(4) {
            if (ui.onAllNodesWithText("Entendido").fetchSemanticsNodes().isNotEmpty()) click("Entendido")
        }
    }

    private fun back() { ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }; ui.waitForIdle() }
    private fun waitStudy() { ui.waitUntil(90_000) { !study.state.value.isBusy && !study.accountState.value.busy }; ui.waitForIdle() }

    private fun control(path: String, method: String = "GET"): JSONObject = runBlocking {
        val response = UrlConnectionTransport().send(method, baseUrl + path, mapOf("X-Acceptance-Control" to controlKey), null)
        assertTrue("Control fixture HTTP ${response.status}: ${response.body}", response.status in 200..299)
        if (response.body.isEmpty()) JSONObject() else JSONObject(response.body)
    }

    private fun configureServer(url: String) {
        ui.onNodeWithContentDescription("Ajustes").performClick()
        click("Servidor")
        field(0, url); click("Aplicar servidor")
        if (ui.onAllNodesWithText("Confirmar uso de HTTP").fetchSemanticsNodes().isNotEmpty()) {
            click("Confirmar uso de HTTP")
        }
        ui.waitUntil(30_000) { auth.state.value.server == url && !auth.state.value.busy }
        back(); back()
    }

    private fun login(username: String) {
        ui.onNodeWithContentDescription("Ajustes").performClick()
        click("Cuenta")
        click("Iniciar sesión")
        field(0, username); field(1, password); click("Iniciar sesión")
        ui.waitUntil(30_000) { (auth.state.value.session as? SessionState.Authenticated)?.user?.username == username && !auth.state.value.busy }
        waitStudy()
    }

    private fun logout() {
        dismissMessages()
        ui.onNodeWithContentDescription("Ajustes").performClick()
        click("Cuenta")
        click("Cerrar sesión")
        ui.waitUntil(30_000) { auth.state.value.session is SessionState.Anonymous && !auth.state.value.busy }
        ui.waitUntil(30_000) { study.accountOwner.value == null && !study.state.value.isBusy }
        assertEquals(cl.quizzunio.study.AnonymousQuizStore(ui.activity).load().map { it.quiz }, study.state.value.quizzes)
        dismissMessages()
    }

    private fun accessibilityClick(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (root == null) return false
        if (predicate(root)) {
            var candidate: AccessibilityNodeInfo? = root
            repeat(5) {
                if (candidate?.isClickable == true) {
                    val bounds = Rect(); candidate?.getBoundsInScreen(bounds)
                    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")
                    ).use { it.readBytes() }
                    return true
                }
                candidate = candidate?.parent
            }
        }
        for (index in 0 until root.childCount) if (accessibilityClick(root.getChild(index), predicate)) return true
        return false
    }

    private fun selectDocument(label: String, content: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileName = "gem_${runId}_$label.txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(content.toByteArray(Charsets.UTF_8)) }
        click("Toca para agregar archivos")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 15_000
        var selected = false
        while (SystemClock.uptimeMillis() < deadline && !selected) {
            selected = accessibilityClick(automation.rootInActiveWindow) { it.text?.toString() == fileName }
            if (!selected) SystemClock.sleep(200)
        }
        assertTrue("El selector debe mostrar $fileName", selected)
        val completeDeadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < completeDeadline && study.state.value.documents.none { it.name == fileName }) {
            val confirmed = accessibilityClick(automation.rootInActiveWindow) {
                it.text?.toString()?.lowercase() in listOf("open", "abrir", "select", "seleccionar") ||
                    it.contentDescription?.toString()?.lowercase() in listOf("open", "abrir", "select", "seleccionar") ||
                    it.viewIdResourceName?.endsWith(":id/action_menu_select") == true
            }
            if (!confirmed) accessibilityClick(automation.rootInActiveWindow) { it.text?.toString() == fileName }
            SystemClock.sleep(200)
        }
        ui.waitUntil(20_000) { study.state.value.documents.any { it.name == fileName } && !study.state.value.isBusy }
    }

    private val asciiMaterial = """
        Photosynthesis converts solar energy into chemical energy inside plant cells.
        Mitochondria produce cellular energy during aerobic respiration in eukaryotes.
        Chlorophyll absorbs sunlight within green leaf tissues during photosynthesis.
        Membranes regulate substance exchange between cells and their surroundings.
        Ribosomes synthesize proteins by following genetic instructions from messenger RNA.
        Chromosomes organize genetic material required for accurate cellular reproduction.
    """.trimIndent()

    @Test fun r1aRevokedSessionSetup() {
        val username = "gem_${runId}_rev"
        runBlocking { AuthApi(auth.client).register(username, "$username@example.com", password) }
        login(username)
        control("/acceptance/revoke-user/$username", "POST")
    }

    @Test fun r1bRevokedSessionProcessRestartNotifies() {
        ui.waitUntil(30_000) { auth.state.value.session is SessionState.Anonymous && !auth.state.value.busy }
        ui.onNodeWithText("Tu sesión anterior expiró o fue revocada. Continúas en modo anónimo.").assertExists()
        dismissMessages()
        assertEquals(cl.quizzunio.study.AnonymousQuizStore(ui.activity).load().map { it.quiz }, study.state.value.quizzes)
    }

    @Test fun r2OfflineInteractionPauseFinishAndLaterSync() {
        val username = "gem_${runId}_off"
        val title = "Biología ñ $runId"
        runBlocking {
            val pair = AuthApi(auth.client).register(username, "$username@example.com", password)
            val body = QuizCodec.content(
                title,
                listOf(
                    EditedQuestion(null, "¿Qué orgánulo produce ATP?", listOf("Mitocondria", "Ribosoma", "Núcleo", "Golgi"), 0),
                    EditedQuestion(null, "¿Qué pigmento absorbe luz?", listOf("Clorofila", "Hemoglobina", "Melanina", "Queratina"), 0)
                ),
                ascii = false
            )
            val created = QuizCodec.decode(
                JSONObject(
                    auth.client.request("POST", "/quizzes/import", 201, body, pair.accessToken, mapOf("Idempotency-Key" to "gem-import-$runId-123456"))
                )
            )
            AuthApi(auth.client).logout(pair.accessToken)
            assertEquals(title, created.title)
        }
        login(username)
        val record = study.accountState.value.records.single { it.quiz.title == title }
        val remoteId = record.remote!!.quizId

        control("/acceptance/offline/on", "POST")
        try {
            click(title)
            val q1 = study.state.value.activeQuiz!!.questions[0]
            click(q1.answers[q1.correctAnswer])
            // La falla transitoria de /auth/activity sin conexión no debe abrir diálogos de error.
            ui.onAllNodesWithText("Entendido").assertCountEquals(0)

            waitStudy()
            ui.onNodeWithContentDescription("Volver").performClick()
            waitStudy()
            ui.onAllNodesWithText("Entendido").assertCountEquals(0)
            dismissMessages()
            ui.onNodeWithText("Nuevo cuestionario").assertExists()
            assertEquals(QuizSyncState.Pending, study.accountState.value.records.single { it.quiz.id == record.quiz.id }.sync)

            click(title)
            assertEquals(listOf(0, null), study.state.value.answers)
            click("Siguiente")
            val q2 = study.state.value.activeQuiz!!.questions[1]
            click(q2.answers[q2.correctAnswer])
            click("Finalizar")
            waitStudy()
            ui.onNodeWithText("Resultados").assertExists()
            ui.onNodeWithText("2 / 2").assertExists()
            assertTrue(study.state.value.message.orEmpty().contains("pendiente de sincronizar"))
            dismissMessages()
            click("Volver al inicio")
        } finally {
            control("/acceptance/offline/off", "POST")
        }

        click("Sincronizar")
        waitStudy(); dismissMessages()
        val synced = study.accountState.value.records.single { it.quiz.id == record.quiz.id }
        assertEquals(QuizSyncState.Synced, synced.sync)
        val serverDoc = runBlocking { RemoteQuizRepository(auth.client, auth.session).get(remoteId) }
        assertEquals(listOf(0, 0), serverDoc.questions.map { it.currentAnswer })

        // Conflicto 409 IMPORT_KEY_CONFLICT con misma clave y distinto contenido
        runBlocking {
            val conflictBody = QuizCodec.content(
                "Otro contenido $runId",
                listOf(EditedQuestion(null, "Otra pregunta", listOf("A", "B", "C", "D"), 1)),
                ascii = false
            )
            try {
                auth.session.authorized { token ->
                    auth.client.request("POST", "/quizzes/import", 201, conflictBody, token, mapOf("Idempotency-Key" to "gem-import-$runId-123456"))
                }
                fail("Debe rechazar clave repetida con distinto contenido")
            } catch (expected: ApiException) {
                assertEquals(409, expected.status)
                assertEquals("IMPORT_KEY_CONFLICT", expected.code)
                assertTrue(expected.message.orEmpty().contains("clave de importación"))
            }
        }
        logout()
    }

    @Test fun r3PremiumPreValidationAndSyntheticServerGeneration() {
        val username = "gem_${runId}_prem"
        runBlocking {
            val pair = AuthApi(auth.client).register(username, "$username@example.com", password)
            control("/acceptance/promote/$username", "POST")
            AuthApi(auth.client).logout(pair.accessToken)
        }
        login(username)
        assertEquals(UserTier.Premium, (auth.state.value.session as SessionState.Authenticated).user.tier)
        val before = control("/acceptance/metrics")

        click("Nuevo cuestionario")
        ui.onNodeWithText("Servidor · Premium").assertIsSelected()
        // Sin título ni archivo: no debe abrir AiProcessingScreen ni llamar POST /quizzes.
        click("Generar en servidor")
        ui.onNodeWithText("Generando con IA").assertDoesNotExist()
        dismissMessages()

        val title = "Biología 漢字 😀 $runId"
        val material = "$asciiMaterial\n\n  Energía\t∑, n\u0303, 中文, 😀 y 𐐀.\n"
        field(0, title)
        selectDocument("P", material)
        assertEquals(material, study.state.value.documents.single().text)
        repeat(5) { click("+") }
        assertEquals(15, study.state.value.questionCount)
        ui.onNodeWithText("+").assertIsNotEnabled()
        assertEquals(before.optInt("POST /quizzes"), control("/acceptance/metrics").optInt("POST /quizzes"))

        // Material Unicode con dos preguntas y proveedor sintético local.
        repeat(13) { click("−") }
        assertEquals(2, study.state.value.questionCount)
        click("Generar en servidor")
        ui.onNodeWithText("Generando con IA").assertExists()
        ui.waitUntil(30_000) { study.generationStage.value == GenerationStage.Validated || study.generationStage.value == GenerationStage.Failed }
        assertEquals("La generación sintética debe validarse: ${study.state.value.message}", GenerationStage.Validated, study.generationStage.value)
        click("Continuar al cuestionario")
        val record = study.accountState.value.records.single { it.quiz.title == title }
        assertNotNull(record.remote)
        assertEquals(QuizOrigin.Server, record.origin)
        assertEquals(1, control("/acceptance/metrics").optInt("synthetic_ai_requests"))
        back()
        logout()
    }

    @Test fun r4RestoreDefaultServer() {
        if (auth.state.value.session is SessionState.Authenticated) logout()
        configureServer("https://10.0.2.2:8443")
        assertSame(SessionState.Anonymous, auth.state.value.session)
    }
}
