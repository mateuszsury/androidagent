package dev.mateuszsury.androidagent;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static dev.mateuszsury.androidagent.ToolProviders.prop;
import static dev.mateuszsury.androidagent.ToolProviders.result;
import static dev.mateuszsury.androidagent.ToolProviders.text;
import static dev.mateuszsury.androidagent.ToolProviders.tool;

/** Device controls without Accessibility: volume, media keys, torch, clipboard, speech, brightness, vibration. */
final class DeviceTools implements ToolProviders.Provider {
    static final DeviceTools INSTANCE = new DeviceTools();

    private static final Set<String> NAMES = new HashSet<>(Arrays.asList(
            "set_volume", "get_volume", "media_key", "torch", "clipboard_set", "clipboard_get", "speak",
            "set_brightness", "vibrate"));
    private static final String[] STREAM_NAMES = {"music", "ring", "alarm", "notification"};
    private static final int[] STREAMS = {AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING,
            AudioManager.STREAM_ALARM, AudioManager.STREAM_NOTIFICATION};

    private DeviceTools() {
    }

    @Override
    public JSONArray list(Context app) throws JSONException {
        JSONArray t = new JSONArray();
        t.put(tool("set_volume",
                "Set a volume stream. Give level (0..max, see get_volume) or percent (0-100). Shows the system volume "
                        + "slider briefly so the user sees the change.",
                new JSONObject().put("stream", enumProp("Which volume", STREAM_NAMES))
                        .put("level", prop("integer", "Absolute level, 0..max for that stream"))
                        .put("percent", prop("integer", "0-100, used when level is not given")),
                "stream"));
        t.put(tool("get_volume", "Read current, min and max levels of the music, ring, alarm and notification volumes, "
                + "the ringer mode and whether music is playing.", new JSONObject()));
        t.put(tool("media_key", "Send a media button to the app currently playing media.",
                new JSONObject().put("key", enumProp("Media button", "play_pause", "play", "pause", "next", "previous", "stop")),
                "key"));
        t.put(tool("torch", "Turn the flashlight on or off.",
                new JSONObject().put("on", prop("boolean", "true = on, false = off")), "on"));
        t.put(tool("clipboard_set", "Copy text to the clipboard (replaces what the user had copied).",
                new JSONObject().put("text", prop("string", "Text to copy")), "text"));
        t.put(tool("clipboard_get",
                "Read the clipboard text. Android only allows this while androidagent is the app in focus (its chat or "
                        + "bubble open); otherwise it returns an error.", new JSONObject()));
        t.put(tool("speak", "Read text aloud with the phone's text-to-speech voice. Returns immediately.",
                new JSONObject().put("text", prop("string", "What to say"))
                        .put("language", prop("string", "Optional BCP-47 language, e.g. pl-PL or en-US")),
                "text"));
        t.put(tool("set_brightness",
                "Set the screen brightness in percent, or switch to automatic brightness. Needs the 'Modify system "
                        + "settings' special access for androidagent; without it returns an error explaining how to grant it.",
                new JSONObject().put("percent", prop("integer", "0-100"))
                        .put("auto", prop("boolean", "true = automatic (adaptive) brightness; percent is then ignored"))));
        t.put(tool("vibrate", "Vibrate the phone briefly, e.g. to get the user's attention.",
                new JSONObject().put("ms", prop("integer", "Duration in milliseconds, 1-5000 (default 300)"))));
        return t;
    }

    private static JSONObject enumProp(String description, String... values) throws JSONException {
        JSONArray a = new JSONArray();
        for (String v : values) a.put(v);
        return new JSONObject().put("type", "string").put("enum", a).put("description", description);
    }

    @Override
    public boolean handles(String name) {
        return NAMES.contains(name);
    }

