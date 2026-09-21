package com.scanner.lite

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

// 下拉控制中心 / 快捷设置磁贴
class ScanTileService : TileService() {

    // 当用户在下拉控制中心手动添加此磁贴时回调
    override fun onTileAdded() {
        super.onTileAdded()
        SettingsStore.setTileAdded(this, true)
    }

    // 当用户在下拉控制中心手动移除此磁贴时回调
    override fun onTileRemoved() {
        super.onTileRemoved()
        SettingsStore.setTileAdded(this, false)
    }

    override fun onStartListening() {
        super.onStartListening()
        // 磁贴变为可见/刷新时，自动同步状态为已添加
        SettingsStore.setTileAdded(this, true)

        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            contentDescription = getString(R.string.tile_description)
            icon = Icon.createWithResource(this@ScanTileService, R.drawable.ic_scan_tile)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ (API 34+) 逻辑：系统会自动处理解锁与收起面板，直接传入 PendingIntent 即可
            openScannerApi34()
        } else {
            // Android 13 及以下逻辑：支持 unlockAndRun 延迟解锁跳转
            if (isLocked) {
                unlockAndRun { openScannerLegacy() }
            } else {
                openScannerLegacy()
            }
        }
    }

    // Android 14+ 标准实现
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun openScannerApi34() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        startActivityAndCollapse(pendingIntent)
    }

    // Android 13 及以下旧版本实现
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openScannerLegacy() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        startActivityAndCollapse(intent)
    }
}