package com.scanner.lite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri

// 链接里允许出现的字符：遇到空白、中文、全角标点就停止
private const val URL_CHARS = """[^\s\u4e00-\u9fa5\u3000-\u303f\uff00-\uffef"'<>]"""

// 1. 带 http(s):// 的完整链接
private val schemeRegex = Regex("""(?i)https?://$URL_CHARS+""")

// 2. 以 www. 开头的链接
private val wwwRegex = Regex("""(?i)(?<![@\w.-])www\.[a-z0-9-]+(\.[a-z0-9-]+)+$URL_CHARS*""")

// 3. 只有域名的链接，比如 baidu.com/abc（只认常见后缀，避免误判）
private val domainRegex = Regex(
    """(?i)(?<![@\w.-])[a-z0-9-]+(\.[a-z0-9-]+)*\.""" +
            """(com|cn|net|org|io|top|cc|co|me|app|dev|xyz|info|edu|gov|tv|vip|site|online|link|ly|gl)""" +
            """(?![a-z0-9-])(/$URL_CHARS*)?"""
)

// 从文字里找出第一个链接，没有则返回 null
// loose = true 时（OCR）会额外识别 www.xxx.com 和 xxx.com 这种没有 http 的写法
fun findUrl(text: String, loose: Boolean = false): String? {
    // OCR 有时会把 "https://" 识别成 "https: //"，先修正
    val t = text.replace(Regex("""(?i)(https?)\s*:\s*/\s*/\s*"""), "$1://")

    // 先找带 http(s):// 的；OCR 模式下再找 www. 和纯域名
    var hit: String? = schemeRegex.find(t)?.value
    if (hit == null && loose) {
        hit = wwwRegex.find(t)?.value ?: domainRegex.find(t)?.value
    }
    if (hit == null) return null

    val clean = hit.trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
    if (clean.isEmpty()) return null

    return if (clean.startsWith("http", ignoreCase = true)) {
        clean.takeIf { it.substringAfter("://").isNotEmpty() }
    } else {
        "https://$clean" // 没有协议头的，自动补上
    }
}

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: Exception) {
        Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
    }
}

fun copyText(context: Context, text: String, message: String = "已复制") {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("scan", text))
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}