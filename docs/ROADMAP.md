# vibeADB 开发计划（v2.1）

> 会话制短时测试工具 + ~50 行 URL 信箱，预估单人 ~4-5 周。v1 的心跳/限流/保活全部移除，仅保留最小发现能力（变更理由见文末决策记录）。每个里程碑都有**验收标准**，满足才进入下一阶段。

## 技术选型

| 模块 | 选型 | 理由 |
|---|---|---|
| Android app | Kotlin + Jetpack Compose | 两屏 UI，声明式开发快 |
| Shizuku 集成 | `dev.rikka.shizuku:api` + `provider` | 官方库，顺带支持 Sui |
| WebSocket 网关 | UserService 内嵌 Java-WebSocket（M1 前置选型验证） | 轻量，避免重型框架 |
| 隧道 | 内嵌 cloudflared 二进制（arm64-v8a / armeabi-v7a，assets 打包），仅 quick tunnel | 免登录、零配置，会话制下够用 |
| URL 信箱 | TypeScript + Wrangler + KV，两个路由 ~50 行 | `wrangler deploy` 一条命令部署，免费额度内运行，零运维 |
| Agent CLI | Go（cobra + gorilla/websocket） | 单二进制分发，对 AI Agent 友好 |
| MCP server | TypeScript + `@modelcontextprotocol/sdk` | 可选，最后包一层 |

## M0 — 协议冻结（2~3 天）

**交付物**（`protocol/`）：
- WS 握手流程与鉴权（密码 challenge）、JSON-RPC 方法清单、错误码、空闲超时约定。
- **二进制帧格式**：截图回传（PNG）与 APK 分块上传（`pm install -S <size>` stdin 流式）——两端实现前必须定稿。
- 配对串格式：`vibeadb://<worker-host>/<deviceId>#<password>`（及无 Worker 的直连变体）。
- 信箱 API：`PUT /devices/<id>`（写，Bearer 令牌）、`GET /devices/<id>`（读）、404/401 语义、TTL 约定。

**验收标准**：文档无歧义，Worker / Android / CLI 三端可各自照抄独立实现。

## M1 — 手机端：Shizuku + UserService 网关（1.5~2 周，核心风险点）

**交付物**（`android/`）：
- Shizuku 集成：provider 注册、权限请求 UI、binder 生命周期监听（Shizuku 未启动时引导用户去启动）。
- UserService 网关：绑定/启动/destroy 生命周期；`127.0.0.1:<port>` WS server；鉴权握手；JSON-RPC dispatch。
- 首版命令面：`ping`、`shell`（阻塞+流式）、`screencap`、`uiautomator dump`、`input`、`am`、`pm install/list`。
- 会话管理：空闲超时、握手失败即断。

**验收标准**：真机上经 `adb forward` 用任意 WS 客户端连 `127.0.0.1:<port>`，完成鉴权 + `shell` + `screencap` + `input tap`；错误密码被拒；Shizuku 被杀后网关优雅退出。

**选型验证项**（前置到第一周）：WS 库在 UserService 进程的可用性；`pm install`（shell 身份、安装人 `com.android.shell`）真机通过。

## M2 — URL 信箱 Worker（2~3 天，可与 M1 并行）

**交付物**（`worker/`）：
- `PUT /devices/<id>`（验 Bearer 令牌，upsert，`expirationTtl=24h`）+ `GET /devices/<id>`（返回 `{domain}` 或 404）。
- 无心跳、无限流、无 cron 清理、不存储任何密码相关数据。

**验收标准**：`wrangler dev` 下 curl 完成 PUT → GET 全链路；错令牌 401、无记录 404；`wrangler deploy` 后公网可达。KV 写频率 = 会话启动次数，免费额度（1000 writes/day）内余量极大。

## M3 — 会话 UI + quick tunnel（3~5 天）

**交付物**：
- cloudflared quick tunnel 内嵌与启动；前台服务仅存活于会话期间（Android 14+ 声明 `FOREGROUND_SERVICE_DATA_SYNC`；其 6h/24h 上限对短会话不构成约束，不做 `BOOT_COMPLETED` 自启与 START_STICKY 保活）。
- URL 投递：会话启动、隧道 URL 变化（cloudflared 重启）时自动 `PUT` 信箱（事件驱动，无定时器）。
- UI 两屏：Shizuku 状态/授权引导；会话状态（启停按钮、配对串展示与一键复制、错误提示）。
- 一次性配置：Worker 地址 + 写令牌（App 设置里填一次）；密码首次运行随机生成（≥32 字符）并保存，可重置。

