package dev.mateuszsury.androidagent;

import android.content.ComponentName;
import android.content.Context;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;

/** Quick setup progress from saved state (no ping): used for the Chats header and the Settings card. */
final class SetupState {
    static final String[] STEPS = {
            "Wbudowane środowisko", "Oficjalny klient agenta", "Logowanie do konta",
            "Uprawnienia opcjonalne", "Pierwsza rozmowa",
    };
    /** Must match HELPER_VERSION in termux/buddy-run. */
    static final int HELPER_VERSION = 16;

    private final Context app;
    final boolean[] done = new boolean[5];

    SetupState(Context c) {
        app=c.getApplicationContext();
        done[0] = EmbeddedRuntime.get(c).ready();
        done[1] = agentInstalled(c, Prefs.backend(c));
        done[2] = agentReady(c, Prefs.backend(c));
        PowerManager pm = c.getSystemService(PowerManager.class);
        done[3] = pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        done[4] = Prefs.hadReply(c);
    }

    static boolean helperCurrent(Context c) {
        return EmbeddedRuntime.get(c).ready() && Prefs.helperStatus(c) != null
                && Prefs.helperVersion(c) >= HELPER_VERSION;
    }

    static boolean agentInstalled(Context c, String agent) {
        return EmbeddedRuntime.get(c).ready() && EmbeddedRuntime.get(c).agentInstalled(agent);
    }

    /** Installed and signed in. */
    static boolean agentReady(Context c, String agent) {
        String s = Prefs.signedAgents(c);
        if (!helperCurrent(c) || !agentInstalled(c, agent) || s == null) return false;
        for (String signed : s.split(",")) if (agent.equals(signed.trim())) return true;
        return false;
    }

    int count() {
        int n = 0;
        for (boolean d : done) if (d) n++;
        return n;
    }

    /** Index of the first unfinished step, or -1. */
    int next() {
        for (int i = 0; i < done.length; i++) if (!done[i]) return i;
        return -1;
    }

    /** Accessibility and battery exemption are optional; the local host and account are required. */
    boolean canRun() {
        return done[0] && done[1] && done[2] && McpHost.get(app).running();
    }

    static boolean accessibilityEnabled(Context c) {
        String enabled = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(c, BuddyService.class);
        TextUtils.SimpleStringSplitter s = new TextUtils.SimpleStringSplitter(':');
        s.setString(enabled);
        while (s.hasNext()) {
            if (me.equals(ComponentName.unflattenFromString(s.next()))) return true;
        }
        return false;
    }
}
