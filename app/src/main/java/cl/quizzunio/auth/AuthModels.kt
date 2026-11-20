package cl.quizzunio.auth

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
    fun username(value: String) { require(Regex("[A-Za-z0-9_-]{3,32}").matches(value)) { "Usuario: 3–32 letras ASCII, números, guion o guion bajo." } }
    fun email(value: String) {
        val parts = value.split('@')
        require(ascii(value) && value.length <= 255 && parts.size == 2) { "Escribe un correo ASCII válido." }
        val local = parts[0]; val domain = parts[1].split('.')
        require(local.length in 1..64 && local.split('.').all { atom -> atom.isNotEmpty() && atom.all { it.isLetterOrDigit() || it in "!#$%&'*+-/=?^_`{|}~" } } &&
            domain.size >= 2 && domain.all { label -> label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() && label.all { it.isLetterOrDigit() || it == '-' } }) {
            "Escribe un correo ASCII válido."
        }
    }
    fun password(value: String, isNew: Boolean) {
        require(ascii(value) && value.codePointCount(0, value.length) in (if (isNew) 12 else 1)..128) {
            if (isNew) "La contraseña debe contener 12–128 caracteres ASCII, sin recortar espacios."
            else "Escribe la contraseña actual (hasta 128 caracteres ASCII)."
        }
    }
    fun identifier(value: String) { if ('@' in value) email(value) else username(value) }
}
