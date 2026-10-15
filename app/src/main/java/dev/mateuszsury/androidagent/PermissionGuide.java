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
        // Agent tools: notification access, location, photos, installing APKs, system settings.
        final boolean notificationAccess;
        final boolean notificationAccessConnected;
        final boolean locationFine;
        final boolean locationCoarse;
        final boolean photos;
        final boolean installApps;
        final boolean writeSettings;

        State(boolean notifications, boolean microphone, boolean enabled, boolean connected, boolean exempt,
              boolean notificationAccess, boolean notificationAccessConnected, boolean locationFine,
              boolean locationCoarse, boolean photos, boolean installApps, boolean writeSettings) {
            this.notifications = notifications;
            this.microphone = microphone;
            this.accessibilityEnabled = enabled;
            this.accessibilityConnected = connected;
            this.batteryExempt = exempt;
            this.notificationAccess = notificationAccess;
            this.notificationAccessConnected = notificationAccessConnected;
            this.locationFine = locationFine;
            this.locationCoarse = locationCoarse;
            this.photos = photos;
            this.installApps = installApps;
            this.writeSettings = writeSettings;
        }

        /** How many of the listed consents are fully on (for the screen's subtitle). */
        int enabledCount() {
            boolean[] all = {notifications, microphone, accessibilityConnected, batteryExempt, notificationAccess,
                    locationCoarse, photos, installApps, writeSettings};
            int n = 0;
            for (boolean b : all) if (b) n++;
            return n;
        }

        static final int TOTAL = 9;
    }

    static State read(Context context) {
        boolean notifications = NotificationPermission.enabled(context)
                && (Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS));
        PowerManager power = context.getSystemService(PowerManager.class);
        boolean install;
        try { install = context.getPackageManager().canRequestPackageInstalls(); }
        catch (RuntimeException e) { install = false; }
        return new State(notifications, granted(context, Manifest.permission.RECORD_AUDIO),
                accessibilityEnabled(context), BuddyService.get() != null,
                power != null && power.isIgnoringBatteryOptimizations(context.getPackageName()),
                NotifListener.granted(context), NotifListener.get() != null,
                LocationTools.fineGranted(context), LocationTools.coarseGranted(context),
                MediaTools.photosAllowed(context), install, Settings.System.canWrite(context));
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

    /** androidagent's own page under Notification access (Android 11+), else the list of listeners. */
    static Intent notificationAccessIntent(Context context) {
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        NotifListener.component(context).flattenToString());
    }

    static Intent notificationAccessListIntent() {
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }

    /** "Install unknown apps" for androidagent only. */
    static Intent installAppsIntent(Context context) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName()));
    }

    /** "Modify system settings" for androidagent only. */
    static Intent writeSettingsIntent(Context context) {
        return new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.getPackageName()));
    }
}
