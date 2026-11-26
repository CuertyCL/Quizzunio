package cl.quizzunio.study

import android.content.Context
import android.util.AtomicFile
import cl.quizzunio.core.QuizQuestion
import cl.quizzunio.core.hasValidUnicode
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Snapshot por servidor y cuenta; nunca se abre para una sesión anónima. */
class AccountQuizStore(context: Context, server: String, private val userId: Long) : QuizRecordStore {
    private val namespace = MessageDigest.getInstance("SHA-256").digest(server.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private val directory = File(context.applicationContext.noBackupFilesDir, "accounts/$namespace/$userId")
    private val file = AtomicFile(File(directory, "quizzes.json"))
    private var generationUncertain = false
    internal var pendingGeneration: PendingGeneration? = null
        private set
    val workspaceKey: String get() = "$namespace/$userId"
    init { require(userId > 0) }

    override fun load(): List<QuizRecord> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
            return emptyList()

        try {
            val text = InputStreamReader(
                file.openRead(),
                Charsets.UTF_8.newDecoder()).buffered().use { reader ->
                    val builder = StringBuilder(); val buffer = CharArray(8192)
                    while (true) {
                        val count = reader.read(buffer); if (count < 0) break
                        require(builder.length + count <= MAX_SNAPSHOT_CHARACTERS)
                        builder.append(buffer, 0, count)
                    }; builder.toString()
                }

            val root = JSONObject(text)
            generationUncertain = root.optBoolean("generationUncertain", false)
            require(root.getInt("formatVersion") == 1 && root.getLong("ownerId") == userId)
            pendingGeneration = if (root.has("pendingGeneration") && !root.isNull("pendingGeneration")) {
                val pending = root.getJSONObject("pendingGeneration")
                PendingGeneration(pending.getString("key"), pending.getString("fingerprint"))
            } else null
            val entries = root.getJSONArray("records")

            return (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)

                val questions = entry.getJSONArray("questions")
                require(questions.length() in 1..50)

                val quiz = StudyQuiz(
                    entry.getString("localId"),
                    entry.getString("title"),
                    entry.getString("topic"),
                    (0 until questions.length()).map {
                        val q = questions.getJSONObject(it)

                        val options = q.getJSONArray("options")
                        require(options.length() == 4 && q.getInt("correct") in 0..3)

                        val prompt = q.getString("question")

                        val alternatives = (0..3).map(options::getString)
                        require(prompt.hasValidUnicode() && alternatives.all { it.hasValidUnicode() })

                        QuizQuestion(prompt, alternatives, q.getInt("correct"))
                    },
                    entry.getBoolean("completed")
                )
                require(quiz.title.hasValidUnicode() && quiz.topic.hasValidUnicode())

                val answers = entry.getJSONArray("answers")
                require(answers.length() == quiz.questions.size)

                val values = (0 until answers.length()).map {
                    answers.getInt(it).also { n -> require(n in -1..3) }.takeIf { n -> n >= 0 }
                }

                val current = entry.getInt("currentIndex")
                require(current in quiz.questions.indices)

                val remote = if (entry.isNull("remote")) null else QuizCodec.decode(entry.getJSONObject("remote"))

                require(remote == null || (remote.userId == userId && remote.questions.size == quiz.questions.size))

                QuizRecord(
                    quiz,
                    userId,
                    QuizOrigin.valueOf(entry.getString("origin")),
                    remote,
                    values,
                    current,
                    QuizSyncState.valueOf(entry.getString("sync")),
                    entry.getString("importKey")
                )
            }.also { records -> require(records.map { it.quiz.id }.distinct().size == records.size) }
        } catch (error: Exception) {
            throw IOException("Los datos de esta cuenta no se pudieron abrir. El archivo se conserva para su recuperación.", error)
        }
    }

    override fun save(records: List<QuizRecord>) {
        require(records.all { it.ownerId == userId && it.answers.size == it.quiz.questions.size && (it.remote == null || it.remote.userId == userId) })

        val entries = JSONArray()
        records.forEach { record ->
            entries.put(JSONObject()
                .put("localId", record.quiz.id)
                .put("title", record.quiz.title)
                .put("topic", record.quiz.topic)
                .put("completed", record.quiz.completed)
                .put("origin", record.origin.name)
                .put("remote", record.remote?.let(QuizCodec::encode) ?: JSONObject.NULL)
                .put("sync", record.sync.name)
                .put("importKey", record.importKey)
                .put("currentIndex", record.currentIndex)
                .put("answers", JSONArray(record.answers.map { it ?: -1 }))
                .put("questions", JSONArray().apply {
                    record.quiz.questions.forEach {
                        put(JSONObject().put("question", it.question)
                            .put("options", JSONArray(it.answers))
                            .put("correct", it.correctAnswer)
                        )
                    }
                }))
        }

        val text = JSONObject().put("formatVersion", 1)
            .put("ownerId", userId)
            .put("records", entries)
            .put("generationUncertain", generationUncertain)
            .put("pendingGeneration", pendingGeneration?.let {
                JSONObject().put("key", it.key).put("fingerprint", it.fingerprint)
            } ?: JSONObject.NULL).toString()

        require(text.length <= MAX_SNAPSHOT_CHARACTERS) { "El almacenamiento de esta cuenta supera el límite local." }
        require(text.hasValidUnicode()) { "El almacenamiento contiene una secuencia Unicode inválida." }

        if (!directory.exists() && !directory.mkdirs()) throw IOException("No se pudo preparar el almacenamiento de la cuenta.")

        val stream = file.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    override fun loadGenerationUncertain(): Boolean = generationUncertain

    override fun saveGenerationUncertain(records: List<QuizRecord>, value: Boolean) {
        val previous = generationUncertain
        generationUncertain = value
        try { save(records) }
        catch (error: Exception) { generationUncertain = previous; throw error }
    }

    internal fun savePendingGeneration(records: List<QuizRecord>, pending: PendingGeneration?) {
        val previous = pendingGeneration
        val wasUncertain = generationUncertain
        pendingGeneration = pending
        try {
            saveGenerationUncertain(records, pending != null)
            val snapshot = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            val stored = snapshot.optJSONObject("pendingGeneration")
            if (pending == null && stored != null || pending != null &&
                (stored?.optString("key") != pending.key || stored?.optString("fingerprint") != pending.fingerprint)
            ) throw IOException("No se pudo confirmar la operación de generación en disco.")
            val folder = android.system.Os.open(directory.path,
                android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(folder) } finally { android.system.Os.close(folder) }
        } catch (error: Exception) {
            pendingGeneration = previous
            generationUncertain = wasUncertain
            throw error
        }
    }

    private companion object { const val MAX_SNAPSHOT_CHARACTERS = 16_000_000 }
}
