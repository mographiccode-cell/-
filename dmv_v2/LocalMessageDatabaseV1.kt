package com.mographiccode.deletedmessagevault

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

class LocalMessageDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    companion object {
        private const val DATABASE_NAME = "captured_messages.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_MESSAGES = "messages"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL,
                sender TEXT,
                body TEXT NOT NULL,
                posted_at INTEGER NOT NULL,
                captured_at INTEGER NOT NULL,
                notification_key TEXT,
                dedupe_hash TEXT NOT NULL UNIQUE,
                is_deletion_signal INTEGER NOT NULL DEFAULT 0,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                matched_original_id INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_posted_at ON $TABLE_MESSAGES(posted_at DESC)")
        db.execSQL("CREATE INDEX idx_messages_sender ON $TABLE_MESSAGES(sender)")
        db.execSQL("CREATE INDEX idx_messages_deleted ON $TABLE_MESSAGES(is_deleted, is_deletion_signal)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) onCreate(db)
    }

    fun insertMessage(
        packageName: String,
        sender: String?,
        body: String,
        postedAt: Long,
        notificationKey: String?,
        deletionSignal: Boolean,
    ): Long {
        val normalizedBody = body.trim()
        if (normalizedBody.isBlank()) return -1
        val hash = sha256("$packageName|${sender.orEmpty()}|$normalizedBody|$postedAt")
        val values = ContentValues().apply {
            put("package_name", packageName)
            put("sender", sender?.trim())
            put("body", normalizedBody)
            put("posted_at", postedAt)
            put("captured_at", System.currentTimeMillis())
            put("notification_key", notificationKey)
            put("dedupe_hash", hash)
            put("is_deletion_signal", if (deletionSignal) 1 else 0)
        }
        val db = writableDatabase
        val id = db.insertWithOnConflict(TABLE_MESSAGES, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (id > 0 && deletionSignal) matchDeletionSignal(db, packageName, sender, postedAt, id)
        return id
    }

    private fun matchDeletionSignal(
        db: SQLiteDatabase,
        packageName: String,
        sender: String?,
        postedAt: Long,
        signalId: Long,
    ) {
        if (sender.isNullOrBlank()) return
        val args = mutableListOf(packageName, sender, postedAt.toString(), signalId.toString())
        db.rawQuery(
            """
            SELECT id FROM $TABLE_MESSAGES
            WHERE package_name = ?
              AND sender = ?
              AND posted_at <= ?
              AND id != ?
              AND is_deletion_signal = 0
              AND is_deleted = 0
            ORDER BY posted_at DESC, id DESC
            LIMIT 1
            """.trimIndent(),
            args.toTypedArray(),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val originalId = cursor.getLong(0)
                db.execSQL("UPDATE $TABLE_MESSAGES SET is_deleted = 1 WHERE id = ?", arrayOf(originalId))
                db.execSQL("UPDATE $TABLE_MESSAGES SET matched_original_id = ? WHERE id = ?", arrayOf(originalId, signalId))
            }
        }
    }

    fun getMessages(query: String, deletedOnly: Boolean): List<Map<String, Any?>> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (query.isNotBlank()) {
            where.add("(sender LIKE ? OR body LIKE ?)")
            val pattern = "%${query.trim()}%"
            args.add(pattern)
            args.add(pattern)
        }
        if (deletedOnly) where.add("(is_deleted = 1 OR is_deletion_signal = 1)")
        val sql = buildString {
            append("SELECT id, package_name, sender, body, posted_at, is_deleted, is_deletion_signal FROM $TABLE_MESSAGES")
            if (where.isNotEmpty()) append(" WHERE ${where.joinToString(" AND ")}")
            append(" ORDER BY posted_at DESC, id DESC LIMIT 1000")
        }
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursorToMap(cursor))
            }
        }
    }

    fun getStats(): Map<String, Int> {
        val db = readableDatabase
        val total = scalarCount(db, "SELECT COUNT(*) FROM $TABLE_MESSAGES WHERE is_deletion_signal = 0")
        val deleted = scalarCount(db, "SELECT COUNT(*) FROM $TABLE_MESSAGES WHERE is_deleted = 1")
        val startOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_MESSAGES WHERE is_deletion_signal = 0 AND posted_at >= ?",
            arrayOf(startOfDay.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return mapOf("total" to total, "deleted" to deleted, "today" to today)
    }

    fun clearAll() {
        writableDatabase.delete(TABLE_MESSAGES, null, null)
    }

    private fun scalarCount(db: SQLiteDatabase, sql: String): Int =
        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun cursorToMap(cursor: Cursor): Map<String, Any?> {
        val packageName = cursor.getString(1)
        return mapOf(
            "id" to cursor.getLong(0),
            "packageName" to packageName,
            "sender" to cursor.getString(2),
            "text" to cursor.getString(3),
            "postedAt" to cursor.getLong(4),
            "isDeleted" to (cursor.getInt(5) == 1),
            "isDeletionSignal" to (cursor.getInt(6) == 1),
            "source" to if (packageName == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp",
        )
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
