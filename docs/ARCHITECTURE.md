# vibeADB 架构设计（v2.1）

> 定位：个人开发者让**远程** AI Agent 对真机执行**短时** ADB 级测试操作（装/卸应用、启动 Activity、注入输入、截图、取 UI 层级、抓日志）。手机无需公网 IP；免 root（Shizuku/adb 启动，Sui 路径支持 root）。
>
> v2.1 变更：补一个 ~50 行的"URL 信箱"Worker，解决**多轮启动**下 URL 反复传递的问题——配对串只需粘贴一次，长期有效。

## 0. 设计原则

1. **会话制**：开始会话 → 干活 → 结束。不做保活、断线自愈、开机自启。
2. **发现最小化**：控制面只有一个"URL 信箱"：手机会话启动时投递一次 URL，Agent 每轮连接前取一次。事件驱动、无心跳、无限流、不碰密码。
3. **安全只留必要的**：网关握手密码 + 只绑 127.0.0.1 + 信箱写保护。

## 1. 总体架构

```
 AI Agent (远程机器)
   │  ① 配对串 vibeadb://<worker-host>/<deviceId>#<password>
   │     （只粘贴一次，存进 Agent 配置，长期有效）
   │
   │  ② GET /devices/<deviceId> ──► Worker「URL 信箱」◄── ④ PUT URL（会话启动/URL 变化时）
   │  ③ 返回当前 tunnel-host             │ KV: deviceId → domain
   ▼                                    └─ Worker 不经手测试流量，接触不到密码
 trycloudflare.com (quick tunnel)
   │  ⑤ WSS 直连（数据面：命令 + 截图/APK 等大流量全走这里）
   ▼
 Android 手机
   ├─ cloudflared（App 内嵌，仅会话期间运行）
   └─ UserService 网关（Shizuku shell/root 身份，只绑 127.0.0.1）
```

多轮启动流程（无需人工介入）：

1. 手机点「开始会话」→ 拉起网关 + cloudflared → 拿到 tunnel URL → `PUT` 进信箱。
2. Agent 每次连接前 `GET` 当前 URL → 连接 → 握手验密码 → 干活。
3. 会话断开、手机重开会话 → 新 URL 已自动 `PUT`；Agent 连接失败后自动重 `GET`（短退避几次）→ 拿新 URL 重连。
4. 信箱无记录（404）= 手机端会话未运行 → Agent 明确报"请在手机上开始会话"。

单轮/无 Worker 场景：配对串也可直接填 `<tunnel-host>#<password>`（跳过信箱，一次性使用）。

## 2. 组件职责

| 组件 | 职责 | 关键点 |
|---|---|---|
| Android App | 会话启停、Shizuku 授权引导、cloudflared 管理、URL 投递、配对串展示/复制 | 前台服务仅存活于会话期间 |
| UserService 网关 | `127.0.0.1:<port>` WebSocket 服务：握手鉴权 + JSON-RPC 命令执行 | 以 shell/root UID 运行，是"ADB 能力"的真正实现者 |
| Worker（URL 信箱） | `PUT /devices/<id>`（写，验令牌）+ `GET /devices/<id>`（读） | ~50 行；只存 `{domain}`；TTL 惰性过期 |
| Agent CLI | resolve → 连接 → 鉴权 → 执行；失败自动重 resolve（有限退避） | 重试耗尽仍失败才报错 |

## 3. Shizuku 关键判定（沿用 v1 调研结论）

- **Shizuku ≠ adbd**。"接入 ADB"的落地形式是：**以 adb（shell）身份执行操作**，而不是跑真 adbd 协议（无 RSA 认证、无 ADB 传输协议、无交互 tty）。
- 免 root（Shizuku/adb 启动）时身份为 **UID 2000 / `u:r:shell:s0`**，能力 ≈ 非 root 的 `adb shell`；Sui（Magisk）时 UID 0。
- 网关实现位置首选 **UserService**（官方推荐形态）：独立进程、shell/root 身份、无 non-SDK 限制、可开 ServerSocket。`newProcess` 已废弃（API 14 移除）、无 tty、随调用方死亡。
- UserService 进程不是合法 Android app 进程：`Context#registerReceiver`、`getContentResolver` 等不可用；需实现 `destroy`（transaction `16777115`）清理并 `System.exit()`。

## 4. 能力边界

