package cl.quizzunio.study

import android.util.AtomicFile
import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.core.hasValidUnicode
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Cuestionario e intento actual; no representa una identidad del servidor. */
data class AnonymousQuizRecord(
    val quiz: StudyQuiz,
    val answers: List<Int?> = List(quiz.questions.size) { null },
    val currentIndex: Int = 0
)

/** Formato local compartido: leer históricos no exige las reglas de una creación nueva. */
internal object LocalQuizSnapshotCodec {
    fun validate(record: AnonymousQuizRecord) {
        val quiz = record.quiz
        require(Regex("[A-Za-z0-9_-]{1,80}").matches(quiz.id)) { "Identificador local inválido." }
        require(quiz.title.hasValidUnicode() && quiz.topic.hasValidUnicode())
        require(quiz.questions.size in 1..50)
        quiz.questions.forEach {
            require(it.answers.size == 4 && it.correctAnswer in 0..3)
            require(it.question.hasValidUnicode() && it.answers.all(String::hasValidUnicode))
        }
        require(record.answers.size == quiz.questions.size && record.answers.all { it == null || it in 0..3 })
        require(record.currentIndex in quiz.questions.indices)
        require(!quiz.completed || record.answers.all { it != null }) { "Finalización sin respuestas completas." }
    }

    fun validateNew(quiz: StudyQuiz) {
        require(quiz.questions.size in 1..QuizContentValidator.MAX_QUESTIONS) { "Las nuevas generaciones admiten de 1 a 15 preguntas." }
        QuizContentValidator.title(quiz.title)
        quiz.questions.forEach { QuizContentValidator.question(it.question, it.answers, it.correctAnswer) }
    }

    fun encode(record: AnonymousQuizRecord): JSONObject {
        validate(record)
        val quiz = record.quiz
        return JSONObject().put("localId", quiz.id).put("title", quiz.title).put("topic", quiz.topic)
            .put("completed", quiz.completed).put("currentIndex", record.currentIndex)
            .put("answers", JSONArray(record.answers.map { it ?: -1 }))
            .put("questions", JSONArray().apply {
                quiz.questions.forEach {
                    put(JSONObject().put("question", it.question).put("options", JSONArray(it.answers)).put("correct", it.correctAnswer))
                }
            })
    }

    fun decode(entry: JSONObject): AnonymousQuizRecord {
        val questions = entry.getJSONArray("questions")
        require(questions.length() in 1..50)
        val quiz = StudyQuiz(string(entry, "localId"), string(entry, "title"), string(entry, "topic"),
            (0 until questions.length()).map {
                val question = questions.getJSONObject(it)
                val options = question.getJSONArray("options")
                require(options.length() == 4)
                QuizQuestion(string(question, "question"), (0..3).map { index -> options.get(index) as? String
                    ?: throw IllegalArgumentException("Alternativa local inválida.") }, integer(question, "correct"))
            }, entry.get("completed") as Boolean)
        val answers = entry.getJSONArray("answers")
        return AnonymousQuizRecord(quiz, (0 until answers.length()).map {
            val value = answers.get(it)
            require(value is Int && value in -1..3)
            value.takeIf { n -> n >= 0 }
        }, integer(entry, "currentIndex")).also(::validate)
    }

    private fun integer(value: JSONObject, name: String): Int = (value.get(name) as? Int)
        ?: throw IllegalArgumentException("Entero local inválido: $name")

    private fun string(value: JSONObject, name: String): String = (value.get(name) as? String)
        ?: throw IllegalArgumentException("Cadena local inválida: $name")
}

/** Lectura recuperable, UTF-8 estricto y exclusión mutua entre instancias del mismo archivo. */
internal class AtomicQuizSnapshot(private val base: File) {
    private val file = AtomicFile(base)
    private val lock = locks.getOrPut(base.absolutePath) { Any() }

    fun <T> locked(block: () -> T): T = synchronized(lock) { block() }

    fun read(): JSONObject? {
        // openRead recupera también el respaldo antiguo cuando la base está ausente.
        if (!base.exists() && !File(base.path + ".bak").exists()) return null
        val text = InputStreamReader(file.openRead(), Charsets.UTF_8.newDecoder()).buffered().use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(result.length + count <= MAX_CHARACTERS) { "El snapshot supera el límite local." }
                result.append(buffer, 0, count)
            }
            result.toString()
        }
        require(text.hasValidUnicode())
        return JSONObject(text)
    }

    fun write(root: JSONObject) {
        val text = root.toString()
        require(text.length <= MAX_CHARACTERS && text.hasValidUnicode()) { "El almacenamiento supera el límite local o contiene Unicode inválido." }
        val directory = requireNotNull(base.parentFile)
        if (!directory.exists() && !directory.mkdirs()) throw IOException("No se pudo preparar el almacenamiento local.")
        val stream = file.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val MAX_CHARACTERS = 16_000_000
        val locks = ConcurrentHashMap<String, Any>()
    }
}
