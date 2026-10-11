package dev.mateuszsury.androidagent;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

/** Native permission guidance checks. Never grant/revoke consent or rewrite accessibility settings. */
final class PermissionRegressionChecks {
    static void readOnlyStateAndNativeRoutes(Context app) throws Exception {
        String enabled = Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        int accessibility = Settings.Secure.getInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        PermissionGuide.State state = PermissionGuide.read(app);
        require(state.microphone == (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
                "Microphone state differs from Android's permission");
        require(state.notifications == (app.getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()
                        && (Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED)), "Notification state differs from Android's permission");
        require(state.batteryExempt == app.getSystemService(PowerManager.class)
                        .isIgnoringBatteryOptimizations(app.getPackageName()), "Battery exemption state differs from Android");
        Intent access = PermissionGuide.accessibilitySettingsIntent();
        require(Settings.ACTION_ACCESSIBILITY_SETTINGS.equals(access.getAction()) && access.resolveActivity(app.getPackageManager()) != null,
                "Accessibility route is not a supported native settings screen");
        Intent battery = PermissionGuide.batterySettingsIntent(app);
        require(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.equals(battery.getAction())
                        && ("package:" + app.getPackageName()).equals(battery.getDataString())
                        && battery.resolveActivity(app.getPackageManager()) != null,
                "Battery consent route is not scoped to androidagent");
        Intent details = PermissionGuide.appSettingsIntent(app);
        require(Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(details.getAction())
                        && ("package:" + app.getPackageName()).equals(details.getDataString())
                        && details.resolveActivity(app.getPackageManager()) != null,
                "Fallback settings point to a different app");
        ActivityInfo activity = app.getPackageManager().getActivityInfo(
                new android.content.ComponentName(app, PermissionsActivity.class), 0);
        require(!activity.exported, "The consent guide is exported to other apps");
        require(java.util.Objects.equals(enabled, Settings.Secure.getString(app.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                        && accessibility == Settings.Secure.getInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0),
                "Reading permission state changed the user's accessibility services");
    }

    static void accessibilityRecognitionIsScoped(Context app) {
        ServiceInfo service = new ServiceInfo();
        service.packageName = "app.flowmesh.alpha";
        service.name = "app.flowmesh.platform.FlowMeshAccessibilityService";
        require(!PermissionGuide.isOwnAccessibilityService(app, service),
                "Another enabled accessibility service is mistaken for androidagent");
        service.packageName = app.getPackageName();
        service.name = ".BuddyService";
        require(PermissionGuide.isOwnAccessibilityService(app, service), "A relative own-service component is not recognized");
        service.name = BuddyService.class.getName();
        require(PermissionGuide.isOwnAccessibilityService(app, service), "The canonical own service is not recognized");
        service.name = null;
        require(!PermissionGuide.isOwnAccessibilityService(app, service)
                        && !PermissionGuide.isOwnAccessibilityService(app, null), "Invalid components are accepted");
    }

    static void screenRefreshAndNativeAccessibilityRoute(Instrumentation instrumentation, Activity screen) throws Exception {
        require(screen instanceof PermissionsActivity, "Expected the permission guide screen");
        Context app = instrumentation.getTargetContext();
        String services = Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        int notifications = app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS);
        int microphone = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO);
        boolean battery = app.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(app.getPackageName());
        onMain(instrumentation, () -> {
            View root = screen.getWindow().getDecorView();
            require(find(root, "Powiadomienia") != null && find(root, "Mikrofon") != null
                            && find(root, "Sterowanie ekranem") != null && find(root, "Długie zadania i bateria") != null,
                    "The guide omits an optional permission");
            instrumentation.callActivityOnPause(screen);
            instrumentation.callActivityOnResume(screen);
            root = screen.getWindow().getDecorView();
            PermissionGuide.State state = PermissionGuide.read(app);
            require(find(root, state.batteryExempt ? "Bez optymalizacji baterii" : "Optymalizacja aktywna") != null,
                    "Returning from Android settings did not refresh battery state");
            String accessibilityStatus = state.accessibilityConnected ? "Połączone"
                    : state.accessibilityEnabled ? "Włączone · oczekiwanie na połączenie" : "Wyłączone · opcjonalne";
            require(find(root, accessibilityStatus) != null, "Returning from settings did not refresh accessibility state");
            View accessibility = find(root, "Ustawienia ułatwień dostępu");
            require(accessibility instanceof Button && ((Button) accessibility).getEllipsize() == null,
                    "Accessibility settings label can be truncated at large font sizes");
        });
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                new IntentFilter(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        try {
            onMain(instrumentation, () -> find(screen.getWindow().getDecorView(), "Ustawienia ułatwień dostępu").performClick());
            require(monitor.getHits() == 1, "Accessibility button did not open the native settings route");
        } finally { instrumentation.removeMonitor(monitor); }
        require(java.util.Objects.equals(services, Settings.Secure.getString(app.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)), "The guide replaced existing accessibility services");
        require(notifications == app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        && microphone == app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        && battery == app.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(app.getPackageName()),
                "Opening/resuming the guide silently changed consent");
    }

    private static View find(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View result = find(parent.getChildAt(i), value);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void onMain(Instrumentation instrumentation, Runnable action) throws Exception {
        Throwable[] error = {null};
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable failed) { error[0] = failed; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
