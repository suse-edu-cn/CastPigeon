package com.suseoaa.castpigeon.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.suseoaa.castpigeon.shared.NotificationMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Calendar

data class ClipboardHistoryItem(
    val id: Long,
    val content: String,
    val direction: String,
    val timestamp: Long
)

class MessageDatabaseHelper(private val context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "castpigeon.db"
        private const val DATABASE_VERSION = 3
        private const val TABLE_MESSAGES = "messages"
        private const val TABLE_CLIPBOARD_HISTORY = "clipboard_history"
        
        private const val COL_ID = "id"
        private const val COL_MSG_ID = "msg_id"
        private const val COL_APP_NAME = "app_name"
        private const val COL_TITLE = "title"
        private const val COL_CONTENT = "content"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_DEVICE_HASH = "device_hash"
        private const val COL_DIRECTION = "direction"

        private val _historyChanges = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
        val historyChanges: SharedFlow<Unit> = _historyChanges.asSharedFlow()

        fun notifyHistoryChanged() {
            _historyChanges.tryEmit(Unit)
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        createMessagesTable(db)
        createClipboardHistoryTable(db)
    }

    private fun createMessagesTable(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_MESSAGES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_MSG_ID TEXT UNIQUE,
                $COL_APP_NAME TEXT,
                $COL_TITLE TEXT,
                $COL_CONTENT TEXT,
                $COL_TIMESTAMP INTEGER,
                $COL_DEVICE_HASH TEXT
            )
        """.trimIndent()
        db.execSQL(createTable)
    }

    private fun createClipboardHistoryTable(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE IF NOT EXISTS $TABLE_CLIPBOARD_HISTORY (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CONTENT TEXT NOT NULL,
                $COL_DIRECTION TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTable)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_clipboard_history_timestamp ON $TABLE_CLIPBOARD_HISTORY($COL_TIMESTAMP DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            createClipboardHistoryTable(db)
        }
    }

    fun insertMessage(msg: NotificationMessage) {
        // 提取并去重保存图标到本地物理文件
        msg.iconBase64?.let { base64 ->
            try {
                val iconDir = context.getDir("Icons", Context.MODE_PRIVATE)
                val iconFile = java.io.File(iconDir, "${msg.appName}.png")
                if (!iconFile.exists()) {
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                    iconFile.writeBytes(bytes)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COL_MSG_ID, msg.id)
            put(COL_APP_NAME, msg.appName)
            put(COL_TITLE, msg.title)
            put(COL_CONTENT, msg.content)
            put(COL_TIMESTAMP, msg.timestamp)
            put(COL_DEVICE_HASH, "local") // Android为发送端，记录为local即可
        }
        val rowId = db.insertWithOnConflict(TABLE_MESSAGES, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        db.close()
        if (rowId != -1L) {
            notifyHistoryChanged()
        }
    }

    fun insertClipboardHistory(content: String, direction: String, timestamp: Long = System.currentTimeMillis()) {
        if (content.isBlank()) return

        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COL_CONTENT, content)
            put(COL_DIRECTION, direction)
            put(COL_TIMESTAMP, timestamp)
        }
        val rowId = db.insert(TABLE_CLIPBOARD_HISTORY, null, values)
        db.close()
        if (rowId != -1L) {
            notifyHistoryChanged()
        }
    }

    fun getTodayMessageCount(): Int {
        val db = this.readableDatabase
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_MESSAGES WHERE $COL_TIMESTAMP >= ?", arrayOf(startOfDay.toString()))
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        db.close()
        return count
    }

    fun getAllMessages(): List<NotificationMessage> {
        val db = this.readableDatabase
        val list = mutableListOf<NotificationMessage>()
        val cursor = db.rawQuery("SELECT * FROM $TABLE_MESSAGES ORDER BY $COL_TIMESTAMP DESC", null)
        if (cursor.moveToFirst()) {
            do {
                val msg = NotificationMessage(
                    id = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_ID)),
                    appName = cursor.getString(cursor.getColumnIndexOrThrow(COL_APP_NAME)),
                    title = cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE)),
                    content = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT)),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
                    iconBase64 = null // 图标已保存为文件
                )
                list.add(msg)
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return list
    }

    fun getAllClipboardHistory(): List<ClipboardHistoryItem> {
        val db = this.readableDatabase
        val list = mutableListOf<ClipboardHistoryItem>()
        val cursor = db.rawQuery(
            "SELECT $COL_ID, $COL_CONTENT, $COL_DIRECTION, $COL_TIMESTAMP FROM $TABLE_CLIPBOARD_HISTORY ORDER BY $COL_TIMESTAMP DESC",
            null
        )
        if (cursor.moveToFirst()) {
            do {
                list.add(
                    ClipboardHistoryItem(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                        content = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT)),
                        direction = cursor.getString(cursor.getColumnIndexOrThrow(COL_DIRECTION)),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP))
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return list
    }
}
