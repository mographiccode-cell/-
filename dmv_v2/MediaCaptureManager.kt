package com.mographiccode.deletedmessagevault

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class MediaCaptureManager(private val context: Context) {
    private val db = LocalMessageDatabase(context)
    private val archiveDir: File by lazy {
        File(context.filesDir, "whatsapp_archive/media").apply { mkdirs() }
    }

    fun hasAnyMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun captureNotificationUri(
        packageName: String,
        messageId: Long,
        uri: Uri?,
        mimeType: String?,
        displayNameHint: String?,
        postedAt: Long
    ): String? {
        if (uri == null) return null
        return try {
            val path = copyUriToArchive(uri, mimeType, displayNameHint, postedAt) ?: return null
            db.attachMediaToMessage(
                messageId = messageId,
                mediaPath = path,
                mimeType = mimeType,
                mediaName = displayNameHint ?: File(path).name,
                originUri = uri.toString(),
                contentType = contentTypeFromMime(mimeType)
            )
            path
        } catch (_: Exception) {
            null
        }
    }

    fun captureBitmap(
        messageId: Long,
        bitmap: Bitmap?,
        postedAt: Long
    ): String? {
        if (bitmap == null || messageId <= 0L) return null
        return try {
            val file = File(archiveDir, "notification_${postedAt}_${messageId}.png")
            if (!file.exists()) {
                FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            }
            db.attachMediaToMessage(
                messageId = messageId,
                mediaPath = file.absolutePath,
                mimeType = "image/png",
                mediaName = file.name,
                originUri = null,
                contentType = "image"
            )
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun scanRecentWhatsAppMedia(windowMillis: Long = 120_000L): Int {
        if (!hasAnyMediaPermission()) return 0
        val cutoffSeconds = (System.currentTimeMillis() - windowMillis) / 1000L
        var captured = 0
        captured += scanCollection(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            cutoffSeconds,
            requiredPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        )
        captured += scanCollection(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            cutoffSeconds,
            requiredPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        )
        captured += scanCollection(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            cutoffSeconds,
            requiredPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        )
        return captured
    }

    private fun scanCollection(baseUri: Uri, cutoffSeconds: Long, requiredPermission: String): Int {
        if (context.checkSelfPermission(requiredPermission) != PackageManager.PERMISSION_GRANTED) return 0
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        if (Build.VERSION.SDK_INT >= 29) projection += MediaStore.MediaColumns.RELATIVE_PATH
        else projection += MediaStore.MediaColumns.DATA

        val selection = "${MediaStore.MediaColumns.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf(cutoffSeconds.toString())
        var captured = 0
        try {
            context.contentResolver.query(
                baseUri,
                projection.toTypedArray(),
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val pathIndex = if (Build.VERSION.SDK_INT >= 29) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                }
                var checked = 0
                while (cursor.moveToNext() && checked < 120) {
                    checked++
                    val location = if (pathIndex >= 0) cursor.getString(pathIndex).orEmpty() else ""
                    if (!looksLikeWhatsAppPath(location)) continue
                    val id = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex) ?: "media_$id"
                    val mime = cursor.getString(mimeIndex)
                    val addedAt = cursor.getLong(dateIndex) * 1000L
                    val itemUri = ContentUris.withAppendedId(baseUri, id)
                    val saved = copyUriToArchive(itemUri, mime, name, addedAt) ?: continue
                    val packageName = if (location.lowercase().contains("com.whatsapp.w4b")) {
                        "com.whatsapp.w4b"
                    } else {
                        "com.whatsapp"
                    }
                    val result = db.attachOrInsertCapturedMedia(
                        packageName = packageName,
                        mediaPath = saved,
                        mimeType = mime,
                        mediaName = name,
                        originUri = itemUri.toString(),
                        capturedAt = addedAt
                    )
                    if (result != -1L) captured++
                }
            }
        } catch (_: SecurityException) {
            return captured
        } catch (_: Exception) {
            return captured
        }
        return captured
    }

    private fun looksLikeWhatsAppPath(path: String): Boolean {
        val value = path.lowercase().replace('\\', '/')
        if (!value.contains("whatsapp")) return false
        return value.contains("android/media/com.whatsapp") ||
            value.contains("android/media/com.whatsapp.w4b") ||
            value.contains("whatsapp/media")
    }

    private fun copyUriToArchive(uri: Uri, mimeType: String?, displayName: String?, timestamp: Long): String? {
        val cleanName = sanitize(displayName ?: "media")
        val extFromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType.orEmpty())
        val extension = when {
            cleanName.contains('.') -> ""
            !extFromMime.isNullOrBlank() -> ".$extFromMime"
            else -> ""
        }
        val stable = sha256(uri.toString()).take(16)
        val file = File(archiveDir, "${timestamp}_${stable}_${cleanName}$extension")
        if (file.exists() && file.length() > 0) return file.absolutePath
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            } ?: return null
            if (file.length() <= 0L) {
                file.delete()
                null
            } else {
                file.absolutePath
            }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun sanitize(value: String): String {
        val cleaned = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return cleaned.ifBlank { "media" }
    }

    private fun contentTypeFromMime(mimeType: String?): String {
        val mime = mimeType.orEmpty().lowercase()
        return when {
            mime == "image/gif" -> "gif"
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio"
            else -> "media"
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
