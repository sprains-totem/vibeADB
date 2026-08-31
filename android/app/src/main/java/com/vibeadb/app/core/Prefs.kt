package com.vibeadb.app.core

import android.content.Context

/** 密码 / deviceId / Worker 配置的本地存储。密码不落任何服务器，只存本地。 */
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

    var workerHost: String
        get() = sp.getString(KEY_WORKER_HOST, "") ?: ""
        set(value) = sp.edit().putString(KEY_WORKER_HOST, value).apply()

    var workerToken: String
        get() = sp.getString(KEY_WORKER_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_WORKER_TOKEN, value).apply()

    fun resetPassword(): String {
        val p = Password.generate()
        sp.edit().putString(KEY_PASSWORD, p).apply()
        return p
    }

    companion object {
        private const val KEY_PASSWORD = "password"
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_WORKER_HOST = "workerHost"
        private const val KEY_WORKER_TOKEN = "workerToken"
    }
}
