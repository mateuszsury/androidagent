package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Optional Android consents; opening this screen never requests or grants a permission. */
public class PermissionsActivity extends Screen {
    private static final int MICROPHONE_REQUEST = 903, LOCATION_REQUEST = 906, PHOTOS_REQUEST = 907;
    private static final String PREFERENCES = "permission_guide";
    private LinearLayout cards;
    private ScrollView scroll;
    private TextView subtitle;
    private boolean entered;

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        LinearLayout bar = topBar(this, "权限", "Android 授权");
        subtitle = (TextView) ((LinearLayout) ((LinearLayout) bar.getChildAt(0)).getChildAt(1)).getChildAt(1);
        root.addView(bar, full());
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        cards = column(this);
        cards.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 28));
        scroll.addView(cards);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    // The explicit buttons below own notification consent on this screen.
    @Override protected void offerNotifications() {}

    @Override protected void onNotificationPermissionChanged() { refresh(); }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MICROPHONE_REQUEST || request == LOCATION_REQUEST || request == PHOTOS_REQUEST) refresh();
    }

    private void refresh() {
        if (cards == null || isDestroyed()) return;
        int position = scroll.getScrollY();
        PermissionGuide.State state = PermissionGuide.read(this);
        subtitle.setText(state.enabledCount() + " z " + PermissionGuide.State.TOTAL + "已启用 · Android 权限");
        cards.removeAllViews();
        paragraph(cards,
                "终端和对话无需屏幕和麦克风访问即可工作。"
                        + "登录模型账户是单独的步骤。 "
                        + "你需要自行在 Android 窗口中开启权限。", MUTED);

        section("应用");
        card(R.drawable.ms_notifications, "Powiadomienia", state.notifications ? "已开启" : "已关闭 · 可选",
                state.notifications ? OK : FAINT,
                "它们显示活动任务和停止按钮。拒绝不会阻止对话或工具。"
                        + "Android 可能仍在其面板中显示该服务处于活动状态。",
                state.notifications ? "通知设置" : "开启通知",
                () -> {
                    if (NotificationPermission.canRequest(this)) NotificationPermission.requestIfNeeded(this);
                    else launch(NotificationPermission.settingsIntent(this), PermissionGuide.appSettingsIntent(this));
                }, null);
        card(R.drawable.ms_mic, "Mikrofon", state.microphone ? "Dozwolony" : "已关闭 · 可选",
                state.microphone ? OK : FAINT,
                "只需用于听写。你始终可以用键盘输入。"
                        + "听写还需要手机上有可用的语音识别服务。",
                state.microphone ? "麦克风设置" : "允许麦克风",
                () -> requestRuntime("microphone_requested", new String[]{Manifest.permission.RECORD_AUDIO},
                        MICROPHONE_REQUEST, state.microphone), null);
        String accessibilityStatus = state.accessibilityConnected ? "已连接"
                : state.accessibilityEnabled ? "已开启 · 等待连接" : "已关闭 · 可选";
        card(R.drawable.ms_touch_app, "屏幕控制", accessibilityStatus,
                state.accessibilityConnected ? OK : state.accessibilityEnabled ? WARN : FAINT,
                "无障碍服务允许代理读取屏幕、点击和滚动。 "
                        + "请在设置中选择 androidagent 并确认授权。 "
                        + "其余无障碍服务仍由你控制。",
                "无障碍设置",
                () -> launch(PermissionGuide.accessibilitySettingsIntent(), PermissionGuide.appSettingsIntent(this)),
                state.accessibilityEnabled ? null : restrictedNote(
                        "如果 Android 在安装 APK 后阻止此授权，请打开“应用信息”"
                                + "如果该选项可用，请在三点菜单中选择“允许受限设置”。 "
                                + "然后返回无障碍设置。"));
        card(R.drawable.ms_battery_charging_full, "长任务与电池",
                state.batteryExempt ? "Bez optymalizacji baterii" : "优化已开启", state.batteryExempt ? OK : FAINT,
                "对于长对话、编译和 Docker 机器，你可以允许应用在后台运行。"
                        + "即使如此，Android 或设备厂商仍可能在内存不足时停止该进程。"
                        + "如果出现问题，请也在应用信息中检查后台运行限制。",
                state.batteryExempt ? "电池设置" : "允许后台运行",
                () -> {
                    if (state.batteryExempt) launch(PermissionGuide.batteryListIntent(), PermissionGuide.appSettingsIntent(this));
                    else launch(PermissionGuide.batterySettingsIntent(this), PermissionGuide.batteryListIntent());
                }, null);

        section("代理工具");
        String access = state.notificationAccessConnected ? "已启用"
                : state.notificationAccess ? "已启用 · 等待连接" : "已关闭 · 可选";
        card(R.drawable.ms_notifications_active, "通知访问权限", access,
                state.notificationAccessConnected ? OK : state.notificationAccess ? WARN : FAINT,
                "代理可以读取通知的标题和内容、打开通知、点击其按钮或关闭通知，"
                        + "当你在对话中提出要求时。通知内容对代理来说永远不是命令。",
                state.notificationAccess ? "通知访问设置" : "启用通知访问",
                () -> launch(PermissionGuide.notificationAccessIntent(this), PermissionGuide.notificationAccessListIntent()),
                state.notificationAccess || Build.VERSION.SDK_INT < 33 ? null : restrictedNote(
                        "Android 可能会将此设置标记为对 APK 应用的受限设置。"
                                + "然后在应用信息的三点菜单中选择 "
                                + "请允许受限设置。"));
        boolean located = state.locationCoarse;
        card(R.drawable.ms_location_on, "Lokalizacja",
                state.locationFine ? "精确" : located ? "大致" : "已关闭 · 可选", located ? OK : FAINT,
                "当你要求天气或附近路线时，代理会检查手机位置。"
                        + "仅在应用打开时运行。Android 会询问是否共享精确位置或大致位置。",
                located ? "定位设置" : "允许定位",
                () -> requestRuntime("location_requested", new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST, located), null);
        card(R.drawable.ms_photo_library, "照片", state.photos ? "Dozwolone" : "已关闭 · 可选",
                state.photos ? OK : FAINT,
                "代理可以浏览图库中最新的照片，并把选中的照片复制到自己的工作目录。"
                        + "使用相机拍照不需要此授权：照片始终由你自己拍摄。",
                state.photos ? "照片设置" : "允许照片访问",
                () -> requestRuntime("photos_requested", new String[]{MediaTools.photoPermission()}, PHOTOS_REQUEST,
                        state.photos), null);
        card(R.drawable.ms_install_mobile, "正在安装应用", state.installApps ? "Dozwolone" : "已关闭 · 可选",
                state.installApps ? OK : FAINT,
                "允许为代理构建的应用打开 Android 安装器。"
                        + "每次安装和更新都需要你在 Android 窗口中自行确认。",
                state.installApps ? "安装设置" : "允许安装",
                () -> launch(PermissionGuide.installAppsIntent(this), PermissionGuide.appSettingsIntent(this)), null);
        card(R.drawable.ms_settings, "更改系统设置", state.writeSettings ? "Dozwolona" : "已关闭 · 可选",
                state.writeSettings ? OK : FAINT,
                "只有修改系统设置（例如屏幕亮度）的工具才需要它。"
                        + "它不会提供对密码或 Android 受保护设置的访问。",
                state.writeSettings ? "应用系统设置" : "允许更改设置",
                () -> launch(PermissionGuide.writeSettingsIntent(this), PermissionGuide.appSettingsIntent(this)), null);

        TextView footer = paragraph(cards,
                "文件和 USB：只有在选择文件夹或具体设备后才会出现授权。"
                        + "无需共享整个手机存储。"
                        + "终端和对话不需要 root，也不需要安装其他应用的授权。", FAINT);
        footer.setPadding(dp(this, 4), dp(this, 10), dp(this, 4), 0);
        if (!entered) {
            entered = true;
            Motion.stagger(cards, 0, 28);
        }
        scroll.post(() -> scroll.scrollTo(0, position));
    }

    /** Asks Android once (and again only while it offers a rationale); afterwards opens App info. */
    private void requestRuntime(String key, String[] permissions, int request, boolean granted) {
        SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        boolean requested = preferences.getBoolean(key, false);
        if (!granted && (!requested || shouldShowRequestPermissionRationale(permissions[0]))) {
            preferences.edit().putBoolean(key, true).apply();
            try { requestPermissions(permissions, request); }
            catch (RuntimeException e) { launch(PermissionGuide.appSettingsIntent(this), null); }
        } else launch(PermissionGuide.appSettingsIntent(this), null);
    }

    private void launch(Intent preferred, Intent fallback) {
        try { startActivity(preferred); }
        catch (RuntimeException e) {
            if (fallback != null) {
                try { startActivity(fallback); return; }
                catch (RuntimeException ignored) {}
            }
            Toast.makeText(this, "这些设置不可用。请在 Android 设置中打开 androidagent 的应用信息。",
                    Toast.LENGTH_LONG).show();
        }
    }

    private Button actionButton(String label, boolean emphasized) {
        Button action = emphasized ? outlinedButton(this, label, ACCENT_TEXT) : button(this, label, false);
        action.setSingleLine(false);
        action.setEllipsize(null);
        action.setMinHeight(dp(this, 44));
        action.setMinimumHeight(dp(this, 44));
        return action;
    }

    private void section(String label) {
        TextView t = sectionLabel(this, label);
        t.setPadding(dp(this, 4), dp(this, 16), dp(this, 4), dp(this, 8));
        cards.addView(t, full());
    }

    private void card(int icon, String name, String status, int tone, String explanation, String label, Runnable action,
                      View extra) {
        boolean active = tone == OK;
        LinearLayout card = Ui.card(this);
        card.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 16));
        LinearLayout head = row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(rounded(this, tone == FAINT ? CARD2 : Theme.withAlpha(tone, 0x24), 12));
        ImageView glyph = Ui.icon(this, icon, tone == FAINT ? MUTED : tone, 22);
        tile.addView(glyph, new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        head.addView(tile, new LinearLayout.LayoutParams(dp(this, 42), dp(this, 42)));
        LinearLayout texts = column(this);
        TextView heading = title(this, name, 16);
        heading.setAccessibilityHeading(true);
        texts.addView(heading, full());
        LinearLayout pill = statusPill(this, status, tone);
        TextView current = (TextView) pill.getChildAt(1);
        current.setSingleLine(false);
        current.setMaxLines(2);
        current.setEllipsize(null);
        current.setTextColor(tone == FAINT ? MUTED : TEXT);
        current.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams pillParams = wrap();
        pillParams.topMargin = dp(this, 6);
        texts.addView(pill, pillParams);
        LinearLayout.LayoutParams textParams = weight1();
        textParams.leftMargin = dp(this, 14);
        head.addView(texts, textParams);
        card.addView(head, full());
        TextView about = paragraph(card, explanation, MUTED);
        about.setPadding(0, dp(this, 12), 0, 0);
        Button button = actionButton(label, !active);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams buttonParams = full();
        buttonParams.topMargin = dp(this, 14);
        card.addView(button, buttonParams);
        if (extra != null) {
            LinearLayout.LayoutParams extraParams = full();
            extraParams.topMargin = dp(this, 12);
            card.addView(extra, extraParams);
        }
        LinearLayout.LayoutParams params = full();
        params.bottomMargin = dp(this, 10);
        cards.addView(card, params);
    }

    /** A quiet inset for Android's "restricted settings" step, with a route to App info. */
    private View restrictedNote(String text) {
        LinearLayout box = column(this);
        box.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 12));
        box.setBackground(outlined(this, Theme.withAlpha(WARN, 0x10), 10, Theme.withAlpha(WARN, 0x30), 1));
        TextView note = text(this, text, 12, MUTED);
        note.setLineSpacing(dp(this, 2), 1);
        box.addView(note, full());
        Button details = actionButton("应用信息", false);
        details.setTextSize(13);
        details.setOnClickListener(v -> launch(PermissionGuide.appSettingsIntent(this), null));
        LinearLayout.LayoutParams params = wrap();
        params.topMargin = dp(this, 10);
        box.addView(details, params);
        return box;
    }

    private TextView paragraph(LinearLayout parent, String value, int color) {
        TextView description = text(this, value, 13, color);
        description.setLineSpacing(dp(this, 3), 1);
        description.setPadding(dp(this, 4), 0, dp(this, 4), dp(this, 4));
        parent.addView(description, full());
        return description;
    }
}
