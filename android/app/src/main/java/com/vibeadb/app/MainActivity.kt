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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.vibeadb.app.core.Prefs
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startSessionService()
        }

    private val shizukuPermListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> UiTick.bump() }

    override fun onCreate(savedInstanceState: Bundle?) {
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
            SessionCard(state, onStart = { requestNotifThenStart() }, onStop = { stopSessionService() })
            SettingsCard(prefs)
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
                        Text("未运行。开始会话后把配对串发给 Agent 即可。")
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
                        Text("隧道: ${state.tunnelHost}")
                        Text(
                            when {
                                state.mailboxOk == true -> "已投递到信箱（Agent 用配对串即可连）"
                                state.mailboxOk == false -> "信箱投递失败（检查 Worker 地址/令牌）"
                                else -> "未配置信箱（直连模式，URL 变化需重新复制）"
                            }
                        )
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
        var host by remember { mutableStateOf(prefs.workerHost) }
        var token by remember { mutableStateOf(prefs.workerToken) }
        var pw by remember { mutableStateOf(prefs.password) }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("设置", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Worker 地址（如 vibeadb-mailbox.xxx.workers.dev）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("写令牌（Worker 的 WRITE_TOKEN）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(onClick = {
                    prefs.workerHost = host.trim().removePrefix("https://").removeSuffix("/")
                    prefs.workerToken = token.trim()
                    host = prefs.workerHost
                    Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                }) { Text("保存 Worker 配置") }

                Text("设备 ID: ${prefs.deviceId}", style = MaterialTheme.typography.bodySmall)
                Text("密码: $pw", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copyText(ctx, "deviceId", prefs.deviceId) }) {
                        Text("复制 ID")
                    }
                    OutlinedButton(onClick = { copyText(ctx, "password", prefs.password) }) {
                        Text("复制密码")
                    }
                    OutlinedButton(onClick = {
                        pw = prefs.resetPassword()
                        Toast.makeText(ctx, "密码已重置", Toast.LENGTH_SHORT).show()
                    }) { Text("重置密码") }
                }
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
