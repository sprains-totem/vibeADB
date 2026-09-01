package com.vibeadb.app.shizuku;

interface IGatewayService {
    /**
     * 启动网关：出站连接边缘中继（wss://relayHost/device），等待 client 腿配对。
     * 密码用于校验 client 的 auth 帧（端到端，边缘不接触）。
     */
    boolean start(String password, String relayHost, String deviceId);

    /** "idle" / "connecting" / "online" / "retrying | lastClose: ..." */
    String status();

    /** 网关进程环形日志（实时诊断用） */
    String getLogs();

    void clearLogs();

    /** Shizuku UserService 清理回调（transaction 16777115），必须 System.exit */
    void destroy();
}
