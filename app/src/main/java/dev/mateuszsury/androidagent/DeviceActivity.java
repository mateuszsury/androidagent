package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;

/** Live status and an honest, app-scoped terminal; no model needed. */
public class DeviceActivity extends Screen {
  private LinearLayout metrics;
  private TextView output;
  private EditText command;
  private Button run;
  private LocalShell shell;
  private boolean busy;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout root = column(this);
    root.setBackgroundColor(BG);
    root.setFitsSystemWindows(true);
    LinearLayout bar = row(this);
    bar.setPadding(dp(this, 8), dp(this, 8), dp(this, 16), dp(this, 8));
    ImageButton back = iconButton(this, R.drawable.ms_arrow_back, "Wróć");
    back.setOnClickListener(v -> finish());
    bar.addView(back, new LinearLayout.LayoutParams(dp(this, 48), dp(this, 48)));
    bar.addView(title(this, "Twój telefon", 20), weight1());
    Button refresh = button(this, "Odśwież", false);
    refresh.setOnClickListener(v -> refresh());
    bar.addView(refresh);
    root.addView(bar, full());
    ScrollView scroll = new ScrollView(this);
    LinearLayout content = column(this);
    content.setPadding(dp(this, 22), dp(this, 12), dp(this, 22), dp(this, 24));
    metrics = column(this);
    content.addView(metrics, full());
    TextView label = title(this, "Terminal", 22);
    label.setPadding(0, dp(this, 26), 0, dp(this, 8));
    content.addView(label);
    TextView detail =
        text(
            this,
            "Komendy wykonują się na telefonie z uprawnieniami androidagent. Pliki zapisujesz w"
                + " jego katalogu roboczym. Pełne narzędzia programistyczne zapewnia Termux.",
            13,
            MUTED);
    detail.setLineSpacing(dp(this, 3), 1);
    content.addView(detail, full());
    command = new EditText(this);
    command.setSingleLine(true);
    command.setText("id; uname -a");
    command.setTextColor(TEXT);
    command.setTextSize(13);
    command.setTypeface(mono(this, 400));
    command.setHint("Komenda powłoki");
    command.setContentDescription("Komenda powłoki");
    command.setPadding(dp(this, 14), dp(this, 14), dp(this, 14), dp(this, 14));
    command.setBackground(rounded(this, CARD, 14));
    LinearLayout.LayoutParams inputP = full();
    inputP.topMargin = dp(this, 18);
    content.addView(command, inputP);
    run = button(this, "Uruchom komendę", true);
    run.setOnClickListener(
        v -> {
          if (busy) {
            if (shell != null) shell.stop();
          } else execute();
        });
    content.addView(run, full());
    output = monoText(this, "$ Gotowy. Katalog: workspace/", 12, CODE_TEXT);
    output.setTextIsSelectable(true);
    output.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 16));
    output.setBackground(rounded(this, CODE, 16));
    output.setMinHeight(dp(this, 130));
    content.addView(output, full());
    TextView hint = text(this, "Limit: 10 s · wynik do 32 KB · bez roota", 11, MUTED);
    hint.setPadding(0, dp(this, 10), 0, dp(this, 20));
    content.addView(hint);
    Button setup = button(this, "Połącz Codexa i sterowanie ekranem", false);
    setup.setOnClickListener(v -> startActivity(new Intent(this, OnboardingActivity.class)));
    content.addView(setup, full());
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    if (state != null) {
      command.setText(state.getString("command", "id"));
      output.setText(state.getString("output", ""));
    }
    if (getIntent().getBooleanExtra("terminal", false))
      scroll.post(() -> scroll.smoothScrollTo(0, label.getTop()));
  }

  @Override
  protected void onResume() {
    super.onResume();
    refresh();
  }

  private void refresh() {
    metrics.removeAllViews();
    try {
      JSONObject s = DeviceState.read(this);
      TextView model = title(this, s.getString("model"), 23);
      metrics.addView(model);
      TextView android =
          text(
              this, "Android " + s.getString("android") + "  ·  API " + s.getInt("api"), 13, MUTED);
      android.setPadding(0, dp(this, 5), 0, dp(this, 22));
      metrics.addView(android);
      metric(
          "Bateria",
          s.getInt("battery_percent") + "%" + (s.getBoolean("charging") ? " · ładowanie" : ""));
      metric("Wolna pamięć RAM", DeviceState.gb(s.getLong("ram_available_bytes")));
      metric("Wolne miejsce", DeviceState.gb(s.getLong("storage_available_bytes")));
      metric("Połączenie", s.getString("network"));
      metric(
          "Sterowanie ekranem", s.getBoolean("accessibility_connected") ? "Aktywne" : "Wyłączone");
    } catch (Exception e) {
      metrics.addView(text(this, e.getMessage(), 14, ERR));
    }
  }

  private void metric(String name, String value) {
    LinearLayout r = row(this);
    r.setPadding(0, dp(this, 12), 0, dp(this, 12));
    r.addView(text(this, name, 14, MUTED), weight1());
    r.addView(title(this, value, 14));
    metrics.addView(r, full());
    View line = new View(this);
    line.setBackgroundColor(LINE);
    metrics.addView(line, new LinearLayout.LayoutParams(-1, dp(this, 1)));
  }

  private void execute() {
    String cmd = command.getText().toString().trim();
    if (cmd.isEmpty()) return;
    busy = true;
    run.setText("Zatrzymaj");
    command.setEnabled(false);
    output.setText("$ " + cmd + "\n\nWykonywanie…");
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
                    output.setText("$ " + cmd + "\n\n" + done);
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
    if (shell != null) shell.stop();
    super.onDestroy();
  }
}
