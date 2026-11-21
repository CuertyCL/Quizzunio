package cl.quizzunio

import android.content.Context
import android.content.ContextWrapper
import android.security.NetworkSecurityPolicy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.auth.AuthViewModel
import cl.quizzunio.auth.SessionState
import cl.quizzunio.settings.AppSettings
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
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
        if (auth.state.value.session is SessionState.Unavailable) {
            clickText("Abandonar sesión local y continuar como anónimo")
            ui.waitUntil(30_000) { auth.state.value.session is SessionState.Anonymous && !auth.state.value.busy }
        }
        dismissDialogs()

        ui.onNodeWithContentDescription("Ajustes").performClick()
        ui.waitForIdle()
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

        // Restaurar valor predeterminado para dejar el emulador limpio.
        ui.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextReplacement("https://10.0.2.2:8443")
        clickText("Aplicar servidor")
        ui.waitUntil(15_000) { auth.state.value.server == "https://10.0.2.2:8443" && !auth.state.value.busy }
        assertFalse(auth.client.configuration.isCleartext)
    }

    @Test fun settingsCanSwitchAndPersistBothThemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AppSettings.getInstance(context)
        val initial = runBlocking { settings.isDarkMode.first() }
        lateinit var auth: AuthViewModel
        ui.runOnIdle { auth = ViewModelProvider(ui.activity)[AuthViewModel::class.java] }
        ui.waitUntil(30_000) { auth.state.value.session is SessionState.Anonymous && !auth.state.value.busy }
        dismissDialogs()
        ui.onNodeWithContentDescription("Ajustes").performClick()
        try {
            for (dark in listOf(!initial, initial)) {
                val label = if (dark) "Oscuro" else "Claro"
                clickText("Tema de la aplicación")
                ui.waitUntil(10_000) { ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(dark, runBlocking { settings.isDarkMode.first() })
            }
        } finally {
            runBlocking { settings.setDarkMode(initial) }
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
