package com.vibeadb.app.core

/** 从 cloudflared 日志中提取 quick tunnel 域名 */
object UrlParser {
    private val RE = Regex("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com")

    /** 返回不含 https:// 前缀的域名，无匹配返回 null */
    fun findTunnelHost(line: String): String? =
        RE.find(line)?.value?.removePrefix("https://")
}
