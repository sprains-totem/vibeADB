package com.vibeadb.app.gateway

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import javax.net.ssl.SSLSocketFactory

/**
 * v2 出站隧道：App（UserService 进程，shell/root UID）**主动连出**到边缘中继的 device 腿。
 * 不再需要 cloudflared / 入站隧道 / URL 信箱——中继域名恒定。
 *
 * 帧转发：client 腿（经 DO 透明管道）→ 这里 → Dispatcher 执行 → 结果帧原路返回。
 * auth 由本地校验（端到端，边缘不接触密码）。
 */
class RelayTunnelClient(
    relayHost: String,
    deviceId: String,
    private val password: String,
    private val onState: (String) -> Unit,
) : WebSocketClient(URI("wss://$relayHost/device")) {

    private val dispatcher = Dispatcher(ProcessCommandRunner())

    @Volatile private var authed = false

    init {
        addHeader("X-Device-Id", deviceId)
        try {
            setSocketFactory(SSLSocketFactory.getDefault() as SSLSocketFactory)
        } catch (_: Throwable) {
        }
        // 30s WS ping 保活：防运营商 NAT/防火墙掐 60s 空闲长连接
        // （CF 边缘直接应答 pong，不唤醒 DO、不计请求额度）
        connectionLostTimeout = 30
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        onState("online")
        authed = false
    }

    override fun onMessage(message: String?) {
        val msg = message ?: return
        try {
            val obj = JSONObject(msg)
            when (obj.optString("op")) {
                "auth" -> {
                    if (obj.optString("password") == password) {
                        authed = true
                        send("""{"op":"auth","ok":true}""")
                    } else {
                        send("""{"op":"auth","ok":false,"error":"bad password"}""")
                        close(4001, "auth failed")
                    }
                }
                "edge" -> {
                    // paired / client_gone / device_gone —— 状态由连接本身体现，忽略
                }
                "" -> {
                    if (!authed) {
                        close(4001, "unauthorized")
                        return
                    }
                    dispatcher.dispatch(this, channel(), msg)
                }
            }
        } catch (e: Exception) {
            try { send("""{"op":"protoError","error":"bad frame"}""") } catch (_: Exception) {}
        }
    }

    override fun onMessage(message: ByteBuffer) {
        if (!authed) {
            close(4001, "unauthorized")
            return
        }
        if (message.remaining() < 5) return
        val id = message.int
        val data = ByteArray(message.remaining())
        message.get(data)
        dispatcher.onChunk(this, id, data)
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        dispatcher.cancel(this)
        authed = false
    }

    override fun onError(ex: Exception?) {}

    fun shutdown() {
        try { close() } catch (_: Exception) {}
    }

    private fun channel(): ReplyChannel = object : ReplyChannel {
        override fun sendText(text: String) {
            send(text)
        }

        override fun sendBinary(id: Int, data: ByteArray) {
            val buf = ByteBuffer.allocate(4 + data.size).putInt(id).put(data)
            buf.flip()
            send(buf)
        }
    }
}
