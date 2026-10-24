package com.manfis.astramatic.desktop

import com.manfis.astramatic.remote.PlayerStatus
import com.manfis.astramatic.remote.RemoteApi
import com.manfis.astramatic.remote.RemoteCommand
import com.manfis.astramatic.remote.ServerInfo
import com.manfis.astramatic.remote.SharedFolder
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/** Телефоны, которые сейчас в плеере и держат соединение: их состояние и отправка команд. */
class RemoteHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
    private val _statuses = MutableStateFlow<Map<String, PlayerStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, PlayerStatus>> = _statuses

    suspend fun serve(session: DefaultWebSocketServerSession) {
        val id = UUID.randomUUID().toString()
        sessions[id] = session
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val status = runCatching { RemoteApi.json.decodeFromString<PlayerStatus>(frame.readText()) }.getOrNull() ?: continue
                _statuses.update { it + (id to status) }
            }
        } finally {
            sessions.remove(id)
            _statuses.update { it - id }
        }
    }

    /** Команда всем подключённым телефонам (обычно он один). */
    fun send(command: RemoteCommand) {
        val text = RemoteApi.json.encodeToString(RemoteCommand.serializer(), command)
        sessions.values.forEach { session -> scope.launch { runCatching { session.send(Frame.Text(text)) } } }
    }
}

/** HTTP-сервер для телефона: список общих папок, файлы с перемоткой, канал пульта. */
class MediaServer(private val config: () -> DesktopConfig, private val hub: RemoteHub) {
    private var engine: EmbeddedServer<*, *>? = null

    /** null — работает; иначе почему не запустился. */
    val error = MutableStateFlow<String?>(null)

    fun start() {
        stop()
        val port = config().port
        error.value = try {
            engine = embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }.start(wait = false)
            null
        } catch (e: Exception) {
            if (e.message?.contains("in use", ignoreCase = true) == true) "Порт $port занят другой программой" else e.message
        }
    }

    fun stop() {
        engine?.stop(500, 1500)
        engine = null
    }

    private fun Application.module() {
        install(WebSockets) {
            pingPeriod = 15.seconds
            timeout = 30.seconds
        }
        install(PartialContent)
        install(AutoHeadResponse)

        routing {
            // Ссылка из QR-кода — не для браузера, её открывает приложение.
            get("/pair") {
                call.respondText(
                    "Это ссылка подключения для приложения AstraMatic: отсканируйте QR-код в нём.",
                    ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                )
            }

            get("/api/info") {
                if (!call.authorized()) return@get
                val info = ServerInfo(
                    name = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("ПК"),
                    folders = config().folders.map { SharedFolder(it.id, it.name) },
                )
                call.respondJson(RemoteApi.json.encodeToString(info))
            }

            get("/api/list") {
                if (!call.authorized()) return@get
                val folder = config().folders.find { it.id == call.request.queryParameters["folder"] }
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Папка не найдена")
                val entries = FolderBrowser.list(folder, call.request.queryParameters["path"].orEmpty())
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Папка не найдена")
                call.respondJson(RemoteApi.json.encodeToString(entries))
            }

            get("/file/{folder}/{path...}") {
                if (!call.authorized()) return@get
                val folder = config().folders.find { it.id == call.parameters["folder"] }
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                val file = FolderBrowser.resolve(folder, call.parameters.getAll("path").orEmpty().joinToString("/"))
                    ?.takeIf { it.isFile }
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondFile(file)
            }

            webSocket("/ws") {
                if (!call.authorized(respond = false)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Нет доступа"))
                    return@webSocket
                }
                hub.serve(this)
            }
        }
    }

    private suspend fun ApplicationCall.authorized(respond: Boolean = true): Boolean {
        val ok = request.headers["Authorization"] == "Bearer ${config().key}"
        if (!ok && respond) respond(HttpStatusCode.Unauthorized, "Нет доступа: подключите телефон по QR-коду заново")
        return ok
    }

    private suspend fun ApplicationCall.respondJson(text: String) = respondText(text, ContentType.Application.Json)
}
