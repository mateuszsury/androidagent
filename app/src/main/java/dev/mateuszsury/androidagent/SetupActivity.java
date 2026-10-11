package dev.mateuszsury.androidagent;

import android.app.Dialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import static dev.mateuszsury.androidagent.Ui.*;

/** Settings: embedded environment, account state and the existing appearance preferences. */
public class SetupActivity extends Screen {
    private ScrollView scroll;
    private LinearLayout list;
    /** Setup card open state; null = automatic (open until all steps are done). */
    private Boolean setupOpen;
    private final android.os.Handler callbacks = new android.os.Handler(android.os.Looper.getMainLooper());
    private String runtimeState;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        build();
    }

    private void build() {
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        scroll = new ScrollView(this);
        list = column(this);
        list.setPadding(0, dp(this, 12), 0, dp(this, 24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(bottomNav(this, NAV_SETTINGS, ChatHub.get(this).runningCount()), full());
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        render();
        runtimeState = runtimeState();
        callbacks.removeCallbacks(watchRuntime);
        callbacks.postDelayed(watchRuntime, 1000);
        ping();
    }

    @Override protected void onNotificationPermissionChanged() { render(); }

    @Override protected void onPause() {
        callbacks.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private String runtimeState() {
        EmbeddedRuntime runtime = EmbeddedRuntime.get(this);
        return runtime.ready() + ":" + runtime.busy() + ":" + runtime.status() + ":"
                + runtime.progress() + ":" + runtime.error() + ":" + Prefs.helperCheckedAt(this);
    }

    private final Runnable watchRuntime = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            String state = runtimeState();
            if (!state.equals(runtimeState)) { runtimeState = state; render(); }
            callbacks.postDelayed(this, 1000);
        }
    };

    /** Checks the installed clients' actual account status without displaying credentials. */
    private void ping() {
        if (!EmbeddedRuntime.get(this).ready()) return;
        withToolHost(() -> {
            EmbeddedRuntime.get(this).refreshAccounts();
            callbacks.postDelayed(this::render, 1200);
            callbacks.postDelayed(this::render, 4100);
        });
    }

    /** Appearance changed on this screen: restyle everything in place, keeping the scroll position. */
    private void themeChanged() {
        Theme.changed(this);
        rethemed();
        int y = scroll.getScrollY();
        build();
        render();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private static final class Step {
        String title, body, action, action2;
        Boolean done;
        boolean optional;
        View.OnClickListener l, l2;
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        int scrollY = scroll.getScrollY();
        list.removeAllViews();

        LinearLayout head = column(this);
        head.setPadding(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 4));
        head.addView(title(this, "Ustawienia", 28));
        TextView sub = text(this, "Codex z kontem ChatGPT lub Claude Code z kontem Claude. Oficjalni klienci działają we wbudowanym środowisku na tym telefonie.", 14, MUTED);
        sub.setPadding(0, dp(this, 4), 0, 0);
        head.addView(sub);
        list.addView(head, full());

        // ---------------------------------------------------------------- setup
        EmbeddedRuntime runtime = EmbeddedRuntime.get(this);
        boolean a11y = SetupState.accessibilityEnabled(this);
        boolean hasClaude = SetupState.agentInstalled(this, "claude");
        boolean hasCodex = SetupState.agentInstalled(this, "codex");
        String backend = Prefs.backend(this);
        String agent = "codex".equals(backend) ? "Codex" : "Claude Code";
        boolean agentInstalled = SetupState.agentInstalled(this, backend);
        boolean accountReady = SetupState.agentReady(this, backend);
        Step[] steps = new Step[5];

        String environment = runtime.ready()
                ? "Terminal, pliki i most narzędzi są dostępne w androidagent. Nie potrzebujesz osobnej aplikacji Termux."
                : runtime.busy() ? runtime.status() + " · " + runtime.progress() + "%"
                : "androidagent przygotowuje własne środowisko terminalowe i oficjalnych klientów. Pobieranie klientów wymaga internetu.";
        if (runtime.error() != null && !runtime.error().isEmpty()) environment = runtime.error();
        steps[0] = step("Wbudowane środowisko", environment, runtime.ready(),
                runtime.ready() ? "Otwórz środowisko" : runtime.busy() ? "Pokaż postęp" : "Przygotuj / ponów",
                v -> EmbeddedRuntime.open(this), null, null);

        String account = accountReady ? agent + " jest zainstalowany i zalogowany."
                : !agentInstalled ? "Najpierw przygotuj klienta " + agent + " w panelu środowiska."
                : "Klient " + agent + " jest gotowy. Zaloguj się do własnego konta przez oficjalną stronę. Instalacja środowiska nie loguje Cię automatycznie.";
        steps[1] = step("Konto " + agent, account, accountReady,
                accountReady ? "Sprawdź konto" : agentInstalled ? "Zaloguj się" : "Przygotuj klienta",
                v -> {
                    if (accountReady) ping();
                    else if (agentInstalled) TermuxSetup.signIn(this, backend);
                    else EmbeddedRuntime.open(this);
                }, agentInstalled && !accountReady ? "Sprawdź ponownie" : null, v -> ping());

        String a11yText = "Pozwala odczytywać ekran, dotykać i wpisywać tekst w innych aplikacjach. Rozmowy, terminal i Docker działają bez tej zgody.";
        if (!a11y && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            a11yText += " Android może najpierw wymagać zezwolenia na ustawienia z ograniczeniami w informacjach o aplikacji.";
        steps[2] = step("Sterowanie ekranem (opcjonalne)", a11yText, a11y,
                "Przegląd uprawnień", v -> PermissionGuide.open(this), null, null);
        steps[2].optional = true;

        PowerManager pm = getSystemService(PowerManager.class);
        boolean background = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        steps[3] = step("Praca w tle (opcjonalne)",
                "Wyjątek optymalizacji baterii pomaga utrzymać długie zadania. Android wymaga Twojej zgody; ustawienie dotyczy tylko androidagent.",
                background, "Przegląd uprawnień", v -> PermissionGuide.open(this), null, null);
        steps[3].optional = true;

        steps[4] = step("Pierwsza rozmowa", Prefs.hadReply(this)
                        ? "Historia rozmów jest zapisana. Możesz wrócić do niej z ekranu głównego."
                        : "Po zalogowaniu rozpocznij rozmowę. /status odczytuje stan telefonu lokalnie i działa bez modelu.",
                Prefs.hadReply(this), "Otwórz rozmowy", v -> startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)), null, null);
        steps[4].optional = true;
        setupCard(steps);

        list.addView(sectionLabel(this, "Powiadomienia"));
        LinearLayout notifications = card();
        notifications.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 16));
        notifications.addView(title(this, NotificationPermission.enabled(this)
            ? "Powiadomienia aplikacji włączone" : "Powiadomienia aplikacji wyłączone", 15));
        notifications.addView(text(this, "Pokazują pracę agentów, USB i Dockera oraz przycisk Stop. "
            + "Zgoda jest opcjonalna — funkcje działają także bez powiadomień.", 13, MUTED), topGap(6));
        Button notificationSettings = button(this, "Ustawienia powiadomień", false);
        notificationSettings.setOnClickListener(v -> startActivity(NotificationPermission.settingsIntent(this)));
        notifications.addView(notificationSettings, topGap(12));
        addCard(notifications);

        // ----------------------------------------------------------- appearance
        list.addView(sectionLabel(this, "Appearance"));
        LinearLayout ap = card();
        ap.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 16));
        ap.addView(fieldLabel("Mode", null));
        ap.addView(segmented(this, Theme.MODES, Prefs.themeMode(this), false, v -> {
            Prefs.setThemeMode(this, v);
            themeChanged();
        }), topGap(8));
        ap.addView(fieldLabel("Accent", Theme.hex(Prefs.accent(this))), topGap(18));
        ap.addView(swatches(), topGap(10));
        ap.addView(fieldLabel("Surface", null), topGap(18));
        ap.addView(segmented(this, Theme.SURFACES, Prefs.surface(this), false, v -> {
            Prefs.setSurface(this, v);
            themeChanged();
        }), topGap(8));
        TextView sn = text(this, "Warm is androidagent original tone. Black (true black) applies in dark mode only.", 13, MUTED);
        ap.addView(sn, topGap(8));
        addCard(ap);

        // ---------------------------------------------------------------- agent
        list.addView(sectionLabel(this, "Agent"));
        LinearLayout ag = card();
        ag.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 16));
        ag.addView(title(this, "Domyślny agent", 15));
        TextView brainInfo = text(this, "Nowe rozmowy użyją wybranego agenta. Claude Code korzysta z Twojego konta Claude, a Codex z konta ChatGPT. "
                + "Dostępni klienci: " + (hasClaude && hasCodex ? "obaj" : hasClaude ? "Claude Code"
                : hasCodex ? "Codex" : "jeszcze nieprzygotowani") + ".", 13, MUTED);
        brainInfo.setPadding(0, dp(this, 2), 0, 0);
        ag.addView(brainInfo);
        ag.addView(segmented(this, new String[][]{{"claude", "Claude Code"}, {"codex", "Codex"}}, backend, false, v -> {
            Prefs.setBackend(this, v);
            if (!"both".equals(Prefs.agents(this))) Prefs.setAgents(this, v);
            Toast.makeText(this, "New chats will use " + ("codex".equals(v) ? "Codex" : "Claude Code")
                    + ". You can pick per chat with the New chat button.", Toast.LENGTH_LONG).show();
            render();
        }), topGap(12));
        addCard(ag);

        // ----------------------------------------------------------- experience
        list.addView(sectionLabel(this, "Experience"));
        LinearLayout ex = card();
        ex.addView(switchRow(this, "Chat bubble", "Floats over other apps. Drag it onto the ✕ to hide it; "
                + "approval cards still pop up when an agent needs your OK.", Prefs.bubbleEnabled(this), on -> {
            Prefs.setBubbleEnabled(this, on);
            if (on) HiddenNotice.cancel(this);
            BuddyService svc = BuddyService.get();
            if (svc != null) svc.applyBubbleSetting();
        }), full());
        ex.addView(divider(), lineParams());
        ex.addView(switchRow(this, "Read replies aloud", "Talk with the mic in any chat; it sends when you stop speaking.",
                Prefs.speakReplies(this), on -> {
                    Prefs.setSpeakReplies(this, on);
                    if (!on) Voice.stopSpeaking();
                }), full());
        ex.addView(divider(), lineParams());
        final boolean dev = Prefs.devMode(this);
        ex.addView(switchRow(this, "Tryb programisty", "Codex i Claude Code mogą wykonywać komendy i zmieniać pliki dostępne dla androidagent. "
                + "Katalog startowy: ~/.buddy/work. Dostęp obejmuje również inne pliki dostępne dla aplikacji.", dev, on -> {
            Prefs.setDevMode(this, on);
            render();
        }), full());
        if (dev) {
            LinearLayout warn = row(this);
            warn.setGravity(Gravity.TOP);
            warn.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            warn.setBackground(rounded(this, ERR_SOFT, 12));
            warn.addView(icon(this, R.drawable.ms_warning, ERR, 18));
            TextView wt = text(this, "Komendy agentów we wbudowanym środowisku wykonują się bez osobnego potwierdzenia. "
                    + "Android ogranicza je do uprawnień androidagent; nie są ograniczone do katalogu projektu. "
                    + "Komendy narzędzia telefonu run_command wymagają zatwierdzenia dokładnej treści.", 13, ERR);
            wt.setPadding(dp(this, 10), 0, 0, 0);
            warn.addView(wt, weight1());
            LinearLayout.LayoutParams wp = full();
            wp.setMargins(dp(this, 12), 0, dp(this, 12), dp(this, 12));
            ex.addView(warn, wp);
        }
        addCard(ex);

        // ------------------------------------------------------- remote control
        list.addView(sectionLabel(this, "Zdalna kontynuacja Claude"));
        LinearLayout rc = card();
        rc.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        rc.addView(text(this, "Przycisk telefonu w rozmowie Claude Code otwiera tę sesję we wbudowanym terminalu "
                + "z Remote Control. Możesz kontynuować ją w oficjalnej aplikacji Claude. Dostępność zależy od konta Claude.", 14, MUTED));
        addCard(rc);

        list.addView(sectionLabel(this, "Narzędzia agentów"));
        LinearLayout pt = card();
        pt.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 16));
        pt.addView(text(this, "Most narzędzi jest konfigurowany automatycznie dla rozmów w androidagent. "
                + "Terminal, pliki i oficjalne logowanie znajdziesz w panelu środowiska. Uprawnienia Androida pozostają pod Twoją kontrolą.", 14, MUTED));
        Button runtimeButton = button(this, "Otwórz środowisko i klientów", false);
        runtimeButton.setOnClickListener(v -> EmbeddedRuntime.open(this));
        pt.addView(runtimeButton, topGap(12));
        Button permissionsButton = button(this, "Przegląd uprawnień", false);
        permissionsButton.setOnClickListener(v -> PermissionGuide.open(this));
        pt.addView(permissionsButton, topGap(8));
        addCard(pt);

        TextView ver = monoText(this, "androidagent " + BuildInfo.VERSION + " · " + getPackageName(), 12, MUTED);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(this, 24), 0, 0);
        list.addView(ver, full());
        scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    // ------------------------------------------------------------- setup card

    private Step step(String title, String body, Boolean done, String action, View.OnClickListener l,
                      String action2, View.OnClickListener l2) {
        Step s = new Step();
        s.title = title;
        s.body = body;
        s.done = done;
        s.action = action;
        s.l = l;
        s.action2 = action2;
        s.l2 = l2;
        return s;
    }

    private void setupCard(Step[] steps) {
        int done = 0, next = -1, required = 0;
        for (int i = 0; i < steps.length; i++) {
            if (steps[i].optional) continue;
            required++;
            if (Boolean.TRUE.equals(steps[i].done)) done++;
            else if (next < 0) next = i;
        }
        boolean complete = next < 0;
        boolean open = setupOpen != null ? setupOpen : !complete;

        LinearLayout c = card();
        c.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        LinearLayout head = row(this);
        head.addView(title(this, "Przygotowanie", 17));
        TextView n = monoText(this, done + "/" + required, 12, complete ? OK : ACCENT_TEXT);
        n.setTypeface(mono(this, 600));
        n.setPadding(dp(this, 8), dp(this, 2), 0, 0);
        head.addView(n, weight1());
        head.addView(icon(this, open ? R.drawable.ms_expand_less : R.drawable.ms_expand_more, MUTED, 22));
        head.setOnClickListener(v -> {
            setupOpen = !open;
            render();
        });
        c.addView(head, full());

        FrameLayout bar = new FrameLayout(this);
        bar.setBackground(rounded(this, CARD2, 3));
        View fill = new View(this);
        fill.setBackground(rounded(this, complete ? OK : ACCENT, 3));
        bar.addView(fill, new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT));
        final int doneF = done;
        final int requiredF = Math.max(1, required);
        bar.post(() -> {
            FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) fill.getLayoutParams();
            fp.width = bar.getWidth() * doneF / requiredF;
            fill.setLayoutParams(fp);
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 6));
        bp.topMargin = dp(this, 10);
        c.addView(bar, bp);

        if (!complete) {
            TextView guide = text(this, "Otwórz przewodnik", 14, ACCENT_TEXT);
            guide.setTypeface(sans(this, 600));
            guide.setPadding(0, dp(this, 12), 0, 0);
            guide.setOnClickListener(v -> startActivity(new Intent(this, OnboardingActivity.class)));
            c.addView(guide);
        }
        if (!open) {
            TextView s = text(this, complete ? "Środowisko i konto są gotowe." : "Następny krok: " + steps[next].title, 13, MUTED);
            s.setPadding(0, dp(this, 10), 0, 0);
            c.addView(single(s));
        } else {
            for (int i = 0; i < steps.length; i++) {
                Step st = steps[i];
                boolean isDone = Boolean.TRUE.equals(st.done), isNext = i == next;
                LinearLayout r = row(this);
                r.setGravity(Gravity.TOP);
                r.setPadding(0, dp(this, 14), 0, 0);
                FrameLayout circle = new FrameLayout(this);
                if (isDone) {
                    circle.setBackground(oval(OK));
                    ImageView ck = icon(this, R.drawable.ms_check, 0xFFFFFFFF, 16);
                    circle.addView(ck, new FrameLayout.LayoutParams(dp(this, 16), dp(this, 16), Gravity.CENTER));
                } else {
                    circle.setBackground(outlined(this, 0x00000000, 12, isNext ? ACCENT : CARD2, 2));
                    TextView num = monoText(this, String.valueOf(i + 1), 12, isNext ? ACCENT_TEXT : MUTED);
                    num.setTypeface(mono(this, 600));
                    num.setGravity(Gravity.CENTER);
                    circle.addView(num, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
                }
                r.addView(circle, new LinearLayout.LayoutParams(dp(this, 24), dp(this, 24)));
                LinearLayout col = column(this);
                col.setPadding(dp(this, 12), dp(this, 2), 0, 0);
                TextView t = title(this, st.title, 15);
                if (!isDone && !isNext) t.setTextColor(MUTED);
                col.addView(t);
                if (isNext || st.optional) {
                    TextView b = text(this, st.body, 14, MUTED);
                    b.setPadding(0, dp(this, 4), 0, 0);
                    col.addView(b);
                    if (st.action != null || st.action2 != null) {
                        LinearLayout btns = row(this);
                        if (st.action != null) {
                            Button a = button(this, st.action, true);
                            a.setOnClickListener(st.l);
                            btns.addView(a, wrap());
                        }
                        if (st.action2 != null) {
                            Button a2 = button(this, st.action2, false);
                            a2.setOnClickListener(st.l2);
                            LinearLayout.LayoutParams a2p = wrap();
                            if (st.action != null) {
                                btns.setOrientation(LinearLayout.VERTICAL); // two buttons side by side don't fit
                                btns.setGravity(Gravity.START);
                                a2p.topMargin = dp(this, 8);
                            }
                            btns.addView(a2, a2p);
                        }
                        LinearLayout.LayoutParams bt = full();
                        bt.topMargin = dp(this, 10);
                        col.addView(btns, bt);
                    }
                }
                r.addView(col, weight1());
                c.addView(r, full());
            }
        }
        LinearLayout.LayoutParams p = full();
        p.setMargins(dp(this, 16), dp(this, 14), dp(this, 16), 0);
        list.addView(c, p);
    }

    // ----------------------------------------------------------- appearance

    private View fieldLabel(String s, String right) {
        LinearLayout r = row(this);
        r.addView(title(this, s, 15), weight1());
        if (right != null) r.addView(monoText(this, right, 12, MUTED));
        return r;
    }

    private View swatches() {
        LinearLayout r = row(this);
        int current = Prefs.accent(this);
        boolean preset = false;
        for (String[] a : Theme.ACCENTS) {
            int col = Theme.parse(a[0], 0xFFD97757);
            boolean on = col == current;
            preset |= on;
            r.addView(swatch(col, on, 0, a[1], v -> {
                Prefs.setAccent(this, col);
                themeChanged();
            }), new LinearLayout.LayoutParams(0, dp(this, 40), 1));
        }
        r.addView(swatch(preset ? CARD2 : current, !preset, preset ? R.drawable.ms_add : 0, "Custom colour",
                v -> customAccent()), new LinearLayout.LayoutParams(0, dp(this, 40), 1));
        return r;
    }

    private View swatch(int color, boolean on, int iconRes, String desc, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        f.setContentDescription(desc);
        FrameLayout ring = new FrameLayout(this);
        if (on) ring.setBackground(outlined(this, 0x00000000, 20, TEXT, 2));
        View dotV = new View(this);
        dotV.setBackground(oval(color));
        ring.addView(dotV, new FrameLayout.LayoutParams(dp(this, 30), dp(this, 30), Gravity.CENTER));
        if (iconRes != 0) {
            ImageView ic = icon(this, iconRes, MUTED, 18);
            ring.addView(ic, new FrameLayout.LayoutParams(dp(this, 18), dp(this, 18), Gravity.CENTER));
        }
        f.addView(ring, new FrameLayout.LayoutParams(dp(this, 40), dp(this, 40), Gravity.CENTER));
        f.setOnClickListener(l);
        return f;
    }

    private void customAccent() {
        LinearLayout c = column(this);
        c.addView(title(this, "Custom accent", 18));
        TextView s = text(this, "Any colour as a hex code, like #4F8BFF.", 13, MUTED);
        s.setPadding(0, dp(this, 2), 0, dp(this, 12));
        c.addView(s);
        LinearLayout r = row(this);
        final View preview = new View(this);
        preview.setBackground(oval(Prefs.accent(this)));
        r.addView(preview, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
        final EditText et = new EditText(this);
        et.setText(Theme.hex(Prefs.accent(this)));
        et.setTextColor(TEXT);
        et.setTypeface(mono(this, 400));
        et.setTextSize(15);
        et.setSingleLine(true);
        et.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        et.setBackground(outlined(this, BG, 12, OUTLINE, 1));
        LinearLayout.LayoutParams ep = weight1();
        ep.leftMargin = dp(this, 12);
        r.addView(et, ep);
        Button apply = button(this, "Apply", true);
        LinearLayout.LayoutParams app = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 44));
        app.leftMargin = dp(this, 8);
        r.addView(apply, app);
        c.addView(r, full());
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence x, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence x, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                String h = e.toString().trim();
                boolean ok = h.replace("#", "").length() == 6;
                int col = ok ? Theme.parse(h, 0) : 0;
                ok &= col != 0;
                preview.setBackground(oval(ok ? col : CARD2));
                apply.setEnabled(ok);
                apply.setAlpha(ok ? 1f : 0.4f);
            }
        });
        final Dialog d = bottomSheet(this, c);
        apply.setOnClickListener(v -> {
            int col = Theme.parse(et.getText().toString().trim(), 0);
            if (col == 0) return;
            d.dismiss();
            Prefs.setAccent(this, col);
            themeChanged();
        });
        d.show();
    }

    // ---------------------------------------------------------------- helpers

    private LinearLayout card() {
        LinearLayout c = column(this);
        c.setBackground(rounded(this, CARD, 16));
        return c;
    }

    private void addCard(View c) {
        LinearLayout.LayoutParams p = full();
        p.setMargins(dp(this, 16), dp(this, 6), dp(this, 16), 0);
        list.addView(c, p);
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        return v;
    }

    private LinearLayout.LayoutParams lineParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 1));
        p.leftMargin = p.rightMargin = dp(this, 16);
        return p;
    }

    private LinearLayout.LayoutParams topGap(int dpv) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, dpv);
        return p;
    }

}
