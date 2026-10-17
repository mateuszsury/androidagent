package dev.mateuszsury.androidagent;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static dev.mateuszsury.androidagent.ToolProviders.prop;
import static dev.mateuszsury.androidagent.ToolProviders.text;
import static dev.mateuszsury.androidagent.ToolProviders.tool;

/**
 * Android intents for the agent, without Accessibility. Every tool only OPENS a screen (dialer, SMS composer,
 * share sheet, calendar editor, settings) or hands a request to the clock app; nothing is sent or called here.
 * {@code start_intent} refuses actions that act without the owner's own tap (calls, sending, uninstalling,
 * resets, device admin) and never grants URI permissions or targets androidagent itself.
 */
final class IntentTools implements ToolProviders.Provider {
    static final IntentTools INSTANCE = new IntentTools();

    private static final Set<String> NAMES = new HashSet<>(Arrays.asList(
            "start_intent", "share_text", "open_settings", "set_alarm", "set_timer", "add_calendar_event", "dial",
            "compose_sms"));

    /** Actions start_intent never starts; the dedicated tools cover the safe variants. */
    private static final Map<String, String> DENIED = new LinkedHashMap<>();
    static {
        String call = "Placing calls is not allowed; use dial, which only opens the dialer for the user to call.";
        DENIED.put(Intent.ACTION_CALL, call);
        DENIED.put("android.intent.action.CALL_PRIVILEGED", call);
        DENIED.put("android.intent.action.CALL_EMERGENCY", call);
        String send = "Use compose_sms (SMS composer) or share_text (share sheet); the user sends.";
        DENIED.put(Intent.ACTION_SENDTO, send);
        DENIED.put(Intent.ACTION_SEND, send);
        DENIED.put(Intent.ACTION_SEND_MULTIPLE, send);
        DENIED.put("android.provider.Telephony.SMS_DELIVER", send);
        String packages = "Installing, uninstalling or deleting apps is not allowed from start_intent.";
        DENIED.put(Intent.ACTION_DELETE, packages);
        DENIED.put(Intent.ACTION_UNINSTALL_PACKAGE, packages);
        DENIED.put(Intent.ACTION_INSTALL_PACKAGE, packages);
        String system = "Resetting, rebooting or shutting down the phone is not allowed.";
        DENIED.put("android.intent.action.MASTER_CLEAR", system);
        DENIED.put("android.intent.action.FACTORY_RESET", system);
        DENIED.put(Intent.ACTION_REBOOT, system);
        DENIED.put(Intent.ACTION_SHUTDOWN, system);
        DENIED.put("android.intent.action.ACTION_REQUEST_SHUTDOWN", system);
        DENIED.put("com.android.internal.intent.action.REQUEST_SHUTDOWN", system);
        DENIED.put("android.intent.action.FACTORY_TEST", system);
        String admin = "Device administration, provisioning, passwords and default-app roles are not allowed.";
        DENIED.put("android.app.action.ADD_DEVICE_ADMIN", admin);
        DENIED.put("android.app.action.PROVISION_MANAGED_PROFILE", admin);
        DENIED.put("android.app.action.PROVISION_MANAGED_DEVICE", admin);
        DENIED.put("android.app.action.SET_NEW_PASSWORD", admin);
        DENIED.put("android.app.action.SET_NEW_PARENT_PROFILE_PASSWORD", admin);
        DENIED.put("android.provider.Telephony.ACTION_CHANGE_DEFAULT", admin);
        DENIED.put("android.app.role.action.REQUEST_ROLE", admin);
    }

    private static final String[] FLAG_NAMES = {"clear_top", "clear_task", "single_top", "no_history",
            "exclude_from_recents", "reorder_to_front", "multiple_task", "new_document", "no_animation"};
    private static final int[] FLAGS = {Intent.FLAG_ACTIVITY_CLEAR_TOP, Intent.FLAG_ACTIVITY_CLEAR_TASK,
            Intent.FLAG_ACTIVITY_SINGLE_TOP, Intent.FLAG_ACTIVITY_NO_HISTORY, Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT, Intent.FLAG_ACTIVITY_MULTIPLE_TASK, Intent.FLAG_ACTIVITY_NEW_DOCUMENT,
            Intent.FLAG_ACTIVITY_NO_ANIMATION};

