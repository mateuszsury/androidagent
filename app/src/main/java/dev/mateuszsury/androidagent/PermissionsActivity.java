package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Optional Android consents; opening this screen never requests or grants a permission. */
public class PermissionsActivity extends Screen {
    private static final int MICROPHONE_REQUEST = 903;
    private static final String PREFERENCES = "permission_guide";
    private LinearLayout cards;
    private ScrollView scroll;

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        LinearLayout bar = row(this);
        bar.setPadding(dp(this, 8), dp(this, 8), dp(this, 16), dp(this, 8));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, "Wróć");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = title(this, "Uprawnienia", 20);
        title.setAccessibilityHeading(true);
        bar.addView(title, weight1());
        root.addView(bar, full());
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        cards = column(this);
        cards.setPadding(dp(this, 20), dp(this, 12), dp(this, 20), dp(this, 24));
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
        if (request == MICROPHONE_REQUEST) refresh();
    }

    private void refresh() {
        if (cards == null || isDestroyed()) return;
        int position = scroll.getScrollY();
        PermissionGuide.State state = PermissionGuide.read(this);
        cards.removeAllViews();
        paragraph(cards,
                "Terminal i rozmowy działają bez dostępu do ekranu i mikrofonu. "
                        + "Logowanie do konta modelu jest osobnym krokiem. "
                        + "Uprawnienia włączasz samodzielnie w oknach Androida.", MUTED);
        card("Powiadomienia", state.notifications ? "Włączone" : "Wyłączone · opcjonalne",
                "Pokazują aktywne zadania i przycisk zatrzymania. Odmowa nie blokuje rozmów ani narzędzi. "
                        + "Android może nadal pokazywać aktywną usługę w swoim panelu.",
                state.notifications ? "Ustawienia powiadomień" : "Włącz powiadomienia",
                () -> {
                    if (NotificationPermission.canRequest(this)) NotificationPermission.requestIfNeeded(this);
                    else launch(NotificationPermission.settingsIntent(this), PermissionGuide.appSettingsIntent(this));
                }, state.notifications);
        card("Mikrofon", state.microphone ? "Dozwolony" : "Wyłączony · opcjonalny",
                "Potrzebny tylko do dyktowania. Możesz zawsze pisać na klawiaturze. "
                        + "Dyktowanie wymaga też dostępnej usługi rozpoznawania mowy w telefonie.",
                state.microphone ? "Ustawienia mikrofonu" : "Zezwól na mikrofon",
                () -> requestMicrophone(state.microphone), state.microphone);
        String accessibilityStatus = state.accessibilityConnected ? "Połączone"
                : state.accessibilityEnabled ? "Włączone · oczekiwanie na połączenie" : "Wyłączone · opcjonalne";
        card("Sterowanie ekranem", accessibilityStatus,
                "Usługa ułatwień dostępu pozwala agentowi odczytywać ekran, klikać i przewijać. "
                        + "W ustawieniach wybierz androidagent i potwierdź zgodę. "
                        + "Pozostałe usługi ułatwień dostępu pozostają pod Twoją kontrolą.",
                "Ustawienia ułatwień dostępu",
                () -> launch(PermissionGuide.accessibilitySettingsIntent(), PermissionGuide.appSettingsIntent(this)),
                state.accessibilityConnected);
        if (!state.accessibilityEnabled) {
            paragraph(cards,
                    "Jeżeli Android blokuje tę zgodę po instalacji APK, otwórz Informacje o aplikacji "
                            + "i w menu ⋮ wybierz Zezwól na ustawienia z ograniczeniami, jeśli ta opcja jest dostępna. "
                            + "Następnie wróć do ustawień ułatwień dostępu.", MUTED);
            Button details = actionButton("Informacje o aplikacji");
            details.setOnClickListener(v -> launch(PermissionGuide.appSettingsIntent(this), null));
            cards.addView(details, full());
        }
        card("Długie zadania i bateria", state.batteryExempt ? "Bez optymalizacji baterii" : "Optymalizacja aktywna",
                "Dla długich rozmów, kompilacji i maszyny Docker możesz pozwolić aplikacji działać w tle. "
                        + "Android lub producent urządzenia może mimo to zatrzymać proces przy braku pamięci. "
                        + "W razie problemów sprawdź też ograniczenia działania w tle w Informacjach o aplikacji.",
                state.batteryExempt ? "Ustawienia baterii" : "Zezwól na działanie w tle",
                () -> {
                    if (state.batteryExempt) launch(PermissionGuide.batteryListIntent(), PermissionGuide.appSettingsIntent(this));
                    else launch(PermissionGuide.batterySettingsIntent(this), PermissionGuide.batteryListIntent());
                }, state.batteryExempt);
        paragraph(cards,
                "Pliki i USB: zgoda pojawia się dopiero po wybraniu folderu albo konkretnego urządzenia. "
                        + "Nie trzeba udostępniać całej pamięci telefonu. "
                        + "Terminal i rozmowy nie wymagają roota ani zgody na instalowanie innych aplikacji.", MUTED);
        scroll.post(() -> scroll.scrollTo(0, position));
    }

    private void requestMicrophone(boolean granted) {
        boolean requested = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean("microphone_requested", false);
        if (!granted && (!requested || shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))) {
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean("microphone_requested", true).apply();
            try { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_REQUEST); }
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
            Toast.makeText(this, "Te ustawienia nie są dostępne. Otwórz informacje o androidagent w ustawieniach Androida.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private Button actionButton(String label) {
        Button action = button(this, label, false);
        action.setSingleLine(false);
        action.setEllipsize(null);
        return action;
    }

    private void card(String name, String status, String explanation, String label, Runnable action, boolean active) {
        LinearLayout card = column(this);
        card.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 16));
        card.setBackground(rounded(this, CARD, 16));
        TextView heading = title(this, name, 17);
        heading.setAccessibilityHeading(true);
        card.addView(heading, full());
        TextView current = text(this, status, 13, active ? OK : MUTED);
        current.setPadding(0, dp(this, 6), 0, dp(this, 8));
        current.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(current, full());
        paragraph(card, explanation, MUTED);
        Button button = actionButton(label);
        button.setOnClickListener(v -> action.run());
        card.addView(button, full());
        LinearLayout.LayoutParams params = full();
        params.topMargin = dp(this, 14);
        cards.addView(card, params);
    }

    private void paragraph(LinearLayout parent, String value, int color) {
        TextView description = text(this, value, 13, color);
        description.setLineSpacing(dp(this, 3), 1);
        description.setPadding(0, 0, 0, dp(this, 12));
        parent.addView(description, full());
    }
}
