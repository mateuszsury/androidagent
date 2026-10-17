package dev.mateuszsury.androidagent;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static dev.mateuszsury.androidagent.ToolProviders.prop;
import static dev.mateuszsury.androidagent.ToolProviders.text;
import static dev.mateuszsury.androidagent.ToolProviders.tool;

/**
 * Core phone tools that work without Accessibility: owner approval, status notifications, waiting, opening
 * apps and links, and waiting for an APK install. Also hosts the shared activity launcher that respects
 * Android's background-activity-start limits.
 */
final class PhoneTools implements ToolProviders.Provider {
    static final PhoneTools INSTANCE = new PhoneTools();

    /** Status updates from the notify tool: quiet, one per conversation, replaced in place. */
    static final String STATUS_CHANNEL = "agent";
    private static final int STATUS_ID = 7300;
    private static final int OFFER_ID = 7302;

    private static final Set<String> NAMES = new HashSet<>(Arrays.asList(
            "confirm", "notify", "wait", "open_url", "open_app", "list_apps", "wait_for_install"));

    private PhoneTools() {
    }

    @Override
    public JSONArray list(Context app) throws JSONException {
        JSONArray t = new JSONArray();
        t.put(tool("confirm",
                "Ask the user to approve an action and wait for the answer. The request appears as an approval card in "
                        + "the androidagent chat (and in the bubble / a notification when the chat is not in front). "
                        + "REQUIRED before sending messages, calling, posting, buying, deleting, or changing settings. "
                        + "Show the exact text or action. Returns APPROVED, DENIED or TIMEOUT (5 minutes).",
                new JSONObject().put("summary", prop("string", "Exactly what you are about to do, including any text to be sent")),
                "summary"));
        t.put(tool("notify",
                "Show a short status line to the user without stopping: an Android notification in the 'Agent' channel "
                        + "(replaced on each call) and the bubble pill when the bubble is shown.",
                new JSONObject().put("text", prop("string", "Status text")), "text"));
        t.put(tool("wait", "Wait for the UI to settle, up to 10000 ms.",
                new JSONObject().put("ms", prop("integer", "Milliseconds")), "ms"));
        t.put(tool("open_url", "Open a URL or deep link (https://, mailto:, geo:, market:, ...). Works without Accessibility.",
                new JSONObject().put("url", prop("string", "The URL")), "url"));
        t.put(tool("open_app", "Open an installed app by its name (e.g. 'Messenger') or package name. Works without Accessibility.",
                new JSONObject().put("app", prop("string", "App name or package")), "app"));
        t.put(tool("list_apps", "List launchable apps with their package names.", new JSONObject()));
        t.put(tool("wait_for_install",
                "After opening the Android installer for an APK, wait until the user has tapped Install/Update and confirmed. "
                        + "Returns as soon as the package is installed (at least min_version_code if given), early if the "
                        + "installer is no longer on screen (detected only with Accessibility), or after the timeout. "
                        + "Use this instead of shell polling loops.",
                new JSONObject().put("package", prop("string", "Package name, e.g. com.buddyapps.flappy"))
                        .put("min_version_code", prop("integer", "Wait for at least this versionCode (for updates)"))
                        .put("timeout_s", prop("integer", "Default 120, max 300")),
                "package"));
        return t;
    }

    @Override
    public boolean handles(String name) {
        return NAMES.contains(name);
    }

    @Override
    public JSONObject call(Context app, String caller, String name, JSONObject a) throws Exception {
        activity(name);
        switch (name) {
            case "confirm": return text(ApprovedCommands.get(app).confirm(caller, a.optString("summary", null)));
            case "notify": return text(notify(app, caller, a.getString("text")));
            case "wait": {
                long ms = Math.max(0, Math.min(10000, a.optLong("ms", 1000)));
                Thread.sleep(ms);
                return text("Waited " + ms + " ms.");
            }
            case "open_url": return text(openUrl(app, a.getString("url")));
            case "open_app": return text(openApp(app, a.getString("app")));
            case "list_apps": return text(listApps(app));
            case "wait_for_install":
                return text(waitForInstall(app, a.getString("package"), a.optLong("min_version_code", 0),
                        Math.max(10, Math.min(300, a.optInt("timeout_s", 120)))));
            default: throw new IllegalArgumentException("Unknown tool " + name);
        }
    }

    /** Show the tool in the bubble (and the "using your phone" indicator for active tools). */
    static void activity(String name) {
        BuddyService svc = BuddyService.get();
        if (svc != null) svc.onToolActivity(name);
    }

    // ------------------------------------------------------------ notify

    private static String notify(Context app, String caller, String text) {
        text = text.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("text is empty");
        if (text.length() > 500) text = text.substring(0, 499) + "…";
        BuddyService svc = BuddyService.get();
        if (svc != null) svc.showStatus(text);
        boolean posted = postStatus(app, caller, text);
        if (posted) return svc != null ? "Shown in a notification and the bubble." : "Shown in a notification.";
        return svc != null ? "Shown in the bubble (Android notifications are off for androidagent)."
                : "Not shown: Android notifications are off for androidagent and the bubble is not active.";
    }

