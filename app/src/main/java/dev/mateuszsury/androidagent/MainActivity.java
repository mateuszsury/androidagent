package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;

/** Conversation-first home. The device features work before agent sign-in. */
public class MainActivity extends Screen implements ChatHub.Listener {
  private ChatHub hub;
  private LinearLayout connection;
  private TextView connectionText;
  private Ui.Dot connectionDot;
  private LinearLayout composer;
  private EditText prompt;
  private View historyBadge;
  private TextView commandDetail;
  private boolean compactHeight, twoPane;
  private String connectionKey = "";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable watch =
      new Runnable() {
        @Override
        public void run() {
          refresh();
          handler.postDelayed(this, 2000);
        }
      };

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    // Landscape phones and short split-screen windows need useful content above the composer.
    compactHeight = getResources().getConfiguration().screenHeightDp < 480;
    // Wide and short (a phone in landscape): the welcome box and the actions sit side by side.
    twoPane = compactHeight && getResources().getConfiguration().screenWidthDp >= 600;
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

    // ------------------------------------------------------------------ top bar
    LinearLayout bar = row(this);
    bar.setPadding(dp(this, 4), dp(this, compactHeight ? 2 : 6), dp(this, 4), dp(this, compactHeight ? 2 : 4));
    FrameLayout historyBox = new FrameLayout(this);
    ImageButton history = iconButton(this, R.drawable.ms_menu, "Historia rozmów");
    history.setOnClickListener(v -> history());
    historyBox.addView(history, new FrameLayout.LayoutParams(dp(this, 48), dp(this, 48)));
    historyBadge = new View(this);
    historyBadge.setBackground(oval(ACCENT));
    historyBadge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    FrameLayout.LayoutParams hb = new FrameLayout.LayoutParams(dp(this, 7), dp(this, 7), Gravity.TOP | Gravity.END);
    hb.topMargin = dp(this, 12);
    hb.rightMargin = dp(this, 10);
    historyBox.addView(historyBadge, hb);
    historyBadge.setVisibility(View.GONE);
    bar.addView(historyBox, new LinearLayout.LayoutParams(dp(this, 48), dp(this, 48)));

