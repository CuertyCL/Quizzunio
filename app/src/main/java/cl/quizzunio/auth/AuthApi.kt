package cl.quizzunio.auth

import cl.quizzunio.network.*
import java.time.Instant
import java.time.format.DateTimeParseException
import org.json.JSONObject

class AuthApi(private val client: ApiClient) : AuthGateway {
    override suspend fun login(identifier: String, password: String): TokenPair {
        val canonicalIdentifier = CredentialValidator.canonicalIdentifier(identifier)
        CredentialValidator.password(password, false)
        return tokens(client.request("POST", "/auth/login", 200, JSONObject().put("identifier", canonicalIdentifier).put("password", password)))
    }
    override suspend fun register(username: String, email: String, password: String): TokenPair {
        val canonicalUsername = CredentialValidator.canonicalUsername(username)
        CredentialValidator.email(email)
        CredentialValidator.password(password, true)
        return tokens(client.request("POST", "/auth/register", 201, JSONObject().put("username", canonicalUsername).put("email", email).put("password", password)))
    }
    override suspend fun refresh(refreshToken: String): TokenPair = tokens(client.request("POST", "/auth/refresh", 200, JSONObject().put("refreshToken", refreshToken)))
    override suspend fun me(token: String): UserIdentity = decodeResponse {
        val value = JSONObject(client.request("GET", "/auth/me", 200, token = token))
        val id = value.longValue("userId"); require(id > 0)
        UserIdentity(id, value.stringValue("username"), value.stringValue("email"), UserTier.valueOf(value.stringValue("tier")))
    }
    override suspend fun logout(token: String) { client.request("POST", "/auth/logout", 204, token = token) }
    override suspend fun activity(token: String) { client.request("POST", "/auth/activity", 204, token = token) }
    override suspend fun changePassword(token: String, current: String, replacement: String) {
        CredentialValidator.password(current, false); CredentialValidator.password(replacement, true)
        client.request("POST", "/auth/change-password", 204, JSONObject().put("currentPassword", current).put("newPassword", replacement), token)
    }
    override suspend fun deleteAccount(token: String, current: String) {
        CredentialValidator.password(current, false)
        client.request("DELETE", "/auth/account", 204, JSONObject().put("currentPassword", current), token)
    }
    private fun tokens(text: String): TokenPair = decodeResponse {
        val value = JSONObject(text)
        require(value.stringValue("tokenType") == "Bearer")
        val access = value.stringValue("accessToken"); val refresh = value.stringValue("refreshToken")
        val pattern = Regex("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")
        require(pattern.matches(access) && pattern.matches(refresh) && access != refresh)
        try {
            val accessExpiry = Instant.parse(value.stringValue("accessExpiresAt"))
            val refreshExpiry = Instant.parse(value.stringValue("refreshExpiresAt"))
            val absolute = Instant.parse(value.stringValue("absoluteExpiresAt"))
            require(accessExpiry <= absolute && refreshExpiry <= absolute)
            TokenPair(access, refresh, accessExpiry, refreshExpiry, absolute)
        } catch (error: DateTimeParseException) { throw IllegalArgumentException("Fecha inválida.") }
    }
}
