package com.mographikod.projectdesk.recoveryv12;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class RecoveryAccessibilityService extends AccessibilityService {
    static final String PREFS = "recovery_state";
    static final String TARGET_PACKAGE = "com.mographikod.projectdesk";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanner = this::scanCurrentWindow;

    private String localSession = "";
    private int stableRounds = 0;
    private int navigationAttempts = 0;
    private int nullRootRounds = 0;

    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
            "projectdesk", "projectdesk القديم", "الرئيسية", "الصفحة الرئيسية",
            "المشاريع", "المشروعات", "projects", "project", "المهام", "tasks",
            "الإعدادات", "اعدادات", "settings", "بحث", "search", "إضافة",
            "إضافة مشروع", "اضافة مشروع", "مشروع جديد", "new project",
            "الملفات", "files", "التقارير", "reports", "الطلاب", "students",
            "المشرفون", "supervisors", "تسجيل الخروج", "logout"
    ));

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        scheduleScan(350);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!sp.getBoolean("active", false)) return;

        CharSequence pkg = event.getPackageName();
        if (pkg != null && TARGET_PACKAGE.contentEquals(pkg)) {
            scheduleScan(450);
        }
    }

    @Override
    public void onInterrupt() {
        // No destructive action. The user can restart recovery from the app.
    }

    private void scheduleScan(long delayMs) {
        handler.removeCallbacks(scanner);
        handler.postDelayed(scanner, delayMs);
    }

    private void resetForSession(String session) {
        localSession = session;
        stableRounds = 0;
        navigationAttempts = 0;
        nullRootRounds = 0;
    }

    private void scanCurrentWindow() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!sp.getBoolean("active", false)) return;

        String session = sp.getString("session_id", "");
        if (!session.equals(localSession)) resetForSession(session);

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            nullRootRounds++;
            setStatus("بانتظار واجهة ProjectDesk القديم…");
            if (nullRootRounds >= 12) {
                finishRecovery(false, "تعذر قراءة نافذة التطبيق القديم. تأكد من تفعيل خدمة إمكانية الوصول ثم أعد المحاولة.");
            } else {
                scheduleScan(700);
            }
            return;
        }
        nullRootRounds = 0;

        String packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (!TARGET_PACKAGE.equals(packageName)) {
            setStatus("افتح ProjectDesk القديم واتركه أمامك لثوانٍ.");
            scheduleScan(700);
            return;
        }

        saveRawSnapshot(root);

        LinkedHashMap<String, ProjectCandidate> existing = readProjects();
        int before = existing.size();

        List<ProjectCandidate> candidates = extractProjectCandidates(root);
        for (ProjectCandidate candidate : candidates) {
            if (candidate.isValid()) {
                existing.put(candidate.fingerprint(), candidate);
            }
        }
        writeProjects(existing);

        int after = existing.size();
        if (after > before) {
            stableRounds = 0;
            setStatus("تم اكتشاف " + after + " مشروع. جارٍ متابعة القائمة…");
        } else {
            stableRounds++;
        }

        // If we have not found a project yet, first try to enter the Projects section.
        if (after == 0 && navigationAttempts < 4) {
            if (clickProjectsNavigation(root)) {
                navigationAttempts++;
                stableRounds = 0;
                setStatus("تم فتح قسم المشاريع. جارٍ قراءة البطاقات…");
                scheduleScan(900);
                return;
            }
            navigationAttempts++;
        }

        AccessibilityNodeInfo scrollable = findBestScrollable(root);
        boolean moved = false;
        if (scrollable != null) {
            moved = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        }

        if (moved && stableRounds < 4) {
            scheduleScan(900);
            return;
        }

        if (after > 0 && (stableRounds >= 2 || !moved)) {
            finishRecovery(true, "اكتمل الاكتشاف: " + after + " مشروع.");
        } else if (after == 0 && stableRounds >= 3) {
            finishRecovery(false,
                    "لم يتم العثور على بطاقات مشاريع قابلة للقراءة. لن يتم إنشاء نسخة احتياطية فارغة.");
        } else {
            scheduleScan(900);
        }
    }

    private void finishRecovery(boolean success, String message) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit()
                .putBoolean("active", false)
                .putBoolean("complete", true)
                .putBoolean("scan_success", success)
                .putString("status", message)
                .apply();

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void setStatus(String message) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("status", message).apply();
    }

    private LinkedHashMap<String, ProjectCandidate> readProjects() {
        LinkedHashMap<String, ProjectCandidate> map = new LinkedHashMap<>();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString("projects_json", "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                ProjectCandidate p = ProjectCandidate.fromJson(arr.getJSONObject(i));
                if (p.isValid()) map.put(p.fingerprint(), p);
            }
        } catch (JSONException ignored) {
        }
        return map;
    }

    private void writeProjects(LinkedHashMap<String, ProjectCandidate> projects) {
        JSONArray arr = new JSONArray();
        for (ProjectCandidate p : projects.values()) arr.put(p.toJson());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("projects_json", arr.toString())
                .putInt("project_count", projects.size())
                .apply();
    }

    private List<ProjectCandidate> extractProjectCandidates(AccessibilityNodeInfo root) {
        List<ProjectCandidate> out = new ArrayList<>();

        List<AccessibilityNodeInfo> scrollables = new ArrayList<>();
        collectScrollableNodes(root, scrollables);
        for (AccessibilityNodeInfo list : scrollables) {
            int childCount = list.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = list.getChild(i);
                if (child == null) continue;
                List<String> lines = new ArrayList<>();
                collectTexts(child, lines);
                ProjectCandidate p = parseCandidate(lines);
                if (p != null && p.isValid()) out.add(p);
            }
        }

        // Flutter often exposes cards as clickable semantic nodes instead of RecyclerView rows.
        List<AccessibilityNodeInfo> clickables = new ArrayList<>();
        collectClickableNodes(root, clickables);
        for (AccessibilityNodeInfo node : clickables) {
            List<String> lines = new ArrayList<>();
            collectTexts(node, lines);
            ProjectCandidate p = parseCandidate(lines);
            if (p != null && p.isValid()) out.add(p);
        }

        // Last fallback: parse the visible semantic text as consecutive card blocks.
        if (out.isEmpty()) {
            List<String> all = new ArrayList<>();
            collectTexts(root, all);
            List<String> cleaned = cleanLines(all);
            for (int i = 0; i < cleaned.size(); i++) {
                String line = cleaned.get(i);
                if (looksLikeUniversity(line)) {
                    int start = Math.max(0, i - 2);
                    int end = Math.min(cleaned.size(), i + 2);
                    ProjectCandidate p = parseCandidate(new ArrayList<>(cleaned.subList(start, end)));
                    if (p != null && p.isValid()) out.add(p);
                }
            }
        }

        LinkedHashMap<String, ProjectCandidate> unique = new LinkedHashMap<>();
        for (ProjectCandidate p : out) unique.put(p.fingerprint(), p);
        return new ArrayList<>(unique.values());
    }

    private ProjectCandidate parseCandidate(List<String> input) {
        List<String> lines = cleanLines(input);
        if (lines.size() < 2) return null;

        String title = extractLabeled(lines,
                "عنوان المشروع", "اسم المشروع", "project title", "project name");

        // Legacy ProjectDesk cards put the project title first. Do not reject a title
        // merely because the project itself contains words such as "جامعة".
        if (TextUtils.isEmpty(title)) {
            for (String line : lines) {
                if (isGeneric(line) || looksLikeStudentLabel(line) || looksLikeStatus(line)) continue;
                String candidateTitle = stripLabel(line, "المشروع", "project");
                if (!TextUtils.isEmpty(candidateTitle)) {
                    title = candidateTitle;
                    break;
                }
            }
        }

        String student = extractLabeled(lines,
                "اسم الطالب", "الطالب", "student name", "student");
        String university = extractLabeled(lines,
                "اسم الجامعة", "الجامعة", "university");

        // When there is no explicit label, the university is normally the last
        // university-looking line in the card, after title/student.
        if (TextUtils.isEmpty(university)) {
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                if (sameNormalized(line, title)) continue;
                if (looksLikeUniversity(line)) {
                    university = stripLabel(line, "الجامعة", "اسم الجامعة", "university");
                    if (TextUtils.isEmpty(university)) university = line;
                    break;
                }
            }
        }

        if (TextUtils.isEmpty(student)) {
            boolean passedTitle = false;
            for (String line : lines) {
                if (!passedTitle) {
                    if (sameNormalized(stripLabel(line, "المشروع", "project"), title)
                            || sameNormalized(line, title)) {
                        passedTitle = true;
                    }
                    continue;
                }
                if (sameNormalized(line, university) || looksLikeUniversity(line)
                        || isGeneric(line) || looksLikeStatus(line)) continue;
                String possible = stripLabel(line, "الطالب", "اسم الطالب", "student", "student name");
                if (!TextUtils.isEmpty(possible) && !sameNormalized(possible, title)) {
                    student = possible;
                    break;
                }
            }
        }

        title = safe(title);
        student = safe(student);
        university = safe(university);

        boolean hasIdentityContext = !student.isEmpty() && !university.isEmpty();
        boolean hasUniversityContext = !university.isEmpty() && lines.size() >= 3;
        if (title.length() < 3 || (!hasIdentityContext && !hasUniversityContext)) return null;
        if (isGeneric(title) || looksLikeStatus(title)) return null;

        return new ProjectCandidate(title, student, university, lines);
    }

    private static List<String> cleanLines(List<String> raw) {
        List<String> out = new ArrayList<>();
        Set<String> seenAdjacent = new HashSet<>();
        for (String s : raw) {
            if (s == null) continue;
            String[] parts = s.split("[\\n\\r•|]+");
            for (String part : parts) {
                String v = part.replaceAll("\\s+", " ").trim();
                if (v.isEmpty()) continue;
                String n = normalize(v);
                if (n.isEmpty()) continue;
                if (seenAdjacent.add(n)) out.add(v);
            }
        }
        return out;
    }

    private static void collectTexts(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null && text.length() > 0) out.add(text.toString());
        if (desc != null && desc.length() > 0 && (text == null || !desc.toString().equals(text.toString()))) {
            out.add(desc.toString());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectTexts(node.getChild(i), out);
        }
    }

    private static void collectScrollableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isScrollable()
                || node.getActions() == (node.getActions() | AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectScrollableNodes(node.getChild(i), out);
        }
    }

    private static void collectClickableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isClickable() && node.getChildCount() > 0) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            collectClickableNodes(node.getChild(i), out);
        }
    }

    private static AccessibilityNodeInfo findBestScrollable(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectScrollableNodes(root, nodes);
        AccessibilityNodeInfo best = null;
        int score = -1;
        for (AccessibilityNodeInfo node : nodes) {
            int s = node.getChildCount();
            if (node.isScrollable()) s += 10;
            if (s > score) {
                score = s;
                best = node;
            }
        }
        return best;
    }

    private boolean clickProjectsNavigation(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all);
        for (AccessibilityNodeInfo node : all) {
            String t = node.getText() == null ? "" : node.getText().toString().trim();
            String d = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
            if (isProjectsLabel(t) || isProjectsLabel(d)) {
                AccessibilityNodeInfo clickable = node;
                for (int i = 0; i < 5 && clickable != null; i++) {
                    if (clickable.isClickable()) {
                        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    clickable = clickable.getParent();
                }
            }
        }
        return false;
    }

    private static void flatten(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) flatten(node.getChild(i), out);
    }

    private static boolean isProjectsLabel(String text) {
        String n = normalize(text);
        return n.equals("المشاريع") || n.equals("المشروعات") || n.equals("projects")
                || n.equals("قائمة المشاريع") || n.equals("project list");
    }

    private void saveRawSnapshot(AccessibilityNodeInfo root) {
        List<String> all = new ArrayList<>();
        collectTexts(root, all);
        List<String> cleaned = cleanLines(all);
        StringBuilder sb = new StringBuilder();
        for (String line : cleaned) {
            if (sb.length() > 12000) break;
            sb.append(line).append("\n");
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("last_snapshot", sb.toString())
                .putInt("visible_text_count", cleaned.size())
                .apply();
    }

    private static String extractLabeled(List<String> lines, String... labels) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String normalizedLine = normalize(line);
            for (String label : labels) {
                String nl = normalize(label);
                if (normalizedLine.equals(nl)) {
                    if (i + 1 < lines.size()) return safe(lines.get(i + 1));
                }
                if (normalizedLine.startsWith(nl)) {
                    String stripped = stripLabel(line, label);
                    if (!stripped.equals(line) && !stripped.isEmpty()) return stripped;
                }
            }
        }
        return "";
    }

    private static String stripLabel(String value, String... labels) {
        if (value == null) return "";
        String v = value.trim();
        for (String label : labels) {
            if (v.toLowerCase(Locale.ROOT).startsWith(label.toLowerCase(Locale.ROOT))) {
                v = v.substring(Math.min(label.length(), v.length())).trim();
                v = v.replaceFirst("^[\\s:：\\-–—]+", "").trim();
                return v;
            }
        }
        return v;
    }

    private static boolean looksLikeUniversity(String s) {
        String n = normalize(s);
        return n.contains("جامعة") || n.contains("university") || n.contains("كلية");
    }

    private static boolean looksLikeStudentLabel(String s) {
        String n = normalize(s);
        return n.startsWith("الطالب") || n.startsWith("اسم الطالب")
                || n.startsWith("student");
    }

    private static boolean looksLikeStatus(String s) {
        String n = normalize(s);
        return n.matches(".*\\b\\d{1,3}%.*")
                || n.contains("part 1") || n.contains("part 2")
                || n.contains("الجزء الأول") || n.contains("الجزء الثاني")
                || n.equals("جديد") || n.equals("مكتمل") || n.equals("قيد التنفيذ");
    }

    private static boolean isGeneric(String s) {
        String n = normalize(s);
        return GENERIC.contains(n) || n.matches("^\\d+$");
    }

    private static boolean sameNormalized(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String safe(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC)
                .replace("ـ", "")
                .replaceAll("[ًٌٍَُِّْ]", "")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        return n;
    }

    static class ProjectCandidate {
        final String title;
        final String student;
        final String university;
        final List<String> rawLines;

        ProjectCandidate(String title, String student, String university, List<String> rawLines) {
            this.title = safe(title);
            this.student = safe(student);
            this.university = safe(university);
            this.rawLines = rawLines == null ? new ArrayList<>() : new ArrayList<>(rawLines);
        }

        boolean isValid() {
            return title.length() >= 3 && (!student.isEmpty() || !university.isEmpty());
        }

        String fingerprint() {
            return normalize(title) + "|" + normalize(student) + "|" + normalize(university);
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("title", title);
                o.put("student", student);
                o.put("university", university);
                JSONArray raw = new JSONArray();
                for (String line : rawLines) raw.put(line);
                o.put("raw", raw);
            } catch (JSONException ignored) {
            }
            return o;
        }

        static ProjectCandidate fromJson(JSONObject o) {
            List<String> raw = new ArrayList<>();
            JSONArray arr = o.optJSONArray("raw");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) raw.add(arr.optString(i, ""));
            }
            return new ProjectCandidate(
                    o.optString("title", ""),
                    o.optString("student", ""),
                    o.optString("university", ""),
                    raw
            );
        }
    }
}
