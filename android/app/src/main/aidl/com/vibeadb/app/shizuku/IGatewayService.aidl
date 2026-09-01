package com.vibeadb.app.shizuku;

interface IGatewayService {
    /**
     * 启动网关：出站连接边缘中继（wss://relayHost/device），等待 client 腿配对。
     * 密码用于校验 client 的 auth 帧（端到端，边缘不接触）。
     */
    boolean start(String password, String relayHost, String deviceId);

    /** "idle" / "connecting" / "online" / "retrying" */
    String status();

    /** Shizuku UserService 清理回调（transaction 16777115），必须 System.exit */
    void destroy();
}
