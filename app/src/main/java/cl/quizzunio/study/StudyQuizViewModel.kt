package cl.quizzunio.study

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cl.quizzunio.core.hasValidUnicode
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.collect

data class StudyQuizState(
    val quizName: String = "",
    val topic: String = "",
    val questionCount: Int = 10,
    val documents: List<StudyDocument> = emptyList(),
    val quizzes: List<StudyQuiz> = emptyList(),
    val activeQuiz: StudyQuiz? = null,
    val answers: List<Int?> = emptyList(),
    val currentQuestionIndex: Int = 0,
    val isBusy: Boolean = true,
    val message: String? = null
) {
    val correctAnswers: Int
        get() {
            val quiz = activeQuiz ?: return 0
            return quiz.questions.indices.count { index ->
                answers.getOrNull(index) == quiz.questions[index].correctAnswer
            }
        }
}

enum class GenerationStage { Idle, Preparing, Requesting, Received, Validated, Failed }
enum class StudyNavigationTarget { Play, Results, EditorClosed }

/** Coordina importación, generación local y una sesión de respuestas independiente de la UI. */
class StudyQuizViewModel(
    private val repository: StudyQuizRepository,
    private val documentReader: StudyDocumentReader,
    private val generator: LocalQuizGenerator
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(StudyQuizState())
    val state: StateFlow<StudyQuizState> = mutableState.asStateFlow()
    private val attempts = mutableMapOf<String, Pair<List<Int?>, Int>>()
    private var account: AccountStudyCoordinator? = null
    private val mutableAccountOwner = MutableStateFlow<Long?>(null)
    val accountOwner: StateFlow<Long?> = mutableAccountOwner.asStateFlow()
    private val mutableReview = MutableStateFlow(false)
    val isReview: StateFlow<Boolean> = mutableReview.asStateFlow()
    private val mutableAccountState = MutableStateFlow(AccountStudyState())
    val accountState: StateFlow<AccountStudyState> = mutableAccountState.asStateFlow()
    private val mutableGenerationStage = MutableStateFlow(GenerationStage.Idle)
    val generationStage: StateFlow<GenerationStage> = mutableGenerationStage.asStateFlow()
    private val mutableNavigationTarget = MutableStateFlow<StudyNavigationTarget?>(null)
    val navigationTarget: StateFlow<StudyNavigationTarget?> = mutableNavigationTarget.asStateFlow()

    fun requestGeneratedQuiz() { mutableNavigationTarget.value = StudyNavigationTarget.Play }
    fun requestResults() { mutableNavigationTarget.value = StudyNavigationTarget.Results }
    fun requestEditorClose() { mutableNavigationTarget.value = StudyNavigationTarget.EditorClosed }
    fun consumeNavigation() { mutableNavigationTarget.value = null }

    fun bindAccount(coordinator: AccountStudyCoordinator?) {
        scope.coroutineContext.cancelChildren()
        account = coordinator
        attempts.clear()
        mutableState.value = StudyQuizState(isBusy = coordinator != null)
        mutableAccountState.value = AccountStudyState()
        mutableGenerationStage.value = GenerationStage.Idle
        mutableReview.value = false
        mutableNavigationTarget.value = null
        mutableAccountOwner.value = coordinator?.ownerId
        if (coordinator == null) return
        scope.launch {
            try {
                coordinator.load()
                mutableState.update { it.copy(isBusy = false) }
                launch {
                    coordinator.state.collect { value ->
                        mutableAccountState.value = value
                        mutableState.update { it.copy(quizzes = value.records.map { record -> record.quiz }) }
                    }
                }
                try { coordinator.refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { showMessage(error.message ?: "No se pudo consultar la nube. Los datos locales siguen disponibles.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.update { it.copy(isBusy = false, message = error.message) } }
        }
    }

    init {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repository.load() }
                mutableState.update {
                    it.copy(
                        quizzes = result.quizzes,
                        isBusy = false,
                        message = if (result.unreadableCount > 0) {
                            "No se pudieron abrir ${result.unreadableCount} cuestionarios guardados. Sus archivos se conservaron."
                        } else null
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.update {
                    it.copy(isBusy = false, message = "No se pudieron cargar los cuestionarios guardados.")
                }
            }
        }
    }

    fun updateQuizName(value: String) {
        if (!mutableState.value.isBusy) mutableState.update { it.copy(quizName = value) }
    }

    fun updateTopic(value: String) {
        if (!mutableState.value.isBusy) mutableState.update { it.copy(topic = value) }
    }

    fun changeQuestionCount(delta: Int) {
        if (!mutableState.value.isBusy) mutableState.update {
            it.copy(questionCount = (it.questionCount + delta.toLong()).coerceIn(1, QuizContentValidator.MAX_QUESTIONS.toLong()).toInt())
        }
    }

    fun importDocuments(uris: List<Uri>) = importDocuments(uris, forServer = false)

    internal fun importDocuments(uris: List<Uri>, forServer: Boolean) {
        val initial = mutableState.value
        if (initial.isBusy || uris.isEmpty()) return
        mutableState.update { it.copy(isBusy = true, message = null) }
        scope.launch {
            val documents = initial.documents.toMutableList()
            val messages = mutableListOf<String>()
            var characterCount = documents.sumOf { it.text.codePointCount(0, it.text.length) }
            val maxPoints = if (forServer) StudyDocumentReader.MAX_REMOTE_EXTRACTION_POINTS else MAX_CHARACTERS
            try {
                withContext(Dispatchers.IO) {
                    for (uri in uris.distinct()) {
                        ensureActive()
                        if (documents.any { it.id == uri.toString() }) continue
                        if (documents.size >= MAX_DOCUMENTS) {
                            messages += "Puedes adjuntar hasta $MAX_DOCUMENTS archivos."
                            break
                        }
                        try {
                            val document = documentReader.read(uri, forServer)
                            val points = document.text.codePointCount(0, document.text.length)
                            if (characterCount + points > maxPoints) {
                                messages += "${document.name}: el material combinado supera la capacidad de extracción de la aplicación."
                                continue
                            }
                            documents += document
                            characterCount += points
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            messages += exception.message ?: "No se pudo abrir uno de los archivos seleccionados."
                        }
                    }
                }
                mutableState.update {
                    it.copy(documents = documents.toList(), isBusy = false, message = messages.distinct().joinToString("\n").takeIf { text -> text.isNotBlank() })
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.update {
                    it.copy(documents = documents.toList(), isBusy = false, message = "No se pudo completar la importación de archivos.")
                }
            }
        }
    }

    fun removeDocument(id: String) {
        if (!mutableState.value.isBusy) mutableState.update {
            it.copy(documents = it.documents.filterNot { document -> document.id == id })
        }
    }

    fun generateQuiz(onComplete: () -> Unit) {
        val initial = mutableState.value
        initial.activeQuiz?.let { attempts[it.id] = initial.answers.toList() to initial.currentQuestionIndex }
        if (initial.isBusy) return
        if (!validateDraftTitle(initial.quizName)) {
            return
        }
        if (initial.documents.isEmpty()) {
            showMessage("Agrega al menos un archivo con texto para crear el cuestionario.")
            return
        }
        mutableState.update { it.copy(isBusy = true, message = null) }
        scope.launch {
            val quiz = try {
                require(initial.topic.hasValidUnicode() && initial.topic.codePointCount(0, initial.topic.length) <= 200) {
                    "El tema admite hasta 200 puntos de código Unicode válidos."
                }
                require(initial.documents.sumOf { it.text.codePointCount(0, it.text.length) } <= MAX_CHARACTERS) {
                    "La generación local admite hasta $MAX_CHARACTERS puntos de código de material."
                }
                val generated = withContext(Dispatchers.Default) {
                    generator.generate(initial.documents.joinToString("\n\n") { it.text }, initial.questionCount)
                }
                val created = StudyQuiz(
                    id = UUID.randomUUID().toString(),
                    title = initial.quizName,
                    topic = initial.topic,
                    questions = generated
                )
                if (repository.canPersist) withContext(Dispatchers.IO) { repository.save(created) }
                account?.addLocal(created)
                created
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.update {
                    it.copy(isBusy = false, message = exception.message ?: "No se pudo generar el cuestionario.")
                }
                return@launch
            }
            mutableState.update {
                it.copy(
                    quizzes = listOf(quiz) + it.quizzes.filterNot { saved -> saved.id == quiz.id },
                    activeQuiz = quiz,
                    answers = List(quiz.questions.size) { null },
                    currentQuestionIndex = 0,
                    quizName = "",
                    topic = "",
                    documents = emptyList(),
                    isBusy = false,
                    message = null
                )
            }
            onComplete()
        }
    }

    fun selectQuiz(id: String): Boolean {
        val current = mutableState.value
        if (current.isBusy) return false
        val quiz = current.quizzes.firstOrNull { it.id == id } ?: return false
        current.activeQuiz?.let { attempts[it.id] = current.answers to current.currentQuestionIndex }
        val attempt = attempts[id]
        val saved = account?.record(id)
        mutableReview.value = false
        mutableState.update {
            it.copy(activeQuiz = quiz, answers = saved?.answers ?: attempt?.first ?: List(quiz.questions.size) { null }, currentQuestionIndex = saved?.currentIndex ?: attempt?.second ?: 0, message = null)
        }
        return true
    }

    fun selectAnswer(index: Int) {
        val current = mutableState.value
        if (mutableReview.value) return
        if (current.isBusy) return
        val question = current.activeQuiz?.questions?.getOrNull(current.currentQuestionIndex) ?: return
        if (index !in question.answers.indices || current.answers.getOrNull(current.currentQuestionIndex) != null) return
        mutableState.update {
            it.copy(answers = it.answers.toMutableList().apply { set(current.currentQuestionIndex, index) })
        }
        persistCurrentAttempt()
    }

    fun previousQuestion() {
        if (!mutableState.value.isBusy) mutableState.update {
            it.copy(currentQuestionIndex = (it.currentQuestionIndex - 1).coerceAtLeast(0))
        }
        persistCurrentAttempt()
    }

    /** Devuelve true cuando la última pregunta está contestada y se puede finalizar. */
    fun nextQuestion(): Boolean {
        val current = mutableState.value
        if (current.isBusy || current.activeQuiz == null) return false
        if (current.answers.getOrNull(current.currentQuestionIndex) == null) {
            showMessage("Selecciona una respuesta antes de continuar.")
            return false
        }
        if (current.currentQuestionIndex == current.activeQuiz.questions.lastIndex) return true
        mutableState.update { it.copy(currentQuestionIndex = it.currentQuestionIndex + 1) }
        persistCurrentAttempt()
        return false
    }

    fun finishQuiz(onComplete: () -> Unit = {}) {
        val current = mutableState.value
        val quiz = current.activeQuiz ?: return
        if (current.isBusy) return
        if (mutableReview.value) { onComplete(); return }
        if (current.answers.size != quiz.questions.size || current.answers.any { it == null }) {
            showMessage("Responde todas las preguntas antes de finalizar.")
            return
        }
        mutableState.update { it.copy(isBusy = true, message = null) }
        scope.launch {
            val completed = quiz.copy(completed = true)
            var capturedLocally = false
            try {
                if (repository.canPersist) withContext(Dispatchers.IO) { repository.save(completed) }
                account?.let { coordinator ->
                    coordinator.capture(completed, current.answers, current.currentQuestionIndex)
                    capturedLocally = true
                    coordinator.synchronize(completed.id)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (capturedLocally) {
                    mutableState.update {
                        it.copy(
                            activeQuiz = completed,
                            quizzes = it.quizzes.map { saved -> if (saved.id == quiz.id) completed else saved },
                            isBusy = false,
                            message = "Finalizado localmente (pendiente de sincronizar): ${exception.message ?: "sin confirmación del servidor."}"
                        )
                    }
                    onComplete()
                } else {
                    mutableState.update {
                        it.copy(isBusy = false, message = exception.message ?: "No se pudo guardar la finalización. Inténtalo de nuevo.")
                    }
                }
                return@launch
            }
            mutableState.update {
                it.copy(
                    activeQuiz = completed,
                    quizzes = it.quizzes.map { saved -> if (saved.id == quiz.id) completed else saved },
                    isBusy = false
                )
            }
            onComplete()
        }
    }

    fun resetAttempt() {
        val current = mutableState.value
        val quiz = current.activeQuiz ?: return
        mutableReview.value = false
        if (!current.isBusy) mutableState.update {
            it.copy(activeQuiz = quiz.copy(completed = false), quizzes = it.quizzes.map { saved -> if (saved.id == quiz.id) saved.copy(completed = false) else saved },
                answers = List(quiz.questions.size) { null }, currentQuestionIndex = 0, message = null)
        }
        persistCurrentAttempt()
    }

    fun reviewAnswers() {
        mutableReview.value = true
        if (!mutableState.value.isBusy) mutableState.update {
            it.copy(currentQuestionIndex = 0, message = null)
        }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    fun pauseQuiz(onComplete: () -> Unit) {
        val current = mutableState.value
        val quiz = current.activeQuiz ?: return
        if (current.isBusy) return
        attempts[quiz.id] = current.answers.toList() to current.currentQuestionIndex
        val coordinator = account
        if (coordinator != null) {
            runAccountOperation {
                coordinator.capture(quiz, current.answers, current.currentQuestionIndex)
                try {
                    coordinator.synchronize(quiz.id)
                    showMessage(if (coordinator.record(quiz.id)?.remote != null) "El servidor confirmó las respuestas." else "Progreso guardado en este dispositivo para tu cuenta.")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    showMessage("Progreso guardado en este dispositivo (pendiente de sincronizar): ${error.message ?: "sin confirmación del servidor."}")
                }
                onComplete()
            }
            return
        }
        showMessage("El progreso permanece en memoria mientras la aplicación siga abierta. No se ha guardado en disco ni en la nube.")
        onComplete()
    }

    private fun persistCurrentAttempt() {
        val coordinator = account ?: return
        val current = mutableState.value
        val quiz = current.activeQuiz ?: return
        scope.launch {
            try { coordinator.capture(quiz, current.answers, current.currentQuestionIndex) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showMessage("No se pudo guardar el progreso local: ${error.message}") }
        }
    }

    private fun runAccountOperation(block: suspend (AccountStudyCoordinator) -> Unit) {
        val coordinator = account ?: run { showMessage("Inicia sesión para acceder a la nube."); return }
        if (state.value.isBusy) return
        mutableState.update { it.copy(isBusy = true, message = null) }
        scope.launch {
            try { block(coordinator) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showMessage(error.message ?: "La operación no pudo completarse.") }
            finally { mutableState.update { it.copy(isBusy = false) } }
        }
    }

    fun refreshCloud(next: Boolean = false) = runAccountOperation { it.refresh(next) }
    fun confirmGenerationReconciled() = runAccountOperation { it.confirmGenerationReconciled() }
    fun uploadQuiz(id: String) = runAccountOperation {
        val current = state.value
        if (current.activeQuiz?.id == id) it.capture(current.activeQuiz, current.answers, current.currentQuestionIndex)
        it.upload(id)
        showMessage("El servidor confirmó el guardado en la nube. Las respuestas del intento se sincronizan por separado.")
    }
    fun synchronizeQuiz(id: String) = runAccountOperation {
        val current = state.value
        if (current.activeQuiz?.id == id) it.capture(current.activeQuiz, current.answers, current.currentQuestionIndex)
        it.synchronize(id); showMessage("El servidor confirmó las respuestas.")
    }
    /** Valida precondiciones locales antes de abrir la pantalla de procesamiento remoto. */
    fun startRemoteGeneration(onStarted: () -> Unit) {
        val initial = mutableState.value
        if (account == null) { showMessage("Inicia sesión para acceder a la nube."); return }
        if (initial.isBusy) return
        if (accountState.value.generationUncertain) {
            showMessage("Consulta primero la nube y concilia la generación sin confirmar antes de volver a generar.")
            return
        }
        if (!validateDraftTitle(initial.quizName)) return
        if (initial.documents.isEmpty()) {
            showMessage("Adjunta material antes de generar.")
            return
        }
        if (initial.questionCount !in 1..QuizContentValidator.MAX_QUESTIONS) {
            showMessage("La generación remota admite entre 1 y 15 preguntas.")
            return
        }
        onStarted()
        generateRemote { }
    }

    fun generateRemote(onComplete: () -> Unit) = runAccountOperation {
        val initial = state.value
        initial.activeQuiz?.let { attempts[it.id] = initial.answers.toList() to initial.currentQuestionIndex }
        mutableGenerationStage.value = GenerationStage.Preparing
        try {
            require(initial.documents.isNotEmpty()) { "Adjunta material antes de generar." }
            val text = withContext(Dispatchers.Default) { initial.documents.joinToString("\n\n") { document -> document.text } }
            val record = it.generate(initial.quizName, text, initial.questionCount) {
                mutableGenerationStage.value = GenerationStage.Requesting
            }
            mutableGenerationStage.value = GenerationStage.Received
            mutableState.update { value -> value.copy(activeQuiz = record.quiz, answers = record.answers, currentQuestionIndex = 0,
                quizName = "", topic = "", documents = emptyList()) }
            mutableGenerationStage.value = GenerationStage.Validated
            onComplete()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) { mutableGenerationStage.value = GenerationStage.Failed; throw error }
    }
    fun editQuiz(id: String, title: String, questions: List<EditedQuestion>, onComplete: () -> Unit) = runAccountOperation {
        it.edit(id, title, questions)
        if (state.value.activeQuiz?.id == id) {
            val record = it.record(id) ?: return@runAccountOperation
            mutableState.update { value -> value.copy(activeQuiz = record.quiz, answers = record.answers, currentQuestionIndex = record.currentIndex) }
        }
        onComplete()
    }
    fun deleteQuiz(id: String) = runAccountOperation {
        it.delete(id)
        attempts.remove(id)
        if (state.value.activeQuiz?.id == id) mutableState.update { value -> value.copy(activeQuiz = null, answers = emptyList(), currentQuestionIndex = 0) }
    }
    fun acceptServerVersion(id: String) = runAccountOperation {
        it.acceptServerVersion(id)
        if (state.value.activeQuiz?.id == id) {
            val record = it.record(id) ?: return@runAccountOperation
            mutableState.update { value -> value.copy(activeQuiz = record.quiz, answers = record.answers, currentQuestionIndex = 0) }
        }
    }
    fun inspectServerVersion(id: String) = runAccountOperation {
        val latest = it.latest(id)
        showMessage("Versión del servidor: ${latest.version}. ${latest.title}\n${latest.questions.size} preguntas. Tus cambios y respuestas locales siguen conservados.")
    }

    private fun showMessage(message: String) {
        mutableState.update { it.copy(message = message) }
    }

    private fun validateDraftTitle(title: String): Boolean = try {
        QuizContentValidator.title(title)
        true
    } catch (error: IllegalArgumentException) {
        showMessage(error.message ?: "Escribe un título válido.")
        false
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_DOCUMENTS = 8
        const val MAX_CHARACTERS = StudyDocumentReader.MAX_LOCAL_EXTRACTION_POINTS
    }
}

val LocalStudyQuizViewModel = staticCompositionLocalOf<StudyQuizViewModel?> { null }

@Composable
fun rememberStudyQuizViewModel(): StudyQuizViewModel {
    val context = LocalContext.current
    val activity = remember(context) { context.findStudyActivity() }
    return remember(activity) {
        val appContext = activity.applicationContext
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(StudyQuizViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return StudyQuizViewModel(
                    StudyQuizRepository(appContext),
                    StudyDocumentReader(appContext),
                    LocalQuizGenerator()
                ) as T
            }
        }
        ViewModelProvider(activity, factory)[StudyQuizViewModel::class.java]
    }
}

private fun Context.findStudyActivity(): ComponentActivity {
    var candidate: Context = this
    while (candidate is ContextWrapper) {
        if (candidate is ComponentActivity) return candidate
        val base = candidate.baseContext
        if (base === candidate) break
        candidate = base
    }
    error("La gestión de cuestionarios requiere una actividad de Android.")
}
