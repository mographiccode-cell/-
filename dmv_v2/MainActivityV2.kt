package com.mographiccode.deletedmessagevault

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlin.math.abs

class MainActivity : FlutterActivity() {
    private val channelName = "com.mographiccode.deletedmessagevault/native"
    private val mediaPermissionRequestCode = 8102
    private var pendingPermissionResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "isNotificationAccessEnabled" -> result.success(isNotificationAccessEnabled())
                "openNotificationAccessSettings" -> {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    result.success(true)
                }
                "getMediaPermissionState" -> result.success(mediaPermissionState())
                "requestMediaPermissions" -> requestMediaPermissions(result)
                "scanRecentMedia" -> {
                    Thread {
                        val count = MediaScanner(this).scanRecent(24 * 60 * 60 * 1000L)
                        runOnUiThread { result.success(count) }
                    }.start()
                }
                "getMessages" -> {
                    val query = call.argument<String>("query")
                    val deletedOnly = call.argument<Boolean>("deletedOnly") ?: false
                    result.success(combinedMessages(query, deletedOnly))
                }
                "getStats" -> result.success(combinedStats())
                "clearData" -> {
                    LocalMessageDatabase(this).clearAll()
                    MediaArchiveDatabase(this).clearAll()
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun combinedMessages(query: String?, deletedOnly: Boolean): List<Map<String, Any?>> {
        val messages = LocalMessageDatabase(this).getMessages(query, deletedOnly)
            .map { HashMap(it) }
            .toMutableList()
        val mediaItems = MediaArchiveDatabase(this).getItems(query)
        val usedMessageIndexes = mutableSetOf<Int>()
        val unmatchedMedia = mutableListOf<Map<String, Any?>>()

        for (media in mediaItems) {
            val mediaTime = (media["postedAt"] as? Number)?.toLong() ?: continue
            val mediaPackage = media["packageName"]?.toString().orEmpty()
            val mediaType = media["contentType"]?.toString().orEmpty()
            val candidates = messages.indices.filter { index ->
                if (index in usedMessageIndexes) return@filter false
                val message = messages[index]
                val messageTime = (message["postedAt"] as? Number)?.toLong() ?: return@filter false
                val messagePackage = message["packageName"]?.toString().orEmpty()
                messagePackage == mediaPackage && abs(messageTime - mediaTime) <= 25_000L
            }
            val strong = candidates
                .filter { index -> placeholderMatches(messages[index]["body"]?.toString().orEmpty(), mediaType) }
                .minByOrNull { index -> abs(((messages[index]["postedAt"] as Number).toLong()) - mediaTime) }
            val fallback = if (strong == null) {
                val veryClose = candidates.filter { index ->
                    abs(((messages[index]["postedAt"] as Number).toLong()) - mediaTime) <= 8_000L
                }
                if (veryClose.size == 1) veryClose.first() else null
            } else null
            val matchIndex = strong ?: fallback
            if (matchIndex != null) {
                val message = messages[matchIndex]
                message["mediaPath"] = media["mediaPath"]
                message["mimeType"] = media["mimeType"]
                message["mediaName"] = media["mediaName"]
                message["originUri"] = media["originUri"]
                message["contentType"] = mediaType
                usedMessageIndexes += matchIndex
            } else if (!deletedOnly) {
                unmatchedMedia += media
            }
        }

        messages += unmatchedMedia.map { HashMap(it) }
        return messages.sortedByDescending { (it["postedAt"] as? Number)?.toLong() ?: 0L }
    }

    private fun placeholderMatches(body: String, mediaType: String): Boolean {
        val value = body.lowercase()
        return when (mediaType) {
            "image", "gif" -> value.contains("photo") || value.contains("image") || value.contains("صورة") || value.contains("gif") || value.contains("sticker") || value.contains("ملصق")
            "video" -> value.contains("video") || value.contains("فيديو")
            "audio" -> value.contains("audio") || value.contains("voice") || value.contains("صوت") || value.contains("تسجيل")
            else -> false
        }
    }

    private fun combinedStats(): Map<String, Int> {
        val base = LocalMessageDatabase(this).getStats().toMutableMap()
        base["media"] = MediaArchiveDatabase(this).count()
        return base
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val expected = ComponentName(this, WhatsAppNotificationListener::class.java)
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(":").mapNotNull { ComponentName.unflattenFromString(it) }.any { it == expected }
    }

    private fun mediaPermissionState(): Map<String, Boolean> {
        return if (Build.VERSION.SDK_INT >= 33) {
            mapOf(
                "images" to hasPermission(Manifest.permission.READ_MEDIA_IMAGES),
                "video" to hasPermission(Manifest.permission.READ_MEDIA_VIDEO),
                "audio" to hasPermission(Manifest.permission.READ_MEDIA_AUDIO)
            )
        } else {
            val granted = hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            mapOf("images" to granted, "video" to granted, "audio" to granted)
        }
    }

    private fun requestMediaPermissions(result: MethodChannel.Result) {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = permissions.filterNot { hasPermission(it) }
        if (missing.isEmpty()) {
            result.success(mediaPermissionState())
            return
        }
        pendingPermissionResult?.error("busy", "يوجد طلب صلاحية مفتوح", null)
        pendingPermissionResult = result
        requestPermissions(missing.toTypedArray(), mediaPermissionRequestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == mediaPermissionRequestCode) {
            pendingPermissionResult?.success(mediaPermissionState())
            pendingPermissionResult = null
        }
    }

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
