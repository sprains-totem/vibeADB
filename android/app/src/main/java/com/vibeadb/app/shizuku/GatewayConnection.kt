package com.vibeadb.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** App 侧：绑定 Shizuku UserService 并启停网关。须在后台线程调用（内部 latch 等待）。 */
object GatewayConnection : ServiceConnection {

    private var binder: IGatewayService? = null
    private var latch = CountDownLatch(0)
    private var args: Shizuku.UserServiceArgs? = null

    @Synchronized
    fun ensureStarted(context: Context, password: String, port: Int): Boolean {
        if (!Shizuku.pingBinder()) {
            throw IllegalStateException("Shizuku 未运行")
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("未授予 Shizuku 权限")
        }
        binder = null
        latch = CountDownLatch(1)
        val a = Shizuku.UserServiceArgs(ComponentName(context, GatewayService::class.java))
            .processNameSuffix("gateway")
            .version(1)
            .tag("vibeadb")
        args = a
        Shizuku.bindUserService(a, this)
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw IllegalStateException("网关绑定超时")
        }
        val b = binder ?: throw IllegalStateException("网关绑定失败")
        return b.start(password, port)
    }

    @Synchronized
    fun stopSession(@Suppress("UNUSED_PARAMETER") context: Context) {
        args?.let { runCatching { Shizuku.unbindUserService(it, this, true) } }
        args = null
        binder = null
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        binder = IGatewayService.Stub.asInterface(service)
        latch.countDown()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        binder = null
    }
}
