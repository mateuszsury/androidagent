package dev.mateuszsury.androidagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.List;

/**
 * Foreground owner of the background services: it runs while at least one service is starting or
 * running, keeps the CPU (partial wake lock) and Wi-Fi awake so servers answer with the screen off,
 * and shows "Usługi w tle: N działają" with a "Zatrzymaj wszystkie" action.
 */
public final class ServicesService extends Service {
    static final String STOP_ALL = "dev.mateuszsury.androidagent.services.STOP_ALL";
    private static final int NOTICE = 931;
    private static volatile ServicesService current;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private boolean foreground;

    static boolean running() {
        return current != null;
    }

    /** Update the notification, or stop when nothing runs any more. Main thread. */
    static void refresh() {
        ServicesService s = current;
        if (s != null) s.update();
    }

    @Override public void onCreate() {
        super.onCreate();
        current = this;
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "androidagent:services");
        wake.setReferenceCounted(false);
        try {
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            // Deprecated on API 34+, where Android maps it to the low-latency lock; still the right request.
            @SuppressWarnings("deprecation") int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            if (wm != null) {
                wifi = wm.createWifiLock(mode, "androidagent:services");
                wifi.setReferenceCounted(false);
            }
        } catch (RuntimeException ignored) {
            wifi = null;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!showForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ServiceManager manager = ServiceManager.get(this);
        if (intent == null) {
            // Sticky restart after Android killed the process: bring autostart services back first.
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            manager.async(() -> {
                manager.restore();
                main.post(this::update);
            });
            return START_STICKY;
        }
        if (STOP_ALL.equals(intent.getAction())) manager.async(manager::stopAll);
        update();
        // Sticky: if Android kills the process, it recreates this service and autostart services return.
        return START_STICKY;
    }

    private boolean showForeground() {
        try {
            Notification n = build(ServiceManager.get(this).active());
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTICE, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTICE, n);
            foreground = true;
            return true;
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException: started from the background without an exemption.
            return false;
        }
    }

    private void update() {
        if (current != this) return; // destroyed meanwhile
        List<ServiceManager.Status> active = ServiceManager.get(this).active();
        if (active.isEmpty()) {
            if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
            stopSelf();
            return;
        }
        if (!wake.isHeld()) wake.acquire();
        if (wifi != null && !wifi.isHeld()) {
            try { wifi.acquire(); } catch (RuntimeException ignored) { }
        }
        if (foreground) getSystemService(NotificationManager.class).notify(NOTICE, build(active));
    }

    private Notification build(List<ServiceManager.Status> active) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("services", "Usługi w tle", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, NOTICE, new Intent(this, ServicesActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, NOTICE + 1, new Intent(this, ServicesService.class).setAction(STOP_ALL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        StringBuilder names = new StringBuilder(), lines = new StringBuilder();
        for (ServiceManager.Status st : active) {
            int port = st.mainPort();
            names.append(names.length() == 0 ? "" : " · ").append(st.def.name).append(port != 0 ? " :" + port : "");
            String url = st.urls.isEmpty() ? "" : st.urls.get(st.urls.size() - 1)[0];
            lines.append(lines.length() == 0 ? "" : "\n").append(st.def.name)
                    .append(ServiceManager.STARTING.equals(st.state) ? " · uruchamianie" : "")
                    .append(url.isEmpty() ? "" : " · " + url);
        }
        String title = active.isEmpty() ? "Usługi w tle" : "Usługi w tle: " + ServiceManager.runningLabel(active.size());
        return new Notification.Builder(this, "services")
                .setSmallIcon(R.drawable.ms_terminal)
                .setContentTitle(title)
                .setContentText(names.length() == 0 ? "Zatrzymywanie…" : names.toString())
                .setStyle(new Notification.BigTextStyle().bigText(lines.length() == 0 ? "Zatrzymywanie…" : lines.toString()))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Zatrzymaj wszystkie", stop).build())
                .build();
    }

    @Override public void onDestroy() {
        current = null;
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) {
            try { wifi.release(); } catch (RuntimeException ignored) { }
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
