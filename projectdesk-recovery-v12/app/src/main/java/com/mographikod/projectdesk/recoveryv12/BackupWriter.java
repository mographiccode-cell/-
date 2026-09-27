package com.mographikod.projectdesk.recoveryv12;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BackupWriter {
    private BackupWriter() {}

    public static final class Result {
        public final Uri uri;
        public final String fileName;
        public final int projectCount;

        Result(Uri uri, String fileName, int projectCount) {
            this.uri = uri;
            this.fileName = fileName;
            this.projectCount = projectCount;
        }
    }

    public static Result createBackup(Context context, JSONArray projects) throws Exception {
        if (projects == null || projects.length() == 0) {
            throw new IllegalStateException("لن يتم إنشاء نسخة فارغة: عدد المشاريع المكتشفة يساوي صفر.");
        }

        File workDir = new File(context.getCacheDir(), "projectdesk_recovery_v12");
        deleteRecursively(workDir);
        if (!workDir.mkdirs() && !workDir.isDirectory()) {
            throw new IOException("تعذر إنشاء مجلد العمل المؤقت.");
        }

        File dbDir = new File(workDir, "database");
        if (!dbDir.mkdirs() && !dbDir.isDirectory()) {
            throw new IOException("تعذر إنشاء مجلد قاعدة البيانات.");
        }

        File dbFile = new File(dbDir, "project_organizer.db");
        buildDatabase(dbFile, projects);

        int verifiedCount = verifyDatabase(dbFile);
        if (verifiedCount <= 0 || verifiedCount != projects.length()) {
            throw new IllegalStateException(
                    "فشل التحقق من قاعدة الاستعادة. المتوقع " + projects.length()
                            + " مشروع، والموجود فعليًا " + verifiedCount + ".");
        }

        String stamp = timestampForFile();
        String fileName = "ProjectDesk-Recovery-" + stamp + ".pobackup";
        File zipFile = new File(workDir, fileName);

        String manifest = new JSONObject()
                .put("app", "Project Organizer")
                .put("format", 1)
                .put("created_at", isoUtcNow())
                .put("recovered_by", "ProjectDesk Recovery 1.2")
                .put("attachment_note",
                        "Recovered project identity fields from the legacy ProjectDesk UI. "
                                + "Private attachment bytes remain protected by Android app sandboxing.")
                .toString();

        writeZip(zipFile, dbFile, manifest);

        if (!zipFile.isFile() || zipFile.length() < 1024) {
            throw new IOException("ملف الاستعادة الناتج غير صالح.");
        }

        Uri savedUri = saveToDownloads(context, zipFile, fileName);
        return new Result(savedUri, fileName, verifiedCount);
    }

    private static void buildDatabase(File dbFile, JSONArray projects) throws Exception {
        if (dbFile.exists() && !dbFile.delete()) {
            throw new IOException("تعذر استبدال قاعدة البيانات المؤقتة.");
        }

        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        try {
            db.setForeignKeyConstraintsEnabled(true);

            db.execSQL("CREATE TABLE IF NOT EXISTS android_metadata (locale TEXT)");
            db.execSQL("DELETE FROM android_metadata");
            ContentValues locale = new ContentValues();
            locale.put("locale", "ar_SA");
            db.insertOrThrow("android_metadata", null, locale);

            db.execSQL("CREATE TABLE projects("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "original_title TEXT NOT NULL DEFAULT '', "
                    + "short_title TEXT NOT NULL, "
                    + "concept_key TEXT NOT NULL, "
                    + "student_name TEXT NOT NULL, "
                    + "student_code TEXT NOT NULL DEFAULT '', "
                    + "university TEXT NOT NULL DEFAULT '', "
                    + "university_branch TEXT NOT NULL DEFAULT '', "
                    + "student_branch TEXT NOT NULL DEFAULT '', "
                    + "supervisor TEXT NOT NULL DEFAULT '', "
                    + "part INTEGER NOT NULL DEFAULT 1, "
                    + "status TEXT NOT NULL DEFAULT 'جديد', "
                    + "progress INTEGER NOT NULL DEFAULT 0, "
                    + "has_ai INTEGER NOT NULL DEFAULT 0, "
                    + "original_idea TEXT NOT NULL DEFAULT '', "
                    + "problem TEXT NOT NULL DEFAULT '', "
                    + "technologies TEXT NOT NULL DEFAULT '', "
                    + "functional_requirements TEXT NOT NULL DEFAULT '', "
                    + "folder_name TEXT NOT NULL DEFAULT '', "
                    + "source_file_path TEXT NOT NULL DEFAULT '', "
                    + "last_note TEXT NOT NULL DEFAULT '', "
                    + "created_at TEXT NOT NULL, "
                    + "updated_at TEXT NOT NULL)");

            db.execSQL("CREATE TABLE deliverables("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "project_id INTEGER NOT NULL, "
                    + "key_name TEXT NOT NULL, "
                    + "title TEXT NOT NULL, "
                    + "required INTEGER NOT NULL DEFAULT 1, "
                    + "done INTEGER NOT NULL DEFAULT 0, "
                    + "file_path TEXT NOT NULL DEFAULT '', "
                    + "sort_order INTEGER NOT NULL DEFAULT 0, "
                    + "UNIQUE(project_id, key_name), "
                    + "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)");

            db.execSQL("CREATE TABLE tasks("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "project_id INTEGER NOT NULL, "
                    + "title TEXT NOT NULL, "
                    + "source TEXT NOT NULL DEFAULT 'تعديل', "
                    + "details TEXT NOT NULL DEFAULT '', "
                    + "status TEXT NOT NULL DEFAULT 'معلق', "
                    + "created_at TEXT NOT NULL, "
                    + "completed_at TEXT, "
                    + "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)");

            db.execSQL("CREATE TABLE project_files("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "project_id INTEGER NOT NULL, "
                    + "name TEXT NOT NULL, "
                    + "role TEXT NOT NULL DEFAULT 'مرفق', "
                    + "path TEXT NOT NULL, "
                    + "created_at TEXT NOT NULL, "
                    + "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)");

            db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)");

            db.beginTransaction();
            try {
                String now = isoUtcNow();
                for (int i = 0; i < projects.length(); i++) {
                    JSONObject p = projects.getJSONObject(i);
                    String title = clean(p.optString("title", ""));
                    String student = clean(p.optString("student", ""));
                    String university = clean(p.optString("university", ""));
                    if (title.isEmpty()) continue;

                    ContentValues values = new ContentValues();
                    values.put("original_title", title);
                    values.put("short_title", title);
                    values.put("concept_key", conceptKey(title));
                    values.put("student_name", student);
                    values.put("student_code", "");
                    values.put("university", university);
                    values.put("university_branch", "");
                    values.put("student_branch", "");
                    values.put("supervisor", "");
                    values.put("part", 1);
                    values.put("status", "جديد");
                    values.put("progress", 0);
                    values.put("has_ai", 0);
                    values.put("original_idea", "");
                    values.put("problem", "");
                    values.put("technologies", "");
                    values.put("functional_requirements", "");
                    values.put("folder_name", safeFolderName(title));
                    values.put("source_file_path", "");
                    values.put("last_note", buildRecoveryNote(p));
                    values.put("created_at", now);
                    values.put("updated_at", now);
                    db.insertOrThrow("projects", null, values);
                }

                ContentValues setting = new ContentValues();
                setting.put("key", "recovered_by");
                setting.put("value", "ProjectDesk Recovery 1.2");
                db.insertWithOnConflict("settings", null, setting, SQLiteDatabase.CONFLICT_REPLACE);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            db.close();
        }
    }

    private static int verifyDatabase(File dbFile) {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM projects", null)) {
            if (!cursor.moveToFirst()) return 0;
            return cursor.getInt(0);
        } finally {
            db.close();
        }
    }

    private static String buildRecoveryNote(JSONObject p) {
        StringBuilder note = new StringBuilder("مستعاد آليًا بواسطة ProjectDesk Recovery 1.2.");
        JSONArray raw = p.optJSONArray("raw");
        if (raw != null && raw.length() > 0) {
            note.append("\nالنص الظاهر في البطاقة القديمة:");
            for (int i = 0; i < raw.length(); i++) {
                String line = clean(raw.optString(i, ""));
                if (!line.isEmpty()) note.append("\n• ").append(line);
            }
        }
        return note.toString();
    }

    private static void writeZip(File zipFile, File dbFile, String manifest) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            zos.putNextEntry(new ZipEntry("database/project_organizer.db"));
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(dbFile))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) zos.write(buffer, 0, read);
            }
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private static Uri saveToDownloads(Context context, File source, String fileName) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/ProjectDesk Recovery");
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("تعذر إنشاء الملف داخل Downloads.");

            boolean success = false;
            try (OutputStream out = resolver.openOutputStream(uri);
                 BufferedInputStream in = new BufferedInputStream(new FileInputStream(source))) {
                if (out == null) throw new IOException("تعذر فتح ملف Downloads للكتابة.");
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                success = true;
            } finally {
                if (!success) resolver.delete(uri, null, null);
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return uri;
        }

        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File folder = new File(downloads, "ProjectDesk Recovery");
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("تعذر إنشاء مجلد ProjectDesk Recovery داخل Downloads.");
        }
        File dest = new File(folder, fileName);
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        return Uri.fromFile(dest);
    }

    private static String conceptKey(String title) {
        String n = RecoveryAccessibilityService.normalize(title)
                .replaceAll("[^\\p{L}\\p{N}]+", "_")
                .replaceAll("^_+|_+$", "");
        return n.isEmpty() ? "recovered_project" : n;
    }

    private static String safeFolderName(String title) {
        String s = clean(title).replaceAll("[\\\\/:*?\"<>|]", "_");
        if (s.length() > 80) s = s.substring(0, 80);
        return s;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String isoUtcNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String timestampForFile() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
        return f.format(new Date());
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
