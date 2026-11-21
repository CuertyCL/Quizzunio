package cl.quizzunio.study

import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.core.hasValidUnicode
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
        val entries = value.getJSONArray("questions"); require(entries.length() in 1..QuizContentValidator.MAX_QUESTIONS)
        val questions = (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            val qid = entry.longValue("questionId"); val quizId = entry.longValue("quizId")
            val correct = entry.longValue("correctAnswerIndex"); val current = entry.longValue("current_answer")
            val options = entry.getJSONArray("options"); require(options.length() == 4)
            require(qid > 0 && quizId == id && correct in 0..3 && current in -1..3)
            val question = entry.stringValue("question")
            val alternatives = (0..3).map { options.get(it) as? String
                ?: throw IllegalArgumentException("Alternativa inválida.") }
            require(question.hasValidUnicode() && alternatives.all { it.hasValidUnicode() })
            RemoteQuestion(qid, quizId, question, alternatives, correct.toInt(), current.toInt())
        }
        require(questions.map { it.questionId }.distinct().size == questions.size)
        val title = value.stringValue("title")
        require(title.hasValidUnicode() && title.codePointCount(0, title.length) <= QuizContentValidator.MAX_TITLE_POINTS)
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

    @Suppress("UNUSED_PARAMETER")
    fun content(title: String, questions: List<EditedQuestion>, ascii: Boolean, version: Long? = null): JSONObject =
        content(title, questions, version)

    internal fun content(title: String, questions: List<EditedQuestion>, version: Long? = null): JSONObject {
        QuizContentValidator.title(title)
        require(questions.size in 1..QuizContentValidator.MAX_QUESTIONS) { "La nube admite entre 1 y 15 preguntas por cuestionario." }
        val ids = questions.mapNotNull { it.questionId }; require(ids.all { it > 0 } && ids.distinct().size == ids.size)
        val array = JSONArray()
        questions.forEach { question ->
            QuizContentValidator.question(question.question, question.options, question.correctAnswerIndex)
            array.put(JSONObject().put("question", question.question).put("options", JSONArray(question.options))
                .put("correctAnswerIndex", question.correctAnswerIndex).apply { question.questionId?.let { put("questionId", it) } })
        }
        return JSONObject().put("title", title).put("questions", array).apply { version?.let { require(it > 0); put("version", it) } }
    }
}
