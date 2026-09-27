package com.kentaro.healthsync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class NotionException(val code: Int, message: String) : IOException("Notion API エラー ($code): $message")

/** Notion API の最小限のクライアント（ブロッキング。IOスレッドから呼ぶこと） */
class NotionClient(private val token: String) {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val requestBody = body?.toString()?.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.notion.com/v1/$path")
            .header("Authorization", "Bearer $token")
            .header("Notion-Version", "2022-06-28")
            .method(method, requestBody)
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                throw NotionException(resp.code, msg?.ifEmpty { null } ?: text)
            }
            return JSONObject(text)
        }
    }

    /** データベースの列名 → 型（number, checkbox, title ...） */
    fun getSchema(databaseId: String): Map<String, String> {
        val props = call("GET", "databases/$databaseId").getJSONObject("properties")
        return props.keys().asSequence().associateWith { props.getJSONObject(it).getString("type") }
    }

    /** 指定日の行（複数あれば最新に作られたもの） */
    fun findPageForDate(databaseId: String, dateProperty: String, date: LocalDate): JSONObject? {
        val body = JSONObject()
            .put("filter", JSONObject().put("property", dateProperty).put("date", JSONObject().put("equals", date.toString())))
            .put("sorts", JSONArray().put(JSONObject().put("timestamp", "created_time").put("direction", "descending")))
            .put("page_size", 1)
        val results = call("POST", "databases/$databaseId/query", body).getJSONArray("results")
        return if (results.length() > 0) results.getJSONObject(0) else null
    }

    /** 直近の行から、各列の最新の数値を集める */
    fun latestNumbers(databaseId: String, dateProperty: String, properties: List<String>): Map<String, Double> {
        if (properties.isEmpty()) return emptyMap()
        val body = JSONObject()
            .put("sorts", JSONArray().put(JSONObject().put("property", dateProperty).put("direction", "descending")))
            .put("page_size", 20)
        val results = call("POST", "databases/$databaseId/query", body).getJSONArray("results")
        val found = mutableMapOf<String, Double>()
        for (i in 0 until results.length()) {
            val page = results.getJSONObject(i)
            for (p in properties) if (p !in found) numberOf(page, p)?.let { found[p] = it }
            if (found.size == properties.size) break
        }
        return found
    }

    fun createPage(databaseId: String, properties: JSONObject): JSONObject =
        call("POST", "pages", JSONObject().put("parent", JSONObject().put("database_id", databaseId)).put("properties", properties))

    fun updatePage(pageId: String, properties: JSONObject): JSONObject =
        call("PATCH", "pages/$pageId", JSONObject().put("properties", properties))

    companion object {
        fun numberOf(page: JSONObject, property: String): Double? {
            val prop = page.optJSONObject("properties")?.optJSONObject(property) ?: return null
            return if (prop.isNull("number")) null else prop.getDouble("number")
        }

        fun number(v: Double): JSONObject = JSONObject().put("number", v)
        fun title(text: String): JSONObject =
            JSONObject().put("title", JSONArray().put(JSONObject().put("text", JSONObject().put("content", text))))
        fun date(d: LocalDate): JSONObject = JSONObject().put("date", JSONObject().put("start", d.toString()))
    }
}