    @Override
    public JSONObject call(Context app, String caller, String name, JSONObject a) throws Exception {
        PhoneTools.activity(name);
        switch (name) {
            case "set_volume": return text(setVolume(app, a));
            case "get_volume": return result(getVolume(app));
            case "media_key": return text(mediaKey(app, a.getString("key")));
            case "torch": return text(torch(app, a.getBoolean("on")));
            case "clipboard_set": return text(clipboardSet(app, a.getString("text")));
            case "clipboard_get": return text(clipboardGet(app));
            case "speak": return text(speak(app, a.getString("text"), a.optString("language", "")));
            case "set_brightness": return text(setBrightness(app, a));
            case "vibrate": {
                long ms = Math.max(1, Math.min(5000, a.optLong("ms", 300)));
                vibrator(app).vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                return text("Vibrated for " + ms + " ms.");
            }
            default: throw new IllegalArgumentException("Unknown tool " + name);
        }
    }

    // --------------------------------------------------------------- volume

    private static int stream(String name) {
        for (int i = 0; i < STREAM_NAMES.length; i++) if (STREAM_NAMES[i].equals(name)) return STREAMS[i];
        throw new IllegalArgumentException("stream must be one of music, ring, alarm, notification");
    }

    private static String setVolume(Context app, JSONObject a) throws JSONException {
        AudioManager am = app.getSystemService(AudioManager.class);
        String name = a.getString("stream");
        int stream = stream(name);
        int min = am.getStreamMinVolume(stream), max = am.getStreamMaxVolume(stream);
        int level;
        if (a.has("level")) level = a.getInt("level");
        else if (a.has("percent")) level = Math.round(Math.max(0, Math.min(100, a.getInt("percent"))) * max / 100f);
        else throw new IllegalArgumentException("Give level (0.." + max + ") or percent (0-100).");
        level = Math.max(min, Math.min(max, level));
        if (am.isVolumeFixed()) throw new IllegalStateException("This device has a fixed volume.");
        try {
            am.setStreamVolume(stream, level, AudioManager.FLAG_SHOW_UI);
        } catch (SecurityException e) {
            throw new IllegalStateException("Android refused to change the " + name + " volume (Do Not Disturb is on and "
                    + "androidagent has no Do Not Disturb access). Ask the user to change it, or turn off Do Not Disturb.");
        }
        int now = am.getStreamVolume(stream);
        return "Set " + name + " volume to " + now + "/" + max + " (" + Math.round(now * 100f / Math.max(1, max)) + "%)."
                + (now != level ? " Android adjusted the requested level " + level + "." : "");
    }

    private static JSONObject getVolume(Context app) throws JSONException {
        AudioManager am = app.getSystemService(AudioManager.class);
        JSONObject out = new JSONObject();
        for (int i = 0; i < STREAMS.length; i++) {
            int s = STREAMS[i], cur = am.getStreamVolume(s), max = am.getStreamMaxVolume(s);
            out.put(STREAM_NAMES[i], new JSONObject().put("level", cur).put("min", am.getStreamMinVolume(s))
                    .put("max", max).put("percent", Math.round(cur * 100f / Math.max(1, max)))
                    .put("muted", am.isStreamMute(s)));
        }
        int mode = am.getRingerMode();
        out.put("ringer_mode", mode == AudioManager.RINGER_MODE_SILENT ? "silent"
                : mode == AudioManager.RINGER_MODE_VIBRATE ? "vibrate" : "normal");
        out.put("music_active", am.isMusicActive());
        return out;
    }

