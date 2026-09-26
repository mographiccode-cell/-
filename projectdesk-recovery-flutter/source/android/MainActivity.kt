package com.mographikod.projectdesk.recovery

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val channelName = "projectdesk.recovery/control"
    private val io = Executors.newSingleThreadExecutor()

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getState" -> result.success(getState())
                    "openAccessibility" -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        result.success(true)
                    }
                    "startRecovery" -> startRecovery(result)
                    "createBackup" -> createBackup(result)
                    else -> result.notImplemented()
                }
            }
    }

    private fun getState(): Map<String, Any?> {
        val sp = getSharedPreferences(RecoveryAccessibilityService.PREFS, MODE_PRIVATE)
        val projects = mutableListOf<Map<String, Any?>>()
        try {
            val arr = JSONArray(sp.getString("projects_json", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                projects.add(
                    mapOf(
                        "title" to o.optString("title", ""),
                        "student" to o.optString("student", ""),
                        "university" to o.optString("university", "")
                    )
                )
            }
        } catch (_: Exception) {}

        return mapOf(
            "service_enabled" to isRecoveryServiceEnabled(),
            "active" to sp.getBoolean("active", false),
            "complete" to sp.getBoolean("complete", false),
            "scan_success" to sp.getBoolean("scan_success", false),
            "status" to sp.getString("status", "لم يبدأ الاسترجاع بعد."),
            "project_count" to sp.getInt("project_count", projects.size),
            "projects" to projects,
            "visible_text_count" to sp.getInt("visible_text_count", 0)
        )
    }

    private fun startRecovery(result: MethodChannel.Result) {
        if (!isRecoveryServiceEnabled()) {
            result.error(
                "SERVICE_DISABLED",
                "فعّل خدمة ProjectDesk Recovery من إعدادات إمكانية الوصول أولًا.",
                null
            )
            return
        }

        val launch = packageManager.getLaunchIntentForPackage(RecoveryAccessibilityService.TARGET_PACKAGE)
        if (launch == null) {
            result.error(
                "LEGACY_NOT_FOUND",
                "لم يتم العثور على ProjectDesk القديم على الجهاز.",
                null
            )
            return
        }

        getSharedPreferences(RecoveryAccessibilityService.PREFS, MODE_PRIVATE)
            .edit()
            .putString("projects_json", "[]")
            .putInt("project_count", 0)
            .putInt("visible_text_count", 0)
            .putString("last_snapshot", "")
            .putString("status", "جارٍ فتح ProjectDesk القديم…")
            .putString("session_id", System.currentTimeMillis().toString())
            .putBoolean("complete", false)
            .putBoolean("scan_success", false)
            .putBoolean("active", true)
            .apply()

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launch)
        result.success(true)
    }

    private fun createBackup(result: MethodChannel.Result) {
        val sp = getSharedPreferences(RecoveryAccessibilityService.PREFS, MODE_PRIVATE)
        val raw = sp.getString("projects_json", "[]") ?: "[]"

        val projects = try {
            JSONArray(raw)
        } catch (e: Exception) {
            result.error("INVALID_DISCOVERY", "تعذر قراءة نتائج الاكتشاف.", null)
            return
        }

        if (projects.length() == 0) {
            result.error(
                "EMPTY_DISCOVERY",
                "تم إيقاف إنشاء النسخة لأن عدد المشاريع المكتشفة يساوي صفر.",
                null
            )
            return
        }

        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 77)
            result.error("STORAGE_PERMISSION", "اسمح للتطبيق بالوصول إلى التخزين ثم أعد المحاولة.", null)
            return
        }

        io.execute {
            try {
                val out = BackupWriter.createBackup(this, projects)
                runOnUiThread {
                    result.success(
                        mapOf(
                            "project_count" to out.projectCount,
                            "file_name" to out.fileName,
                            "uri" to out.uri.toString()
                        )
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    result.error("BACKUP_FAILED", e.message ?: "فشل إنشاء ملف الاستعادة.", null)
                }
            }
        }
    }

    private fun isRecoveryServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expected = ComponentName(this, RecoveryAccessibilityService::class.java).flattenToString()
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
