package com.mographikod.projectdesk.recovery;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
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
import java.util.Set;

public class RecoveryAccessibilityService extends AccessibilityService {
    public static final String PREFS = "recovery_state";
    public static final String TARGET_PACKAGE = "com.mographikod.projectdesk";

    private static final int MAX_SCROLL_STEPS = 40;
    private static final int REQUIRED_SAME_VIEWPORTS_AT_END = 4;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanner = this::scanCurrentWindow;

    private String localSession = "";
    private int navigationAttempts = 0;
    private int nullRootRounds = 0;
    private int scrollSteps = 0;
    private int sameViewportRounds = 0;
    private String lastViewportSignature = "";

    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
            "projectdesk", "projectdesk القديم", "الرئيسية", "الصفحة الرئيسية",
            "المشاريع", "المشروعات", "projects", "project", "المهام", "tasks",
            "الإعدادات", "اعدادات", "settings", "بحث", "search", "إضافة",
            "إضافة مشروع", "اضافة مشروع", "مشروع جديد", "new project",
            "الملفات", "files", "التقارير", "reports", "الطلاب", "students",
            "المشرفون", "supervisors", "تسجيل الخروج", "logout", "فتح", "تعديل",
            "حذف", "المزيد", "عرض", "التالي", "السابق"
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
            scheduleScan(350);
        }
    }

    @Override
    public void onInterrupt() {}

    private void scheduleScan(long delayMs) {
        handler.removeCallbacks(scanner);
        handler.postDelayed(scanner, delayMs);
    }

    private void resetForSession(String session) {
        localSession = session;
        navigationAttempts = 0;
        nullRootRounds = 0;
        scrollSteps = 0;
        sameViewportRounds = 0;
        lastViewportSignature = "";
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
            if (nullRootRounds >= 18) {
                finishRecovery(false,
                        "تعذر قراءة نافذة ProjectDesk القديم. أعد تفعيل خدمة إمكانية الوصول ثم حاول مرة أخرى.");
            } else {
                scheduleScan(650);
            }
            return;
        }
        nullRootRounds = 0;

        String packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (!TARGET_PACKAGE.equals(packageName)) {
            setStatus("بانتظار ProjectDesk القديم…");
            scheduleScan(650);
            return;
        }

        saveRawSnapshot(root);

        String signature = viewportSignature(root);
        if (!signature.isEmpty() && signature.equals(lastViewportSignature)) {
            sameViewportRounds++;
        } else {
            sameViewportRounds = 0;
            lastViewportSignature = signature;
        }

        LinkedHashMap<String, ProjectCandidate> existing = readProjects();
        int before = existing.size();

        List<ProjectCandidate> visible = extractProjectCandidates(root);
        for (ProjectCandidate p : visible) {
            if (p.isValid()) existing.put(p.fingerprint(), p);
        }
        writeProjects(existing);

        int after = existing.size();
        int added = after - before;

        if (after == 0 && navigationAttempts < 6) {
            if (clickProjectsNavigation(root)) {
                navigationAttempts++;
                sameViewportRounds = 0;
                lastViewportSignature = "";
                setStatus("تم فتح قسم المشاريع. جارٍ قراءة جميع البطاقات…");
                scheduleScan(1000);
                return;
            }
            navigationAttempts++;
        }

        setStatus(
                "تم اكتشاف " + after + " مشروع"
                        + (added > 0 ? " (+" + added + ")" : "")
                        + ". جارٍ تمرير القائمة… خطوة "
                        + Math.min(scrollSteps + 1, MAX_SCROLL_STEPS)
                        + " من " + MAX_SCROLL_STEPS + ".");

        boolean reachedStableEnd = after > 0
                && sameViewportRounds >= REQUIRED_SAME_VIEWPORTS_AT_END;

        if (reachedStableEnd || scrollSteps >= MAX_SCROLL_STEPS) {
            finishRecovery(true,
                    "اكتمل مسح القائمة: تم اكتشاف " + after
                            + " مشروع بعد " + scrollSteps + " خطوة تمرير.");
            return;
        }

        boolean requestedScroll = tryNodeScroll(root);
        if (requestedScroll) {
            scrollSteps++;
            scheduleScan(900);
            return;
        }

        if (dispatchUpSwipe()) {
            scrollSteps++;
            scheduleScan(1050);
            return;
        }

        // Some Flutter screens temporarily report no scroll action while rebuilding.
        // Do not stop after one failure. Re-scan several times and only finish when
        // the visible viewport itself remains identical.
        sameViewportRounds++;
        if (after > 0 && sameViewportRounds >= REQUIRED_SAME_VIEWPORTS_AT_END) {
            finishRecovery(true,
                    "اكتمل مسح القائمة: تم اكتشاف " + after + " مشروع.");
        } else if (after == 0 && sameViewportRounds >= 7) {
            finishRecovery(false,
                    "لم يتم العثور على مشاريع. لن يتم إنشاء نسخة احتياطية فارغة.");
        } else {
            scheduleScan(850);
        }
    }

    private boolean tryNodeScroll(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectScrollableNodes(root, nodes);
        if (nodes.isEmpty()) return false;

        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo node : nodes) {
            int score = node.getChildCount() * 3;
            if (node.isScrollable()) score += 100;
            String className = node.getClassName() == null ? "" : node.getClassName().toString();
            if (className.contains("RecyclerView")
                    || className.contains("ListView")
                    || className.contains("ScrollView")) {
                score += 200;
            }
            if (score > bestScore) {
                bestScore = score;
                best = node;
            }
        }

        if (best != null
                && best.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            return true;
        }

        for (AccessibilityNodeInfo node : nodes) {
            if (node == best) continue;
            if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                return true;
            }
        }
        return false;
    }

    private boolean dispatchUpSwipe() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels * 0.50f;
        float startY = dm.heightPixels * 0.78f;
        float endY = dm.heightPixels * 0.28f;

        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 500))
                .build();

        return dispatchGesture(gesture, null, null);
    }

    private void finishRecovery(boolean success, String message) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean("active", false)
                .putBoolean("complete", true)
                .putBoolean("scan_success", success)
                .putString("status", message)
                .putInt("scroll_steps", scrollSteps)
                .apply();

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void setStatus(String message) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString("status", message)
                .putInt("scroll_steps", scrollSteps)
                .apply();
    }

    private LinkedHashMap<String, ProjectCandidate> readProjects() {
        LinkedHashMap<String, ProjectCandidate> map = new LinkedHashMap<>();
        try {
            JSONArray arr = new JSONArray(
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                            .getString("projects_json", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                ProjectCandidate p = ProjectCandidate.fromJson(arr.getJSONObject(i));
                if (p.isValid()) map.put(p.fingerprint(), p);
            }
        } catch (Exception ignored) {}
        return map;
    }

    private void writeProjects(LinkedHashMap<String, ProjectCandidate> projects) {
        JSONArray arr = new JSONArray();
        for (ProjectCandidate p : projects.values()) arr.put(p.toJson());
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString("projects_json", arr.toString())
                .putInt("project_count", projects.size())
                .apply();
    }

    private List<ProjectCandidate> extractProjectCandidates(AccessibilityNodeInfo root) {
        List<ProjectCandidate> out = new ArrayList<>();

        // 1) Parse direct children of every visible scroll container.
        List<AccessibilityNodeInfo> scrollables = new ArrayList<>();
        collectScrollableNodes(root, scrollables);
        for (AccessibilityNodeInfo list : scrollables) {
            for (int i = 0; i < list.getChildCount(); i++) {
                AccessibilityNodeInfo child = list.getChild(i);
                if (child == null) continue;
                List<String> lines = new ArrayList<>();
                collectTexts(child, lines);
                ProjectCandidate p = parseCandidate(lines);
                if (p != null && p.isValid()) out.add(p);
            }
        }

        // 2) Flutter often exposes each card as a clickable semantic node.
        List<AccessibilityNodeInfo> clickables = new ArrayList<>();
        collectClickableNodes(root, clickables);
        for (AccessibilityNodeInfo node : clickables) {
            List<String> lines = new ArrayList<>();
            collectTexts(node, lines);
            ProjectCandidate p = parseCandidate(lines);
            if (p != null && p.isValid()) out.add(p);
        }

        // 3) Always parse the complete visible semantic text too. In v1.3 this
        // fallback only ran when steps 1/2 found nothing, which is why a page that
        // exposed three cards structurally could hide the remaining visible cards.
        List<String> all = new ArrayList<>();
        collectTexts(root, all);
        List<String> cleaned = cleanLines(all);

        for (int i = 0; i < cleaned.size(); i++) {
            String line = cleaned.get(i);

            if (looksLikeUniversity(line)) {
                int start = Math.max(0, i - 4);
                int end = Math.min(cleaned.size(), i + 2);
                ProjectCandidate p = parseCandidate(
                        new ArrayList<>(cleaned.subList(start, end)));
                if (p != null && p.isValid()) out.add(p);
            }

            String n = normalize(line);
            if (n.startsWith("عنوان المشروع")
                    || n.startsWith("اسم المشروع")
                    || n.startsWith("project title")
                    || n.startsWith("project name")) {
                int end = Math.min(cleaned.size(), i + 6);
                ProjectCandidate p = parseCandidate(
                        new ArrayList<>(cleaned.subList(i, end)));
                if (p != null && p.isValid()) out.add(p);
            }
        }

        // 4) Simple legacy cards may contain only title + student + university
        // without labels. Parse short windows, while the validator removes menus.
        for (int i = 0; i + 1 < cleaned.size(); i++) {
            int end = Math.min(cleaned.size(), i + 5);
            ProjectCandidate p = parseCandidate(
                    new ArrayList<>(cleaned.subList(i, end)));
            if (p != null && p.isValid()) out.add(p);
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

        if (TextUtils.isEmpty(title)) {
            for (String line : lines) {
                if (isGeneric(line)
                        || looksLikeStudentLabel(line)
                        || looksLikeStatus(line)) {
                    continue;
                }
                String c = stripLabel(line, "المشروع", "project");
                if (!TextUtils.isEmpty(c)) {
                    title = c;
                    break;
                }
            }
        }

        String student = extractLabeled(lines,
                "اسم الطالب", "الطالب", "student name", "student");
        String university = extractLabeled(lines,
                "اسم الجامعة", "الجامعة", "university");

        if (TextUtils.isEmpty(university)) {
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                if (sameNormalized(line, title)) continue;
                if (looksLikeUniversity(line)) {
                    String u = stripLabel(
                            line, "الجامعة", "اسم الجامعة", "university");
                    university = TextUtils.isEmpty(u) ? line : u;
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

                if (sameNormalized(line, university)
                        || looksLikeUniversity(line)
                        || isGeneric(line)
                        || looksLikeStatus(line)
                        || looksLikeStudentLabel(line) && safe(stripLabel(
                                line, "الطالب", "اسم الطالب", "student",
                                "student name")).isEmpty()) {
                    continue;
                }

                String possible = stripLabel(
                        line, "الطالب", "اسم الطالب", "student", "student name");
                if (isLikelyStudentName(possible)
                        && !sameNormalized(possible, title)) {
                    student = possible;
                    break;
                }
            }
        }

        title = safe(title);
        student = safe(student);
        university = safe(university);

        if (title.length() < 3
                || title.length() > 240
                || isGeneric(title)
                || looksLikeStatus(title)) {
            return null;
        }

        if (!isLikelyStudentName(student)) return null;

        return new ProjectCandidate(title, student, university, lines);
    }

    private static boolean isLikelyStudentName(String value) {
        String s = safe(value);
        if (s.length() < 2 || s.length() > 120) return false;
        if (isGeneric(s) || looksLikeStatus(s) || looksLikeUniversity(s)) return false;
        String n = normalize(s);
        if (n.contains("عنوان المشروع")
                || n.contains("اسم المشروع")
                || n.contains("project title")) {
            return false;
        }
        return true;
    }

    private static String viewportSignature(AccessibilityNodeInfo root) {
        List<String> raw = new ArrayList<>();
        collectTexts(root, raw);
        List<String> lines = cleanLines(raw);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(normalize(line)).append('|');
            if (sb.length() > 10000) break;
        }
        if (sb.length() == 0) return "";
        return Integer.toHexString(sb.toString().hashCode())
                + ":" + lines.size()
                + ":" + normalize(lines.get(0))
                + ":" + normalize(lines.get(lines.size() - 1));
    }

    private static List<String> cleanLines(List<String> raw) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String s : raw) {
            if (s == null) continue;
            String[] parts = s.split("[\\n\\r•|]+");
            for (String part : parts) {
                String v = part.replaceAll("\\s+", " ").trim();
                if (v.isEmpty()) continue;
                String n = normalize(v);
                if (n.isEmpty()) continue;
                if (seen.add(n)) out.add(v);
            }
        }
        return out;
    }

    private static void collectTexts(
            AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();

        if (text != null && text.length() > 0) out.add(text.toString());
        if (desc != null && desc.length() > 0
                && (text == null || !desc.toString().equals(text.toString()))) {
            out.add(desc.toString());
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectTexts(node.getChild(i), out);
        }
    }

    private static void collectScrollableNodes(
            AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;

        if (node.isScrollable()
                || (node.getActions()
                & AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != 0) {
            out.add(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectScrollableNodes(node.getChild(i), out);
        }
    }

    private static void collectClickableNodes(
            AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;

        if (node.isClickable() && node.getChildCount() > 0) out.add(node);

        for (int i = 0; i < node.getChildCount(); i++) {
            collectClickableNodes(node.getChild(i), out);
        }
    }

    private boolean clickProjectsNavigation(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all);

        for (AccessibilityNodeInfo node : all) {
            String t = node.getText() == null
                    ? "" : node.getText().toString().trim();
            String d = node.getContentDescription() == null
                    ? "" : node.getContentDescription().toString().trim();

            if (isProjectsLabel(t) || isProjectsLabel(d)) {
                AccessibilityNodeInfo current = node;
                for (int i = 0; i < 6 && current != null; i++) {
                    if (current.isClickable()) {
                        return current.performAction(
                                AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    current = current.getParent();
                }
            }
        }
        return false;
    }

    private static void flatten(
            AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            flatten(node.getChild(i), out);
        }
    }

    private static boolean isProjectsLabel(String text) {
        String n = normalize(text);
        return n.equals("المشاريع")
                || n.equals("المشروعات")
                || n.equals("projects")
                || n.equals("قائمة المشاريع")
                || n.equals("project list");
    }

    private void saveRawSnapshot(AccessibilityNodeInfo root) {
        List<String> all = new ArrayList<>();
        collectTexts(root, all);
        List<String> cleaned = cleanLines(all);

        StringBuilder sb = new StringBuilder();
        for (String line : cleaned) {
            if (sb.length() > 15000) break;
            sb.append(line).append("\n");
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString("last_snapshot", sb.toString())
                .putInt("visible_text_count", cleaned.size())
                .apply();
    }

    private static String extractLabeled(
            List<String> lines, String... labels) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String normalizedLine = normalize(line);

            for (String label : labels) {
                String nl = normalize(label);

                if (normalizedLine.equals(nl)
                        && i + 1 < lines.size()) {
                    return safe(lines.get(i + 1));
                }

                if (normalizedLine.startsWith(nl)) {
                    String stripped = stripLabel(line, label);
                    if (!stripped.equals(line) && !stripped.isEmpty()) {
                        return stripped;
                    }
                }
            }
        }
        return "";
    }

    private static String stripLabel(String value, String... labels) {
        if (value == null) return "";

        String v = value.trim();
        for (String label : labels) {
            if (v.toLowerCase(Locale.ROOT)
                    .startsWith(label.toLowerCase(Locale.ROOT))) {
                v = v.substring(
                        Math.min(label.length(), v.length())).trim();
                v = v.replaceFirst("^[\\s:：\\-–—]+", "").trim();
                return v;
            }
        }
        return v;
    }

    private static boolean looksLikeUniversity(String s) {
        String n = normalize(s);
        return n.startsWith("الجامعة")
                || n.startsWith("جامعة")
                || n.startsWith("اسم الجامعة")
                || n.contains(" university")
                || n.startsWith("university")
                || n.startsWith("كلية")
                || n.contains(" جامعة ");
    }

    private static boolean looksLikeStudentLabel(String s) {
        String n = normalize(s);
        return n.startsWith("الطالب")
                || n.startsWith("اسم الطالب")
                || n.startsWith("student");
    }

    private static boolean looksLikeStatus(String s) {
        String n = normalize(s);
        return n.matches(".*\\b\\d{1,3}%.*")
                || n.contains("part 1")
                || n.contains("part 2")
                || n.contains("الجزء الأول")
                || n.contains("الجزء الثاني")
                || n.equals("جديد")
                || n.equals("مكتمل")
                || n.equals("قيد التنفيذ");
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

    public static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC)
                .replace("ـ", "")
                .replaceAll("[ًٌٍَُِّْ]", "")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    static class ProjectCandidate {
        final String title;
        final String student;
        final String university;
        final List<String> rawLines;

        ProjectCandidate(
                String title,
                String student,
                String university,
                List<String> rawLines) {
            this.title = safe(title);
            this.student = safe(student);
            this.university = safe(university);
            this.rawLines = rawLines == null
                    ? new ArrayList<>() : new ArrayList<>(rawLines);
        }

        boolean isValid() {
            return title.length() >= 3 && isLikelyStudentName(student);
        }

        String fingerprint() {
            return normalize(title)
                    + "|" + normalize(student);
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
            } catch (JSONException ignored) {}
            return o;
        }

        static ProjectCandidate fromJson(JSONObject o) {
            List<String> raw = new ArrayList<>();
            JSONArray arr = o.optJSONArray("raw");

            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    raw.add(arr.optString(i, ""));
                }
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
