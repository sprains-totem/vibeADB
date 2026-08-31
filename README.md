# vibeADB

让远程 AI Agent 通过公网 HTTPS 对真机执行 ADB 级测试操作（装/卸应用、启动 Activity、注入输入、截图、取 UI 层级、抓日志）。手机无需公网 IP，免 root（Shizuku），**会话制短时使用**，无保活设计。

设计文档：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · 路线图：[docs/ROADMAP.md](docs/ROADMAP.md) · 协议：[protocol/PROTOCOL.md](protocol/PROTOCOL.md)

## 组件

| 目录 | 内容 |
|---|---|
| `android/` | Kotlin + Compose App：会话启停、Shizuku UserService 网关（WS + JSON-RPC）、内嵌 cloudflared、信箱投递 |
| `worker/` | URL 信箱（~50 行 TS）：`deviceId → 当前隧道域名`，事件驱动写，无心跳 |
| `agent/` | Go CLI：解析配对串 → 信箱解析 → WSS 连接 → shell/screenshot/ui/tap/install/logcat |
| `protocol/` | 三端共享协议契约（M0 冻结） |

## 使用流程

1. **部署 Worker**（一次性）：见 [worker/DEPLOY.md](worker/DEPLOY.md)，得到 Worker 地址 + 自己设置 `WRITE_TOKEN`。
2. **装 App**：从 GitHub Release 下载 `vibeadb-*-release.apk` 安装；设置里填 Worker 地址与写令牌。
3. **开始会话**：手机上安装并启动 [Shizuku](https://shizuku.rikka.app/)（无线调试或 root 方式）→ App 点「开始会话」→ 点「复制配对串」发给 Agent。
4. **Agent 侧**：
   ```bash
   export VIBEADB_PAIRING='vibeadb://<worker-host>/<deviceId>#<password>'
   vibeadb ping
   vibeadb screenshot
   vibeadb tap 500 1200
   vibeadb install app-debug.apk
   vibeadb shell 'dumpsys battery'
   ```
   手机重开会话后 Agent 自动重连（信箱解析新 URL），无需重新发配对串。

## CI / Release

- `ci.yml`：push/PR 触发——Worker vitest 测试、Go 测试、Android JVM 单测 + release APK 构建（临时签名）。
- `release.yml`：push tag `v*` 触发——产出 GitHub Release：**release APK**（签名见下）、**worker file**（`vibeadb-worker-*.zip`，含构建产物与部署说明）、多平台 Agent 二进制。
- 全部编译/测试在 Actions 上执行，本地无需任何构建环境。

可选 Secrets（release 签名一致性）：

| Secret | 内容 |
|---|---|
| `ANDROID_KEYSTORE_B64` | base64 后的 keystore（`base64 -w0 release.keystore`） |
| `ANDROID_STORE_PASSWORD` / `ANDROID_KEY_PASSWORD` / `ANDROID_KEY_ALIAS` | 对应凭证 |

未配置时使用 CI 临时生成的 keystore（每次 tag 签名不同，覆盖安装需先卸载；生成的 keystore 会作为 artifact 上传，可保存后配置为 Secret）。

## 安全模型（两条）

1. 网关握手验密码（32 字符高熵，App 生成，仅存手机与 Agent 侧，**信箱永不接触密码**）。
2. 网关只绑 `127.0.0.1`，仅经隧道可达；会话空闲 5 分钟自动断开。

详细取舍见 ARCHITECTURE §5-§6。注意：隧道流量经 Cloudflare 边缘（TLS 终结），个人测试用途可接受。

## 已知限制

- APK 仅打包 `arm64-v8a` 的 cloudflared（现代设备均覆盖）；x86 模拟器不支持。
- `pm install` 以 shell 身份（安装者 `com.android.shell`）；部分 OEM/SELinux 下的命令差异需真机验证（见 ROADMAP 风险表）。
- MCP server 为可选件，暂未实现（协议已冻结，随时可包一层）。
