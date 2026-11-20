package cl.quizzunio.study

import cl.quizzunio.network.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AccountStudyState(val records: List<QuizRecord> = emptyList(), val hasMore: Boolean = true,
                             val page: Int = 0, val busy: Boolean = false, val error: String? = null,
                             val generationUncertain: Boolean = false)

/** Conserva identidad, respuestas y conflictos; el transporte nunca se mezcla con la UI. */
class AccountStudyCoordinator(val ownerId: Long, private val remote: RemoteQuizGateway, private val store: QuizRecordStore) {
    private val mutableState = MutableStateFlow(AccountStudyState())
    val state = mutableState.asStateFlow()
    private val writes = Mutex()
    private var loaded = false

    suspend fun load() {
        val records = withContext(Dispatchers.IO) { store.load() }
        require(records.all { it.ownerId == ownerId && (it.remote == null || it.remote.userId == ownerId) })
        val uncertain = withContext(Dispatchers.IO) { store.loadGenerationUncertain() }
        mutableState.value = AccountStudyState(records = records, generationUncertain = uncertain)
        loaded = true
    }
    fun record(id: String): QuizRecord? = state.value.records.firstOrNull { it.quiz.id == id }

    private suspend fun persist(records: List<QuizRecord>) {
        check(loaded) { "Recupera el almacenamiento de la cuenta antes de guardar nuevos datos." }
        require(records.all { it.ownerId == ownerId })
        withContext(Dispatchers.IO) { store.save(records) }
        mutableState.value = mutableState.value.copy(records = records)
    }
    private suspend fun replace(record: QuizRecord) = writes.withLock {
        persist(listOf(record) + state.value.records.filterNot { it.quiz.id == record.quiz.id || (record.remote != null && it.remote?.quizId == record.remote.quizId) })
    }

    suspend fun addLocal(quiz: StudyQuiz) { replace(QuizRecord(quiz, ownerId, QuizOrigin.Local)) }

    suspend fun capture(quiz: StudyQuiz, answers: List<Int?>, index: Int) = writes.withLock {
        val existing = record(quiz.id) ?: return@withLock
        require(answers.size == quiz.questions.size && index in quiz.questions.indices)
        val dirty = existing.answers != answers
        val updated = existing.copy(quiz = quiz, answers = answers.toList(), currentIndex = index,
            sync = if (dirty && existing.remote != null && existing.sync != QuizSyncState.Conflict) QuizSyncState.Pending else existing.sync)
        persist(state.value.records.map { if (it.quiz.id == quiz.id) updated else it })
    }