    LinearLayout brand = row(this);
    brand.setGravity(twoPane ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
    brand.setPadding(dp(this, twoPane ? 4 : 0), 0, 0, 0);
    Spark mark = new Spark(this).animated(false).color(ACCENT);
    brand.addView(mark, new LinearLayout.LayoutParams(dp(this, 15), dp(this, 15)));
    TextView name = single(monoText(this, "androidagent", 17, TEXT));
    name.setTypeface(mono(this, 600));
    name.setPadding(dp(this, 8), 0, 0, 0);
    name.setAccessibilityHeading(true);
    brand.addView(name);
    bar.addView(brand, weight1());

    connection = row(this);
    connection.setPadding(dp(this, 10), dp(this, compactHeight ? 5 : 7), dp(this, 8), dp(this, compactHeight ? 5 : 7));
    connectionDot = new Ui.Dot(this, FAINT);
    connection.addView(connectionDot, new LinearLayout.LayoutParams(dp(this, 12), dp(this, 12)));
    connectionText = single(monoText(this, "", 12, TEXT));
    connectionText.setPadding(dp(this, 7), 0, dp(this, 2), 0);
    connection.addView(connectionText);
    connection.addView(icon(this, R.drawable.ms_expand_more, MUTED, 16));
    connection.setContentDescription("Wybierz agenta");
    connection.setOnClickListener(v -> chooseAgent());
    connection.setForeground(getDrawable(ripple(this, false)));
    connection.setClipToOutline(true);
    Motion.press(connection);
    if (twoPane) {
      LinearLayout.LayoutParams pp = wrap();
      pp.rightMargin = dp(this, 4);
      bar.addView(connection, pp);
    }

    ImageButton settings = iconButton(this, R.drawable.ms_tune, "Ustawienia");
    settings.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
    bar.addView(settings, new LinearLayout.LayoutParams(dp(this, 48), dp(this, 48)));
    root.addView(bar, full());

    if (!twoPane) {
      LinearLayout status = row(this);
      status.setGravity(Gravity.CENTER);
      status.setPadding(0, 0, 0, dp(this, compactHeight ? 4 : 10));
      status.addView(connection);
      root.addView(status, full());
    }

    // ------------------------------------------------------------------ content
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setClipToPadding(false);
    LinearLayout center = twoPane ? row(this) : column(this);
    if (twoPane) center.setGravity(Gravity.TOP);
    center.setPadding(dp(this, 16), dp(this, compactHeight ? 4 : 8), dp(this, 16), dp(this, compactHeight ? 10 : 16));

    LinearLayout hero = welcome();
    LinearLayout cards = column(this);
    if (!compactHeight) {
      TextView label = sectionLabel(this, "Na skróty");
      label.setPadding(dp(this, 4), dp(this, 22), 0, dp(this, 10));
      cards.addView(label, full());
    }
    cards.addView(
        suggestion(
            R.drawable.ms_phone,
            "Sprawdź telefon",
            "Bateria, pamięć i połączenie",
            () -> startActivity(new Intent(this, DeviceActivity.class))),
        rowParams());
    View command =
        suggestion(R.drawable.ms_terminal, "Uruchom komendę", "", this::openCommand);
    commandDetail = (TextView) ((LinearLayout) ((LinearLayout) command).getChildAt(1)).getChildAt(1);
    cards.addView(command, rowParams());
    cards.addView(
        suggestion(
            R.drawable.ms_build_circle,
            "Docker",
            "Buduj aplikacje i sprzątaj środowiska",
            () -> startActivity(new Intent(this, DockerActivity.class))),
        rowParams());
    cards.addView(
        suggestion(
            R.drawable.ms_chat_bubble,
            "Zleć zadanie agentowi",
            "Czytaj ekran, działaj w aplikacjach",
            () -> openChat("Sprawdź, co jest na ekranie telefonu, i opisz dostępne działania.")),
        rowParams());
    cards.addView(
        suggestion(
            R.drawable.ic_remote,
            "Urządzenia USB",
            "UART, ESP, drugi Android i nośniki",
            () -> startActivity(new Intent(this, UsbActivity.class))),
        rowParams());

    if (twoPane) {
      center.addView(hero, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f);
      rp.leftMargin = dp(this, 16);
      rp.topMargin = dp(this, 6);
      center.addView(cards, rp);
    } else {
      center.addView(hero, full());
      LinearLayout.LayoutParams cp = full();
      cp.topMargin = dp(this, compactHeight ? 10 : 0);
      center.addView(cards, cp);
    }
    scroll.addView(center);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

    // ----------------------------------------------------------------- composer
    composer = row(this);
    composer.setPadding(dp(this, 14), dp(this, 5), dp(this, 6), dp(this, 5));
    composer.setBackground(outlined(this, CARD, 14, OUTLINE, 1));
    TextView glyph = monoText(this, "›", 20, ACCENT_TEXT);
    glyph.setTypeface(mono(this, 600));
    glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    glyph.setPadding(0, 0, 0, dp(this, 2));
    composer.addView(glyph);
    prompt = new EditText(this);
    prompt.setHint("Napisz do agenta");
    prompt.setHintTextColor(FAINT);
    prompt.setTextColor(TEXT);
    prompt.setTextSize(15);
    prompt.setTypeface(sans(this, 400));
    prompt.setBackground(null);
    prompt.setHighlightColor(Theme.withAlpha(ACCENT, 0x55));
    GradientDrawable cursor = new GradientDrawable();
    cursor.setColor(ACCENT);
    cursor.setSize(dp(this, 2), dp(this, 18));
    prompt.setTextCursorDrawable(cursor);
    prompt.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    prompt.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    prompt.setMaxLines(compactHeight ? 2 : 4);
    prompt.setPadding(dp(this, 10), dp(this, 9), dp(this, 6), dp(this, 9));
    prompt.setOnFocusChangeListener(
        (v, focused) ->
            composer.setBackground(
                outlined(this, CARD, 14, focused ? Theme.withAlpha(ACCENT, 0xB0) : OUTLINE, 1)));
    composer.addView(prompt, weight1());
    ImageButton plus = iconButton(this, R.drawable.ms_add, "Nowa rozmowa z załącznikiem", MUTED);
    plus.setOnClickListener(v -> openChat("", true));
    composer.addView(plus, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
    ImageButton send = iconButton(this, R.drawable.ms_arrow_upward, "Rozpocznij rozmowę", ON_ACCENT);
    send.setBackground(oval(ACCENT));
    send.setOnClickListener(v -> openChat(prompt.getText().toString()));
    Motion.press(send);
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(this, 38), dp(this, 38));
    sp.leftMargin = dp(this, 2);
    composer.addView(send, sp);
    LinearLayout.LayoutParams cp = full();
    cp.leftMargin = cp.rightMargin = dp(this, 16);
    cp.topMargin = dp(this, compactHeight ? 4 : 6);
    cp.bottomMargin = dp(this, compactHeight ? 6 : 14);
    root.addView(composer, cp);
    setContentView(root);
    if (state != null) prompt.setText(state.getString("draft", ""));

    if (state == null) {
      Motion.enter(hero, 30);
      Motion.stagger(cards, 110, 45);
      Motion.enter(composer, 260);
    }
  }

