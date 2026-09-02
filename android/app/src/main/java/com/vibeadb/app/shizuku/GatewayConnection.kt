package com.vibeadb.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.vibeadb.app.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** App 侧：绑定 Shizuku UserService 并启停网关。须在后台线程调用（内部 latch 等待）。 */
object GatewayConnection : ServiceConnection {

    private var binder: IGatewayService? = null
    private var latch = CountDownLatch(0)
    private var args: Shizuku.UserServiceArgs? = null

    @Synchronized
    fun ensureStarted(
        context: Context,
        password: String,
        relayHost: String,
        deviceId: String,
        sid: String,
        epoch: Long
    ): Boolean {
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
            .daemon(false) // 非 daemon 模式：宿主解除绑定或死亡时，Shizuku 自动清理
            .version(BuildConfig.VERSION_CODE)
            .tag("vibeadb")
        args = a
        Shizuku.bindUserService(a, this)
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw IllegalStateException("网关绑定超时")
        }
        val b = binder ?: throw IllegalStateException("网关绑定失败")
        return b.start(password, relayHost, deviceId, sid, epoch)
    }

    @Synchronized
    fun stopSession(@Suppress("UNUSED_PARAMETER") context: Context) {
        args?.let { runCatching { Shizuku.unbindUserService(it, this, true) } }
        args = null
        binder = null
    }

    /** 网关连接状态（"idle"/"connecting"/"online"/"retrying | ..."），未绑定返回 null */
    fun currentStatus(): String? = binder?.status()

    /** 网关进程环形日志，未绑定返回 null */
    fun logs(): String? = binder?.getLogs()

    fun clearLogs() {
        binder?.clearLogs()
        com.vibeadb.app.core.RingLog.clear()
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        binder = IGatewayService.Stub.asInterface(service)
        latch.countDown()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        binder = null
    }
}
