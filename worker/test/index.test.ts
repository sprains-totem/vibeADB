import { describe, expect, it } from "vitest";
import worker, { type Env } from "../src/index";

function mockKV(): KVNamespace {
  const map = new Map<string, string>();
  return {
    get: async (key: string) => map.get(key) ?? null,
    put: async (key: string, value: string) => {
      map.set(key, value);
    },
  } as unknown as KVNamespace;
}

const BASE = "https://box.example.workers.dev";
const ID = "a1b2c3d4e5f60718293a4b5c6d7e8f90"; // 32 hex

function env(kv: KVNamespace, token = "tok"): Env {
  return { KV: kv, WRITE_TOKEN: token };
}

function get(path: string, e: Env): Promise<Response> {
  return worker.fetch(new Request(BASE + path), e);
}

function put(path: string, e: Env, body: unknown, token?: string): Promise<Response> {
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (token !== undefined) headers["authorization"] = `Bearer ${token}`;
  return worker.fetch(
    new Request(BASE + path, { method: "PUT", headers, body: JSON.stringify(body) }),
    e,
  );
}

describe("mailbox /health", () => {
  it("returns ok", async () => {
    const r = await get("/health", env(mockKV()));
    expect(r.status).toBe(200);
    expect(await r.json()).toEqual({ ok: true });
  });
});

describe("mailbox resolve", () => {
  it("404 when device unknown", async () => {
    const r = await get(`/devices/${ID}`, env(mockKV()));
    expect(r.status).toBe(404);
  });

  it("roundtrip: put then get", async () => {
    const e = env(mockKV());
    const p = await put(`/devices/${ID}`, e, { domain: "aaa-bbb.trycloudflare.com" }, "tok");
    expect(p.status).toBe(200);
    const g = await get(`/devices/${ID}`, e);
    expect(g.status).toBe(200);
    expect(await g.json()).toEqual({ domain: "aaa-bbb.trycloudflare.com" });
  });

  it("rejects malformed deviceId", async () => {
    const e = env(mockKV());
    expect((await get("/devices/zzz", e)).status).toBe(400);
    expect((await get("/devices/" + "g".repeat(32), e)).status).toBe(400);
  });

  it("rejects invalid domain", async () => {
    const e = env(mockKV());
    expect((await put(`/devices/${ID}`, e, { domain: "bad host" }, "tok")).status).toBe(400);
    expect((await put(`/devices/${ID}`, e, { domain: 42 }, "tok")).status).toBe(400);
  });
});

describe("mailbox auth", () => {
  it("401 without token", async () => {
    const r = await put(`/devices/${ID}`, env(mockKV()), { domain: "x.example.com" });
    expect(r.status).toBe(401);
  });

  it("401 with wrong token", async () => {
    const r = await put(`/devices/${ID}`, env(mockKV()), { domain: "x.example.com" }, "wrong");
    expect(r.status).toBe(401);
  });

  it("401 when WRITE_TOKEN unset", async () => {
    const r = await put(
      `/devices/${ID}`,
      { KV: mockKV(), WRITE_TOKEN: "" },
      { domain: "x.example.com" },
      "",
    );
    expect(r.status).toBe(401);
  });
});

describe("mailbox misc", () => {
  it("unknown path 404", async () => {
    expect((await get("/other", env(mockKV()))).status).toBe(404);
  });

  it("method not allowed", async () => {
    const e = env(mockKV());
    const r = await worker.fetch(
      new Request(BASE + `/devices/${ID}`, { method: "DELETE" }),
      e,
    );
    expect(r.status).toBe(405);
  });
});
