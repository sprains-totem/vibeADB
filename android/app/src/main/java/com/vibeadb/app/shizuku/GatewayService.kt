package com.vibeadb.app.shizuku

import android.content.Context
import android.os.Process
import com.vibeadb.app.core.RingLog
import com.vibeadb.app.gateway.RelayTunnelClient

/**
 * Shizuku UserService：以 shell/root UID 在独立进程运行。
 * v2：进程内维护到边缘中继的出站 WebSocket（自动重连）。
 *
 * 关键：start() 可能被多次调用（重开会话/重试）。用代际计数器保证同一进程
 * 永远只有一个活动循环——新 start 使旧循环立即失效退出，杜绝双腿互踢。
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
        stopTunnel() // 先终止旧循环（内部会递增 generation）
        stopped = false
        generation += 1 // 为本次 start 分配新代际（必须在 stopTunnel 之后）
        val myGen = generation
        val t = Thread {
            var backoff = 2L
            while (!stopped && generation == myGen) {
                RingLog.log("gw", "connecting (gen=$myGen)")
                state = "connecting"
                val c = RelayTunnelClient(relayHost, deviceId, password) { s -> state = s }
                client = c
                val opened = try {
                    c.connectBlocking(15, java.util.concurrent.TimeUnit.SECONDS)
                } catch (t: InterruptedException) {
                    false
                }
                if (opened && !stopped && generation == myGen) {
                    state = "online"
                    RingLog.log("gw", "online")
                    backoff = 2
                    while (!stopped && generation == myGen && c.isOpen) {
                        try {
                            Thread.sleep(500)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    if (generation == myGen) RingLog.log("gw", "connection lost (isOpen=false)")
                }
                try { c.shutdown() } catch (_: Exception) {}
                if (stopped || generation != myGen) break
                state = "retrying"
                RingLog.log("gw", "retry in ${backoff}s")
                try {
                    Thread.sleep(backoff * 1000)
                } catch (_: InterruptedException) {
                    break
                }
                backoff = (backoff * 2).coerceAtMost(30)
            }
            RingLog.log("gw", "loop exited (gen=$myGen)")
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
        generation += 1 // 唤醒/终止所有旧循环
        loopThread?.interrupt()
        loopThread = null
        try { client?.shutdown() } catch (_: Throwable) {}
        client = null
    }

    companion object {
        /** 循环代际：每次 start/stop 递增，旧代循环自行退出 */
        @Volatile private var generation = 0
    }
}