    private static final String[] PANELS = {"settings", "wifi", "internet", "bluetooth", "airplane", "data_usage", "vpn",
            "nfc", "cast", "volume", "sound", "dnd", "location", "display", "battery", "battery_saver", "app", "apps",
            "notifications", "default_apps", "accessibility", "developer", "date", "language", "keyboard", "storage",
            "security", "privacy", "accounts", "usage_access", "write_settings", "overlay", "install_sources"};

    private IntentTools() {
    }

    @Override
    public JSONArray list(Context app) throws JSONException {
        JSONArray t = new JSONArray();
        JSONArray flagNames = new JSONArray();
        for (String f : FLAG_NAMES) flagNames.put(f);
        t.put(tool("start_intent",
                "Start an Android activity intent (FLAG_ACTIVITY_NEW_TASK is always added). Refused: calls, "
                        + "SEND/SENDTO (use share_text / compose_sms), install/uninstall/delete, resets, device admin "
                        + "and roles, and androidagent's own screens. URI permissions are never granted. Anything "
                        + "outward-facing still needs confirm first.",
                new JSONObject().put("action", prop("string", "Intent action, e.g. android.intent.action.VIEW"))
                        .put("data", prop("string", "Optional data URI"))
                        .put("type", prop("string", "Optional MIME type"))
                        .put("package", prop("string", "Optional target package"))
                        .put("component", prop("string", "Optional component, e.g. com.example/.MainActivity"))
                        .put("category", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string"))
                                .put("description", "Optional categories, e.g. [\"android.intent.category.BROWSABLE\"]"))
                        .put("extras", new JSONObject().put("type", "object")
                                .put("description", "Optional extras: values may be string, integer, number, boolean or an array of strings/integers"))
                        .put("flags", new JSONObject().put("type", "array")
                                .put("items", new JSONObject().put("type", "string").put("enum", flagNames))
                                .put("description", "Optional extra activity flags")),
                "action"));
        t.put(tool("share_text", "Open Android's share sheet with text; the user picks the app and sends it.",
                new JSONObject().put("text", prop("string", "Text to share"))
                        .put("subject", prop("string", "Optional subject (for e-mail apps)")),
                "text"));
        JSONArray panels = new JSONArray();
        for (String p : PANELS) panels.put(p);
        t.put(tool("open_settings",
                "Open a system settings screen or quick panel (wifi, internet, volume and nfc open as panels on top of "
                        + "the current app). Only opens it: the user changes the setting, or you do it via screen tools "
                        + "after confirm.",
                new JSONObject().put("panel", new JSONObject().put("type", "string").put("enum", panels))
                        .put("package", prop("string", "For app / notifications / install_sources: the app package (default androidagent)")),
                "panel"));
        t.put(tool("set_alarm", "Create an alarm in the clock app.",
                new JSONObject().put("hour", prop("integer", "0-23"))
                        .put("minute", prop("integer", "0-59"))
                        .put("label", prop("string", "Optional label"))
                        .put("days", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string"))
                                .put("description", "Optional repeat days: mon, tue, wed, thu, fri, sat, sun"))
                        .put("skip_ui", prop("boolean", "Create it without showing the clock app (default false)")),
                "hour", "minute"));
        t.put(tool("set_timer", "Start a countdown timer in the clock app.",
                new JSONObject().put("seconds", prop("integer", "1-86400"))
                        .put("label", prop("string", "Optional label"))
                        .put("skip_ui", prop("boolean", "Start it without showing the clock app (default false)")),
                "seconds"));
        t.put(tool("add_calendar_event",
                "Open the calendar app's new-event editor pre-filled; the user saves it. Times: ISO 8601 in the phone's "
                        + "time zone (2026-10-16T14:30), with an offset, a date only (all-day), or epoch milliseconds.",
                new JSONObject().put("title", prop("string", "Event title"))
                        .put("begin", prop("string", "Start time"))
                        .put("end", prop("string", "Optional end time (default: 1 hour after begin; all-day: same day)"))
                        .put("location", prop("string", "Optional location"))
                        .put("description", prop("string", "Optional description"))
                        .put("all_day", prop("boolean", "Optional all-day event")),
                "title", "begin"));
        t.put(tool("dial", "Open the phone dialer with a number filled in. It never places the call: the user taps Call.",
                new JSONObject().put("number", prop("string", "Phone number")), "number"));
        t.put(tool("compose_sms",
                "Open the SMS composer with recipient and text filled in. It never sends: the user taps Send. Do not "
                        + "tap Send yourself.",
                new JSONObject().put("number", prop("string", "Recipient phone number (several separated by ;)"))
                        .put("body", prop("string", "Message text")),
                "number", "body"));
        return t;
    }

