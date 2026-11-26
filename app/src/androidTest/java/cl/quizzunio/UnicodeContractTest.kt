package cl.quizzunio

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.auth.AuthApi
import cl.quizzunio.auth.SessionRepository
import cl.quizzunio.auth.SessionState
import cl.quizzunio.auth.TokenPair
import cl.quizzunio.auth.TokenStore
import cl.quizzunio.auth.UserTier
import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.network.ApiClient
import cl.quizzunio.network.ApiConfiguration
import cl.quizzunio.network.ApiException
import cl.quizzunio.network.HttpResponse
import cl.quizzunio.network.HttpTransport
import cl.quizzunio.study.AccountQuizStore
import cl.quizzunio.study.AccountStudyCoordinator
import cl.quizzunio.study.EditedQuestion
import cl.quizzunio.study.QuizCodec
import cl.quizzunio.study.QuizOrigin
import cl.quizzunio.study.QuizRecord
import cl.quizzunio.study.QuizSyncState
import cl.quizzunio.study.RemoteQuestion
import cl.quizzunio.study.RemoteQuiz
import cl.quizzunio.study.RemoteQuizRepository
import cl.quizzunio.study.StudyQuiz
import cl.quizzunio.study.StudyQuizRepository
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UnicodeContractTest {
    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String?) {
        fun json() = JSONObject(requireNotNull(body))
    }

    private class ScriptedTransport : HttpTransport {
        val calls = mutableListOf<Request>()
        private val responses = ArrayDeque<(Request) -> HttpResponse>()

        fun expect(method: String, path: String, answer: (Request) -> HttpResponse) {
            responses.addLast { request ->
                assertEquals(method, request.method)
                assertEquals(path, request.path)
                answer(request)
            }
        }

        override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
            val request = Request(method, url.removePrefix(BASE_URL), headers, body)
            calls += request
            return responses.removeFirstOrNull()?.invoke(request) ?: error("Petición inesperada: $method ${request.path}")
        }

        fun assertExhausted() = assertTrue("Faltan peticiones esperadas", responses.isEmpty())
    }

    private class MemoryTokens : TokenStore {
        private var pair: TokenPair? = null
        override fun read(server: String) = pair
        override fun write(server: String, tokens: TokenPair) { pair = tokens }
        override fun clear() { pair = null }
    }

    private class Fixture {
        val transport = ScriptedTransport()
        private val client = ApiClient(ApiConfiguration(BASE_URL), transport)
        val session = SessionRepository(AuthApi(client), MemoryTokens(), BASE_URL)
        val remote = RemoteQuizRepository(client, session)

        fun login(username: String = "José", password: String = PASSWORD, tier: UserTier = UserTier.Premium,
                  identifier: String = username) = runBlocking {
            transport.expect("POST", "/auth/login") { request ->
                assertEquals(identifier, request.json().getString("identifier"))
                assertEquals(password, request.json().getString("password"))
                tokenResponse(200)
            }
            expectIdentity(username, tier)
            session.login(identifier, password)
        }

        fun expectIdentity(username: String, tier: UserTier = UserTier.Premium) {
            transport.expect("GET", "/auth/me") { request ->
                assertBearer(request)
                jsonResponse(200, JSONObject().put("userId", OWNER).put("username", username)
                    .put("email", EMAIL.lowercase()).put("tier", tier.name))
            }
        }

        fun expectLimit(bytes: Long, bearer: String = ACCESS) {
            transport.expect("GET", "/quizzes/limits") { request ->
                assertBearer(request, bearer)
                assertNull(request.body)
                jsonResponse(200, JSONObject().put("maxTextBytes", bytes).put("encoding", "UTF-8"))
            }
        }

        fun expectGeneration(source: String, title: String = TITLE, bearer: String = ACCESS) {
            transport.expect("POST", "/quizzes") { request ->
                assertBearer(request, bearer)
                assertEquals(setOf("title", "text", "questionCount"), request.json().keys().asSequence().toSet())
                assertEquals(source, request.json().getString("text"))
                assertEquals(title, request.json().getString("title"))
                assertEquals(1, request.json().getInt("questionCount"))
                documentResponse(201, document(title))
            }
        }
    }

    @Test fun registrationLoginAndAccountOperationsPreserveTheIdentityContract() = runBlocking {
        val fixture = Fixture()
        val users = listOf("José" to "Jose\u0301", "María-Luisa" to "María-Luisa", "用户123" to "用户123")
        users.forEach { (canonical, entered) ->
            fixture.transport.expect("POST", "/auth/register") { request ->
                assertEquals(canonical, request.json().getString("username"))
                assertEquals(EMAIL, request.json().getString("email"))
                assertEquals(PASSWORD, request.json().getString("password"))
                tokenResponse(201)
            }
            fixture.expectIdentity(canonical, UserTier.Free)
            fixture.session.register(entered, EMAIL, PASSWORD)
            assertEquals(canonical, (fixture.session.state.value as SessionState.Authenticated).user.username)
            fixture.transport.expect("POST", "/auth/logout") { request ->
                assertBearer(request)
                HttpResponse(204, "")
            }
            fixture.session.logout()
            assertEquals(SessionState.Anonymous, fixture.session.state.value)
        }

        for ((entered, onWire, display) in listOf(
            Triple("Jose\u0301", "José", "José"), Triple("Usuario", "Usuario", "Usuario"),
            Triple("usuario", "usuario", "usuario"), Triple(EMAIL, EMAIL, "José")
        )) {
            fixture.transport.expect("POST", "/auth/login") { request ->
                assertEquals(onWire, request.json().getString("identifier"))
                assertEquals("  e\u0301😀 ", request.json().getString("password"))
                tokenResponse(200)
            }
            fixture.expectIdentity(display)
            fixture.session.login(entered, "  e\u0301😀 ")
            assertEquals(display, (fixture.session.state.value as SessionState.Authenticated).user.username)
            fixture.transport.expect("POST", "/auth/logout") { HttpResponse(204, "") }
            fixture.session.logout()
        }

        fixture.login()
        val replacement = "  Nueva e\u0301 😀 contraseña  "
        fixture.transport.expect("POST", "/auth/change-password") { request ->
            assertBearer(request)
            assertEquals(PASSWORD, request.json().getString("currentPassword"))
            assertEquals(replacement, request.json().getString("newPassword"))
            HttpResponse(204, "")
        }
        fixture.session.changePassword(PASSWORD, replacement)
        assertEquals(SessionState.Anonymous, fixture.session.state.value)
        fixture.login(password = replacement)
        fixture.transport.expect("DELETE", "/auth/account") { request ->
            assertBearer(request)
            assertEquals(replacement, request.json().getString("currentPassword"))
            HttpResponse(204, "")
        }
        fixture.session.deleteAccount(replacement)
        assertEquals(SessionState.Anonymous, fixture.session.state.value)

        val beforeInvalid = fixture.transport.calls.size
        expectFailure<IllegalArgumentException> { fixture.session.register("usuario😀", EMAIL, PASSWORD) }
        expectFailure<IllegalArgumentException> { fixture.session.register("José", "á@example.com", PASSWORD) }
        assertEquals(beforeInvalid, fixture.transport.calls.size)
        fixture.transport.assertExhausted()
    }

    @Test fun generationKeySurvivesReopeningAndRecoversWithoutAnotherPost() = runBlocking {
        val fixture = Fixture()
        fixture.login()
        val context = isolatedContext()
        val remote = fixture.remote
        val original = AccountStudyCoordinator(OWNER, remote, AccountQuizStore(context, BASE_URL, OWNER))
        original.load()
        fixture.expectLimit(8_000_000)
        var sentKey = ""
        fixture.transport.expect("POST", "/quizzes") { request ->
            sentKey = requireNotNull(request.headers["Idempotency-Key"])
            assertEquals(SOURCE, request.json().getString("text"))
            throw java.net.SocketTimeoutException("Synthetic response loss")
        }
        val lost = expectFailure<ApiException> { original.generate(TITLE, SOURCE, 1) }
        assertEquals("NETWORK_ERROR", lost.code)
        assertTrue(original.state.value.generationUncertain)

        val reopened = AccountStudyCoordinator(OWNER, remote, AccountQuizStore(context, BASE_URL, OWNER))
        reopened.load()
        assertTrue(reopened.state.value.generationUncertain)
        val expected = document()
        fixture.transport.expect("GET", "/quizzes/generations/$sentKey") { request ->
            assertBearer(request)
            jsonResponse(200, JSONObject().put("state", "COMPLETED").put("quiz", QuizCodec.encode(expected)))
        }
        val (status, recovered) = reopened.recoverGeneration()
        assertEquals("COMPLETED", status)
        assertEquals(expected.quizId, recovered?.remote?.quizId)
        assertFalse(reopened.state.value.generationUncertain)
        val check = AccountStudyCoordinator(OWNER, remote, AccountQuizStore(context, BASE_URL, OWNER))
        check.load()
        assertEquals(listOf(expected.quizId), check.state.value.records.map { it.remote?.quizId })
        assertFalse(check.state.value.generationUncertain)
        fixture.transport.assertExhausted()
    }

    @Test fun safeAuthenticationValidationMessagesIgnoreServerTextAndUnknownFields() = runBlocking {
        val fixture = Fixture()
        fun response(field: String?) = JSONObject().put("code", "INVALID_REQUEST")
            .put("message", "server-specific-data").put("reason", "INVALID_LENGTH")
            .apply { if (field != null) put("field", field) }

        for (field in listOf("username", "unexpected", null)) {
            fixture.transport.expect("POST", "/auth/register") { jsonResponse(400, response(field)) }
            val failure = expectFailure<ApiException> {
                fixture.session.register("Account", EMAIL, PASSWORD)
            }
            assertEquals(400, failure.status)
            assertFalse(failure.message.orEmpty().contains("server-specific-data"))
            assertEquals(if (field == "username") "El nombre de usuario debe tener de 3 a 32 caracteres."
                else "El servidor rechazó los datos de la solicitud.", failure.message)
        }
        fixture.transport.assertExhausted()
    }

    @Test fun generationUsesEachAdvertisedLimitAndSendsOnlyLiteralText() = runBlocking {
        val fixture = Fixture()
        fixture.login()
        val coordinator = AccountStudyCoordinator(OWNER, fixture.remote, AccountQuizStore(isolatedContext(), BASE_URL, OWNER))
        coordinator.load()
        val exact = "ñ😀\t"
        fixture.expectLimit(6)
        val excess = expectFailure<IllegalArgumentException> { coordinator.generate(TITLE, exact, 1) }
        assertTrue(excess.message.orEmpty().contains("7 bytes UTF-8") && excess.message.orEmpty().contains("admite 6"))
        assertFalse(coordinator.state.value.generationUncertain)

        fixture.expectLimit(7)
        fixture.expectGeneration(exact)
        assertEquals(TITLE, coordinator.generate(TITLE, exact, 1).quiz.title)

        val source = "a".repeat(1_050_000) + SOURCE
        fixture.expectLimit(1_100_000)
        fixture.expectGeneration(source)
        assertEquals(SOURCE, coordinator.generate(TITLE, source, 1).quiz.questions.single().question)
        assertFalse(coordinator.state.value.generationUncertain)
        assertEquals(3, fixture.transport.calls.count { it.path == "/quizzes/limits" })
        assertEquals(2, fixture.transport.calls.count { it.path == "/quizzes" })
        fixture.transport.assertExhausted()
    }

    @Test fun failuresBeforeUploadPermitRetryAndBoth413ResponsesAreDistinct() = runBlocking {
        val fixture = Fixture()
        fixture.login()
        val coordinator = AccountStudyCoordinator(OWNER, fixture.remote, AccountQuizStore(isolatedContext(), BASE_URL, OWNER))
        coordinator.load()

        for (body in listOf(
            """{"maxTextBytes":0,"encoding":"UTF-8"}""",
            """{"maxTextBytes":8388609,"encoding":"UTF-8"}""",
            """{"maxTextBytes":"100","encoding":"UTF-8"}""",
            """{"maxTextBytes":100.5,"encoding":"UTF-8"}""",
            """{"maxTextBytes":100,"encoding":"UTF-16"}""",
            """{"encoding":"UTF-8"}""",
            """{"maxTextBytes":100,"maxTextBytes":101,"encoding":"UTF-8"}""",
            """{"maxTextBytes":100,"encoding":"UTF-8"} error""",
            """{maxTextBytes:100,encoding:'UTF-8'}"""
        )) {
            fixture.transport.expect("GET", "/quizzes/limits") { HttpResponse(200, body) }
            val failure = expectFailure<ApiException> { coordinator.generate(TITLE, SOURCE, 1) }
            assertEquals("INVALID_RESPONSE", failure.code)
            assertTrue(failure.message.orEmpty().contains("límite de texto inválido"))
            assertFalse(coordinator.state.value.generationUncertain)
        }

        fixture.transport.expect("GET", "/quizzes/limits") { throw IOException("Sin conexión simulada") }
        assertEquals("NETWORK_ERROR", expectFailure<ApiException> { coordinator.generate(TITLE, SOURCE, 1) }.code)
        fixture.transport.expect("GET", "/quizzes/limits") { throw SocketTimeoutException() }
        assertEquals("TIMEOUT", expectFailure<ApiException> { coordinator.generate(TITLE, SOURCE, 1) }.code)
        fixture.transport.expect("GET", "/quizzes/limits") { errorResponse(503, "SERVICE_UNAVAILABLE") }
        assertEquals(503, expectFailure<ApiException> { coordinator.generate(TITLE, SOURCE, 1) }.status)
        assertFalse(coordinator.state.value.generationUncertain)
        assertTrue(fixture.transport.calls.none { it.path == "/quizzes" })

        for ((code, message) in listOf("SOURCE_TEXT_TOO_LARGE" to "El texto", "REQUEST_TOO_LARGE" to "El cuerpo")) {
            fixture.expectLimit(1_000)
            fixture.transport.expect("POST", "/quizzes") { errorResponse(413, code) }
            val failure = expectFailure<ApiException> { coordinator.generate(TITLE, SOURCE, 1) }
            assertEquals(413, failure.status)
            assertEquals(code, failure.code)
            assertTrue(failure.message.orEmpty().startsWith(message))
            assertFalse(coordinator.state.value.generationUncertain)
        }

        fixture.expectLimit(1_000)
        fixture.expectGeneration(SOURCE)
        assertEquals(TITLE, coordinator.generate(TITLE, SOURCE, 1).quiz.title)
        fixture.transport.assertExhausted()
    }

    @Test fun preflightUsesSessionRefreshAndFreeNeverSubmitsGeneration() = runBlocking {
        val fixture = Fixture()
        fixture.login()
        fixture.transport.expect("GET", "/quizzes/limits") { request ->
            assertBearer(request)
            errorResponse(401, "INVALID_TOKEN")
        }
        fixture.transport.expect("POST", "/auth/refresh") { request ->
            assertEquals(REFRESH, request.json().getString("refreshToken"))
            tokenResponse(200, ROTATED, "D".repeat(42) + "A")
        }
        fixture.expectLimit(1_000, ROTATED)
        fixture.expectGeneration(SOURCE, bearer = ROTATED)
        assertEquals(TITLE, fixture.remote.generate(TITLE, SOURCE, 1).title)

        val coordinator = AccountStudyCoordinator(OWNER, fixture.remote, AccountQuizStore(isolatedContext(), BASE_URL, OWNER))
        coordinator.load()
        fixture.transport.expect("GET", "/quizzes/limits") { request ->
            assertBearer(request, ROTATED)
            errorResponse(401, "INVALID_TOKEN")
        }
        fixture.transport.expect("POST", "/auth/refresh") { errorResponse(401, "INVALID_TOKEN") }
        assertEquals(401, expectFailure<ApiException> { coordinator.generate(TITLE, SOURCE, 1) }.status)
        assertEquals(SessionState.Anonymous, fixture.session.state.value)
        assertFalse(coordinator.state.value.generationUncertain)
        fixture.transport.assertExhausted()

        val free = Fixture()
        free.login(tier = UserTier.Free)
        expectFailure<IllegalStateException> { free.remote.generate(TITLE, SOURCE, 1) }
        assertTrue(free.transport.calls.none { it.path.startsWith("/quizzes") })
        free.transport.assertExhausted()
    }

    @Test fun importedAndEditedUnicodeSurvivesPersistenceAndVersionedAnswerSync() = runBlocking {
        val fixture = Fixture()
        fixture.login(tier = UserTier.Free)
        val context = isolatedContext()
        val store = AccountQuizStore(context, BASE_URL, OWNER)
        val coordinator = AccountStudyCoordinator(OWNER, fixture.remote, store)
        coordinator.load()
        val imported = document()
        val local = imported.toStudyQuiz("unicode-generated-quiz")
        coordinator.addLocal(local)
        coordinator.capture(local, listOf(2), 0)

        var firstBody: String? = null
        fixture.transport.expect("POST", "/quizzes/import") { request ->
            assertBearer(request)
            assertEquals(local.id, request.headers["Idempotency-Key"])
            assertContent(request.json(), TITLE, SOURCE, OPTIONS)
            firstBody = request.body
            throw IOException("Respuesta de importación perdida")
        }
        assertEquals("NETWORK_ERROR", expectFailure<ApiException> { coordinator.upload(local.id) }.code)
        assertEquals(local.id, store.load().single().importKey)
        assertNull(store.load().single().remote)

        fixture.transport.expect("POST", "/quizzes/import") { request ->
            assertBearer(request)
            assertEquals(local.id, request.headers["Idempotency-Key"])
            assertEquals(firstBody, request.body)
            assertContent(request.json(), TITLE, SOURCE, OPTIONS)
            documentResponse(201, imported)
        }
        assertEquals(QuizSyncState.Pending, coordinator.upload(local.id).sync)

        fixture.transport.expect("PUT", "/quizzes/71/answers") { request ->
            assertBearer(request)
            assertEquals(1L, request.json().getLong("version"))
            val answer = request.json().getJSONArray("answers").getJSONObject(0)
            assertEquals(91L, answer.getLong("questionId"))
            assertEquals(2, answer.getInt("current_answer"))
            documentResponse(200, imported.copy(questions = listOf(imported.questions.single().copy(currentAnswer = 2))))
        }
        assertEquals(QuizSyncState.Synced, coordinator.synchronize(local.id).sync)

        val editedTitle = "$TITLE n\u0303"
        val edited = document(editedTitle, "$SOURCE\t𝄞", 2, 2)
        fixture.transport.expect("PUT", "/quizzes/71") { request ->
            assertBearer(request)
            assertContent(request.json(), editedTitle, "$SOURCE\t𝄞", OPTIONS)
            assertEquals(1L, request.json().getLong("version"))
            assertEquals(91L, request.json().getJSONArray("questions").getJSONObject(0).getLong("questionId"))
            documentResponse(200, edited)
        }
        coordinator.edit(local.id, editedTitle, listOf(EditedQuestion(91, "$SOURCE\t𝄞", OPTIONS, 1)))

        val recovered = AccountStudyCoordinator(OWNER, fixture.remote, AccountQuizStore(context, BASE_URL, OWNER))
        recovered.load()
        val record = requireNotNull(recovered.record(local.id))
        assertEquals(edited, record.remote)
        assertEquals(edited.toStudyQuiz(local.id), record.quiz)
        assertEquals(listOf(2), record.answers)
        assertEquals(local.id, record.importKey)

        fixture.transport.expect("GET", "/quizzes/71") { documentResponse(200, edited) }
        assertEquals(edited, recovered.latest(local.id))
        fixture.transport.expect("PUT", "/quizzes/71") { request ->
            assertEquals(2L, request.json().getLong("version"))
            assertContent(request.json(), "Borrador 😀", SOURCE, OPTIONS)
            errorResponse(409, "QUIZ_VERSION_CONFLICT")
        }
        val conflict = expectFailure<ApiException> {
            recovered.edit(local.id, "Borrador 😀", listOf(EditedQuestion(91, SOURCE, OPTIONS, 1)))
        }
        assertEquals("QUIZ_VERSION_CONFLICT", conflict.code)
        assertEquals(QuizSyncState.Conflict, store.load().single().sync)
        assertEquals(edited, store.load().single().remote)

        val newer = edited.copy(version = 3, title = "Cambio remoto Ñ")
        fixture.transport.expect("GET", "/quizzes/71") { documentResponse(200, newer) }
        recovered.acceptServerVersion(local.id)
        assertEquals(newer, store.load().single().remote)
        assertEquals(listOf(2), store.load().single().answers)
        fixture.transport.assertExhausted()
    }

    @Test fun validQuizFieldsCanExceedThePreviousWholeRequestCap() = runBlocking {
        val fixture = Fixture()
        fixture.login(tier = UserTier.Free)
        val exact = "😀".repeat(16_383) + "漢"
        val title = "😀".repeat(255)
        val questions = List(4) { index ->
            RemoteQuestion(91L + index, 71, exact, List(4) { exact }, 1, -1)
        }
        val imported = document(title).copy(questions = questions)
        val record = QuizRecord(imported.toStudyQuiz("import-large-unicode"), OWNER, QuizOrigin.Local)
        fixture.transport.expect("POST", "/quizzes/import") { request ->
            assertTrue(requireNotNull(request.body).toByteArray(Charsets.UTF_8).size > 1_000_000)
            assertEquals(exact, request.json().getJSONArray("questions").getJSONObject(3).getJSONArray("options").getString(3))
            documentResponse(201, imported)
        }
        assertEquals(imported, fixture.remote.importLocal(record))

        val edited = imported.copy(version = 2)
        val draft = questions.map { EditedQuestion(it.questionId, it.question, it.options, it.correctAnswerIndex) }
        fixture.transport.expect("PUT", "/quizzes/71") { request ->
            assertTrue(requireNotNull(request.body).toByteArray(Charsets.UTF_8).size > 1_000_000)
            assertEquals(1L, request.json().getLong("version"))
            documentResponse(200, edited)
        }
        assertEquals(edited, fixture.remote.edit(imported, title, draft))
        assertEquals(edited, AccountQuizStore(isolatedContext(), BASE_URL, OWNER).let { store ->
            store.save(listOf(record.copy(remote = edited)))
            store.load().single().remote
        })
        fixture.transport.assertExhausted()
    }

    @Test fun historicalBlankAndLongLocalQuizzesRemainReadable() = runBlocking {
        val fixture = Fixture()
        fixture.login()
        val context = isolatedContext()
        val store = AccountQuizStore(context, BASE_URL, OWNER)
        val coordinator = AccountStudyCoordinator(OWNER, fixture.remote, store)
        coordinator.load()
        val historical = document("😀".repeat(255), " \n", 1).let {
            it.copy(questions = listOf(it.questions.single().copy(options = listOf("", " ", "ñ", "😀"))))
        }
        fixture.transport.expect("GET", "/quizzes?page=1&pageSize=20") {
            jsonResponse(200, JSONObject().put("page", 1).put("pageSize", 20)
                .put("quizzes", JSONArray().put(QuizCodec.encode(historical))))
        }
        coordinator.refresh()
        val id = historical.toStudyQuiz().id
        assertEquals(historical, store.load().single().remote)
        assertEquals(" \n", coordinator.record(id)?.quiz?.questions?.single()?.question)

        coordinator.capture(historical.toStudyQuiz(), listOf(2), 0)
        fixture.transport.expect("PUT", "/quizzes/71/answers") {
            documentResponse(200, historical.copy(questions = listOf(historical.questions.single().copy(currentAnswer = 2))))
        }
        assertEquals(listOf(2), coordinator.synchronize(id).answers)

        val invalid = listOf(EditedQuestion(91, " \t", OPTIONS, 0))
        val blankOption = listOf(EditedQuestion(91, SOURCE, listOf("A", "B", "\u00A0", "D"), 0))
        val beforeEdit = fixture.transport.calls.size
        expectFailure<IllegalArgumentException> { coordinator.edit(id, TITLE, invalid) }
        expectFailure<IllegalArgumentException> { coordinator.edit(id, TITLE, blankOption) }
        assertEquals(beforeEdit, fixture.transport.calls.size)
        assertEquals(" \n", store.load().single().remote?.questions?.single()?.question)
        fixture.transport.assertExhausted()

        val legacy = StudyQuiz("old-count-16", TITLE, "", List(16) { QuizQuestion(SOURCE, OPTIONS, 1) })
        val localStore = StudyQuizRepository(context)
        localStore.useAccount(BASE_URL, OWNER)
        localStore.save(legacy)
        assertEquals(legacy, localStore.load().quizzes.single())
        store.save(listOf(QuizRecord(legacy, OWNER, QuizOrigin.Local)))
        assertEquals(legacy, store.load().single().quiz)
    }

    private suspend inline fun <reified T : Exception> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (error: Exception) {
            if (error is T) return error
            throw error
        }
        error("Se esperaba ${T::class.java.simpleName}")
    }

    private fun isolatedContext(): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "unicode-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(context) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = root
            override fun getNoBackupFilesDir() = root
        }
    }

    private companion object {
        const val OWNER = 17L
        const val BASE_URL = "https://example.com"
        const val EMAIL = "Name+Tag@EXAMPLE.COM"
        const val TITLE = "  Álgebra e\u0301 漢 😀  "
        const val SOURCE = "  Cálculo: ñ y n\u0303, ∑, 漢字, 😀, 𐐀\r\n\tTexto\n\n  "
        const val PASSWORD = "  Contrasen\u0303a 😀漢  "
        val OPTIONS = listOf(" e\u0301", "漢字\t𝄞", "😀", "\nñ\n")
        val ACCESS = "A".repeat(43)
        val REFRESH = "B".repeat(42) + "A"
        val ROTATED = "C".repeat(42) + "A"

        fun assertBearer(request: Request, token: String = ACCESS) =
            assertEquals("Bearer $token", request.headers["Authorization"])

        fun jsonResponse(status: Int, value: JSONObject) = HttpResponse(status, value.toString())
        fun documentResponse(status: Int, value: RemoteQuiz) = jsonResponse(status, QuizCodec.encode(value))
        fun errorResponse(status: Int, code: String) = jsonResponse(status, JSONObject().put("code", code))

        fun tokenResponse(status: Int, access: String = ACCESS, refresh: String = REFRESH) = jsonResponse(status,
            JSONObject().put("tokenType", "Bearer").put("accessToken", access).put("refreshToken", refresh)
                .put("accessExpiresAt", "2027-01-01T00:00:00Z").put("refreshExpiresAt", "2027-01-02T00:00:00Z")
                .put("absoluteExpiresAt", "2027-02-01T00:00:00Z"))

        fun document(title: String = TITLE, prompt: String = SOURCE, version: Long = 1, current: Int = -1) =
            RemoteQuiz(71, OWNER, title, "2026-11-19 00:00:00.000", version,
                listOf(RemoteQuestion(91, 71, prompt, OPTIONS, 1, current)))

        fun assertContent(value: JSONObject, title: String, prompt: String, options: List<String>) {
            assertEquals(title, value.getString("title"))
            val question = value.getJSONArray("questions").getJSONObject(0)
            assertEquals(prompt, question.getString("question"))
            val actual = question.getJSONArray("options")
            assertEquals(options, (0 until actual.length()).map(actual::getString))
            assertEquals(1, question.getInt("correctAnswerIndex"))
        }
    }
}
