package cl.quizzunio.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
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

/** AES-GCM con clave no exportable y archivo excluido de copias y transferencias. */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "session.enc"))
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
        if (!file.baseFile.exists()) return null

        try {
            val bytes = file.openRead().use { it.readBytesLimited() }

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
            )
        } catch (error: GeneralSecurityException) {
            throw IOException("No se pudo descifrar la sesión. Vuelve a iniciar sesión.")
        } catch (error: IllegalArgumentException) {
            throw IOException("La sesión guardada está dañada.")
        } catch (error: org.json.JSONException) {
            throw IOException("La sesión guardada está incompleta.")
        }
    }

    override fun write(server: String, tokens: TokenPair) {
        val plain = JSONObject().put("server", server)
            .put("access", tokens.accessToken)
            .put("refresh", tokens.refreshToken)
            .put("accessExpiry", tokens.accessExpiresAt.toString())
            .put("refreshExpiry", tokens.refreshExpiresAt.toString())
            .put("absoluteExpiry", tokens.absoluteExpiresAt.toString())
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(server.toByteArray(Charsets.UTF_8))

        writeBytes(byteArrayOf(1) + cipher.iv + cipher.doFinal(plain))
    }

    override fun clear() {
        writeBytes(ByteArray(0))
        if (file.openRead().use { it.read() } != -1) {
            throw IOException("No se pudo confirmar que la sesión se retiró del almacenamiento.")
        }
    }

    private fun writeBytes(bytes: ByteArray) {
        val stream = file.startWrite()

        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
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
