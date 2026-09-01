/**
 * vibeADB 边缘中继 Worker 入口
 *
 * 路由：
 *   GET /health                        连通性检查
 *   GET /device   (X-Device-Id: id)    手机出站 WebSocket（device 腿）
 *   GET /connect?deviceId=<id>         client WebSocket（MCP / 调试客户端）
 * 每个 deviceId 一个 Durable Object 实例（idFromName）。
 * 密码鉴权端到端（手机侧校验），边缘不接触任何秘密。
 */

import { Env, isValidDeviceId } from "./relay";

export { Relay };

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json" },
  });
}

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

    // 归一化后转发升级请求给 DO（deviceId 进 query，DO 内部 addDevice/addClient）
    const relayUrl = new URL(`https://relay.internal/${role}`);
    relayUrl.searchParams.set("deviceId", deviceId.toLowerCase());
    return stub.fetch(new Request(relayUrl.toString(), request));
  },
};
