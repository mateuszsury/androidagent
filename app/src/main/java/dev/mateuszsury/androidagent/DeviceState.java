package dev.mateuszsury.androidagent;

import android.app.ActivityManager;
import android.content.*;
import android.net.*;
import android.os.*;
import org.json.JSONObject;

/** Only public, permission-free device metrics; no serial numbers or personal data. */
final class DeviceState {
  static JSONObject read(Context c) throws Exception {
    Intent battery = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    int level = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
    int scale = battery == null ? 100 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
    ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
    ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
    StatFs disk = new StatFs(c.getFilesDir().getAbsolutePath());
    ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
    NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
    String network =
        nc == null
            ? "offline"
            : nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                ? "Wi-Fi"
                : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    ? "sieć komórkowa"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        ? "Ethernet"
                        : "połączono";
    return new JSONObject()
        .put("model", Build.MANUFACTURER + " " + Build.MODEL)
        .put("android", Build.VERSION.RELEASE)
        .put("api", Build.VERSION.SDK_INT)
        .put("battery_percent", level < 0 ? -1 : level * 100 / Math.max(1, scale))
        .put(
            "charging",
            battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        .put("ram_available_bytes", memory.availMem)
        .put("ram_total_bytes", memory.totalMem)
        .put("storage_available_bytes", disk.getAvailableBytes())
        .put("storage_total_bytes", disk.getTotalBytes())
        .put("uptime_seconds", SystemClock.elapsedRealtime() / 1000)
        .put("network", network)
        .put("accessibility_connected", BuddyService.get() != null)
        .put("termux_installed", Termux.installed(c))
        .put("termux_permission", Termux.permitted(c))
        .put("shell_identity", "app UID " + android.os.Process.myUid() + " (bez roota)");
  }

  static String gb(long bytes) {
    return String.format(java.util.Locale.forLanguageTag("pl"), "%.1f GB", bytes / 1073741824.0);
  }

  static String summary(Context c) throws Exception {
    JSONObject j = read(c);
    return j.getString("model")
        + " · Android "
        + j.getString("android")
        + "\n\nBateria: "
        + j.getInt("battery_percent")
        + "%"
        + (j.getBoolean("charging") ? " · ładowanie" : "")
        + "\nWolna pamięć RAM: "
        + gb(j.getLong("ram_available_bytes"))
        + " / "
        + gb(j.getLong("ram_total_bytes"))
        + "\nWolne miejsce: "
        + gb(j.getLong("storage_available_bytes"))
        + "\nSieć: "
        + j.getString("network")
        + "\nCzas działania: "
        + j.getLong("uptime_seconds") / 60
        + " min\n\nSterowanie ekranem: "
        + (j.getBoolean("accessibility_connected") ? "aktywne" : "wyłączone")
        + "\nTermux: "
        + (j.getBoolean("termux_installed") ? "zainstalowany" : "brak")
        + "\n\nOdczyt lokalny · bez zapytania do modelu";
  }
}
