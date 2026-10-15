package dev.mateuszsury.androidagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The screen-control tools (Accessibility) exposed to Claude Code and Codex over MCP. Tools that work without
 * Accessibility live in {@link PhoneTools}, {@link IntentTools} and {@link DeviceTools}.
 */
final class Tools {
    /** Elements remembered per read_screen (ids stay valid across its pages until the next read_screen). */
    private static final int MAX_NODES = 1500;
    private static final int PAGE_SIZE = 350;
    private static final int SCREENSHOT_WIDTH = 720;

    private static final Set<String> NAMES = new HashSet<>(Arrays.asList(
            "read_screen", "tap", "long_press", "find_and_tap", "type_text", "ime_action", "scroll", "swipe", "press",
            "screenshot"));

    private final BuddyService svc;
    private final Object lock = new Object();
    private final List<AccessibilityNodeInfo> lastNodes = new ArrayList<>();
    void clearNodes() { synchronized(lock) { for(AccessibilityNodeInfo n:lastNodes)n.recycle();lastNodes.clear(); } }

    Tools(BuddyService svc) {
        this.svc = svc;
    }

    static boolean handles(String name) {
        return NAMES.contains(name);
    }

    // ---------------------------------------------------------------- schema

    static JSONArray list() throws JSONException {
        JSONArray t = new JSONArray();
        t.put(tool("read_screen",
                "Read what is on the phone screen as a list of UI elements with ids, text, resource ids (res=) and tap "
                        + "coordinates. Call this first and after every action to see the result. Element ids are only "
                        + "valid until the next read_screen. Long screens are paged: the reply says when to ask for the next page.",
                props().put("page", intProp("0-based page of elements (default 0)"))
                        .put("limit", intProp("Elements per page, default 350, max 1000"))));
        t.put(tool("tap",
                "Tap an element (by id from the last read_screen) or a screen coordinate in pixels.",
                props().put("element", intProp("Element id from read_screen"))
                        .put("x", intProp("X in screen pixels")).put("y", intProp("Y in screen pixels"))));
        t.put(tool("long_press",
                "Long-press an element or coordinate.",
                props().put("element", intProp("Element id from read_screen"))
                        .put("x", intProp("X in screen pixels")).put("y", intProp("Y in screen pixels"))));
        t.put(tool("find_and_tap",
                "Find a visible element by its text / content description (case-insensitive substring) or by its "
                        + "resource id (the res= value from read_screen) and tap it, without a read_screen first. Clicks "
                        + "the nearest clickable ancestor; falls back to a tap gesture. Exact text matches are preferred.",
                props().put("text", strProp("Text or content description to find"))
                        .put("resource_id", strProp("View resource id, e.g. send_button or com.app:id/send_button"))
                        .put("index", intProp("Which match to tap when several match (0-based, default 0)"))
                        .put("exact", boolProp("Only whole-text matches"))
                        .put("long_press", boolProp("Long-press instead of tap"))));
        t.put(tool("type_text",
                "Type into the focused text field (or the given element). Replaces its content unless append is true. "
                        + "Set submit to press the keyboard's enter/send action afterwards. Set paste to insert via the "
                        + "clipboard (replaces the user's clipboard) for fields that ignore direct text input; this also "
                        + "happens automatically when a field rejects direct input.",
                props().put("text", strProp("Text to type"))
                        .put("element", intProp("Optional element id of the text field"))
                        .put("append", boolProp("Append instead of replacing"))
                        .put("submit", boolProp("Press the IME enter/send action after typing"))
                        .put("paste", boolProp("Insert through the clipboard instead of setting the text directly")),
                "text"));
        t.put(tool("ime_action",
                "Press the keyboard's action key (enter / send / search / go / done) for the focused text field or the "
                        + "given element.",
                props().put("element", intProp("Optional element id of the text field"))));
        t.put(tool("scroll",
                "Scroll the screen (or a scrollable element) in a direction. 'down' reveals content further down. With "
                        + "an element, scrolls that list via its accessibility scroll action (more reliable than a swipe).",
                props().put("direction", enumProp("up", "down", "left", "right"))
                        .put("element", intProp("Optional element id (a scrollable element or anything inside one)")),
                "direction"));
        t.put(tool("swipe",
                "Swipe from one point to another, in screen pixels.",
                props().put("x1", intProp("")).put("y1", intProp("")).put("x2", intProp("")).put("y2", intProp(""))
                        .put("duration_ms", intProp("Default 300")),
                "x1", "y1", "x2", "y2"));
        t.put(tool("press",
                "Press a system button or run a system action: back, home, recents, notifications, quick_settings, "
                        + "lock_screen, take_screenshot (saved to the gallery) or power_dialog.",
                props().put("button", enumProp("back", "home", "recents", "notifications", "quick_settings",
                        "lock_screen", "take_screenshot", "power_dialog")),
                "button"));
        t.put(tool("screenshot",
                "Take a screenshot (JPEG). Use only when read_screen is not enough (images, games, canvas UIs). "
                        + "The reply says how to convert image coordinates to screen pixels.",
                props()));
        return t;
    }

