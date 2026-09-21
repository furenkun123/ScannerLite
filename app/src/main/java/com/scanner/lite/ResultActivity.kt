package com.scanner.lite

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.scanner.lite.ui.theme.ScannerLiteTheme

class ResultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val translatable = intent.getBooleanExtra(EXTRA_TRANSLATABLE, false)
        setContent {
            ScannerLiteTheme {
                ResultScreen(
                    text = text,
                    translatable = translatable,
                    onClose = { finish() }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TRANSLATABLE = "translatable"

        // 打开结果页：ResultActivity.newIntent(context, 内容)
        // translatable = true：OCR 结果，显示翻译和分享
        fun newIntent(
            context: Context,
            text: String,
            translatable: Boolean = false
        ): Intent =
            Intent(context, ResultActivity::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_TRANSLATABLE, translatable)
    }
}

@Composable
fun ResultScreen(
    text: String,
    translatable: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val url = findUrl(text, loose = translatable) // translatable 为 true 就是 OCR 结果，用宽松规则

    var target by remember { mutableStateOf(Lang.ZH) }           // 目标语言
    var translating by remember { mutableStateOf(false) }        // 是否翻译中
    var translated by remember { mutableStateOf<String?>(null) } // 译文

    // 最外层：半透明遮罩，点一下就关闭
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose
            )
    ) {
        // 底部卡片：高度随内容自适应，最高占屏幕 85%
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.85f)
                // 拦截点击，避免点卡片时被当成点遮罩而关闭
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 3.dp
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)
            ) {
                // 顶部小横条
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 32.dp, height = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (translatable) "识别结果" else "扫描结果",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))

                // 内容区：内容多时可滚动，按钮固定在下面
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 原文
                    Card(Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Text(
                                text = text,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

                    // 翻译区域（只有 OCR 结果才显示）
                    if (translatable) {
                        Text("翻译成", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Lang.entries.forEach { lang ->
                                FilterChip(
                                    selected = target == lang,
                                    onClick = {
                                        target = lang
                                        translated = null // 换语言后清掉旧译文
                                    },
                                    label = { Text(lang.label) }
                                )
                            }
                        }

                        Button(
                            onClick = {
                                translating = true
                                translateText(text, target) { result ->
                                    translating = false
                                    result
                                        .onSuccess { translated = it }
                                        .onFailure {
                                            Toast.makeText(
                                                context, it.message ?: "翻译失败", Toast.LENGTH_LONG
                                            ).show()
                                        }
                                }
                            },
                            enabled = !translating,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (translating) "翻译中…（首次需下载语言包）" else "翻译") }

                        translated?.let { result ->
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    SelectionContainer {
                                        Text(result, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    TextButton(onClick = { copyText(context, result) }) {
                                        Text("复制译文")
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 底部按钮：一行排开，按钮少的时候自动变宽
                val buttonPadding = PaddingValues(horizontal = 8.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (url != null) {
                        Button(
                            onClick = { openUrl(context, url) },
                            modifier = Modifier.weight(1f),
                            contentPadding = buttonPadding
                        ) { Text("打开链接", maxLines = 1) }
                    }

                    FilledTonalButton(
                        onClick = { copyText(context, text) },
                        modifier = Modifier.weight(1f),
                        contentPadding = buttonPadding
                    ) { Text("复制", maxLines = 1) }

                    // 分享只给 OCR 结果保留；扫码结果没有
                    if (translatable) {
                        OutlinedButton(
                            onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(send, "分享"))
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = buttonPadding
                        ) { Text("分享", maxLines = 1) }
                    }
                }
            }
        }
    }
}