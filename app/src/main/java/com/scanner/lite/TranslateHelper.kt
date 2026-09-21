package com.scanner.lite

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

// 可选的目标语言，想加别的语言在这里加一行即可
enum class Lang(val label: String, val code: String) {
    ZH("中文", TranslateLanguage.CHINESE),
    EN("English", TranslateLanguage.ENGLISH),
    JA("日本語", TranslateLanguage.JAPANESE),
    KO("한국어", TranslateLanguage.KOREAN)
}

private val languageIdentifier = LanguageIdentification.getClient()

// 翻译文字：先检测原文语言，再翻译成目标语言
// 结果用 Result 返回：成功是译文，失败带着中文提示
fun translateText(text: String, target: Lang, onDone: (Result<String>) -> Unit) {
    languageIdentifier.identifyLanguage(text)
        .addOnSuccessListener { tag ->
            when (val source = TranslateLanguage.fromLanguageTag(tag)) {
                null -> onDone(Result.failure(Exception("无法识别原文语言，或暂不支持该语言")))
                target.code -> onDone(Result.failure(Exception("原文已经是${target.label}了")))
                else -> runTranslate(text, source, target, onDone)
            }
        }
        .addOnFailureListener { onDone(Result.failure(Exception("语言检测失败，请重试"))) }
}

private fun runTranslate(
    text: String,
    source: String,
    target: Lang,
    onDone: (Result<String>) -> Unit
) {
    val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target.code)
            .build()
    )

    // 第一次用某种语言需要下载语言包（约 30MB），之后离线可用
    translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
        .addOnSuccessListener {
            translator.translate(text)
                .addOnSuccessListener {
                    translator.close()
                    onDone(Result.success(it))
                }
                .addOnFailureListener {
                    translator.close()
                    onDone(Result.failure(Exception("翻译失败，请重试")))
                }
        }
        .addOnFailureListener {
            translator.close()
            onDone(Result.failure(Exception("语言包下载失败，请检查网络后重试")))
        }
}