    suspend fun refresh(next: Boolean = false) {
        if (state.value.busy || (next && !state.value.hasMore)) return
        val page = if (next) state.value.page + 1 else 1
        mutableState.value = state.value.copy(busy = true, error = null)
        try {
            val response = remote.list(page)
            writes.withLock {
                val previous = state.value.records
                val records = previous.filter { next || it.remote == null || it.sync == QuizSyncState.Pending || it.sync == QuizSyncState.Conflict }.toMutableList()
                response.quizzes.forEach { quiz ->
                    val position = records.indexOfFirst { it.remote?.quizId == quiz.quizId }
                    val old = records.getOrNull(position) ?: previous.firstOrNull { it.remote?.quizId == quiz.quizId }
                    val updated = when {
                        old == null -> QuizRecord(quiz.toStudyQuiz(), ownerId, QuizOrigin.Server, quiz,
                            quiz.questions.map { it.currentAnswer.takeIf { n -> n >= 0 } }, sync = QuizSyncState.Synced)
                        old.sync == QuizSyncState.Pending || old.sync == QuizSyncState.Conflict -> old.copy(sync =
                            if (old.remote?.version != quiz.version) QuizSyncState.Conflict else old.sync)
                        else -> old.copy(quiz = quiz.toStudyQuiz(old.quiz.id), remote = quiz,
                            answers = quiz.questions.map { it.currentAnswer.takeIf { n -> n >= 0 } },
                            currentIndex = old.currentIndex.coerceAtMost(quiz.questions.lastIndex))
                    }
                    if (position >= 0) records[position] = updated else records.add(updated)
                }
                persist(records)
                mutableState.value = state.value.copy(page = page, hasMore = response.quizzes.size == response.pageSize)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { mutableState.value = state.value.copy(error = error.message ?: "No se pudo consultar la nube."); throw error }
        finally { mutableState.value = state.value.copy(busy = false) }
    }

    suspend fun generate(title: String, text: String, count: Int): QuizRecord {
        check(!state.value.generationUncertain) { "Consulta primero la nube y concilia la generación sin confirmar antes de volver a generar." }
        setGenerationUncertain(true)
        try {
            val quiz = remote.generate(title, text, count)
            val record = QuizRecord(quiz.toStudyQuiz(), ownerId, QuizOrigin.Server, quiz, sync = QuizSyncState.Synced)
            replace(record)
            setGenerationUncertain(false)
            return record
        } catch (error: ApiException) {
            if (error.status != 0 && error.code != "INVALID_RESPONSE") setGenerationUncertain(false)
            throw error
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (invalid: IllegalArgumentException) {
            setGenerationUncertain(false); throw invalid
        }
    }

    private suspend fun setGenerationUncertain(value: Boolean) = writes.withLock {
        check(loaded)
        withContext(Dispatchers.IO) { store.saveGenerationUncertain(state.value.records, value) }
        mutableState.value = state.value.copy(generationUncertain = value, page = if (value) 0 else state.value.page)
    }

    suspend fun confirmGenerationReconciled() {
        check(state.value.page > 0 && state.value.error == null) { "Consulta primero la nube." }
        setGenerationUncertain(false)
    }

    suspend fun upload(id: String): QuizRecord {
        val old = record(id) ?: error("No se encontró el cuestionario.")
        val quiz = remote.importLocal(old)
        val updated = old.copy(remote = quiz, sync = if (old.answers.any { it != null }) QuizSyncState.Pending else QuizSyncState.Synced)
        replace(updated)
        return updated
    }

    suspend fun synchronize(id: String): QuizRecord {
        val old = record(id) ?: error("No se encontró el cuestionario.")
        val quiz = old.remote ?: return old
        check(old.sync != QuizSyncState.Conflict) { "Hay un conflicto. Conserva tus respuestas y revisa la versión del servidor antes de sustituirlas." }
        try {
            val confirmed = remote.answers(quiz, old.answers)
            val updated = old.copy(remote = confirmed, sync = QuizSyncState.Synced)
            replace(updated); return updated
        } catch (error: ApiException) {
            if (error.code == "QUIZ_VERSION_CONFLICT") replace(old.copy(sync = QuizSyncState.Conflict))
            throw error
        }
    }

    suspend fun latest(id: String): RemoteQuiz {
        val quiz = record(id)?.remote ?: error("El cuestionario todavía es local.")
        return remote.get(quiz.quizId)
    }

    suspend fun acceptServerVersion(id: String) {
        val old = record(id) ?: error("No se encontró el cuestionario.")
        val latest = latest(id)
        replace(old.copy(quiz = latest.toStudyQuiz(old.quiz.id), remote = latest,
            answers = latest.questions.map { it.currentAnswer.takeIf { n -> n >= 0 } }, currentIndex = 0, sync = QuizSyncState.Synced))
    }

    suspend fun edit(id: String, title: String, questions: List<EditedQuestion>) {
        val old = record(id) ?: error("No se encontró el cuestionario.")
        val quiz = old.remote ?: error("La edición de contenido requiere un cuestionario guardado en la nube.")
        check(old.sync == QuizSyncState.Synced) { "Sincroniza las respuestas pendientes o resuelve el conflicto antes de editar contenido." }
        try {
            val updated = remote.edit(quiz, title, questions)
            replace(old.copy(quiz = updated.toStudyQuiz(old.quiz.id), remote = updated,
                answers = updated.questions.map { it.currentAnswer.takeIf { n -> n >= 0 } }, currentIndex = 0, sync = QuizSyncState.Synced))
        } catch (error: ApiException) {
            if (error.code == "QUIZ_VERSION_CONFLICT") replace(old.copy(sync = QuizSyncState.Conflict))
            throw error
        }
    }

    suspend fun delete(id: String) {
        val old = record(id) ?: return
        old.remote?.let { remote.delete(it) }
        writes.withLock { persist(state.value.records.filterNot { it.quiz.id == id }) }
    }
}
