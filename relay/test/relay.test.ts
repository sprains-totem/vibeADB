import { describe, expect, it } from "vitest";
import { Relay, type WsLike } from "../src/index";

class FakeWS implements WsLike {
  sent: (string | ArrayBuffer)[] = [];
  closed: { code?: number; reason?: string } | null = null;
  private attachment: unknown;
  tags: string[] = [];

  send(message: string | ArrayBuffer): void {
    if (this.closed) throw new Error("sending on closed socket");
    this.sent.push(message);
  }

  close(code?: number, reason?: string): void {
    if (this.closed) return;
    this.closed = { code, reason };
  }

  serializeAttachment(annotation: unknown): void {
    this.attachment = annotation;
  }

  deserializeAttachment(): unknown {
    return this.attachment;
  }

  textFrames(): string[] {
    return this.sent.filter((m): m is string => typeof m === "string");
  }
}

class FakeState {
  private byTag = new Map<string, FakeWS[]>();

  acceptWebSocket(ws: FakeWS, tags: string[]): void {
    ws.tags = [...tags];
    for (const t of tags) {
      const list = this.byTag.get(t) ?? [];
      list.push(ws);
      this.byTag.set(t, list);
    }
  }

  getWebSockets(tag: string): FakeWS[] {
    // 模拟 workers 运行时：被 close 的 socket 会被移除
    const list = (this.byTag.get(tag) ?? []).filter((ws) => !ws.closed);
    this.byTag.set(tag, list);
    return list;
  }
}

function makeRelay() {
  const state = new FakeState();
  return { relay: new Relay(state as unknown as DurableObjectState), state };
}

describe("edge relay pairing", () => {
  it("rejects client when device is offline", () => {
    const { relay } = makeRelay();
    const client = new FakeWS();
    expect(relay.addClient(client)).toBe(false);
    expect(client.closed?.code).toBe(4004);
  });

  it("device connect -> client connect -> paired notification", () => {
    const { relay } = makeRelay();
    const device = new FakeWS();
    const client = new FakeWS();
    relay.addDevice(device);
    expect(relay.addClient(client)).toBe(true);
    expect(device.textFrames()).toContain(JSON.stringify({ op: "edge", event: "paired" }));
  });

  it("forwards frames both ways, preserving text and binary", () => {
    const { relay } = makeRelay();
    const device = new FakeWS();
    const client = new FakeWS();
    relay.addDevice(device);
    relay.addClient(client);

    const bin = new ArrayBuffer(8);
    relay.onMessage(device, '{"jsonrpc":"2.0","id":1,"method":"ping"}');
    relay.onMessage(client, bin);
    relay.onMessage(client, '{"op":"eod","id":1}');

    expect(client.textFrames()).toContain('{"jsonrpc":"2.0","id":1,"method":"ping"}');
    expect(device.sent).toContain(bin);
    expect(device.textFrames()).toContain('{"op":"eod","id":1}');
  });

  it("device close notifies and closes clients", () => {
    const { relay } = makeRelay();
    const device = new FakeWS();
    const client = new FakeWS();
    relay.addDevice(device);
    relay.addClient(client);

    relay.onClose(device);

    expect(client.textFrames()).toContain(JSON.stringify({ op: "edge", event: "device_gone" }));
    expect(client.closed?.code).toBe(4004);
  });

  it("client close notifies device", () => {
    const { relay } = makeRelay();
    const device = new FakeWS();
    const client = new FakeWS();
    relay.addDevice(device);
    relay.addClient(client);

    relay.onClose(client);

    expect(device.textFrames()).toContain(JSON.stringify({ op: "edge", event: "client_gone" }));
  });

  it("new device leg replaces old one (latest-wins)", () => {
    const { relay } = makeRelay();
    const oldDevice = new FakeWS();
    const client = new FakeWS();
    const newDevice = new FakeWS();
    relay.addDevice(oldDevice);
    relay.addClient(client);

    relay.addDevice(newDevice);

    expect(oldDevice.closed?.code).toBe(4005);
    expect(newDevice.closed).toBeNull();
    // 旧 device 腿被替换后，新腿仍与 client 配对
    relay.onMessage(newDevice, "ping-frame");
    expect(client.sent).toContain("ping-frame");
  });

  it("new client leg replaces old one (latest-wins)", () => {
    const { relay } = makeRelay();
    const device = new FakeWS();
    const oldClient = new FakeWS();
    const newClient = new FakeWS();
    relay.addDevice(device);
    relay.addClient(oldClient);

    relay.addClient(newClient);

    expect(oldClient.closed?.code).toBe(4006);
    expect(newClient.closed).toBeNull();
    // 后续 device 帧只发给新 client
    relay.onMessage(device, "frame-for-client");
    expect(oldClient.sent).not.toContain("frame-for-client");
    expect(newClient.sent).toContain("frame-for-client");
  });

  it("message from unregistered socket is rejected", () => {
    const { relay } = makeRelay();
    const stray = new FakeWS();
    relay.onMessage(stray, "hello");
    expect(stray.closed?.code).toBe(4001);
  });
});
