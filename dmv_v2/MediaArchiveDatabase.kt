package com.mographiccode.deletedmessagevault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

class MediaArchiveDatabase(private val context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    companion object {
        private const val DB_NAME = "media_archive.db"
        private const val DB_VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE media_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL,
                media_path TEXT NOT NULL UNIQUE,
                mime_type TEXT,
                media_name TEXT,
                origin_uri TEXT UNIQUE,
                captured_at INTEGER NOT NULL,
                content_type TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_media_captured_at ON media_items(captured_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(
        packageName: String,
        mediaPath: String,
        mimeType: String?,
        mediaName: String?,
        originUri: String?,
        capturedAt: Long,
        contentType: String
    ): Boolean {
        val values = ContentValues().apply {
            put("package_name", packageName)
            put("media_path", mediaPath)
            put("mime_type", mimeType)
            put("media_name", mediaName)
            put("origin_uri", originUri)
            put("captured_at", capturedAt)
            put("content_type", contentType)
        }
        return writableDatabase.insertWithOnConflict(
            "media_items",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun getItems(query: String?): List<Map<String, Any?>> {
        val args = mutableListOf<String>()
        val where = if (query.isNullOrBlank()) {
            "1=1"
        } else {
            args += "%${query.trim()}%"
            args += "%${query.trim()}%"
            "(media_name LIKE ? OR content_type LIKE ?)"
        }
        val out = mutableListOf<Map<String, Any?>>()
        readableDatabase.rawQuery(
            """
            SELECT id, package_name, media_path, mime_type, media_name, captured_at, content_type, origin_uri
            FROM media_items
            WHERE $where
            ORDER BY captured_at DESC
            LIMIT 1000
            """.trimIndent(),
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val type = c.getString(6) ?: "media"
                out += mapOf(
                    "id" to (-1_000_000L - c.getLong(0)),
                    "packageName" to c.getString(1),
                    "sender" to "وسائط واتساب",
                    "body" to mediaLabel(type),
                    "postedAt" to c.getLong(5),
                    "capturedAt" to c.getLong(5),
                    "isDeleted" to false,
                    "contentType" to type,
                    "mediaPath" to c.getString(2),
                    "mimeType" to c.getString(3),
                    "mediaName" to c.getString(4),
                    "originUri" to c.getString(7)
                )
            }
        }
        return out
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM media_items", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun clearAll() {
        writableDatabase.delete("media_items", null, null)
        File(context.filesDir, "whatsapp_archive").deleteRecursively()
    }

    private fun mediaLabel(type: String): String = when (type) {
        "image" -> "صورة محفوظة"
        "video" -> "فيديو محفوظ"
        "audio" -> "مقطع صوتي محفوظ"
        "gif" -> "GIF محفوظ"
        "sticker" -> "ملصق محفوظ"
        else -> "وسائط محفوظة"
    }
}
