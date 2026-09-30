package com.kentaro.healthsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { SettingsScreen() }
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf(settings.notionToken) }
    var databaseId by remember { mutableStateOf(settings.databaseId) }
    var titleProperty by remember { mutableStateOf(settings.titleProperty) }
    var dateProperty by remember { mutableStateOf(settings.dateProperty) }
    var titleFormat by remember { mutableStateOf(settings.titleFormat) }
    var carryOver by remember { mutableStateOf(settings.carryOverProperties) }
    var syncDays by remember { mutableStateOf(settings.syncDays.toString()) }
    var intervalHours by remember { mutableStateOf(settings.intervalHours.toString()) }
    var overwrite by remember { mutableStateOf(settings.overwrite) }
    val metrics = remember { mutableStateListOf<MetricSetting>().apply { addAll(settings.metricSettings()) } }
    var status by remember { mutableStateOf(settings.lastLog) }
    var busy by remember { mutableStateOf(false) }
    var grantedCount by remember { mutableStateOf(0) }
    var hcError by remember { mutableStateOf("") }
    // 項目ID → (自動の値, アプリごとの値)
    val breakdown = remember { mutableStateMapOf<String, Pair<Double?, List<Pair<String, Double?>>>>() }
    var breakdownError by remember { mutableStateOf("") }
    var crashLog by remember { mutableStateOf(settings.crashLog) }

    val sdkStatus = remember { runCatching { HealthConnectClient.getSdkStatus(context) }.getOrDefault(HealthConnectClient.SDK_UNAVAILABLE) }
    val hcAvailable = sdkStatus == HealthConnectClient.SDK_AVAILABLE

    fun wantedPermissions(): Set<String> =
        metrics.filter { it.enabled }.mapNotNull { m -> ALL_METRICS.find { it.id == m.id }?.permission }.toSet() +
            PERMISSION_BACKGROUND

    suspend fun refreshGranted() {
        if (!hcAvailable) return
        // ここで例外が出てもアプリを落とさず、画面に理由を出す
        try {
            val granted = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
            grantedCount = wantedPermissions().count { it in granted }
            hcError = ""
        } catch (e: Exception) {
            hcError = "ヘルスコネクトの確認に失敗: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { scope.launch { refreshGranted() } }

    LaunchedEffect(Unit) { refreshGranted() }

    fun save() {
        settings.notionToken = token
        settings.databaseId = Settings.normalizeId(databaseId)
        databaseId = settings.databaseId
        settings.titleProperty = titleProperty
        settings.dateProperty = dateProperty
        settings.titleFormat = titleFormat
        settings.carryOverProperties = carryOver
        settings.syncDays = syncDays.toIntOrNull() ?: 2
        settings.intervalHours = intervalHours.toIntOrNull() ?: 3
        settings.overwrite = overwrite
        settings.saveMetricSettings(metrics)
        SyncEngine.schedule(context)
    }

    fun runSync(days: Int? = null) {
        save()
        busy = true
        status = "同期中…"
        scope.launch {
            status = try {
                SyncEngine(context).sync(days ?: settings.syncDays)
            } catch (e: Exception) {
                "エラー: ${e.message}"
            }
            settings.lastLog = status
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ヘルスコネクト → Notion", style = MaterialTheme.typography.headlineSmall)

        if (crashLog.isNotEmpty()) {
            Section("前回アプリが異常終了しました") {
                Text("下の内容を長押しでコピーして、開発者（Claude）に送ってください。", style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(crashLog, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { settings.crashLog = ""; crashLog = "" }) { Text("閉じる") }
            }
        }

        // 1. ヘルスコネクト
        Section("1. ヘルスコネクト") {
            if (!hcAvailable) {
                Text("ヘルスコネクトが使えません。Playストアからインストール/更新してください。")
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                        )
                    }
                }) { Text("Playストアを開く") }
            } else {
                Text("許可済み: $grantedCount / ${wantedPermissions().size}")
                if (hcError.isNotEmpty()) SelectionContainer { Text(hcError, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    save()
                    try {
                        permissionLauncher.launch(wantedPermissions())
                    } catch (e: Exception) {
                        hcError = "許可画面を開けません: ${e.javaClass.simpleName}: ${e.message}"
                    }
                }) { Text("読み取りを許可する") }
            }
        }

        // 2. Notion
        Section("2. Notion") {
            OutlinedTextField(
                token, { token = it }, Modifier.fillMaxWidth(), label = { Text("インテグレーションのトークン") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(databaseId, { databaseId = it }, Modifier.fillMaxWidth(), label = { Text("データベースのURLまたはID") }, singleLine = true)
            OutlinedButton(enabled = !busy, onClick = {
                save()
                busy = true
                scope.launch {
                    status = try {
                        val schema = withContext(Dispatchers.IO) {
                            NotionClient(settings.notionToken).getSchema(settings.databaseId)
                        }
                        "接続OK。列一覧:\n" + schema.entries.joinToString("\n") { "・${it.key}（${it.value}）" }
                    } catch (e: Exception) {
                        "接続失敗: ${e.message}"
                    }
                    busy = false
                }
            }) { Text("接続テスト（列一覧を表示）") }
        }

        // 3. 項目
        Section("3. 転送する項目 → Notionの列名") {
            Text("列名を変えればNotion側の好きな数値列に書き込めます。", style = MaterialTheme.typography.bodySmall)
            metrics.forEachIndexed { i, m ->
                val def = ALL_METRICS.first { it.id == m.id }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(m.enabled, { metrics[i] = m.copy(enabled = it) })
                    Spacer(Modifier.padding(4.dp))
                    OutlinedTextField(
                        m.property, { metrics[i] = m.copy(property = it) }, Modifier.fillMaxWidth(),
                        label = { Text(def.label) }, singleLine = true, enabled = m.enabled,
                    )
                }
                if (m.enabled && m.source.isNotBlank()) {
                    Text("　データ元: ${appLabel(context, m.source)} のみ", style = MaterialTheme.typography.bodySmall)
                }
                val b = breakdown[m.id]
                if (m.enabled && b != null) {
                    Column(Modifier.padding(start = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("自動（全アプリ）: ${formatValue(b.first)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = m.source.isNotBlank(), onClick = { metrics[i] = m.copy(source = "") }) {
                                Text(if (m.source.isBlank()) "使用中" else "これを使う")
                            }
                        }
                        b.second.forEach { (pkg, v) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${appLabel(context, pkg)}: ${formatValue(v)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(enabled = m.source != pkg, onClick = { metrics[i] = m.copy(source = pkg) }) {
                                    Text(if (m.source == pkg) "使用中" else "これを使う")
                                }
                            }
                        }
                    }
                }
            }
            OutlinedButton(enabled = !busy && hcAvailable, onClick = {
                busy = true
                breakdownError = ""
                scope.launch {
                    val engine = SyncEngine(context)
                    for (m in metrics.filter { it.enabled }) {
                        val def = ALL_METRICS.first { it.id == m.id }
                        try {
                            breakdown[m.id] = engine.todayBreakdown(def)
                        } catch (e: Exception) {
                            breakdownError += "${def.label}: ${e.message}\n"
                        }
                    }
                    busy = false
                }
            }) { Text("今日の値をアプリごとに確認") }
            Text(
                "値が他のアプリの表示と合わないときは、確認ボタンを押して、正しいアプリの「これを使う」を選び、保存して同期してください。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (breakdownError.isNotEmpty()) Text(breakdownError, color = MaterialTheme.colorScheme.error)
        }

        // 4. 詳細
        Section("4. 詳細設定") {
            OutlinedTextField(titleProperty, { titleProperty = it }, Modifier.fillMaxWidth(), label = { Text("タイトル列") }, singleLine = true)
            OutlinedTextField(titleFormat, { titleFormat = it }, Modifier.fillMaxWidth(), label = { Text("タイトルの書式") }, singleLine = true)
            OutlinedTextField(dateProperty, { dateProperty = it }, Modifier.fillMaxWidth(), label = { Text("日付列") }, singleLine = true)
            OutlinedTextField(
                carryOver, { carryOver = it }, Modifier.fillMaxWidth(),
                label = { Text("新しい行に前日から引き継ぐ列（カンマ区切り）") }, singleLine = true,
            )
            OutlinedTextField(
                syncDays, { syncDays = it }, Modifier.fillMaxWidth(), label = { Text("毎回さかのぼる日数") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                intervalHours, { intervalHours = it }, Modifier.fillMaxWidth(), label = { Text("自動同期の間隔（時間）") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(overwrite, { overwrite = it })
                Spacer(Modifier.padding(4.dp))
                Text("Notionに値があっても上書きする")
            }
            Text("歩数・消費カロリー・睡眠時間は1日の中で増えるため、この設定に関係なく常に最新値に更新します。", style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { runSync() }) {
                Text("保存して今すぐ同期")
            }
            OutlinedButton(enabled = !busy, onClick = { runSync(30) }) { Text("過去30日を同期") }
        }

        HorizontalDivider()
        Text(status.ifEmpty { "まだ同期していません" }, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(32.dp))
    }
}

private fun formatValue(v: Double?): String = when {
    v == null -> "データなし"
    v % 1.0 == 0.0 -> "%,d".format(v.toLong())
    else -> v.toString()
}

/** パッケージ名をアプリ名にする（見つからなければパッケージ名のまま） */
private fun appLabel(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
