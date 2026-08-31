# vibeADB 架构设计（v1 冻结稿）

> 目标：让 AI Agent 通过公网 HTTPS 对真机执行 ADB 级别的测试操作（装/卸应用、启动 Activity、注入输入、截图、取 UI 层级、抓日志），手机无需公网 IP、免 root（可选的 Sui 路径支持 root）。

## 1. 总体架构

数据面（隧道直连）与控制面（域名发现）分离：

```
┌─────────────┐   HTTPS/WebSocket  ┌──────────────────┐   HTTPS    ┌──────────────────────┐
│  AI Agent   │ ─────────────────► │ trycloudflare.com │ ─────────► │  Android 手机          │
│  (CLI/MCP)  │  ① 直连（数据面）    │   (quick tunnel)  │   ② 隧道    │  UserService 网关      │
└─────┬───────┘                    └──────────────────┘            │  (Shizuku shell/root)  │
      │ ③ resolve(设备ID+密码)                                      └──────────────────────┘
      ▼                                                                    ▲
┌─────────────┐   KV     ┌──────────┐  ④ register(设备ID+写令牌+域名)      │
│ CF Worker   │ ◄──────► │   KV     │ ◄────────────────────────────────────│
│ (控制面)     │          └──────────┘             心跳上报 1min            │
└─────────────┘
```

- **数据面**：quick tunnel 直连，ADB 流量（截图、安装包等大流量）不经过 Worker，免费额度与带宽不受影响。
- **控制面**：Worker + KV 只做"设备注册 / 域名发现 / 心跳"，轻量、免费（KV 免费 100k reads/day、1k writes/day；1min 心跳 ≈ 1440 writes/day/设备，余量足够）。

## 2. 组件职责

| 组件 | 职责 | 关键点 |
|---|---|---|
| Android app | Shizuku 授权引导、隧道管理、KV 上报、状态 UI | 前台服务保活；内嵌 cloudflared 二进制 |
| UserService 网关 | 监听 `127.0.0.1:<port>` 的 WebSocket 服务，鉴权握手 + JSON-RPC 命令执行 | 以 shell/root UID 运行，是"ADB 能力"的真正实现者 |
| CF Worker | `register`（写）+ `resolve`（查）两个接口，TTL 清理、限流 | KV schema 见 §5 |
| Agent CLI/MCP | resolve → 连接 → 鉴权 → 执行操作序列 | 缓存 + 连接失败自动重查 |

## 3. 关键概念判定（依据 Shizuku-API 文档）

- **Shizuku ≠ adbd**。"接入 ADB"的落地形式是：**以 adb（shell）身份执行操作**，而不是跑真 adbd 协议（无 RSA 认证、无 ADB 传输协议、无交互 tty）。
- 免 root（Shizuku/adb 启动）时身份为 **UID 2000 / `u:r:shell:s0`**，能力 ≈ 非 root 的 `adb shell`；Sui（Magisk）时 UID 0。
- 网关实现位置首选 **UserService**（文档推荐形态）：独立进程、shell/root 身份、无 non-SDK 限制、可开 ServerSocket。`newProcess` 已废弃（API 14 移除）、无 tty、随调用方死亡。
- UserService 进程不是合法 Android app 进程：`Context#registerReceiver`、`getContentResolver` 等不可用；需实现 `destroy`（transaction `16777115`）清理并 `System.exit()`。

## 4. 能力边界

**支持（首版命令面，均验证为 shell 身份可行）：**

| 类别 | 命令 |
|---|---|
| 包管理 | `pm install / uninstall / list / grant`（install 时安装者需为 `com.android.shell`，见 Shizuku demo） |
| 应用控制 | `am start / stop / force-stop / broadcast` |
| 输入注入 | `input tap / swipe / text / keyevent` |
| 截图 | `screencap`（部分应用安全窗口会黑屏，需容忍） |
| UI 层级 | `uiautomator dump`（AI 测试核心） |
| 日志 | `logcat`（流式订阅） |
| 状态 | `settings / dumpsys / getprop` |
| 随机测试 | `monkey` |

**不支持 / 明确不承诺：**

- 真 adbd / 原生 `adb` CLI 兼容、scrcpy 式视频流（需自实现，列为二期候选）
- 免 root 下的 root-only 操作（读其他 app 私有数据等）
- 交互式 tty shell
- 程序化开启"无线调试"、保持 adbd 存活（无相关 API，须用户在开发者选项手动操作）
- 具体命令可用性受 Android 版本 / OEM / SELinux 影响 → 需要测试矩阵（见 ROADMAP 风险表）

## 5. 安全模型（不可裁剪项）

1. **网关握手鉴权（最关键）**：映射密码同时是**连接凭证**，不只是域名兑换券。WebSocket 握手必须校验密码，失败即断开。域名泄露 ≠ 可连接。
2. **Worker 写接口单独鉴权**：`register` 需验证写令牌（Worker secret 对比），防止任何人污染 KV。
3. **查询限流**：`resolve` 防暴力猜密码（per-IP 计数 + 失败次数）。密码要求高熵（≥32 字符，客户端生成）。
4. **KV TTL + 心跳**：`updatedAt` 过期清理僵尸域名；Agent 拿到的 URL 可能已断 → 必须"连接失败 → 重查"。
5. **网关只绑定 `127.0.0.1`**，仅经隧道可达；会话空闲超时自动断开。
6. （升级路径，有域名后）命名隧道 + Cloudflare Access **Service Token**，Agent 侧带 `CF-Access-Client-Id/Secret` 头即可通过边缘鉴权。

## 6. KV Schema（多设备）

```
devices:<deviceId> {
  domain:        string        // 当前 quick tunnel 域名
  passwordHash:  string        // 映射密码的 HMAC/SHA-256
  updatedAt:     number        // 心跳时间戳
  deviceName:    string        // 可选，便于 Agent 列表展示
}
```

- 写：`POST /register { deviceId, token, domain, deviceName? }`（token 校验通过后 upsert，写 `updatedAt` 并可能重设密码哈希——密码轮换）
- 查：`GET /resolve?deviceId=&password=` → 校验哈希、检查新鲜度（`updatedAt` 距今 > N 分钟视为离线）→ 返回 `{ domain, online }` 或 404/410
- 频率：心跳 1min；TTL 清理由 Worker 定时触发或惰性过期。

## 7. 仓库结构

```
vibeADB/
├── android/     # Kotlin app：授权引导 + cloudflared 管理 + KV 上报 + 状态 UI
│   └── gateway/ # UserService：WebSocket 网关（命令面实现）
├── protocol/    # 网关 JSON-RPC 方法与 Worker 接口定义（两端共享）
├── worker/      # CF Worker + KV（wrangler.toml）
└── agent/       # CLI（Go）+ MCP server（TypeScript）
```