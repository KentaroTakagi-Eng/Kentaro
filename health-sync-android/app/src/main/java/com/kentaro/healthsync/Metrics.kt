package com.kentaro.healthsync

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Notionへ転送できるヘルスコネクトの項目一覧。
 *
 * 新しい項目を増やすときは、この `ALL_METRICS` に1行追加し、
 * AndroidManifest.xml に対応する READ_ 権限を追加するだけでよい。
 * 転送先のNotion列名やオン/オフはアプリの設定画面で変更できる。
 */
data class MetricDef(
    val id: String,
    val label: String,
    val defaultProperty: String,
    val defaultEnabled: Boolean,
    val recordType: KClass<out Record>,
    /** 歩数など1日の中で増えていく値。「上書きしない」設定でも常に最新値に更新する */
    val cumulative: Boolean = false,
    /**
     * その日（start〜end）の値を1つ返す。データがなければ null。
     * origins が空ならすべてのアプリのデータ（ヘルスコネクトの優先順位で集計）、
     * 指定があればそのアプリのデータだけを使う。
     */
    val read: suspend (HealthConnectClient, Instant, Instant, Set<DataOrigin>) -> Double?,
) {
    val permission: String get() = HealthPermission.getReadPermission(recordType)

    /** その日にこの項目を書き込んだアプリ（パッケージ名）の一覧 */
    suspend fun origins(c: HealthConnectClient, s: Instant, e: Instant): Set<String> {
        val found = mutableSetOf<String>()
        var token: String? = null
        var pages = 0
        do {
            val res = c.readRecords(ReadRecordsRequest(recordType, TimeRangeFilter.between(s, e), pageSize = 1000, pageToken = token))
            res.records.mapTo(found) { it.metadata.dataOrigin.packageName }
            token = res.pageToken
        } while (token != null && ++pages < 10)
        return found
    }
}

val ALL_METRICS: List<MetricDef> = listOf(
    MetricDef("weight", "体重 (kg)", "体重", true, WeightRecord::class) { c, s, e, o ->
        latest<WeightRecord>(c, s, e, o)?.weight?.inKilograms?.round(1)
    },
    // Notionの「体脂肪率」列は%表示なので 20.3% → 0.203 で保存する
    MetricDef("body_fat", "体脂肪率 (%)", "体脂肪率", true, BodyFatRecord::class) { c, s, e, o ->
        latest<BodyFatRecord>(c, s, e, o)?.percentage?.value?.div(100)?.round(3)
    },
    MetricDef("total_calories", "消費カロリー 合計 (kcal)", "消費カロリー", true, TotalCaloriesBurnedRecord::class, cumulative = true) { c, s, e, o ->
        aggregate(c, TotalCaloriesBurnedRecord.ENERGY_TOTAL, s, e, o)?.inKilocalories?.round(0)
    },
    MetricDef("active_calories", "活動カロリー (kcal)", "活動カロリー", false, ActiveCaloriesBurnedRecord::class, cumulative = true) { c, s, e, o ->
        aggregate(c, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, s, e, o)?.inKilocalories?.round(0)
    },
    MetricDef("steps", "歩数", "歩数", true, StepsRecord::class, cumulative = true) { c, s, e, o ->
        aggregate(c, StepsRecord.COUNT_TOTAL, s, e, o)?.toDouble()
    },
    MetricDef("bp_systolic", "血圧 上 (mmHg)", "血圧（上）", false, BloodPressureRecord::class) { c, s, e, o ->
        latest<BloodPressureRecord>(c, s, e, o)?.systolic?.inMillimetersOfMercury?.round(0)
    },
    MetricDef("bp_diastolic", "血圧 下 (mmHg)", "血圧（下）", false, BloodPressureRecord::class) { c, s, e, o ->
        latest<BloodPressureRecord>(c, s, e, o)?.diastolic?.inMillimetersOfMercury?.round(0)
    },
    MetricDef("resting_hr", "安静時心拍数 (bpm)", "脈拍", false, RestingHeartRateRecord::class) { c, s, e, o ->
        latest<RestingHeartRateRecord>(c, s, e, o)?.beatsPerMinute?.toDouble()
    },
    // その日に目覚めた睡眠の合計時間
    MetricDef("sleep_hours", "睡眠時間 (時間)", "睡眠時間", false, SleepSessionRecord::class, cumulative = true) { c, s, e, o ->
        // 前日の夜に寝始めた睡眠も拾うため、1日前から読んで「その日に目覚めた」ものだけ数える
        val sessions = c.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(s.minus(Duration.ofDays(1)), e), dataOriginFilter = o)
        ).records.filter { it.endTime >= s && it.endTime < e }
        if (sessions.isEmpty()) null
        else mergedMinutes(sessions.map { it.startTime to it.endTime }).div(60.0).round(1)
    },
)

/** バックグラウンド同期に必要な追加権限（Android 14以降のヘルスコネクト） */
const val PERMISSION_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

private suspend inline fun <reified T : Record> latest(c: HealthConnectClient, s: Instant, e: Instant, o: Set<DataOrigin>): T? =
    c.readRecords(
        ReadRecordsRequest(T::class, TimeRangeFilter.between(s, e), dataOriginFilter = o, ascendingOrder = false, pageSize = 1)
    ).records.firstOrNull()

private suspend fun <T : Any> aggregate(
    c: HealthConnectClient, metric: AggregateMetric<T>, s: Instant, e: Instant, o: Set<DataOrigin>,
): T? = c.aggregate(AggregateRequest(setOf(metric), TimeRangeFilter.between(s, e), dataOriginFilter = o))[metric]

/** 重なっている時間は1回だけ数える（複数のアプリが同じ睡眠を記録していても二重にならない） */
internal fun mergedMinutes(intervals: List<Pair<Instant, Instant>>): Long {
    var total = 0L
    var curStart: Instant? = null
    var curEnd: Instant? = null
    for ((start, end) in intervals.sortedBy { it.first }) {
        if (curEnd == null || start > curEnd) {
            if (curStart != null) total += Duration.between(curStart, curEnd).toMinutes()
            curStart = start
            curEnd = end
        } else if (end > curEnd) {
            curEnd = end
        }
    }
    if (curStart != null) total += Duration.between(curStart, curEnd).toMinutes()
    return total
}

fun Double.round(digits: Int): Double = BigDecimal(this).setScale(digits, RoundingMode.HALF_UP).toDouble()
