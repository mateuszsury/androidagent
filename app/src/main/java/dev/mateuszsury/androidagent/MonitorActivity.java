package dev.mateuszsury.androidagent;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.mateuszsury.androidagent.Ui.*;

/** Every Claude Code / Codex session on the phone: running processes and recent history. */
public class MonitorActivity extends Screen implements ChatHub.MonitorListener {
    private static final long REFRESH_MS = 3000;

    private ChatHub hub;
    private LinearLayout running, recent;
    private TextView runCount;
    private Ui.Dot liveDot;
    private FrameLayout navSlot;
    private int navBadge = -1;
    private boolean visible;
    /** What the running list shows (without elapsed time); unchanged polls only tick the clocks. */
    private String procsSignature;
    private String recentSignature;
    private Set<String> shownPids = new HashSet<>();
    private final Map<String, TextView> clocks = new HashMap<>();
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            if (!EmbeddedRuntime.get(MonitorActivity.this).ready()) {
                showEnvironmentState();
                running.postDelayed(this, REFRESH_MS);
                return;
            }
            if (!McpHost.get(MonitorActivity.this).running()) {
                withToolHost(() -> {
                    request("recent");
                    request("procs");
                    running.removeCallbacks(poll);
                    running.postDelayed(poll, REFRESH_MS);
                });
                return;
            }
            request("procs");
            running.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        hub = ChatHub.get(this);

        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        LinearLayout live = RootHeader.live(this, "co 3 s", OK);
        liveDot = (Ui.Dot) live.getChildAt(0);
        live.setContentDescription("Odświeżanie co 3 sekundy");
        root.addView(RootHeader.build(this, "Sesje", "procesy i historia agentów", live), full());
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = column(this);
        list.setPadding(0, dp(this, 4), 0, dp(this, 24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        navSlot = new FrameLayout(this);
        setNav(hub.runningCount());
        root.addView(navSlot, full());
        setContentView(root);

        LinearLayout rh = row(this);
        rh.setPadding(0, 0, dp(this, 20), 0);
        rh.addView(sectionLabel(this, "Działa teraz"));
        runCount = badge(this, "", OK);
        runCount.setVisibility(View.GONE);
        LinearLayout.LayoutParams rcp = wrap();
        rcp.topMargin = dp(this, 7);
        rh.addView(runCount, rcp);
        list.addView(rh, full());

        running = column(this);
        running.setPadding(dp(this, 16), dp(this, 2), dp(this, 16), 0);
        list.addView(running, full());
        running.addView(inlineNote("Sprawdzanie…", FAINT, true));

        list.addView(sectionLabel(this, "Ostatnie"));
        recent = column(this);
        list.addView(recent, full());
        recent.addView(inlineNote("Wczytywanie…", FAINT, false));
    }

    private void setNav(int count) {
        if (count == navBadge) return;
        navBadge = count;
        navSlot.removeAllViews();
        navSlot.addView(bottomNav(this, NAV_SESSIONS, count));
    }

    /** A quiet one-line state: a dot (pulsing while checking) and a muted line. */
    private View inlineNote(String s, int color, boolean pulsing) {
        LinearLayout r = row(this);
        r.setPadding(dp(this, pulsing ? 4 : 20), dp(this, 10), dp(this, 20), dp(this, 10));
        Ui.Dot d = toolDot(this, color);
        r.addView(d);
        if (pulsing) d.setPulsing(true);
        TextView t = text(this, s, 14, MUTED);
        t.setPadding(dp(this, 8), 0, 0, 0);
        r.addView(t, weight1());
        return r;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        visible = true;
        hub.monitor = this;
        liveDot.setPulsing(true);
        running.removeCallbacks(poll);
        if (!EmbeddedRuntime.get(this).ready()) {
            showEnvironmentState();
            running.postDelayed(poll, REFRESH_MS);
            return;
        }
        withToolHost(() -> {
            request("recent");
            running.removeCallbacks(poll);
            running.post(poll);
        });
    }

    private void showEnvironmentState() {
        EmbeddedRuntime runtime = EmbeddedRuntime.get(this);
        String detail = !runtime.error().isEmpty() ? runtime.error()
                : runtime.busy() ? runtime.status() + " · " + runtime.progress() + "%"
                : "Przygotuj wbudowane środowisko w panelu Środowisko i konta.";
        String signature = "env:" + detail;
        if (signature.equals(procsSignature)) return;
        procsSignature = signature;
        shownPids = new HashSet<>();
        clocks.clear();
        running.removeAllViews();
        LinearLayout card = card(this);
        card.setPadding(dp(this, 16), 0, dp(this, 16), dp(this, 16));
        LinearLayout empty = emptyState(this, runtime.busy() ? "Środowisko się przygotowuje" : "Środowisko nie jest gotowe", detail);
        empty.setPadding(dp(this, 8), dp(this, 24), dp(this, 8), dp(this, 16));
        card.addView(empty, full());
        Button open = button(this, runtime.busy() ? "Pokaż postęp" : "Otwórz środowisko", false);
        open.setOnClickListener(v -> EmbeddedRuntime.open(this));
        card.addView(open, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 44)));
        LinearLayout.LayoutParams cp = full();
        cp.topMargin = dp(this, 4);
        running.addView(card, cp);
        Motion.enter(card, 40);
        if (!"env".equals(recentSignature)) {
            recentSignature = "env";
            recent.removeAllViews();
            recent.addView(inlineNote("Historia klientów będzie dostępna po przygotowaniu środowiska. Rozmowy androidagent pozostają zapisane.",
                    FAINT, false));
        }
        updateCount(0);
    }

    @Override
    protected void onPause() {
        visible = false;
        liveDot.setPulsing(false);
        if (hub.monitor == this) hub.monitor = null;
        running.removeCallbacks(poll);
        super.onPause();
    }

    private void request(String... args) {
        try {
            Termux.run(this, args);
        } catch (Exception e) {
            procsSignature = "error";
            running.removeAllViews();
            running.addView(inlineNote(e.getMessage(), ERR, false));
        }
    }

    @Override
    public void onMonitor(JSONObject ev) {
        switch (ev.optString("type")) {
            case "buddy_procs":
                showProcs(ev.optJSONArray("procs"));
                break;
            case "buddy_recent":
                showRecent(ev.optJSONArray("sessions"));
                break;
            default:
                break;
        }
    }

    private void updateCount(int n) {
        if (n > 0) {
            String s = String.valueOf(n);
            if (!s.contentEquals(runCount.getText())) runCount.setText(s);
            if (runCount.getVisibility() != View.VISIBLE) {
                runCount.setVisibility(View.VISIBLE);
                Motion.pop(runCount, 0);
            }
        } else {
            runCount.setVisibility(View.GONE);
        }
        setNav(n);
    }

    private static String pidKey(JSONObject p) {
        return p.optLong("pid") + ":" + p.optString("session");
    }

    private void showProcs(JSONArray procs) {
        // Claude Code's background service isn't a conversation: list it apart, without a Stop button.
        List<JSONObject> sessions = new ArrayList<>(), services = new ArrayList<>();
        StringBuilder sig = new StringBuilder("procs:");
        for (int i = 0; procs != null && i < procs.length(); i++) {
            JSONObject p = procs.optJSONObject(i);
            if (p == null) continue;
            ("daemon".equals(p.optString("kind")) ? services : sessions).add(p);
            Sessions.Session chat = hub.sessions.find(p.optString("buddy"));
            sig.append(pidKey(p)).append('|').append(p.optString("kind")).append('|').append(p.optString("agent"))
                    .append('|').append(p.optString("buddy")).append('|').append(p.optString("title"))
                    .append('|').append(p.optString("cwd")).append('|')
                    .append(chat == null ? "-" : chat.label() + "\u0001" + chat.status).append(';');
        }
        int n = sessions.size();
        updateCount(n);
        String signature = sig.toString();
        if (signature.equals(procsSignature)) {
            // Same processes: only the elapsed clocks move.
            for (JSONObject p : procs == null ? new ArrayList<JSONObject>() : all(procs)) {
                TextView clock = clocks.get(pidKey(p));
                if (clock == null) continue;
                String t = elapsed(p);
                if (!t.contentEquals(clock.getText())) clock.setText(t);
            }
            return;
        }
        procsSignature = signature;
        Set<String> previous = shownPids;
        shownPids = new HashSet<>();
        clocks.clear();
        running.removeAllViews();
        if (n == 0) running.addView(inlineNote("Nic teraz nie działa.", FAINT, false));
        for (final JSONObject p : sessions) {
            View card = processCard(p);
            LinearLayout.LayoutParams cp = full();
            cp.topMargin = dp(this, 4);
            cp.bottomMargin = dp(this, 6);
            running.addView(card, cp);
            String key = pidKey(p);
            shownPids.add(key);
            if (!previous.contains(key)) Motion.enter(card, 40);
        }
        for (JSONObject d : services) {
            LinearLayout r = row(this);
            r.setGravity(Gravity.TOP);
            r.setPadding(dp(this, 4), dp(this, 10), dp(this, 4), dp(this, 4));
            r.addView(icon(this, R.drawable.ms_terminal, FAINT, 18));
            LinearLayout col = column(this);
            col.setPadding(dp(this, 10), 0, 0, 0);
            col.addView(text(this, "Usługa w tle Claude Code", 14, TEXT));
            TextView meta = single(monoText(this, "pid " + d.optLong("pid") + " · od " + elapsed(d)
                    + " · obsługuje sesje w tle", 12, FAINT));
            meta.setPadding(0, dp(this, 2), 0, 0);
            col.addView(meta);
            r.addView(col, weight1());
            running.addView(r, full());
            shownPids.add(pidKey(d));
        }
    }

    private static List<JSONObject> all(JSONArray a) {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(o);
        }
        return out;
    }

    private static String elapsed(JSONObject p) {
        return DateUtils.formatElapsedTime(p.optLong("seconds"));
    }

    /** A terminal-style card for one agent process. */
    private View processCard(JSONObject p) {
        final String backend = p.optString("agent");
        final String agent = "codex".equals(backend) ? "Codex" : "Claude Code";
        final Sessions.Session chat = hub.sessions.find(p.optString("buddy"));
        final String kind = p.optString("kind", "terminal");
        final boolean background = "background".equals(kind);
        String title = p.optString("title").trim();
        final String where = chat != null ? chat.label()
                : !p.optString("buddy").isEmpty() ? "Usunięta rozmowa androidagent"
                : !title.isEmpty() ? title
                : background ? "Sesja w tle" : "Sesja w terminalu";

        LinearLayout card = card(this);
        LinearLayout first = row(this);
        Ui.Dot dot = toolDot(this, OK);
        first.addView(dot);
        dot.setPulsing(true);
        TextView name = single(title(this, where, 15));
        name.setPadding(dp(this, 6), 0, dp(this, 8), 0);
        first.addView(name, weight1());
        TextView el = monoText(this, elapsed(p), 12, MUTED);
        el.setTypeface(mono(this, 500));
        first.addView(el);
        clocks.put(pidKey(p), el);
        card.addView(first, full());

        LinearLayout tags = row(this);
        tags.setPadding(dp(this, 22), dp(this, 8), 0, 0);
        tags.addView(badge(this, "codex".equals(backend) ? "codex" : "claude-code", "codex".equals(backend) ? INFO : ACCENT_TEXT));
        TextView kindTag = badge(this, chat != null ? "androidagent" : background ? "w tle" : "terminal", chat != null ? OK : MUTED);
        LinearLayout.LayoutParams kp = wrap();
        kp.leftMargin = dp(this, 6);
        tags.addView(kindTag, kp);
        String ses = p.optString("session");
        if (ses.length() >= 8) {
            TextView id = monoText(this, "#" + ses.substring(0, 8), 11, FAINT);
            id.setPadding(dp(this, 8), 0, 0, 0);
            tags.addView(id);
        }
        card.addView(tags, full());

        TextView meta = single(monoText(this, "", 12, MUTED));
        meta.setText(connector("pid " + p.optLong("pid") + " · " + shortPath(p.optString("cwd"))));
        meta.setPadding(dp(this, 6), dp(this, 8), 0, 0);
        card.addView(meta, full());
        if (background && chat == null) {
            TextView hint = text(this, "Działa w usłudze w tle Claude Code, np. rozmowa kontynuowana z aplikacji Claude.",
                    13, MUTED);
            hint.setPadding(dp(this, 22), dp(this, 6), 0, 0);
            card.addView(hint);
        }
        if (chat != null && !chat.status.isEmpty()) {
            LinearLayout st = row(this);
            st.setPadding(dp(this, 22), dp(this, 6), 0, 0);
            Spark spark = new Spark(this).color(ACCENT);
            st.addView(spark, new LinearLayout.LayoutParams(dp(this, 13), dp(this, 13)));
            TextView stt = single(monoText(this, chat.status, 12, ACCENT_TEXT));
            stt.setPadding(dp(this, 7), 0, 0, 0);
            st.addView(stt, weight1());
            card.addView(st, full());
        }
        LinearLayout btns = row(this);
        if (chat != null) {
            Button open = button(this, "Otwórz czat", false);
            open.setOnClickListener(v -> openChat(chat));
            LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(0, dp(this, 40), 1);
            op.rightMargin = dp(this, 8);
            btns.addView(open, op);
        }
        Button stop = outlinedButton(this, "Zatrzymaj", ERR);
        stop.setOnClickListener(v -> confirmSheet(this, "Zatrzymać sesję " + agent + "?",
                chat != null ? where
                        : (background ? "Działa w tle, np. rozmowa kontynuowana z aplikacji Claude. "
                        : "To może być sesja, której używasz teraz w terminalu. ")
                        + "Może to być nawet sesja sterująca androidagent. Zatrzymanie kończy bieżącą pracę tej rozmowy.",
                "Zatrzymaj", true, () -> {
                    if (chat != null) hub.stop(chat);
                    else request("kill", String.valueOf(p.optLong("pid")));
                    Toast.makeText(this, "Zatrzymywanie…", Toast.LENGTH_SHORT).show();
                }));
        btns.addView(stop, chat != null ? new LinearLayout.LayoutParams(0, dp(this, 40), 1)
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 40)));
        LinearLayout.LayoutParams bp = full();
        bp.topMargin = dp(this, 12);
        card.addView(btns, bp);
        if (chat != null) {
            card.setOnClickListener(v -> openChat(chat));
            card.setForeground(getDrawable(ripple(this, false)));
            card.setClipToOutline(true);
        }
        return card;
    }

    /** "└ text" with the connector in the faint colour, like a tool result line. */
    private CharSequence connector(String s) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("└ " + s);
        sb.setSpan(new android.text.style.ForegroundColorSpan(FAINT), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private void showRecent(JSONArray list) {
        StringBuilder sig = new StringBuilder("recent:");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject s = list.optJSONObject(i);
            if (s != null) sig.append(s.optString("id")).append(s.optLong("mtime")).append(s.optString("title")).append(';');
        }
        if (sig.toString().equals(recentSignature)) return;
        boolean first = recentSignature == null || "env".equals(recentSignature);
        recentSignature = sig.toString();
        recent.removeAllViews();
        if (list == null || list.length() == 0) {
            recent.addView(emptyState(this, "Brak historii", "Tu pojawią się sesje Claude Code i Codex z tego telefonu."), full());
            return;
        }
        for (int i = 0; i < list.length(); i++) {
            final JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            final String backend = s.optString("agent");
            final Sessions.Session chat = hub.sessions.find(s.optString("buddy"));
            String title = s.optString("title");
            final String t = title.isEmpty() ? "(bez wiadomości)" : title;

            LinearLayout r = listRow(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            TextView tile = agentTile(this, backend);
            tile.setTextSize(11);
            r.addView(tile, new LinearLayout.LayoutParams(dp(this, 34), dp(this, 34)));
            LinearLayout col = column(this);
            col.setPadding(dp(this, 12), 0, dp(this, 8), 0);
            LinearLayout top = row(this);
            TextView tt = single(title(this, t, 15));
            top.addView(tt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            if (chat != null) {
                LinearLayout.LayoutParams tp = wrap();
                tp.leftMargin = dp(this, 8);
                top.addView(badge(this, "androidagent", OK), tp);
            }
            col.addView(top, full());
            TextView meta = single(monoText(this, MainActivity.ago(s.optLong("mtime") * 1000) + " · "
                    + shortPath(s.optString("cwd")), 11, FAINT));
            meta.setPadding(0, dp(this, 3), 0, 0);
            col.addView(meta);
            r.addView(col, weight1());
            r.addView(icon(this, R.drawable.ms_chevron_right, FAINT, 18));
            r.setOnClickListener(v -> {
                if (chat != null) {
                    openChat(chat);
                } else {
                    startActivity(new Intent(this, TranscriptActivity.class)
                            .putExtra("file", s.optString("file")).putExtra("agent", backend)
                            .putExtra("id", s.optString("id")).putExtra("title", t)
                            .putExtra("cwd", shortPath(s.optString("cwd"))));
                }
            });
            recent.addView(r, full());
        }
        if (first) Motion.stagger(recent, 60, 35);
    }

    private void openChat(Sessions.Session chat) {
        hub.select(chat);
        startActivity(new Intent(this, ChatActivity.class).putExtra("id", chat.id));
    }

    static String shortPath(String p) {
        return p.replace("/data/data/com.termux/files/home", "~");
    }
}