    private static JSONObject tool(String name, String desc, JSONObject properties, String... required) throws JSONException {
        return ToolProviders.tool(name, desc, properties, required);
    }

    private static JSONObject props() {
        return new JSONObject();
    }

    private static JSONObject intProp(String d) throws JSONException {
        return new JSONObject().put("type", "integer").put("description", d);
    }

    private static JSONObject strProp(String d) throws JSONException {
        return new JSONObject().put("type", "string").put("description", d);
    }

    private static JSONObject boolProp(String d) throws JSONException {
        return new JSONObject().put("type", "boolean").put("description", d);
    }

    private static JSONObject enumProp(String... values) throws JSONException {
        JSONArray a = new JSONArray();
        for (String v : values) a.put(v);
        return new JSONObject().put("type", "string").put("enum", a);
    }

    // ------------------------------------------------------------ dispatch

    JSONObject call(String name, JSONObject a) throws Exception {
        String revoked = svc.controlRevoked();
        if (revoked != null) return text(revoked);
        svc.onToolActivity(name);
        switch (name) {
            case "read_screen": return text(readScreen(a.optInt("page", 0), a.optInt("limit", PAGE_SIZE)));
            case "tap": return text(tap(a, false));
            case "long_press": return text(tap(a, true));
            case "find_and_tap": return text(findAndTap(a));
            case "type_text": return text(typeText(a));
            case "ime_action": return text(imeAction(a));
            case "scroll": return text(scroll(a));
            case "swipe": return text(swipe(a));
            case "press": return text(press(a.getString("button")));
            case "screenshot": return screenshot();
            default:
                throw new IllegalArgumentException("Unknown tool " + name);
        }
    }

    private static JSONObject text(String s) throws JSONException {
        return ToolProviders.text(s);
    }

    // ---------------------------------------------------------- read_screen

