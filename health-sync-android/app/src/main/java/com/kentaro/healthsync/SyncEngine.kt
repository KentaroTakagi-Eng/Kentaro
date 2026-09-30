package com.kentaro.healthsync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** ヘルスコネクトの値を読み、Notionの「その日の行」に書き込む */
class SyncEngine(private val context: Context) {
    private val settings = Settings(context)

    suspend fun sync(days: Int = settings.syncDays): String = withContext(Dispatchers.IO) {
        val log = StringBuilder()
        log.appendLine("同期開始 ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("M/d HH:mm"))}")

        require(settings.notionToken.isNotBlank()) { "Notionのトークンが未設定です" }
        val dbId = Settings.normalizeId(settings.databaseId)
        val notion = NotionClient(settings.notionToken)
        val hc = HealthConnectClient.getOrCreate(context)
        val granted = hc.permissionController.getGrantedPermissions()

        // 転送対象の項目を決める（オフ・権限なし・Notionに列がないものは除外）
        val schema = notion.getSchema(dbId)
        val defs = ALL_METRICS.associateBy { it.id }
        val targets = settings.metricSettings().filter { it.enabled }.mapNotNull { s ->
            val def = defs[s.id] ?: return@mapNotNull null
            when {
                def.permission !in granted -> { log.appendLine("・${def.label}: 権限がないためスキップ"); null }
                schema[s.property] != "number" -> {
                    log.appendLine("・${def.label}: Notionに数値列「${s.property}」がないためスキップ"); null
                }
                else -> def to s.property
            }
        }
        if (targets.isEmpty()) {
            log.appendLine("転送できる項目がありません")
            return@withContext log.toString()
        }

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val titleFormatter = DateTimeFormatter.ofPattern(settings.titleFormat)

        for (offset in (days - 1) downTo 0) {
            val date = today.minusDays(offset.toLong())
            val start = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant()

            val values = linkedMapOf<String, Double>()
            for ((def, property) in targets) {
                runCatching { def.read(hc, start, end) }
                    .onSuccess { v -> if (v != null) values[property] = v }
                    .onFailure { log.appendLine("・${def.label}: 読み取り失敗 ${it.message}") }
            }
            if (values.isEmpty()) {
                log.appendLine("$date: ヘルスコネクトにデータなし")
                continue
            }

            val page = notion.findPageForDate(dbId, settings.dateProperty, date)
            val props = JSONObject()
            if (page == null) {
                // 新しい行を作る。BMI計算に使う身長などは直近の行から引き継ぐ
                val carry = settings.carryOverList().filter { schema[it] == "number" && it !in values }
                notion.latestNumbers(dbId, settings.dateProperty, carry).forEach { (k, v) -> props.put(k, NotionClient.number(v)) }
                values.forEach { (k, v) -> props.put(k, NotionClient.number(v)) }
                props.put(settings.titleProperty, NotionClient.title(date.format(titleFormatter)))
                props.put(settings.dateProperty, NotionClient.date(date))
                notion.createPage(dbId, props)
                log.appendLine("$date: 新規作成 ${describe(values)}")
            } else {
                val cumulative = targets.filter { it.first.cumulative }.map { it.second }.toSet()
                values.forEach { (k, v) ->
                    val current = NotionClient.numberOf(page, k)
                    val write = if (k in cumulative) current != v else settings.overwrite || current == null
                    if (write) props.put(k, NotionClient.number(v))
                }
                if (props.length() == 0) {
                    log.appendLine("$date: 変更なし")
                } else {
                    notion.updatePage(page.getString("id"), props)
                    log.appendLine("$date: 更新 ${describe(values.filterKeys { props.has(it) })}")
                }
            }
        }
        log.toString()
    }

    private fun describe(values: Map<String, Double>) =
        values.entries.joinToString(" / ") { (k, v) -> "$k=${if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()}" }

    companion object {
        private const val WORK_NAME = "health-sync"

        fun schedule(context: Context) {
            val hours = Settings(context).intervalHours.toLong()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(hours, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

/** 定期的にバックグラウンドで同期する */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = Settings(applicationContext)
        return try {
            settings.lastLog = "[自動] " + SyncEngine(applicationContext).sync()
            Result.success()
        } catch (e: IOException) {
            settings.lastLog = "[自動] 通信エラー: ${e.message}"
            Result.retry()
        } catch (e: Exception) {
            settings.lastLog = "[自動] エラー: ${e.message}"
            Result.failure()
        }
    }
}
