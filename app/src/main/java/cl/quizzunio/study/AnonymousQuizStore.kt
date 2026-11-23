package cl.quizzunio.study

import android.content.Context
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Un único espacio del dispositivo, independiente de cuenta y URL. */
class AnonymousQuizStore internal constructor(base: File) {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, "anonymous/quizzes.json"))
    private val snapshot = AtomicQuizSnapshot(base)

    fun load(): List<AnonymousQuizRecord> = snapshot.locked { read() }

    internal fun update(change: (List<AnonymousQuizRecord>) -> List<AnonymousQuizRecord>): List<AnonymousQuizRecord> = snapshot.locked {
        // Leer antes de cada mutación impide sobrescribir corrupción y snapshots de otra instancia.
        val next = change(read())
        next.forEach(LocalQuizSnapshotCodec::validate)
        require(next.map { it.quiz.id }.distinct().size == next.size) { "Identificadores locales duplicados." }
        snapshot.write(JSONObject().put("formatVersion", 1).put("workspace", "anonymous")
            .put("records", JSONArray().apply { next.forEach { put(LocalQuizSnapshotCodec.encode(it)) } }))
        next
    }

    private fun read(): List<AnonymousQuizRecord> = try {
        val root = snapshot.read()
        if (root == null) emptyList() else {
            require(root.get("formatVersion") == 1 && root.getString("workspace") == "anonymous")
            val records = root.getJSONArray("records")
            (0 until records.length()).map { LocalQuizSnapshotCodec.decode(records.getJSONObject(it)) }
                .also { require(it.map { record -> record.quiz.id }.distinct().size == it.size) }
        }
    } catch (error: Exception) {
        throw IOException("No se pudieron abrir los cuestionarios anónimos. El archivo se conserva; no se sobrescribirá. ${error.message.orEmpty()}", error)
    }
}

/** Publica únicamente snapshots confirmados; ninguna operación puede contactar con la nube. */
class AnonymousStudyCoordinator(private val store: AnonymousQuizStore) {
    private val writes = Mutex()
    private var loaded = false
    private val mutableRecords = MutableStateFlow<List<AnonymousQuizRecord>>(emptyList())
    val records = mutableRecords.asStateFlow()

    suspend fun load() = writes.withLock {
        loaded = false
        mutableRecords.value = emptyList()
        mutableRecords.value = withContext(Dispatchers.IO) { store.load() }
        loaded = true
    }

    fun record(id: String): AnonymousQuizRecord? = records.value.firstOrNull { it.quiz.id == id }

    private suspend fun mutate(change: (List<AnonymousQuizRecord>) -> List<AnonymousQuizRecord>) = writes.withLock {
        check(loaded) { "Recupera el almacenamiento anónimo antes de guardar." }
        mutableRecords.value = withContext(Dispatchers.IO) { store.update(change) }
    }

    suspend fun add(quiz: StudyQuiz) {
        LocalQuizSnapshotCodec.validateNew(quiz)
        mutate { listOf(AnonymousQuizRecord(quiz)) + it.filterNot { record -> record.quiz.id == quiz.id } }
    }

    suspend fun capture(record: AnonymousQuizRecord) = mutate { records ->
        check(records.any { it.quiz.id == record.quiz.id }) { "El cuestionario ya no existe en el espacio anónimo." }
        records.map { if (it.quiz.id == record.quiz.id) record else it }
    }

    suspend fun delete(id: String) = mutate { it.filterNot { record -> record.quiz.id == id } }

    internal suspend fun rename(id: String, title: String) {
        QuizContentValidator.title(title)
        mutate { records ->
            check(records.any { it.quiz.id == id }) { "El cuestionario ya no existe en el espacio anónimo." }
            records.map { if (it.quiz.id == id) it.copy(quiz = it.quiz.copy(title = title)) else it }
        }
    }
}
