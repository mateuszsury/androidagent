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
        LinearLayout bar = topBar(this, "Uprawnienia", "zgody Androida");
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
        subtitle.setText(state.enabledCount() + " z " + PermissionGuide.State.TOTAL + " włączonych · zgody Androida");
        cards.removeAllViews();
        paragraph(cards,
                "Terminal i rozmowy działają bez dostępu do ekranu i mikrofonu. "
                        + "Logowanie do konta modelu jest osobnym krokiem. "
                        + "Uprawnienia włączasz samodzielnie w oknach Androida.", MUTED);

        section("Aplikacja");
        card(R.drawable.ms_notifications, "Powiadomienia", state.notifications ? "Włączone" : "Wyłączone · opcjonalne",
                state.notifications ? OK : FAINT,
                "Pokazują aktywne zadania i przycisk zatrzymania. Odmowa nie blokuje rozmów ani narzędzi. "
                        + "Android może nadal pokazywać aktywną usługę w swoim panelu.",
                state.notifications ? "Ustawienia powiadomień" : "Włącz powiadomienia",
                () -> {
                    if (NotificationPermission.canRequest(this)) NotificationPermission.requestIfNeeded(this);
                    else launch(NotificationPermission.settingsIntent(this), PermissionGuide.appSettingsIntent(this));
                }, null);
        card(R.drawable.ms_mic, "Mikrofon", state.microphone ? "Dozwolony" : "Wyłączony · opcjonalny",
                state.microphone ? OK : FAINT,
                "Potrzebny tylko do dyktowania. Możesz zawsze pisać na klawiaturze. "
                        + "Dyktowanie wymaga też dostępnej usługi rozpoznawania mowy w telefonie.",
                state.microphone ? "Ustawienia mikrofonu" : "Zezwól na mikrofon",
                () -> requestRuntime("microphone_requested", new String[]{Manifest.permission.RECORD_AUDIO},
                        MICROPHONE_REQUEST, state.microphone), null);
        String accessibilityStatus = state.accessibilityConnected ? "Połączone"
                : state.accessibilityEnabled ? "Włączone · oczekiwanie na połączenie" : "Wyłączone · opcjonalne";
        card(R.drawable.ms_touch_app, "Sterowanie ekranem", accessibilityStatus,
                state.accessibilityConnected ? OK : state.accessibilityEnabled ? WARN : FAINT,
                "Usługa ułatwień dostępu pozwala agentowi odczytywać ekran, klikać i przewijać. "
                        + "W ustawieniach wybierz androidagent i potwierdź zgodę. "
                        + "Pozostałe usługi ułatwień dostępu pozostają pod Twoją kontrolą.",
                "Ustawienia ułatwień dostępu",
                () -> launch(PermissionGuide.accessibilitySettingsIntent(), PermissionGuide.appSettingsIntent(this)),
                state.accessibilityEnabled ? null : restrictedNote(
                        "Jeżeli Android blokuje tę zgodę po instalacji APK, otwórz Informacje o aplikacji "
                                + "i w menu z trzema kropkami wybierz Zezwól na ustawienia z ograniczeniami, jeśli ta opcja jest dostępna. "
                                + "Następnie wróć do ustawień ułatwień dostępu."));
        card(R.drawable.ms_battery_charging_full, "Długie zadania i bateria",
                state.batteryExempt ? "Bez optymalizacji baterii" : "Optymalizacja aktywna", state.batteryExempt ? OK : FAINT,
                "Dla długich rozmów, kompilacji i maszyny Docker możesz pozwolić aplikacji działać w tle. "
                        + "Android lub producent urządzenia może mimo to zatrzymać proces przy braku pamięci. "
                        + "W razie problemów sprawdź też ograniczenia działania w tle w Informacjach o aplikacji.",
                state.batteryExempt ? "Ustawienia baterii" : "Zezwól na działanie w tle",
                () -> {
                    if (state.batteryExempt) launch(PermissionGuide.batteryListIntent(), PermissionGuide.appSettingsIntent(this));
                    else launch(PermissionGuide.batterySettingsIntent(this), PermissionGuide.batteryListIntent());
                }, null);

        section("Narzędzia agenta");
        String access = state.notificationAccessConnected ? "Włączony"
                : state.notificationAccess ? "Włączony · oczekiwanie na połączenie" : "Wyłączony · opcjonalny";
        card(R.drawable.ms_notifications_active, "Dostęp do powiadomień", access,
                state.notificationAccessConnected ? OK : state.notificationAccess ? WARN : FAINT,
                "Agent może odczytać tytuł i treść powiadomień, otworzyć je, nacisnąć ich przycisk lub je zamknąć, "
                        + "gdy poprosisz o to w rozmowie. Treść powiadomień nigdy nie jest dla agenta poleceniem.",
                state.notificationAccess ? "Ustawienia dostępu do powiadomień" : "Włącz dostęp do powiadomień",
                () -> launch(PermissionGuide.notificationAccessIntent(this), PermissionGuide.notificationAccessListIntent()),
                state.notificationAccess || Build.VERSION.SDK_INT < 33 ? null : restrictedNote(
                        "Android może oznaczyć to ustawienie jako ograniczone dla aplikacji z pliku APK. "
                                + "Wtedy w Informacjach o aplikacji wybierz w menu z trzema kropkami "
                                + "Zezwól na ustawienia z ograniczeniami."));
        boolean located = state.locationCoarse;
        card(R.drawable.ms_location_on, "Lokalizacja",
                state.locationFine ? "Dokładna" : located ? "Przybliżona" : "Wyłączona · opcjonalna", located ? OK : FAINT,
                "Agent sprawdzi położenie telefonu, gdy poprosisz np. o pogodę lub trasę w okolicy. "
                        + "Działa tylko przy otwartej aplikacji. Android zapyta, czy udostępnić dokładną, czy przybliżoną lokalizację.",
                located ? "Ustawienia lokalizacji" : "Zezwól na lokalizację",
                () -> requestRuntime("location_requested", new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST, located), null);
        card(R.drawable.ms_photo_library, "Zdjęcia", state.photos ? "Dozwolone" : "Wyłączone · opcjonalne",
                state.photos ? OK : FAINT,
                "Agent może przejrzeć najnowsze zdjęcia z galerii i skopiować wybrane do swojego katalogu roboczego. "
                        + "Robienie zdjęć aparatem nie wymaga tej zgody: zdjęcie zawsze robisz sam.",
                state.photos ? "Ustawienia zdjęć" : "Zezwól na dostęp do zdjęć",
                () -> requestRuntime("photos_requested", new String[]{MediaTools.photoPermission()}, PHOTOS_REQUEST,
                        state.photos), null);
        card(R.drawable.ms_install_mobile, "Instalowanie aplikacji", state.installApps ? "Dozwolone" : "Wyłączone · opcjonalne",
                state.installApps ? OK : FAINT,
                "Pozwala otworzyć instalator Androida dla aplikacji zbudowanych przez agenta. "
                        + "Każdą instalację i aktualizację potwierdzasz sam w oknie Androida.",
                state.installApps ? "Ustawienia instalowania" : "Zezwól na instalowanie",
                () -> launch(PermissionGuide.installAppsIntent(this), PermissionGuide.appSettingsIntent(this)), null);
        card(R.drawable.ms_settings, "Zmiana ustawień systemu", state.writeSettings ? "Dozwolona" : "Wyłączona · opcjonalna",
                state.writeSettings ? OK : FAINT,
                "Potrzebna tylko narzędziom, które zmieniają na przykład jasność ekranu. "
                        + "Nie daje dostępu do haseł ani do ustawień chronionych przez Androida.",
                state.writeSettings ? "Ustawienia systemowe aplikacji" : "Zezwól na zmianę ustawień",
                () -> launch(PermissionGuide.writeSettingsIntent(this), PermissionGuide.appSettingsIntent(this)), null);

        TextView footer = paragraph(cards,
                "Pliki i USB: zgoda pojawia się dopiero po wybraniu folderu albo konkretnego urządzenia. "
                        + "Nie trzeba udostępniać całej pamięci telefonu. "
                        + "Terminal i rozmowy nie wymagają roota ani zgody na instalowanie innych aplikacji.", FAINT);
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
            Toast.makeText(this, "Te ustawienia nie są dostępne. Otwórz informacje o androidagent w ustawieniach Androida.",
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
        Button details = actionButton("Informacje o aplikacji", false);
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
