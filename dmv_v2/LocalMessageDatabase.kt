package com.mographiccode.deletedmessagevault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.security.MessageDigest

class LocalMessageDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    companion object {
        private const val DB_NAME = "captured_messages.db"
        private const val DB_VERSION = 2
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL,
                sender TEXT NOT NULL,
                body TEXT NOT NULL,
                posted_at INTEGER NOT NULL,
                captured_at INTEGER NOT NULL,
                notification_key TEXT,
                dedupe_hash TEXT NOT NULL UNIQUE,
                is_deletion_signal INTEGER NOT NULL DEFAULT 0,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                matched_original_id INTEGER,
                content_type TEXT NOT NULL DEFAULT 'text',
                media_path TEXT,
                mime_type TEXT,
                media_name TEXT,
                origin_uri TEXT
            )
            """.trimIndent()
        )
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            val additions = listOf(
                "ALTER TABLE messages ADD COLUMN content_type TEXT NOT NULL DEFAULT 'text'",
                "ALTER TABLE messages ADD COLUMN media_path TEXT",
                "ALTER TABLE messages ADD COLUMN mime_type TEXT",
                "ALTER TABLE messages ADD COLUMN media_name TEXT",
                "ALTER TABLE messages ADD COLUMN origin_uri TEXT"
            )
            additions.forEach { sql ->
                try { db.execSQL(sql) } catch (_: Exception) { }
            }
            createIndexes(db)
        }
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_posted_at ON messages(posted_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_sender ON messages(sender)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_deleted ON messages(is_deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_media ON messages(media_path)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_origin_uri ON messages(origin_uri)")
    }

    fun insertNotification(
        packageName: String,
        sender: String,
        body: String,
        postedAt: Long,
        notificationKey: String?,
        isDeletionSignal: Boolean,
        contentType: String = "text"
    ): Long {
        val cleanSender = sender.ifBlank { "واتساب" }
        val cleanBody = body.trim().ifBlank { mediaLabel(contentType) }
        val dedupe = sha256("n|$packageName|$cleanSender|$cleanBody|$postedAt|$contentType")
        val values = ContentValues().apply {
            put("package_name", packageName)
            put("sender", cleanSender)
            put("body", cleanBody)
            put("posted_at", postedAt)
            put("captured_at", System.currentTimeMillis())
            put("notification_key", notificationKey)
            put("dedupe_hash", dedupe)
            put("is_deletion_signal", if (isDeletionSignal) 1 else 0)
            put("content_type", contentType)
        }
        val id = writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (isDeletionSignal && id != -1L && cleanSender.isNotBlank()) {
            markLikelyOriginalDeleted(packageName, cleanSender, postedAt, id)
        }
        return id
    }

    fun attachMediaToMessage(
        messageId: Long,
        mediaPath: String,
        mimeType: String?,
        mediaName: String?,
        originUri: String?,
        contentType: String
    ) {
        if (messageId <= 0) return
        val values = ContentValues().apply {
            put("media_path", mediaPath)
            put("mime_type", mimeType)
            put("media_name", mediaName)
            put("origin_uri", originUri)
            put("content_type", contentType)
        }
        writableDatabase.update("messages", values, "id = ?", arrayOf(messageId.toString()))
    }

    fun attachOrInsertCapturedMedia(
        packageName: String,
        mediaPath: String,
        mimeType: String?,
        mediaName: String?,
        originUri: String?,
        capturedAt: Long
    ): Long {
        if (originUri != null && hasOriginUri(originUri)) return -1L
        if (hasMediaPath(mediaPath)) return -1L

        val type = contentTypeFromMime(mimeType)
        val candidateId = findRecentMediaPlaceholder(packageName, capturedAt, type)
        if (candidateId != null) {
            attachMediaToMessage(candidateId, mediaPath, mimeType, mediaName, originUri, type)
            return candidateId
        }

        val dedupe = sha256("m|$packageName|$mediaPath|${originUri.orEmpty()}")
        val values = ContentValues().apply {
            put("package_name", packageName)
            put("sender", "وسائط واتساب")
            put("body", mediaLabel(type))
            put("posted_at", capturedAt)
            put("captured_at", System.currentTimeMillis())
            put("notification_key", "media:${originUri.orEmpty()}")
            put("dedupe_hash", dedupe)
            put("is_deletion_signal", 0)
            put("content_type", type)
            put("media_path", mediaPath)
            put("mime_type", mimeType)
            put("media_name", mediaName)
            put("origin_uri", originUri)
        }
        return writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun findRecentMediaPlaceholder(packageName: String, capturedAt: Long, mediaType: String): Long? {
        val from = capturedAt - 20_000L
        val to = capturedAt + 20_000L
        val compatible = when (mediaType) {
            "image" -> listOf("image", "sticker", "gif", "media")
            "video" -> listOf("video", "gif", "media")
            "audio" -> listOf("audio", "media")
            else -> listOf(mediaType, "media")
        }
        val placeholders = compatible.joinToString(",") { "?" }
        val args = mutableListOf(packageName, from.toString(), to.toString())
        args.addAll(compatible)
        val sql = """
            SELECT id FROM messages
            WHERE package_name = ?
              AND posted_at BETWEEN ? AND ?
              AND is_deletion_signal = 0
              AND media_path IS NULL
              AND content_type IN ($placeholders)
            ORDER BY ABS(posted_at - ?) ASC
            LIMIT 1
        """.trimIndent()
        args.add(capturedAt.toString())
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }

    private fun markLikelyOriginalDeleted(packageName: String, sender: String, deletionAt: Long, signalId: Long) {
        val sql = """
            SELECT id FROM messages
            WHERE package_name = ?
              AND sender = ?
              AND posted_at <= ?
              AND is_deletion_signal = 0
              AND is_deleted = 0
            ORDER BY posted_at DESC
            LIMIT 1
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(packageName, sender, deletionAt.toString())).use { c ->
            if (!c.moveToFirst()) return
            val originalId = c.getLong(0)
            writableDatabase.beginTransaction()
            try {
                writableDatabase.execSQL("UPDATE messages SET is_deleted = 1 WHERE id = ?", arrayOf(originalId))
                writableDatabase.execSQL("UPDATE messages SET matched_original_id = ? WHERE id = ?", arrayOf(originalId, signalId))
                writableDatabase.setTransactionSuccessful()
            } finally {
                writableDatabase.endTransaction()
            }
        }
    }

    private fun hasOriginUri(originUri: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM messages WHERE origin_uri = ? LIMIT 1", arrayOf(originUri)).use { c ->
            return c.moveToFirst()
        }
    }

    private fun hasMediaPath(mediaPath: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM messages WHERE media_path = ? LIMIT 1", arrayOf(mediaPath)).use { c ->
            return c.moveToFirst()
        }
    }

    fun getMessages(query: String?, deletedOnly: Boolean): List<Map<String, Any?>> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        where += "is_deletion_signal = 0"
        if (deletedOnly) where += "is_deleted = 1"
        if (!query.isNullOrBlank()) {
            where += "(sender LIKE ? OR body LIKE ? OR media_name LIKE ?)"
            repeat(3) { args += "%${query.trim()}%" }
        }
        val sql = """
            SELECT id, package_name, sender, body, posted_at, captured_at,
                   is_deleted, content_type, media_path, mime_type, media_name, origin_uri
            FROM messages
            WHERE ${where.joinToString(" AND ")}
            ORDER BY posted_at DESC
            LIMIT 1000
        """.trimIndent()
        val out = mutableListOf<Map<String, Any?>>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out += mapOf(
                    "id" to c.getLong(0),
                    "packageName" to c.getString(1),
                    "sender" to c.getString(2),
                    "body" to c.getString(3),
                    "postedAt" to c.getLong(4),
                    "capturedAt" to c.getLong(5),
                    "isDeleted" to (c.getInt(6) == 1),
                    "contentType" to c.getString(7),
                    "mediaPath" to c.getString(8),
                    "mimeType" to c.getString(9),
                    "mediaName" to c.getString(10),
                    "originUri" to c.getString(11)
                )
            }
        }
        return out
    }

    fun getStats(): Map<String, Int> {
        val startOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        fun scalar(sql: String, args: Array<String> = emptyArray()): Int {
            readableDatabase.rawQuery(sql, args).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
        }
        return mapOf(
            "total" to scalar("SELECT COUNT(*) FROM messages WHERE is_deletion_signal = 0"),
            "deleted" to scalar("SELECT COUNT(*) FROM messages WHERE is_deleted = 1 AND is_deletion_signal = 0"),
            "media" to scalar("SELECT COUNT(*) FROM messages WHERE media_path IS NOT NULL AND is_deletion_signal = 0"),
            "today" to scalar("SELECT COUNT(*) FROM messages WHERE posted_at >= ? AND is_deletion_signal = 0", arrayOf(startOfDay.toString()))
        )
    }

    fun clearAll() {
        writableDatabase.delete("messages", null, null)
    }

    fun clearArchivedFiles(context: Context) {
        File(context.filesDir, "whatsapp_archive").deleteRecursively()
    }

    private fun contentTypeFromMime(mimeType: String?): String {
        val mime = mimeType.orEmpty().lowercase()
        return when {
            mime.startsWith("image/gif") -> "gif"
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio"
            else -> "media"
        }
    }

    private fun mediaLabel(type: String): String = when (type) {
        "image" -> "صورة محفوظة"
        "video" -> "فيديو محفوظ"
        "audio" -> "مقطع صوتي محفوظ"
        "gif" -> "GIF محفوظ"
        "sticker" -> "ملصق محفوظ"
        "document" -> "مستند محفوظ"
        else -> "وسائط محفوظة"
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
