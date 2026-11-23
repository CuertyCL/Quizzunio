package cl.quizzunio.study

import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.network.ApiException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test

class AnonymousImportCoordinatorTest {
    private class Store : QuizRecordStore {
        var records = emptyList<QuizRecord>()
        override fun load() = records
        override fun save(records: List<QuizRecord>) { this.records = records.toList() }
        override fun loadGenerationUncertain() = false
        override fun saveGenerationUncertain(records: List<QuizRecord>, value: Boolean) = save(records)
    }
    private open class Remote(val owner: Long = 17) : RemoteQuizGateway {
        val imported = mutableMapOf<String, RemoteQuiz>()
        var importCalls = 0
        var answerCalls = 0
        var loseImportResponse = false
        var failAnswers = false
        var conflict = false
        override suspend fun list(page: Int, pageSize: Int) = RemotePage(page, pageSize, imported.values.toList())
        override suspend fun get(id: Long) = imported.values.single { it.quizId == id }
        override suspend fun generate(title: String, text: String, count: Int): RemoteQuiz = error("La importación nunca usa IA")
        override suspend fun importLocal(record: QuizRecord): RemoteQuiz {
            importCalls++
            val quiz = imported.getOrPut(record.importKey) { RemoteQuiz(81, owner, record.quiz.title, "2026-11-22 00:00:00.000", 1,
                record.quiz.questions.mapIndexed { index, q -> RemoteQuestion(100L + index, 81, q.question, q.answers, q.correctAnswer, -1) }) }
            if (loseImportResponse) { loseImportResponse = false; throw ApiException(0, "NETWORK_ERROR", "Respuesta perdida") }
            return quiz
        }
        override suspend fun answers(quiz: RemoteQuiz, answers: List<Int?>): RemoteQuiz {
            answerCalls++
            if (conflict) throw ApiException(409, "QUIZ_VERSION_CONFLICT", "Conflicto")
            if (failAnswers) throw ApiException(0, "NETWORK_ERROR", "Sin conexión")
            val updated = quiz.copy(questions = quiz.questions.mapIndexed { index, q -> q.copy(currentAnswer = answers[index] ?: -1) })
            imported.entries.first { it.value.quizId == quiz.quizId }.setValue(updated)
            return updated
        }
        override suspend fun edit(quiz: RemoteQuiz, title: String, questions: List<EditedQuestion>) = error("No se edita al importar")
        override suspend fun delete(quiz: RemoteQuiz) { imported.entries.removeAll { it.value.quizId == quiz.quizId } }
    }
    private fun source(answers: List<Int?> = listOf(3, null), completed: Boolean = false) = AnonymousQuizRecord(
        StudyQuiz("source-123", "Ñ 😀", "Tema", List(2) { QuizQuestion("¿Pregunta $it?", listOf("Á", "B", "C", "D"), 0) }, completed), answers, 1)

    @Test fun immediateImportPreservesEveryProgressVariantAndNeverChangesTheSource() = runBlocking {
        listOf(source(listOf(null, null)), source(), source(listOf(0, 3), true)).forEach { original ->
            val remote = Remote(); val store = Store(); val coordinator = AccountStudyCoordinator(17, remote, store)
            coordinator.load(); val result = coordinator.importAnonymous(original)
            assertEquals(1, remote.importCalls)
            assertEquals(original.answers, result.answers); assertEquals(1, result.currentIndex)
            assertEquals(original.quiz.completed, result.quiz.completed); assertEquals(original.quiz.topic, result.quiz.topic)
            assertEquals(QuizSyncState.Synced, result.sync)
            assertEquals(original.answers.map { it ?: -1 }, result.remote!!.questions.map { it.currentAnswer })
            assertEquals(source(original.answers, original.quiz.completed), original)
            coordinator.importAnonymous(original)
            assertEquals(1, store.records.size); assertEquals(1, remote.importCalls)
            coordinator.refresh()
            assertEquals(original.quiz.completed, coordinator.record(result.quiz.id)!!.quiz.completed)
        }
    }

    @Test fun lostImportResponseAndRestartReuseThePersistedCopyAndKey() = runBlocking {
        val remote = Remote().apply { loseImportResponse = true }; val store = Store()
        val first = AccountStudyCoordinator(17, remote, store); first.load()
        try { first.importAnonymous(source()); fail("Debe informar respuesta perdida") } catch (expected: ApiException) { assertEquals(0, expected.status) }
        val pending = store.records.single(); assertNull(pending.remote)
        assertEquals(1, remote.imported.size)
        val restored = AccountStudyCoordinator(17, remote, store); restored.load()
        restored.refresh()
        val result = restored.retryAnonymousImport(pending.quiz.id)
        assertEquals(pending.importKey, result.importKey); assertEquals(pending.quiz.id, result.quiz.id)
        assertEquals(1, remote.imported.size); assertEquals(2, remote.importCalls)
        assertEquals(1, store.records.size)
        assertEquals(listOf(3, -1), result.remote!!.questions.map { it.currentAnswer })
    }

