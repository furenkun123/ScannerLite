package com.scanner.lite

import android.Manifest
import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.annotation.RequiresPermission
import androidx.core.content.edit

// 读写设置。以后加新设置，照着加一对函数即可
object SettingsStore {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // 识别成功后是否震动
    fun vibrate(context: Context) = prefs(context).getBoolean("vibrate", true)
    fun setVibrate(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean("vibrate", value) }

    // 是否保存历史记录
    fun saveHistory(context: Context) = prefs(context).getBoolean("save_history", true)
    fun setSaveHistory(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean("save_history", value) }

    // 启动时的默认模式：true = 扫码，false = OCR
    fun defaultScanMode(context: Context) = prefs(context).getBoolean("default_scan", true)
    fun setDefaultScanMode(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean("default_scan", value) }

    // 扫码结果直接复制，不打开结果页
    fun copyDirect(context: Context) = prefs(context).getBoolean("copy_direct", false)
    fun setCopyDirect(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean("copy_direct", value) }

    // 快捷设置磁贴是否已添加到控制中心
    fun isTileAdded(context: Context) = prefs(context).getBoolean("tile_added", false)
    fun setTileAdded(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean("tile_added", value) }
}

// 轻震一下（出任何问题都忽略，不能因为震动导致 App 闪退）
@RequiresPermission(Manifest.permission.VIBRATE)
fun vibrateOnce(context: Context) {
    try {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: Exception) {
        // 震动失败不影响主流程
    }
}