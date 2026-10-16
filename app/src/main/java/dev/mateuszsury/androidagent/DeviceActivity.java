package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;

/** Live status and an honest, app-scoped terminal; no model needed. */
public class DeviceActivity extends Screen {
  private static final String[] QUICK = {"uname -a", "id", "df -h .", "ls -la", "getprop ro.product.model", "ps -A | head"};
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          refresh();
          handler.postDelayed(this, 5000);
        }
      };
  private TextView output;
  private EditText command;
  private Button run;
  private LocalShell shell;
  private boolean busy;
  // Metrics: built once, updated in place.
  private TextView model, system, batteryValue, batteryState, ramValue, ramSub, diskValue, diskSub, error;
  private ToolUi.Meter batteryMeter, ramMeter, diskMeter;
  private ToolUi.Status network, control, runtime, uptime;
  private String signature = "";

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout root = column(this);
    root.setBackgroundColor(BG);
    root.setFitsSystemWindows(true);
    TextView refresh = barAction(this, "Odśwież");
    refresh.setOnClickListener(
        v -> {
          signature = "";
          refresh();
          Motion.pop(v, 0);
        });
    root.addView(topBar(this, "Twój telefon", "~/telefon · odczyt lokalny", refresh), full());
    ScrollView scroll = new ScrollView(this);
    scroll.setClipToPadding(false);
    LinearLayout content = ToolUi.body(this);

    // ---------------------------------------------------------------- hero
    LinearLayout hero = terminalBox(this, "urządzenie", ACCENT);
    model = title(this, Build.MANUFACTURER + " " + Build.MODEL, 20);
    model.setTypeface(sans(this, 650));
    hero.addView(model, full());
    system = monoText(this, "Android " + Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT, 12, MUTED);
    system.setPadding(0, dp(this, 4), 0, 0);
    hero.addView(system, full());
    LinearLayout.LayoutParams heroP = cardParams(this);
    heroP.topMargin = dp(this, 12);
    content.addView(hero, heroP);

    // ---------------------------------------------------------------- battery
    LinearLayout battery = card(this);
    LinearLayout head = row(this);
    head.addView(ToolUi.caption(this, "Bateria"), weight1());
    batteryState = badge(this, "", MUTED);
    head.addView(batteryState, wrap());
    battery.addView(head, full());
    batteryValue = monoText(this, "—", 30, TEXT);
    batteryValue.setTypeface(mono(this, 600));
    batteryValue.setPadding(0, dp(this, 6), 0, 0);
    battery.addView(batteryValue, wrap());
    batteryMeter = new ToolUi.Meter(this, OK);
    battery.addView(batteryMeter, ToolUi.meterParams(this, 8, 10));
    content.addView(battery, cardParams(this));

    // ---------------------------------------------------------------- RAM + storage
    LinearLayout pair = row(this);
    pair.setGravity(android.view.Gravity.TOP);
    LinearLayout ram = card(this), disk = card(this);
    ramValue = metric(ram, "Pamięć RAM");
    ramSub = sub(ram);
    ramMeter = new ToolUi.Meter(this, MUTED);
    ram.addView(ramMeter, ToolUi.meterParams(this, 6, 10));
    diskValue = metric(disk, "Miejsce");
    diskSub = sub(disk);
    diskMeter = new ToolUi.Meter(this, MUTED);
    disk.addView(diskMeter, ToolUi.meterParams(this, 6, 10));
    pair.addView(ram, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    dp2.leftMargin = dp(this, 10);
    pair.addView(disk, dp2);
    content.addView(pair, cardParams(this));

    // ---------------------------------------------------------------- status rows
    LinearLayout statuses = ToolUi.group(this);
    network = new ToolUi.Status(this, "Połączenie");
    control = new ToolUi.Status(this, "Sterowanie ekranem");
    runtime = new ToolUi.Status(this, "Środowisko agentów");
    uptime = new ToolUi.Status(this, "Czas działania");
    ToolUi.add(statuses, network.view);
    ToolUi.add(statuses, control.view);
    ToolUi.add(statuses, runtime.view);
    ToolUi.add(statuses, uptime.view);
    content.addView(statuses, cardParams(this));
    error = text(this, "", 13, ERR);
    error.setPadding(dp(this, 20), 0, dp(this, 20), 0);
    error.setVisibility(View.GONE);
    content.addView(error, full());

    // ---------------------------------------------------------------- terminal
    TextView label = sectionLabel(this, "Terminal");
    label.setPadding(dp(this, 20), dp(this, 22), dp(this, 20), dp(this, 6));
    content.addView(label, full());
    TextView detail =
        ToolUi.note(
            this,
            "Komendy wykonują się na telefonie z uprawnieniami androidagent. Pliki zapisujesz w"
                + " jego katalogu roboczym. Pełny terminal i klienci agentów są dostępni we wbudowanym środowisku.",
            13);
    content.addView(detail, full());

    TextView fieldLabel = ToolUi.caption(this, "Komenda powłoki");
    fieldLabel.setPadding(dp(this, 20), dp(this, 14), dp(this, 20), dp(this, 8));
    content.addView(fieldLabel, full());
    command = input(this, "Komenda powłoki", true);
    command.setSingleLine(true);
    command.setText("id; uname -a");
    command.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    command.setContentDescription("Komenda powłoki");
    command.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_FULLSCREEN);
    command.setOnEditorActionListener(
        (v, action, event) -> {
          if (action == EditorInfo.IME_ACTION_GO && !busy) {
            execute();
            return true;
          }
          return false;
        });
    LinearLayout field = ToolUi.prompt(this, "$", command);
    content.addView(field, sideParams(0));

    HorizontalScrollView chipScroll = new HorizontalScrollView(this);
    chipScroll.setHorizontalScrollBarEnabled(false);
    chipScroll.setClipToPadding(false);
    chipScroll.setPadding(dp(this, 16), 0, dp(this, 16), 0);
    LinearLayout chips = row(this);
    for (String q : QUICK) {
      TextView chip = monoText(this, q, 12, MUTED);
      chip.setPadding(dp(this, 10), dp(this, 6), dp(this, 10), dp(this, 6));
      chip.setBackground(outlined(this, 0x00000000, 8, OUTLINE, 1));
      chip.setOnClickListener(
          v -> {
            if (busy) return;
            command.setText(q);
            command.setSelection(q.length());
          });
      Motion.press(chip);
      LinearLayout.LayoutParams cp = wrap();
      cp.rightMargin = dp(this, 8);
      chips.addView(chip, cp);
    }
    chipScroll.addView(chips);
    LinearLayout.LayoutParams chipP = full();
    chipP.topMargin = dp(this, 10);
    content.addView(chipScroll, chipP);

    run = button(this, "Uruchom komendę", true);
    run.setOnClickListener(
        v -> {
          if (busy) {
            if (shell != null) shell.stop();
          } else execute();
        });
    LinearLayout.LayoutParams runP = sideParams(12);
    runP.height = dp(this, 46);
    content.addView(run, runP);

    output = terminalText(this, "");
    output.setMinHeight(dp(this, 140));
    output.setText(style("$ Gotowy. Katalog: workspace/"));
    content.addView(output, sideParams(12));
    TextView hint = monoText(this, "limit 10 s · wynik do 32 KB · bez roota", 11, FAINT);
    hint.setPadding(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 6));
    content.addView(hint, full());

    LinearLayout more = ToolUi.group(this);
    ToolUi.Row setup =
        new ToolUi.Row(this, R.drawable.ms_terminal, "Otwórz środowisko i klientów", "Pełny terminal · Codex · Claude Code", true)
            .onClick(v -> EmbeddedRuntime.open(this));
    ToolUi.add(more, setup.view);
    LinearLayout.LayoutParams moreP = cardParams(this);
    moreP.topMargin = dp(this, 14);
    content.addView(more, moreP);

    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    if (state != null) {
      command.setText(state.getString("command", "id"));
      output.setText(style(state.getString("output", "")));
    }
    refresh();
    Motion.stagger(content, 40, 35);
    if (getIntent().getBooleanExtra("terminal", false))
      scroll.post(() -> scroll.smoothScrollTo(0, label.getTop()));
  }

  private LinearLayout.LayoutParams sideParams(int top) {
    LinearLayout.LayoutParams p = full();
    p.leftMargin = p.rightMargin = dp(this, 16);
    p.topMargin = dp(this, top);
    return p;
  }

  private TextView metric(LinearLayout card, String name) {
    card.addView(ToolUi.caption(this, name), full());
    TextView v = single(monoText(this, "—", 20, TEXT));
    v.setTypeface(mono(this, 600));
    v.setPadding(0, dp(this, 6), 0, 0);
    card.addView(v, full());
    return v;
  }

  private TextView sub(LinearLayout card) {
    TextView s = single(monoText(this, "", 11, FAINT));
    s.setPadding(0, dp(this, 2), 0, 0);
    card.addView(s, full());
    return s;
  }

  @Override
  protected void onResume() {
    super.onResume();
    handler.removeCallbacks(tick);
    handler.post(tick);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(tick);
    super.onPause();
  }

  private void refresh() {
    try {
      JSONObject s = DeviceState.read(this);
      // Only touch views whose values changed: no flicker, no needless accessibility events.
      String sig = s.toString().replaceAll("\"uptime_seconds\":\\d+", "");
      long up = s.getLong("uptime_seconds");
      uptime.set(up >= 3600 ? (up / 3600) + " h " + String.format(java.util.Locale.ROOT, "%02d", (up % 3600) / 60) + " min" : (up / 60) + " min", FAINT);
      if (sig.equals(signature)) return;
      signature = sig;
      ToolUi.setText(model, s.getString("model"));
      ToolUi.setText(system, "Android " + s.getString("android") + " · API " + s.getInt("api") + " · bez roota");
      int level = s.getInt("battery_percent");
      boolean charging = s.getBoolean("charging");
      int color = level < 0 ? FAINT : level < 20 ? ERR : level < 50 ? WARN : OK;
      ToolUi.setText(batteryValue, level < 0 ? "—" : level + "%");
      ToolUi.setText(batteryState, charging ? "ładowanie" : "na baterii");
      batteryState.setTextColor(charging ? OK : MUTED);
      batteryState.setBackground(rounded(this, Theme.withAlpha(charging ? OK : MUTED, 0x22), 6));
      batteryMeter.set(level < 0 ? 0 : level / 100f, color);
      batteryMeter.setContentDescription("Bateria " + level + "%");
      long ramFree = s.getLong("ram_available_bytes"), ramTotal = s.optLong("ram_total_bytes", 0);
      ToolUi.setText(ramValue, DeviceState.gb(ramFree));
      ToolUi.setText(ramSub, ramTotal > 0 ? "wolne z " + DeviceState.gb(ramTotal) : "wolne");
      ramMeter.set(used(ramFree, ramTotal), usage(used(ramFree, ramTotal)));
      long diskFree = s.getLong("storage_available_bytes"), diskTotal = s.optLong("storage_total_bytes", 0);
      ToolUi.setText(diskValue, DeviceState.gb(diskFree));
      ToolUi.setText(diskSub, diskTotal > 0 ? "wolne z " + DeviceState.gb(diskTotal) : "wolne");
      diskMeter.set(used(diskFree, diskTotal), usage(used(diskFree, diskTotal)));
      String net = s.getString("network");
      network.set(net, "offline".equals(net) ? ERR : OK);
      boolean screen = s.getBoolean("accessibility_connected");
      control.set(screen ? "Aktywne" : "Wyłączone", screen ? OK : FAINT);
      boolean ready = s.getBoolean("runtime_ready"), preparing = s.getBoolean("runtime_busy");
      runtime.set(ready ? "Gotowe" : preparing ? "Przygotowywanie" : "Nieprzygotowane", ready ? OK : preparing ? ACCENT : FAINT);
      runtime.dot.setPulsing(preparing);
      ToolUi.setVisible(error, false);
    } catch (Exception e) {
      error.setText(String.valueOf(e.getMessage()));
      ToolUi.setVisible(error, true);
    }
  }

  private static float used(long free, long total) {
    return total <= 0 ? 0f : 1f - (float) free / total;
  }

  private static int usage(float used) {
    return used > 0.92f ? ERR : used > 0.8f ? WARN : Theme.withAlpha(TEXT, 0xB0);
  }

  /** Colours a "$ cmd\n\noutput\n\n[exit n]" transcript; the characters stay exactly as given. */
  private CharSequence style(String full) {
    SpannableStringBuilder sb = new SpannableStringBuilder(full);
    int flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
    if (full.startsWith("$ ")) {
      sb.setSpan(new ForegroundColorSpan(ACCENT), 0, 1, flags);
      int end = full.indexOf('\n');
      if (end > 2) sb.setSpan(new TypefaceSpan(mono(this, 600)), 2, end, flags);
      else sb.setSpan(new ForegroundColorSpan(CODE_MUTED), 2, full.length(), flags);
    }
    int exit = full.lastIndexOf("\n[exit ");
    if (exit >= 0) {
      boolean ok = full.startsWith("\n[exit 0]", exit) || full.startsWith("\n[exit 0 ", exit);
      sb.setSpan(new ForegroundColorSpan(AnsiText.PALETTE[ok ? 2 : 1]), exit + 1, full.length(), flags);
    }
    int failed = full.indexOf("\n\nBłąd: ");
    if (failed >= 0) sb.setSpan(new ForegroundColorSpan(AnsiText.PALETTE[1]), failed + 2, full.length(), flags);
    if (full.endsWith("Wykonywanie…"))
      sb.setSpan(new ForegroundColorSpan(CODE_MUTED), full.length() - "Wykonywanie…".length(), full.length(), flags);
    return sb;
  }

  private void execute() {
    String cmd = command.getText().toString().trim();
    if (cmd.isEmpty()) {
      Motion.shake(command);
      return;
    }
    busy = true;
    run.setText("Zatrzymaj");
    run.setTextColor(ERR);
    run.setBackground(outlined(this, ERR_SOFT, 10, Theme.withAlpha(ERR, 0x40), 1));
    command.setEnabled(false);
    output.setText(style("$ " + cmd + "\n\nWykonywanie…"));
    shell = new LocalShell();
    LocalShell task = shell;
    new Thread(
            () -> {
              String result;
              try {
                result = task.run(getApplicationContext(), cmd).display();
              } catch (Exception e) {
                result = "Błąd: " + e.getMessage();
              }
              final String done = result;
              runOnUiThread(
                  () -> {
                    if (isDestroyed()) return;
                    busy = false;
                    command.setEnabled(true);
                    run.setText("Uruchom komendę");
                    run.setTextColor(ON_ACCENT);
                    run.setBackground(rounded(this, ACCENT, 10));
                    output.setText(style("$ " + cmd + "\n\n" + done));
                  });
            },
            "androidagent-terminal")
        .start();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putString("command", command.getText().toString());
    out.putString("output", output.getText().toString());
    super.onSaveInstanceState(out);
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    if (shell != null) shell.stop();
    super.onDestroy();
  }
}
