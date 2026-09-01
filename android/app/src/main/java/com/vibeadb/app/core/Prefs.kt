package com.vibeadb.app.core

import android.content.Context

/** 密码 / deviceId / 中继地址的本地存储。密码不落任何服务器，只存本地。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("vibeadb", Context.MODE_PRIVATE)

    val password: String
        get() = sp.getString(KEY_PASSWORD, null) ?: Password.generate().also {
            sp.edit().putString(KEY_PASSWORD, it).apply()
        }

    val deviceId: String
        get() = sp.getString(KEY_DEVICE_ID, null) ?: Password.deviceId().also {
            sp.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    /** 边缘中继域名（恒定，如 vibeadb-relay.xxx.workers.dev） */
    var relayHost: String
        get() = sp.getString(KEY_RELAY_HOST, "") ?: ""
        set(value) = sp.edit().putString(KEY_RELAY_HOST, value).apply()

    fun resetPassword(): String {
        val p = Password.generate()
        sp.edit().putString(KEY_PASSWORD, p).apply()
        return p
    }

    companion object {
        private const val KEY_PASSWORD = "password"
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_RELAY_HOST = "relayHost"
    }
}
