package com.vibeadb.app.mailbox

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * URL 信箱客户端（PROTOCOL.md §2）。
 * 只上传当前隧道域名，不接触密码。
 */
class MailboxClient(private val host: String, private val token: String) {

    /** PUT /devices/{deviceId}；返回 true = 2xx */
    fun put(deviceId: String, domain: String): Boolean {
        val base = if (host.startsWith("http")) host else "https://$host"
        val conn = (URL("$base/devices/$deviceId").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            conn.outputStream.use {
                it.write(JSONObject().put("domain", domain).toString().toByteArray())
            }
            return conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }
}
