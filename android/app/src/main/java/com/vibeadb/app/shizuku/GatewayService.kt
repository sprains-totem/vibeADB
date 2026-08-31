package com.vibeadb.app.shizuku

import android.content.Context
import android.os.Process
import com.vibeadb.app.gateway.GatewayWsServer

/**
 * Shizuku UserService：以 shell/root UID 在独立进程运行。
 * 生命周期：App bindUserService → start(password, port) → unbindUserService 触发 destroy → System.exit。
 */
class GatewayService(private val context: Context?) : IGatewayService.Stub() {

    private var server: GatewayWsServer? = null

    override fun start(password: String?, port: Int): Boolean {
        if (password.isNullOrEmpty() || port !in 1024..65535) return false
        shutdownServer()
        return try {
            val s = GatewayWsServer("127.0.0.1", port, password)
            s.isReuseAddr = true
            s.start()
            server = s
            true
        } catch (t: Throwable) {
            false
        }
    }

    override fun status(): String = if (server != null) "running" else "idle"

    override fun destroy() {
        shutdownServer()
        Process.killProcess(Process.myPid())
        System.exit(0)
    }

    @Synchronized
    private fun shutdownServer() {
        try { server?.shutdown() } catch (_: Throwable) {}
        server = null
    }
}
