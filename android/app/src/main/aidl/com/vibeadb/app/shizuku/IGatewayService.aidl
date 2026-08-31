package com.vibeadb.app.shizuku;

interface IGatewayService {
    /** 启动 127.0.0.1:port 的 WebSocket 网关（鉴权密码由 App 传入） */
    boolean start(String password, int port);

    /** "running" / "idle" */
    String status();

    /** Shizuku UserService 清理回调（transaction 16777115），必须 System.exit */
    void destroy();
}
