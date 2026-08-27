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
                        val count = MediaCaptureManager(this).scanRecentWhatsAppMedia(10 * 60_000L)
                        runOnUiThread { result.success(count) }
                    }.start()
                }
                "getMessages" -> {
                    val query = call.argument<String>("query")
                    val deletedOnly = call.argument<Boolean>("deletedOnly") ?: false
                    result.success(LocalMessageDatabase(this).getMessages(query, deletedOnly))
                }
                "getStats" -> result.success(LocalMessageDatabase(this).getStats())
                "clearData" -> {
                    val db = LocalMessageDatabase(this)
                    db.clearAll()
                    db.clearArchivedFiles(this)
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val expected = ComponentName(this, WhatsAppNotificationListener::class.java)
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(":").mapNotNull { ComponentName.unflattenFromString(it) }.any { it == expected }
    }

    private fun mediaPermissionState(): Map<String, Boolean> {
        if (Build.VERSION.SDK_INT >= 33) {
            return mapOf(
                "images" to hasPermission(Manifest.permission.READ_MEDIA_IMAGES),
                "video" to hasPermission(Manifest.permission.READ_MEDIA_VIDEO),
                "audio" to hasPermission(Manifest.permission.READ_MEDIA_AUDIO),
                "legacy" to false
            )
        }
        val legacy = hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        return mapOf("images" to legacy, "video" to legacy, "audio" to legacy, "legacy" to legacy)
    }

    private fun requestMediaPermissions(result: MethodChannel.Result) {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            mutableListOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }.toTypedArray()
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = permissions.filterNot { hasPermission(it) }
        if (missing.isEmpty()) {
            result.success(mediaPermissionState())
            return
        }
        pendingPermissionResult?.error("busy", "طلب صلاحية آخر ما زال مفتوحًا", null)
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

    private fun hasPermission(permission: String): Boolean {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
