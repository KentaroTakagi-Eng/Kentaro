package com.kentaro.healthsync

import android.app.Application
import android.os.Build

/** 予期しないエラーで落ちたとき、内容を保存して次回起動時に画面へ表示できるようにする */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val info = "アプリ ${packageManager.getPackageInfo(packageName, 0).versionName} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / ${Build.MODEL}"
                // プロセスがすぐ終了するので commit() で同期的に書き込む
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putString("crash_log", info + "\n" + e.stackTraceToString().take(4000))
                    .commit()
            }
            previous?.uncaughtException(thread, e)
        }
    }
}
