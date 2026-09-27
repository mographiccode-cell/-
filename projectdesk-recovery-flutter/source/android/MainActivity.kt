package com.mographikod.projectdesk.recovery

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    companion object {
        private const val PICK_BACKUP_FOLDER = 9104
        private const val KEY_BACKUP_TREE_URI = "backup_tree_uri"
        private const val KEY_BACKUP_FOLDER_LABEL = "backup_folder_label"
    }

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
                    "selectBackupFolder" -> selectBackupFolder(result)
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

        val folderUri = sp.getString(KEY_BACKUP_TREE_URI, "") ?: ""

        return mapOf(
            "service_enabled" to isRecoveryServiceEnabled(),
            "active" to sp.getBoolean("active", false),
            "complete" to sp.getBoolean("complete", false),
            "scan_success" to sp.getBoolean("scan_success", false),
            "status" to sp.getString("status", "لم يبدأ الاسترجاع بعد."),
            "project_count" to sp.getInt("project_count", projects.size),
            "projects" to projects,
            "visible_text_count" to sp.getInt("visible_text_count", 0),
            "scroll_steps" to sp.getInt("scroll_steps", 0),
            "backup_folder_set" to folderUri.isNotBlank(),
            "backup_folder_label" to (
                sp.getString(KEY_BACKUP_FOLDER_LABEL, "") ?: ""
            )
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

        val launch = packageManager.getLaunchIntentForPackage(
            RecoveryAccessibilityService.TARGET_PACKAGE
        )
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
            .putInt("scroll_steps", 0)
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

    private fun selectBackupFolder(result: MethodChannel.Result) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }

        startActivityForResult(intent, PICK_BACKUP_FOLDER)
        result.success(true)
    }

    @Deprecated("Deprecated in Android API, retained for broad device compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != PICK_BACKUP_FOLDER || resultCode != Activity.RESULT_OK) return

        val uri = data?.data ?: return
        val flags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some file providers grant usable access for the current app without persistence.
        }

        getSharedPreferences(RecoveryAccessibilityService.PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKUP_TREE_URI, uri.toString())
            .putString(KEY_BACKUP_FOLDER_LABEL, readableFolderName(uri))
            .apply()
    }

    private fun readableFolderName(uri: Uri): String {
        return try {
            val id = DocumentsContract.getTreeDocumentId(uri)
            val value = id.substringAfter(":", id)
            if (value.isBlank()) "المجلد المحدد" else value
        } catch (_: Exception) {
            uri.lastPathSegment ?: "المجلد المحدد"
        }
    }

    private fun createBackup(result: MethodChannel.Result) {
        val sp = getSharedPreferences(RecoveryAccessibilityService.PREFS, MODE_PRIVATE)
        val raw = sp.getString("projects_json", "[]") ?: "[]"

        val projects = try {
            JSONArray(raw)
        } catch (_: Exception) {
            result.error(
                "INVALID_DISCOVERY",
                "تعذر قراءة نتائج الاكتشاف.",
                null
            )
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

        val folderValue = sp.getString(KEY_BACKUP_TREE_URI, "") ?: ""
        if (folderValue.isBlank()) {
            result.error(
                "FOLDER_REQUIRED",
                "اختر مجلد حفظ النسخة الاحتياطية أولًا.",
                null
            )
            return
        }

        val folderUri = try {
            Uri.parse(folderValue)
        } catch (_: Exception) {
            result.error(
                "INVALID_FOLDER",
                "مجلد الحفظ المحدد غير صالح. اختر المجلد مرة أخرى.",
                null
            )
            return
        }

        io.execute {
            try {
                val out = BackupWriter.createBackup(
                    this,
                    projects,
                    folderUri
                )

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
                    result.error(
                        "BACKUP_FAILED",
                        e.message ?: "فشل إنشاء ملف الاستعادة.",
                        null
                    )
                }
            }
        }
    }

    private fun isRecoveryServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expected = ComponentName(
            this,
            RecoveryAccessibilityService::class.java
        ).flattenToString()

        return enabled.split(":").any {
            it.equals(expected, ignoreCase = true)
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
