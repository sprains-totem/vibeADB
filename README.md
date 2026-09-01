# vibeADB

AI Agent 通过**原生 MCP 工具**对真机/模拟器执行 ADB 级测试操作（装/卸应用、启动 Activity、注入输入、截屏、UI 层级、日志）。免 root（Shizuku），**边缘中继架构**：手机出站 WebSocket 连恒定域名，无需公网 IP、无需 cloudflared、无 URL 轮换。

架构：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · 协议：[protocol/PROTOCOL.md](protocol/PROTOCOL.md) · v1（quick tunnel 版）在 `v1` 分支

## 组件

| 目录 | 内容 |
|---|---|
| `android/` | Kotlin+Compose App：出站连中继的 Shizuku UserService 网关（纯原生 WebSocket，通吃真机与模拟器） |
| `relay/` | Cloudflare Durable Object 边缘中继（~100 行）：按 deviceId 配对两腿，透明转发，不接触密码 |
| `mcp/` | MCP 服务器：`screenshot / tap / install_apk / shell / ui_dump / logcat_tail / …` 工具直调 |
| `protocol/` | 三端共享协议契约 |

## 三步上手

1. **部署中继**（一次性）：`cd relay && npm i && npx wrangler deploy` → 得恒定域名（详见 [relay/DEPLOY.md](relay/DEPLOY.md)）。
2. **手机**：装 Release 里的 APK → 装 [Shizuku](https://shizuku.rikka.app/) 并启动 → 设置页填中继地址 → 开始会话 → 复制配对串（永久有效）。
3. **Agent**（Claude Code 示例）：
   ```json
   { "mcpServers": { "vibeadb": {
       "command": "node",
       "args": ["/path/vibeadb-mcp/dist/index.js"],
       "env": { "VIBEADB_PAIRING": "vibeadb://<relay-host>/<deviceId>#<password>" }
   } } }
   ```
   然后直接对话式测试：`install_apk` → `am_start` → `screenshot`（模型直接看到截图）→ `ui_dump` 找坐标 → `tap` → `logcat_tail`。手机断线重连自动恢复，无需重新配对。

## CI / Release

- `ci.yml`：relay/mcp 的 vitest、android JVM 单测 + assembleRelease，全部跑在 Actions。
- `release.yml`：tag `v*` → Release 产出 **APK**、**relay zip**、**mcp zip**。
- 可选 Secrets：`ANDROID_KEYSTORE_B64` / `ANDROID_STORE_PASSWORD` / `ANDROID_KEY_PASSWORD` / `ANDROID_KEY_ALIAS`（保证签名一致；缺省时 CI 生成并上传 keystore artifact）。

## 安全模型

- 密码端到端：client 的 auth 帧经中继**原样转发**，由手机校验；边缘不接触密码与业务内容。
- 手机只出不进：无公网 IP、无本地监听；配对串 = 域名（公开可扫）+ deviceId（128 位随机，勿外传）+ 高熵密码。
- 详情见 ARCHITECTURE §5；v1 的威胁模型讨论同样适用于本版。

## 已知限制

- DO 免费档 ~100k requests/day（每条 WS 消息计 1 request）——个人测试用量远低于限额。
- 同时 1 条 client 腿（多 Agent 并行见 ROADMAP Deferred）。
- MCP 服务器与 APK 文件须在同一台机器上（`install_apk` 读本地路径）。
