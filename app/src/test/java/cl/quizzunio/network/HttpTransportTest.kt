package cl.quizzunio.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** HTTP real con servidor efímero; no certifica la integración con QuizzunioServer. */
class HttpTransportTest {
    @Test fun configurationRequiresHttpsOutsideExplicitDebugLoopback() {
        assertEquals("https://example.com:8443", ApiConfiguration("https://example.com:8443/").baseUrl)
        assertEquals("http://10.0.2.2:8080", ApiConfiguration("http://10.0.2.2:8080", true).baseUrl)
        listOf("http://example.com", "https://user:secret@example.com", "https://example.com/a", "https://example.com?secret=x").forEach {
            assertThrows(IllegalArgumentException::class.java) { ApiConfiguration(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { ApiConfiguration("http://10.0.2.2:8080") }
    }

    @Test fun sendsUtf8AndBearerAndDoesNotFollowRedirects() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/echo") { exchange ->
            assertEquals("Bearer opaque", exchange.requestHeaders.getFirst("Authorization"))
            val body = exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.set("Location", "/must-not-run"); exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/must-not-run") { error("No debe seguir redirecciones con Bearer") }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val transport = UrlConnectionTransport()
            val response = transport.send("POST", "$base/echo", mapOf("Authorization" to "Bearer opaque"), "{\"texto\":\"Biología ñ 🧠\"}")
            assertEquals(200, response.status); assertEquals("{\"texto\":\"Biología ñ 🧠\"}", response.body)
            assertEquals(302, transport.send("GET", "$base/redirect", mapOf("Authorization" to "Bearer opaque"), null).status)
        } finally { server.stop(0) }
    }

    @Test fun preservesErrorStatusAndRetryAfterWithoutRetrying() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val status = exchange.requestURI.path.drop(1).toInt()
            val body = "{\"code\":\"TEST_ERROR\",\"message\":\"Error\"}".toByteArray()
            exchange.responseHeaders.set("Retry-After", "31")
            exchange.sendResponseHeaders(status, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            for (status in listOf(400, 401, 403, 404, 409, 429, 500, 502, 503)) {
                val response = UrlConnectionTransport().send("GET", "http://127.0.0.1:${server.address.port}/$status", emptyMap(), null)
                assertEquals(status, response.status); assertEquals(31L, response.retryAfterSeconds)
                assertTrue(response.body.contains("TEST_ERROR"))
            }
        } finally { server.stop(0) }
    }

    @Test fun cancellationDoesNotWaitForTheReadTimeout() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> entered.countDown(); release.await(5, TimeUnit.SECONDS); exchange.close() }
        server.start()
        try {
            val request = launch(Dispatchers.Default) { UrlConnectionTransport(readTimeoutMillis = 30000).send("GET", "http://127.0.0.1:${server.address.port}/", emptyMap(), null) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            withTimeout(2000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
        } finally { release.countDown(); server.stop(0) }
    }
}
