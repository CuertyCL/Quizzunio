package cl.quizzunio.study

import android.util.JsonReader
import android.util.JsonToken
import cl.quizzunio.auth.AccessPolicy
import cl.quizzunio.auth.SessionRepository
import cl.quizzunio.auth.SessionState
import cl.quizzunio.core.hasNonBlankContent
import cl.quizzunio.core.utf8ByteCount
import cl.quizzunio.network.*
import java.io.IOException
import java.io.StringReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RemoteQuizRepository(private val client: ApiClient, private val session: SessionRepository) : RemoteQuizGateway {
    private fun owner(): Long = (session.state.value as? SessionState.Authenticated)?.user?.userId
        ?: throw IllegalStateException("Inicia sesión para acceder a la nube.")
    private fun document(text: String, owner: Long): RemoteQuiz = decodeResponse {
        document(JSONObject(text), owner)
    }
    private fun document(value: JSONObject, owner: Long): RemoteQuiz =
        QuizCodec.decode(value).also { require(it.userId == owner) { "Propietario inesperado." } }

    override suspend fun list(page: Int, pageSize: Int): RemotePage {
        require(page > 0 && pageSize in 1..50)
        val owner = owner()
        return session.authorized { token ->
            try {
                readPage(page, pageSize, owner, token)
            } catch (error: ApiException) {
                if (error.code != "RESPONSE_TOO_LARGE" || pageSize == 1) throw error
                val first = (page.toLong() - 1) * pageSize + 1
                if (first + pageSize - 1 > Int.MAX_VALUE) throw error
                val quizzes = mutableListOf<RemoteQuiz>()
                for (index in 0 until pageSize) {
                    val item = readPage((first + index).toInt(), 1, owner, token).quizzes.singleOrNull() ?: break
                    quizzes.add(item)
                }
                RemotePage(page, pageSize, quizzes)
            }
        }
    }

    private suspend fun readPage(page: Int, pageSize: Int, owner: Long, token: String): RemotePage = decodeResponse {
        val value = JSONObject(client.request("GET", "/quizzes?page=$page&pageSize=$pageSize", 200, token = token))
        require(value.longValue("page") == page.toLong() && value.longValue("pageSize") == pageSize.toLong())
        val entries = value.getJSONArray("quizzes")
        require(entries.length() <= pageSize)
        RemotePage(page, pageSize, (0 until entries.length()).map { document(entries.getJSONObject(it), owner) })
    }

    override suspend fun get(id: Long): RemoteQuiz {
        require(id > 0); val owner = owner()
        return session.authorized { decodeResponse { document(client.request("GET", "/quizzes/$id", 200, token = it), owner).also { require(it.quizId == id) } } }
    }
    override suspend fun generate(title: String, text: String, count: Int): RemoteQuiz =
        generate(title, text, count) {}

    internal suspend fun generate(title: String, text: String, count: Int, beforeSubmission: suspend () -> Unit): RemoteQuiz {
        check(AccessPolicy.canGenerateRemotely(session.state.value)) { "La generación en servidor requiere Premium verificado." }
        QuizContentValidator.title(title)
        require(count in 1..QuizContentValidator.MAX_QUESTIONS && text.hasNonBlankContent()) {
            "La generación remota admite entre 1 y 15 preguntas y necesita texto fuente."
        }
        val owner = owner()
        val limit = session.authorized { token -> readTextLimit(token) }
        val bytes = withContext(Dispatchers.Default) { text.utf8ByteCount() }
        require(bytes <= limit) {
            "El texto ocupa $bytes bytes UTF-8 y el servidor admite $limit. Quita o divide el material."
        }
        return session.authorized {
            check(owner() == owner && AccessPolicy.canGenerateRemotely(session.state.value)) {
                "La cuenta cambió antes de generar. Verifica la sesión y vuelve a intentarlo."
            }
            beforeSubmission()
            val body = JSONObject().put("title", title).put("text", text).put("questionCount", count)
            decodeResponse {
                document(client.request("POST", "/quizzes", 201, body, it), owner).also { quiz ->
                    require(quiz.title == title && quiz.questions.size == count)
                    quiz.questions.forEach { question ->
                        QuizContentValidator.question(question.question, question.options, question.correctAnswerIndex)
                    }
                }
            }
        }
    }

    private suspend fun readTextLimit(token: String): Long = try {
        val response = client.request("GET", "/quizzes/limits", 200, token = token)
        decodeResponse { parseTextLimit(response) }
    } catch (error: ApiException) {
        if (error.status == 401) throw error
        val message = when (error.code) {
            "INVALID_RESPONSE", "RESPONSE_TOO_LARGE" -> "El servidor devolvió un límite de texto inválido."
            "TIMEOUT" -> "Se agotó el tiempo de espera al consultar el límite de texto."
            else -> "No se pudo consultar el límite de texto del servidor."
        }
        throw ApiException(error.status, error.code, "$message Inténtalo de nuevo.")
    }

    private fun parseTextLimit(response: String): Long = try {
        JsonReader(StringReader(response)).use { reader ->
            var limit: Long? = null
            var hasEncoding = false
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "maxTextBytes" -> {
                        require(limit == null && reader.peek() == JsonToken.NUMBER)
                        limit = reader.nextString().toLongOrNull()
                        require(limit != null && limit in 1..MAX_CONFIGURED_TEXT_BYTES)
                    }
                    "encoding" -> {
                        require(!hasEncoding && reader.peek() == JsonToken.STRING && reader.nextString() == "UTF-8")
                        hasEncoding = true
                    }
                    else -> throw IllegalArgumentException("Campo de límite inesperado.")
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT && hasEncoding && limit != null)
            limit
        }
    } catch (error: IOException) {
        throw IllegalArgumentException("JSON de límites inválido.", error)
    } catch (error: IllegalStateException) {
        throw IllegalArgumentException("Estructura de límites inválida.", error)
    }
    override suspend fun importLocal(record: QuizRecord): RemoteQuiz {
        val owner = owner(); require(record.ownerId == owner && record.remote == null)
        val body = QuizCodec.content(record.quiz.title, record.quiz.questions.map { EditedQuestion(null, it.question, it.answers, it.correctAnswer) })
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
        val body = QuizCodec.content(title, questions, quiz.version)
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

    private companion object {
        const val MAX_CONFIGURED_TEXT_BYTES = 8_388_608L
    }
}
