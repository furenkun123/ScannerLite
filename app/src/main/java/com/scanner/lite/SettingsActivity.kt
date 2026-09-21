package com.scanner.lite

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.scanner.lite.ui.theme.ScannerLiteTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScannerLiteTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var vibrate by remember { mutableStateOf(SettingsStore.vibrate(context)) }
    var saveHistory by remember { mutableStateOf(SettingsStore.saveHistory(context)) }
    var startScan by remember { mutableStateOf(SettingsStore.defaultScanMode(context)) }
    var copyDirect by remember { mutableStateOf(SettingsStore.copyDirect(context)) }

    // 监听并记录控制中心磁贴添加状态
    var isTileAdded by remember { mutableStateOf(SettingsStore.isTileAdded(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionTitle("扫码")

            SwitchItem(
                title = "扫码结果直接复制",
                subtitle = "扫到码后直接复制到剪贴板，不打开结果页，可连续扫码。仅对扫码有效",
                checked = copyDirect
            ) {
                copyDirect = it
                SettingsStore.setCopyDirect(context, it)
            }



            SectionTitle("通用")

            SwitchItem(
                title = "识别成功后震动",
                subtitle = "识别到内容时轻震一下",
                checked = vibrate
            ) {
                vibrate = it
                SettingsStore.setVibrate(context, it)
            }

            SwitchItem(
                title = "保存历史记录",
                subtitle = "关闭后新的识别结果不再保存，已有记录不受影响",
                checked = saveHistory
            ) {
                saveHistory = it
                SettingsStore.setSaveHistory(context, it)
            }

            ListItem(
                headlineContent = { Text("启动时的默认模式") },
                supportingContent = {
                    Column {
                        Text("下次打开 App 时生效")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = startScan,
                                onClick = {
                                    startScan = true
                                    SettingsStore.setDefaultScanMode(context, true)
                                },
                                label = { Text("扫码") }
                            )
                            FilterChip(
                                selected = !startScan,
                                onClick = {
                                    startScan = false
                                    SettingsStore.setDefaultScanMode(context, false)
                                },
                                label = { Text("OCR") }
                            )
                        }
                    }
                }
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("快捷方式")

            // 一键添加磁贴选项（包含防重复校验）
            ListItem(
                modifier = Modifier.clickable {
                    if (isTileAdded) {
                        Toast.makeText(context, "磁贴已在控制中心，无需重复添加", Toast.LENGTH_SHORT).show()
                    } else {
                        requestAddScanTile(context) { added ->
                            isTileAdded = added
                        }
                    }
                },
                headlineContent = { Text("添加扫码磁贴") },
                supportingContent = {
                    Text(
                        if (isTileAdded) "快捷图标已成功添加至下拉控制中心"
                        else "一键将“扫一扫”磁贴添加到控制中心"
                    )
                },
                trailingContent = {
                    if (isTileAdded) {
                        Text(
                            text = "已添加",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
            ListItem(
                headlineContent = { Text("关于") },
                supportingContent = { Text("极简扫码 v${appVersion(context)}") }
            )
        }
    }
}

// 唤起系统弹窗添加磁贴，并将回调状态写入 Store 且通知 UI 更新
private fun requestAddScanTile(context: Context, onStateChanged: (Boolean) -> Unit) {
    val statusBarManager = context.getSystemService(StatusBarManager::class.java)
    val componentName = ComponentName(context, ScanTileService::class.java)

    val icon = try {
        Icon.createWithResource(context, R.drawable.ic_scan_tile)
    } catch (_: Exception) {
        Icon.createWithResource(context, R.mipmap.ic_launcher)
    }

    statusBarManager?.requestAddTileService(
        componentName,
        context.getString(R.string.tile_label),
        icon,
        ContextCompat.getMainExecutor(context)
    ) { result ->
        when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> {
                Toast.makeText(context, "已成功添加磁贴", Toast.LENGTH_SHORT).show()
                SettingsStore.setTileAdded(context, true)
                onStateChanged(true)
            }
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                Toast.makeText(context, "控制中心已存在该磁贴", Toast.LENGTH_SHORT).show()
                SettingsStore.setTileAdded(context, true)
                onStateChanged(true)
            }
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> {
                // 用户取消了添加弹窗
            }
        }
    }
}

// 小组标题
@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

// 带开关的一行设置，点整行也能切换
@Composable
private fun SwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) }
    )
}

// 读取 App 版本号
private fun appVersion(context: Context): String = try {
    context.packageManager
        .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        .versionName ?: ""
} catch (_: Exception) {
    ""
}