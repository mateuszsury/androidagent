package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;

/** Conversation-first home. The device features work before agent sign-in. */
public class MainActivity extends Screen implements ChatHub.Listener {
  private ChatHub hub;
  private TextView connection;
  private EditText prompt;
  private boolean compactHeight;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    // Landscape phones and short split-screen windows need useful content above the composer.
    compactHeight = getResources().getConfiguration().screenHeightDp < 480;
    getWindow()
        .setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    hub = ChatHub.get(this);
    LinearLayout root = column(this);
    root.setFocusableInTouchMode(true);
    root.requestFocus();
    root.setBackgroundColor(BG);
    root.setFitsSystemWindows(true);
    LinearLayout bar = row(this);
    bar.setPadding(dp(this, 12), dp(this, compactHeight ? 4 : 8),
        dp(this, 12), dp(this, compactHeight ? 4 : 8));
    ImageButton history = iconButton(this, R.drawable.ms_menu, "Historia rozmów");
    history.setOnClickListener(v -> history());
    bar.addView(history, new LinearLayout.LayoutParams(dp(this, 48), dp(this, 48)));
    TextView brand = title(this, "androidagent", 21);
    brand.setGravity(Gravity.CENTER);
    bar.addView(brand, weight1());
    ImageButton settings = iconButton(this, R.drawable.ms_tune, "Ustawienia");
    settings.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
    bar.addView(settings, new LinearLayout.LayoutParams(dp(this, 48), dp(this, 48)));
    root.addView(bar, full());

    LinearLayout status = row(this);
    status.setGravity(Gravity.CENTER);
    connection = text(this, "", 12, MUTED);
    connection.setPadding(dp(this, 14), dp(this, compactHeight ? 6 : 9),
        dp(this, 14), dp(this, compactHeight ? 6 : 9));
    connection.setBackground(rounded(this, CARD, 20));
    connection.setContentDescription("Wybierz agenta");
    connection.setOnClickListener(v -> chooseAgent());
    status.addView(connection);
    root.addView(status, full());

    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout center = column(this);
    center.setGravity(compactHeight ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER);
    center.setPadding(dp(this, 26), dp(this, compactHeight ? 8 : 30),
        dp(this, 26), dp(this, compactHeight ? 16 : 24));
    TextView mark = title(this, "›_", 33);
    mark.setTypeface(mono(this, 600));
    mark.setGravity(Gravity.CENTER);
    mark.setBackground(rounded(this, CARD, 25));
    if (!compactHeight) center.addView(mark, new LinearLayout.LayoutParams(dp(this, 76), dp(this, 76)));
    TextView heading = title(this, "Co dziś zrobimy?", compactHeight ? 22 : 28);
    heading.setGravity(Gravity.CENTER);
    heading.setPadding(0, dp(this, compactHeight ? 4 : 24), 0, dp(this, compactHeight ? 4 : 8));
    center.addView(heading, full());
    TextView sub = text(this, "Twój agent. Twój telefon.\nOd pomysłu do wykonania.", 15, MUTED);
    sub.setGravity(Gravity.CENTER);
    sub.setLineSpacing(dp(this, 4), 1);
    if (!compactHeight) center.addView(sub, full());
    LinearLayout cards = column(this);
    LinearLayout.LayoutParams cardsP = full();
    cardsP.topMargin = dp(this, compactHeight ? 8 : 30);
    cards.addView(
        suggestion(
            R.drawable.ms_phone,
            "Sprawdź telefon",
            "Bateria, pamięć i połączenie",
            () -> startActivity(new Intent(this, DeviceActivity.class))),
        full());
    cards.addView(
        suggestion(
            R.drawable.ms_terminal,
            "Uruchom komendę",
            "Lokalny terminal i pliki",
            () -> startActivity(new Intent(this, DeviceActivity.class).putExtra("terminal", true))),
        full());
    cards.addView(
        suggestion(
            R.drawable.ms_build_circle,
            "Docker",
            "Buduj aplikacje i sprzątaj środowiska",
            () -> startActivity(new Intent(this, DockerActivity.class))),
        full());
    cards.addView(
        suggestion(
            R.drawable.ms_chat_bubble,
            "Zleć zadanie agentowi",
            "Czytaj ekran, działaj w aplikacjach",
            () -> openChat("Sprawdź, co jest na ekranie telefonu, i opisz dostępne działania.")),
        full());
    cards.addView(suggestion(R.drawable.ms_terminal, "Urządzenia USB", "UART, ESP, drugi Android i nośniki", () -> startActivity(new Intent(this, UsbActivity.class))), full());
    center.addView(cards, cardsP);
    scroll.addView(center);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

