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
    private val generation = Mutex()
    private var loaded = false
    private val imports = Mutex()
    private val answerSync = Mutex()
    val workspaceKey: String get() = (store as? AccountQuizStore)?.workspaceKey ?: "account/$ownerId"

    /** Confirma primero la copia local; un reintento continúa la operación original. */
    suspend fun importAnonymous(source: AnonymousQuizRecord): QuizRecord = imports.withLock {
        LocalQuizSnapshotCodec.validate(source)
        LocalQuizSnapshotCodec.validateNew(source.quiz)
        val id = AnonymousImportIdentity.localId(source.quiz.id)
        writes.withLock {
            if (record(id) == null) {
                val copy = QuizRecord(source.quiz.copy(id = id), ownerId, QuizOrigin.Local,
                    answers = source.answers.toList(), currentIndex = source.currentIndex,
                    importKey = java.util.UUID.randomUUID().toString())
                persist(listOf(copy) + state.value.records)
            }
        }
        continueAnonymousImport(id)
    }

    suspend fun retryAnonymousImport(id: String): QuizRecord = imports.withLock {
        require(AnonymousImportIdentity.isImported(id))
        continueAnonymousImport(id)
    }

    private suspend fun continueAnonymousImport(id: String): QuizRecord {
        var current = record(id) ?: error("No se encontró la copia local de la importación.")
        if (current.remote != null && current.sync == QuizSyncState.Synced) {
            // Una repetición explícita no debe anunciar éxito sobre un remoto ya eliminado.
            val latest = latest(id)
            current = writes.withLock {
                val saved = record(id) ?: error("La copia local ya no existe.")
                val conflict = latest.version != saved.remote?.version || (saved.sync == QuizSyncState.Synced &&
                    latest.questions.map { it.currentAnswer.takeIf { n -> n >= 0 } } != saved.answers)
                if (conflict) {
                    val updated = saved.copy(sync = QuizSyncState.Conflict)
                    persist(state.value.records.map { if (it.quiz.id == id) updated else it })
                    updated
                } else saved
            }
        }
        if (current.remote == null) current = upload(id)
        if (current.sync == QuizSyncState.Pending || current.sync == QuizSyncState.Conflict) current = synchronize(id)
        return current
    }

    suspend fun load() {
        loaded = false
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

    suspend fun addLocal(quiz: StudyQuiz) {
        QuizContentValidator.title(quiz.title)
        require(quiz.questions.size in 1..QuizContentValidator.MAX_QUESTIONS) {
            "Un cuestionario nuevo debe tener entre 1 y 15 preguntas."
        }
        quiz.questions.forEach { question ->
            QuizContentValidator.question(question.question, question.answers, question.correctAnswer)
        }
        replace(QuizRecord(quiz, ownerId, QuizOrigin.Local))
    }

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
                        else -> old.copy(quiz = quiz.toStudyQuiz(old.quiz.id).let { refreshed ->
                            if (AnonymousImportIdentity.isImported(old.quiz.id)) refreshed.copy(topic = old.quiz.topic,
                                completed = old.quiz.completed && refreshed.completed) else refreshed
                        }, remote = quiz,
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

    suspend fun generate(title: String, text: String, count: Int): QuizRecord =
        generate(title, text, count) {}

    internal suspend fun generate(title: String, text: String, count: Int, onRequest: () -> Unit): QuizRecord = generation.withLock {
        check(!state.value.generationUncertain) { "Consulta primero la nube y concilia la generación sin confirmar antes de volver a generar." }
        var submitted = false
        val beforeSubmission: suspend () -> Unit = {
            if (!submitted) {
                setGenerationUncertain(true)
                submitted = true
                onRequest()
            }
        }
        try {
            val quiz = if (remote is RemoteQuizRepository) {
                remote.generate(title, text, count, beforeSubmission)
            } else {
                beforeSubmission()
                remote.generate(title, text, count)
            }
            val record = QuizRecord(quiz.toStudyQuiz(), ownerId, QuizOrigin.Server, quiz, sync = QuizSyncState.Synced)
            replace(record)
            setGenerationUncertain(false)
            record
        } catch (error: ApiException) {
            if (submitted && error.status != 0 && error.code != "INVALID_RESPONSE") setGenerationUncertain(false)
            throw error
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (invalid: IllegalArgumentException) {
            if (submitted) setGenerationUncertain(false)
            throw invalid
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
        return writes.withLock {
            val latest = record(id) ?: error("La copia local ya no existe. Concilia la operación antes de repetirla.")
            val updated = latest.copy(remote = quiz, sync = if (latest.answers.any { it != null }) QuizSyncState.Pending else QuizSyncState.Synced)
            persist(listOf(updated) + state.value.records.filterNot { it.quiz.id == id || it.remote?.quizId == quiz.quizId })
            updated
        }
    }

    suspend fun synchronize(id: String): QuizRecord {
        return answerSync.withLock { synchronizeAnswers(id) }
    }

    private suspend fun synchronizeAnswers(id: String): QuizRecord {
        val old = record(id) ?: error("No se encontró el cuestionario.")
        val quiz = old.remote ?: return old
        check(old.sync != QuizSyncState.Conflict) { "Hay un conflicto. Conserva tus respuestas y revisa la versión del servidor antes de sustituirlas." }
        try {
            val confirmed = remote.answers(quiz, old.answers)
            return writes.withLock {
                val latest = record(id) ?: error("La copia local ya no existe.")
                if (latest.remote?.version != quiz.version) return@withLock latest
                val updated = latest.copy(remote = confirmed, sync = when {
                    latest.sync == QuizSyncState.Conflict -> QuizSyncState.Conflict
                    latest.answers != old.answers -> QuizSyncState.Pending
                    else -> QuizSyncState.Synced
                })
                persist(state.value.records.map { if (it.quiz.id == id) updated else it })
                updated
            }
        } catch (error: ApiException) {
            if (error.code == "QUIZ_VERSION_CONFLICT") writes.withLock {
                record(id)?.takeIf { it.remote?.version == quiz.version }?.let { current ->
                    persist(state.value.records.map { if (it.quiz.id == id) current.copy(sync = QuizSyncState.Conflict) else it })
                }
            }
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
        old.remote?.let {
            try { remote.delete(it) }
            catch (error: ApiException) {
                // La eliminación explícita también puede retirar una copia cuyo remoto ya no existe.
                if (error.status != 404) throw error
            }
        }
        writes.withLock { persist(state.value.records.filterNot { it.quiz.id == id }) }
    }
}
