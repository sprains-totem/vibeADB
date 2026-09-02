package com.vibeadb.app.shizuku

import android.content.Context
import com.vibeadb.app.core.RingLog
import com.vibeadb.app.gateway.RelayTunnelClient
import kotlin.system.exitProcess

/**
 * Shizuku UserService：以 shell/root UID 在独立进程运行。
 * v2：进程内维护到边缘中继的出站 WebSocket（自动重连）。
 */
class GatewayService(private val context: Context?) : IGatewayService.Stub() {

    private var loopThread: Thread? = null
    private var client: RelayTunnelClient? = null

    @Volatile private var stopped = false

    @Volatile private var state = "idle"

    override fun start(password: String?, relayHost: String?, deviceId: String?, sid: String?, epoch: Long): Boolean {
        if (password.isNullOrEmpty() || relayHost.isNullOrEmpty() || deviceId.isNullOrEmpty() || sid.isNullOrEmpty() || epoch <= 0) {
            RingLog.log("gw", "start rejected: empty/invalid args")
            return false
        }
        RingLog.log("gw", "start requested (relay=$relayHost, sid=$sid, epoch=$epoch)")
        stopTunnel()
        stopped = false
        generation += 1
        val myGen = generation
        val t = Thread {
            var backoff = 2L
            while (!stopped && generation == myGen) {
                RingLog.log("gw", "connecting (gen=$myGen)")
                state = "connecting"
                val c = RelayTunnelClient(relayHost, deviceId, sid, epoch, password) { s -> state = s }
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

    override fun status(): String = when {
        state == "retrying" || state == "connecting" ->
            "$state | lastClose: ${client?.lastCloseInfo ?: "-"} | lastOpen: ${client?.lastOpenInfo ?: "-"}"
        else -> state
    }

    override fun getLogs(): String = RingLog.dump()

    override fun clearLogs() = RingLog.clear()

    override fun destroy() {
        RingLog.log("gw", "destroy called by Shizuku")
        stopTunnel()
        exitProcess(0)
    }

    @Synchronized
    private fun stopTunnel() {
        stopped = true
        generation += 1
        loopThread?.interrupt()
        loopThread = null
        try { client?.shutdown() } catch (_: Throwable) {}
        client = null
    }

    companion object {
        @Volatile private var generation = 0
    }
}
