package com.vibeadb.app.shizuku;

interface IGatewayService {
    /**
     * 启动网关：出站连接边缘中继（wss://relayHost/device），等待 client 腿配对。
     * 密码用于校验 client 的 auth 帧（端到端，边缘不接触）。
     * sid + epoch 用于中继侧的会话栅栏（彻底杜绝多进程/旧版僵尸互踢）。
     */
    boolean start(String password, String relayHost, String deviceId, String sid, long epoch) = 1;

    /** "idle" / "connecting" / "online" / "retrying | lastClose: ..." */
    String status() = 2;

    /** 网关进程环形日志（实时诊断用） */
    String getLogs() = 3;

    void clearLogs() = 4;

    /** Shizuku UserService 专属清理事务码（16777114，对应底层 Binder 16777115） */
    void destroy() = 16777114;
}
