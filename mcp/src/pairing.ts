/** 配对串解析（PROTOCOL.md §1）：vibeadb://<relay-host>/<deviceId>#<password> */

export interface PairingTarget {
  /** 中继主机名；测试环境允许带 ws:// 前缀 */
  relayHost: string;
  deviceId: string;
  password: string;
}

export function parsePairing(raw: string): PairingTarget {
  let s = raw.trim();
  if (!s.startsWith("vibeadb://")) {
    throw new Error("无效配对串：缺少 vibeadb:// 前缀");
  }
  s = s.slice("vibeadb://".length);
  const hashIdx = s.indexOf("#");
  if (hashIdx < 0) {
    throw new Error("无效配对串：缺少 #password 部分");
  }
  const body = s.slice(0, hashIdx);
  const password = s.slice(hashIdx + 1);
  if (!body || !password) {
    throw new Error("无效配对串");
  }

  // 允许 host 带协议前缀（ws:// / wss://，测试/本地环境用）
  let rest = body;
  let prefixLen = 0;
  const schemeIdx = rest.indexOf("://");
  if (schemeIdx >= 0) {
    prefixLen = schemeIdx + 3;
    rest = rest.slice(prefixLen);
  }
  const slashIdx = rest.lastIndexOf("/");
  if (slashIdx < 0) {
    throw new Error("无效配对串：v2 需要 <relay-host>/<deviceId> 形式");
  }
  const relayHost = body.slice(0, prefixLen + slashIdx);
  const deviceId = rest.slice(slashIdx + 1);
  if (!relayHost || !deviceId) {
    throw new Error("无效配对串：relay-host 或 deviceId 为空");
  }
  return { relayHost, deviceId, password };
}

/** 构造 client 腿的 WebSocket URL */
export function connectUrl(t: PairingTarget): string {
  const base = /^(ws|wss):\/\//.test(t.relayHost) ? t.relayHost : `wss://${t.relayHost}`;
  return `${base}/connect?deviceId=${encodeURIComponent(t.deviceId)}`;
}
