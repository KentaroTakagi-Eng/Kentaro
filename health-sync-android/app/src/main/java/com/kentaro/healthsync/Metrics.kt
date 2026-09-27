package com.kentaro.healthsync

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
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
    /** その日（start〜end）の値を1つ返す。データがなければ null。 */
    val read: suspend (HealthConnectClient, Instant, Instant) -> Double?,
) {
    val permission: String get() = HealthPermission.getReadPermission(recordType)
}

val ALL_METRICS: List<MetricDef> = listOf(
    MetricDef("weight", "体重 (kg)", "体重", true, WeightRecord::class) { c, s, e ->
        latest<WeightRecord>(c, s, e)?.weight?.inKilograms?.round(1)
    },
    // Notionの「体脂肪率」列は%表示なので 20.3% → 0.203 で保存する
    MetricDef("body_fat", "体脂肪率 (%)", "体脂肪率", true, BodyFatRecord::class) { c, s, e ->
        latest<BodyFatRecord>(c, s, e)?.percentage?.value?.div(100)?.round(3)
    },
    MetricDef("total_calories", "消費カロリー 合計 (kcal)", "消費カロリー", true, TotalCaloriesBurnedRecord::class) { c, s, e ->
        aggregate(c, TotalCaloriesBurnedRecord.ENERGY_TOTAL, s, e)?.inKilocalories?.round(0)
    },
    MetricDef("active_calories", "活動カロリー (kcal)", "活動カロリー", false, ActiveCaloriesBurnedRecord::class) { c, s, e ->
        aggregate(c, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, s, e)?.inKilocalories?.round(0)
    },
    MetricDef("steps", "歩数", "歩数", true, StepsRecord::class) { c, s, e ->
        aggregate(c, StepsRecord.COUNT_TOTAL, s, e)?.toDouble()
    },
    MetricDef("bp_systolic", "血圧 上 (mmHg)", "血圧（上）", false, BloodPressureRecord::class) { c, s, e ->
        latest<BloodPressureRecord>(c, s, e)?.systolic?.inMillimetersOfMercury?.round(0)
    },
    MetricDef("bp_diastolic", "血圧 下 (mmHg)", "血圧（下）", false, BloodPressureRecord::class) { c, s, e ->
        latest<BloodPressureRecord>(c, s, e)?.diastolic?.inMillimetersOfMercury?.round(0)
    },
    MetricDef("resting_hr", "安静時心拍数 (bpm)", "脈拍", false, RestingHeartRateRecord::class) { c, s, e ->
        latest<RestingHeartRateRecord>(c, s, e)?.beatsPerMinute?.toDouble()
    },
    // その日に目覚めた睡眠の合計時間
    MetricDef("sleep_hours", "睡眠時間 (時間)", "睡眠時間", false, SleepSessionRecord::class) { c, s, e ->
        val sessions = c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(s, e)))
            .records.filter { it.endTime >= s && it.endTime < e }
        if (sessions.isEmpty()) null
        else sessions.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.div(60.0).round(1)
    },
)

/** バックグラウンド同期に必要な追加権限（Android 14以降のヘルスコネクト） */
const val PERMISSION_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

private suspend inline fun <reified T : Record> latest(c: HealthConnectClient, s: Instant, e: Instant): T? =
    c.readRecords(
        ReadRecordsRequest(T::class, TimeRangeFilter.between(s, e), ascendingOrder = false, pageSize = 1)
    ).records.firstOrNull()

private suspend fun <T : Any> aggregate(c: HealthConnectClient, metric: AggregateMetric<T>, s: Instant, e: Instant): T? =
    c.aggregate(AggregateRequest(setOf(metric), TimeRangeFilter.between(s, e)))[metric]

fun Double.round(digits: Int): Double = BigDecimal(this).setScale(digits, RoundingMode.HALF_UP).toDouble()