**支持（首版命令面，均验证为 shell 身份可行）：**

| 类别 | 命令 |
|---|---|
| 包管理 | `pm install / uninstall / list / grant`（install 安装者为 `com.android.shell`；APK 经 WS 上传，`pm install -S <size>` stdin 流式安装） |
| 应用控制 | `am start / stop / force-stop / broadcast` |
| 输入注入 | `input tap / swipe / text / keyevent` |
| 截图 | `screencap`（安全窗口 FLAG_SECURE 会黑屏，需容忍） |
| UI 层级 | `uiautomator dump`（AI 测试核心） |
| 日志 | `logcat`（流式订阅） |
| 状态 | `settings / dumpsys / getprop` |
| 随机测试 | `monkey` |
| 通用 | `shell`（任意命令，覆盖上表未列场景） |

**不支持 / 明确不承诺：**

- 真 adbd / 原生 `adb` CLI 兼容、scrcpy 式视频流（需自实现）、交互式 tty shell
- 免 root 下的 root-only 操作（读其他 app 私有数据等）
- 程序化开启"无线调试"、保持 adbd 存活（无相关 API）
- 具体命令可用性受 Android 版本 / OEM / SELinux 影响 → 需要测试矩阵（见 ROADMAP 风险表）

## 5. URL 信箱（唯一控制面，~50 行）

KV Schema：

```
devices:<deviceId> → { domain: string }   // 每次写时 expirationTtl=24h，惰性过期
```

- **写**：`PUT /devices/<deviceId>`，header `Authorization: Bearer <写令牌>`（Worker env 单一共享 secret，App 设置里填一次），body `{"domain": ...}`。触发时机：会话启动、cloudflared 重启导致 URL 变化（事件驱动，**无定时心跳**）。
- **读**：`GET /devices/<deviceId>`（deviceId 为 128 位随机数，即地址也是读取凭证，不可枚举）→ `{domain}`；无记录 404。
- **额度核算**：每次会话启动 ≈ 1 次 KV 写。即使每天重开 100 次会话也远低于免费版 1000 writes/day（v1 的 1min 心跳 = 1440 writes/day 是其死因）。
- **明确不做**：心跳、新鲜度判定、限流、密码哈希、多设备列表接口、cron 清理。过期记录要么被下次会话覆盖，要么 24h 后自然消失，残留无害。
- KV 为最终一致（跨站点传播最长 ~60s）：刚 `PUT` 完就 `GET` 可能拿到旧值——Agent 的失败重查退避覆盖此场景即可，不做额外设计。

## 6. 安全模型（不可裁剪项）

1. **网关握手鉴权**：握手必须校验密码，失败即断开。密码 ≥32 字符高熵，由 App 生成，仅存在于手机与 Agent 侧；**Worker 永远接触不到密码**（v1 的 resolve 反而把密码发给了 Worker）。**域名泄露 ≠ 可连接。**
2. **只绑 `127.0.0.1`**：网关仅经隧道可达；会话空闲超时自动断开。
3. **信箱写保护**：`PUT` 验共享写令牌。若不做，deviceId 泄露者可抢注域名——Agent 会连到攻击者网关并交出密码，这是本设计唯一的注入面，3 行代码封掉。

**信任边界备注**：隧道 TLS 在 Cloudflare 边缘终结，截图/命令内容对 CF 技术上可见（个人测试用途可接受）；Worker 只见 URL、不见流量与密码。deviceId 与写令牌均只存手机本地。

**明确不做**：限流、密码哈希/轮换、审计日志、CF Access Service Token。

## 7. 配对串

```
vibeadb://<worker-host>/<deviceId>#<password>   # 常规：永久有效，多轮启动免人工
vibeadb://<tunnel-host>#<password>              # 直连：单轮一次性（无 Worker 时）
```

App 一键复制；Agent 端存入配置文件（多设备 = 多条记录）。

## 8. 仓库结构

```
vibeADB/
├── android/     # Kotlin app：会话 UI + Shizuku 授权引导 + cloudflared 管理
│   └── gateway/ # UserService：WebSocket 网关（命令面实现）
├── protocol/    # JSON-RPC 方法、二进制帧格式、配对串、信箱 API（两端共享）
├── worker/      # URL 信箱（~50 行）
└── agent/       # CLI（Go）+ MCP server（TypeScript，可选）
```
