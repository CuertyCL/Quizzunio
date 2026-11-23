package cl.quizzunio.study

import java.security.MessageDigest

/** La relación origen/copia es estable dentro de cada espacio servidor/cuenta. */
object AnonymousImportIdentity {
    fun localId(sourceId: String): String = "anonymous-" + MessageDigest.getInstance("SHA-256")
        .digest(sourceId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun isImported(id: String): Boolean = id.startsWith("anonymous-") && id.length == 74
}
