package cl.quizzunio

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.auth.*
import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.network.*
import cl.quizzunio.study.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** HTTP/JDBC reales mediante fixture aislado. Ninguna prueba invoca generación IA. */
class AnonymousImportIntegrationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val run get() = requireNotNull(args.getString("anonymousRun"))
    private val baseUrl get() = requireNotNull(args.getString("anonymousBaseUrl")).also { require(it.startsWith("http://127.0.0.1:")) }
    private val controlKey get() = requireNotNull(args.getString("anonymousControl"))
    private val password = "Anonymous-Test-123!"
    private class Tokens : TokenStore {
        private var pair: TokenPair? = null
        override fun read(server: String) = pair
        override fun write(server: String, tokens: TokenPair) { pair = tokens }
        override fun clear() { pair = null }
    }
    private fun context(tag: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.noBackupFilesDir, "anonymous-e2e-$run-$tag").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
    }
    private suspend fun session(tag: String, create: Boolean = true): SessionRepository {
        val session = SessionRepository(AuthApi(ApiClient(ApiConfiguration(baseUrl), UrlConnectionTransport())), Tokens(), baseUrl)
        if (create) session.register("anon_${run}_$tag", "anon_${run}_$tag@example.com", password)
        else session.login("anon_${run}_$tag", password)
        return session
    }
    private fun owner(session: SessionRepository) = (session.state.value as SessionState.Authenticated).user.userId
    private fun remote(session: SessionRepository) = RemoteQuizRepository(ApiClient(ApiConfiguration(baseUrl), UrlConnectionTransport()), session)
    private suspend fun control(path: String): JSONObject {
        val response = UrlConnectionTransport().send(if (path.endsWith("metrics")) "GET" else "POST", baseUrl + "/control/" + path,
            mapOf("X-Anonymous-Control" to controlKey), null)
        assertTrue("Control de prueba HTTP ${response.status}", response.status in 200..299)
        return if (response.body.isEmpty()) JSONObject() else JSONObject(response.body)
    }
    private fun source(id: String, answers: List<Int?>, completed: Boolean = false) = AnonymousQuizRecord(
        StudyQuiz("source-$id", "Anónimo Ñ 😀 $run $id", "Unidad n\u0303\t漢", List(2) {
            QuizQuestion("¿Célula $it?\n𝄞", listOf("Á", "B", "漢", "😀"), 0)
        }, completed), answers, 1)
    private fun seed(context: Context, record: AnonymousQuizRecord) = AnonymousQuizStore(context).update { records ->
        check(records.none { it.quiz.id == record.quiz.id }) { "Usar un run nuevo para no sobrescribir fixtures existentes" }
        records + record
    }
    private fun originalBytes(context: Context) = File(context.noBackupFilesDir, "anonymous/quizzes.json").readBytes()

    @Test fun explicitImportPreservesEmptyPartialAndCompletedAttemptsAndAccountIsolation() = runBlocking {
        val context = context("normal"); val session = session("normal"); val remote = remote(session)
        val store = AccountQuizStore(context, baseUrl, owner(session)); val coordinator = AccountStudyCoordinator(owner(session), remote, store)
        coordinator.load()
        val before = control("metrics").optInt("POST /quizzes/import")
        listOf(source("empty", listOf(null, null)), source("partial", listOf(3, null)), source("completed", listOf(0, 3), true)).forEach { source ->
            seed(context, source); val original = originalBytes(context)
            val result = coordinator.importAnonymous(source)
            assertEquals(source.answers, result.answers); assertEquals(1, result.currentIndex)
            assertEquals(source.quiz.completed, result.quiz.completed)
            assertEquals(source.answers.map { it ?: -1 }, remote.get(result.remote!!.quizId).questions.map { it.currentAnswer })
            assertEquals(QuizSyncState.Synced, result.sync)
            coordinator.importAnonymous(source)
            assertArrayEquals(original, originalBytes(context))
        }
        assertEquals(before + 3, control("metrics").optInt("POST /quizzes/import"))
        assertEquals(3, remote.list(1).quizzes.size); assertEquals(3, store.load().size)
        val other = session("other"); val otherRemote = remote(other)
        assertTrue(AccountQuizStore(context, baseUrl, owner(other)).load().isEmpty())
        assertTrue(AccountQuizStore(context, "https://other.example", owner(session)).load().isEmpty())
        try { otherRemote.get(store.load().first().remote!!.quizId); fail("Cuenta ajena debe ser rechazada") }
        catch (expected: ApiException) { assertEquals(404, expected.status) }
        val historical = source("historic", List(16) { null }).let { historical -> historical.copy(quiz = historical.quiz.copy(questions = List(16) { historical.quiz.questions.first() })) }
        val blank = source("blank", listOf(null, null)).let { it.copy(quiz = it.quiz.copy(questions = it.quiz.questions.map { q -> q.copy(question = " ") })) }
        val countBefore = control("metrics").optInt("POST /quizzes/import")
        for (invalid in listOf(historical, blank)) {
            seed(context, invalid)
            try { coordinator.importAnonymous(invalid); fail("No se debe importar un histórico incompatible") }
            catch (expected: IllegalArgumentException) { assertTrue(AnonymousQuizStore(context).load().contains(invalid)) }
        }
        assertEquals(countBefore, control("metrics").optInt("POST /quizzes/import"))
        assertEquals(0, control("metrics").optInt("POST /quizzes"))
    }

    @Test fun aPrepareInterruptedImports() = runBlocking {
        val context = context("restart"); val session = session("restart")
        val store = AccountQuizStore(context, baseUrl, owner(session))
        val coordinator = AccountStudyCoordinator(owner(session), remote(session), store); coordinator.load()
        val lost = source("lost", listOf(3, null)); val pending = source("pending", listOf(0, 3), true)
        seed(context, lost); seed(context, pending)
        val original = originalBytes(context)
        control("drop-import")
        try { coordinator.importAnonymous(lost); fail("Debe informar respuesta perdida tras commit") }
        catch (expected: ApiException) { assertEquals("NETWORK_ERROR", expected.code) }
        assertNull(store.load().single().remote)
        control("fail-answers")
        try { coordinator.importAnonymous(pending); fail("Debe informar respuestas pendientes") }
        catch (expected: ApiException) { assertEquals(503, expected.status) }
        val records = store.load()
        val assigned = records.single { it.quiz.id == AnonymousImportIdentity.localId(pending.quiz.id) }
        assertNotNull(assigned.remote); assertEquals(QuizSyncState.Pending, assigned.sync)
        assertEquals(pending.answers, assigned.answers); assertEquals(1, assigned.currentIndex)
        assertEquals(2, remote(session).list(1).quizzes.size)
        session.abandon()
        val other = session("pendingb")
        assertTrue(AccountQuizStore(context, baseUrl, owner(other)).load().isEmpty())
        assertTrue(remote(other).list(1).quizzes.isEmpty())
        try { coordinator.retryAnonymousImport(assigned.quiz.id); fail("Una sesión abandonada no debe seguir enviando") }
        catch (expected: IllegalStateException) { assertEquals(QuizSyncState.Pending, store.load().single { it.quiz.id == assigned.quiz.id }.sync) }
        assertArrayEquals(original, originalBytes(context))
    }

    @Test fun connectionFailureBeforeCommitKeepsTheCopyAndRetriesTheSameKey() = runBlocking {
        val context = context("offline"); val session = session("offline"); val remote = remote(session)
        val coordinator = AccountStudyCoordinator(owner(session), remote, AccountQuizStore(context, baseUrl, owner(session)))
        coordinator.load(); val source = source("offline", listOf(2, null)); seed(context, source)
        val original = originalBytes(context)
        control("fail-import")
        try { coordinator.importAnonymous(source); fail("Debe informar pérdida de conexión") }
        catch (expected: ApiException) { assertEquals("NETWORK_ERROR", expected.code) }
        val pending = AccountQuizStore(context, baseUrl, owner(session)).load().single()
        assertNull(pending.remote); assertEquals(source.answers, pending.answers)
        assertTrue(remote.list(1).quizzes.isEmpty())
        val confirmed = coordinator.retryAnonymousImport(pending.quiz.id)
        assertEquals(pending.importKey, confirmed.importKey); assertEquals(QuizSyncState.Synced, confirmed.sync)
        assertEquals(1, remote.list(1).quizzes.size)
        assertArrayEquals(original, originalBytes(context))
    }

    @Test fun realVersionConflictPreservesTheLocalAttemptAndOriginal() = runBlocking {
        val context = context("conflict"); val session = session("conflict"); val remote = remote(session)
        val store = AccountQuizStore(context, baseUrl, owner(session)); val coordinator = AccountStudyCoordinator(owner(session), remote, store)
        coordinator.load(); val source = source("conflict", listOf(3, null)); seed(context, source)
        val original = originalBytes(context)
        control("fail-answers")
        try { coordinator.importAnonymous(source); fail("Debe quedar pendiente") }
        catch (expected: ApiException) { assertEquals(503, expected.status) }
        val pending = store.load().single(); val document = pending.remote!!
        val edited = remote.edit(document, document.title + " remoto", document.questions.map {
            EditedQuestion(it.questionId, it.question, it.options, it.correctAnswerIndex)
        })
        assertEquals(document.version + 1, edited.version)
        try { coordinator.retryAnonymousImport(pending.quiz.id); fail("La versión obsoleta debe generar conflicto real") }
        catch (expected: ApiException) { assertEquals(409, expected.status); assertEquals("QUIZ_VERSION_CONFLICT", expected.code) }
        val conflicted = store.load().single()
        assertEquals(QuizSyncState.Conflict, conflicted.sync)
        assertEquals(source.answers, conflicted.answers); assertEquals(source.currentIndex, conflicted.currentIndex)
        assertEquals(edited, remote.get(document.quizId))
        assertArrayEquals(original, originalBytes(context))
        remote.delete(edited)
        coordinator.delete(pending.quiz.id)
        assertTrue(store.load().isEmpty())
        val reimported = coordinator.importAnonymous(source)
        assertNotEquals(pending.importKey, reimported.importKey)
        assertNotEquals(document.quizId, reimported.remote!!.quizId)
        assertEquals(source.answers, reimported.answers)
        assertArrayEquals(original, originalBytes(context))
    }

    @Test fun bRecoverInterruptedImportsAfterRealProcessDeath() = runBlocking {
        val context = context("restart"); val session = session("restart", create = false); val remote = remote(session)
        val store = AccountQuizStore(context, baseUrl, owner(session)); val coordinator = AccountStudyCoordinator(owner(session), remote, store)
        coordinator.load(); assertEquals(2, store.load().size)
        // Al restaurar una sesión, el listado puede descubrir el remoto cuya respuesta se perdió.
        coordinator.refresh()
        val before = control("metrics").optInt("POST /quizzes/import")
        val original = originalBytes(context)
        val previous = store.load().filter { AnonymousImportIdentity.isImported(it.quiz.id) }.associateBy { it.quiz.id }
        assertEquals(2, previous.size)
        for (record in previous.values) {
            val confirmed = coordinator.retryAnonymousImport(record.quiz.id)
            assertEquals(record.importKey, confirmed.importKey)
            assertEquals(record.answers, confirmed.answers); assertEquals(record.currentIndex, confirmed.currentIndex)
            assertEquals(record.quiz.completed, confirmed.quiz.completed)
            assertEquals(QuizSyncState.Synced, confirmed.sync)
            assertEquals(record.answers.map { it ?: -1 }, remote.get(confirmed.remote!!.quizId).questions.map { it.currentAnswer })
        }
        assertEquals(before + 1, control("metrics").optInt("POST /quizzes/import"))
        assertEquals(2, remote.list(1).quizzes.size)
        assertEquals(2, store.load().size)
        assertArrayEquals(original, originalBytes(context))
        assertEquals(0, control("metrics").optInt("POST /quizzes"))
    }

    @Test fun uiConfirmationUploadsImmediatelyAndLoginLogoutRevocationPreserveOriginals() {
        lateinit var auth: AuthViewModel; lateinit var study: StudyQuizViewModel
        ui.runOnIdle { auth = ViewModelProvider(ui.activity)[AuthViewModel::class.java]; study = ViewModelProvider(ui.activity)[StudyQuizViewModel::class.java] }
        ui.waitUntil(30_000) { auth.state.value.session !is SessionState.Restoring && !study.state.value.isBusy }
        assertSame("No alterar sesiones ajenas", SessionState.Anonymous, auth.state.value.session)
        val originalServer = auth.state.value.server
        val source = source("ui-$run", listOf(0, 3), true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        seed(context, source)
        ui.runOnIdle { study.retryLocalLoad() }
        ui.waitUntil(30_000) { !study.state.value.isBusy }
        val original = originalBytes(context)
        val expectedAnonymous = study.state.value.quizzes
        try {
            ui.runOnIdle { auth.configureServer(baseUrl, confirmCleartext = true) }
            ui.waitUntil(30_000) { !auth.state.value.busy && auth.state.value.server == baseUrl }
            ui.runOnIdle { auth.login("missing_$run", password) }
            ui.waitUntil(30_000) { !auth.state.value.busy }
            assertSame(SessionState.Anonymous, auth.state.value.session)
            assertEquals(expectedAnonymous, study.state.value.quizzes)
            ui.runOnIdle { auth.clearMessage(); auth.register("anon_${run}_ui", "anon_${run}_ui@example.com", password, password) }
            ui.waitUntil(30_000) { auth.state.value.session is SessionState.Authenticated && !study.state.value.isBusy && study.accountOwner.value != null }
            assertFalse(study.state.value.quizzes.any { it.id == source.quiz.id })
            ui.onNodeWithText("Importar cuestionario del dispositivo").performClick()
            ui.waitUntil(30_000) { !study.state.value.isBusy }
            ui.onNodeWithText("Importar «${source.quiz.title}» · 2 preguntas").performScrollTo().performClick()
            val before = runBlocking { control("metrics").optInt("POST /quizzes/import") }
            ui.onNodeWithText("Confirmar importación y subida").performClick()
            ui.waitUntil(30_000) { !study.state.value.isBusy && study.accountState.value.records.any { it.quiz.id == AnonymousImportIdentity.localId(source.quiz.id) && it.remote != null && it.sync == QuizSyncState.Synced } }
            val copy = study.accountState.value.records.single { it.quiz.id == AnonymousImportIdentity.localId(source.quiz.id) }
            assertEquals(source.answers, copy.answers); assertTrue(copy.quiz.completed); assertEquals(1, copy.currentIndex)
            assertEquals(before + 1, runBlocking { control("metrics").optInt("POST /quizzes/import") })
            assertArrayEquals(original, originalBytes(context))
            ui.runOnIdle { study.clearMessage() }
            // Revocación real desde otra operación, seguida de restauración productiva.
            runBlocking { auth.session.authorized { AuthApi(auth.client).logout(it) } }
            ui.runOnIdle { auth.restore() }
            ui.waitUntil(30_000) { auth.state.value.session is SessionState.Anonymous && !study.state.value.isBusy && study.accountOwner.value == null }
            assertEquals(expectedAnonymous, study.state.value.quizzes)
            ui.runOnIdle { auth.clearMessage(); auth.login("anon_${run}_ui", password) }
            ui.waitUntil(30_000) { auth.state.value.session is SessionState.Authenticated && !study.state.value.isBusy && study.accountOwner.value != null }
            ui.runOnIdle { auth.logout() }
            ui.waitUntil(30_000) { auth.state.value.session is SessionState.Anonymous && !study.state.value.isBusy && study.accountOwner.value == null }
            assertEquals(expectedAnonymous, study.state.value.quizzes)
            assertArrayEquals(original, originalBytes(context))
        } finally {
            if (auth.state.value.session is SessionState.Authenticated) {
                ui.runOnIdle { auth.logout() }
                ui.waitUntil(30_000) { auth.state.value.session is SessionState.Anonymous && !auth.state.value.busy }
            }
            ui.runOnIdle { auth.clearMessage(); study.clearMessage(); auth.configureServer(originalServer, confirmCleartext = originalServer.startsWith("http://")) }
            ui.waitUntil(30_000) { !auth.state.value.busy && auth.state.value.server == originalServer }
        }
    }
}
