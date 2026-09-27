package com.kentaro.healthsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 項目ごとの設定（転送するか / 書き込み先のNotion列名） */
data class MetricSetting(val id: String, val enabled: Boolean, val property: String)

/** アプリの設定。すべて画面から変更でき、端末内に保存される。 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var notionToken: String
        get() = prefs.getString("notion_token", "") ?: ""
        set(v) = prefs.edit().putString("notion_token", v.trim()).apply()

    var databaseId: String
        get() = prefs.getString("database_id", DEFAULT_DATABASE_ID) ?: DEFAULT_DATABASE_ID
        set(v) = prefs.edit().putString("database_id", v.trim()).apply()

    var titleProperty: String
        get() = prefs.getString("title_property", "記録") ?: "記録"
        set(v) = prefs.edit().putString("title_property", v.trim()).apply()

    var dateProperty: String
        get() = prefs.getString("date_property", "測定日") ?: "測定日"
        set(v) = prefs.edit().putString("date_property", v.trim()).apply()

    var titleFormat: String
        get() = prefs.getString("title_format", "yyyy.MM.dd") ?: "yyyy.MM.dd"
        set(v) = prefs.edit().putString("title_format", v.trim()).apply()

    /** 新しい日の行を作るとき、直近の行から引き継ぐ数値列（BMI計算用の身長など） */
    var carryOverProperties: String
        get() = prefs.getString("carry_over", "身長,目標体重") ?: ""
        set(v) = prefs.edit().putString("carry_over", v).apply()

    var syncDays: Int
        get() = prefs.getInt("sync_days", 2)
        set(v) = prefs.edit().putInt("sync_days", v.coerceIn(1, 30)).apply()

    var intervalHours: Int
        get() = prefs.getInt("interval_hours", 3)
        set(v) = prefs.edit().putInt("interval_hours", v.coerceIn(1, 24)).apply()

    /** Notionにすでに値があっても上書きするか */
    var overwrite: Boolean
        get() = prefs.getBoolean("overwrite", true)
        set(v) = prefs.edit().putBoolean("overwrite", v).apply()

    var lastLog: String
        get() = prefs.getString("last_log", "") ?: ""
        set(v) = prefs.edit().putString("last_log", v).apply()

    /** 前回アプリが落ちたときのエラー内容（App.kt が保存する） */
    var crashLog: String
        get() = prefs.getString("crash_log", "") ?: ""
        set(v) = prefs.edit().putString("crash_log", v).apply()

    fun carryOverList(): List<String> =
        carryOverProperties.split(',', '、').map { it.trim() }.filter { it.isNotEmpty() }

    /** 保存済みの設定と ALL_METRICS をマージする（新しく追加した項目は初期値で出てくる） */
    fun metricSettings(): List<MetricSetting> {
        val saved = mutableMapOf<String, MetricSetting>()
        runCatching {
            val arr = JSONArray(prefs.getString("metrics", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                saved[o.getString("id")] = MetricSetting(o.getString("id"), o.getBoolean("enabled"), o.getString("property"))
            }
        }
        return ALL_METRICS.map { saved[it.id] ?: MetricSetting(it.id, it.defaultEnabled, it.defaultProperty) }
    }

    fun saveMetricSettings(list: List<MetricSetting>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("enabled", it.enabled).put("property", it.property.trim()))
        }
        prefs.edit().putString("metrics", arr.toString()).apply()
    }

    companion object {
        /** 「ダイエット記録 ~Diet log~」の Diet log DB */
        const val DEFAULT_DATABASE_ID = "264623d2a18c81eabe42c4e133658a25"

        /** NotionのURLやハイフン付きIDから32桁のIDを取り出す */
        fun normalizeId(input: String): String {
            val hex = Regex("[0-9a-fA-F]{32}")
            val compact = input.substringBefore('?').replace("-", "")
            return hex.findAll(compact).lastOrNull()?.value ?: input.trim()
        }
    }
}