**验收标准**：真机点「开始」→ 信箱出现记录且 domain 正确；杀掉隧道重开会话 → 信箱 URL 自动更新；点「停止」后网关退出、URL 作废（记录残留无害，TTL 自然过期）。断线不做自动恢复。

## M4 — Agent CLI（3~5 天）

**交付物**（`agent/`）：
- `vibeadb connect '<配对串>'`：内置 resolve → 连接 → 鉴权并保持会话；连接失败自动重 resolve（短退避有限次，覆盖 KV 最终一致与会话重启窗口）。
- `shell | screenshot | ui | tap | swipe | text | install | logcat` 子命令。
- 错误语义清晰：404 → "手机端会话未运行"；重试耗尽 → "在手机上重新开始会话"。

**验收标准**：在远程机器上完成一轮真机"截图 / `am start` / `pm list`"；**手机重开会话后，CLI 无人工介入自动恢复连接**（多轮启动场景）。

## M5 — 收尾（2~3 天）

- README：架构图 + Worker 部署指南（`wrangler deploy` 一步）+ 手机使用指南 + Agent 使用示例 + 安全说明。
- （可选，若时间允许）MCP server：`connect` / `exec` 两个 tool。

**验收标准**：从零跟随 README（部署 Worker → 手机填配置 → 开始会话 → 粘贴配对串），端到端跑通"装 APK → uiautomator dump → 截图 → input 点击"。

## 明确不进 v2.1（Deferred）

| 项 | 说明 | 触发条件 |
|---|---|---|
| 心跳 / 新鲜度判定 | 活性由 Agent 连接行为判定，信箱无需提前感知 | 无（Agent 重查已覆盖） |
| 命名隧道 / 固定域名 | 需要自有域名与一次配置，换来 URL 永久不变、可去信箱化 | quick tunnel 体验成为实际瓶颈 |
| 保活/自恢复（START_STICKY、开机自启） | 短时会话不需要 | 服务变长驻 |
| 信箱限流 / 多设备列表 / 密码轮换 / 审计 | 高熵密码 + deviceId 不可枚举 + 私有短会话已覆盖威胁模型 | 多人共用时 |
| 真 adbd / scrcpy 视频 / tty | 需自实现传输层/pty 层/SurfaceFlinger 抓取 | 出现对应刚需 |

## 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| WS 库在 UserService 兼容性 | M1 阻塞 | 选型验证前置到 M1 第一周 |
| 个别命令因 OEM/SELinux 失败 | 命令面不确定性 | ≥2 台不同品牌真机测试矩阵；命令失败返回明确错误码而非静默 |
| quick tunnel 偶发限流/不可用/URL 轮换 | 会话中断 | URL 自动重投 + Agent 自动重查；重开会话即可，短时工具可接受；隧道侧留薄适配接口 |
| KV 最终一致（传播最长 ~60s） | 刚 PUT 完 GET 到旧值 | Agent 失败重查退避覆盖，不做额外设计 |
| trycloudflare 政策变化 | 服务不可用 | 命名隧道为后备路径（信箱机制不变，domain 换成固定域名） |

## 依赖顺序

```
M0 ──► M1（网关）──────► M3 ──► M4 ──► M5
  └──► M2（信箱，可并行）──┘      ▲
         └────────────────────────┘（M4 依赖 M2 的 resolve）
```

- M1 与 M2 无依赖可并行；M3 需要 M2 作投递目标；M4 需要 M2 作 resolve 来源。
- 每完成一个里程碑打 tag，主分支保持可构建。

## 决策记录

1. **移除 v1 的心跳**：活性由 Agent 连接行为判定；v1 的 1min 心跳 = 1440 writes/day/设备，超出 KV 免费版 1000 writes/day（v1 文档"余量足够"为计算错误）。信箱改为事件驱动写（会话启动/URL 变化），写频率 = 会话启动次数，额度问题消失。
2. **安全模型收缩**（网关握手密码、只绑 127.0.0.1、信箱写令牌）：会话制 + 高熵密码 + deviceId 不可枚举下，其余防护属过度设计。信箱不接触密码（v1 的 resolve 会把密码发给 Worker）。
3. **Android 15 dataSync FGS 风险消解**：短会话不受 6h/24h 上限约束，不做开机自启。
4. **（v2）删除 v1 的 Worker 里程碑**：单轮会话下配对串人工传递足够。
5. **（v2.1）加回最小 URL 信箱**：多轮启动场景下人工反复粘贴配对串不可接受；信箱只做"设备 ID → 当前 URL"的读写（~50 行，`wrangler deploy` 即部署），配对串因此只需配置一次、长期有效。它与 v1 控制面的区别：无心跳、事件驱动写、不碰密码与流量、无限流、无清理任务。
