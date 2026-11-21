package cl.quizzunio

import android.content.ContentValues
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.view.MotionEvent
import android.view.InputDevice
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
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Aceptación sobre actividad, selector Android, HTTP y persistencia reales. Requiere fixture explícito. */
@RunWith(AndroidJUnit4::class)
class Phase10AcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var auth: AuthViewModel
    private lateinit var study: StudyQuizViewModel
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val runId get() = requireNotNull(arguments.getString("phase10Run"))
    private val baseUrl get() = requireNotNull(arguments.getString("phase10BaseUrl"))
    private val controlKey get() = requireNotNull(arguments.getString("phase10Control"))
    private val password = "Phase10-Valid-123!"
    private val freeName get() = "p10_${runId}_free"

    @Before fun connectToRealActivity() {
        org.junit.Assume.assumeTrue("Aceptación integral requiere fixture aislado y argumentos phase10 explícitos.", arguments.getString("phase10BaseUrl") != null)
        require(baseUrl.startsWith("http://127.0.0.1:")) { "Se exige fixture aislado mediante túnel ADB explícito." }
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
        if (auth.state.value.session is SessionState.Anonymous && auth.state.value.server != baseUrl) configureServerThroughUi()
    }

    private fun click(text: String) {
        val node = ui.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick(); ui.waitForIdle()
    }
    private fun field(index: Int, value: String) { ui.onAllNodes(hasSetTextAction())[index].performScrollTo().performTextReplacement(value) }
    private fun dismissMessages() {
        repeat(3) {
            if (ui.onAllNodesWithText("Entendido").fetchSemanticsNodes().isNotEmpty()) click("Entendido")
        }
    }
    private fun back() { ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }; ui.waitForIdle() }
    private fun waitStudy() { ui.waitUntil(90_000) { !study.state.value.isBusy && !study.accountState.value.busy }; ui.waitForIdle() }
    private fun user(): UserIdentity = (auth.state.value.session as SessionState.Authenticated).user
    private fun metrics(): JSONObject = control("/acceptance/metrics")
    private fun quizJsonFiles(): Set<String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return listOf(context.filesDir, context.noBackupFilesDir).flatMap { directory ->
            directory.walkTopDown().filter { it.isFile && it.extension == "json" }.map { it.absolutePath }.toList()
        }.toSet()
    }
    private fun control(path: String, method: String = "GET"): JSONObject = runBlocking {
        val response = UrlConnectionTransport().send(method, baseUrl + path, mapOf("X-Acceptance-Control" to controlKey), null)
        assertTrue("Control del fixture: HTTP ${response.status}", response.status in 200..299)
        if (response.body.isEmpty()) JSONObject() else JSONObject(response.body)
    }

    private fun configureServerThroughUi() {
        ui.onNodeWithContentDescription("Ajustes").performClick()
        field(0, baseUrl); click("Aplicar servidor")
        if (ui.onAllNodesWithText("Confirmar uso de HTTP").fetchSemanticsNodes().isNotEmpty()) {
            click("Confirmar uso de HTTP")
        }
        ui.waitUntil(30_000) { auth.state.value.server == baseUrl && !auth.state.value.busy }
        back()
    }

    private val material = """
        La fotosintesis es el proceso que transforma la energia luminosa en energia quimica para las plantas.
        La mitocondria es un organulo que produce energia para las actividades celulares mediante respiracion.
        La clorofila es un pigmento que absorbe la luz solar en los tejidos verdes de las plantas.
        La membrana es una estructura que regula el intercambio de sustancias entre las celulas y su entorno.
        El ribosoma es una estructura responsable de fabricar proteinas siguiendo la informacion genetica.
        El cromosoma es una estructura que contiene material genetico organizado para la reproduccion celular.
        El citoplasma es el medio donde ocurren diferentes reacciones metabolicas necesarias para las celulas.
        El nucleo es la estructura que conserva la informacion hereditaria y regula diferentes funciones celulares.
    """.trimIndent()

    private fun accessibilityClick(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (root == null) return false
        if (predicate(root)) {
            var candidate: AccessibilityNodeInfo? = root
            repeat(5) {
                if (candidate?.isClickable == true) {
                    val bounds = Rect(); candidate?.getBoundsInScreen(bounds)
                    val time = SystemClock.uptimeMillis()
                    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                    val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    val up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    down.source = InputDevice.SOURCE_TOUCHSCREEN
                    up.source = InputDevice.SOURCE_TOUCHSCREEN
                    try {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                            automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")
                        ).use { it.readBytes() }
                        return true
                    }
                    finally { down.recycle(); up.recycle() }
                }
                candidate = candidate?.parent
            }
        }
        for (index in 0 until root.childCount) if (accessibilityClick(root.getChild(index), predicate)) return true
        return false
    }

    private fun selectRealDocument(label: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileName = "p10_${runId}_$label.txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(material.toByteArray(Charsets.UTF_8)) }
        click("Toca para agregar archivos")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 15_000
        var selected = false
        while (SystemClock.uptimeMillis() < deadline && !selected) {
            selected = accessibilityClick(automation.rootInActiveWindow) { it.text?.toString() == fileName }
            if (!selected) SystemClock.sleep(200)
        }
        assertTrue("El selector de documentos debe mostrar el TXT real $fileName", selected)
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
        try { ui.waitUntil(20_000) { study.state.value.documents.any { it.name == fileName } && !study.state.value.isBusy } }
        catch (error: ComposeTimeoutException) {
            fail("El selector no entregó $fileName. Documentos=${study.state.value.documents.map { it.name }}, ocupado=${study.state.value.isBusy}, mensaje=${study.state.value.message}")
        }
        assertTrue(study.state.value.documents.single().text.contains("mitocondria"))
    }

    private fun createLocal(title: String, documentLabel: String) {
        click("Nuevo cuestionario")
        click("Local")
        field(0, title)
        repeat(study.state.value.questionCount - 2) { click("−") }
        selectRealDocument(documentLabel)
        click("Generar localmente")
        ui.waitUntil(30_000) { study.state.value.activeQuiz?.title == title && !study.state.value.isBusy }
        ui.onNodeWithText(study.state.value.activeQuiz!!.questions.first().question).assertExists()
    }

    private fun answerAll() {
        while (true) {
            val state = study.state.value
            val quiz = requireNotNull(state.activeQuiz)
            if (state.answers[state.currentQuestionIndex] == null) {
                val question = quiz.questions[state.currentQuestionIndex]
                click(question.answers[question.correctAnswer])
            }
            if (state.currentQuestionIndex == quiz.questions.lastIndex) {
                click("Finalizar"); waitStudy(); break
            } else click("Siguiente")
        }
        ui.onNodeWithText("Resultados").assertExists()
        ui.onNodeWithText("2 / 2").assertExists()
    }

    @Test fun aAnonymousGenerationAndRotation() {
        assertSame(SessionState.Anonymous, auth.state.value.session)
        configureServerThroughUi()
        val before = metrics()
        val filesBefore = quizJsonFiles()
        createLocal("p10-$runId-A", "A")
        assertEquals(2, study.state.value.activeQuiz!!.questions.size)
        answerAll()
        val after = metrics()
        assertEquals(before.optInt("POST /quizzes"), after.optInt("POST /quizzes"))
        assertEquals(before.optInt("POST /quizzes/import"), after.optInt("POST /quizzes/import"))
        assertEquals(0, after.optInt("POST /auth/login"))
        assertTrue(StudyQuizRepository(ui.activity).load().quizzes.isEmpty())
        assertEquals("El flujo anónimo no crea ningún JSON persistente", filesBefore, quizJsonFiles())
        val quizId = study.state.value.activeQuiz!!.id
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        assertEquals("La rotación conserva el ViewModel anónimo y sus respuestas", quizId, study.state.value.activeQuiz?.id)
        assertEquals(2, study.state.value.answers.filterNotNull().size)
    }

    @Test fun aAnonymousProcessDeathRestoresNoQuiz() {
        assertSame(SessionState.Anonymous, auth.state.value.session)
        assertTrue(study.state.value.quizzes.isEmpty())
        assertNull(study.state.value.activeQuiz)
        assertTrue(StudyQuizRepository(ui.activity).load().quizzes.isEmpty())
        ui.onNodeWithText("Nuevo cuestionario").assertExists()
        ui.onNodeWithText("p10-$runId-A").assertDoesNotExist()
    }

    private fun enterAccess() {
        ui.onNodeWithContentDescription("Ajustes").performClick()
        click("Iniciar sesión o registrarse")
        if (ui.onAllNodesWithText("Continuar al acceso").fetchSemanticsNodes().isNotEmpty()) click("Continuar al acceso")
    }

    private fun loginThroughUi(name: String, secret: String = password) {
        enterAccess(); click("Iniciar sesión")
        field(0, name); field(1, secret); click("Iniciar sesión")
        ui.waitUntil(30_000) { (auth.state.value.session as? SessionState.Authenticated)?.user?.username == name && !auth.state.value.busy }
        waitStudy()
        assertEquals(name, user().username)
    }

    private fun logoutThroughUi() {
        dismissMessages()
        ui.onNodeWithContentDescription("Ajustes").performClick()
        click("Cerrar todas las sesiones")
        ui.waitUntil(30_000) { auth.state.value.session is SessionState.Anonymous && !auth.state.value.busy }
        ui.waitUntil(30_000) { study.accountOwner.value == null && study.state.value.quizzes.isEmpty() }
        dismissMessages()
        ui.onNodeWithText("Nuevo cuestionario").assertExists()
    }

    @Test fun bFreeRegistrationLoginImportAnswers() {
        if (auth.state.value.session is SessionState.Authenticated) {
            assertTrue("Solo se cierra una cuenta creada por el fixture", user().username.startsWith("p10_"))
            logoutThroughUi()
        }
        assertSame(SessionState.Anonymous, auth.state.value.session)
        enterAccess(); click("Registrarse")
        field(0, freeName); field(1, "$freeName@example.com"); field(2, password); field(3, password); click("Registrarse")
        ui.waitUntil(30_000) { auth.state.value.session is SessionState.Authenticated && !auth.state.value.busy }
        waitStudy(); assertEquals(UserTier.Free, user().tier)
        logoutThroughUi(); loginThroughUi(freeName)
        val before = metrics()
        val title = "p10-$runId-B"
        createLocal(title, "B")
        back()
        click("Guardar en nube"); waitStudy(); dismissMessages()
        val record = study.accountState.value.records.single { it.quiz.title == title }
        assertNotNull(record.remote); assertEquals(user().userId, record.remote!!.userId)
        assertEquals(before.optInt("POST /quizzes") , metrics().optInt("POST /quizzes"))
        assertEquals(before.optInt("POST /quizzes/import") + 1, metrics().optInt("POST /quizzes/import"))
        click(title)
        val question = study.state.value.activeQuiz!!.questions.first()
        click(question.answers[question.correctAnswer])
        click("Guardar progreso y salir"); waitStudy(); dismissMessages()
        val remote = runBlocking { RemoteQuizRepository(auth.client, auth.session).get(record.remote.quizId) }
        assertEquals(question.correctAnswer, remote.questions.first().currentAnswer)
        assertEquals(-1, remote.questions[1].currentAnswer)
        click(title); answerAll(); click("Volver al inicio")
        val confirmed = runBlocking { RemoteQuizRepository(auth.client, auth.session).get(record.remote.quizId) }
        assertTrue(confirmed.questions.all { it.currentAnswer == it.correctAnswerIndex })
    }

    @Test fun bFreeProcessDeathRestoresSessionAndAnswers() {
        ui.waitUntil(30_000) { auth.state.value.session is SessionState.Authenticated && study.accountState.value.records.isNotEmpty() }
        assertEquals(freeName, user().username); assertEquals(UserTier.Free, user().tier)
        val record = study.accountState.value.records.single { it.quiz.title == "p10-$runId-B" }
        assertTrue(record.answers.all { it != null })
        click(record.quiz.title)
        assertEquals(record.answers, study.state.value.answers)
    }

    private fun ownRepository() = RemoteQuizRepository(auth.client, auth.session)
    private fun asciiContent(title: String, version: Long? = null, questionId: Long? = null): JSONObject =
        QuizCodec.content(title, listOf(EditedQuestion(questionId, "Which process stores solar energy?",
            listOf("Photosynthesis", "Respiration", "Diffusion", "Osmosis"), 0)), true, version)

    @Test fun dFreeCannotGenerateButCanImport() {
        assertEquals(UserTier.Free, user().tier)
        val before = metrics()
        click("Nuevo cuestionario")
        ui.onNodeWithText("Servidor · Premium").assertIsNotEnabled()
        back()
        runBlocking {
            try { ownRepository().generate("Forbidden", material, 2); fail("Free no puede generar en el repositorio") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("Premium")) }
            try {
                auth.session.authorized { token -> auth.client.request("POST", "/quizzes", 201,
                    JSONObject().put("title", "Forbidden").put("text", material).put("questionCount", 2), token) }
                fail("El servidor debe denegar la solicitud directa Free")
            } catch (expected: ApiException) { assertEquals(403, expected.status); assertEquals("PREMIUM_REQUIRED", expected.code) }
            val imported = auth.session.authorized { token -> auth.client.request("POST", "/quizzes/import", 201,
                asciiContent("p10-$runId-D"), token, mapOf("Idempotency-Key" to java.util.UUID.randomUUID().toString())) }
            val quiz = QuizCodec.decode(JSONObject(imported))
            assertEquals(user().userId, quiz.userId); assertEquals(-1, quiz.questions.single().currentAnswer)
        }
        assertEquals(before.optInt("real_ai_requests"), metrics().optInt("real_ai_requests"))
        click("Actualizar nube"); waitStudy()
        assertTrue(study.accountState.value.records.any { it.quiz.title == "p10-$runId-D" })
    }

    @Test fun eAccountIsolationAndGlobalLogout() {
        assertEquals(freeName, user().username)
        val owner = user()
        val owned = study.accountState.value.records.first { it.quiz.title == "p10-$runId-B" }.remote!!
        val oldAccess = runBlocking { auth.session.authorized { it } }
        logoutThroughUi()
        ui.onNodeWithText("p10-$runId-B").assertDoesNotExist()
        runBlocking {
            try { AuthApi(auth.client).me(oldAccess); fail("Logout debe revocar access") }
            catch (expected: ApiException) { assertEquals(401, expected.status) }
            val other = "p10_${runId}_other"
            val pair = AuthApi(auth.client).register(other, "$other@example.com", password)
            AuthApi(auth.client).logout(pair.accessToken)
        }
        loginThroughUi("p10_${runId}_other")
        assertNotEquals(owner.userId, user().userId)
        assertTrue(study.state.value.quizzes.isEmpty())
        ui.onNodeWithText("p10-$runId-B").assertDoesNotExist()
        runBlocking {
            for (method in listOf("GET", "PUT", "DELETE")) {
                try {
                    auth.session.authorized { token -> auth.client.request(method, "/quizzes/${owned.quizId}",
                        if (method == "DELETE") 204 else 200,
                        if (method == "PUT") asciiContent("Forbidden", owned.version, owned.questions.first().questionId) else null, token) }
                    fail("La cuenta B no puede $method un cuestionario de A")
                } catch (expected: ApiException) { assertEquals(404, expected.status) }
            }
            val ownerPair = AuthApi(auth.client).login(freeName, password)
            val unchanged = QuizCodec.decode(JSONObject(auth.client.request("GET", "/quizzes/${owned.quizId}", 200, token = ownerPair.accessToken)))
            assertEquals(owned, unchanged)
            AuthApi(auth.client).logout(ownerPair.accessToken)
        }
        logoutThroughUi(); loginThroughUi(freeName)
        assertTrue(study.state.value.quizzes.any { it.title == "p10-$runId-B" })
    }

    @Test fun fLostImportResponseIsReconciledWithoutDuplicate() {
        val title = "p10-$runId-F"
        val before = metrics()
        createLocal(title, "F"); back()
        control("/acceptance/drop-next-import", "POST")
        ui.onAllNodesWithText("Guardar en nube").onLast().performScrollTo().performClick()
        waitStudy()
        assertTrue("Debe informar que no recibió confirmación", study.state.value.message.orEmpty().contains("No se recibió confirmación"))
        assertNull(study.accountState.value.records.single { it.quiz.title == title }.remote)
        val committed = runBlocking { ownRepository().list(1).quizzes.filter { it.title == title } }
        assertEquals(1, committed.size)
        dismissMessages()
        ui.onAllNodesWithText("Guardar en nube").onLast().performScrollTo().performClick(); waitStudy(); dismissMessages()
        val confirmed = study.accountState.value.records.single { it.quiz.title == title }
        assertEquals(committed.single().quizId, confirmed.remote?.quizId)
        assertEquals(1, runBlocking { ownRepository().list(1).quizzes.count { it.title == title } })
        assertEquals(before.optInt("lost_confirmed_imports") + 1, metrics().optInt("lost_confirmed_imports"))
        assertEquals(before.optInt("real_ai_requests"), metrics().optInt("real_ai_requests"))
    }

    @Test fun gPasswordChangeRevokesAllSessions() {
        assertEquals(freeName, user().username)
        val oldAccess = runBlocking { auth.session.authorized { it } }
        val second = runBlocking { AuthApi(auth.client).login(freeName, password) }
        ui.onNodeWithContentDescription("Ajustes").performClick()
        ui.onNode(hasSetTextAction() and hasText("Contraseña actual")).performScrollTo().performTextReplacement(password)
        ui.onNode(hasSetTextAction() and hasText("Nueva contraseña (12–128 puntos de código)")).performScrollTo().performTextReplacement("Phase10-Changed-456!")
        click("Cambiar contraseña y revocar sesiones")
        ui.waitUntil(30_000) { auth.state.value.session is SessionState.Anonymous && !auth.state.value.busy }
        dismissMessages()
        runBlocking {
            for (access in listOf(oldAccess, second.accessToken)) {
                try { AuthApi(auth.client).me(access); fail("Access anterior debe quedar revocado") }
                catch (expected: ApiException) { assertEquals(401, expected.status) }
            }
            try { AuthApi(auth.client).refresh(second.refreshToken); fail("Refresh anterior debe quedar revocado") }
            catch (expected: ApiException) { assertEquals(401, expected.status) }
            try { AuthApi(auth.client).login(freeName, password); fail("Contraseña anterior debe fallar") }
            catch (expected: ApiException) { assertEquals("INVALID_CREDENTIALS", expected.code) }
        }
        loginThroughUi(freeName, "Phase10-Changed-456!")
        assertEquals(UserTier.Free, user().tier)
    }

    @Test fun hVersionedEditingPreservesDraftOnConflict() {
        val original = study.accountState.value.records.first { it.quiz.title == "p10-$runId-D" }
        val remote = original.remote!!
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(original.quiz.title))
        // Cada tarjeta conserva su orden; se usa la posición del registro cargado para abrir su editor.
        ui.onNode(hasText("Editar") and hasAnyAncestor(hasTestTag("quiz-actions-${original.quiz.id}"))).performScrollTo().performClick()
        field(0, "p10-$runId-H-saved")
        click("Revisar y guardar cambios"); click("Guardar en servidor"); waitStudy()
        val saved = study.accountState.value.records.single { it.quiz.id == original.quiz.id }
        assertEquals(remote.version + 1, saved.remote!!.version)
        assertEquals("p10-$runId-H-saved", saved.quiz.title)
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(saved.quiz.title))
        ui.onNode(hasText("Editar") and hasAnyAncestor(hasTestTag("quiz-actions-${saved.quiz.id}"))).performScrollTo().performClick()
        val draft = "p10-$runId-H-draft"
        field(0, draft)
        val newer = runBlocking { ownRepository().edit(saved.remote, "p10-$runId-H-external", saved.remote.questions.map {
            EditedQuestion(it.questionId, it.question, it.options, it.correctAnswerIndex)
        }) }
        click("Revisar y guardar cambios"); click("Guardar en servidor"); waitStudy()
        assertTrue(study.state.value.message.orEmpty().contains("cambió en el servidor"))
        assertEquals(QuizSyncState.Conflict, study.accountState.value.records.single { it.quiz.id == saved.quiz.id }.sync)
        dismissMessages()
        ui.onNode(hasSetTextAction() and hasText(draft)).assertExists()
        val retained = runBlocking { ownRepository().get(newer.quizId) }
        assertEquals(newer.version, retained.version); assertEquals("p10-$runId-H-external", retained.title)
    }

    @Test fun cPremiumRealGenerationAndLocalAlternative() {
        if (auth.state.value.session is SessionState.Authenticated) logoutThroughUi()
        val premium = "p10_${runId}_premium"
        runBlocking {
            val pair = AuthApi(auth.client).register(premium, "$premium@example.com", password)
            control("/acceptance/promote/$premium", "POST")
            AuthApi(auth.client).logout(pair.accessToken)
        }
        loginThroughUi(premium)
        assertEquals(UserTier.Premium, user().tier)
        val before = metrics()
        assertEquals("Solo se permite una generación real en todo el fixture", 0, before.optInt("real_ai_requests"))
        click("Nuevo cuestionario")
        ui.onNodeWithText("Servidor · Premium").assertIsSelected()
        field(0, "p10-$runId-C")
        repeat(study.state.value.questionCount - 2) { click("−") }
        selectRealDocument("C")
        click("Generar en servidor")
        ui.waitUntil(150_000) { study.generationStage.value == GenerationStage.Validated || study.generationStage.value == GenerationStage.Failed }
        assertEquals("La única llamada IA debe devolver un cuestionario real válido: ${study.state.value.message}", GenerationStage.Validated, study.generationStage.value)
        click("Continuar al cuestionario")
        val record = study.accountState.value.records.single { it.quiz.title == "p10-$runId-C" }
        assertNotNull(record.remote); assertEquals(QuizOrigin.Server, record.origin); assertEquals(2, record.quiz.questions.size)
        answerAll(); click("Volver al inicio")
        val confirmed = runBlocking { ownRepository().get(record.remote!!.quizId) }
        assertTrue(confirmed.questions.all { it.currentAnswer == it.correctAnswerIndex })
        assertEquals(1, metrics().optInt("real_ai_requests")); assertEquals(200, metrics().getInt("real_ai_status"))
        createLocal("p10-$runId-C-local", "CL")
        assertEquals(1, metrics().optInt("real_ai_requests"))
        assertEquals(before.optInt("POST /quizzes") + 1, metrics().optInt("POST /quizzes"))
        assertNull(study.accountState.value.records.single { it.quiz.title == "p10-$runId-C-local" }.remote)
    }

    @Test fun zReturnToAnonymousAndRestoreDefaultServer() {
        if (auth.state.value.session is SessionState.Authenticated) {
            assertTrue(user().username.startsWith("p10_")); logoutThroughUi()
        }
        assertSame(SessionState.Anonymous, auth.state.value.session)
        ui.onNodeWithContentDescription("Ajustes").performClick()
        field(0, "https://10.0.2.2:8443"); click("Aplicar servidor")
        ui.waitUntil(30_000) { auth.state.value.server == "https://10.0.2.2:8443" && !auth.state.value.busy }
        back(); assertTrue(study.state.value.quizzes.isEmpty())
    }
}