  /** The Claude Code welcome box: spark, the question, and a few terminal hints. */
  private LinearLayout welcome() {
    LinearLayout hero = terminalBox(this, "androidagent", Theme.withAlpha(ACCENT, 0xC0));
    hero.setPadding(dp(this, 16), dp(this, compactHeight ? 16 : 22), dp(this, 16), dp(this, compactHeight ? 12 : 16));
    LinearLayout head = row(this);
    Spark spark = new Spark(this).color(ACCENT);
    int size = compactHeight ? 18 : 22;
    head.addView(spark, new LinearLayout.LayoutParams(dp(this, size), dp(this, size)));
    TextView heading = title(this, "Co dziś zrobimy?", compactHeight ? 20 : 24);
    heading.setAccessibilityHeading(true);
    heading.setPadding(dp(this, 12), 0, 0, 0);
    head.addView(heading, weight1());
    hero.addView(head, full());
    int indent = size + 12;
    boolean full = !compactHeight || twoPane;
    if (full) {
      TextView sub = text(this, "Twój agent. Twój telefon.\nOd pomysłu do wykonania.", 14, MUTED);
      sub.setLineSpacing(dp(this, 2), 1f);
      sub.setPadding(dp(this, indent), dp(this, 6), 0, 0);
      hero.addView(sub, full());
      LinearLayout hints = column(this);
      hints.setPadding(dp(this, indent), dp(this, 14), 0, 0);
      TextView status = hint("/status", " — stan telefonu bez logowania", ACCENT_TEXT, MUTED);
      status.setBackgroundResource(ripple(this, false));
      status.setOnClickListener(v -> openChat("/status"));
      hints.addView(status, full());
      TextView cwd = hint("cwd:", " ~/.buddy/work", FAINT, MUTED);
      hints.addView(cwd, full());
      hero.addView(hints, full());
    }
    return hero;
  }

