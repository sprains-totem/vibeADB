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
import com.vibeadb.app.core.Prefs
import com.vibeadb.app.core.UrlParser
import com.vibeadb.app.mailbox.MailboxClient
import com.vibeadb.app.shizuku.GatewayConnection
import java.io.File
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * 会话前台服务：网关(Shizuku UserService) + cloudflared(quick tunnel) + 信箱投递。
 * 生命周期 = 会话生命周期：开始会话拉起，停止/空闲即结束，无保活与自恢复。
 */
class SessionService : Service() {

    private var cloudflared: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var stopped = false
    @Volatile private var sessionActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_STOP -> stopSession()
            else -> stopSelf()
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
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
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
            val port = 20000 + Random.nextInt(20000)
            val ok = GatewayConnection.ensureStarted(this, prefs.password, port)
            if (!ok) throw IllegalStateException("网关启动失败")
            startCloudflared(prefs, port)
        } catch (t: Throwable) {
            if (!stopped) {
                SessionState.update(SessionUiState.Failed(t.message ?: t.javaClass.simpleName))
                stopSessionInternal()
            }
        }
    }

    private fun startCloudflared(prefs: Prefs, port: Int) {
        val bin = File(applicationInfo.nativeLibraryDir, "libcloudflared.so")
        if (!bin.exists()) {
            throw IllegalStateException("缺少 cloudflared（本 APK 仅打包 arm64-v8a）")
        }
        val p = ProcessBuilder(
            bin.absolutePath, "tunnel", "--no-autoupdate", "--protocol", "http2",
            "--url", "http://127.0.0.1:$port"
        ).redirectErrorStream(true).start()
        cloudflared = p
        SessionState.update(SessionUiState.Starting("隧道连接中…"))
        thread(name = "vibeadb-tunnel") {
            var found = false
            p.inputStream.bufferedReader().forEachLine { line ->
                if (found || stopped) return@forEachLine
                val host = UrlParser.findTunnelHost(line) ?: return@forEachLine
                found = true
                var mailboxOk: Boolean? = null
                if (prefs.workerHost.isNotBlank() && prefs.workerToken.isNotBlank()) {
                    mailboxOk = try {
                        MailboxClient(prefs.workerHost, prefs.workerToken).put(prefs.deviceId, host)
                    } catch (t: Throwable) {
                        false
                    }
                }
                val pairing = if (prefs.workerHost.isNotBlank()) {
                    Pairing.worker(prefs.workerHost, prefs.deviceId, prefs.password)
                } else {
                    Pairing.direct(host, prefs.password)
                }
                SessionState.update(SessionUiState.Running(host, pairing, mailboxOk))
            }
            // cloudflared 进程退出（被杀/网络中断/异常）
            if (!stopped) {
                val cur = SessionState.state.value
                if (cur is SessionUiState.Running || cur is SessionUiState.Starting) {
                    SessionState.update(SessionUiState.Failed("隧道进程退出（重开会话即可）"))
                    stopSessionInternal()
                }
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
        try { cloudflared?.destroy() } catch (_: Throwable) {}
        cloudflared = null
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
