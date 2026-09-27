package com.mographikod.projectdesk.recoveryv12;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int WRITE_REQUEST = 71;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView serviceState;
    private TextView recoveryState;
    private TextView projectPreview;
    private Button startButton;
    private Button backupButton;
    private Button shareButton;

    private BackupWriter.Result lastBackup;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 900);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    private void buildUi() {
        int pad = dp(20);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(250, 249, 247));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(28), pad, dp(36));
        root.setGravity(Gravity.RIGHT);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("ProjectDesk Recovery 1.2", 28, true);
        title.setTextColor(Color.rgb(35, 30, 30));
        root.addView(title, fullWidth());

        TextView subtitle = text(
                "إصدار إصلاح الاسترجاع: يقرأ بطاقات ProjectDesk القديم (عنوان المشروع + الطالب + الجامعة)، "
                        + "ويمنع إنشاء أي نسخة فارغة.", 16, false);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(8), 0, dp(20));
        root.addView(subtitle, fullWidth());

        root.addView(sectionTitle("1. تفعيل خدمة الاسترجاع"));
        serviceState = text("", 15, true);
        root.addView(serviceState, fullWidth());

        Button accessibility = button("فتح إعدادات إمكانية الوصول");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, fullWidthWithMargin());

        root.addView(sectionTitle("2. اكتشاف المشاريع القديمة"));
        TextView explainer = text(
                "لن يقرأ التطبيق أي تطبيق آخر. بعد الضغط سيُفتح ProjectDesk القديم تلقائيًا، "
                        + "ثم يتم تمرير قائمة المشاريع وقراءة البطاقات الظاهرة.", 15, false);
        root.addView(explainer, fullWidthWithMargin());

        startButton = button("بدء الاسترجاع من ProjectDesk القديم");
        startButton.setOnClickListener(v -> startRecovery());
        root.addView(startButton, fullWidthWithMargin());

        recoveryState = text("لم يبدأ الاسترجاع بعد.", 16, true);
        recoveryState.setPadding(0, dp(16), 0, dp(8));
        root.addView(recoveryState, fullWidth());

        projectPreview = text("", 14, false);
        projectPreview.setTextColor(Color.DKGRAY);
        projectPreview.setPadding(dp(12), dp(12), dp(12), dp(12));
        projectPreview.setBackgroundColor(Color.rgb(242, 238, 235));
        root.addView(projectPreview, fullWidthWithMargin());

        root.addView(sectionTitle("3. إنشاء ملف الاستعادة"));
        TextView backupInfo = text(
                "زر الحفظ لا يعمل إلا إذا اكتشف التطبيق مشروعًا واحدًا على الأقل. "
                        + "الملف الناتج يحتوي database/project_organizer.db وmanifest.json "
                        + "بنفس صيغة الاستعادة المتوقعة في ProjectDesk Next.", 15, false);
        root.addView(backupInfo, fullWidthWithMargin());

        backupButton = button("إنشاء وحفظ ملف .pobackup");
        backupButton.setEnabled(false);
        backupButton.setOnClickListener(v -> createBackup());
        root.addView(backupButton, fullWidthWithMargin());

        shareButton = button("مشاركة ملف الاستعادة");
        shareButton.setVisibility(View.GONE);
        shareButton.setOnClickListener(v -> shareLastBackup());
        root.addView(shareButton, fullWidthWithMargin());

        TextView warning = text(
                "مهم: لا تحذف ProjectDesk القديم ولا تمسح بياناته حتى تفتح ProjectDesk Next "
                        + "وتتأكد أن المشاريع ظهرت داخله.", 15, true);
        warning.setTextColor(Color.rgb(150, 55, 45));
        warning.setPadding(0, dp(24), 0, 0);
        root.addView(warning, fullWidth());

        setContentView(scroll);
    }

    private void startRecovery() {
        if (!isRecoveryServiceEnabled()) {
            Toast.makeText(this,
                    "فعّل ProjectDesk Recovery 1.2 من إعدادات إمكانية الوصول أولًا.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        Intent launch = getPackageManager().getLaunchIntentForPackage(
                RecoveryAccessibilityService.TARGET_PACKAGE);
        if (launch == null) {
            Toast.makeText(this,
                    "لم يتم العثور على ProjectDesk القديم (com.mographikod.projectdesk).",
                    Toast.LENGTH_LONG).show();
            return;
        }

        SharedPreferences sp = getSharedPreferences(
                RecoveryAccessibilityService.PREFS, MODE_PRIVATE);
        sp.edit()
                .putString("projects_json", "[]")
                .putInt("project_count", 0)
                .putInt("visible_text_count", 0)
                .putString("last_snapshot", "")
                .putString("status", "جارٍ فتح ProjectDesk القديم…")
                .putString("session_id", Long.toString(System.currentTimeMillis()))
                .putBoolean("complete", false)
                .putBoolean("scan_success", false)
                .putBoolean("active", true)
                .apply();

        lastBackup = null;
        shareButton.setVisibility(View.GONE);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(launch);
    }

    private void createBackup() {
        SharedPreferences sp = getSharedPreferences(
                RecoveryAccessibilityService.PREFS, MODE_PRIVATE);
        String raw = sp.getString("projects_json", "[]");

        final JSONArray projects;
        try {
            projects = new JSONArray(raw);
        } catch (Exception e) {
            Toast.makeText(this, "تعذر قراءة نتائج الاكتشاف.", Toast.LENGTH_LONG).show();
            return;
        }

        if (projects.length() == 0) {
            Toast.makeText(this,
                    "تم إيقاف الحفظ لأن عدد المشاريع صفر. لن ننشئ Backup فارغًا.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_REQUEST);
            Toast.makeText(this, "اسمح بالتخزين ثم اضغط إنشاء النسخة مرة أخرى.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        backupButton.setEnabled(false);
        recoveryState.setText("جارٍ بناء قاعدة SQLite والتحقق منها قبل الحفظ…");

        new Thread(() -> {
            try {
                BackupWriter.Result result = BackupWriter.createBackup(this, projects);
                runOnUiThread(() -> {
                    lastBackup = result;
                    recoveryState.setText(
                            "تم إنشاء نسخة صحيحة والتحقق منها: "
                                    + result.projectCount + " مشروع.\n"
                                    + "حُفظت في Downloads/ProjectDesk Recovery/\n"
                                    + result.fileName);
                    shareButton.setVisibility(View.VISIBLE);
                    backupButton.setEnabled(true);
                    Toast.makeText(this, "تم حفظ ملف الاستعادة بنجاح.",
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    recoveryState.setText("فشل إنشاء النسخة: " + e.getMessage());
                    backupButton.setEnabled(true);
                    Toast.makeText(this, "لم يتم إنشاء ملف ناقص أو فارغ.",
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void shareLastBackup() {
        if (lastBackup == null || lastBackup.uri == null) return;
        if ("file".equalsIgnoreCase(lastBackup.uri.getScheme())) {
            Toast.makeText(this,
                    "الملف محفوظ في Downloads/ProjectDesk Recovery.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("application/zip");
        share.putExtra(Intent.EXTRA_STREAM, lastBackup.uri);
        share.setClipData(ClipData.newRawUri("ProjectDesk backup", lastBackup.uri));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "مشاركة ملف الاستعادة"));
    }

    private void refreshUi() {
        boolean enabled = isRecoveryServiceEnabled();
        serviceState.setText(enabled
                ? "✓ خدمة الاسترجاع مفعلة."
                : "الخدمة غير مفعلة. افتح الإعدادات وفعّل ProjectDesk Recovery 1.2.");

        SharedPreferences sp = getSharedPreferences(
                RecoveryAccessibilityService.PREFS, MODE_PRIVATE);
        int count = sp.getInt("project_count", 0);
        boolean active = sp.getBoolean("active", false);
        boolean complete = sp.getBoolean("complete", false);
        String status = sp.getString("status", "لم يبدأ الاسترجاع بعد.");
        int visibleTexts = sp.getInt("visible_text_count", 0);

        recoveryState.setText(status + "\nعدد المشاريع المكتشفة: " + count
                + (active ? "\nالاكتشاف يعمل الآن…" : ""));

        backupButton.setEnabled(count > 0 && !active);
        startButton.setEnabled(!active);

        projectPreview.setText(buildPreview(sp, count, visibleTexts, complete));
    }

    private String buildPreview(SharedPreferences sp, int count, int visibleTexts, boolean complete) {
        StringBuilder sb = new StringBuilder();
        if (count <= 0) {
            sb.append("لم تُسجل مشاريع بعد.");
            if (visibleTexts > 0) {
                sb.append("\nتمكن Accessibility من رؤية ")
                        .append(visibleTexts)
                        .append(" عنصرًا نصيًا في الشاشة القديمة.");
            }
            if (complete) {
                String snapshot = sp.getString("last_snapshot", "");
                if (snapshot != null && !snapshot.trim().isEmpty()) {
                    String[] lines = snapshot.split("\\n");
                    sb.append("\n\nعينة مما رآه التطبيق:");
                    for (int i = 0; i < Math.min(lines.length, 8); i++) {
                        if (!lines[i].trim().isEmpty()) sb.append("\n• ").append(lines[i].trim());
                    }
                }
            }
            return sb.toString();
        }

        try {
            JSONArray arr = new JSONArray(sp.getString("projects_json", "[]"));
            sb.append("المشاريع التي سيتم وضعها داخل النسخة:");
            for (int i = 0; i < Math.min(arr.length(), 12); i++) {
                JSONObject p = arr.getJSONObject(i);
                sb.append("\n\n").append(i + 1).append(") ")
                        .append(p.optString("title", "بدون عنوان"));
                String student = p.optString("student", "");
                String university = p.optString("university", "");
                if (!student.isEmpty()) sb.append("\n   الطالب: ").append(student);
                if (!university.isEmpty()) sb.append("\n   الجامعة: ").append(university);
            }
            if (arr.length() > 12) sb.append("\n\n… وبقية المشاريع");
        } catch (Exception e) {
            sb.append("تم اكتشاف ").append(count).append(" مشروع.");
        }
        return sb.toString();
    }

    private boolean isRecoveryServiceEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;

        String expected = new ComponentName(
                this, RecoveryAccessibilityService.class).flattenToString();
        for (String item : enabled.split(":")) {
            if (expected.equalsIgnoreCase(item)) return true;
        }
        return false;
    }

    private TextView sectionTitle(String value) {
        TextView t = text(value, 20, true);
        t.setTextColor(Color.rgb(70, 55, 50));
        t.setPadding(0, dp(22), 0, dp(10));
        return t;
    }

    private TextView text(String value, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(Color.rgb(45, 42, 40));
        t.setGravity(Gravity.RIGHT);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setLineSpacing(0, 1.22f);
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(54));
        return b;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fullWidthWithMargin() {
        LinearLayout.LayoutParams p = fullWidth();
        p.topMargin = dp(10);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