    private static String mediaKey(Context app, String key) {
        int code;
        switch (key) {
            case "play_pause": code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
            case "play": code = KeyEvent.KEYCODE_MEDIA_PLAY; break;
            case "pause": code = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "next": code = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "previous": code = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "stop": code = KeyEvent.KEYCODE_MEDIA_STOP; break;
            default: throw new IllegalArgumentException("key must be play_pause, play, pause, next, previous or stop");
        }
        AudioManager am = app.getSystemService(AudioManager.class);
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0));
        return "Sent media key " + key + "." + (am.isMusicActive() ? " Music is playing." : " No music is playing now.");
    }

    // ---------------------------------------------------------------- torch

    private static String torch(Context app, boolean on) throws CameraAccessException {
        CameraManager cm = app.getSystemService(CameraManager.class);
        String chosen = null;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flash == null || !flash) continue;
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (chosen == null || facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) chosen = id;
        }
        if (chosen == null) throw new IllegalStateException("This phone has no flashlight.");
        try {
            cm.setTorchMode(chosen, on);
        } catch (CameraAccessException e) {
            throw new IllegalStateException("The flashlight is busy (the camera is in use by another app).");
        }
        return on ? "Flashlight on." : "Flashlight off.";
    }

    // ------------------------------------------------------------ clipboard

    /** Run on the main thread and wait (clipboard and TTS objects belong to it). */
    static <T> T onMain(Callable<T> work) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return work.call();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<T> value = new AtomicReference<>();
        final AtomicReference<Exception> error = new AtomicReference<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                value.set(work.call());
            } catch (Exception e) {
                error.set(e);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("The app's main thread is busy; try again.");
        if (error.get() != null) throw error.get();
        return value.get();
    }

    private static String clipboardSet(Context app, String text) throws Exception {
        if (text.length() > 100000) throw new IllegalArgumentException("text is too long (max 100000 characters)");
        onMain(() -> {
            app.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("androidagent", text));
            return null;
        });
        return "Copied " + text.length() + " characters to the clipboard.";
    }

    private static String clipboardGet(Context app) throws Exception {
        ClipData clip = onMain(() -> app.getSystemService(ClipboardManager.class).getPrimaryClip());
        if (clip == null) {
            throw new IllegalStateException("Android only lets the app in focus read the clipboard, and androidagent is "
                    + "not in focus now (or the clipboard is empty). Ask the user to open the androidagent chat and retry, "
                    + "or to paste the text into the chat.");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clip.getItemCount(); i++) {
            CharSequence t = clip.getItemAt(i).coerceToText(app);
            if (t != null) sb.append(i > 0 ? "\n" : "").append(t);
        }
        if (sb.length() == 0) return "The clipboard holds no text.";
        return sb.length() > 32000 ? sb.substring(0, 32000) + "\n…(truncated)" : sb.toString();
    }

    // --------------------------------------------------------------- speech

    private static String speak(Context app, String text, String language) throws Exception {
        if (text.trim().isEmpty()) throw new IllegalArgumentException("text is empty");
        if (text.length() > 4000) throw new IllegalArgumentException("text is too long (max 4000 characters)");
        String problem = onMain(() -> Voice.speak(app, text, language.trim().isEmpty() ? null : language.trim()));
        return problem != null ? "Speaking, but " + problem : "Speaking.";
    }

    // ----------------------------------------------------------- brightness

    private static String setBrightness(Context app, JSONObject a) throws JSONException {
        if (!Settings.System.canWrite(app)) {
            throw new IllegalStateException("androidagent may not change system settings. Ask the user to allow "
                    + "'Modify system settings' for androidagent (call open_settings with panel 'write_settings' to "
                    + "open that screen), then retry.");
        }
        android.content.ContentResolver cr = app.getContentResolver();
        if (a.optBoolean("auto")) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            return "Automatic brightness on.";
        }
        if (!a.has("percent")) throw new IllegalArgumentException("Give percent (0-100) or auto=true.");
        int percent = Math.max(0, Math.min(100, a.getInt("percent")));
        int value = Math.max(1, Math.round(percent * 255 / 100f));
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, value);
        return "Brightness set to " + percent + "% (automatic brightness off).";
    }

    // ------------------------------------------------------------ vibration

    private static Vibrator vibrator(Context app) {
        Vibrator v = app.getSystemService(Vibrator.class);
        if (v == null || !v.hasVibrator()) throw new IllegalStateException("This phone cannot vibrate.");
        return v;
    }

    /** Short double buzz to get the user's attention (e.g. "tap Update"). */
    static void nudge(Context app) {
        try {
            Vibrator v = app.getSystemService(Vibrator.class);
            if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 60, 80, 60}, -1));
        } catch (RuntimeException ignored) {
        }
    }
}
