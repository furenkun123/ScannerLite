package com.scanner.lite

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

const val TYPE_SCAN = 0  // 扫码
const val TYPE_OCR = 1   // 文字识别

// 一条历史记录
data class HistoryItem(
    val id: Long,
    val content: String,
    val type: Int,
    val time: Long
)

// 数据库：只有一张 history 表
private class HistoryDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "content TEXT NOT NULL, " +
                    "type INTEGER NOT NULL, " +
                    "time INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

// 对外的增删查
object HistoryStore {
    private var helper: HistoryDbHelper? = null

    @Synchronized
    private fun db(context: Context): SQLiteDatabase {
        val h = helper ?: HistoryDbHelper(context).also { helper = it }
        return h.writableDatabase
    }

    // 添加一条；相同内容先删掉旧的，这样不会重复
    fun add(context: Context, content: String, type: Int) {
        val db = db(context)
        db.delete("history", "content = ? AND type = ?", arrayOf(content, type.toString()))
        db.insert("history", null, ContentValues().apply {
            put("content", content)
            put("type", type)
            put("time", System.currentTimeMillis())
        })
    }

    // 读取全部，最新的在最前面
    fun getAll(context: Context): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        db(context).query("history", null, null, null, null, null, "time DESC").use { c ->
            while (c.moveToNext()) {
                list.add(
                    HistoryItem(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        content = c.getString(c.getColumnIndexOrThrow("content")),
                        type = c.getInt(c.getColumnIndexOrThrow("type")),
                        time = c.getLong(c.getColumnIndexOrThrow("time"))
                    )
                )
            }
        }
        return list
    }

    fun delete(context: Context, id: Long) {
        db(context).delete("history", "id = ?", arrayOf(id.toString()))
    }

    fun clear(context: Context) {
        db(context).delete("history", null, null)
    }
}

// 识别成功后统一调用它：按设置保存历史、震动，再复制或打开结果页
fun openResult(context: Context, text: String, isOcr: Boolean) {
    if (SettingsStore.saveHistory(context)) {
        HistoryStore.add(context, text, if (isOcr) TYPE_OCR else TYPE_SCAN)
    }
    if (SettingsStore.vibrate(context)) {
        vibrateOnce(context)
    }

    // 扫码 + 直接复制：不打开结果页
    if (!isOcr && SettingsStore.copyDirect(context)) {
        copyText(context, text, "已复制：" + text.take(40))
        return
    }

    context.startActivity(ResultActivity.newIntent(context, text, translatable = isOcr))
}