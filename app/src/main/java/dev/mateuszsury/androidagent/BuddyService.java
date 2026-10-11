package dev.mateuszsury.androidagent;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Accessibility service: draws the Buddy bubble, hosts the local MCP server,
 * and performs the screen actions the agent (Claude Code or Codex) asks for.
 */
public class BuddyService extends AccessibilityService implements McpServer.Handler {
    private static volatile BuddyService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Bubble bubble;
    private ControlIndicator control;
    /** After the user taps Stop on the control indicator, phone actions are refused until then (elapsedRealtime). */
    private volatile long revokedUntil;
    private Tools tools;
    private McpServer server;

    static BuddyService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Theme.load(this);
        Models.setCodex(Prefs.codexModels(this));
        new Thread(() -> Attachments.cleanup(this)).start();
        tools = new Tools(this);
        bubble = new Bubble(this);
        control = new ControlIndicator(this);
        bubble.show();
        McpHost.get(this).acquire(this);

        // Hide the bubble on the lock screen.
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(lockReceiver, f);
        bubble.setLocked(getSystemService(KeyguardManager.class).isKeyguardLocked());
        // Reconnecting Accessibility should preserve the user's current screen.
        if (System.currentTimeMillis() < Prefs.setupWatchUntil(this)) watchSetup();
    }

    private final BroadcastReceiver lockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (bubble == null) return;
            if (Intent.ACTION_USER_PRESENT.equals(i.getAction())) bubble.setLocked(false);
            else if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) bubble.setLocked(true);
            else bubble.setLocked(getSystemService(KeyguardManager.class).isKeyguardLocked());
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (bubble == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        int top = 0;
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                Rect r = new Rect();
                w.getBoundsInScreen(r);
                if (!r.isEmpty()) top = r.top;
            }
        }
        bubble.onKeyboardTop(top);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(lockReceiver);
        } catch (Exception ignored) {
        }
        if (tools != null) {
            Tools oldTools = tools;
            new Thread(()->{oldTools.cancelLocalCommands();oldTools.clearNodes();}, "androidagent-service-stop").start();
        }
        McpHost.get(this).release(this);
        if (bubble != null) bubble.remove();
        if (control != null) control.end();
        instance = null;
        super.onDestroy();
    }

    Handler mainHandler() {
        return main;
    }

    /** Show or hide the bubble after the setting changes in the app. */
    void applyBubbleSetting() {
        main.post(() -> {
            if (bubble != null) bubble.applyEnabled();
        });
    }

    /** Restyle the bubble after the appearance settings (or the system dark mode) change. */
    void applyTheme() {
        main.post(() -> {
            if (bubble != null) bubble.applyTheme();
        });
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (Theme.load(this)) applyTheme();
    }

    // ------------------------------------------------- first-run guide

    private final Runnable setupWatch = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() > Prefs.setupWatchUntil(BuddyService.this)) return;
            try {
                Termux.run(BuddyService.this, "ping");
            } catch (Exception ignored) {
            }
            main.postDelayed(this, 3000);
        }
    };

    /** While the user works in Termux during setup, keep checking and bring the guide back when the step is done. */
    void watchSetup() {
        main.removeCallbacks(setupWatch);
        main.postDelayed(setupWatch, 2000);
    }

    private void onSetupPong() {
        if (System.currentTimeMillis() > Prefs.setupWatchUntil(this)) return;
        if (!OnboardingActivity.currentStepDone(this)) return;
        Prefs.setSetupWatchUntil(this, 0);
        main.removeCallbacks(setupWatch);
        showGuide();
    }

    void showGuide() {
        try {
            startActivity(new Intent(this, OnboardingActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception ignored) {
        }
    }

    Bubble bubble() {
        return bubble;
    }

    // ------------------------------------------------------- MCP handler

    @Override
    public JSONArray listTools() throws JSONException {
        return tools.list();
    }

    @Override
    public JSONObject callTool(String name, JSONObject args) throws Exception {
        return tools.call(name, args);
    }

    @Override
    public java.io.File attachment(String id) {
        return Attachments.file(this, id);
    }

    @Override
    public void onEvent(final JSONObject event) {
        if ("buddy_pong".equals(event.optString("type"))) {
            Prefs.setHelperStatus(this, event.optString("text"));
            Prefs.setHelperVersion(this, event.optInt("helper", 1));
            if (event.has("signed")) Prefs.setSignedAgents(this, event.optString("signed"));
            org.json.JSONArray cm = event.optJSONArray("codex_models");
            if (cm != null && cm.length() > 0) {
                Prefs.setCodexModels(this, cm.toString());
                Models.setCodex(cm.toString());
            }
            main.post(this::onSetupPong);
            return;
        }
        String t = event.optString("type");
        if ("buddy_procs".equals(t) || "buddy_recent".equals(t) || "buddy_transcript".equals(t)) {
            main.post(() -> ChatHub.get(this).onMonitorEvent(event));
            return;
        }
        main.post(() -> ChatHub.get(this).onEvent(event));
    }

    // ------------------------------------------- called from tool threads

    void onToolActivity(final String tool) {
        main.post(() -> {
            if(instance!=this)return;
            bubble.onToolActivity(tool);
            // asking for approval or posting a note isn't operating the phone
            if (!"confirm".equals(tool) && !"notify".equals(tool)) control.onActivity();
        });
    }

    /** Null if phone actions are allowed, else why not (the user took back control). */
    String controlRevoked() {
        return McpHost.get(this).controlRevoked();
    }

    /** The user asked for something new: phone actions are allowed again. */
    void allowControl() {
        McpHost.get(this).allowControl();
    }

    /** Stop from the indicator or its notification: stop Buddy's chats and refuse phone actions for a minute. */
    void takeBackControl() {
        main.post(() -> {
            McpHost.get(this).emergencyStop();
            android.widget.Toast.makeText(this, "Stopped. Your phone is yours again.", android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    void cancelLocalCommands() { if(tools!=null)tools.cancelLocalCommands(); }
    void onControlStopped() {
        if(bubble!=null)bubble.cancelConfirm();
        if(control!=null)control.end();
    }

    /** Short vibration to get the user's attention (e.g. "tap Update"). */
    void nudge() {
        try {
            android.os.Vibrator v = getSystemService(android.os.Vibrator.class);
            if (v != null) v.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 60, 80, 60}, -1));
        } catch (Exception ignored) {
        }
    }

    void showStatus(final String text) {
        main.post(() -> {if(instance==this)bubble.setStatus(text);});
    }

    void setBubblePassThrough(final boolean on) {
        final CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            try{if(instance==this)bubble.setPassThrough(on);}finally{done.countDown();}
        });
        try {
            done.await(1, TimeUnit.SECONDS);
            if (on) Thread.sleep(120);
        } catch (InterruptedException ignored) {
        }
    }

    /** Blocks the calling (tool) thread until the user answers in the bubble. */
    String askConfirm(final String summary) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Boolean> answer = new AtomicReference<>();
        final Bubble.ConfirmCallback callback = approved -> {
            answer.set(approved);
            done.countDown();
        };
        main.post(() -> {if(instance!=this||bubble==null||controlRevoked()!=null)callback.onAnswer(false);else bubble.askConfirm(summary,callback);});
        try {
            if (!done.await(5, TimeUnit.MINUTES))
                return "DENIED: the user did not answer within 5 minutes. Do not do it.";
        } finally {
            main.post(() -> {if(bubble!=null)bubble.cancelConfirm(callback);});
        }
        return Boolean.TRUE.equals(answer.get())
                ? "APPROVED by the user. Go ahead."
                : "DENIED by the user. Do not do it; ask what they want instead.";
    }
}
