package cl.quizzunio.auth

import cl.quizzunio.core.hasValidUnicode
import java.text.Normalizer
import java.time.Instant

enum class UserTier { Free, Premium }
enum class GenerationSource { Local, Server }
data class UserIdentity(val userId: Long, val username: String, val email: String, val tier: UserTier)
sealed class SessionState {
    object Restoring : SessionState()
    object Anonymous : SessionState()
    data class Authenticated(val user: UserIdentity) : SessionState()
    data class Unavailable(val message: String) : SessionState()
}

object AccessPolicy {
    fun canUseCloud(session: SessionState): Boolean = session is SessionState.Authenticated
    fun canGenerateRemotely(session: SessionState): Boolean = (session as? SessionState.Authenticated)?.user?.tier == UserTier.Premium
    fun defaultSource(session: SessionState): GenerationSource = if (canGenerateRemotely(session)) GenerationSource.Server else GenerationSource.Local
}

class TokenPair(val accessToken: String, val refreshToken: String, val accessExpiresAt: Instant,
                val refreshExpiresAt: Instant, val absoluteExpiresAt: Instant) {
    override fun toString(): String = "TokenPair[protegido]"
}

interface AuthGateway {
    suspend fun login(identifier: String, password: String): TokenPair
    suspend fun register(username: String, email: String, password: String): TokenPair
    suspend fun refresh(refreshToken: String): TokenPair
    suspend fun me(token: String): UserIdentity
    suspend fun logout(token: String)
    suspend fun activity(token: String)
    suspend fun changePassword(token: String, current: String, replacement: String)
    suspend fun deleteAccount(token: String, current: String)
}

object CredentialValidator {
    fun ascii(value: String): Boolean = value.all { it.code <= 127 }
    fun username(value: String) { canonicalUsername(value) }

    internal fun canonicalUsername(value: String): String {
        require(value.length <= 256 && hasUsernameCharacters(value)) { USERNAME_ERROR }
        val canonical = Normalizer.normalize(value, Normalizer.Form.NFC)
        require(canonical.codePointCount(0, canonical.length) in 3..32 && hasUsernameCharacters(canonical)) {
            USERNAME_ERROR
        }
        return canonical
    }

    private fun hasUsernameCharacters(value: String): Boolean {
        var letterBase = false
        var offset = 0
        while (offset < value.length) {
            val point = value.codePointAt(offset)
            offset += Character.charCount(point)
            // Algunos caracteres invisibles tienen categoría de letra o marca.
            if (point in INVISIBLE_USERNAME_POINTS || point in 0x180B..0x180F ||
                point in 0xFE00..0xFE0F || point in 0xE0100..0xE01EF
            ) return false
            when {
                Character.isLetter(point) -> letterBase = true
                Character.isDigit(point) || point == '-'.code || point == '_'.code -> letterBase = false
                !letterBase || Character.getType(point) !in COMBINING_MARK_TYPES -> return false
            }
        }
        return true
    }

    fun email(value: String) {
        val parts = value.split('@')
        require(ascii(value) && value.length <= 255 && parts.size == 2) { "Escribe un correo ASCII válido." }
        val local = parts[0]; val domain = parts[1].split('.')
        require(local.length in 1..64 && local.split('.').all { atom -> atom.isNotEmpty() && atom.all { it.isLetterOrDigit() || it in "!#$%&'*+-/=?^_`{|}~" } } &&
            parts[1].length <= 253 && domain.size >= 2 && domain.all { label -> label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() && label.all { it.isLetterOrDigit() || it == '-' } }) {
            "Escribe un correo ASCII válido."
        }
    }
    fun password(value: String, isNew: Boolean) {
        require(value.hasValidUnicode() && value.codePointCount(0, value.length) in (if (isNew) 12 else 0)..128) {
            if (isNew) "La contraseña debe contener 12–128 puntos de código Unicode válidos."
            else "La contraseña actual admite hasta 128 puntos de código Unicode válidos."
        }
    }
    fun identifier(value: String) { canonicalIdentifier(value) }

    internal fun canonicalIdentifier(value: String): String {
        if ('@' !in value) return canonicalUsername(value)
        email(value)
        return value
    }

    private const val USERNAME_ERROR =
        "Usuario: 3–32 puntos de código en NFC, con letras, dígitos, marcas tras letras, guion o guion bajo."
    private val INVISIBLE_USERNAME_POINTS = setOf(0x034F, 0x115F, 0x1160, 0x3164, 0xFFA0, 0x20E3)
    private val COMBINING_MARK_TYPES = setOf(
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt()
    )
}
