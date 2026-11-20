package cl.quizzunio.study

import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.network.ApiException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Pruebas de componente del coordinador; disco y HTTP son dependencias sustituidas. */
class AccountStudyCoordinatorTest {
    private class Store : QuizRecordStore {
        var records: List<QuizRecord> = emptyList()
        var corrupted = false
        var uncertain = false
        override fun load(): List<QuizRecord> { if (corrupted) throw IOException("Corrupto"); return records }
        override fun save(records: List<QuizRecord>) { this.records = records.toList() }
        override fun loadGenerationUncertain(): Boolean = uncertain
        override fun saveGenerationUncertain(records: List<QuizRecord>, value: Boolean) { save(records); uncertain = value }
    }
    private class Remote : RemoteQuizGateway {
        var doc = RemoteQuiz(81, 17, "Cuestionario", "2026-11-19 00:00:00.000", 3,
            listOf(RemoteQuestion(905, 81, "Primera", listOf("A", "B", "C", "D"), 0, -1),
                RemoteQuestion(12, 81, "Segunda", listOf("E", "F", "G", "H"), 2, -1)))
        var conflict = false
        var deleted = false
        var imports = 0
        var generations = 0
        var failGeneration = false
        var lastKey: String? = null
        override suspend fun list(page: Int, pageSize: Int) = RemotePage(page, pageSize, listOf(doc))
        override suspend fun get(id: Long) = doc
        override suspend fun generate(title: String, text: String, count: Int): RemoteQuiz {
            generations++; if (failGeneration) throw ApiException(0, "NETWORK_ERROR", "Sin confirmación"); return doc
        }
        override suspend fun importLocal(record: QuizRecord): RemoteQuiz { imports++; lastKey = record.importKey; return doc }
        override suspend fun answers(quiz: RemoteQuiz, answers: List<Int?>): RemoteQuiz {
            if (conflict) throw ApiException(409, "QUIZ_VERSION_CONFLICT", "Conflicto")
            doc = doc.copy(questions = doc.questions.mapIndexed { index, question -> question.copy(currentAnswer = answers[index] ?: -1) })
            return doc
        }
        override suspend fun edit(quiz: RemoteQuiz, title: String, questions: List<EditedQuestion>) = doc.copy(title = title, version = doc.version + 1)
        override suspend fun delete(quiz: RemoteQuiz) { deleted = true }
    }
    private fun local() = StudyQuiz("local-1234567890123456", "Local", "Tema", listOf(
        QuizQuestion("Primera", listOf("A", "B", "C", "D"), 0), QuizQuestion("Segunda", listOf("E", "F", "G", "H"), 2)))

    @Test fun localGenerationStorageDoesNotUseRemoteGenerationOrImport() = runBlocking {
        val remote = Remote(); val store = Store(); val coordinator = AccountStudyCoordinator(17, remote, store); coordinator.load()
        coordinator.addLocal(local())
        assertEquals(0, remote.generations); assertEquals(0, remote.imports)
        assertEquals(17L, store.records.single().ownerId); assertNull(store.records.single().remote)
        coordinator.capture(local(), listOf(2, null), 1)
        val restored = AccountStudyCoordinator(17, remote, store); restored.load()
        assertEquals(listOf(2, null), restored.record(local().id)?.answers)
        assertEquals(1, restored.record(local().id)?.currentIndex)
    }

    @Test fun explicitUploadPreservesLocalIdentityAndMarksExistingAnswersPending() = runBlocking {
        val remote = Remote(); val coordinator = AccountStudyCoordinator(17, remote, Store()); coordinator.load()
        coordinator.addLocal(local()); coordinator.capture(local(), listOf(2, null), 0)
        val uploaded = coordinator.upload(local().id)
        assertEquals(local().id, uploaded.quiz.id); assertEquals(local().id, remote.lastKey)
        assertEquals(81L, uploaded.remote?.quizId); assertEquals(listOf(2, null), uploaded.answers)
        assertEquals(QuizSyncState.Pending, uploaded.sync); assertEquals(0, remote.generations)
        val confirmed = coordinator.synchronize(local().id)
        assertEquals(QuizSyncState.Synced, confirmed.sync)
        assertEquals(listOf(2, -1), remote.doc.questions.map { it.currentAnswer })
    }

