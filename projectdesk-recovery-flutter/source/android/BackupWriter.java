package com.mographikod.projectdesk.recovery;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.DocumentsContract;

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

    public static Result createBackup(
            Context context,
            JSONArray projects,
            Uri selectedTreeUri) throws Exception {

        if (projects == null || projects.length() == 0) {
            throw new IllegalStateException(
                    "لن يتم إنشاء نسخة فارغة: عدد المشاريع المكتشفة يساوي صفر.");
        }

        if (selectedTreeUri == null) {
            throw new IllegalStateException(
                    "لم يتم تحديد مجلد حفظ النسخة الاحتياطية.");
        }

        File workDir = new File(
                context.getCacheDir(), "projectdesk_recovery_verified");
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
        if (verifiedCount <= 0) {
            throw new IllegalStateException(
                    "قاعدة الاستعادة الناتجة لا تحتوي أي مشروع.");
        }

        if (verifiedCount != projects.length()) {
            throw new IllegalStateException(
                    "فشل التحقق من قاعدة الاستعادة. المكتشف "
                            + projects.length()
                            + " والمحفوظ "
                            + verifiedCount
                            + ".");
        }

        String fileName =
                "ProjectDesk-Recovery-" + timestampForFile() + ".pobackup";
        File zipFile = new File(workDir, fileName);

        String manifest = new JSONObject()
                .put("app", "Project Organizer")
                .put("format", 1)
                .put("created_at", isoUtcNow())
                .put("recovered_by", "ProjectDesk Recovery 1.4")
                .put("project_count", verifiedCount)
                .put(
                        "attachment_note",
                        "Project records were recovered from the visible legacy "
                                + "ProjectDesk UI. Private attachment bytes cannot "
                                + "be copied from a differently signed app sandbox.")
                .toString();

        writeZip(zipFile, dbFile, manifest);

        if (!zipFile.isFile() || zipFile.length() < 1024) {
            throw new IOException("ملف الاستعادة الناتج غير صالح.");
        }

        // Re-open the produced archive and verify both mandatory entries exist.
        verifyZipPayload(zipFile);

        Uri savedUri = saveToSelectedFolder(
                context,
                selectedTreeUri,
                zipFile,
                fileName);

        return new Result(savedUri, fileName, verifiedCount);
    }

    private static void buildDatabase(
            File dbFile,
            JSONArray projects) throws Exception {

        if (dbFile.exists() && !dbFile.delete()) {
            throw new IOException(
                    "تعذر استبدال قاعدة البيانات المؤقتة.");
        }

        SQLiteDatabase db =
                SQLiteDatabase.openOrCreateDatabase(dbFile, null);

        try {
            db.setForeignKeyConstraintsEnabled(true);

            db.execSQL(
                    "CREATE TABLE android_metadata (locale TEXT)");
            ContentValues locale = new ContentValues();
            locale.put("locale", "en_US");
            db.insertOrThrow("android_metadata", null, locale);

            db.execSQL(
                    "CREATE TABLE projects("
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

            db.execSQL(
                    "CREATE TABLE deliverables("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "project_id INTEGER NOT NULL, "
                            + "key_name TEXT NOT NULL, "
                            + "title TEXT NOT NULL, "
                            + "required INTEGER NOT NULL DEFAULT 1, "
                            + "done INTEGER NOT NULL DEFAULT 0, "
                            + "file_path TEXT NOT NULL DEFAULT '', "
                            + "sort_order INTEGER NOT NULL DEFAULT 0, "
                            + "UNIQUE(project_id, key_name), "
                            + "FOREIGN KEY(project_id) REFERENCES projects(id) "
                            + "ON DELETE CASCADE)");

            db.execSQL(
                    "CREATE TABLE tasks("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "project_id INTEGER NOT NULL, "
                            + "title TEXT NOT NULL, "
                            + "source TEXT NOT NULL DEFAULT 'تعديل', "
                            + "details TEXT NOT NULL DEFAULT '', "
                            + "status TEXT NOT NULL DEFAULT 'معلق', "
                            + "created_at TEXT NOT NULL, "
                            + "completed_at TEXT, "
                            + "FOREIGN KEY(project_id) REFERENCES projects(id) "
                            + "ON DELETE CASCADE)");

            db.execSQL(
                    "CREATE TABLE project_files("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "project_id INTEGER NOT NULL, "
                            + "name TEXT NOT NULL, "
                            + "role TEXT NOT NULL DEFAULT 'مرفق', "
                            + "path TEXT NOT NULL, "
                            + "created_at TEXT NOT NULL, "
                            + "FOREIGN KEY(project_id) REFERENCES projects(id) "
                            + "ON DELETE CASCADE)");

            db.execSQL(
                    "CREATE TABLE settings("
                            + "key TEXT PRIMARY KEY, "
                            + "value TEXT NOT NULL)");

            db.beginTransaction();
            try {
                String now = isoUtcNow();

                for (int i = 0; i < projects.length(); i++) {
                    JSONObject p = projects.getJSONObject(i);

                    String title =
                            clean(p.optString("title", ""));
                    String student =
                            clean(p.optString("student", ""));
                    String university =
                            clean(p.optString("university", ""));

                    if (title.isEmpty() || student.isEmpty()) {
                        continue;
                    }

                    ContentValues v = new ContentValues();
                    v.put("original_title", title);
                    v.put("short_title", title);
                    v.put("concept_key", conceptKey(title));
                    v.put("student_name", student);
                    v.put("student_code", "");
                    v.put("university", university);
                    v.put("university_branch", "");
                    v.put("student_branch", "");
                    v.put("supervisor", "");
                    v.put("part", 1);
                    v.put("status", "جديد");
                    v.put("progress", 0);
                    v.put("has_ai", 0);
                    v.put("original_idea", "");
                    v.put("problem", "");
                    v.put("technologies", "");
                    v.put("functional_requirements", "");
                    v.put("folder_name", safeFolderName(title));
                    v.put("source_file_path", "");
                    v.put("last_note", buildRecoveryNote(p));
                    v.put("created_at", now);
                    v.put("updated_at", now);

                    db.insertOrThrow("projects", null, v);
                }

                ContentValues recovered = new ContentValues();
                recovered.put("key", "recovered_by");
                recovered.put(
                        "value", "ProjectDesk Recovery 1.4");
                db.insertOrThrow("settings", null, recovered);

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
                dbFile.getAbsolutePath(),
                null,
                SQLiteDatabase.OPEN_READONLY);

        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM projects",
                null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            db.close();
        }
    }

    private static String buildRecoveryNote(JSONObject p) {
        StringBuilder note =
                new StringBuilder(
                        "مستعاد بواسطة ProjectDesk Recovery 1.4.");

        JSONArray raw = p.optJSONArray("raw");
        if (raw != null && raw.length() > 0) {
            note.append(
                    "\nالنص الظاهر في البطاقة القديمة:");

            for (int i = 0; i < raw.length(); i++) {
                String line =
                        clean(raw.optString(i, ""));
                if (!line.isEmpty()) {
                    note.append("\n• ").append(line);
                }
            }
        }

        return note.toString();
    }

    private static void writeZip(
            File zipFile,
            File dbFile,
            String manifest) throws IOException {

        try (ZipOutputStream zos =
                     new ZipOutputStream(
                             new BufferedOutputStream(
                                     new FileOutputStream(zipFile)))) {

            zos.putNextEntry(
                    new ZipEntry(
                            "database/project_organizer.db"));

            try (BufferedInputStream in =
                         new BufferedInputStream(
                                 new FileInputStream(dbFile))) {

                byte[] buffer = new byte[8192];
                int read;

                while ((read = in.read(buffer)) != -1) {
                    zos.write(buffer, 0, read);
                }
            }

            zos.closeEntry();

            zos.putNextEntry(
                    new ZipEntry("manifest.json"));
            zos.write(
                    manifest.getBytes(
                            StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private static void verifyZipPayload(
            File zipFile) throws IOException {

        boolean hasDatabase = false;
        boolean hasManifest = false;

        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(
                             new BufferedInputStream(
                                     new FileInputStream(zipFile)))) {

            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("database/project_organizer.db"
                        .equals(entry.getName())) {
                    hasDatabase = true;
                } else if ("manifest.json"
                        .equals(entry.getName())) {
                    hasManifest = true;
                }
                zis.closeEntry();
            }
        }

        if (!hasDatabase || !hasManifest) {
            throw new IOException(
                    "ملف الاستعادة الداخلي غير مكتمل.");
        }
    }

    private static Uri saveToSelectedFolder(
            Context context,
            Uri treeUri,
            File source,
            String fileName) throws IOException {

        final ContentResolver resolver =
                context.getContentResolver();

        final String treeDocumentId;
        final Uri parentDocumentUri;

        try {
            treeDocumentId =
                    DocumentsContract.getTreeDocumentId(treeUri);
            parentDocumentUri =
                    DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            treeDocumentId);
        } catch (Exception e) {
            throw new IOException(
                    "تعذر فتح المجلد المحدد. اختر مجلد الحفظ مرة أخرى.",
                    e);
        }

        final Uri outputUri;
        try {
            outputUri = DocumentsContract.createDocument(
                    resolver,
                    parentDocumentUri,
                    "application/zip",
                    fileName);
        } catch (Exception e) {
            throw new IOException(
                    "لا يمكن إنشاء ملف داخل المجلد المحدد. "
                            + "اختر مجلدًا مثل Download أو Documents.",
                    e);
        }

        if (outputUri == null) {
            throw new IOException(
                    "لم يمنح مدير الملفات صلاحية إنشاء النسخة داخل المجلد.");
        }

        boolean success = false;

        try (BufferedInputStream in =
                     new BufferedInputStream(
                             new FileInputStream(source));
             OutputStream rawOut =
                     resolver.openOutputStream(
                             outputUri,
                             "w");
             BufferedOutputStream out =
                     rawOut == null
                             ? null
                             : new BufferedOutputStream(rawOut)) {

            if (out == null) {
                throw new IOException(
                        "تعذر فتح ملف النسخة للكتابة.");
            }

            byte[] buffer = new byte[8192];
            int read;

            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }

            out.flush();
            success = true;
        } finally {
            if (!success) {
                try {
                    DocumentsContract.deleteDocument(
                            resolver,
                            outputUri);
                } catch (Exception ignored) {}
            }
        }

        return outputUri;
    }

    private static String conceptKey(String title) {
        String n =
                RecoveryAccessibilityService.normalize(title)
                        .replaceAll(
                                "[^\\p{L}\\p{N}]+",
                                "_")
                        .replaceAll("^_+|_+$", "");

        return n.isEmpty()
                ? "recovered_project"
                : n;
    }

    private static String safeFolderName(String title) {
        String s = clean(title)
                .replaceAll(
                        "[\\\\/:*?\"<>|]",
                        "_");

        if (s.length() > 80) {
            s = s.substring(0, 80);
        }

        return s;
    }

    private static String clean(String s) {
        return s == null
                ? ""
                : s.replaceAll("\\s+", " ").trim();
    }

    private static String isoUtcNow() {
        SimpleDateFormat f =
                new SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        Locale.US);

        f.setTimeZone(
                TimeZone.getTimeZone("UTC"));

        return f.format(new Date());
    }

    private static String timestampForFile() {
        return new SimpleDateFormat(
                "yyyy-MM-dd_HH-mm-ss",
                Locale.US)
                .format(new Date());
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;

        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }

        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
