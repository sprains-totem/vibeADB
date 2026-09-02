package com.vibeadb.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vibeadb.app.core.Pairing
import com.vibeadb.app.core.Password
import com.vibeadb.app.core.Prefs
import com.vibeadb.app.core.RingLog
import com.vibeadb.app.shizuku.GatewayConnection
import kotlin.concurrent.thread

/**
 * 会话前台服务：Shizuku UserService 网关 + 到边缘中继的出站 WebSocket。
 * 生命周期 = 会话生命周期；出站连接自带重连退避，不做开机自启/保活。
 */
class SessionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var stopped = false
    @Volatile private var sessionActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> startSession()
                ACTION_STOP -> stopSession()
                else -> stopSelf()
            }
        } catch (t: Throwable) {
            SessionState.update(SessionUiState.Failed("服务启动异常: ${t.javaClass.simpleName}: ${t.message}"))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "vibeADB 会话", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("vibeADB 会话运行中")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
        } catch (t: Throwable) {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startSession() {
        if (sessionActive) return
        sessionActive = true
        stopped = false
        startForegroundCompat()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibeadb:session")
            .also { it.acquire(6 * 60 * 60 * 1000L) }
        SessionState.update(SessionUiState.Starting("启动中…"))
        thread(name = "vibeadb-session") { runSession() }
    }

    private fun runSession() {
        val prefs = Prefs(this)
        try {
            if (prefs.relayHost.isBlank()) {
                throw IllegalStateException("未配置边缘中继地址（设置页填写）")
            }
            val sid = Password.deviceId().take(16)
            val epoch = System.currentTimeMillis()
            RingLog.log("app", "ensureStarted (relay=${prefs.relayHost}, sid=$sid, epoch=$epoch)")
            val ok = GatewayConnection.ensureStarted(
                this,
                prefs.password,
                prefs.relayHost,
                prefs.deviceId,
                sid,
                epoch
            )
            RingLog.log("app", "ensureStarted -> $ok")
            if (!ok) throw IllegalStateException("网关启动失败")
            val pairing = Pairing.worker(prefs.relayHost, prefs.deviceId, prefs.password)
            // 网关状态轮询（跨进程，AIDL）
            while (!stopped) {
                val st = GatewayConnection.currentStatus() ?: "idle"
                SessionState.update(
                    when {
                        st == "online" -> SessionUiState.Running(prefs.relayHost, pairing, true)
                        st.startsWith("connecting") || st.startsWith("retrying") ->
                            SessionUiState.Starting("连接边缘中继（$st）…")
                        else -> SessionUiState.Starting("等待网关…")
                    }
                )
                Thread.sleep(1000)
            }
        } catch (t: Throwable) {
            RingLog.log("app", "session error: ${t.javaClass.simpleName}: ${t.message}")
            if (!stopped) {
                SessionState.update(SessionUiState.Failed(t.message ?: t.javaClass.simpleName))
                stopSessionInternal()
            }
        }
    }

    private fun stopSession() {
        SessionState.update(SessionUiState.Idle)
        stopSessionInternal()
    }

    private fun stopSessionInternal() {
        stopped = true
        sessionActive = false
        RingLog.log("app", "session stopped")
        try { GatewayConnection.stopSession(this) } catch (_: Throwable) {}
        try { wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.vibeadb.app.action.START"
        const val ACTION_STOP = "com.vibeadb.app.action.STOP"
        private const val CHANNEL_ID = "session"
        private const val NOTIF_ID = 1
    }
}