    @Override
    public boolean handles(String name) {
        return NAMES.contains(name);
    }

    @Override
    public JSONObject call(Context app, String caller, String name, JSONObject a) throws Exception {
        PhoneTools.activity(name);
        switch (name) {
            case "start_intent": return text(startIntent(app, a));
            case "share_text": return text(shareText(app, a.getString("text"), a.optString("subject", "")));
            case "open_settings": return text(openSettings(app, a.getString("panel"), a.optString("package", "")));
            case "set_alarm": return text(setAlarm(app, a));
            case "set_timer": return text(setTimer(app, a));
            case "add_calendar_event": return text(addCalendarEvent(app, a));
            case "dial": return text(dial(app, a.getString("number")));
            case "compose_sms": return text(composeSms(app, a.getString("number"), a.getString("body")));
            default: throw new IllegalArgumentException("Unknown tool " + name);
        }
    }

    private static String settle(String message) throws InterruptedException {
        Thread.sleep(1200);
        return message + PhoneTools.seeResult();
    }

    // ------------------------------------------------------------ start_intent

    private static String startIntent(Context app, JSONObject a) throws Exception {
        String action = a.getString("action").trim();
        if (action.isEmpty()) throw new IllegalArgumentException("action is empty");
        String denied = DENIED.get(action);
        if (denied != null) throw new SecurityException("start_intent refuses " + action + ". " + denied);
        Intent intent = new Intent(action);
        String data = a.optString("data", "").trim(), type = a.optString("type", "").trim();
        Uri uri = data.isEmpty() ? null : Uri.parse(data);
        if (uri != null) checkUri(app, uri);
        if (uri != null && !type.isEmpty()) intent.setDataAndType(uri, type);
        else if (uri != null) intent.setData(uri);
        else if (!type.isEmpty()) intent.setType(type);
        String own = app.getPackageName();
        String pkg = a.optString("package", "").trim();
        if (!pkg.isEmpty()) {
            if (pkg.equals(own)) throw new SecurityException("start_intent cannot target androidagent itself.");
            intent.setPackage(pkg);
        }
        String component = a.optString("component", "").trim();
        if (!component.isEmpty()) {
            ComponentName cn = ComponentName.unflattenFromString(component);
            if (cn == null) throw new IllegalArgumentException("component must look like com.example/.MainActivity");
            if (cn.getPackageName().equals(own)) throw new SecurityException("start_intent cannot target androidagent itself.");
            intent.setComponent(cn);
        }
        Object categories = a.opt("category");
        if (categories instanceof JSONArray) {
            JSONArray c = (JSONArray) categories;
            for (int i = 0; i < c.length(); i++) intent.addCategory(c.getString(i));
        } else if (categories instanceof String && !((String) categories).trim().isEmpty()) {
            intent.addCategory(((String) categories).trim());
        }
        JSONObject extras = a.optJSONObject("extras");
        if (extras != null) putExtras(intent, extras);
        JSONArray flags = a.optJSONArray("flags");
        if (flags != null) {
            for (int i = 0; i < flags.length(); i++) {
                String f = flags.getString(i);
                int idx = Arrays.asList(FLAG_NAMES).indexOf(f);
                if (idx < 0) throw new IllegalArgumentException("Unknown flag " + f + ". Allowed: " + Arrays.toString(FLAG_NAMES));
                intent.addFlags(FLAGS[idx]);
            }
        }
        // Implicit intents that would resolve to androidagent itself are refused too.
        String handler = handler(app, intent);
        if (own.equals(handler)) throw new SecurityException("start_intent cannot target androidagent itself.");
        PhoneTools.launch(app, intent, action + (uri != null ? " " + data : ""));
        return settle("Started " + action + (uri != null ? " for " + data : "")
                + (handler != null && !"android".equals(handler) ? " (handled by " + handler + ")." : "."));
    }