  private TextView hint(String key, String rest, int keyColor, int restColor) {
    SpannableStringBuilder sb = new SpannableStringBuilder(key + rest);
    sb.setSpan(new ForegroundColorSpan(keyColor), 0, key.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    sb.setSpan(new ForegroundColorSpan(restColor), key.length(), sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    TextView t = monoText(this, "", 12, restColor);
    t.setText(sb);
    t.setPadding(0, dp(this, 3), 0, dp(this, 3));
    return t;
  }

  private LinearLayout.LayoutParams rowParams() {
    LinearLayout.LayoutParams p = full();
    p.bottomMargin = dp(this, 8);
    return p;
  }

  /** A suggestion card: icon tile, title, muted line, chevron. Title's parent's parent is the card. */
  private View suggestion(int resource, String label, String detail, Runnable action) {
    LinearLayout r = row(this);
    r.setPadding(dp(this, 12), dp(this, compactHeight ? 9 : 11), dp(this, 10), dp(this, compactHeight ? 9 : 11));
    r.setBackground(outlined(this, CARD, 12, LINE, 1));
    r.setForeground(getDrawable(ripple(this, false)));
    r.setClipToOutline(true);
    FrameLayout tile = new FrameLayout(this);
    tile.setBackground(outlined(this, CARD2, 10, LINE, 1));
    tile.addView(icon(this, resource, TEXT, 19), new FrameLayout.LayoutParams(dp(this, 19), dp(this, 19), Gravity.CENTER));
    r.addView(tile, new LinearLayout.LayoutParams(dp(this, 36), dp(this, 36)));
    LinearLayout t = column(this);
    t.setPadding(dp(this, 14), 0, dp(this, 8), 0);
    t.addView(single(title(this, label, 15)));
    TextView sub = single(text(this, detail, 13, MUTED));
    sub.setPadding(0, dp(this, 1), 0, 0);
    t.addView(sub);
    r.addView(t, weight1());
    r.addView(icon(this, R.drawable.ms_chevron_right, FAINT, 18));
    r.setOnClickListener(v -> action.run());
    Motion.press(r);
    return r;
  }

  /** The embedded bash once the environment is prepared; the quick device shell before that. */
  private void openCommand() {
    if (EmbeddedRuntime.get(this).ready()) TerminalActivity.openShell(this);
    else startActivity(new Intent(this, DeviceActivity.class).putExtra("terminal", true));
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

  // ------------------------------------------------------------------ history

  private void history() {
    LinearLayout content = column(this);
    final Dialog[] sheet = {null};
    int count = 0;
    for (Sessions.Session s : hub.sessions.all) if (!s.messages.isEmpty()) count++;
    LinearLayout head = row(this);
    LinearLayout titles = column(this);
    TextView heading = title(this, "Twoje rozmowy", 20);
    heading.setAccessibilityHeading(true);
    titles.addView(heading);
    titles.addView(monoText(this, count == 0 ? "brak zapisanych" : count + " " + plural(count), 11, FAINT));
    head.addView(titles, weight1());
    TextView sessions = barAction(this, "Sesje →");
    sessions.setContentDescription("Aktywne sesje");
    sessions.setOnClickListener(
        v -> {
          sheet[0].dismiss();
          startActivity(new Intent(this, MonitorActivity.class));
        });
    head.addView(sessions);
    content.addView(head, full());

    Button fresh = button(this, "+  Nowa rozmowa", true);
    fresh.setOnClickListener(
        v -> {
          sheet[0].dismiss();
          openChat("");
        });
    LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 44));
    fp.topMargin = dp(this, 14);
    fp.bottomMargin = dp(this, 6);
    content.addView(fresh, fp);

    LinearLayout items = column(this);
    for (Sessions.Session s : hub.sessions.all) {
      if (s.messages.isEmpty()) continue;
      View item = historyRow(s);
      item.setOnClickListener(
          v -> {
            sheet[0].dismiss();
            hub.select(s);
            startActivity(new Intent(this, ChatActivity.class).putExtra("id", s.id));
          });
      item.setOnLongClickListener(
          v -> {
            confirmSheet(
                this,
                "Usunąć rozmowę?",
                chatTitle(s),
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
      items.addView(emptyState(this, "Brak rozmów", "Tu pojawią się Twoje rozmowy."), full());
    } else {
      TextView tip = monoText(this, "przytrzymaj, aby usunąć", 11, FAINT);
      tip.setGravity(Gravity.CENTER);
      tip.setPadding(0, dp(this, 10), 0, 0);
      items.addView(tip, full());
    }
    content.addView(items, full());
    Motion.stagger(items, 60, 30);
    sheet[0] = bottomSheet(this, content);
    sheet[0].show();
  }

  private View historyRow(Sessions.Session s) {
    LinearLayout r = row(this);
    r.setPadding(dp(this, 6), dp(this, 10), dp(this, 6), dp(this, 10));
    r.setBackgroundResource(ripple(this, false));
    TextView tile = agentTile(this, s.backend);
    tile.setTextSize(11);
    r.addView(tile, new LinearLayout.LayoutParams(dp(this, 34), dp(this, 34)));
    LinearLayout col = column(this);
    col.setPadding(dp(this, 12), 0, dp(this, 8), 0);
    TextView t = single(title(this, chatTitle(s), 15));
    if (!s.unread && !s.running) t.setTypeface(sans(this, 500));
    col.addView(t);
    String meta = s.brainName() + (s.model.isEmpty() ? "" : " · " + s.model);
    if (s.running) meta += " · pracuje";
    TextView m = single(monoText(this, meta, 11, s.running ? ACCENT_TEXT : FAINT));
    m.setPadding(0, dp(this, 2), 0, 0);
    col.addView(m);
    r.addView(col, weight1());
    LinearLayout end = column(this);
    end.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    end.addView(monoText(this, ago(s.updated), 11, FAINT));
    if (s.running) {
      Spark spark = new Spark(this).color(ACCENT);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(this, 14), dp(this, 14));
      p.topMargin = dp(this, 5);
      p.gravity = Gravity.END;
      end.addView(spark, p);
    } else if (s.unread) {
      View dot = new View(this);
      dot.setBackground(oval(ACCENT));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(this, 8), dp(this, 8));
      p.topMargin = dp(this, 7);
      p.gravity = Gravity.END;
      p.rightMargin = dp(this, 2);
      end.addView(dot, p);
    }
    r.addView(end, wrap());
    r.setContentDescription(
        chatTitle(s) + ", " + s.brainName() + ", " + ago(s.updated)
            + (s.running ? ", pracuje" : s.unread ? ", nieprzeczytana" : ""));
    return r;
  }

  private static String chatTitle(Sessions.Session s) {
    return s.title.isEmpty() ? "Nowa rozmowa · " + s.brainName() : s.title;
  }

  private static String plural(int n) {
    int tens = n % 100, ones = n % 10;
    if (n == 1) return "rozmowa";
    if (ones >= 2 && ones <= 4 && (tens < 12 || tens > 14)) return "rozmowy";
    return "rozmów";
  }

  // ------------------------------------------------------------------ agent state

  private void refresh() {
    String backend = Prefs.backend(this);
    String agent = "claude".equals(backend) ? "Claude Code" : "Codex";
    EmbeddedRuntime runtime = EmbeddedRuntime.get(this);
    String state;
    int color;
    boolean busy = false;
    if (new SetupState(this).canRun() || SetupState.agentReady(this, backend)) {
      state = "gotowy";
      color = OK;
    } else if (runtime.busy()) {
      state = "przygotowanie " + runtime.progress() + "%";
      color = FAINT;
      busy = true;
    } else if (SetupState.agentInstalled(this, backend)) {
      boolean known = Prefs.helperStatus(this) != null;
      state = known ? "zaloguj się" : "sprawdzanie";
      color = known ? WARN : FAINT;
    } else {
      state = "konfiguracja";
      color = WARN;
    }
    boolean attention = hub.sessions.anyUnread() || hub.runningCount() > 0;
    String key = agent + state + color + attention;
    if (key.equals(connectionKey)) return;
    connectionKey = key;
    connectionText.setText(agent + " · " + state);
    connectionDot.setColor(color);
    connectionDot.setPulsing(busy);
    connection.setBackground(
        outlined(this, Theme.withAlpha(color, 0x14), 20, Theme.withAlpha(color, 0x48), 1));
    prompt.setHint("Napisz do " + agent);
    historyBadge.setVisibility(attention ? View.VISIBLE : View.GONE);
    commandDetail.setText(runtime.ready() ? "Wbudowany terminal bash" : "Szybka komenda powłoki telefonu");
  }

  private void chooseAgent() {
    LinearLayout content = column(this);
    TextView heading = title(this, "Wybierz agenta", 20);
    heading.setAccessibilityHeading(true);
    content.addView(heading);
    TextView detail =
        text(
            this,
            "Nowe rozmowy użyją wybranego agenta. Historia każdej rozmowy pozostaje przy swoim"
                + " agencie.",
            13,
            MUTED);
    detail.setPadding(0, dp(this, 6), 0, dp(this, 14));
    content.addView(detail, full());
    final Dialog[] sheet = {null};
    String current = Prefs.backend(this);
    for (String[] option :
        new String[][] {{"claude", "Claude Code", "konto Claude"}, {"codex", "Codex", "konto ChatGPT"}}) {
      boolean on = option[0].equals(current);
      LinearLayout choice = row(this);
      choice.setPadding(dp(this, 12), dp(this, 11), dp(this, 12), dp(this, 11));
      choice.setBackground(
          on
              ? outlined(this, Theme.withAlpha(ACCENT, 0x14), 12, Theme.withAlpha(ACCENT, 0xB0), 1)
              : outlined(this, 0x00000000, 12, OUTLINE, 1));
      choice.setForeground(getDrawable(ripple(this, false)));
      choice.setClipToOutline(true);
      TextView tile = agentTile(this, option[0]);
      tile.setTextSize(11);
      choice.addView(tile, new LinearLayout.LayoutParams(dp(this, 34), dp(this, 34)));
      LinearLayout col = column(this);
      col.setPadding(dp(this, 12), 0, dp(this, 8), 0);
      col.addView(title(this, option[1], 15));
      boolean ready = SetupState.agentReady(this, option[0]);
      boolean installed = SetupState.agentInstalled(this, option[0]);
      TextView st =
          single(
              monoText(
                  this,
                  option[2] + " · " + (ready ? "zalogowany" : installed ? "wymaga logowania" : "nieprzygotowany"),
                  11,
                  ready ? OK : installed ? WARN : FAINT));
      st.setPadding(0, dp(this, 2), 0, 0);
      col.addView(st);
      choice.addView(col, weight1());
      if (on) {
        ImageView check = icon(this, R.drawable.ms_check, ACCENT_TEXT, 20);
        choice.addView(check);
        Motion.pop(check, 120);
      }
      choice.setOnClickListener(
          v -> {
            Prefs.setBackend(this, option[0]);
            if (!"both".equals(Prefs.agents(this))) Prefs.setAgents(this, option[0]);
            sheet[0].dismiss();
            refresh();
          });
      Motion.press(choice);
      LinearLayout.LayoutParams p = full();
      p.bottomMargin = dp(this, 8);
      content.addView(choice, p);
    }
    Button setup = button(this, "Konfiguruj agentów", false);
    setup.setOnClickListener(
        v -> {
          sheet[0].dismiss();
          EmbeddedRuntime.open(this);
        });
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 44));
    sp.topMargin = dp(this, 6);
    content.addView(setup, sp);
    sheet[0] = bottomSheet(this, content);
    sheet[0].show();
  }

  @Override
  protected void onResume() {
    super.onResume();
    EmbeddedRuntime runtime = EmbeddedRuntime.get(this);
    // Start only after the home screen is visible. This expands bundled tools offline;
    // client downloads and account login remain separate actions in the environment panel.
    if (!runtime.ready() && !runtime.busy() && runtime.error().isEmpty()) {
      try {
        runtime.bootstrap();
      } catch (Exception error) {
        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
      }
    }
    hub.addListener(this);
    refresh();
    handler.removeCallbacks(watch);
    handler.postDelayed(watch, 2000);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(watch);
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
        : s < 3600
            ? s / 60 + " min"
            : s < 86400 ? s / 3600 + " godz." : s < 172800 ? "1 dzień" : s / 86400 + " dni";
  }
}
