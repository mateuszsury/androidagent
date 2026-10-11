package dev.mateuszsury.androidagent;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real Android 13+ permission-controller regression, run before other foreground-service fixtures. */
final class NotificationRegressionChecks {
    static void freshConsentDenialKeepsToolsRunning(Instrumentation instrumentation, Activity screen,
                                                   UiAutomation ui) throws Exception {
        require(Build.VERSION.SDK_INT >= 33, "Run this regression only on API 33+");
        Context app = instrumentation.getTargetContext();
        require(screen instanceof Screen, "Expected a visible androidagent screen");
        require(!AgentService.active && !McpHost.get(app).running(), "Run before service fixtures");
        require(app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_DENIED && NotificationPermission.canRequest(app),
            "Requires fresh consent: host-prepared denial/permission flags and an unanswered app preference");
        require(!NotificationPermission.enabled(app), "Notifications unexpectedly enabled before fresh consent");
        android.accessibilityservice.AccessibilityServiceInfo serviceInfo = ui.getServiceInfo();
        int originalFlags = serviceInfo.flags;
        serviceInfo.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        ui.setServiceInfo(serviceInfo);
        AtomicBoolean actionRan = new AtomicBoolean();
        try {
            onMain(instrumentation, () -> ((Screen) screen).withToolHost(() -> actionRan.set(true)));
            AccessibilityNodeInfo deny = waitForDeny(ui, 15000);
            require(deny != null, "Starting tools did not show Android's notification permission dialog");
            require(actionRan.get(), "Permission dialog blocked the requested tool action");
            require(AgentService.active && McpHost.get(app).running() && foreground(app),
                "Foreground tools failed to start before permission was answered");
            try { require(deny.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Cannot decline notification permission"); }
            finally { deny.recycle(); }
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (!screen.hasWindowFocus() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            instrumentation.waitForIdleSync();
            require(screen.hasWindowFocus(), "Permission dialog did not return to the app");
            require(app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_DENIED && !NotificationPermission.enabled(app),
                "Deny did not leave notifications disabled");
            require(AgentService.active && McpHost.get(app).running() && foreground(app),
                "Deny stopped the foreground tools");
            require(!NotificationPermission.canRequest(app), "Deny will trigger a repeated permission prompt");
            AtomicBoolean secondAction = new AtomicBoolean();
            onMain(instrumentation, () -> ((Screen) screen).withToolHost(() -> secondAction.set(true)));
            require(secondAction.get(), "Notification refusal blocked later tool actions");
            AccessibilityNodeInfo repeated = waitForDeny(ui, 750);
            if (repeated != null) repeated.recycle();
            require(repeated == null, "A denied permission dialog was forced again");
            Intent settings = NotificationPermission.settingsIntent(app);
            require(Settings.ACTION_APP_NOTIFICATION_SETTINGS.equals(settings.getAction())
                && app.getPackageName().equals(settings.getStringExtra(Settings.EXTRA_APP_PACKAGE))
                && settings.resolveActivity(app.getPackageManager()) != null,
                "Notification settings do not resolve to this app's native settings");
        } finally {
            app.stopService(new Intent(app, AgentService.class));
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (AgentService.active && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            serviceInfo.flags = originalFlags;
            ui.setServiceInfo(serviceInfo);
        }
    }

    private static boolean foreground(Context app) {
        for (ActivityManager.RunningServiceInfo service :
                app.getSystemService(ActivityManager.class).getRunningServices(32))
            if (AgentService.class.getName().equals(service.service.getClassName())) return service.foreground;
        return false;
    }

    private static AccessibilityNodeInfo waitForDeny(UiAutomation ui, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            if (root != null) {
                try {
                    for (String controller : new String[]{"com.android.permissioncontroller", "com.google.android.permissioncontroller"}) {
                        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(controller + ":id/permission_deny_button");
                        AccessibilityNodeInfo result = null;
                        for (AccessibilityNodeInfo node : nodes) {
                            if (result == null && node.isVisibleToUser() && node.isClickable()) result = node;
                            else node.recycle();
                        }
                        if (result != null) return result;
                    }
                } finally { root.recycle(); }
            }
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        return null;
    }

    private static void onMain(Instrumentation instrumentation, Runnable action) throws Exception {
        final Throwable[] failure = {null};
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { failure[0] = e; } });
        if (failure[0] instanceof Error) throw (Error) failure[0];
        if (failure[0] instanceof Exception) throw (Exception) failure[0];
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
