package cl.quizzunio.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

interface TokenStore {
    fun read(server: String): TokenPair?
    fun write(server: String, tokens: TokenPair)
    fun clear()
}

internal interface SessionCleanupJournal {
    fun isPending(): Boolean
    fun begin()
    fun finish()
}

private fun fileExists(path: File): Boolean = try {
    Os.stat(path.path)
    true
} catch (error: ErrnoException) {
    if (error.errno != OsConstants.ENOENT) throw IOException("No se pudo acceder al archivo de sesión.", error)
    false
}

private fun AtomicFile.openSessionRead(): java.io.FileInputStream? {
    val backup = File(baseFile.path + ".bak")
    return try {
        val stream = openRead()
        // Si AtomicFile no pudo restaurar una copia antigua, no uses el base residual.
        try {
            if (fileExists(backup)) throw IOException("No se pudo recuperar el respaldo de sesión.")
        } catch (error: Exception) {
            stream.close()
            throw error
        }
        stream
    } catch (error: FileNotFoundException) {
        if (fileExists(baseFile) || fileExists(backup)) throw error
        null
    }
}

private fun AtomicFile.syncSessionDirectory() {
    val directory = Os.open(baseFile.parentFile!!.path, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(directory)
    } finally {
        Os.close(directory)
    }
}

private class AtomicSessionCleanup(directory: File) : SessionCleanupJournal {
    private val file = AtomicFile(File(directory, "session-cleanup"))

    override fun isPending(): Boolean = file.openSessionRead()?.use { stream ->
        val marker = stream.read()
        if (marker == -1) false
        else {
            if (marker != 1 || stream.read() != -1) throw IOException("La marca de limpieza de sesión está dañada.")
            true
        }
    } ?: false

    override fun begin() {
        if (runCatching { isPending() }.getOrDefault(false)) {
            file.syncSessionDirectory()
            return
        }
        // Abrir antes de reemplazar para que AtomicFile trate su respaldo anterior.
        file.openSessionRead()?.close()
        write(true)
    }

    override fun finish() = write(false)

    private fun write(pending: Boolean) {
        val output = file.startWrite()
        try {
            if (pending) output.write(1)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        if (isPending() != pending) throw IOException("No se pudo confirmar la marca de limpieza de sesión.")
        file.syncSessionDirectory()
    }
}

/** AES-GCM con clave no exportable y archivo excluido de copias y transferencias. */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "session.enc"))
    internal val cleanupJournal: SessionCleanupJournal = AtomicSessionCleanup(context.applicationContext.noBackupFilesDir)
    private val alias = "quizzunio.session.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let {
            return it
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true)
                .build()
            )
        }.generateKey()
    }

    override fun read(server: String): TokenPair? {
        if (cleanupJournal.isPending()) throw IOException("La sesión está pendiente de limpieza.")

        try {
            val bytes = file.openSessionRead()?.use { it.readBytesLimited() } ?: return null

            if (bytes.isEmpty()) return null

            require(bytes.size > 28 && bytes[0].toInt() == 1)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(server.toByteArray(Charsets.UTF_8))

            val value = JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))

            require(value.getString("server") == server)

            return TokenPair(
                value.getString("access"),
                value.getString("refresh"),
                Instant.parse(value.getString("accessExpiry")),
                Instant.parse(value.getString("refreshExpiry")),
                Instant.parse(value.getString("absoluteExpiry"))
            ).apply {
                if (value.has("registrationIdentity")) {
                    val identity = value.getJSONObject("registrationIdentity")
                    val username = identity.getString("username")
                    val email = identity.getString("email")
                    CredentialValidator.username(username)
                    CredentialValidator.email(email)
                    registrationIdentity = RegistrationIdentity(username, email)
                }
            }
        } catch (error: GeneralSecurityException) {
            throw IOException("No se pudo descifrar la sesión. Vuelve a iniciar sesión.")
        } catch (error: IllegalArgumentException) {
            throw IOException("La sesión guardada está dañada.")
        } catch (error: org.json.JSONException) {
            throw IOException("La sesión guardada está incompleta.")
        }
    }

    override fun write(server: String, tokens: TokenPair) {
        check(!cleanupJournal.isPending()) { "La sesión está pendiente de limpieza." }
        val plain = JSONObject().put("server", server)
            .put("access", tokens.accessToken)
            .put("refresh", tokens.refreshToken)
            .put("accessExpiry", tokens.accessExpiresAt.toString())
            .put("refreshExpiry", tokens.refreshExpiresAt.toString())
            .put("absoluteExpiry", tokens.absoluteExpiresAt.toString())
            .apply {
                tokens.registrationIdentity?.let { identity ->
                    put("registrationIdentity", JSONObject().put("username", identity.username).put("email", identity.email))
                }
            }
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(server.toByteArray(Charsets.UTF_8))

        writeBytes(byteArrayOf(1) + cipher.iv + cipher.doFinal(plain))
    }

    override fun clear() {
        // Recuperar primero cualquier respaldo legado para que no pueda desplazar el tombstone.
        file.openSessionRead()?.close()
        writeBytes(ByteArray(0))
        if (file.openSessionRead()?.use { it.read() } != -1) {
            throw IOException("No se pudo confirmar que la sesión se retiró del almacenamiento.")
        }
    }

    private fun writeBytes(bytes: ByteArray) {
        val stream = file.startWrite()

        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        if (file.openSessionRead()?.use { it.readBytesLimited().contentEquals(bytes) } != true) {
            throw IOException("No se pudo confirmar la escritura de la sesión.")
        }
        file.syncSessionDirectory()
    }

    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)

        while (true) {
            val count = read(buffer)

            if (count < 0) return output.toByteArray()

            require(output.size() + count <= 16384)
            output.write(buffer, 0, count)
        }
    }
}
