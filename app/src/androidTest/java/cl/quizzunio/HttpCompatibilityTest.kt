package cl.quizzunio

import android.content.Context
import android.content.ContextWrapper
import android.security.NetworkSecurityPolicy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.auth.AuthViewModel
import cl.quizzunio.auth.LocalAuthViewModel
import cl.quizzunio.auth.SessionState
import cl.quizzunio.settings.AppSettings
import cl.quizzunio.settings.ThemeMode
import cl.quizzunio.ui.navigation.AppNavHost
import cl.quizzunio.ui.theme.QuizzunioTheme
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifica política HTTP/HTTPS en tiempo de ejecución Android, persistencia y confirmación UI explícita. */
@RunWith(AndroidJUnit4::class)
class HttpCompatibilityTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val root = File(base.noBackupFilesDir, "http-compat-$suffix").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(root, "nobackup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("${name}_$suffix", mode)
        }
    }

    @Test fun networkSecurityConfigPermitsConfiguredCleartextTargets() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertTrue(policy.isCleartextTrafficPermitted("10.0.2.2"))
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
        assertTrue(policy.isCleartextTrafficPermitted("192.168.1.100"))
        assertTrue(policy.isCleartextTrafficPermitted("10.8.0.1"))
        assertTrue(policy.isCleartextTrafficPermitted("quizzunio.local"))
    }

    @Test fun authViewModelRequiresHttpConfirmationAndPersistsAcrossRestarts() {
        val context = isolatedContext()
        val first = AuthViewModel(context)
        waitUntil { first.state.value.session is SessionState.Anonymous && !first.state.value.busy }
        assertEquals("https://10.0.2.2:8443", first.state.value.server)

        // Sin confirmación explícita, HTTP debe rechazarse.
        first.configureServer("http://192.168.1.100:8080")
        waitUntil { !first.state.value.busy && first.state.value.message != null }
        assertEquals("https://10.0.2.2:8443", first.state.value.server)
        first.clearMessage()

        // Con confirmación explícita, se aplica y se conserva al reiniciar (nueva instancia de AuthViewModel).
        first.configureServer("http://192.168.1.100:8080", confirmCleartext = true)
        waitUntil { !first.state.value.busy && first.state.value.server == "http://192.168.1.100:8080" }

        val restarted = AuthViewModel(context)
        waitUntil { restarted.state.value.session is SessionState.Anonymous && !restarted.state.value.busy }
        assertEquals("http://192.168.1.100:8080", restarted.state.value.server)

        // HTTPS para Quick Tunnel no requiere confirmación HTTP y conserva la URL sin agregar :8443.
        restarted.configureServer("https://ejemplo.trycloudflare.com")
        waitUntil { !restarted.state.value.busy && restarted.state.value.server == "https://ejemplo.trycloudflare.com" }
        assertNull(restarted.state.value.message)

        val afterHttps = AuthViewModel(context)
        waitUntil { afterHttps.state.value.session is SessionState.Anonymous && !afterHttps.state.value.busy }
        assertEquals("https://ejemplo.trycloudflare.com", afterHttps.state.value.server)
    }

    @Test fun androidRuntimeConnectsToExplicitlyConfirmedHttpServer() {
        val context = isolatedContext()
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val body = "{\"server\":{\"status\":\"UP\",\"transport\":\"HTTP\",\"uptimeSeconds\":3},\"database\":{\"status\":\"UP\",\"dbms\":\"mariadb\",\"connected\":true}}"
        executor.submit {
            socket.accept().use { client ->
                val reader = client.getInputStream().bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                client.getOutputStream().flush()
            }
        }
        try {
            val viewModel = AuthViewModel(context)
            waitUntil { viewModel.state.value.session is SessionState.Anonymous && !viewModel.state.value.busy }
            viewModel.configureServer("http://127.0.0.1:${socket.localPort}", confirmCleartext = true)
            waitUntil { !viewModel.state.value.busy && viewModel.state.value.server == "http://127.0.0.1:${socket.localPort}" }

            viewModel.checkStatus()
            waitUntil { !viewModel.state.value.busy && viewModel.state.value.status != null }
            assertTrue(viewModel.state.value.status.orEmpty().contains("Servidor: UP"))
            assertTrue(viewModel.state.value.status.orEmpty().contains("HTTP"))
        } finally {
            socket.close()
            executor.shutdownNow()
        }
    }

    @Test fun settingsUiPromptsForHttpConfirmationAndAppliesHttpsDirectly() {
        lateinit var auth: AuthViewModel
        ui.runOnIdle { auth = ViewModelProvider(ui.activity)[AuthViewModel::class.java] }
        ui.waitUntil(30_000) { auth.state.value.session !is SessionState.Restoring && !auth.state.value.busy }
        assertTrue(auth.state.value.session is SessionState.Anonymous)
        dismissDialogs()

        ui.onNodeWithContentDescription("Ajustes").performClick()
        ui.waitForIdle()
        ui.onNodeWithText("URL del servidor").assertDoesNotExist()
        clickText("Servidor")
        val initialServer = auth.state.value.server

        // Configurar HTTP LAN muestra advertencia y Cancelar no modifica la URL.
        ui.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextReplacement("http://192.168.1.100:8080")
        clickText("Aplicar servidor")
        ui.onNodeWithText("Conexión HTTP sin cifrado").assertExists()
        clickText("Cancelar")
        assertEquals(initialServer, auth.state.value.server)

        // Confirmar uso de HTTP sí aplica y persiste la dirección.
        clickText("Aplicar servidor")
        ui.onNodeWithText("Conexión HTTP sin cifrado").assertExists()
        clickText("Confirmar uso de HTTP")
        ui.waitUntil(15_000) { auth.state.value.server == "http://192.168.1.100:8080" && !auth.state.value.busy }

        // Cambiar a HTTPS de Cloudflare Quick Tunnel aplica directamente sin diálogo HTTP.
        ui.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextReplacement("https://ejemplo.trycloudflare.com")
        clickText("Aplicar servidor")
        assertTrue(ui.onAllNodesWithText("Conexión HTTP sin cifrado").fetchSemanticsNodes().isEmpty())
        ui.waitUntil(15_000) { auth.state.value.server == "https://ejemplo.trycloudflare.com" && !auth.state.value.busy }

        // Restaurar la URL que tenía el emulador antes de la prueba.
        ui.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextReplacement(initialServer)
        clickText("Aplicar servidor")
        if (initialServer.startsWith("http://")) clickText("Confirmar uso de HTTP")
        ui.waitUntil(15_000) { auth.state.value.server == initialServer && !auth.state.value.busy }
        assertEquals(initialServer.startsWith("http://"), auth.client.configuration.isCleartext)
    }

    @Test fun settingsCanSwitchAndPersistBothThemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AppSettings.getInstance(context)
        val initial = runBlocking { settings.themeMode.first() }
        val stored = runBlocking { settings.getString(AppSettings.Keys.KEY_THEME_MODE).first() }
        lateinit var auth: AuthViewModel
        ui.runOnIdle { auth = ViewModelProvider(ui.activity)[AuthViewModel::class.java] }
        ui.waitUntil(30_000) { auth.state.value.session is SessionState.Anonymous && !auth.state.value.busy }
        dismissDialogs()
        ui.onNodeWithContentDescription("Ajustes").performClick()
        try {
            for (mode in ThemeMode.entries) {
                clickText("Tema de la aplicación")
                ui.onNode(hasText(mode.label) and hasClickAction() and hasAnyAncestor(isDialog()))
                    .performClick()
                ui.waitUntil(10_000) { runBlocking { settings.themeMode.first() } == mode }
                ui.onNodeWithText(mode.label).assertExists()
                val systemDark = ui.activity.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                ui.runOnIdle {
                    val window = ui.activity.window
                    assertEquals(!mode.isDark(systemDark), androidx.core.view.WindowCompat
                        .getInsetsController(window, window.decorView).isAppearanceLightStatusBars)
                }
            }
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText(ThemeMode.Dark.label).assertExists()
        } finally {
            runBlocking {
                if (stored.isEmpty()) settings.removePreference(AppSettings.Keys.KEY_THEME_MODE)
                else settings.setPreference(AppSettings.Keys.KEY_THEME_MODE, stored)
                assertEquals(initial, settings.themeMode.first())
            }
        }
    }

    @Test fun authenticatedSettingsShowsVerifiedProfileAndLogsOutThroughHttp() {
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val logoutReceived = AtomicBoolean(false)
        val access = "A".repeat(43)
        val expiry = java.time.Instant.now().plusSeconds(3600).toString()
        executor.submit {
            try {
                while (!socket.isClosed) socket.accept().use { client ->
                    val input = client.getInputStream().bufferedReader(Charsets.UTF_8)
                    val request = input.readLine().orEmpty()
                    val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
                    val length = headers.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    repeat(length) { input.read() }
                    val authorized = headers.any { it.equals("Authorization: Bearer $access", ignoreCase = true) }
                    val (status, body) = when {
                        request.startsWith("POST /auth/login ") -> "200 OK" to
                            """{"tokenType":"Bearer","accessToken":"$access","refreshToken":"${"E".repeat(43)}","accessExpiresAt":"$expiry","refreshExpiresAt":"$expiry","absoluteExpiresAt":"$expiry"}"""
                        !authorized -> "401 Unauthorized" to """{"code":"INVALID_TOKEN"}"""
                        request.startsWith("GET /auth/me ") -> "200 OK" to
                            """{"userId":731,"username":"Perfil Ñ","email":"perfil@example.test","tier":"Premium"}"""
                        request.startsWith("GET /quizzes?") -> "200 OK" to """{"page":1,"pageSize":20,"quizzes":[]}"""
                        request.startsWith("POST /auth/logout ") -> {
                            logoutReceived.set(true)
                            "204 No Content" to ""
                        }
                        else -> "404 Not Found" to ""
                    }
                    val payload = body.toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(payload)
                        flush()
                    }
                }
            } catch (error: java.net.SocketException) {
                if (!socket.isClosed) throw error
            }
        }
        try {
            val auth = AuthViewModel(isolatedContext())
            waitUntil { auth.state.value.session is SessionState.Anonymous && !auth.state.value.busy }
            auth.configureServer("http://127.0.0.1:${socket.localPort}", confirmCleartext = true)
            waitUntil { auth.state.value.server.endsWith(":${socket.localPort}") && !auth.state.value.busy }
            auth.login("perfil", "PruebaLocal-123!")
            waitUntil { auth.state.value.session is SessionState.Authenticated && !auth.state.value.busy }
            val dark = mutableStateOf(false)
            ui.runOnUiThread {
                ui.activity.setContent {
                    QuizzunioTheme(darkTheme = dark.value) {
                        CompositionLocalProvider(LocalAuthViewModel provides auth) {
                            AppNavHost(startDestination = "settings")
                        }
                    }
                }
            }
            ui.waitUntil(15_000) { ui.onAllNodesWithText("Servidor").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Nombre de usuario: Perfil Ñ").assertDoesNotExist()
            clickText("Cuenta")
            ui.waitUntil(15_000) { ui.onAllNodesWithText("Nombre de usuario: Perfil Ñ").fetchSemanticsNodes().isNotEmpty() }
            for (isDark in listOf(false, true)) {
                ui.runOnIdle { dark.value = isDark }
                ui.onNodeWithText("Nombre de usuario: Perfil Ñ").assertExists()
                ui.onNodeWithText("Correo electrónico: perfil@example.test").assertExists()
                ui.onNodeWithText("Suscripción: Premium").assertExists()
                ui.onNodeWithText("URL del servidor").assertDoesNotExist()
                ui.onAllNodesWithText("Cerrar sesión").assertCountEquals(1)
            }
            clickText("Cerrar sesión")
            ui.waitUntil(15_000) { auth.state.value.session is SessionState.Anonymous && !auth.state.value.busy }
            assertTrue(logoutReceived.get())
            ui.onNodeWithText("Nombre de usuario: Perfil Ñ").assertDoesNotExist()
        } finally {
            socket.close()
            executor.shutdownNow()
        }
    }

    private fun clickText(text: String) {
        val node = ui.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
        ui.waitForIdle()
    }

    private fun dismissDialogs() {
        repeat(3) {
            if (ui.onAllNodesWithText("Entendido").fetchSemanticsNodes().isNotEmpty()) clickText("Entendido")
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("La condición no se cumplió dentro del tiempo esperado.")
    }
}
