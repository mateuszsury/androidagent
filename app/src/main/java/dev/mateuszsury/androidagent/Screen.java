package dev.mateuszsury.androidagent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

/** Base for Buddy's screens: applies the theme and rebuilds when the appearance settings change. */
abstract class Screen extends Activity {
    private String themeKey;
    private boolean resumed;
    private final Handler hostStartup = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        Theme.apply(this);
        themeKey = Theme.key();
        super.onCreate(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Theme.load(this);
        if (!Theme.key().equals(themeKey)) recreate();
        else offerActiveNotifications();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) offerActiveNotifications();
    }

    private void offerActiveNotifications() {
        // Includes returning from Android's accessibility settings and an already running USB host.
        if (McpHost.get(this).running() || BuddyService.get() != null) offerNotifications();
    }

    protected void offerNotifications() {
        getWindow().getDecorView().post(() -> {
            if (resumed && hasWindowFocus() && !isFinishing() && !isDestroyed())
                NotificationPermission.requestIfNeeded(this);
        });
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == NotificationPermission.REQUEST) onNotificationPermissionChanged();
    }

    protected void onNotificationPermissionChanged() {}

    /** Start local tools from a visible screen, then wait for their loopback listener. */
    protected void withToolHost(Runnable action) {
        if (!resumed || isFinishing() || isDestroyed()) return;
        hostStartup.removeCallbacksAndMessages(null);
        // A live socket can belong only to a short setup job. Give terminal
        // and browser-login sessions a lasting foreground owner before that
        // job finishes, even when the listener is already available.
        try { if (!AgentService.active) startForegroundService(new Intent(this, AgentService.class)); }
        catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        if (McpHost.get(this).running()) { action.run(); offerNotifications(); return; }
        final long deadline = SystemClock.elapsedRealtime() + 5000;
        hostStartup.post(new Runnable() {
            @Override public void run() {
                if (!resumed || isFinishing() || isDestroyed()) return;
                if (McpHost.get(Screen.this).running()) { action.run(); offerNotifications(); }
                else if (SystemClock.elapsedRealtime() < deadline) hostStartup.postDelayed(this, 100);
                else Toast.makeText(Screen.this, "Nie udało się uruchomić narzędzi. Spróbuj ponownie.", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override protected void onPause() {
        resumed = false;
        hostStartup.removeCallbacksAndMessages(null);
        super.onPause();
    }

    /** Re-apply the theme in place (after a change made on this screen). */
    protected void rethemed() {
        Theme.apply(this);
        themeKey = Theme.key();
    }
}
