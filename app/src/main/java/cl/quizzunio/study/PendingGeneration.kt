package cl.quizzunio.study

import java.security.MessageDigest
import java.util.UUID

internal data class PendingGeneration(val key: String, val fingerprint: String) {
    init {
        require(GENERATION_KEY.matches(key) && Regex("[0-9a-f]{64}").matches(fingerprint))
    }

    companion object {
        val GENERATION_KEY = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        fun create(title: String, text: String, count: Int): PendingGeneration {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("${title.length}:$title:$count:".toByteArray(Charsets.UTF_8))
            val hash = digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            return PendingGeneration(UUID.randomUUID().toString(), hash)
        }
    }
}

internal data class GenerationLookup(val state: String, val quiz: RemoteQuiz? = null)
