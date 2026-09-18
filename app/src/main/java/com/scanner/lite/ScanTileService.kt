package com.scanner.lite

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi

class ScanTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()

        val intent = Intent(this, MainActivity::class.java).apply {
            action = "com.scanner.lite.ACTION_START_SCAN"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        // 切回主线程执行，兼容部分定制系统（如 ColorOS/OriginOS）对后台拉起 Activity 的限制
        Handler(Looper.getMainLooper()).post {
            if (isLocked) {
                unlockAndRun {
                    performLaunch(intent)
                }
            } else {
                performLaunch(intent)
            }
        }
    }

    private fun performLaunch(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ (API 34+)：使用 PendingIntent 独立类
            Api34Impl.startActivityAndCollapse(this, intent)
        } else {
            // Android 12 ~ 13 (API 31 ~ 33)：使用旧版 Intent 方法
            launchLegacy(intent)
        }
    }

    // 同时压制 Kotlin 编译器警告 (@Suppress) 和 Android Lint 静态检查 (@SuppressLint)
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated", "Deprecated")
    private fun launchLegacy(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    // 隔离 API 34+ 新 API，防止 Android 13 及以下 ClassLoader 加载时抛出 NoSuchMethodError
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34Impl {
        @DoNotInline
        fun startActivityAndCollapse(service: TileService, intent: Intent) {
            val pendingIntent = PendingIntent.getActivity(
                service,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            service.startActivityAndCollapse(pendingIntent)
        }
    }
}