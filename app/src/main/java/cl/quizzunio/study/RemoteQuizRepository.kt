package cl.quizzunio.study

import cl.quizzunio.auth.AccessPolicy
import cl.quizzunio.auth.SessionRepository
import cl.quizzunio.auth.SessionState
import cl.quizzunio.network.*
import org.json.JSONObject

class RemoteQuizRepository(private val client: ApiClient, private val session: SessionRepository) : RemoteQuizGateway {
    private fun owner(): Long = (session.state.value as? SessionState.Authenticated)?.user?.userId
        ?: throw IllegalStateException("Inicia sesión para acceder a la nube.")
    private fun document(text: String, owner: Long): RemoteQuiz = decodeResponse {
        QuizCodec.decode(JSONObject(text)).also { require(it.userId == owner) { "Propietario inesperado." } }
    }
    override suspend fun list(page: Int, pageSize: Int): RemotePage {
        require(page > 0 && pageSize in 1..50)
        val owner = owner()
        return session.authorized { token -> decodeResponse {
            val value = JSONObject(client.request("GET", "/quizzes?page=$page&pageSize=$pageSize", 200, token = token))
            require(value.longValue("page") == page.toLong() && value.longValue("pageSize") == pageSize.toLong())
            val entries = value.getJSONArray("quizzes"); require(entries.length() <= pageSize)
            RemotePage(page, pageSize, (0 until entries.length()).map { document(entries.getJSONObject(it).toString(), owner) })
        } }
    }
    override suspend fun get(id: Long): RemoteQuiz {
        require(id > 0); val owner = owner()
        return session.authorized { decodeResponse { document(client.request("GET", "/quizzes/$id", 200, token = it), owner).also { require(it.quizId == id) } } }
    }
    override suspend fun generate(title: String, text: String, count: Int): RemoteQuiz {
        check(AccessPolicy.canGenerateRemotely(session.state.value)) { "La generación en servidor requiere Premium verificado." }
        require(count in 1..15 && title.isNotBlank() && title.length <= 255 && text.isNotBlank()) { "La generación remota admite 1–15 preguntas y necesita título y texto." }
        require((title + text).all { it.code <= 127 }) { "El generador remoto solo acepta ASCII. Usa generación local para este material; no se eliminarán acentos." }
        val owner = owner()
        return session.authorized {
            check(AccessPolicy.canGenerateRemotely(session.state.value))
            decodeResponse { document(client.request("POST", "/quizzes", 201, JSONObject().put("title", title).put("text", text).put("questionCount", count), it), owner)
                .also { quiz -> require(quiz.questions.size == count) { "El servidor no devolvió la cantidad solicitada." } }
            }
        }
    }
    override suspend fun importLocal(record: QuizRecord): RemoteQuiz {
        val owner = owner(); require(record.ownerId == owner && record.remote == null)
        val body = QuizCodec.content(record.quiz.title, record.quiz.questions.map { EditedQuestion(null, it.question, it.answers, it.correctAnswer) }, false)
        return session.authorized { decodeResponse { document(client.request("POST", "/quizzes/import", 201, body, it,
            mapOf("Idempotency-Key" to record.importKey)), owner).also { confirmed ->
                require(confirmed.title == record.quiz.title && confirmed.questions.size == record.quiz.questions.size)
                require(confirmed.questions.zip(record.quiz.questions).all { (received, sent) ->
                    received.question == sent.question && received.options == sent.answers && received.correctAnswerIndex == sent.correctAnswer
                })
            } } }
    }
    override suspend fun answers(quiz: RemoteQuiz, answers: List<Int?>): RemoteQuiz {
        val owner = owner(); require(quiz.userId == owner)
        val body = QuizCodec.answers(quiz, answers)
        return session.authorized { decodeResponse { document(client.request("PUT", "/quizzes/${quiz.quizId}/answers", 200, body, it), owner).also { confirmed ->
            require(confirmed.quizId == quiz.quizId && confirmed.version == quiz.version && confirmed.questions.map { it.questionId }.toSet() == quiz.questions.map { it.questionId }.toSet())
            val supplied = quiz.questions.mapIndexed { index, question -> question.questionId to (answers[index] ?: -1) }.toMap()
            require(confirmed.questions.all { supplied[it.questionId] == it.currentAnswer }) { "El servidor no confirmó las respuestas enviadas." }
        } } }
    }
    override suspend fun edit(quiz: RemoteQuiz, title: String, questions: List<EditedQuestion>): RemoteQuiz {
        val owner = owner(); require(quiz.userId == owner)
        val existing = quiz.questions.map { it.questionId }.toSet()
        require(questions.all { it.questionId == null || it.questionId in existing })
        val body = QuizCodec.content(title, questions, true, quiz.version)
        return session.authorized { decodeResponse { document(client.request("PUT", "/quizzes/${quiz.quizId}", 200, body, it), owner).also {
            require(quiz.version < Long.MAX_VALUE && it.quizId == quiz.quizId && it.version == quiz.version + 1)
            require(it.title == title && it.questions.size == questions.size && it.questions.zip(questions).all { (received, sent) ->
                (sent.questionId == null || received.questionId == sent.questionId) && received.question == sent.question &&
                    received.options == sent.options && received.correctAnswerIndex == sent.correctAnswerIndex
            })
        } } }
    }
    override suspend fun delete(quiz: RemoteQuiz) {
        require(quiz.userId == owner())
        session.authorized { client.request("DELETE", "/quizzes/${quiz.quizId}", 204, token = it) }
    }
}
