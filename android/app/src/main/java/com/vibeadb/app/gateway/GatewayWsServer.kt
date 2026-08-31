package com.vibeadb.app.gateway

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WebSocket 网关（运行于 Shizuku UserService 进程，只绑 127.0.0.1）。
 * 协议见 PROTOCOL.md §3：auth → JSON-RPC + 二进制分块。
 */
class GatewayWsServer(host: String, port: Int, private val password: String) :
    WebSocketServer(InetSocketAddress(host, port)) {

    private val authed = Collections.newSetFromMap(ConcurrentHashMap<WebSocket, Boolean>())
    private val lastActivity = ConcurrentHashMap<WebSocket, Long>()
    private val dispatcher = Dispatcher(ProcessCommandRunner())
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var shutdown = false

    override fun start() {
        // 预检端口占用，给出可预期的失败
        ServerSocket().use { it.bind(address) }
        super.start()
        scheduler.scheduleAtFixedRate({ sweepIdle() }, 60, 60, TimeUnit.SECONDS)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        lastActivity[conn] = System.currentTimeMillis()
        scheduler.schedule({
            if (!shutdown && !authed.contains(conn)) {
                try { conn.close(4002, "auth timeout") } catch (_: Exception) {}
            }
        }, 15, TimeUnit.SECONDS)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        authed.remove(conn)
        lastActivity.remove(conn)
        dispatcher.cancel(conn)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        touch(conn)
        val ch = channel(conn)
        try {
            val obj = JSONObject(message)
            when (obj.optString("op")) {
                "auth" -> {
                    if (obj.optString("password") == password) {
                        authed.add(conn)
                        ch.sendText("""{"op":"auth","ok":true}""")
                    } else {
                        ch.sendText("""{"op":"auth","ok":false,"error":"bad password"}""")
                        conn.close(4001, "auth failed")
                    }
                }
                "eod" -> {
                    if (!requireAuth(conn)) return
                    dispatcher.onEod(conn, obj.optInt("id"))
                }
                "" -> {
                    if (!requireAuth(conn)) return
                    dispatcher.dispatch(conn, ch, message)
                }
            }
        } catch (e: Exception) {
            try { ch.sendText("""{"op":"protoError","error":"bad frame"}""") } catch (_: Exception) {}
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        touch(conn)
        if (!requireAuth(conn)) return
        if (message.remaining() < 5) return
        val id = message.int
        val data = ByteArray(message.remaining())
        message.get(data)
        dispatcher.onChunk(conn, id, data)
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {}

    override fun onStart() {}

    fun shutdown() {
        shutdown = true
        scheduler.shutdownNow()
        try { stop(0) } catch (_: Exception) {}
    }

    private fun requireAuth(conn: WebSocket): Boolean {
        if (authed.contains(conn)) return true
        try { conn.close(4001, "unauthorized") } catch (_: Exception) {}
        return false
    }

    private fun touch(conn: WebSocket) {
        lastActivity[conn] = System.currentTimeMillis()
    }

    private fun sweepIdle() {
        val now = System.currentTimeMillis()
        for ((conn, last) in lastActivity) {
            if (now - last > IDLE_TIMEOUT_MS) {
                try { conn.close(4000, "idle timeout") } catch (_: Exception) {}
            }
        }
    }

    private fun channel(conn: WebSocket): ReplyChannel = object : ReplyChannel {
        override fun sendText(text: String) {
            conn.send(text)
        }

        override fun sendBinary(id: Int, data: ByteArray) {
            val buf = ByteBuffer.allocate(4 + data.size).putInt(id).put(data)
            buf.flip()
            conn.send(buf)
        }
    }

    companion object {
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