    /** The package that would handle an intent, when Android's package visibility lets us see it. */
    private static String handler(Context app, Intent intent) {
        try {
            ResolveInfo ri = app.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void checkUri(Context app, Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("file")) throw new IllegalArgumentException("file:// URIs cannot be shared with other apps.");
        if (scheme.equals("content") && uri.getAuthority() != null && uri.getAuthority().startsWith(app.getPackageName()))
            throw new SecurityException("androidagent's own files cannot be passed to other apps this way.");
        if (scheme.equals("tel")) checkNumber(uri.getSchemeSpecificPart());
    }

    private static void putExtras(Intent intent, JSONObject extras) throws JSONException {
        Iterator<String> keys = extras.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object v = extras.get(key);
            if (v instanceof String) intent.putExtra(key, (String) v);
            else if (v instanceof Boolean) intent.putExtra(key, (Boolean) v);
            else if (v instanceof Integer) intent.putExtra(key, (Integer) v);
            else if (v instanceof Long) intent.putExtra(key, (Long) v);
            else if (v instanceof Number) intent.putExtra(key, ((Number) v).doubleValue());
            else if (v instanceof JSONArray) {
                JSONArray arr = (JSONArray) v;
                boolean ints = arr.length() > 0;
                for (int i = 0; i < arr.length(); i++) ints &= arr.get(i) instanceof Integer;
                if (ints) {
                    int[] out = new int[arr.length()];
                    for (int i = 0; i < out.length; i++) out[i] = arr.getInt(i);
                    intent.putExtra(key, out);
                } else {
                    String[] out = new String[arr.length()];
                    for (int i = 0; i < out.length; i++) {
                        if (!(arr.get(i) instanceof String)) throw new IllegalArgumentException("Extra " + key + ": arrays may hold only strings or only integers.");
                        out[i] = arr.getString(i);
                    }
                    intent.putExtra(key, out);
                }
            } else throw new IllegalArgumentException("Extra " + key + " must be a string, number, boolean or array.");
        }
    }

    // ---------------------------------------------------------------- sharing

    private static String shareText(Context app, String text, String subject) throws InterruptedException {
        if (text.isEmpty()) throw new IllegalArgumentException("text is empty");
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        if (!subject.isEmpty()) send.putExtra(Intent.EXTRA_SUBJECT, subject);
        PhoneTools.launch(app, Intent.createChooser(send, "Udostępnij"), "the share sheet");
        return settle("Opened the share sheet with " + text.length() + " characters. The user picks the app and sends it.");
    }

    // --------------------------------------------------------------- settings