    @Test fun pageRefreshDoesNotDuplicateRemoteIdsOrOverwritePendingAnswers() = runBlocking {
        val remote = Remote(); val coordinator = AccountStudyCoordinator(17, remote, Store()); coordinator.load(); coordinator.refresh()
        val initial = coordinator.state.value.records.single()
        coordinator.capture(initial.quiz, listOf(1, null), 1)
        remote.doc = remote.doc.copy(questions = remote.doc.questions.map { it.copy(currentAnswer = 3) })
        coordinator.refresh(); coordinator.refresh()
        assertEquals(1, coordinator.state.value.records.size)
        assertEquals(listOf(1, null), coordinator.record(initial.quiz.id)?.answers)
        assertEquals(QuizSyncState.Pending, coordinator.record(initial.quiz.id)?.sync)
    }

    @Test fun conflictKeepsAnswersUntilUserExplicitlyAcceptsServerVersion() = runBlocking {
        val remote = Remote(); val store = Store(); val coordinator = AccountStudyCoordinator(17, remote, store)
        coordinator.load(); coordinator.refresh(); val old = coordinator.state.value.records.single()
        coordinator.capture(old.quiz, listOf(1, null), 1); remote.conflict = true; remote.doc = remote.doc.copy(version = 4)
        try { coordinator.synchronize(old.quiz.id); fail("Debe conservar el conflicto") } catch (error: ApiException) { assertEquals(409, error.status) }
        assertEquals(QuizSyncState.Conflict, coordinator.record(old.quiz.id)?.sync)
        assertEquals(listOf(1, null), store.records.single().answers)
        coordinator.refresh(); assertEquals(listOf(1, null), coordinator.record(old.quiz.id)?.answers)
        coordinator.acceptServerVersion(old.quiz.id)
        assertEquals(4L, coordinator.record(old.quiz.id)?.remote?.version)
        assertEquals(listOf(null, null), coordinator.record(old.quiz.id)?.answers)
    }

    @Test fun corruptedStorageCannotBeOverwrittenByGeneration() = runBlocking {
        val store = Store().apply { corrupted = true }; val coordinator = AccountStudyCoordinator(17, Remote(), store)
        try { coordinator.load(); fail("Debe detectar corrupción") } catch (error: IOException) { assertEquals("Corrupto", error.message) }
        try { coordinator.addLocal(local()); fail("No debe sobrescribir") } catch (error: IllegalStateException) { assertTrue(store.records.isEmpty()) }
    }

    @Test fun unknownGenerationRequiresExplicitReconciliationBeforeRetry() = runBlocking {
        val remote = Remote().apply { failGeneration = true }; val store = Store(); val coordinator = AccountStudyCoordinator(17, remote, store); coordinator.load()
        try { coordinator.generate("Título", "Texto", 2); fail("No confirmado") } catch (error: ApiException) { assertEquals(0, error.status) }
        assertTrue(coordinator.state.value.generationUncertain)
        val restored = AccountStudyCoordinator(17, remote, store); restored.load()
        assertTrue(restored.state.value.generationUncertain)
        try { coordinator.generate("Título", "Texto", 2); fail("No debe repetir") } catch (error: IllegalStateException) { assertEquals(1, remote.generations) }
        coordinator.refresh(); coordinator.confirmGenerationReconciled(); remote.failGeneration = false
        coordinator.generate("Título", "Texto", 2); assertEquals(2, remote.generations)
    }

    @Test fun localDeletionDoesNotCallTheServerAndRemoteDeletionWaitsForConfirmation() = runBlocking {
        val remote = Remote(); val coordinator = AccountStudyCoordinator(17, remote, Store()); coordinator.load()
        coordinator.addLocal(local()); coordinator.delete(local().id); assertFalse(remote.deleted); assertTrue(coordinator.state.value.records.isEmpty())
        coordinator.refresh(); coordinator.delete(coordinator.state.value.records.single().quiz.id)
        assertTrue(remote.deleted); assertTrue(coordinator.state.value.records.isEmpty())
    }
}
