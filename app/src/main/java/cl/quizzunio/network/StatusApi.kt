package cl.quizzunio.network

import org.json.JSONObject

data class ServerStatus(val server: String, val database: String, val connected: Boolean, val transport: String) {
    val description: String get() = "Servidor: $server · Base de datos: $database · $transport. La disponibilidad de IA no se comprueba."
}

class StatusApi(private val client: ApiClient) {
    suspend fun read(): ServerStatus = decodeResponse {
        val json = JSONObject(client.request("GET", "/status", 200))
        val server = json.getJSONObject("server")
        val database = json.getJSONObject("database")
        val status = server.stringValue("status")
        val db = database.stringValue("status")
        val connected = database.get("connected") as? Boolean ?: throw IllegalArgumentException("Estado inválido")
        val transport = server.stringValue("transport")
        require(status in setOf("UP", "DEGRADED") && db in setOf("UP", "DOWN") && transport in setOf("HTTP", "HTTPS"))
        require((db == "UP") == connected && (status == "UP") == connected)
        ServerStatus(status, db, connected, transport)
    }
}