    LinearLayout composer = row(this);
    composer.setPadding(dp(this, 8), dp(this, 7), dp(this, 8), dp(this, 7));
    composer.setBackground(rounded(this, CARD, 28));
    ImageButton plus = iconButton(this, R.drawable.ms_add, "Nowa rozmowa z załącznikiem");
    plus.setOnClickListener(v -> openChat("", true));
    composer.addView(plus, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 44)));
    prompt = new EditText(this);
    prompt.setHint("Napisz do agenta");
    prompt.setHintTextColor(MUTED);
    prompt.setTextColor(TEXT);
    prompt.setTextSize(16);
    prompt.setTypeface(sans(this, 400));
    prompt.setBackground(null);
    prompt.setMaxLines(3);
    prompt.setPadding(dp(this, 6), dp(this, 8), dp(this, 6), dp(this, 8));
    composer.addView(prompt, weight1());
    ImageButton send =
        iconButton(this, R.drawable.ms_arrow_upward, "Rozpocznij rozmowę", ON_ACCENT);
    send.setBackground(oval(ACCENT));
    send.setOnClickListener(v -> openChat(prompt.getText().toString()));
    composer.addView(send, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
    LinearLayout.LayoutParams cp = full();
    cp.leftMargin = cp.rightMargin = dp(this, 16);
    root.addView(composer, cp);
    TextView footer = text(this, "Twój telefon · Twoja kontrola", 10, MUTED);
    footer.setGravity(Gravity.CENTER);
    footer.setPadding(0, dp(this, 12), 0, dp(this, 14));
    if (!compactHeight) root.addView(footer, full());
    setContentView(root);
    if (state != null) prompt.setText(state.getString("draft", ""));
  }

  private View suggestion(int resource, String label, String detail, Runnable action) {
    LinearLayout r = row(this);
    r.setPadding(dp(this, 16), dp(this, compactHeight ? 10 : 14),
        dp(this, 12), dp(this, compactHeight ? 10 : 14));
    r.setBackgroundResource(ripple(this, false));
    r.addView(icon(this, resource, TEXT, 23));
    LinearLayout t = column(this);
    t.setPadding(dp(this, 16), 0, 0, 0);
    t.addView(title(this, label, 14));
    t.addView(text(this, detail, 12, MUTED));
    r.addView(t, weight1());
    r.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 19));
    r.setOnClickListener(v -> action.run());
    return r;
  }

  private void openChat(String draft) {
    openChat(draft, false);
  }

  private void openChat(String draft, boolean attachments) {
    Sessions.Session s = hub.create(Prefs.backend(this));
    hub.select(s);
    startActivity(
        new Intent(this, ChatActivity.class)
            .putExtra("id", s.id)
            .putExtra("draft", draft)
            .putExtra("show_attachments", attachments));
    prompt.setText("");
  }

  private void history() {
    LinearLayout content = column(this);
    content.addView(title(this, "Twoje rozmowy", 23));
    final Dialog[] sheet = {null};
    Button fresh = button(this, "+  Nowa rozmowa", true);
    fresh.setOnClickListener(
        v -> {
          sheet[0].dismiss();
          openChat("");
        });
    content.addView(fresh, full());
    ScrollView scroll = new ScrollView(this);
    LinearLayout items = column(this);
    for (Sessions.Session s : hub.sessions.all) {
      if (s.messages.isEmpty()) continue;
      TextView item = title(this, s.label() + "\n" + s.brainName(), 15);
      item.setPadding(dp(this, 8), dp(this, 18), dp(this, 8), dp(this, 18));
      item.setOnClickListener(
          v -> {
            sheet[0].dismiss();
            startActivity(new Intent(this, ChatActivity.class).putExtra("id", s.id));
          });
      item.setOnLongClickListener(
          v -> {
            confirmSheet(
                this,
                "Usunąć rozmowę?",
                s.label(),
                "Usuń",
                true,
                () -> {
                  hub.delete(s);
                  sheet[0].dismiss();
                });
            return true;
          });
      items.addView(item, full());
    }
    if (items.getChildCount() == 0) {
      TextView empty = text(this, "Tu pojawią się Twoje rozmowy.", 14, MUTED);
      empty.setPadding(0, dp(this, 24), 0, dp(this, 24));
      items.addView(empty);
    }
    scroll.addView(items);
    content.addView(scroll, new LinearLayout.LayoutParams(-1, dp(this, 260)));
    Button sessions = button(this, "Aktywne sesje", false);
    sessions.setOnClickListener(
        v -> {
          sheet[0].dismiss();
          startActivity(new Intent(this, MonitorActivity.class));
        });
    content.addView(sessions, full());
    sheet[0] = bottomSheet(this, content);
    sheet[0].show();
  }

  private void refresh() {
    boolean ready = new SetupState(this).canRun();
    String agent = "claude".equals(Prefs.backend(this)) ? "Claude Code" : "Codex";
    connection.setText(
        (ready ? "●  " : "◌  ") + agent + (ready ? " · gotowy" : " · konfiguracja") + "  ▾");
    prompt.setHint("Napisz do " + agent);
  }

  private void chooseAgent() {
    LinearLayout content = column(this);
    content.addView(title(this, "Wybierz agenta", 23));
    TextView detail =
        text(
            this,
            "Nowe rozmowy użyją wybranego agenta. Historia każdej rozmowy pozostaje przy swoim"
                + " agencie.",
            13,
            MUTED);
    detail.setPadding(0, dp(this, 8), 0, dp(this, 12));
    content.addView(detail, full());
    final Dialog[] sheet = {null};
    for (String[] option : new String[][] {{"codex", "Codex"}, {"claude", "Claude Code"}}) {
      Button choice = button(this, option[1], option[0].equals(Prefs.backend(this)));
      choice.setOnClickListener(
          v -> {
            Prefs.setBackend(this, option[0]);
            if (!"both".equals(Prefs.agents(this))) Prefs.setAgents(this, option[0]);
            sheet[0].dismiss();
            refresh();
          });
      content.addView(choice, full());
    }
    Button setup = button(this, "Konfiguruj agentów", false);
    setup.setOnClickListener(
        v -> {
          sheet[0].dismiss();
          startActivity(new Intent(this, OnboardingActivity.class));
        });
    content.addView(setup, full());
    sheet[0] = bottomSheet(this, content);
    sheet[0].show();
  }

  @Override
  protected void onResume() {
    super.onResume();
    hub.addListener(this);
    refresh();
  }

  @Override
  protected void onPause() {
    hub.removeListener(this);
    super.onPause();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putString("draft", prompt.getText().toString());
    super.onSaveInstanceState(out);
  }

  @Override
  public void onChatsChanged() {
    refresh();
  }

  @Override
  public void onMessage(Sessions.Session s, Sessions.Msg m) {}

  static String ago(long t) {
    long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
    return s < 60
        ? "teraz"
        : s < 3600 ? s / 60 + " min" : s < 86400 ? s / 3600 + " godz." : s / 86400 + " dni";
  }
}