    private String readScreen(int page, int limit) {
        limit = Math.max(20, Math.min(1000, limit));
        page = Math.max(0, page);
        int from = page * limit, to = from + limit;
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        StringBuilder sb = new StringBuilder();
        sb.append("Screen ").append(dm.widthPixels).append('x').append(dm.heightPixels).append(" px\n");
        synchronized (lock) {
            for (AccessibilityNodeInfo n : lastNodes) n.recycle();
            lastNodes.clear();
            List<AccessibilityWindowInfo> windows = svc.getWindows();
            List<String> lines = new ArrayList<>();
            boolean keyboard = false;
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    keyboard = true;
                    continue;
                }
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence title = w.getTitle();
                String header = "\n## " + windowType(w.getType()) + " window"
                        + (root.getPackageName() != null ? " [" + root.getPackageName() + "]" : "")
                        + (title != null ? " \"" + title + "\"" : "") + (w.isActive() ? " (active)" : "");
                int first = lastNodes.size();
                lines.clear();
                walk(root, lines, 0);
                int last = lastNodes.size();
                sb.append(header).append('\n');
                int shown = 0;
                for (int i = 0; i < lines.size(); i++) {
                    int id = first + i;
                    if (id >= from && id < to) {
                        sb.append(lines.get(i));
                        shown++;
                    }
                }
                if (shown == 0 && last > first)
                    sb.append("(elements ").append(first).append('-').append(last - 1).append(" are on another page)\n");
            }
            if (keyboard) sb.append("\n(The on-screen keyboard is open.)\n");
            int total = lastNodes.size();
            if (total > limit) {
                int pages = (total + limit - 1) / limit;
                sb.append("\n(Page ").append(page).append(" of ").append(pages - 1).append(": elements ")
                        .append(Math.min(from, total)).append('-').append(Math.min(to, total) - 1).append(" of ").append(total).append('.');
                if (to < total) sb.append(" Call read_screen with page=").append(page + 1).append(" for more.");
                sb.append(")\n");
            }
            if (total >= MAX_NODES) sb.append("\n(Stopped at ").append(MAX_NODES).append(" elements; scroll for more.)\n");
        }
        return sb.toString();
    }

    private void walk(AccessibilityNodeInfo n, List<String> lines, int depth) {
        if (n == null) return;
        try {
        if (lastNodes.size() >= MAX_NODES || !n.isVisibleToUser()) return;
        CharSequence text = n.getText();
        CharSequence desc = n.getContentDescription();
        boolean interesting = !empty(text) || !empty(desc) || n.isClickable() || n.isEditable()
                || n.isScrollable() || n.isCheckable() || n.isLongClickable();
        if (interesting) {
            int id = lastNodes.size();
            lastNodes.add(AccessibilityNodeInfo.obtain(n));
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            StringBuilder sb = new StringBuilder();
            sb.append(indent(depth)).append('[').append(id).append("] ").append(shortClass(n.getClassName()));
            if (!empty(text)) sb.append(" \"").append(clip(text)).append('"');
            if (!empty(desc) && (empty(text) || !desc.toString().equals(text.toString())))
                sb.append(" desc=\"").append(clip(desc)).append('"');
            String res = shortId(n.getViewIdResourceName());
            if (res != null) sb.append(" res=").append(res);
            if (n.isEditable() && n.isShowingHintText()) sb.append(" (hint)");
            sb.append(" @").append(r.centerX()).append(',').append(r.centerY());
            StringBuilder f = new StringBuilder();
            if (n.isClickable()) f.append(" click");
            if (n.isLongClickable()) f.append(" longclick");
            if (n.isEditable()) f.append(" edit");
            if (n.isScrollable()) f.append(" scroll");
            if (n.isCheckable()) f.append(n.isChecked() ? " checked" : " unchecked");
            if (n.isFocused()) f.append(" focused");
            if (n.isSelected()) f.append(" selected");
            if (!n.isEnabled()) f.append(" disabled");
            if (f.length() > 0) sb.append(" {").append(f.toString().trim()).append('}');
            sb.append('\n');
            lines.add(sb.toString());
        }
        int count = n.getChildCount();
        for (int i = 0; i < count && lastNodes.size() < MAX_NODES; i++) {
            walk(n.getChild(i), lines, interesting ? depth + 1 : depth);
        }
        } finally { n.recycle(); }
    }

    /** "com.example:id/send_button" -> "send_button" (null when the view has no id). */
    static String shortId(String full) {
        if (full == null || full.isEmpty()) return null;
        int slash = full.indexOf(":id/");
        return slash >= 0 ? full.substring(slash + 4) : full;
    }

    private static String windowType(int t) {
        switch (t) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "App";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "System";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "Divider";
            default: return "Other";
        }
    }

    private static String indent(int d) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(d, 8); i++) s.append("  ");
        return s.toString();
    }

    private static boolean empty(CharSequence c) {
        return c == null || c.toString().trim().isEmpty();
    }

    private static String clip(CharSequence c) {
        String s = c.toString().replace('\n', ' ').replace("\"", "'");
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }

    private static String shortClass(CharSequence c) {
        if (c == null) return "View";
        String s = c.toString();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private AccessibilityNodeInfo node(JSONObject a) {
        if (!a.has("element")) return null;
        int id = a.optInt("element", -1);
        synchronized (lock) {
            if (id < 0 || id >= lastNodes.size())
                throw new IllegalArgumentException("Unknown element " + id + ". Call read_screen again.");
            AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(lastNodes.get(id));
            n.refresh();
            return n;
        }
    }

    /** Windows whose content the agent may act on (not our overlay, not the keyboard). */
    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityWindowInfo w : svc.getWindows()) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                    || w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root != null) out.add(root);
        }
        return out;
    }

    // ------------------------------------------------------------- actions

    private String tap(JSONObject a, boolean longPress) throws Exception {
        AccessibilityNodeInfo n = node(a);
        int x, y;
        if (n != null) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            x = r.centerX();
            y = r.centerY();
            if (!n.isVisibleToUser() || r.isEmpty()) {
                // Off-screen or stale: fall back to an accessibility click.
                AccessibilityNodeInfo target = clickableAncestor(n, longPress);
                boolean ok = target != null && target.performAction(longPress
                        ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK);
                return ok ? "Clicked element via accessibility action." : "Element is not visible; scroll to it first.";
            }
        } else {
            if (!a.has("x") || !a.has("y")) throw new IllegalArgumentException("Give element or x and y.");
            x = a.getInt("x");
            y = a.getInt("y");
        }
        Path p = new Path();
        p.moveTo(x, y);
        boolean ok = gesture(p, longPress ? 700 : 60);
        return (ok ? (longPress ? "Long-pressed" : "Tapped") : "Gesture was cancelled at")
                + " (" + x + ", " + y + "). Call read_screen to see the result.";
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n, boolean longPress) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 8; i++) {
            if (longPress ? cur.isLongClickable() : cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private String findAndTap(JSONObject a) throws Exception {
        String text = a.optString("text", "").trim();
        String rid = a.optString("resource_id", "").trim();
        if (text.isEmpty() == rid.isEmpty()) throw new IllegalArgumentException("Give exactly one of text or resource_id.");
        boolean exact = a.optBoolean("exact"), longPress = a.optBoolean("long_press");
        List<AccessibilityNodeInfo> whole = new ArrayList<>(), partial = new ArrayList<>();
        for (AccessibilityNodeInfo root : roots()) {
            List<AccessibilityNodeInfo> found = new ArrayList<>();
            if (!text.isEmpty()) {
                found.addAll(root.findAccessibilityNodeInfosByText(text));
            } else if (rid.contains(":id/")) {
                found.addAll(root.findAccessibilityNodeInfosByViewId(rid));
            } else {
                if (root.getPackageName() != null)
                    found.addAll(root.findAccessibilityNodeInfosByViewId(root.getPackageName() + ":id/" + rid));
                found.addAll(root.findAccessibilityNodeInfosByViewId("android:id/" + rid));
            }
            for (AccessibilityNodeInfo n : found) {
                if (n == null || !n.isVisibleToUser()) continue;
                boolean isWhole = text.isEmpty() || same(n.getText(), text) || same(n.getContentDescription(), text);
                if (isWhole) whole.add(n);
                else if (!exact) partial.add(n);
            }
        }
        List<AccessibilityNodeInfo> matches = new ArrayList<>(whole);
        matches.addAll(partial);
        String what = text.isEmpty() ? "resource id " + rid : "\"" + text + "\"";
        if (matches.isEmpty())
            return "No visible element matches " + what + (exact ? " exactly" : "") + ". Call read_screen to see what is there.";
        int index = a.optInt("index", 0);
        if (index < 0 || index >= matches.size())
            return matches.size() + " elements match " + what + "; index " + index + " is out of range (0-" + (matches.size() - 1) + ").";
        AccessibilityNodeInfo n = matches.get(index);
        String label = describe(n);
        String count = matches.size() > 1 ? " (match " + index + " of " + matches.size() + ")" : "";
        AccessibilityNodeInfo target = clickableAncestor(n, longPress);
        if (target != null && target.performAction(longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK)) {
            Thread.sleep(400);
            return (longPress ? "Long-pressed " : "Tapped ") + label + count + " via accessibility click. Call read_screen to see the result.";
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return "Found " + label + count + " but it has no on-screen area; scroll to it first.";
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        boolean ok = gesture(p, longPress ? 700 : 60);
        return (ok ? (longPress ? "Long-pressed " : "Tapped ") : "Gesture was cancelled on ") + label + count + " at ("
                + r.centerX() + ", " + r.centerY() + "). Call read_screen to see the result.";
    }

    private static boolean same(CharSequence value, String query) {
        return value != null && value.toString().trim().equalsIgnoreCase(query);
    }

    private static String describe(AccessibilityNodeInfo n) {
        StringBuilder b = new StringBuilder(shortClass(n.getClassName()));
        CharSequence t = !empty(n.getText()) ? n.getText() : n.getContentDescription();
        if (!empty(t)) b.append(" \"").append(clip(t)).append('"');
        String res = shortId(n.getViewIdResourceName());
        if (res != null) b.append(" res=").append(res);
        return b.toString();
    }

    private String typeText(JSONObject a) throws Exception {
        String text = a.getString("text");
        AccessibilityNodeInfo target = node(a);
        if (target == null) target = findFocusedInput();
        if (target == null)
            throw new IllegalStateException("No text field is focused. Tap the text field first, then type.");
        if (!target.isFocused()) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean append = a.optBoolean("append");
        CharSequence cur = target.isShowingHintText() ? null : target.getText();
        boolean pasted;
        if (a.optBoolean("paste")) {
            pasted = paste(target, text, append, cur);
        } else {
            String value = append ? (cur == null ? "" : cur.toString()) + text : text;
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            // Fields that reject direct input (some web views, custom editors) usually still accept a paste.
            pasted = !target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) && paste(target, text, append, cur);
        }
        String result = "Typed " + text.length() + " characters" + (pasted ? " (pasted via the clipboard, which now holds this text)." : ".");
        if (a.optBoolean("submit")) {
            Thread.sleep(150);
            boolean sent = target.performAction(AccessibilityAction.ACTION_IME_ENTER.getId());
            result += sent ? " Pressed enter." : " Enter action not supported here; tap the send button instead.";
        }
        return result;
    }

    /** Insert text through the clipboard: select what should be replaced (or move to the end), then paste. */
    private boolean paste(AccessibilityNodeInfo target, String text, boolean append, CharSequence current) throws Exception {
        DeviceTools.onMain(() -> {
            svc.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("androidagent", text));
            return null;
        });
        int len = current == null ? 0 : current.length();
        Bundle sel = new Bundle();
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, append ? len : 0);
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len);
        target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
        if (!target.performAction(AccessibilityNodeInfo.ACTION_PASTE))
            throw new IllegalStateException("This field accepts neither direct text nor paste. Tap it and use the keyboard "
                    + "app's own input, or ask the user to type it.");
        return true;
    }

    private String imeAction(JSONObject a) throws Exception {
        AccessibilityNodeInfo target = node(a);
        if (target == null) target = findFocusedInput();
        if (target == null) throw new IllegalStateException("No text field is focused. Tap the text field first.");
        boolean ok = target.performAction(AccessibilityAction.ACTION_IME_ENTER.getId());
        Thread.sleep(300);
        return ok ? "Pressed the keyboard action key. Call read_screen to see the result."
                : "This field has no keyboard action; tap the app's send/search button instead.";
    }

    /** The focused text field, or the only visible text field when nothing has focus. */
    private AccessibilityNodeInfo findFocusedInput() {
        List<AccessibilityNodeInfo> editable = new ArrayList<>();
        for (AccessibilityNodeInfo root : roots()) {
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) return f;
            collectEditable(root, editable);
        }
        return editable.size() == 1 ? editable.get(0) : null;
    }

    private static void collectEditable(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null || out.size() > 1 || !n.isVisibleToUser()) return;
        if (n.isEditable() && n.isEnabled()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collectEditable(n.getChild(i), out);
    }

    private static AccessibilityNodeInfo scrollableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 12; i++) {
            if (cur.isScrollable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static boolean supports(AccessibilityNodeInfo n, AccessibilityAction action) {
        for (AccessibilityAction a : n.getActionList()) if (a.getId() == action.getId()) return true;
        return false;
    }

    private String scroll(JSONObject a) throws Exception {
        String dir = a.getString("direction").toLowerCase(Locale.ROOT);
        AccessibilityAction directional;
        switch (dir) {
            case "down": directional = AccessibilityAction.ACTION_SCROLL_DOWN; break;
            case "up": directional = AccessibilityAction.ACTION_SCROLL_UP; break;
            case "right": directional = AccessibilityAction.ACTION_SCROLL_RIGHT; break;
            case "left": directional = AccessibilityAction.ACTION_SCROLL_LEFT; break;
            default: throw new IllegalArgumentException("direction must be up, down, left or right");
        }
        AccessibilityNodeInfo n = node(a);
        Rect r = new Rect();
        if (n != null) {
            AccessibilityNodeInfo s = scrollableAncestor(n);
            if (s != null) {
                boolean forward = dir.equals("down") || dir.equals("right");
                boolean ok = supports(s, directional) && s.performAction(directional.getId());
                if (!ok) ok = s.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                if (ok) {
                    Thread.sleep(400);
                    return "Scrolled " + describe(s) + " " + dir + ". Call read_screen to see the new content.";
                }
            }
            n.getBoundsInScreen(r);
            if (s != null && r.isEmpty()) s.getBoundsInScreen(r);
        }
        if (r.isEmpty()) {
            DisplayMetrics dm = svc.getResources().getDisplayMetrics();
            r.set(0, (int) (dm.heightPixels * 0.15), dm.widthPixels, (int) (dm.heightPixels * 0.85));
        }
        int cx = r.centerX(), cy = r.centerY();
        int dy = (int) (r.height() * 0.35), dx = (int) (r.width() * 0.35);
        Path p = new Path();
        switch (dir) {
            case "down": p.moveTo(cx, cy + dy); p.lineTo(cx, cy - dy); break;
            case "up": p.moveTo(cx, cy - dy); p.lineTo(cx, cy + dy); break;
            case "right": p.moveTo(cx + dx, cy); p.lineTo(cx - dx, cy); break;
            default: p.moveTo(cx - dx, cy); p.lineTo(cx + dx, cy); break;
        }
        gesture(p, 350);
        Thread.sleep(400);
        return "Scrolled " + dir + ". Call read_screen to see the new content.";
    }

    private String swipe(JSONObject a) throws Exception {
        Path p = new Path();
        p.moveTo(a.getInt("x1"), a.getInt("y1"));
        p.lineTo(a.getInt("x2"), a.getInt("y2"));
        long d = Math.max(50, Math.min(3000, a.optLong("duration_ms", 300)));
        boolean ok = gesture(p, d);
        return ok ? "Swiped." : "Swipe was cancelled.";
    }

    private boolean gesture(Path path, long durationMs) throws InterruptedException {
        svc.setBubblePassThrough(true);
        try {
            final CountDownLatch done = new CountDownLatch(1);
            final boolean[] ok = {false};
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs)).build();
            boolean dispatched = svc.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription d) {
                    ok[0] = true;
                    done.countDown();
                }

                @Override
                public void onCancelled(GestureDescription d) {
                    done.countDown();
                }
            }, svc.mainHandler());
            if (!dispatched) return false;
            done.await(durationMs + 3000, TimeUnit.MILLISECONDS);
            Thread.sleep(250);
            return ok[0];
        } finally {
            svc.setBubblePassThrough(false);
        }
    }

    private String press(String button) {
        int action;
        switch (button) {
            case "back": action = AccessibilityService.GLOBAL_ACTION_BACK; break;
            case "home": action = AccessibilityService.GLOBAL_ACTION_HOME; break;
            case "recents": action = AccessibilityService.GLOBAL_ACTION_RECENTS; break;
            case "notifications": action = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": action = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "lock_screen": action = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN; break;
            case "take_screenshot": action = AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT; break;
            case "power_dialog": action = AccessibilityService.GLOBAL_ACTION_POWER_DIALOG; break;
            default: throw new IllegalArgumentException("Unknown button " + button);
        }
        boolean ok = svc.performGlobalAction(action);
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }
        return ok ? "Pressed " + button + "." : "Could not press " + button + ".";
    }

    private JSONObject screenshot() throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Bitmap> shot = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
        final boolean[] accepting = {true};
        Bitmap full = null, small = null;
        svc.setBubblePassThrough(true);
        try {
            Thread.sleep(150);
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(AccessibilityService.ScreenshotResult r) {
                    Bitmap hw = null;
                    try {
                        hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                        Bitmap copy = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                        synchronized (shot) {
                            if (accepting[0]) shot.set(copy);
                            else if (copy != null) copy.recycle();
                        }
                    } catch (Exception e) {
                        error.set(String.valueOf(e.getMessage()));
                    } finally {
                        if (hw != null) hw.recycle();
                        r.getHardwareBuffer().close();
                        done.countDown();
                    }
                }

                @Override
                public void onFailure(int code) {
                    error.set(code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                            ? "Screenshots are rate-limited; wait a second and retry." : "Screenshot failed (code " + code + ").");
                    done.countDown();
                }
            });
            done.await(5, TimeUnit.SECONDS);
            synchronized (shot) { accepting[0] = false; full = shot.getAndSet(null); }
            if (full == null) throw new IllegalStateException(error.get() != null ? error.get() : "Screenshot timed out.");
            float scale = Math.min(1f, (float) SCREENSHOT_WIDTH / full.getWidth());
            small = scale < 1f
                    ? Bitmap.createScaledBitmap(full, Math.round(full.getWidth() * scale), Math.max(1, Math.round(full.getHeight() * scale)), true)
                    : full;
            ByteArrayOutputStream jpg = new ByteArrayOutputStream();
            if (!small.compress(Bitmap.CompressFormat.JPEG, 70, jpg)) throw new IllegalStateException("Screenshot encoding failed.");
            String note = String.format(Locale.ROOT,
                    "Screenshot %dx%d (screen is %dx%d). To tap something you see, multiply image coordinates by %.3f.",
                    small.getWidth(), small.getHeight(), full.getWidth(), full.getHeight(), 1f / scale);
            return new JSONObject().put("content", new JSONArray()
                    .put(new JSONObject().put("type", "image").put("mimeType", "image/jpeg")
                            .put("data", Base64.encodeToString(jpg.toByteArray(), Base64.NO_WRAP)))
                    .put(new JSONObject().put("type", "text").put("text", note)));
        } finally {
            synchronized (shot) {
                accepting[0] = false;
                Bitmap orphan = shot.getAndSet(null);
                if (orphan != null) orphan.recycle();
            }
            if (small != null && small != full) small.recycle();
            if (full != null) full.recycle();
            svc.setBubblePassThrough(false);
        }
    }
}
