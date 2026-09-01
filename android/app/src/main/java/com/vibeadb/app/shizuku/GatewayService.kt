package com.vibeadb.app.shizuku

import android.content.Context
import android.os.Process
import com.vibeadb.app.gateway.RelayTunnelClient

/**
 * Shizuku UserService：以 shell/root UID 在独立进程运行。
 * v2：进程内维护到边缘中继的出站 WebSocket（自动重连），不再有本地 WS server 与 cloudflared。
 */
class GatewayService(private val context: Context?) : IGatewayService.Stub() {

    private var loopThread: Thread? = null
    private var client: RelayTunnelClient? = null

    @Volatile private var stopped = false

    @Volatile private var state = "idle"

    override fun start(password: String?, relayHost: String?, deviceId: String?): Boolean {
        if (password.isNullOrEmpty() || relayHost.isNullOrEmpty() || deviceId.isNullOrEmpty()) {
            return false
        }
        stopTunnel()
        stopped = false
        val t = Thread {
            var backoff = 2L
            while (!stopped) {
                val c = RelayTunnelClient(relayHost, deviceId, password) { s -> state = s }
                client = c
                state = "connecting"
                val opened = try {
                    c.connectBlocking(15, java.util.concurrent.TimeUnit.SECONDS)
                } catch (t: InterruptedException) {
                    false
                }
                if (opened && !stopped) {
                    state = "online"
                    backoff = 2
                    // 连接保持期间在此等待（onClose 由库回调）
                    while (!stopped && c.isOpen) {
                        try {
                            Thread.sleep(500)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
                try { c.shutdown() } catch (_: Exception) {}
                if (stopped) break
                state = "retrying"
                try {
                    Thread.sleep(backoff * 1000)
                } catch (_: InterruptedException) {
                    break
                }
                backoff = (backoff * 2).coerceAtMost(30)
            }
        }
        loopThread = t
        t.start()
        return true
    }

    override fun status(): String = state

    override fun destroy() {
        stopTunnel()
        Process.killProcess(Process.myPid())
        System.exit(0)
    }

    @Synchronized
    private fun stopTunnel() {
        stopped = true
        try { client?.shutdown() } catch (_: Throwable) {}
        client = null
    }
}
