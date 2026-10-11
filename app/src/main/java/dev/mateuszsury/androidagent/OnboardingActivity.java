package dev.mateuszsury.androidagent;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static dev.mateuszsury.androidagent.Ui.*;

/** First-run guide for our own runtime; Android permissions and account login stay explicit. */
public class OnboardingActivity extends Screen {
    static final int WELCOME = 0, BRAIN = 1, ENVIRONMENT = 2, PERMISSIONS = 3, TRY = 4;
    private static final int LAST = TRY;
    private ScrollView scroll;
    private LinearLayout body, bottom;
    private TextView progress;
    private int step;
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
        top.setPadding(dp(this, 20), dp(this, 12), dp(this, 8), dp(this, 4));
        progress = monoText(this, "", 12, MUTED);
        top.addView(progress, weight1());
        Button skip = button(this, "Później", false);
        skip.setBackgroundResource(ripple(this, false));
        skip.setTextColor(MUTED);
        skip.setOnClickListener(v -> finishGuide());
        top.addView(skip);
        root.addView(top, full());
        scroll = new ScrollView(this);
        body = column(this);
        body.setPadding(dp(this, 24), dp(this, 16), dp(this, 24), dp(this, 24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        bottom = column(this);
        bottom.setPadding(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 16));
        root.addView(bottom, full());
        setContentView(root);
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

    private void go(int next) {
        step = Math.max(WELCOME, Math.min(next, LAST));
        Prefs.setOnboardStep(this, step);
        scroll.scrollTo(0, 0);
        render();
        if (step == ENVIRONMENT) ping();
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
                render();
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

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        int scrollY = scroll.getScrollY();
        body.removeAllViews();
        bottom.removeAllViews();
        progress.setText(step == WELCOME ? "" : "Krok " + step + " z " + LAST);
        switch (step) {
            case WELCOME:
                header("Poznaj androidagent", "Codex i Claude Code na Twoim telefonie.");
                note("Jedna aplikacja zawiera własne środowisko terminalowe, pliki i narzędzia telefonu. Nie musisz instalować osobnej aplikacji Termux.");
                note("Oficjalne klienty wymagają internetu i Twojego konta. Przygotowanie narzędzi jest automatyczne, a logowanie wykonujesz samodzielnie przez oficjalną stronę.");
                note("Sterowanie ekranem jest opcjonalne. Uprawnienia systemowe wybierasz w ustawieniach Androida.");
                primary("Zaczynamy", v -> go(BRAIN));
                break;
            case BRAIN:
                brain();
                break;
            case ENVIRONMENT:
                environment();
                break;
            case PERMISSIONS:
                header("Uprawnienia Androida", "Rozmowy i terminal działają bez dostępu do innych aplikacji.");
                note("Usługa ułatwień dostępu pozwala czytać i sterować ekranem. Jej włączenie wymaga Twojej zgody w ustawieniach systemu.");
                note("Powiadomienia pokazują postęp i przycisk Stop. Mikrofon jest potrzebny tylko do rozmów głosowych. Wyjątek optymalizacji baterii pomaga przy długich zadaniach.");
                note("Możesz zachować inne włączone usługi ułatwień dostępu. androidagent ich nie zastępuje.");
                primary("Przegląd uprawnień", v -> PermissionGuide.open(this));
                secondary("Dalej", v -> go(TRY));
                break;
            case TRY:
                boolean account = SetupState.agentReady(this, Prefs.backend(this));
                header(account ? "Gotowy do rozmowy" : "Zacznij od telefonu",
                        account ? "Klient jest przygotowany i zalogowany do konta." : "Lokalny stan telefonu jest dostępny także przed logowaniem.");
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

    private void brain() {
        header("Wybierz agenta", "Możesz zmienić domyślny wybór w ustawieniach i dodać drugiego klienta.");
        for (String[] option : new String[][]{
                {"codex", "Codex", "Oficjalny klient z Twoim kontem ChatGPT."},
                {"claude", "Claude Code", "Oficjalny klient z Twoim kontem Claude."},
                {"both", "Obaj", "Codex pozostaje domyślnym agentem nowych rozmów."}}) {
            boolean selected = option[0].equals(Prefs.agents(this));
            LinearLayout card = column(this);
            card.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
            card.setBackground(outlined(this, CARD, 16, selected ? ACCENT : OUTLINE, selected ? 2 : 1));
            card.addView(title(this, (selected ? "●  " : "○  ") + option[1], 16));
            card.addView(text(this, option[2], 13, MUTED));
            card.setContentDescription(option[1] + (selected ? ", wybrany" : ""));
            card.setOnClickListener(v -> {
                Prefs.setAgents(this, option[0]);
                Prefs.setBackend(this, "claude".equals(option[0]) ? "claude" : "codex");
                render();
            });
            LinearLayout.LayoutParams params = full();
            params.topMargin = dp(this, 12);
            body.addView(card, params);
        }
        primary("Dalej", v -> go(ENVIRONMENT));
    }

    private void environment() {
        EmbeddedRuntime runtime = EmbeddedRuntime.get(this);
        header("Wbudowane środowisko", "Terminal i oficjalni klienci działają wewnątrz androidagent.");
        String status = runtime.ready() ? "Środowisko gotowe" : runtime.status();
        if (runtime.busy()) status += " · " + runtime.progress() + "%";
        note(status == null || status.isEmpty() ? "Środowisko oczekuje na przygotowanie." : status);
        if (runtime.error() != null && !runtime.error().isEmpty()) {
            TextView error = text(this, runtime.error(), 14, ERR);
            error.setPadding(0, dp(this, 10), 0, 0);
            body.addView(error);
        }
        note("Otwórz panel środowiska, aby zobaczyć postęp, ponowić pobieranie lub otworzyć pliki i terminal. Przygotowanie środowiska nie zastępuje logowania do konta.");
        for (String backend : wanted(this)) {
            boolean installed = SetupState.agentInstalled(this, backend);
            boolean ready = SetupState.agentReady(this, backend);
            String name = "claude".equals(backend) ? "Claude Code" : "Codex";
            LinearLayout card = column(this);
            card.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
            card.setBackground(rounded(this, CARD, 16));
            card.addView(title(this, name, 16));
            card.addView(text(this, ready ? "Zainstalowany · zalogowany"
                    : installed ? "Zainstalowany · wymaga logowania" : "Jeszcze nieprzygotowany", 13, ready ? OK : MUTED));
            Button login = button(this, ready ? "Sprawdź konto" : installed ? "Zaloguj się" : "Przygotuj klienta", false);
            login.setOnClickListener(v -> {
                if (ready) ping();
                else if (installed) TermuxSetup.signIn(this, backend);
                else EmbeddedRuntime.open(this);
            });
            card.addView(login, full());
            LinearLayout.LayoutParams params = full();
            params.topMargin = dp(this, 14);
            body.addView(card, params);
        }
        primary(runtime.busy() ? "Pokaż postęp" : "Otwórz środowisko", v -> EmbeddedRuntime.open(this));
        secondary("Dalej", v -> go(PERMISSIONS));
    }

    private void header(String heading, String subtitle) {
        TextView title = title(this, heading, 28);
        title.setPadding(0, dp(this, 24), 0, dp(this, 8));
        body.addView(title);
        body.addView(text(this, subtitle, 16, MUTED));
    }

    private void note(String message) {
        TextView note = text(this, message, 14, MUTED);
        note.setLineSpacing(dp(this, 3), 1);
        LinearLayout.LayoutParams params = full();
        params.topMargin = dp(this, 16);
        body.addView(note, params);
    }

    private void primary(String label, View.OnClickListener listener) {
        Button button = button(this, label, true);
        button.setOnClickListener(listener);
        bottom.addView(button, full());
    }

    private void secondary(String label, View.OnClickListener listener) {
        Button button = button(this, label, false);
        button.setOnClickListener(listener);
        bottom.addView(button, full());
    }
}
