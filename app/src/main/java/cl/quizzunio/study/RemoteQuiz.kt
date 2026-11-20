package cl.quizzunio.study

import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.network.*
import org.json.JSONArray
import org.json.JSONObject

enum class QuizOrigin { Local, Server }
enum class QuizSyncState { LocalOnly, Pending, Synced, Conflict }
data class RemoteQuestion(val questionId: Long, val quizId: Long, val question: String, val options: List<String>,
                          val correctAnswerIndex: Int, val currentAnswer: Int)
data class RemoteQuiz(val quizId: Long, val userId: Long, val title: String, val creationDate: String,
                      val version: Long, val questions: List<RemoteQuestion>) {
    fun toStudyQuiz(localId: String = "remote-$quizId"): StudyQuiz = StudyQuiz(localId, title, "",
        questions.map { QuizQuestion(it.question, it.options, it.correctAnswerIndex) }, questions.all { it.currentAnswer != -1 })
}
data class QuizRecord(val quiz: StudyQuiz, val ownerId: Long, val origin: QuizOrigin,
                      val remote: RemoteQuiz? = null, val answers: List<Int?> = List(quiz.questions.size) { null },
                      val currentIndex: Int = 0, val sync: QuizSyncState = QuizSyncState.LocalOnly,
                      val importKey: String = quiz.id)
data class RemotePage(val page: Int, val pageSize: Int, val quizzes: List<RemoteQuiz>)
data class EditedQuestion(val questionId: Long?, val question: String, val options: List<String>, val correctAnswerIndex: Int)

interface QuizRecordStore {
    fun load(): List<QuizRecord>
    fun save(records: List<QuizRecord>)
    fun loadGenerationUncertain(): Boolean
    fun saveGenerationUncertain(records: List<QuizRecord>, value: Boolean)
}

interface RemoteQuizGateway {
    suspend fun list(page: Int, pageSize: Int = 20): RemotePage
    suspend fun get(id: Long): RemoteQuiz
    suspend fun generate(title: String, text: String, count: Int): RemoteQuiz
    suspend fun importLocal(record: QuizRecord): RemoteQuiz
    suspend fun answers(quiz: RemoteQuiz, answers: List<Int?>): RemoteQuiz
    suspend fun edit(quiz: RemoteQuiz, title: String, questions: List<EditedQuestion>): RemoteQuiz
    suspend fun delete(quiz: RemoteQuiz)
}

object QuizCodec {
    fun decode(value: JSONObject): RemoteQuiz = decodeResponse {
        val id = value.longValue("quizId"); val owner = value.longValue("userId"); val version = value.longValue("version")
        require(id > 0 && owner > 0 && version > 0)
        val entries = value.getJSONArray("questions"); require(entries.length() in 1..15)
        val questions = (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            val qid = entry.longValue("questionId"); val quizId = entry.longValue("quizId")
            val correct = entry.longValue("correctAnswerIndex"); val current = entry.longValue("current_answer")
            val options = entry.getJSONArray("options"); require(options.length() == 4)
            require(qid > 0 && quizId == id && correct in 0..3 && current in -1..3)
            RemoteQuestion(qid, quizId, entry.stringValue("question"), (0..3).map { options.get(it) as? String
                ?: throw IllegalArgumentException("Alternativa inválida.") }, correct.toInt(), current.toInt())
        }
        require(questions.map { it.questionId }.distinct().size == questions.size)
        val title = value.stringValue("title"); require(title.length <= 255)
        val date = value.stringValue("creationDate")
        require(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}").matches(date))
        RemoteQuiz(id, owner, title, date, version, questions)
    }

    fun encode(quiz: RemoteQuiz): JSONObject = JSONObject().put("quizId", quiz.quizId).put("userId", quiz.userId)
        .put("title", quiz.title).put("creationDate", quiz.creationDate).put("version", quiz.version)
        .put("questions", JSONArray().apply { quiz.questions.forEach {
            put(JSONObject().put("questionId", it.questionId).put("quizId", it.quizId).put("question", it.question)
                .put("options", JSONArray(it.options)).put("correctAnswerIndex", it.correctAnswerIndex).put("current_answer", it.currentAnswer))
        } })

    fun answers(quiz: RemoteQuiz, answers: List<Int?>): JSONObject {
        require(answers.size == quiz.questions.size && answers.all { it == null || it in 0..3 }) { "Debes enviar todas las respuestas del cuestionario." }
        return JSONObject().put("version", quiz.version).put("answers", JSONArray().apply {
            quiz.questions.forEachIndexed { index, question ->
                put(JSONObject().put("questionId", question.questionId).put("current_answer", answers[index] ?: -1))
            }
        })
    }

    fun content(title: String, questions: List<EditedQuestion>, ascii: Boolean, version: Long? = null): JSONObject {
        require(title.isNotBlank() && title.length <= 255 && questions.size in 1..15) { "La nube admite 1–15 preguntas y títulos de hasta 255 caracteres." }
        require(!ascii || title.all { it.code <= 127 }) { "El contrato de edición solo admite ASCII. El contenido original se conserva." }
        val ids = questions.mapNotNull { it.questionId }; require(ids.all { it > 0 } && ids.distinct().size == ids.size)
        val array = JSONArray()
        questions.forEach { question ->
            require(question.options.size == 4 && question.correctAnswerIndex in 0..3)
            val texts = listOf(question.question) + question.options
            require(texts.all { it.isNotBlank() && Charsets.UTF_8.newEncoder().canEncode(it) && it.toByteArray(Charsets.UTF_8).size <= 60000 }) {
                "Cada pregunta y alternativa debe tener texto válido de hasta 60000 bytes UTF-8."
            }
            require(!ascii || texts.all { text -> text.all { it.code <= 127 } }) { "La edición remota solo admite ASCII. No se translitera ni se elimina contenido." }
            array.put(JSONObject().put("question", question.question).put("options", JSONArray(question.options))
                .put("correctAnswerIndex", question.correctAnswerIndex).apply { question.questionId?.let { put("questionId", it) } })
        }
        return JSONObject().put("title", title).put("questions", array).apply { version?.let { require(it > 0); put("version", it) } }
    }
}
