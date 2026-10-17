package dev.mateuszsury.androidagent;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import static dev.mateuszsury.androidagent.Ui.*;

/** Read the last messages of a Claude Code / Codex session that isn't a Buddy chat, and optionally continue it. */
public class TranscriptActivity extends Screen implements ChatHub.MonitorListener {
    private ChatHub hub;
    private LinearLayout messages;
    private ScrollView scroll;
    private String file, agent, id, title;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        hub = ChatHub.get(this);
        file = getIntent().getStringExtra("file");
        agent = getIntent().getStringExtra("agent");
        id = getIntent().getStringExtra("id");
        title = getIntent().getStringExtra("title");
        if (title == null || title.trim().isEmpty()) title = "Sesja bez tytułu";
        String cwd = getIntent().getStringExtra("cwd");

        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        TextView readOnly = badge(this, "tylko odczyt", MUTED);
        LinearLayout.LayoutParams rp = wrap();
        rp.rightMargin = dp(this, 8);
        readOnly.setLayoutParams(rp);
        root.addView(topBar(this, title, ("codex".equals(agent) ? "codex" : "claude-code")
                + (cwd != null && !cwd.isEmpty() ? " · " + cwd : ""), readOnly), full());

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        messages = column(this);
        messages.setPadding(dp(this, 16), dp(this, 6), dp(this, 16), dp(this, 16));
        messages.addView(loading(), full());
        scroll.addView(messages);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        boolean canContinue = id != null && id.matches("[A-Za-z0-9-]{8,64}");
        if (canContinue) {
            root.addView(divider(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 1)));
            Button cont = button(this, "Kontynuuj w androidagent", true);
            cont.setTextSize(15);
            cont.setOnClickListener(v -> withToolHost(() -> {
                Sessions.Session s = hub.adopt(agent, id, title);
                hub.select(s);
                startActivity(new Intent(this, ChatActivity.class).putExtra("id", s.id));
                finish();
            }));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 50));
            cp.setMargins(dp(this, 16), dp(this, 10), dp(this, 16), dp(this, 12));
            root.addView(cont, cp);
        }
        setContentView(root);
    }

    private View loading() {
        LinearLayout r = row(this);
        r.setPadding(dp(this, 4), dp(this, 16), 0, dp(this, 8));
        r.addView(new Spark(this).color(ACCENT), new LinearLayout.LayoutParams(dp(this, 15), dp(this, 15)));
        TextView t = monoText(this, "Wczytywanie transkrypcji…", 13, ACCENT_TEXT);
        t.setPadding(dp(this, 10), 0, 0, 0);
        r.addView(t);
        return r;
    }

    private TextView note(String s) {
        TextView t = monoText(this, s, 11, FAINT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(this, 10), 0, dp(this, 4));
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        hub.monitor = this;
        if (!EmbeddedRuntime.get(this).ready()) {
            messages.removeAllViews();
            LinearLayout empty = emptyState(this, "Środowisko nie jest gotowe",
                    "Przygotuj wbudowane środowisko w panelu Środowisko i konta, aby odczytać tę sesję.");
            messages.addView(empty, full());
            Button open = button(this, "Otwórz środowisko", false);
            open.setOnClickListener(v -> EmbeddedRuntime.open(this));
            LinearLayout.LayoutParams op = wrap();
            op.gravity = Gravity.CENTER_HORIZONTAL;
            messages.addView(open, op);
            Motion.enter(empty, 40);
            return;
        }
        withToolHost(this::requestTranscript);
    }

    private void requestTranscript() {
        try {
            Termux.run(this, "transcript", agent, file);
        } catch (Exception e) {
            messages.removeAllViews();
            LinearLayout r = row(this);
            r.setGravity(Gravity.TOP);
            r.setPadding(0, dp(this, 16), 0, 0);
            r.addView(toolDot(this, ERR));
            TextView t = text(this, e.getMessage() == null ? "Nie udało się odczytać sesji." : e.getMessage(), 14, ERR);
            t.setPadding(dp(this, 8), 0, 0, 0);
            r.addView(t, weight1());
            messages.addView(r, full());
        }
    }

    @Override
    protected void onPause() {
        if (hub.monitor == this) hub.monitor = null;
        super.onPause();
    }

    @Override
    public void onMonitor(JSONObject ev) {
        if (!"buddy_transcript".equals(ev.optString("type"))) return;
        messages.removeAllViews();
        JSONArray list = ev.optJSONArray("messages");
        if (list == null || list.length() == 0) {
            messages.addView(emptyState(this, "Brak czytelnych wiadomości", "Ta sesja nie zawiera wiadomości do wyświetlenia."), full());
            return;
        }
        int n = list.length();
        messages.addView(note("ostatnie " + n + " " + plural(n)), full());
        for (int i = 0; i < n; i++) {
            JSONObject m = list.optJSONObject(i);
            if (m == null) continue;
            String text = m.optString("text");
            LinearLayout.LayoutParams p = full();
            p.topMargin = dp(this, 14);
            if ("user".equals(m.optString("role"))) {
                messages.addView(userBand(text), p);
            } else {
                LinearLayout r = row(this);
                r.setGravity(Gravity.TOP);
                Ui.Dot dot = toolDot(this, TEXT);
                LinearLayout.LayoutParams dotP = (LinearLayout.LayoutParams) dot.getLayoutParams();
                dotP.topMargin = dp(this, 3);
                r.addView(dot, dotP);
                LinearLayout content = MessageContent.render(this, text);
                LinearLayout.LayoutParams cp = weight1();
                cp.leftMargin = dp(this, 8);
                r.addView(content, cp);
                messages.addView(r, p);
            }
        }
        Motion.stagger(messages, 0, 25);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /** The user's turn: a quiet band with the "›" prompt, like the chat. */
    private View userBand(String s) {
        LinearLayout band = row(this);
        band.setGravity(Gravity.TOP);
        band.setPadding(dp(this, 12), dp(this, 10), dp(this, 14), dp(this, 10));
        band.setBackground(outlined(this, CARD, 10, LINE, 1));
        TextView glyph = monoText(this, "›", 15, ACCENT_TEXT);
        glyph.setTypeface(mono(this, 600));
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        band.addView(glyph);
        TextView t = text(this, s, 15, TEXT);
        t.setTextIsSelectable(true);
        t.setLineSpacing(dp(this, 2), 1f);
        t.setPadding(dp(this, 10), 0, 0, 0);
        band.addView(t, weight1());
        return band;
    }

    private static String plural(int n) {
        return n == 1 ? "wiadomość" : "wiadomości";
    }
}
