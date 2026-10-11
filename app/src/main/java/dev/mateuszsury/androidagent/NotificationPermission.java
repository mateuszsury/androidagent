package dev.mateuszsury.androidagent;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

/** Optional notification access. A refusal must never prevent foreground tools from running. */
final class NotificationPermission {
    static final int REQUEST = 902;
    private static final String PREFERENCES = "notification_permission";

    static boolean enabled(Context context) {
        return context.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    static boolean canRequest(Context context) {
        return Build.VERSION.SDK_INT >= 33
            && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean("requested", false);
    }

    /** Called from a visible screen after starting the feature, never as a prerequisite. */
    static void requestIfNeeded(Activity activity) {
        if (!canRequest(activity) || activity.isFinishing() || activity.isDestroyed()) return;
        // Persist before showing Android's dialog; rotation and returning from a denial must not repeat it.
        activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean("requested", true).apply();
        try { activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST); }
        catch (RuntimeException ignored) { /* Native notification settings remain available. */ }
    }

    static Intent settingsIntent(Context context) {
        return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
    }
}