    @Test fun restartAfterRemoteCreationOnlyRetriesAnswersAndPreservesConflicts() = runBlocking {
        val remote = Remote().apply { failAnswers = true }; val store = Store()
        val first = AccountStudyCoordinator(17, remote, store); first.load()
        try { first.importAnonymous(source()); fail("Debe informar respuestas pendientes") } catch (expected: ApiException) { assertEquals(0, expected.status) }
        val pending = store.records.single(); assertNotNull(pending.remote); assertEquals(QuizSyncState.Pending, pending.sync)
        val restored = AccountStudyCoordinator(17, remote, store); restored.load()
        remote.failAnswers = false; remote.conflict = true
        try { restored.retryAnonymousImport(pending.quiz.id); fail("Debe conservar conflicto") } catch (expected: ApiException) { assertEquals(409, expected.status) }
        assertEquals(QuizSyncState.Conflict, store.records.single().sync)
        assertEquals(source().answers, store.records.single().answers)
        assertEquals(1, remote.importCalls)
    }

    @Test fun incompatibleHistoricalContentIsRejectedBeforeCopyOrNetworkAndDeletionStartsANewOperation() = runBlocking {
        val remote = Remote(); val store = Store(); val coordinator = AccountStudyCoordinator(17, remote, store); coordinator.load()
        for (invalid in listOf(source().copy(quiz = source().quiz.copy(questions = List(16) { source().quiz.questions.first() }), answers = List(16) { null }),
            source().copy(quiz = source().quiz.copy(questions = source().quiz.questions.map { it.copy(question = " ") })))) {
            try { coordinator.importAnonymous(invalid); fail("Debe rechazar el histórico incompatible") } catch (expected: IllegalArgumentException) { assertTrue(store.records.isEmpty()) }
        }
        assertEquals(0, remote.importCalls)
        val first = coordinator.importAnonymous(source()); coordinator.delete(first.quiz.id)
        val second = coordinator.importAnonymous(source())
        assertNotEquals(first.importKey, second.importKey)
        assertEquals(1, store.records.size)
    }

    @Test fun answerChangesDuringImportAreKeptAndDuplicateClicksShareTheSameOperation() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val remote = object : Remote() {
            override suspend fun importLocal(record: QuizRecord): RemoteQuiz {
                entered.complete(Unit); release.await(); return super.importLocal(record)
            }
        }
        val store = Store(); val coordinator = AccountStudyCoordinator(17, remote, store); coordinator.load()
        val first = async { coordinator.importAnonymous(source()) }
        entered.await()
        val copied = store.records.single()
        coordinator.capture(copied.quiz, listOf(0, 2), 0)
        val duplicate = async { coordinator.importAnonymous(source()) }
        release.complete(Unit)
        val confirmed = first.await(); duplicate.await()
        assertEquals(listOf(0, 2), confirmed.answers); assertEquals(0, confirmed.currentIndex)
        assertEquals(listOf(0, 2), confirmed.remote!!.questions.map { it.currentAnswer })
        assertEquals(1, remote.importCalls); assertEquals(1, store.records.size)
    }

    @Test fun newerAnswersDuringSynchronizationRemainPendingUntilExplicitRetry() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val remote = object : Remote() {
            override suspend fun answers(quiz: RemoteQuiz, answers: List<Int?>): RemoteQuiz {
                entered.complete(Unit); release.await(); return super.answers(quiz, answers)
            }
        }
        val store = Store(); val coordinator = AccountStudyCoordinator(17, remote, store); coordinator.load()
        val operation = async { coordinator.importAnonymous(source()) }
        entered.await()
        val copied = store.records.single()
        coordinator.capture(copied.quiz, listOf(1, 2), 0)
        release.complete(Unit)
        assertEquals(QuizSyncState.Pending, operation.await().sync)
        assertEquals(listOf(1, 2), store.records.single().answers)
        val confirmed = coordinator.retryAnonymousImport(copied.quiz.id)
        assertEquals(QuizSyncState.Synced, confirmed.sync)
        assertEquals(listOf(1, 2), confirmed.remote!!.questions.map { it.currentAnswer })
        assertEquals(1, remote.importCalls)
    }

    @Test fun explicitReconciliationDoesNotOverwriteAnswersCapturedWhileGettingTheRemote() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var delayRead = false
        val remote = object : Remote() {
            override suspend fun get(id: Long): RemoteQuiz {
                val result = super.get(id)
                if (delayRead) { entered.complete(Unit); release.await() }
                return result
            }
        }
        val store = Store(); val coordinator = AccountStudyCoordinator(17, remote, store); coordinator.load()
        val original = coordinator.importAnonymous(source())
        delayRead = true
        val repeated = async { coordinator.retryAnonymousImport(original.quiz.id) }
        entered.await(); coordinator.capture(original.quiz, listOf(0, 2), 0); release.complete(Unit)
        val confirmed = repeated.await()
        assertEquals(listOf(0, 2), confirmed.answers)
        assertEquals(listOf(0, 2), confirmed.remote!!.questions.map { it.currentAnswer })
        assertEquals(QuizSyncState.Synced, confirmed.sync); assertEquals(1, remote.importCalls)
    }
}
