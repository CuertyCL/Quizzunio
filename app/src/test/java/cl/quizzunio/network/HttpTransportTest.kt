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
    @Test fun configurationAllowsHttpsAndExplicitHttpAcrossTopologiesAndRejectsMalformed() {
        assertEquals("https://example.com:8443", ApiConfiguration("https://example.com:8443/").baseUrl)
        assertFalse(ApiConfiguration("https://example.com:8443/").isCleartext)

        val tunnel = ApiConfiguration("https://ejemplo.trycloudflare.com")
        assertEquals("https://ejemplo.trycloudflare.com", tunnel.baseUrl)
        assertFalse(tunnel.isCleartext)

        listOf(
            "http://10.0.2.2:8080" to true,
            "http://10.0.2.2:8080" to false,
            "http://127.0.0.1:8080" to false,
            "http://192.168.1.100:8080" to false,
            "http://10.8.0.1:8080" to false,
            "http://quizzunio.local:8080" to false,
            "http://vpn-server:8080" to false,
            "http://[::1]:8080" to false
        ).forEach { (url, dev) ->
            val config = ApiConfiguration(url, dev)
            assertEquals(url, config.baseUrl)
            assertTrue(config.isCleartext)
        }

        listOf(
            "",
            "   ",
            "http://",
            "ftp://192.168.1.100:8080",
            "https://user:secret@example.com",
            "http://user:secret@192.168.1.100:8080",
            "https://example.com/a",
            "https://example.com?secret=x",
            "http://192.168.1.100:8080#frag",
            "http://192.168.1.100:0",
            "http://192.168.1.100:70000",
            "http://192.168.1.100:abc"
        ).forEach { malformed ->
            assertThrows("Debe rechazar $malformed", IllegalArgumentException::class.java) {
                ApiConfiguration(malformed)
            }
        }
    }

    @Test fun releaseConfigurationExecutesHttpAndDoesNotDowngradeFailedHttpsToHttp() = runBlocking {
        var httpHits = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/status") { exchange ->
            httpHits++
            val body = "{\"server\":{\"status\":\"UP\",\"transport\":\"HTTP\",\"uptimeSeconds\":1},\"database\":{\"status\":\"UP\",\"dbms\":\"mariadb\",\"connected\":true}}".toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val port = server.address.port
            // Simula compilación Release (development = false) usando HTTP explícito.
            val httpClient = ApiClient(ApiConfiguration("http://127.0.0.1:$port", development = false), UrlConnectionTransport())
            val body = httpClient.request("GET", "/status", 200)
            assertTrue(body.contains("\"transport\":\"HTTP\""))
            assertEquals(1, httpHits)

            // HTTPS contra un puerto que habla HTTP plano debe fallar TLS sin hacer downgrade automático a HTTP.
            val httpsClient = ApiClient(ApiConfiguration("https://127.0.0.1:$port", development = false), UrlConnectionTransport(readTimeoutMillis = 1500))
            try {
                httpsClient.request("GET", "/status", 200)
                fail("HTTPS no debe hacer fallback silencioso a HTTP")
            } catch (expected: ApiException) {
                assertEquals(0, expected.status)
                assertEquals("NETWORK_ERROR", expected.code)
            }
            assertEquals(1, httpHits)
        } finally { server.stop(0) }
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

    @Test fun limitsQueryUsesBearerAndDisablesCacheWithoutSendingMaterial() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val count = java.util.concurrent.atomic.AtomicInteger()
        server.createContext("/quizzes/limits") { exchange ->
            val valid = exchange.requestMethod == "GET" &&
                exchange.requestHeaders.getFirst("Authorization") == "Bearer exact-token" &&
                exchange.requestHeaders.getFirst("Cache-Control") == "no-store" &&
                exchange.requestBody.use { it.read() == -1 }
            val bytes = """{"maxTextBytes":${count.incrementAndGet()},"encoding":"UTF-8"}""".toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendResponseHeaders(if (valid) 200 else 400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = ApiClient(ApiConfiguration("http://127.0.0.1:${server.address.port}"), UrlConnectionTransport())
            for (limit in 1..2) {
                assertEquals("""{"maxTextBytes":$limit,"encoding":"UTF-8"}""",
                    client.request("GET", "/quizzes/limits", 200, token = "exact-token"))
            }
            assertEquals(2, count.get())
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
