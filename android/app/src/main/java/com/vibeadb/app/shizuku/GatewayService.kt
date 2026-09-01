package com.vibeadb.app.shizuku

import android.content.Context
import android.os.Process
import com.vibeadb.app.core.RingLog
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
            RingLog.log("gw", "start rejected: empty args")
            return false
        }
        RingLog.log("gw", "start requested (relay=$relayHost)")
        stopTunnel()
        stopped = false
        val t = Thread {
            var backoff = 2L
            while (!stopped) {
                RingLog.log("gw", "connecting (backoff was ${backoff}s)")
                state = "connecting"
                val c = RelayTunnelClient(relayHost, deviceId, password) { s -> state = s }
                client = c
                val opened = try {
                    c.connectBlocking(15, java.util.concurrent.TimeUnit.SECONDS)
                } catch (t: InterruptedException) {
                    false
                }
                if (opened && !stopped) {
                    state = "online"
                    RingLog.log("gw", "online")
                    backoff = 2
                    // 连接保持期间在此等待（onClose 由库回调）
                    while (!stopped && c.isOpen) {
                        try {
                            Thread.sleep(500)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    if (!stopped) RingLog.log("gw", "connection lost (isOpen=false)")
                }
                try { c.shutdown() } catch (_: Exception) {}
                if (stopped) break
                state = "retrying"
                RingLog.log("gw", "retry in ${backoff}s")
                try {
                    Thread.sleep(backoff * 1000)
                } catch (_: InterruptedException) {
                    break
                }
                backoff = (backoff * 2).coerceAtMost(30)
            }
            RingLog.log("gw", "loop exited")
        }
        loopThread = t
        t.start()
        return true
    }

    override fun status(): String = when (state) {
        "retrying", "connecting" -> "$state | lastClose: ${client?.lastCloseInfo ?: "-"} | lastOpen: ${client?.lastOpenInfo ?: "-"}"
        else -> state
    }

    override fun getLogs(): String = RingLog.dump()

    override fun clearLogs() = RingLog.clear()

    override fun destroy() {
        RingLog.log("gw", "destroy")
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
