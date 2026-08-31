/**
 * vibeADB URL 信箱
 *
 * GET  /devices/{deviceId}   读取当前隧道域名（deviceId 即读取凭证）
 * PUT  /devices/{deviceId}   写入（Bearer WRITE_TOKEN；会话启动/URL 变化时事件驱动调用）
 * GET  /health               连通性检查
 *
 * KV: devices:<deviceId> -> "<domain>"，写时 expirationTtl=86400（惰性过期）
 * 不存储密码、不代理流量、无心跳无限流。协议见 protocol/PROTOCOL.md §2。
 */

export interface Env {
  KV: KVNamespace;
  WRITE_TOKEN: string;
}

const DEVICE_ID_RE = /^[a-f0-9]{16,64}$/i;
const DOMAIN_RE =
  /^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)+$/;
const TTL_SECONDS = 86400;

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

    if (request.method === "GET" && path === "/health") {
      return json({ ok: true });
    }

    const m = path.match(/^\/devices\/([^/]+)$/);
    if (!m) {
      return json({ error: "not found" }, 404);
    }
    const deviceId = decodeURIComponent(m[1]);
    if (!DEVICE_ID_RE.test(deviceId)) {
      return json({ error: "invalid deviceId" }, 400);
    }
    const key = `devices:${deviceId}`;

    if (request.method === "GET") {
      const domain = await env.KV.get(key);
      if (!domain) {
        return json({ error: "device not found (session not running?)" }, 404);
      }
      return json({ domain });
    }

    if (request.method === "PUT") {
      const expected = `Bearer ${env.WRITE_TOKEN ?? ""}`;
      if (!env.WRITE_TOKEN || request.headers.get("Authorization") !== expected) {
        return json({ error: "unauthorized" }, 401);
      }
      let body: { domain?: unknown };
      try {
        body = await request.json();
      } catch {
        return json({ error: "invalid json" }, 400);
      }
      const domain = body.domain;
      if (typeof domain !== "string" || domain.length > 253 || !DOMAIN_RE.test(domain)) {
        return json({ error: "invalid domain" }, 400);
      }
      await env.KV.put(key, domain, { expirationTtl: TTL_SECONDS });
      return json({ ok: true });
    }

    return json({ error: "method not allowed" }, 405);
  },
};
