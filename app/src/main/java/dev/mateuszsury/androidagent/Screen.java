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
    }

    /** Start local tools from a visible screen, then wait for their loopback listener. */
    protected void withToolHost(Runnable action) {
        if (!resumed || isFinishing() || isDestroyed()) return;
        hostStartup.removeCallbacksAndMessages(null);
        if (McpHost.get(this).running()) { action.run(); return; }
        try { startForegroundService(new Intent(this, AgentService.class)); }
        catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        final long deadline = SystemClock.elapsedRealtime() + 5000;
        hostStartup.post(new Runnable() {
            @Override public void run() {
                if (!resumed || isFinishing() || isDestroyed()) return;
                if (McpHost.get(Screen.this).running()) action.run();
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
