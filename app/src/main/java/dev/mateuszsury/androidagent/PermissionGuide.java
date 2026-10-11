package dev.mateuszsury.androidagent;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

/** Read-only permission state and native settings routes; Android owns every consent decision. */
public final class PermissionGuide {
    private PermissionGuide() {}

    public static void open(Context context) {
        Intent intent = new Intent(context, PermissionsActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    static final class State {
        final boolean notifications;
        final boolean microphone;
        final boolean accessibilityEnabled;
        final boolean accessibilityConnected;
        final boolean batteryExempt;

        State(boolean notifications, boolean microphone, boolean enabled, boolean connected, boolean exempt) {
            this.notifications = notifications;
            this.microphone = microphone;
            this.accessibilityEnabled = enabled;
            this.accessibilityConnected = connected;
            this.batteryExempt = exempt;
        }
    }

    static State read(Context context) {
        boolean notifications = NotificationPermission.enabled(context)
                && (Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS));
        PowerManager power = context.getSystemService(PowerManager.class);
        return new State(notifications, granted(context, Manifest.permission.RECORD_AUDIO),
                accessibilityEnabled(context), BuddyService.get() != null,
                power != null && power.isIgnoringBatteryOptimizations(context.getPackageName()));
    }

    private static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isOwnAccessibilityService(Context context, ServiceInfo service) {
        return service != null && service.packageName != null && service.name != null
                && new ComponentName(context, BuddyService.class).equals(
                new ComponentName(service.packageName,
                        service.name.startsWith(".") ? service.packageName + service.name : service.name));
    }

    private static boolean accessibilityEnabled(Context context) {
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        if (manager == null || !manager.isEnabled()) return false;
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() != null && isOwnAccessibilityService(context, info.getResolveInfo().serviceInfo))
                return true;
        }
        return false;
    }

    static Intent appSettingsIntent(Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName()));
    }

    static Intent accessibilitySettingsIntent() {
        // Open Android's service picker. Never replace the user's list of enabled services.
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }

    static Intent batterySettingsIntent(Context context) {
        return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + context.getPackageName()));
    }

    static Intent batteryListIntent() {
        return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    }
}
