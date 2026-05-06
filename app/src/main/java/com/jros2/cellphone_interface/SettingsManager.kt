package com.jros2.cellphone_interface

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ros2_settings", Context.MODE_PRIVATE)

    var domainId: Int
        get() = prefs.getInt("domain_id", 0)
        set(value) = prefs.edit().putInt("domain_id", value).apply()

    var namespace: String
        get() = prefs.getString("namespace", "phone") ?: "phone"
        set(value) = prefs.edit().putString("namespace", value.trim('/')).apply()

    fun getTopicName(sensorId: String, defaultName: String): String {
        return prefs.getString("topic_$sensorId", defaultName) ?: defaultName
    }

    fun setTopicName(sensorId: String, topicName: String) {
        prefs.edit().putString("topic_$sensorId", topicName).apply()
    }
}