    private static String openSettings(Context app, String panel, String pkg) throws InterruptedException {
        String target = pkg.isEmpty() ? app.getPackageName() : pkg;
        Uri pkgUri = Uri.fromParts("package", target, null);
        List<Intent> candidates = new ArrayList<>();
        switch (panel) {
            case "settings": candidates.add(new Intent(Settings.ACTION_SETTINGS)); break;
            case "wifi":
                candidates.add(new Intent(Settings.Panel.ACTION_WIFI));
                candidates.add(new Intent(Settings.ACTION_WIFI_SETTINGS));
                break;
            case "internet":
                candidates.add(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
                candidates.add(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                break;
            case "bluetooth": candidates.add(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); break;
            case "airplane": candidates.add(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)); break;
            case "data_usage":
                candidates.add(new Intent(Settings.ACTION_DATA_USAGE_SETTINGS));
                candidates.add(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                break;
            case "vpn": candidates.add(new Intent(Settings.ACTION_VPN_SETTINGS)); break;
            case "nfc":
                candidates.add(new Intent(Settings.Panel.ACTION_NFC));
                candidates.add(new Intent(Settings.ACTION_NFC_SETTINGS));
                break;
            case "cast": candidates.add(new Intent(Settings.ACTION_CAST_SETTINGS)); break;
            case "volume":
                candidates.add(new Intent(Settings.Panel.ACTION_VOLUME));
                candidates.add(new Intent(Settings.ACTION_SOUND_SETTINGS));
                break;
            case "sound": candidates.add(new Intent(Settings.ACTION_SOUND_SETTINGS)); break;
            case "dnd":
                candidates.add(new Intent("android.settings.ZEN_MODE_SETTINGS"));
                candidates.add(new Intent(Settings.ACTION_SOUND_SETTINGS));
                break;
            case "location": candidates.add(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); break;
            case "display": candidates.add(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); break;
            case "battery":
                candidates.add(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY));
                candidates.add(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
                break;
            case "battery_saver": candidates.add(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)); break;
            case "app": candidates.add(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)); break;
            case "apps":
                candidates.add(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
                candidates.add(new Intent(Settings.ACTION_APPLICATION_SETTINGS));
                break;
            case "notifications":
                candidates.add(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, target));
                break;
            case "default_apps": candidates.add(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); break;
            case "accessibility": candidates.add(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); break;
            case "developer": candidates.add(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); break;
            case "date": candidates.add(new Intent(Settings.ACTION_DATE_SETTINGS)); break;
            case "language": candidates.add(new Intent(Settings.ACTION_LOCALE_SETTINGS)); break;
            case "keyboard": candidates.add(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)); break;
            case "storage": candidates.add(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)); break;
            case "security": candidates.add(new Intent(Settings.ACTION_SECURITY_SETTINGS)); break;
            case "privacy": candidates.add(new Intent(Settings.ACTION_PRIVACY_SETTINGS)); break;
            case "accounts": candidates.add(new Intent(Settings.ACTION_SYNC_SETTINGS)); break;
            case "usage_access": candidates.add(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); break;
            case "write_settings":
                candidates.add(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.fromParts("package", app.getPackageName(), null)));
                break;
            case "overlay":
                candidates.add(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", app.getPackageName(), null)));
                break;
            case "install_sources": candidates.add(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri)); break;
            default: throw new IllegalArgumentException("Unknown panel " + panel + ". Use one of " + Arrays.toString(PANELS));
        }
        candidates.add(new Intent(Settings.ACTION_SETTINGS));
        for (Intent intent : candidates) {
            try {
                PhoneTools.launch(app, intent, "settings: " + panel);
            } catch (PhoneTools.NoHandler e) {
                continue;
            }
            boolean fallback = Settings.ACTION_SETTINGS.equals(intent.getAction()) && !"settings".equals(panel);
            return settle(fallback ? "This phone has no direct " + panel + " screen; opened the main Settings instead."
                    : "Opened " + panel + " settings.");
        }
        throw new IllegalStateException("No settings screen could be opened on this phone.");
    }

    // ------------------------------------------------------------ clock

    private static String setAlarm(Context app, JSONObject a) throws Exception {
        int hour = a.getInt("hour"), minute = a.getInt("minute");
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) throw new IllegalArgumentException("hour must be 0-23 and minute 0-59");
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, a.optBoolean("skip_ui"));
        String label = a.optString("label", "").trim();
        if (!label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        JSONArray days = a.optJSONArray("days");
        StringBuilder repeat = new StringBuilder();
        if (days != null && days.length() > 0) {
            ArrayList<Integer> list = new ArrayList<>();
            for (int d = 0; d < days.length(); d++) {
                int day = day(days.getString(d));
                if (!list.contains(day)) list.add(day);
                repeat.append(repeat.length() == 0 ? "" : ", ").append(days.getString(d));
            }
            i.putExtra(AlarmClock.EXTRA_DAYS, list);
        }
        PhoneTools.launch(app, i, "the clock app");
        return settle(String.format(Locale.ROOT, "Asked the clock app to set an alarm at %02d:%02d%s%s.", hour, minute,
                label.isEmpty() ? "" : " (" + label + ")", repeat.length() == 0 ? "" : ", repeating " + repeat));
    }

    private static int day(String name) {
        String d = name.trim().toLowerCase(Locale.ROOT);
        if (d.length() >= 3) d = d.substring(0, 3);
        switch (d) {
            case "mon": return Calendar.MONDAY;
            case "tue": return Calendar.TUESDAY;
            case "wed": return Calendar.WEDNESDAY;
            case "thu": return Calendar.THURSDAY;
            case "fri": return Calendar.FRIDAY;
            case "sat": return Calendar.SATURDAY;
            case "sun": return Calendar.SUNDAY;
            default: throw new IllegalArgumentException("Unknown day " + name + "; use mon, tue, wed, thu, fri, sat, sun");
        }
    }

    private static String setTimer(Context app, JSONObject a) throws Exception {
        int seconds = a.getInt("seconds");
        if (seconds < 1 || seconds > 86400) throw new IllegalArgumentException("seconds must be 1-86400");
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, a.optBoolean("skip_ui"));
        String label = a.optString("label", "").trim();
        if (!label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        PhoneTools.launch(app, i, "the clock app");
        return settle("Asked the clock app to start a " + duration(seconds) + " timer" + (label.isEmpty() ? "" : " (" + label + ")") + ".");
    }

    private static String duration(int s) {
        int h = s / 3600, m = s % 3600 / 60, sec = s % 60;
        StringBuilder b = new StringBuilder();
        if (h > 0) b.append(h).append(" h ");
        if (m > 0) b.append(m).append(" min ");
        if (sec > 0 || b.length() == 0) b.append(sec).append(" s");
        return b.toString().trim();
    }

    // --------------------------------------------------------------- calendar

    private static String addCalendarEvent(Context app, JSONObject a) throws Exception {
        String title = a.getString("title").trim();
        if (title.isEmpty()) throw new IllegalArgumentException("title is empty");
        boolean[] dateOnly = {false};
        long begin = time(a.get("begin"), dateOnly);
        boolean allDay = a.optBoolean("all_day", dateOnly[0]);
        long end;
        if (a.has("end") && !a.isNull("end") && !a.optString("end").isEmpty()) end = time(a.get("end"), new boolean[1]);
        else end = allDay ? begin + 24L * 3600 * 1000 : begin + 3600L * 1000;
        if (end < begin) throw new IllegalArgumentException("end is before begin");
        Intent i = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay);
        String location = a.optString("location", "").trim(), description = a.optString("description", "").trim();
        if (!location.isEmpty()) i.putExtra(CalendarContract.Events.EVENT_LOCATION, location);
        if (!description.isEmpty()) i.putExtra(CalendarContract.Events.DESCRIPTION, description);
        PhoneTools.launch(app, i, "the calendar app");
        ZoneId zone = ZoneId.systemDefault();
        DateTimeFormatter f = DateTimeFormatter.ofPattern(allDay ? "EEE d MMM yyyy" : "EEE d MMM yyyy HH:mm", Locale.ENGLISH);
        String when = f.format(Instant.ofEpochMilli(begin).atZone(zone))
                + (allDay ? "" : " - " + DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).format(Instant.ofEpochMilli(end).atZone(zone)));
        return settle("Opened the calendar editor for '" + title + "' on " + when + " (" + zone.getId()
                + "). The user reviews and saves it.");
    }

    static long time(Object v, boolean[] dateOnly) {
        if (v instanceof Number) return ((Number) v).longValue();
        String s = String.valueOf(v).trim();
        if (s.matches("\\d{10,}")) return Long.parseLong(s);
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli(); } catch (DateTimeParseException ignored) { }
        try { return ZonedDateTime.parse(s).toInstant().toEpochMilli(); } catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(s.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
        catch (DateTimeParseException ignored) { }
        try {
            long t = LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            dateOnly[0] = true;
            return t;
        } catch (DateTimeParseException ignored) { }
        throw new IllegalArgumentException("Cannot read the time '" + s + "'. Use ISO 8601, e.g. 2026-10-16T14:30.");
    }

    // ------------------------------------------------------- phone and SMS

    private static String checkNumber(String number) {
        String n = number == null ? "" : number.trim();
        if (n.isEmpty()) throw new IllegalArgumentException("number is empty");
        if (!n.matches("[0-9+*#,;()\\-. pPwW]{1,64}")) throw new IllegalArgumentException("number may contain only digits, +, *, #, spaces and separators");
        // Service/secret codes (*#06#, *#*#...#*#*) can act as soon as they are typed in some dialers.
        if (n.contains("*#") || n.contains("#*")) throw new SecurityException("Service codes (*#…) are not allowed.");
        return n;
    }

    private static String dial(Context app, String number) throws InterruptedException {
        String n = checkNumber(number);
        PhoneTools.launch(app, new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", n, null)), "the dialer");
        return settle("Opened the dialer with " + n + ". Nothing was called: the user taps Call if they want to.");
    }

    private static String composeSms(Context app, String number, String body) throws InterruptedException {
        String n = checkNumber(number);
        if (body.length() > 5000) throw new IllegalArgumentException("body is too long (max 5000 characters)");
        // "sms_body" is the classic extra; current messaging apps (e.g. Google Messages) also read EXTRA_TEXT.
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n, ";,+")))
                .putExtra("sms_body", body).putExtra(Intent.EXTRA_TEXT, body);
        PhoneTools.launch(app, i, "the SMS composer");
        return settle("Opened the SMS composer to " + n + " with " + body.length() + " characters filled in. Nothing was "
                + "sent: the user reviews it and taps Send. Do not tap Send yourself.");
    }
}
