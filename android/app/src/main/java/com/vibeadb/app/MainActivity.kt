package com.vibeadb.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vibeadb.app.core.Pairing
import com.vibeadb.app.core.Prefs
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : ComponentActivity() {

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startSessionService()
        }

    private val shizukuPermListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> UiTick.bump() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermListener)
        setContent { AppUi() }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        super.onDestroy()
    }

    private fun startSessionService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, SessionService::class.java).setAction(SessionService.ACTION_START)
        )
    }

    private fun stopSessionService() {
        startService(
            Intent(this, SessionService::class.java).setAction(SessionService.ACTION_STOP)
        )
    }

    private fun requestNotifThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startSessionService()
        }
    }

    private fun requestShizukuPermission() {
        try {
            Shizuku.requestPermission(1001)
        } catch (t: Throwable) {
            Toast.makeText(this, "Shizuku 不可用：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- UI ----

    @Composable
    private fun AppUi() {
        val ctx = LocalContext.current
        val prefs = remember { Prefs(ctx) }
        val state by SessionState.state.collectAsState()
        val tick by UiTick.tick.collectAsState()
        val shizukuText = remember(tick) { shizukuStatus() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "vibeADB",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            ShizukuCard(shizukuText) { requestShizukuPermission() }
            CrashCard()
            SessionCard(state, onStart = { requestNotifThenStart() }, onStop = { stopSessionService() })
            SettingsCard(prefs)
        }
    }

    @Composable
    private fun CrashCard() {
        val ctx = LocalContext.current
        val tick by UiTick.tick.collectAsState()
        val crash = remember(tick) { File(ctx.filesDir, "crash-latest.txt").takeIf { it.exists() }?.readText() }
        if (crash == null) return
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("上次崩溃报告", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Text(crash.take(2000), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { copyText(ctx, "crash", crash) }) { Text("复制崩溃报告") }
                    OutlinedButton(onClick = {
                        File(ctx.filesDir, "crash-latest.txt").delete()
                        UiTick.bump()
                        Toast.makeText(ctx, "已清除", Toast.LENGTH_SHORT).show()
                    }) { Text("清除") }
                }
            }
        }
    }

    @Composable
    private fun ShizukuCard(status: String, onRequest: () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shizuku", fontWeight = FontWeight.Bold)
                Text("状态: $status")
                OutlinedButton(onClick = onRequest) { Text("请求授权") }
                Text(
                    "若未运行：安装 Shizuku 后，通过「无线调试」或 root 启动。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    @Composable
    private fun SessionCard(state: SessionUiState, onStart: () -> Unit, onStop: () -> Unit) {
        val ctx = LocalContext.current
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("会话", fontWeight = FontWeight.Bold)
                when (state) {
                    is SessionUiState.Idle, is SessionUiState.Stopped -> {
                        if (state is SessionUiState.Stopped) Text(state.message)
                        Text("未运行。开始会话后把配对串发给 MCP 服务器即可（永久有效）。")
                        Button(onClick = onStart) { Text("开始会话") }
                    }
                    is SessionUiState.Starting -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(Modifier.padding(2.dp))
                            Text(state.message)
                        }
                        OutlinedButton(onClick = onStop) { Text("停止") }
                    }
                    is SessionUiState.Running -> {
                        Text("中继: ${state.relayHost} · ${if (state.online) "已连接" else "连接中"}")
                        Text("Agent 可随时通过配对串连接（支持断线自动重连）。")
                        Text("配对串:", fontWeight = FontWeight.Bold)
                        Text(state.pairing, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { copyText(ctx, "pairing", state.pairing) }) {
                                Text("复制配对串")
                            }
                            OutlinedButton(onClick = onStop) { Text("停止会话") }
                        }
                    }
                    is SessionUiState.Failed -> {
                        Text("失败: ${state.message}", color = MaterialTheme.colorScheme.error)
                        Button(onClick = onStart) { Text("重试") }
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsCard(prefs: Prefs) {
        val ctx = LocalContext.current
        var host by remember { mutableStateOf(prefs.relayHost) }
        var pw by remember { mutableStateOf(prefs.password) }
        val pairing = Pairing.worker(
            prefs.relayHost.ifBlank { "<relay-host>" },
            prefs.deviceId,
            prefs.password
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("设置", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("边缘中继地址（如 vibeadb-relay.xxx.workers.dev）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(onClick = {
                    prefs.relayHost = host.trim().removePrefix("https://").removeSuffix("/")
                    host = prefs.relayHost
                    Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                }) { Text("保存中继配置") }

                Text("配对串（永久有效）:", fontWeight = FontWeight.Bold)
                Text(pairing, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copyText(ctx, "pairing", pairing) }) {
                        Text("复制配对串")
                    }
                    OutlinedButton(onClick = { copyText(ctx, "deviceId", prefs.deviceId) }) {
                        Text("复制 ID")
                    }
                    OutlinedButton(onClick = {
                        pw = prefs.resetPassword()
                        Toast.makeText(ctx, "密码已重置（记得更新 Agent 侧配对串）", Toast.LENGTH_LONG).show()
                    }) { Text("重置密码") }
                }
                Text(
                    "密码: $pw",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    private fun copyText(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
    }
}

private fun shizukuStatus(): String = try {
    when {
        !Shizuku.pingBinder() -> "Shizuku 未运行"
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "已授权"
        else -> "未授权"
    }
} catch (t: Throwable) {
    "不可用（未安装 Shizuku?）"
}
