package com.kentaro.healthsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** ヘルスコネクトの権限画面から開かれる、データの使いみちの説明 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                        Text("データの使いみち", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "このアプリは、ヘルスコネクトの体重・体脂肪率・消費カロリーなどを読み取り、" +
                                "あなた自身のNotionデータベースに書き込むためだけに使います。" +
                                "データは他の場所へは送信しません。",
                            Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
