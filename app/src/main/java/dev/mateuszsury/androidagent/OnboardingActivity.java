package dev.mateuszsury.androidagent;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import static dev.mateuszsury.androidagent.Ui.*;

/** First-run guide for our own runtime; Android permissions and account login stay explicit. */
public class OnboardingActivity extends Screen {
    static final int WELCOME = 0, BRAIN = 1, ENVIRONMENT = 2, PERMISSIONS = 3, TRY = 4;
    private static final int LAST = TRY;
    private ScrollView scroll;
    private LinearLayout body, bottom, dots;
    private TextView progress;
    private int step;
    private boolean transitioning;
    private String lastRuntimeState;
    private final android.os.Handler callbacks = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Old 0.4.2 steps 2–8 described a separate Termux app. Preserve all user data and
        // resume at the embedded environment instead of interpreting those step IDs anew.
        android.content.SharedPreferences preferences = getSharedPreferences("buddy", MODE_PRIVATE);
        if (!preferences.getBoolean("onboard_embedded_v1", false)) {
            int old = Prefs.onboardStep(this);
            if (old >= 2) Prefs.setOnboardStep(this, ENVIRONMENT);
            preferences.edit().putBoolean("onboard_embedded_v1", true).apply();
        }
        Prefs.setSetupWatchUntil(this, 0);
        step = Math.max(WELCOME, Math.min(Prefs.onboardStep(this), LAST));
        if (state != null) step = Math.max(WELCOME, Math.min(state.getInt("step", step), LAST));
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);

        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        LinearLayout top = row(this);
        top.setPadding(dp(this, 24), dp(this, 10), dp(this, 8), dp(this, 4));
        top.setMinimumHeight(dp(this, 56));
        progress = monoText(this, "", 12, FAINT);
        progress.setTypeface(mono(this, 500));
        top.addView(progress);
        dots = row(this);
        dots.setPadding(dp(this, 12), 0, 0, 0);
        for (int i = 1; i <= LAST; i++) {
            View d = new View(this);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(this, 6), dp(this, 6));
            p.rightMargin = dp(this, 5);
            dots.addView(d, p);
        }
        dots.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        top.addView(dots, weight1());
        TextView skip = monoText(this, "Później", 13, MUTED);
        skip.setTypeface(mono(this, 500));
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
        skip.setMinHeight(dp(this, 44));
        skip.setBackgroundResource(ripple(this, true));
        skip.setContentDescription("Później, pomiń przewodnik");
        skip.setOnClickListener(v -> finishGuide());
        top.addView(skip);
        root.addView(top, full());
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        body = column(this);
        body.setPadding(dp(this, 24), dp(this, 8), dp(this, 24), dp(this, 24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        bottom = column(this);
        bottom.setPadding(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 16));
        root.addView(bottom, full());
        setContentView(root);
        if (state == null) {
            Motion.enter(body, 60);
            Motion.enter(bottom, 160);
        }
        Motion.animateLayout(dots);
    }

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        render();
        ping();
        lastRuntimeState = runtimeState();
        callbacks.removeCallbacks(watchRuntime);
        callbacks.postDelayed(watchRuntime, 1000);
    }

    @Override protected void onPause() {
        callbacks.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("step", step);
        super.onSaveInstanceState(out);
    }

    @Override public void onBackPressed() { goBack(); }

    private void goBack() {
        if (step > WELCOME) go(step - 1);
        else finish();
    }

    static boolean currentStepDone(Context context) {
        return done(context, Prefs.onboardStep(context));
    }

    static boolean done(Context context, int at) {
        if (at != ENVIRONMENT || !EmbeddedRuntime.get(context).ready()) return false;
        for (String agent : wanted(context)) if (!SetupState.agentInstalled(context, agent)) return false;
        return true;
    }

    static String[] wanted(Context context) {
        String agent = Prefs.agents(context);
        if ("both".equals(agent)) return new String[]{"claude", "codex"};
        return new String[]{"claude".equals(agent) ? "claude" : "codex"};
    }

    /** Move to a step with a short slide: forward slides left, back slides right. */
    private void go(int next) {
        int target = Math.max(WELCOME, Math.min(next, LAST));
        if (transitioning) return;
        final int direction = target >= step ? 1 : -1;
        Runnable swap = () -> {
            step = target;
            Prefs.setOnboardStep(this, step);
            scroll.scrollTo(0, 0);
            render();
            if (step == ENVIRONMENT) ping();
        };
        if (!Motion.enabled() || body.getWindowToken() == null) {
            swap.run();
            return;
        }
        transitioning = true;
        float shift = dp(this, 28);
        for (View v : new View[]{body, bottom}) {
            v.animate().cancel();
            v.animate().alpha(0f).translationX(-direction * shift).setStartDelay(0).setDuration(130)
                    .setInterpolator(Motion.EASE_IN_OUT).start();
        }
        body.postDelayed(() -> {
            transitioning = false;
            if (isFinishing() || isDestroyed()) return;
            swap.run();
            for (View v : new View[]{body, bottom}) {
                v.setTranslationX(direction * shift);
                v.animate().alpha(1f).translationX(0f).setStartDelay(v == bottom ? 40 : 0)
                        .setDuration(Motion.MEDIUM + 40).setInterpolator(Motion.EASE_OUT).start();
            }
        }, 140);
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
            if (!state.equals(lastRuntimeState)) {
                lastRuntimeState = state;
                if (!transitioning && (step == ENVIRONMENT || step == TRY)) render();
            }
            callbacks.postDelayed(this, 1000);
        }
    };

    private void ping() {
        if (!EmbeddedRuntime.get(this).ready()) return;
        withToolHost(() -> EmbeddedRuntime.get(this).refreshAccounts());
    }

    private void finishGuide() {
        Prefs.setOnboarded(this, true);
        Prefs.setSetupWatchUntil(this, 0);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    private void indicator() {
        progress.setText(step == WELCOME ? "Witaj" : "Krok " + step + " z " + LAST);
        for (int i = 0; i < dots.getChildCount(); i++) {
            View d = dots.getChildAt(i);
            int n = i + 1;
            boolean current = n == step, passed = n < step;
            d.setBackground(rounded(this, current || passed ? (passed ? Theme.withAlpha(ACCENT, 0x80) : ACCENT) : OUTLINE, 3));
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) d.getLayoutParams();
            int w = dp(this, current ? 18 : 6);
            if (p.width != w) {
                p.width = w;
                d.setLayoutParams(p);
            }
        }
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        int scrollY = scroll.getScrollY();
        body.removeAllViews();
        bottom.removeAllViews();
        indicator();
        switch (step) {
            case WELCOME:
                welcome();
                break;
            case BRAIN:
                brain();
                break;
            case ENVIRONMENT:
                environment();
                break;
            case PERMISSIONS:
                header("Uprawnienia Androida", "Rozmowy i terminal działają bez dostępu do innych aplikacji.");
                bullet(R.drawable.ic_remote, "Sterowanie ekranem",
                        "Usługa ułatwień dostępu pozwala czytać i sterować ekranem. Jej włączenie wymaga Twojej zgody w ustawieniach systemu.");
                bullet(R.drawable.ms_mic, "Powiadomienia, mikrofon, bateria",
                        "Powiadomienia pokazują postęp i przycisk Stop. Mikrofon jest potrzebny tylko do rozmów głosowych. Wyjątek optymalizacji baterii pomaga przy długich zadaniach.");
                bullet(R.drawable.ms_shield_person, "Twoje usługi zostają",
                        "Możesz zachować inne włączone usługi ułatwień dostępu. androidagent ich nie zastępuje.");
                primary("Przegląd uprawnień", v -> PermissionGuide.open(this));
                secondary("Dalej", v -> go(TRY));
                break;
            case TRY:
                boolean account = SetupState.agentReady(this, Prefs.backend(this));
                header(account ? "Gotowy do rozmowy" : "Zacznij od telefonu",
                        account ? "Klient jest przygotowany i zalogowany do konta." : "Lokalny stan telefonu jest dostępny także przed logowaniem.");
                body.addView(statusPreview(), gap(22));
                note("Komenda /status odczytuje stan telefonu lokalnie. Ten wynik nie jest odpowiedzią modelu.");
                if (!account) note("Aby rozmawiać z Codexem lub Claude Code, otwórz środowisko i zaloguj się do wybranego konta.");
                primary("Sprawdź stan telefonu", v -> {
                    Prefs.setOnboarded(this, true);
                    Sessions.Session session = ChatHub.get(this).create(Prefs.backend(this));
                    ChatHub.get(this).select(session);
                    startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    startActivity(new Intent(this, ChatActivity.class).putExtra("id", session.id).putExtra("draft", "/status"));
                    finish();
                });
                secondary("Przejdź do rozmów", v -> finishGuide());
                break;
            default: break;
        }
        scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    private void welcome() {
        Spark spark = new Spark(this).color(ACCENT).rays(12);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(this, 56), dp(this, 56));
        sp.topMargin = dp(this, 28);
        body.addView(spark, sp);
        Motion.pop(spark, 80);
        TextView title = title(this, "Poznaj androidagent", 30);
        title.setAccessibilityHeading(true);
        title.setPadding(0, dp(this, 22), 0, dp(this, 6));
        body.addView(title);
        body.addView(text(this, "Codex i Claude Code na Twoim telefonie.", 16, MUTED));
        LinearLayout box = terminalBox(this, "co dostajesz", Theme.withAlpha(FAINT, 0xC0));
        box.setPadding(dp(this, 16), dp(this, 20), dp(this, 16), dp(this, 6));
        feature(box, R.drawable.ms_terminal, "Własne środowisko",
                "Jedna aplikacja zawiera własne środowisko terminalowe, pliki i narzędzia telefonu. Nie musisz instalować osobnej aplikacji Termux.");
        feature(box, R.drawable.ms_shield_person, "Twoje konto",
                "Oficjalne klienty wymagają internetu i Twojego konta. Przygotowanie narzędzi jest automatyczne, a logowanie wykonujesz samodzielnie przez oficjalną stronę.");
        feature(box, R.drawable.ms_phone, "Sterowanie opcjonalne",
                "Sterowanie ekranem jest opcjonalne. Uprawnienia systemowe wybierasz w ustawieniach Androida.");
        body.addView(box, gap(26));
        Motion.stagger(box, 160, 60);
        primary("Zaczynamy", v -> go(BRAIN));
    }

    private void feature(LinearLayout box, int icon, String title, String text) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.TOP);
        r.setPadding(0, 0, 0, dp(this, 14));
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(rounded(this, CARD2, 9));
        tile.addView(icon(this, icon, ACCENT_TEXT, 17), new FrameLayout.LayoutParams(dp(this, 17), dp(this, 17), Gravity.CENTER));
        r.addView(tile, new LinearLayout.LayoutParams(dp(this, 30), dp(this, 30)));
        LinearLayout col = column(this);
        col.setPadding(dp(this, 12), dp(this, 2), 0, 0);
        col.addView(title(this, title, 15));
        TextView t = text(this, text, 13, MUTED);
        t.setLineSpacing(dp(this, 2), 1f);
        t.setPadding(0, dp(this, 2), 0, 0);
        col.addView(t);
        r.addView(col, weight1());
        box.addView(r, full());
    }

    private void brain() {
        header("Wybierz agenta", "Możesz zmienić domyślny wybór w ustawieniach i dodać drugiego klienta.");
        int index = 0;
        for (String[] option : new String[][]{
                {"codex", "Codex", "Oficjalny klient z Twoim kontem ChatGPT."},
                {"claude", "Claude Code", "Oficjalny klient z Twoim kontem Claude."},
                {"both", "Obaj", "Codex pozostaje domyślnym agentem nowych rozmów."}}) {
            boolean selected = option[0].equals(Prefs.agents(this));
            LinearLayout card = row(this);
            card.setPadding(dp(this, 14), dp(this, 14), dp(this, 14), dp(this, 14));
            GradientDrawable bg = rounded(this, selected ? Theme.blend(ACCENT, CARD, 0.07f) : CARD, 14);
            bg.setStroke(selected ? Math.round(1.5f * getResources().getDisplayMetrics().density) : dp(this, 1),
                    selected ? ACCENT : LINE);
            card.setBackground(bg);
            card.setForeground(getDrawable(ripple(this, false)));
            card.setClipToOutline(true);
            View tile;
            if ("both".equals(option[0])) {
                TextView t = monoText(this, "+", 17, INFO);
                t.setTypeface(mono(this, 600));
                t.setGravity(Gravity.CENTER);
                t.setBackground(rounded(this, INFO_SOFT, 12));
                tile = t;
            } else {
                tile = agentTile(this, option[0]);
            }
            card.addView(tile, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
            LinearLayout col = column(this);
            col.setPadding(dp(this, 14), 0, dp(this, 10), 0);
            col.addView(title(this, option[1], 16));
            TextView d = text(this, option[2], 13, MUTED);
            d.setPadding(0, dp(this, 2), 0, 0);
            col.addView(d);
            card.addView(col, weight1());
            card.addView(radio(selected), new LinearLayout.LayoutParams(dp(this, 22), dp(this, 22)));
            card.setContentDescription(option[1] + (selected ? ", wybrany" : ""));
            card.setOnClickListener(v -> {
                if (selected) return;
                Prefs.setAgents(this, option[0]);
                Prefs.setBackend(this, "claude".equals(option[0]) ? "claude" : "codex");
                render();
            });
            Motion.press(card);
            LinearLayout.LayoutParams params = full();
            params.topMargin = dp(this, index == 0 ? 22 : 10);
            body.addView(card, params);
            index++;
        }
        primary("Dalej", v -> go(ENVIRONMENT));
    }

    private View radio(boolean on) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(outlined(this, 0x00000000, 11, on ? ACCENT : OUTLINE, on ? 2 : 1));
        if (on) {
            View inner = new View(this);
            inner.setBackground(oval(ACCENT));
            f.addView(inner, new FrameLayout.LayoutParams(dp(this, 10), dp(this, 10), Gravity.CENTER));
            Motion.pop(inner, 0);
        }
        return f;
    }

    private void environment() {
        EmbeddedRuntime runtime = EmbeddedRuntime.get(this);
        header("Wbudowane środowisko", "Terminal i oficjalni klienci działają wewnątrz androidagent.");
        boolean ready = runtime.ready(), busy = runtime.busy();
        boolean failed = runtime.error() != null && !runtime.error().isEmpty();
        String status = ready ? "Środowisko gotowe" : runtime.status();
        if (status == null || status.isEmpty()) status = "Środowisko oczekuje na przygotowanie.";
        LinearLayout box = terminalBox(this, "~/.buddy", ready ? Theme.withAlpha(OK, 0xB0) : busy ? Theme.withAlpha(ACCENT, 0xB0) : Theme.withAlpha(FAINT, 0xC0));
        box.setPadding(dp(this, 16), dp(this, 20), dp(this, 16), dp(this, 16));
        LinearLayout line = row(this);
        if (busy) {
            line.addView(new Spark(this).color(ACCENT), new LinearLayout.LayoutParams(dp(this, 15), dp(this, 15)));
        } else {
            line.addView(toolDot(this, ready ? OK : failed ? ERR : FAINT));
        }
        TextView st = monoText(this, status + (busy ? " · " + runtime.progress() + "%" : ""), 13, ready ? TEXT : MUTED);
        st.setPadding(dp(this, 8), 0, 0, 0);
        line.addView(st, weight1());
        box.addView(line, full());
        if (busy) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(runtime.progress());
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(CARD2));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 4));
            bp.topMargin = dp(this, 12);
            box.addView(bar, bp);
        }
        if (failed) {
            TextView error = text(this, runtime.error(), 13, ERR);
            error.setPadding(dp(this, 22), dp(this, 8), 0, 0);
            box.addView(error, full());
        }
        body.addView(box, gap(22));
        note("Otwórz panel środowiska, aby zobaczyć postęp, ponowić pobieranie lub otworzyć pliki i terminal. Przygotowanie środowiska nie zastępuje logowania do konta.");
        for (String backend : wanted(this)) {
            boolean installed = SetupState.agentInstalled(this, backend);
            boolean signed = SetupState.agentReady(this, backend);
            String name = "claude".equals(backend) ? "Claude Code" : "Codex";
            LinearLayout card = card(this);
            LinearLayout head = row(this);
            head.addView(agentTile(this, backend), new LinearLayout.LayoutParams(dp(this, 36), dp(this, 36)));
            LinearLayout col = column(this);
            col.setPadding(dp(this, 12), 0, 0, 0);
            col.addView(title(this, name, 15));
            TextView s = monoText(this, signed ? "zainstalowany · zalogowany"
                    : installed ? "zainstalowany · wymaga logowania" : "jeszcze nieprzygotowany", 11, signed ? OK : installed ? WARN : FAINT);
            s.setPadding(0, dp(this, 2), 0, 0);
            col.addView(s);
            head.addView(col, weight1());
            card.addView(head, full());
            Button login = button(this, signed ? "Sprawdź konto" : installed ? "Zaloguj się" : "Przygotuj klienta", false);
            login.setOnClickListener(v -> {
                if (signed) ping();
                else if (installed) TermuxSetup.signIn(this, backend);
                else EmbeddedRuntime.open(this);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 42));
            lp.topMargin = dp(this, 12);
            card.addView(login, lp);
            body.addView(card, gap(12));
        }
        primary(busy ? "Pokaż postęp" : "Otwórz środowisko", v -> EmbeddedRuntime.open(this));
        secondary("Dalej", v -> go(PERMISSIONS));
    }

    /** A dark terminal panel previewing what /status does. */
    private View statusPreview() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, "› ", ACCENT);
        append(sb, "/status\n", CODE_TEXT);
        append(sb, "└ ", CODE_MUTED);
        append(sb, "Odczyt lokalny: bateria, pamięć, sieć\n", CODE_MUTED);
        append(sb, "└ ", CODE_MUTED);
        append(sb, "bez modelu i bez logowania", CODE_MUTED);
        TextView t = terminalText(this, "");
        t.setText(sb);
        t.setTextIsSelectable(false);
        return t;
    }

    private static void append(SpannableStringBuilder sb, String s, int color) {
        int start = sb.length();
        sb.append(s);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void header(String heading, String subtitle) {
        TextView title = title(this, heading, 28);
        title.setAccessibilityHeading(true);
        title.setPadding(0, dp(this, 20), 0, dp(this, 8));
        body.addView(title);
        TextView sub = text(this, subtitle, 15, MUTED);
        sub.setLineSpacing(dp(this, 2), 1f);
        body.addView(sub);
    }

    private void bullet(int icon, String title, String text) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.TOP);
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(outlined(this, CARD, 10, LINE, 1));
        tile.addView(icon(this, icon, TEXT, 18), new FrameLayout.LayoutParams(dp(this, 18), dp(this, 18), Gravity.CENTER));
        r.addView(tile, new LinearLayout.LayoutParams(dp(this, 34), dp(this, 34)));
        LinearLayout col = column(this);
        col.setPadding(dp(this, 14), dp(this, 2), 0, 0);
        col.addView(title(this, title, 15));
        TextView t = text(this, text, 14, MUTED);
        t.setLineSpacing(dp(this, 2), 1f);
        t.setPadding(0, dp(this, 3), 0, 0);
        col.addView(t);
        r.addView(col, weight1());
        body.addView(r, gap(20));
    }

    private void note(String message) {
        TextView note = text(this, message, 14, MUTED);
        note.setLineSpacing(dp(this, 3), 1);
        body.addView(note, gap(16));
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams params = full();
        params.topMargin = dp(this, top);
        return params;
    }

    private void primary(String label, View.OnClickListener listener) {
        Button button = button(this, label, true);
        button.setTextSize(15);
        button.setOnClickListener(listener);
        bottom.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 50)));
    }

    private void secondary(String label, View.OnClickListener listener) {
        Button button = button(this, label, false);
        button.setBackground(outlined(this, 0x00000000, 10, OUTLINE, 1));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 46));
        p.topMargin = dp(this, 8);
        bottom.addView(button, p);
    }
}
