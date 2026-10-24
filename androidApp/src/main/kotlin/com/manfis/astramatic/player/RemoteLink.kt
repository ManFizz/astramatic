package com.manfis.astramatic.player

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.manfis.astramatic.library.DesktopServer
import com.manfis.astramatic.library.DesktopStore
import com.manfis.astramatic.remote.PlayerStatus
import com.manfis.astramatic.remote.RemoteApi
import com.manfis.astramatic.remote.RemoteCommand
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Пульт с ПК: пока плеер открыт, держим соединение с каждым подключённым AstraMatic на ПК,
 * раз в секунду отправляем, что играет, и выполняем приходящие команды (на главном потоке).
 */
class RemoteLink(
    context: Context,
    private val status: () -> PlayerStatus,
    private val onCommand: (DesktopServer, RemoteCommand) -> Unit,
) {
    private val store = DesktopStore(context)
    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private val handler = Handler(Looper.getMainLooper())
    private val sockets = mutableMapOf<String, WebSocket>()
    private var running = false

    fun start() {
        if (running) return
        running = true
        store.all().forEach(::connect)
        handler.post(statusTick)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        sockets.values.forEach { it.close(1000, null) }
        sockets.clear()
    }

    private fun connect(server: DesktopServer) {
        val request = Request.Builder()
            .url(server.webSocketUrl)
            .header("Authorization", "Bearer ${server.key}")
            .header(RemoteApi.DEVICE_HEADER, Build.MODEL)
            .build()
        sockets[server.id] = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val command = runCatching { RemoteApi.json.decodeFromString(RemoteCommand.serializer(), text) }.getOrNull()
                    ?: return
                handler.post { if (running) onCommand(server, command) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = retry(server, webSocket)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry(server, webSocket)
        })
    }

    /** ПК выключен или сеть пропала — пробуем снова, пока плеер открыт. */
    private fun retry(server: DesktopServer, socket: WebSocket) {
        handler.post {
            if (sockets[server.id] !== socket) return@post
            sockets.remove(server.id)
            if (running) handler.postDelayed({ if (running && server.id !in sockets) connect(server) }, RECONNECT_MS)
        }
    }

    private val statusTick = object : Runnable {
        override fun run() {
            if (sockets.isNotEmpty()) {
                val text = RemoteApi.json.encodeToString(status())
                sockets.values.forEach { it.send(text) }
            }
            handler.postDelayed(this, STATUS_INTERVAL_MS)
        }
    }

    private companion object {
        const val RECONNECT_MS = 5_000L
        const val STATUS_INTERVAL_MS = 1_000L
    }
}
