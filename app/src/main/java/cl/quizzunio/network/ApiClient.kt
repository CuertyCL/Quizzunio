package cl.quizzunio.network

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject

class ApiException(val status: Int, val code: String, message: String, val retryAfterSeconds: Long? = null) : IOException(message)

class ApiClient(val configuration: ApiConfiguration, private val transport: HttpTransport) {
    suspend fun request(method: String, path: String, expected: Int, body: JSONObject? = null,
                        token: String? = null, headers: Map<String, String> = emptyMap()): String {
        require(path.startsWith('/') && !path.startsWith("//"))
        val encoded = body?.toString()
        require(encoded == null || encoded.toByteArray(Charsets.UTF_8).size <= if (path.startsWith("/auth")) 16384 else 1_000_000) {
            "La solicitud supera el tamaño admitido por el servidor."
        }
        val response = try {
            transport.send(method, configuration.baseUrl + path,
                headers + if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap(), encoded)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: IOException) {
            throw ApiException(0, "NETWORK_ERROR", if (method == "GET") "No se pudo conectar con el servidor."
                else "No se recibió confirmación. El servidor puede haber completado la operación; consulta su estado antes de repetirla.")
        }
        if (response.status != expected) {
            val code = try { JSONObject(response.body).optString("code", "HTTP_ERROR") } catch (error: JSONException) { "HTTP_ERROR" }
            val message = when (response.status) {
                400 -> "El servidor rechazó los datos de la solicitud."
                401 -> if (code == "INVALID_CREDENTIALS") "Identificador o contraseña incorrectos." else "La sesión ya no es válida."
                403 -> if (code == "PREMIUM_REQUIRED") "La generación con IA requiere Premium." else "El servidor no permite esta operación o requiere HTTPS."
                404 -> "El recurso no existe o no pertenece a tu cuenta."
                409 -> when (code) {
                    "QUIZ_VERSION_CONFLICT" -> "El cuestionario cambió en el servidor. Conserva tus cambios y revisa la nueva versión."
                    "IDENTITY_CONFLICT" -> "Ese nombre de usuario o correo ya está registrado."
                    "IMPORT_KEY_CONFLICT" -> "La clave de importación ya corresponde a otro cuestionario."
                    "QUIZ_GENERATION_IN_PROGRESS" -> "Ya hay una generación de cuestionario en curso para esta cuenta."
                    else -> "La operación presenta un conflicto; consulta el estado antes de repetirla."
                }
                429 -> "Inicio de sesión bloqueado temporalmente. Espera ${response.retryAfterSeconds ?: "los"} segundos indicados por el servidor."
                500, 502, 503 -> "El servidor no pudo completar la operación ($code)."
                else -> "Respuesta HTTP inesperada (${response.status})."
            }
            throw ApiException(response.status, code, message, response.retryAfterSeconds)
        }
        if (expected == 204 && response.body.isNotEmpty()) throw ApiException(204, "INVALID_RESPONSE", "El servidor devolvió una confirmación inesperada.")
        return response.body
    }
}

inline fun <T> decodeResponse(block: () -> T): T = try { block() }
catch (error: JSONException) { throw ApiException(200, "INVALID_RESPONSE", "El servidor devolvió JSON inválido o incompleto.") }
catch (error: IllegalArgumentException) { throw ApiException(200, "INVALID_RESPONSE", "La respuesta no cumple el contrato del servidor.") }

fun JSONObject.stringValue(name: String): String = (get(name) as? String)
    ?: throw IllegalArgumentException("Campo de texto inválido.")

fun JSONObject.longValue(name: String): Long {
    val value = get(name)
    require(value is Int || value is Long) { "Identificador o versión inválidos." }
    return (value as Number).toLong()
}
