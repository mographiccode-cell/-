package com.mographiccode.deletedmessagevault

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class MediaScanner(private val context: Context) {
    private val archiveDb = MediaArchiveDatabase(context)
    private val archiveDir = File(context.filesDir, "whatsapp_archive/media").apply { mkdirs() }

    fun scanRecent(windowMillis: Long = 24 * 60 * 60 * 1000L): Int {
        val cutoff = (System.currentTimeMillis() - windowMillis) / 1000L
        var count = 0
        count += scanCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cutoff, imagePermission())
        count += scanCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cutoff, videoPermission())
        count += scanCollection(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cutoff, audioPermission())
        return count
    }

    private fun imagePermission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun videoPermission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun audioPermission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun scanCollection(baseUri: Uri, cutoff: Long, permission: String): Int {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return 0
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        if (Build.VERSION.SDK_INT >= 29) projection += MediaStore.MediaColumns.RELATIVE_PATH
        else projection += MediaStore.MediaColumns.DATA
        var saved = 0
        context.contentResolver.query(
            baseUri,
            projection.toTypedArray(),
            "${MediaStore.MediaColumns.DATE_ADDED} >= ?",
            arrayOf(cutoff.toString()),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val pathIndex = if (Build.VERSION.SDK_INT >= 29) cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            var checked = 0
            while (cursor.moveToNext() && checked < 300) {
                checked++
                val location = if (pathIndex >= 0) cursor.getString(pathIndex).orEmpty() else ""
                if (!isWhatsAppPath(location)) continue
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex) ?: "media_$id"
                val mime = cursor.getString(mimeIndex)
                val capturedAt = cursor.getLong(dateIndex) * 1000L
                val uri = ContentUris.withAppendedId(baseUri, id)
                val localPath = copyToPrivateArchive(uri, name) ?: continue
                val pkg = if (location.lowercase().contains("com.whatsapp.w4b")) "com.whatsapp.w4b" else "com.whatsapp"
                if (archiveDb.insert(pkg, localPath, mime, name, uri.toString(), capturedAt, contentType(mime))) saved++
            }
        }
        return saved
    }

    private fun isWhatsAppPath(path: String): Boolean {
        val p = path.lowercase().replace('\\', '/')
        return p.contains("whatsapp") && (p.contains("android/media/com.whatsapp") || p.contains("whatsapp/media"))
    }

    private fun copyToPrivateArchive(uri: Uri, name: String): String? {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "media" }
        val key = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val file = File(archiveDir, "${key}_$safeName")
        if (file.exists() && file.length() > 0) return file.absolutePath
        return try {
            context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } } ?: return null
            if (file.length() > 0) file.absolutePath else null
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun contentType(mime: String?): String {
        val value = mime.orEmpty().lowercase()
        return when {
            value == "image/gif" -> "gif"
            value.startsWith("image/") -> "image"
            value.startsWith("video/") -> "video"
            value.startsWith("audio/") -> "audio"
            else -> "media"
        }
    }
}
