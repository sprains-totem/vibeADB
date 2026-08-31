# vibeADB 开发计划（v1）

> 预估：单人 ~6-8 周（M2/M3 含真机调试，是波动最大的部分）。每个里程碑都有**验收标准**，满足才进入下一阶段。

## 技术选型

| 模块 | 选型 | 理由 |
|---|---|---|
| Android app | Kotlin + Jetpack Compose | 简单三屏 UI，声明式开发快 |
| Shizuku 集成 | `dev.rikka.shizuku:api` + `provider` | 官方库，顺带支持 Sui |
| WebSocket 网关 | UserService 内嵌轻量 WS server（Java-WebSocket 或 Ktor CIO，M2 选型验证） | 需在 shell/root 身份进程内跑，避免重型框架 |
| 隧道 | 内嵌 cloudflared 二进制（arm64-v8a / armeabi-v7a，gradle 打包 assets） | 免登录、无公网 IP |
| Worker | TypeScript + Wrangler v3 + KV | 官方生态，部署简单 |
| Agent CLI | Go（cobra + gorilla/websocket） | 单二进制分发，对 AI Agent 友好 |
| MCP server | TypeScript + `@modelcontextprotocol/sdk` | MCP 生态最成熟 |

## M0 — 协议与凭证模型冻结（0.5~1 周）

**交付物**（`protocol/`）：
- 网关 WebSocket 协议文档：握手流程（密码 challenge）、帧格式、JSON-RPC 方法清单（§能力边界）、错误码、会话超时约定。
- Worker HTTP API 定义：`POST /register`、`GET /resolve` 的请求/响应/错误码。
- KV schema、密码生成策略（≥32 字符高熵）、写令牌管理方式（Worker `env` secret）。

**验收标准**：协议文档被 worker 和 agent 两份实现独立照抄一致；安全模型 §5 四条全部有落点。

## M1 — Worker + KV（1 周，可独立交付）

**交付物**（`worker/`）：
- `register` / `resolve` 两个路由；写令牌校验；密码哈希存储；`updatedAt` 新鲜度判定；惰性过期清理。
- 限流：per-IP + per-device 计数（先 KV 计数器，后续可换 CF Rate Limiting）。
- cron 定时清理过期条目。

**验收标准**：`wrangler dev` 下 curl 完成 register → resolve 全链路；错误密码 401、超限 429、过期条目 410；`wrangler deploy` 后公网可达。

## M2 — 手机端：授权 + UserService 网关骨架（1.5~2 周，核心风险点）

**交付物**（`android/`）：
- Shizuku 集成：provider 注册、权限请求 UI、binder 生命周期监听（重启后引导重授权）。
- UserService 网关：绑定/启动/destroy 生命周期；`127.0.0.1:<port>` WebSocket server；鉴权握手；JSON-RPC dispatch。
- 首版命令面：`ping`、`shell`（阻塞+流式）、`screencap`、`uiautomator dump`、`input`、`am`、`pm list`。
- 会话管理：空闲超时、握手失败即断。

**验收标准**：真机上用任意 WS 客户端连 `127.0.0.1:<port>`，完成鉴权 + `shell` + `screencap` + `input tap`；错误密码被拒；Shizuku 被杀后网关优雅退出。

**选型验证项**：WS 库在 UserService 进程的可用性（不开 ServerSocket 权限问题）；`pm install`（shell 身份、安装人 `com.android.shell`）真机通过。

## M3 — 隧道 + 上报 + 状态 UI（1~1.5 周）

**交付物**：
- cloudflared 内嵌与启动（前台服务 + WakeLock + 忽略电池优化；Android 14+ 声明 `FOREGROUND_SERVICE_DATA_SYNC`）。
- 隧道 URL 监听：变化即 `PUT /register`，心跳 1min。
- UI 完整三屏：Shizuku 状态/授权引导、隧道状态（URL 复制 / 心跳 / 报错 / 日志开关）、Worker 配置（worker 域名 + 设备注册，可复制命令）。

**验收标准**：真机一键启动 → KV 出现记录且心跳刷新；杀进程/重启后自恢复（START_STICKY）；断网恢复后 URL 变化自动上报。

## M4 — Agent 侧 CLI（1 周）

**交付物**（`agent/`）：
- `vibeadb resolve <deviceId>`、`vibeadb connect <deviceId>`（鉴权并保持会话）、`vibeadb shell|screenshot|ui|tap|install ...` 子命令。
- resolve 缓存 + 连接失败自动重查 + 重试退避。

**验收标准**：CLI 通过公网 HTTPS 完成一次真机截图 / 一次 `am start` / 一次 `pm list`；断连后自愈。

## M5 — 硬化、多设备与文档（1 周）

**交付物**：
- 审计日志（网关侧记录每次操作）、统一错误码、密码轮换流程。
- 多设备：设备命名、`GET /devices` 列表（按写令牌授权）。
- 文档：README（架构图 + 部署 Worker 指南 + 手机使用指南 + 安全说明）。
- （可选，若时间允许）MCP server 包一层：`connect` / `exec` 两个 tool。

**验收标准**：从零跟随 README 可部署 Worker、可注册手机、可用 CLI 完成一轮"装 APK → uiautomator dump → 截图 → input 点击"的端到端测试。

## 明确不进 v1（Deferred）

| 项 | 说明 | 触发条件 |
|---|---|---|
| 真 adbd 协议 / 原生 `adb` CLI 兼容 | 需 TCP-over-WebSocket 封装 + 无线调试配对管理 | 出现"必须复用 adb/scrcpy 工具链"的真实需求 |
| scrcpy 式视频流 | 移植 scrcpy server（shell 身份抓 SurfaceFlinger） | 视频流成为测试刚需 |
| 交互式 tty shell | 需自建 pty 层 | 同上 |
| root-only 能力 | 架构天然支持 Sui；首版只做 shell 面 | 用户明确需要（读应用数据等） |
| 命名隧道 + Access Service Token | 更稳的边缘鉴权 | 有自有域名后 |

## 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| cloudflared 后台保活 / Android 14+ 前台服务类型限制 | M3 可能返工 | M3 一开始就上真机验证；备选：Termux 方式兜底 |
| 个别命令因 OEM/SELinux 失败 | 命令面不确定性 | M2 建立测试矩阵（至少 2 台不同品牌真机）；命令失败要有明确错误码而非静默 |
| quick tunnel URL 轮换 / 偶发限流 | Agent 拿到死链 | 心跳 + Agent 重查 + 退避；备选命名隧道 |
| WS 库在 UserService 兼容性 | M2 阻塞 | 选型验证前置到 M2 第一周 |
| KV 免费写额度（1k/day） | 心跳过频会爆 | 固定 1min 心跳；错误路径不额外写 |
| trycloudflare 不可用 | 服务中断 | 统一抽象隧道适配层，可插拔切命名隧道 |

## 依赖顺序

```
M0 ──► M1 ──────────────────┐
  └──► M2 ──► M3 ──► M5（端到端验收）
              └──► M4
```

- M1（Worker）与 M2（网关）可并行推进；Agent 侧联调最早在 M2 验收后开始。
- 每完成一个里程碑即打 tag，保持主分支始终可构建。