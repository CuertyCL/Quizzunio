package cl.quizzunio.network

import cl.quizzunio.core.hasValidUnicode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiConfiguration(baseUrl: String, val development: Boolean = false) {
    val baseUrl: String
    val isCleartext: Boolean

    init {
        val trimmed = baseUrl.trim()
        val uri = try {
            URI(trimmed)
        } catch (error: URISyntaxException) {
            throw IllegalArgumentException("Escribe una URL válida de servidor (https:// o http://).", error)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)

        require(scheme == "https" || scheme == "http") { "Usa una URL con esquema https:// o http://." }
        require(uri.isAbsolute && !uri.isOpaque) { "Escribe una URL completa con formato https://host o http://host." }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Escribe una URL de servidor sin credenciales, parámetros ni fragmentos."
        }
        require(uri.port == -1 || uri.port in 1..65535) { "El puerto del servidor debe estar entre 1 y 65535." }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "La URL debe indicar la raíz del servidor." }

        this.isCleartext = scheme == "http"
        this.baseUrl = URI(scheme, null, uri.host, uri.port, null, null, null)
            .toASCIIString()
            .trimEnd('/')
    }
}

data class HttpResponse(
    val status: Int,
    val body: String,
    val retryAfterSeconds: Long? = null
)

internal class ResponseTooLargeException : IOException()

interface HttpTransport {
    suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse
}

/** No sigue redirecciones: un Bearer nunca debe reenviarse a otro origen. */
class UrlConnectionTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 120_000,
    private val maxResponseBytes: Int = 32 * 1024 * 1024
) : HttpTransport {
    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
        suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<HttpURLConnection?>()

            continuation.invokeOnCancellation { active.get()?.disconnect() }

            Dispatchers.IO.dispatch(continuation.context, Runnable {
                if (!continuation.isActive) return@Runnable

                try {
                    val connection = URI(url).toURL().openConnection() as HttpURLConnection

                    active.set(connection)

                    if (!continuation.isActive) { connection.disconnect(); return@Runnable }

                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.connectTimeout = connectTimeoutMillis
                    connection.readTimeout = readTimeoutMillis
                    connection.requestMethod = method
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Cache-Control", "no-store")

                    headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }

                    if (body != null) {
                        require(body.hasValidUnicode()) { "La solicitud contiene una secuencia Unicode inválida." }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { it.write(bytes) }
                    }

                    val status = connection.responseCode

                    if (connection.contentLengthLong > maxResponseBytes) throw ResponseTooLargeException()

                    val stream = if (status >= 400) connection.errorStream else connection.inputStream
                    val bytes = stream?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (!continuation.isActive) return@Runnable

                            val count = input.read(buffer)

                            if (count < 0) break
                            if (output.size() + count > maxResponseBytes) throw ResponseTooLargeException()

                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: ByteArray(0)

                    val text = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()

                    val result = HttpResponse(status, text, connection.getHeaderField("Retry-After")?.toLongOrNull())

                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally { active.getAndSet(null)?.disconnect() }
            })
        }
}
