package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Usługi w tle": the owner's view of long-running services — state, URLs, logs, start/stop,
 * remove and adding a service by hand. Entry point: {@code new Intent(context, ServicesActivity.class)}.
 */
public final class ServicesActivity extends Screen implements ServiceManager.Listener {
    private final Handler poll = new Handler(Looper.getMainLooper());
    private final Map<String, TextView> facts = new HashMap<>();
    private final Set<String> busy = new HashSet<>(), shown = new HashSet<>();
    private ServiceManager manager;
    private LinearLayout body;
    private TextView subtitle;
    private String shape;
    private volatile boolean runtimeOk = true;
    private int ticks;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (++ticks % 5 == 0) checkEnvironment();
            refresh(false);
            poll.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        manager = ServiceManager.get(this);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        ImageButton add = iconButton(this, R.drawable.ms_add, "Dodaj usługę");
        add.setOnClickListener(v -> showAdd());
        LinearLayout bar = topBar(this, "Usługi w tle", "~/services", add);
        subtitle = (TextView) ((LinearLayout) ((LinearLayout) bar.getChildAt(0)).getChildAt(1)).getChildAt(1);
        root.addView(bar, full());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = column(this);
        body.setPadding(0, dp(this, 14), 0, dp(this, 28));
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        runtimeOk = manager.runtimeReady();
        manager.async(manager::restore);
        refresh(true);
    }

    @Override protected void onResume() {
        super.onResume();
        manager.addListener(this);
        checkEnvironment();
        poll.post(tick);
    }

    @Override protected void onPause() {
        manager.removeListener(this);
        poll.removeCallbacks(tick);
        super.onPause();
    }

    @Override public void onServicesChanged() {
        refresh(false);
    }

    private void checkEnvironment() {
        manager.async(() -> {
            boolean ready = manager.runtimeReady();
            manager.refreshNetwork();
            if (ready != runtimeOk) {
                runtimeOk = ready;
                poll.post(() -> refresh(false));
            }
        });
    }

    // ------------------------------------------------------------------ render

    private void refresh(boolean force) {
        if (isFinishing() || isDestroyed()) return;
        List<ServiceManager.Status> all = manager.list();
        StringBuilder sig = new StringBuilder().append(runtimeOk).append(manager.foregroundError()).append(busy);
        for (NetAddresses.Address a : manager.network()) sig.append(a.ip).append(a.type);
        for (ServiceManager.Status st : all) {
            sig.append('|').append(st.def.key()).append(st.state).append(st.error).append(st.pid).append(st.ports)
                    .append(st.portOpen).append(st.lanReachable).append(st.loopbackOnly).append(st.restarts)
                    .append(st.exitCode).append(st.restartInMs > 0 ? st.restartInMs / 1000 : -1);
        }
        int running = 0;
        for (ServiceManager.Status st : all) if (st.active()) running++;
        setIfChanged(subtitle, all.isEmpty() ? "~/services · brak usług"
                : "~/services · " + ServiceManager.runningLabel(running) + " · " + saved(all.size()));
        if (!force && sig.toString().equals(shape)) {
            for (ServiceManager.Status st : all) {
                TextView f = facts.get(st.def.name);
                if (f != null) setIfChanged(f, factsText(st));
            }
            return;
        }
        boolean first = shape == null;
        shape = sig.toString();
        rebuild(all, running, first);
    }

    private void rebuild(List<ServiceManager.Status> all, int running, boolean first) {
        body.removeAllViews();
        facts.clear();
        if (!runtimeOk) {
            body.addView(notice(WARN, ServiceManager.NOT_PREPARED,
                    "Usługi działają we wbudowanym terminalu. Przygotuj go, aby je uruchomić.",
                    "Otwórz panel środowiska", () -> EmbeddedRuntime.open(this)), cardParams(this));
        }
        if (!manager.foregroundError().isEmpty())
            body.addView(notice(WARN, "Powiadomienie usług wstrzymane", manager.foregroundError(), null, null), cardParams(this));
        body.addView(summary(running), boxParams());
        body.addView(sectionLabel(this, all.isEmpty() ? "Usługi" : "Usługi · " + all.size()));
        if (all.isEmpty()) {
            LinearLayout empty = emptyState(this, "Brak usług", "Poproś agenta: „uruchom serwer WWW na porcie 8080”");
            Button add = outlinedButton(this, "Dodaj usługę", ACCENT_TEXT);
            add.setOnClickListener(v -> showAdd());
            LinearLayout.LayoutParams ap = wrap();
            ap.gravity = Gravity.CENTER_HORIZONTAL;
            ap.topMargin = dp(this, 18);
            empty.addView(add, ap);
            body.addView(empty, full());
            if (first) Motion.enter(empty, 60);
        }
        Set<String> present = new HashSet<>();
        int index = 0;
        for (ServiceManager.Status st : all) {
            View card = card(st);
            body.addView(card, cardParams(this));
            present.add(st.def.name);
            if (first) Motion.enter(card, 40 + Math.min(index, 8) * 40);
            else if (!shown.contains(st.def.name)) Motion.enter(card, 0);
            index++;
        }
        shown.clear();
        shown.addAll(present);
    }

    private LinearLayout.LayoutParams boxParams() {
        LinearLayout.LayoutParams p = cardParams(this);
        p.bottomMargin = dp(this, 4);
        return p;
    }

    /** The network panel: where other devices reach the services, and the stop-all control. */
    private View summary(int running) {
        List<NetAddresses.Address> lan = NetAddresses.lan(manager.network());
        LinearLayout box = terminalBox(this, "sieć lokalna", Theme.withAlpha(ACCENT, 0x8C));
        LinearLayout head = row(this);
        Ui.Dot dot = toolDot(this, lan.isEmpty() ? FAINT : OK);
        head.addView(dot);
        String where = lan.isEmpty() ? "Brak Wi-Fi" : lan.get(0).label() + " · " + lan.get(0).ip;
        TextView net = single(monoText(this, where, 14, TEXT));
        net.setTypeface(mono(this, 600));
        net.setPadding(dp(this, 6), 0, dp(this, 8), 0);
        head.addView(net, weight1());
        head.addView(badge(this, running == 0 ? "nic nie działa" : ServiceManager.runningLabel(running), running == 0 ? MUTED : OK));
        box.addView(head, full());
        for (int i = 1; i < lan.size(); i++) {
            TextView more = single(monoText(this, lan.get(i).label() + " · " + lan.get(i).ip, 12, MUTED));
            more.setPadding(dp(this, 22), dp(this, 4), 0, 0);
            box.addView(more, full());
        }
        TextView hint = text(this, lan.isEmpty()
                ? "Bez Wi-Fi usługi są dostępne tylko na tym telefonie: http://127.0.0.1:PORT."
                : "Inne urządzenia w tej sieci otworzą usługę pod adresem http://" + lan.get(0).ip
                        + ":PORT, jeśli nasłuchuje na 0.0.0.0. Na telefonie: http://127.0.0.1:PORT.", 13, MUTED);
        hint.setLineSpacing(dp(this, 2), 1f);
        hint.setPadding(0, dp(this, 8), 0, 0);
        box.addView(hint, full());
        if (running > 0) {
            Button stopAll = outlinedButton(this, "Zatrzymaj wszystkie usługi", ERR);
            stopAll.setOnClickListener(v -> confirmSheet(this, "Zatrzymać wszystkie usługi?",
                    "Serwery i boty przestaną odpowiadać. Możesz uruchomić je ponownie w tym miejscu.", "Zatrzymaj", true,
                    () -> {
                        for (ServiceManager.Status st : manager.active()) busy.add(st.def.name);
                        refresh(true);
                        manager.async(() -> {
                            try { manager.stopAll(); } finally { poll.post(() -> { busy.clear(); refresh(true); }); }
                        });
                    }));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 42));
            sp.topMargin = dp(this, 14);
            box.addView(stopAll, sp);
        }
        return box;
    }

    private View card(ServiceManager.Status st) {
        String name = st.def.name;
        boolean stopping = busy.contains(name);
        int color = stopping ? WARN : stateColor(st);
        LinearLayout card = Ui.card(this);
        LinearLayout head = row(this);
        Ui.Dot dot = toolDot(this, color);
        dot.setPulsing(stopping || ServiceManager.STARTING.equals(st.state));
        head.addView(dot);
        TextView title = single(monoText(this, name, 15, TEXT));
        title.setTypeface(mono(this, 600));
        title.setPadding(dp(this, 6), 0, dp(this, 8), 0);
        head.addView(title, weight1());
        head.addView(badge(this, stopping ? "zatrzymywanie" : stateLabel(st), color));
        card.addView(head, full());

        TextView command = codeBlock(this, st.def.command);
        command.setPadding(dp(this, 12), dp(this, 9), dp(this, 12), dp(this, 9));
        command.setMaxLines(4);
        command.setEllipsize(TextUtils.TruncateAt.END);
        command.setLineSpacing(0, 1.25f);
        command.setContentDescription("Komenda: " + st.def.command);
        LinearLayout.LayoutParams cp = full();
        cp.topMargin = dp(this, 10);
        card.addView(command, cp);

        TextView cwd = single(monoText(this, st.def.cwd, 12, MUTED));
        cwd.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams wp = full();
        wp.topMargin = dp(this, 10);
        card.addView(cwd, wp);
        TextView fact = monoText(this, factsText(st), 11, FAINT);
        fact.setPadding(0, dp(this, 3), 0, 0);
        facts.put(name, fact);
        card.addView(fact, full());

        if (!st.error.isEmpty()) {
            boolean waiting = st.error.startsWith("Autostart czeka");
            TextView err = text(this, st.error, 13, waiting ? WARN : ERR);
            err.setPadding(dp(this, 10), dp(this, 7), dp(this, 10), dp(this, 7));
            err.setBackground(rounded(this, waiting ? WARN_SOFT : ERR_SOFT, 8));
            LinearLayout.LayoutParams ep = full();
            ep.topMargin = dp(this, 10);
            card.addView(err, ep);
        }
        if (st.active()) {
            String warning = null;
            if (st.def.port != 0 && !st.portOpen && ServiceManager.RUNNING.equals(st.state))
                warning = "Port " + st.def.port + " jeszcze nie nasłuchuje. Sprawdź logi.";
            else if (st.loopbackOnly)
                warning = "Nasłuchuje tylko na 127.0.0.1: inne urządzenia się nie połączą. Użyj 0.0.0.0.";
            if (warning != null) {
                TextView w = text(this, warning, 13, WARN);
                w.setPadding(0, dp(this, 8), 0, 0);
                card.addView(w, full());
            }
            boolean first = true;
            for (String[] url : st.urls) {
                boolean lanUrl = !url[0].startsWith("http://127.");
                View r = urlRow(url[0], url[1], st.portOpen && (!lanUrl || !st.loopbackOnly));
                LinearLayout.LayoutParams up = full();
                up.topMargin = dp(this, first ? 10 : 6);
                card.addView(r, up);
                first = false;
            }
        }

        LinearLayout actions = row(this);
        Button logs = button(this, "Logi", false);
        logs.setOnClickListener(v -> showLogs(name));
        actions.addView(logs, new LinearLayout.LayoutParams(0, dp(this, 40), 1));
        Button toggle = st.active() || stopping ? outlinedButton(this, "Zatrzymaj", ERR) : button(this, "Uruchom", true);
        toggle.setEnabled(!stopping);
        toggle.setAlpha(stopping ? 0.5f : 1f);
        toggle.setOnClickListener(v -> {
            if (st.active()) operate(name, () -> manager.stop(name));
            else operate(name, () -> manager.start(name));
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(this, 40), 1);
        tp.leftMargin = dp(this, 8);
        actions.addView(toggle, tp);
        TextView remove = monoText(this, "Usuń", 13, ERR);
        remove.setTypeface(mono(this, 600));
        remove.setGravity(Gravity.CENTER);
        remove.setPadding(dp(this, 14), 0, dp(this, 10), 0);
        remove.setBackgroundResource(ripple(this, true));
        remove.setContentDescription("Usuń usługę " + name);
        remove.setOnClickListener(v -> confirmSheet(this, "Usunąć usługę „" + name + "”?",
                "Usługa zostanie zatrzymana, a jej definicja i logi usunięte. Tej operacji nie można cofnąć.",
                "Usuń", true, () -> operate(name, () -> manager.remove(name))));
        actions.addView(remove, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(this, 40)));
        LinearLayout.LayoutParams ap = full();
        ap.topMargin = dp(this, 14);
        card.addView(actions, ap);
        return card;
    }

    private View urlRow(String url, String label, boolean live) {
        LinearLayout r = row(this);
        r.setPadding(dp(this, 10), dp(this, 2), dp(this, 2), dp(this, 2));
        r.setBackground(outlined(this, BG, 10, OUTLINE, 1));
        r.addView(dot(this, live ? OK : FAINT, 7));
        TextView u = single(monoText(this, url, 13, live ? ACCENT_TEXT : MUTED));
        u.setPadding(dp(this, 8), 0, dp(this, 6), 0);
        r.addView(u, weight1());
        r.addView(single(monoText(this, label, 11, FAINT)));
        ImageButton copy = iconButton(this, R.drawable.ms_content_copy, "Kopiuj adres " + url, MUTED);
        copy.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
        copy.setOnClickListener(v -> copy(url));
        r.addView(copy);
        r.setContentDescription("Otwórz " + url);
        r.setForeground(getDrawable(ripple(this, false)));
        r.setOnClickListener(v -> open(url));
        r.setOnLongClickListener(v -> {
            copy(url);
            return true;
        });
        Motion.press(r);
        return r;
    }

    private View notice(int color, String title, String message, String action, Runnable onAction) {
        LinearLayout box = Ui.card(this);
        box.setBackground(outlined(this, Theme.withAlpha(color, 0x14), 14, Theme.withAlpha(color, 0x55), 1));
        LinearLayout head = row(this);
        head.addView(toolDot(this, color));
        TextView t = title(this, title, 15);
        t.setPadding(dp(this, 6), 0, 0, 0);
        head.addView(t, weight1());
        box.addView(head, full());
        TextView m = text(this, message, 13, MUTED);
        m.setPadding(dp(this, 22), dp(this, 4), 0, 0);
        box.addView(m, full());
        if (action != null) {
            TextView a = barAction(this, action);
            a.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            a.setMinHeight(dp(this, 36));
            a.setMinimumHeight(dp(this, 36));
            a.setPadding(dp(this, 22), dp(this, 8), dp(this, 12), 0);
            a.setOnClickListener(v -> onAction.run());
            box.addView(a, wrap());
        }
        return box;
    }

    private void operate(String name, Runnable op) {
        busy.add(name);
        refresh(true);
        manager.async(() -> {
            try { op.run(); } finally { poll.post(() -> { busy.remove(name); refresh(true); }); }
        });
    }

    // ------------------------------------------------------------------- logs

    private void showLogs(String name) {
        LinearLayout c = column(this);
        LinearLayout head = row(this);
        TextView t = title(this, "Logi · " + name, 18);
        t.setAccessibilityHeading(true);
        head.addView(t, weight1());
        TextView copy = barAction(this, "Kopiuj");
        head.addView(copy);
        c.addView(head, full());
        TextView path = single(monoText(this, "files/services/" + name + ".log · 2 pliki po 512 KB", 11, FAINT));
        c.addView(path, full());
        TextView out = terminalText(this, "Wczytywanie…");
        out.setTextSize(11);
        LinearLayout.LayoutParams op = full();
        op.topMargin = dp(this, 12);
        c.addView(out, op);
        Dialog sheet = bottomSheet(this, c);
        ScrollView scroll = (ScrollView) c.getParent();
        final String[] last = {null};
        final boolean[] open = {true};
        copy.setOnClickListener(v -> {
            if (last[0] != null) copyText("Logi " + name, last[0], "Skopiowano logi");
        });
        Runnable load = new Runnable() {
            @Override public void run() {
                if (!open[0]) return;
                Runnable self = this;
                manager.async(() -> {
                    String text;
                    try {
                        text = manager.logs(name, 400);
                    } catch (Exception e) {
                        text = "Nie można odczytać logów: " + e.getMessage();
                    }
                    final String value = text.isEmpty() ? "Brak wpisów. Usługa jeszcze nic nie wypisała." : text;
                    poll.post(() -> {
                        if (!open[0]) return;
                        if (!value.equals(last[0])) {
                            boolean follow = last[0] == null || scroll.getScrollY() + scroll.getHeight()
                                    >= c.getHeight() - dp(ServicesActivity.this, 48);
                            last[0] = value;
                            out.setText(value);
                            if (follow) scroll.post(() -> scroll.scrollTo(0, Math.max(0, c.getHeight() - scroll.getHeight())));
                        }
                        poll.postDelayed(self, 1500);
                    });
                });
            }
        };
        sheet.setOnDismissListener(d -> open[0] = false);
        sheet.show();
        load.run();
    }

    // -------------------------------------------------------------------- add

    private void showAdd() {
        LinearLayout c = column(this);
        TextView t = title(this, "Dodaj usługę", 18);
        t.setAccessibilityHeading(true);
        c.addView(t);
        TextView sub = text(this, "Komenda działa we wbudowanym terminalu także po zakończeniu pracy agenta. "
                + "Uruchom ją na pierwszym planie, bez „&” na końcu.", 13, MUTED);
        sub.setPadding(0, dp(this, 6), 0, dp(this, 4));
        c.addView(sub, full());
        EditText name = field(c, "Nazwa", "web", true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText command = field(c, "Komenda", "python3 -m http.server 8080 --bind 0.0.0.0", false);
        command.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        command.setMinLines(2);
        command.setMaxLines(6);
        command.setGravity(Gravity.TOP | Gravity.START);
        EditText cwd = field(c, "Katalog", ServiceManager.DEFAULT_CWD, true);
        cwd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText port = field(c, "Port", "8080 (opcjonalnie)", true);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        final boolean[] autostart = {false}, restart = {true};
        LinearLayout auto = switchRow(this, "Autostart", "Uruchamiaj przy starcie aplikacji", false, on -> autostart[0] = on);
        auto.setPadding(0, dp(this, 12), 0, dp(this, 6));
        c.addView(auto, full());
        LinearLayout again = switchRow(this, "Restart po błędzie", "Do 5 razy w ciągu 10 minut", true, on -> restart[0] = on);
        again.setPadding(0, dp(this, 6), 0, dp(this, 12));
        c.addView(again, full());
        TextView error = text(this, "", 13, ERR);
        error.setVisibility(View.GONE);
        error.setPadding(0, 0, 0, dp(this, 10));
        c.addView(error, full());
        LinearLayout buttons = row(this);
        Button cancel = button(this, "Anuluj", false);
        Button save = button(this, "Dodaj i uruchom", true);
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(this, 48), 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(this, 48), 1);
        sp.leftMargin = dp(this, 10);
        buttons.addView(save, sp);
        c.addView(buttons, full());
        Dialog sheet = bottomSheet(this, c);
        if (sheet.getWindow() != null)
            sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        cancel.setOnClickListener(v -> sheet.dismiss());
        save.setOnClickListener(v -> {
            ServiceManager.Definition d = new ServiceManager.Definition();
            d.name = name.getText().toString().trim();
            d.command = command.getText().toString().trim();
            d.cwd = cwd.getText().toString().trim();
            d.autostart = autostart[0];
            d.restart = restart[0] ? ServiceManager.RESTART_ON_FAILURE : ServiceManager.RESTART_NEVER;
            String problem = null;
            String p = port.getText().toString().trim();
            if (!d.name.matches("[a-z0-9-]{1,32}")) problem = "Nazwa: 1–32 znaki — małe litery, cyfry i „-”, np. web.";
            else if (d.command.isEmpty()) problem = "Wpisz komendę usługi.";
            else if (!p.isEmpty()) {
                try { d.port = Integer.parseInt(p); } catch (NumberFormatException e) { d.port = -1; }
                if (d.port < 1024 || d.port > 65535) problem = "Port musi być liczbą od 1024 do 65535.";
            }
            if (problem == null) {
                ServiceManager.Status existing = manager.status(d.name);
                if (existing != null && existing.active()) problem = "Usługa „" + d.name + "” już działa. Zatrzymaj ją najpierw.";
            }
            if (problem == null) {
                try { d.validate(); } catch (RuntimeException e) { problem = e.getMessage(); }
            }
            if (problem != null) {
                error.setText(problem);
                error.setVisibility(View.VISIBLE);
                Motion.shake(error);
                return;
            }
            sheet.dismiss();
            operate(d.name, () -> {
                try {
                    manager.define(d, "owner");
                    manager.start(d.name);
                } catch (RuntimeException e) {
                    poll.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        });
        sheet.show();
    }

    private EditText field(LinearLayout parent, String label, String hint, boolean single) {
        TextView l = monoText(this, label.toUpperCase(java.util.Locale.ROOT), 11, FAINT);
        l.setTypeface(mono(this, 600));
        l.setLetterSpacing(0.1f);
        l.setPadding(0, dp(this, 14), 0, dp(this, 6));
        parent.addView(l, full());
        EditText e = input(this, hint, true);
        e.setSingleLine(single);
        e.setContentDescription(label);
        parent.addView(e, full());
        return e;
    }

    // ---------------------------------------------------------------- helpers

    static String stateLabel(ServiceManager.Status st) {
        switch (st.state) {
            case ServiceManager.RUNNING: return "działa";
            case ServiceManager.STARTING:
                return st.restartInMs > 0 ? "restart za " + Math.max(1, (st.restartInMs + 999) / 1000) + " s" : "uruchamianie";
            case ServiceManager.EXITED: return st.exitCode == 0 ? "zakończona" : "kod " + st.exitCode;
            case ServiceManager.FAILED: return "błąd";
            default: return "zatrzymana";
        }
    }

    private static int stateColor(ServiceManager.Status st) {
        switch (st.state) {
            case ServiceManager.RUNNING: return OK;
            case ServiceManager.STARTING: return st.restartInMs > 0 ? WARN : ACCENT;
            case ServiceManager.EXITED: return st.exitCode == 0 ? MUTED : ERR;
            case ServiceManager.FAILED: return ERR;
            default: return FAINT;
        }
    }

    private static String factsText(ServiceManager.Status st) {
        StringBuilder s = new StringBuilder();
        if (st.active() && st.pid != 0) s.append("PID ").append(st.pid).append(" · ");
        if (st.active() && st.startedAtWall > 0) s.append("czas ").append(ServiceManager.duration(st.uptimeMs)).append(" · ");
        if (st.def.port != 0) s.append("port ").append(st.def.port).append(" · ");
        else if (!st.ports.isEmpty()) s.append("port ").append(st.ports.get(0)).append(" · ");
        s.append("restarty ").append(st.restarts);
        if (st.def.autostart) s.append(" · autostart");
        if (ServiceManager.RESTART_NEVER.equals(st.def.restart)) s.append(" · bez restartu");
        s.append(" · ").append(st.owner.startsWith("chat:") ? "od agenta" : st.owner.equals("owner") ? "dodana ręcznie" : "zewnętrzna");
        return s.toString();
    }

    /** Only touch text that changed: no needless layout or accessibility events every second. */
    private static void setIfChanged(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    private static String saved(int n) {
        int tens = n % 100, ones = n % 10;
        if (n == 1) return "1 zapisana";
        return n + (ones >= 2 && ones <= 4 && (tens < 12 || tens > 14) ? " zapisane" : " zapisanych");
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Brak przeglądarki do otwarcia adresu", Toast.LENGTH_SHORT).show();
        }
    }

    private void copy(String url) {
        copyText("Adres usługi", url, "Skopiowano " + url);
    }

    private void copyText(String label, String text, String toast) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }
}