    private static boolean postStatus(Context app, String caller, String text) {
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return false;
        try {
            NotificationChannel ch = new NotificationChannel(STATUS_CHANNEL, "Agent", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Krótkie komunikaty o postępie od agenta (narzędzie notify).");
            nm.createNotificationChannel(ch);
            if (nm.getNotificationChannel(STATUS_CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
            String chat = caller != null && caller.startsWith("chat:") ? caller.substring(5) : null;
            Intent open = chat != null ? new Intent(app, ChatActivity.class).putExtra("id", chat) : new Intent(app, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent tap = PendingIntent.getActivity(app, STATUS_ID + (chat == null ? 0 : chat.hashCode() & 0xffff), open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(app, STATUS_CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle("Agent")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setContentIntent(tap)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .build();
            nm.notify(statusTag(caller), STATUS_ID, n);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String statusTag(String caller) {
        return "status:" + (caller == null ? "external" : caller);
    }

    /** The run ended: its progress line is no longer current (the chat has the answer). */
    static void clearStatus(Context app, String owner) {
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(statusTag(owner), STATUS_ID);
        } catch (RuntimeException ignored) {
        }
    }

    /** Stop: no progress line of any run is current any more. */
    static void clearAllStatus(Context app) {
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm == null) return;
            for (android.service.notification.StatusBarNotification n : nm.getActiveNotifications())
                if (n.getId() == STATUS_ID) nm.cancel(n.getTag(), STATUS_ID);
        } catch (RuntimeException ignored) {
        }
    }

    // ---------------------------------------------------------- launching

    /**
     * Whether androidagent itself may start an activity right now. Android blocks background activity starts;
     * the exemptions that apply here are a connected Accessibility service, a visible androidagent screen, or
     * the "display over other apps" special access.
     */
    static boolean canStartActivities(Context app) {
        if (BuddyService.get() != null) return true;
        if (Settings.canDrawOverlays(app)) return true;
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    /**
     * Start an activity for a tool. Returns normally when Android accepted it. When androidagent is in the
     * background without Accessibility, posts a tap-to-open notification instead and throws a clear error.
     */
    static void launch(Context app, Intent intent, String what) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        BuddyService svc = BuddyService.get();
        Context from = svc != null ? svc : app;
        if (!canStartActivities(app)) {
            boolean offered = offerLaunch(app, intent, what);
            throw new IllegalStateException("Android does not let androidagent open other screens while it is in the "
                    + "background and screen control (Accessibility) is off, so " + what + " was not opened. "
                    + (offered ? "A notification \"Dotknij, aby otworzyć\" was posted: ask the user to tap it, or to "
                    + "return to androidagent and let you retry."
                    : "Ask the user to return to the androidagent app (or enable screen control) and retry."));
        }
        try {
            from.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            throw new NoHandler("No app on this phone can open " + what + ".");
        } catch (SecurityException e) {
            throw new IllegalStateException("Android refused to open " + what + ": " + e.getMessage());
        }
    }

    /** No installed app handles the intent. */
    static final class NoHandler extends IllegalStateException {
        NoHandler(String message) {
            super(message);
        }
    }

    /** A notification the owner can tap to perform the start Android blocked in the background. */
    private static boolean offerLaunch(Context app, Intent intent, String what) {
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return false;
        try {
            ApprovedCommands.createActionChannel(app);
            PendingIntent tap = PendingIntent.getActivity(app, OFFER_ID, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(app, ApprovedCommands.ACTION_CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle("Agent chce otworzyć: " + what)
                    .setContentText("Dotknij, aby otworzyć.")
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build();
            nm.notify(OFFER_ID, n);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String seeResult() {
        return BuddyService.get() != null ? " Call read_screen to see it." : "";
    }

    private static String openUrl(Context app, String url) throws InterruptedException {
        url = url.trim();
        Uri uri = Uri.parse(url);
        if (uri.getScheme() == null) throw new IllegalArgumentException("Give a full URL with a scheme, e.g. https://example.com");
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("file") || scheme.equals("javascript"))
            throw new IllegalArgumentException(scheme + ": URLs cannot be opened; use an https:// link or a deep link.");
        // The same number rules as dial/compose_sms: no service codes typed into the dialer.
        if (scheme.equals("tel") || scheme.equals("sms") || scheme.equals("smsto") || scheme.equals("mms") || scheme.equals("mmsto")) {
            String number = uri.getSchemeSpecificPart();
            int query = number == null ? -1 : number.indexOf('?');
            IntentTools.checkNumber(query >= 0 ? number.substring(0, query) : number);
        }
        launch(app, new Intent(Intent.ACTION_VIEW, uri), url);
        Thread.sleep(1500);
        return "Opened " + url + "." + seeResult();
    }

    private static List<ResolveInfo> launchables(Context app) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        return app.getPackageManager().queryIntentActivities(i, 0);
    }

    private static String openApp(Context app, String query) throws InterruptedException {
        PackageManager pm = app.getPackageManager();
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) throw new IllegalArgumentException("app is empty");
        // Score every app: package name > exact label > label starts with query > label contains it.
        List<String[]> top = new ArrayList<>(); // {package, label} with the best score
        int bestScore = 0;
        for (ResolveInfo ri : launchables(app)) {
            String pkg = ri.activityInfo.packageName;
            String label = String.valueOf(ri.loadLabel(pm));
            String l = label.toLowerCase(Locale.ROOT);
            int score = pkg.equalsIgnoreCase(q) ? 4 : l.equals(q) ? 3 : l.startsWith(q) ? 2 : l.contains(q) ? 1 : 0;
            if (score == 0 || score < bestScore) continue;
            if (score > bestScore) {
                bestScore = score;
                top.clear();
            }
            boolean dup = false;
            for (String[] t : top) dup |= t[0].equals(pkg);
            if (!dup) top.add(new String[]{pkg, label});
        }
        if (top.isEmpty()) return "No installed app matches '" + query + "'. Use list_apps to see names.";
        if (top.size() > 1) {
            // Don't guess between e.g. Amazon Shopping / Amazon Music / Amazon Alexa.
            StringBuilder sb = new StringBuilder("Several apps match '" + query + "', so nothing was opened:\n");
            for (String[] t : top) sb.append("- ").append(t[1]).append(" (").append(t[0]).append(")\n");
            sb.append("Call open_app again with the exact name or package. If it's unclear which one the user means, ask them.");
            return sb.toString();
        }
        String best = top.get(0)[0];
        String bestLabel = top.get(0)[1];
        Intent launch = pm.getLaunchIntentForPackage(best);
        if (launch == null) return "App " + bestLabel + " cannot be launched.";
        launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        launch(app, launch, bestLabel);
        Thread.sleep(1500);
        return "Opened " + bestLabel + " (" + best + ")." + seeResult();
    }

    private static String listApps(Context app) {
        PackageManager pm = app.getPackageManager();
        List<String> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : launchables(app)) {
            String line = ri.loadLabel(pm) + " — " + ri.activityInfo.packageName;
            if (seen.add(line)) lines.add(line);
        }
        Collections.sort(lines, String.CASE_INSENSITIVE_ORDER);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------- wait_for_install

    private static long installedVersion(Context app, String pkg) {
        try {
            return app.getPackageManager().getPackageInfo(pkg, 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    /** Is an installer / Play Protect dialog what the user is looking at? Null when it cannot be known. */
    private static Boolean installerOnScreen() {
        BuddyService svc = BuddyService.get();
        if (svc == null) return null;
        for (AccessibilityWindowInfo w : svc.getWindows()) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null || r.getPackageName() == null) continue;
            String p = r.getPackageName().toString();
            if (p.contains("packageinstaller") || p.equals("com.android.vending")) return true;
            if (w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && w.getTitle() != null
                    && w.getTitle().toString().toLowerCase(Locale.ROOT).contains("install")) return true;
            if ("android".equals(p) && w.isActive()) return true; // "Open with" chooser
        }
        return false;
    }

    private static String waitForInstall(Context app, String pkg, long minVersion, int timeoutS) throws InterruptedException {
        long before = installedVersion(app, pkg);
        long need = minVersion > 0 ? minVersion : (before < 0 ? 0 : before + 1);
        BuddyService svc = BuddyService.get();
        if (svc != null) svc.showStatus(before < 0 ? "Dotknij Zainstaluj, potem potwierdź" : "Dotknij Aktualizuj, potem potwierdź");
        DeviceTools.nudge(app);
        long start = System.currentTimeMillis(), lastSeen = start;
        while (System.currentTimeMillis() - start < timeoutS * 1000L) {
            long v = installedVersion(app, pkg);
            if (v >= 0 && v >= need) {
                svc = BuddyService.get();
                if (svc != null) svc.showStatus("Zainstalowano");
                return "Installed " + pkg + " (versionCode " + v + "). The user confirmed the install.";
            }
            Boolean onScreen = installerOnScreen();
            if (onScreen == null || onScreen) lastSeen = System.currentTimeMillis();
            else if (System.currentTimeMillis() - lastSeen > 12000) {
                return "Not installed yet, and the installer is no longer on screen (the user may have closed it or "
                        + "switched apps). Return to androidagent and reopen the installer, then call wait_for_install again.";
            }
            Thread.sleep(1000);
        }
        return "Timed out after " + timeoutS + " s: the user hasn't confirmed the install. Ask them whether they want to "
                + "install it, instead of waiting longer.";
    }
}
