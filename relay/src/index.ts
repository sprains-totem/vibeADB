/**
 * vibeADB 边缘中继（Durable Object）+ Worker 入口
 *
 * 每个 deviceId 一个 DO 实例（idFromName）。两条 WebSocket 腿：
 *   - device 腿：Android App 出站连接（/device，header X-Device-Id）
 *   - client 腿：MCP 服务器 / 调试客户端连接（/connect?deviceId=）
 * DO 只做"配对管道"：透明转发帧（文本/二进制原样），不解析、不改写业务协议。
 *
 * 密码鉴权是端到端的：client 的 auth 帧原样转发给手机，由手机校验——边缘永远接触不到密码。
 * 配对策略：device 腿 latest-wins（新连接替换旧连接，防 squat 永久占坑）；
 *          client 腿同时只允许一条（新连接替换旧连接）；device 不在线时 client 直接被拒。
 * 边缘控制帧：{"op":"edge","event":"paired"|"client_gone"|"device_gone"}，两端自行忽略/处理。
 * 协议见 protocol/PROTOCOL.md §2。
 * 注意：Relay 类必须定义在入口文件内（wrangler 的 DO 导出检测不识别转发导出）。
 */

export interface Env {
  RELAY: DurableObjectNamespace;
}

/** 与测试/运行时解耦的最小 WebSocket 接口 */
export interface WsLike {
  send(message: string | ArrayBuffer): void;
  close(code?: number, reason?: string): void;
  serializeAttachment(annotation: unknown): void;
  deserializeAttachment(): unknown;
}

type Role = "device" | "client";

const DEVICE_ID_RE = /^[a-f0-9]{16,64}$/i;

export function isValidDeviceId(id: string): boolean {
  return DEVICE_ID_RE.test(id);
}

export class Relay {
  constructor(private readonly state: DurableObjectState) {}

  /** Worker 转发进来的升级请求路由 */
  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const deviceId = url.searchParams.get("deviceId") ?? "";
    if (!isValidDeviceId(deviceId)) {
      return json({ error: "invalid deviceId" }, 400);
    }

    if (url.pathname === "/online") {
      return json({ online: this.state.getWebSockets("device").length > 0 });
    }

    if (url.pathname === "/device") {
      const pair = new WebSocketPair();
      this.addDevice(pair[1] as unknown as WsLike);
      return ws101(pair[0]);
    }

    if (url.pathname === "/connect") {
      if (this.state.getWebSockets("device").length === 0) {
        return json({ error: "device offline (手机端会话未运行)" }, 503);
      }
      const pair = new WebSocketPair();
      this.addClient(pair[1] as unknown as WsLike);
      return ws101(pair[0]);
    }

    return json({ error: "not found" }, 404);
  }

  private roleOf(ws: WsLike): Role | undefined {
    const a = ws.deserializeAttachment() as { role?: Role } | undefined;
    return a?.role;
  }

  private peers(role: Role): WsLike[] {
    const other = role === "device" ? "client" : "device";
    return this.state.getWebSockets(other) as unknown as WsLike[];
  }

  private notifySelf(role: Role, event: string): void {
    for (const ws of this.state.getWebSockets(role) as unknown as WsLike[]) {
      try {
        ws.send(JSON.stringify({ op: "edge", event }));
      } catch {
        /* ignore */
      }
    }
  }

  /** 手机出站连接（device 腿）。latest-wins：替换旧 device 腿。 */
  addDevice(ws: WsLike): void {
    for (const old of this.state.getWebSockets("device") as unknown as WsLike[]) {
      try {
        old.close(4005, "replaced by newer device connection");
      } catch {
        /* ignore */
      }
    }
    this.state.acceptWebSocket(ws as unknown as WebSocket, ["device"]);
    ws.serializeAttachment({ role: "device" });
  }

  /** client 腿。调用方需保证 device 在线；latest-wins 替换旧 client。 */
  addClient(ws: WsLike): boolean {
    if (this.state.getWebSockets("device").length === 0) {
      try {
        ws.close(4004, "device offline (session not running?)");
      } catch {
        /* ignore */
      }
      return false;
    }
    for (const old of this.state.getWebSockets("client") as unknown as WsLike[]) {
      try {
        old.close(4006, "replaced by newer client connection");
      } catch {
        /* ignore */
      }
    }
    this.state.acceptWebSocket(ws as unknown as WebSocket, ["client"]);
    ws.serializeAttachment({ role: "client" });
    this.notifySelf("device", "paired");
    return true;
  }

  /** 帧转发：device<->client，文本/二进制原样透传 */
  onMessage(ws: WsLike, message: string | ArrayBuffer): void {
    const role = this.roleOf(ws);
    if (!role) {
      try {
        ws.close(4001, "unregistered socket");
      } catch {
        /* ignore */
      }
      return;
    }
    for (const peer of this.peers(role)) {
      try {
        peer.send(message);
      } catch {
        /* ignore */
      }
    }
  }

  /** Hibernation API 运行时回调：收到帧 → 转发 */
  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    this.onMessage(ws as unknown as WsLike, message);
  }

  /** Hibernation API 运行时回调：连接关闭 → 通知对端 */
  async webSocketClose(ws: WebSocket, code: number, reason: string, wasClean: boolean): Promise<void> {
    this.onClose(ws as unknown as WsLike);
  }

  async webSocketError(ws: WebSocket, err: unknown): Promise<void> {
    /* ignore */
  }

  onClose(ws: WsLike): void {
    const role = this.roleOf(ws);
    if (role === "device") {
      this.notifySelf("client", "device_gone");
      for (const c of this.peers("device")) {
        try {
          c.close(4004, "device disconnected");
        } catch {
          /* ignore */
        }
      }
    } else if (role === "client") {
      this.notifySelf("device", "client_gone");
    }
  }
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function ws101(client: WebSocket): Response {
  return new Response(null, { status: 101, webSocket: client });
}

/** Worker 入口 */
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    if (path === "/health") {
      return json({ ok: true, service: "vibeadb-relay" });
    }

    let deviceId = "";
    let role: "device" | "client" | null = null;
    if (path === "/device" && request.method === "GET") {
      role = "device";
      deviceId = request.headers.get("x-device-id") ?? "";
    } else if (path === "/connect" && request.method === "GET") {
      role = "client";
      deviceId = url.searchParams.get("deviceId") ?? "";
    }

    if (!role) {
      return json({ error: "not found" }, 404);
    }
    if (!isValidDeviceId(deviceId)) {
      return json({ error: "invalid deviceId (expect 16-64 hex chars)" }, 400);
    }

    const id = env.RELAY.idFromName(deviceId.toLowerCase());
    const stub = env.RELAY.get(id);

    // 归一化后转发升级请求给 DO（DO 内部路由：/device、/connect）
    const doPath = role === "device" ? "/device" : "/connect";
    const relayUrl = new URL(`https://relay.internal${doPath}`);
    relayUrl.searchParams.set("deviceId", deviceId.toLowerCase());
    return stub.fetch(new Request(relayUrl.toString(), request));
  },
};
