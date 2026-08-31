# vibeADB 协议 v1（M0 冻结）

> 本文档是两端（Android 网关 / Go Agent）与 Worker 的唯一契约。三端实现必须与本文件一致。

## 1. 配对串（Pairing String）

```
vibeadb://<worker-host>/<deviceId>#<password>    # 常规：经信箱解析，长期有效
vibeadb://<tunnel-host>#<password>               # 直连变体：单轮一次性，无 Worker
```

- `<worker-host>`：URL 信箱 Worker 的主机名（如 `vibeadb-mailbox.xxx.workers.dev`），不含 `https://`。
- `<deviceId>`：App 生成的 32 字符小写 hex（128 bit），既是信箱地址也是读取凭证。
- `<password>`：App 生成的 32 字符 base64url（24 随机字节，无填充），字符集 `[A-Za-z0-9_-]`，不包含 `#/` 等分隔符，无需转义。
- `<tunnel-host>`：quick tunnel 域名（如 `xxx-yyy.trycloudflare.com`）。

## 2. URL 信箱 API（Worker）

信箱只存 `deviceId → 当前隧道域名`，不接触密码与流量。

### GET /devices/{deviceId}
- `deviceId` 必须匹配 `^[a-f0-9]{16,64}$`（大小写不敏感），否则 `400`。
- 命中 → `200 {"domain": "<tunnel-host>"}`。
- 未命中（或 TTL 过期）→ `404 {"error": "..."}`。Agent 语义：手机端会话未运行。

### PUT /devices/{deviceId}
- Header `Authorization: Bearer <WRITE_TOKEN>`，不匹配 → `401`。
- Body `{"domain": "<tunnel-host>"}`；`domain` 必须是合法主机名（≤253 字符），否则 `400`。
- 成功 → `200 {"ok": true}`；写 KV 时 `expirationTtl = 86400`（惰性过期，无需清理任务）。
- 调用时机：**仅事件驱动**——会话启动、隧道 URL 变化。无心跳。

### GET /health
- `200 {"ok": true}`，用于连通性检查。

## 3. 网关 WebSocket 协议

- URL：`wss://<tunnel-host>`（网关只绑 `127.0.0.1`，经 cloudflared 隧道暴露）。
- 帧类型：文本帧（JSON）与二进制帧（分块传输）。

### 3.1 鉴权（连接后第一个帧，15s 内必须完成）

```json
→ {"op": "auth", "password": "<password>"}
← {"op": "auth", "ok": true}
```

- 失败：`← {"op": "auth", "ok": false, "error": "bad password"}`，随后服务端以 `4001` 关闭。
- 鉴权前的任何其他帧 → `4001` 关闭。
- 鉴权超时（15s）→ `4002` 关闭。

### 3.2 JSON-RPC 命令（文本帧）

```json
→ {"jsonrpc": "2.0", "id": 1, "method": "shell", "params": {"command": "echo hi"}}
← {"jsonrpc": "2.0", "id": 1, "result": {"exitCode": 0, "stdout": "hi\n", "stderr": ""}}
← {"jsonrpc": "2.0", "id": 1, "error": {"code": -32000, "message": "..."}}
```

- `id`：客户端生成的递增正整数（uint32）。

### 3.3 二进制帧（大流量分块）

```
[ 4 字节 big-endian uint32：所属请求 id ][ payload ]
```

- **服务端 → 客户端**：流式输出（shell 流模式、logcat、screencap 的 PNG）。
- **客户端 → 服务端**：`pm.install` 的 APK 数据块（64KB/块建议值）。
- 每个请求遵循：`0..N 个二进制帧 → 1 个 JSON 响应帧（该请求的终结）`。

### 3.4 结束符 eod（客户端 → 服务端，上传用）

```json
→ {"op": "eod", "id": 2}
```

- `pm.install` 客户端发完所有二进制块后发送；服务端关闭进程 stdin，等待结果并回复 JSON 响应。

### 3.5 会话与超时

- 空闲超时：任何方向 5 分钟无帧 → 服务端 `4000` 关闭。
- 无交互式 tty；命令执行非持久（每次 shell 独立进程）。

## 4. JSON-RPC 方法表

| method | params | 二进制块 | result |
|---|---|---|---|
| `ping` | `{}` | - | `{"pong": true}` |
| `shell` | `{command: string, stream?: bool, timeoutSec?: int}` | stream=true 时服务端推块 | stream: `{exitCode}`；否则 `{exitCode, stdout, stderr}` |
| `screencap` | `{}` | 服务端推 1 块（PNG） | `{"size": n}` |
| `ui.dump` | `{}` | - | `{"xml": "..."}`（uiautomator dump） |
| `input` | `{kind: "tap"\|"swipe"\|"text"\|"keyevent", x?, y?, x2?, y2?, durationMs?, text?, keyCode?}` | - | `{exitCode, output}` |
| `pm.install` | `{size: long}` | 客户端传 APK 块 + eod | `{exitCode, output}` |
| `pm.uninstall` | `{package: string}` | - | `{exitCode, output}` |
| `pm.list` | `{}` | - | `{output}`（`pm list packages -3`） |
| `logcat` | `{}` | 服务端持续推块 | 客户端断开即停止 |

- `input` 经 argv 直传（不经 shell），`text` 中的空格按单参数处理。
- `shell` 经 `sh -c` 执行，支持管道；非流式默认超时 60s（可调 1~1800）。
- 任意命令也可用 `shell` 直达（方法表是其上的便利层）。

## 5. 错误码

| code | 含义 |
|---|---|
| -32700 | JSON 解析失败 |
| -32600 | 请求不合法（缺 jsonrpc 字段等） |
| -32601 | 未知方法 |
| -32602 | 参数不合法 |
| -32000 | 执行失败（命令错误/超时） |

WS 关闭码：`4000` 空闲超时，`4001` 鉴权失败/未授权，`4002` 鉴权超时。

## 6. 端到端时序

```
手机: 开始会话 → 网关 WS server(127.0.0.1:P) → cloudflared → 得 tunnel-host → PUT 信箱
Agent: 配对串 → GET 信箱得 tunnel-host → wss 连接 → auth → JSON-RPC 命令序列
手机: 停止会话 → 隧道/网关退出，URL 作废（信箱记录 TTL 自然过期）
Agent 断连: 自动重 GET 信箱（有限退避）→ 新 URL → 重连（覆盖"多轮启动"）
```
