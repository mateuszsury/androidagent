package dev.mateuszsury.androidagent;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

/** Device tests, compiled into a separate APK. Never bundled in the product. */
public class AndroidAgentTests extends Instrumentation {
  interface Check {
    void run() throws Exception;
  }

  private final JSONArray results = new JSONArray();
  private Context app;
  private UiAutomation ui;
  private File evidence;
  private Activity current;
  private int failures;

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    app = getTargetContext();
    ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    evidence = new File(app.getExternalFilesDir(null), "evidence");
    evidence.mkdirs();
    try {
      suite();
    } catch (Throwable e) {
      failures++;
      record("suite", false, e.toString(), 0);
    }
    try (FileOutputStream out = new FileOutputStream(new File(evidence, "results.json"))) {
      out.write(
          new JSONObject()
              .put("tests", results)
              .put("failures", failures)
              .put("api", Build.VERSION.SDK_INT)
              .put("model", Build.MODEL)
              .toString(2)
              .getBytes(StandardCharsets.UTF_8));
    } catch (Exception ignored) {
    }
    Bundle b = new Bundle();
    b.putString(
        "stream", "\nandroidagent: " + results.length() + " checks, " + failures + " failures\n");
    b.putInt("failures", failures);
    finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, b);
  }

  private void test(String name, Check fn) {
    long start = SystemClock.elapsedRealtime();
    try {
      fn.run();
      record(name, true, "", SystemClock.elapsedRealtime() - start);
    } catch (Throwable e) {
      failures++;
      record(name, false, e.toString(), SystemClock.elapsedRealtime() - start);
    }
  }

  private void record(String n, boolean ok, String detail, long ms) {
    try {
      results.put(
          new JSONObject()
              .put("name", n)
              .put("pass", ok)
              .put("detail", detail)
              .put("duration_ms", ms));
    } catch (Exception ignored) {
    }
    Bundle b = new Bundle();
    b.putString("stream", (ok ? "PASS " : "FAIL ") + n + (ok ? "" : " — " + detail) + "\n");
    sendStatus(0, b);
  }

  private void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private Activity launch(Class<?> cls) {
    return launch(new Intent(app, cls));
  }

  private Activity launch(Intent intent) {
    current = startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    waitForIdleSync();
    return current;
  }

  private void main(Runnable action) {
    runOnMainSync(action);
    waitForIdleSync();
  }

  private View find(View v, String label) {
    if (label.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription()))
      return v;
    if (v instanceof TextView && label.contentEquals(((TextView) v).getText())) return v;
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) {
        View r = find(g.getChildAt(i), label);
        if (r != null) return r;
      }
    }
    return null;
  }

  private View find(String label) {
    return find(current.getWindow().getDecorView(), label);
  }

  private EditText edit(View v) {
    if (v instanceof EditText) return (EditText) v;
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) {
        EditText e = edit(g.getChildAt(i));
        if (e != null) return e;
      }
    }
    return null;
  }

  private ScrollView scroll(View v) {
    if (v instanceof ScrollView) return (ScrollView) v;
    if (v instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) v;
      for (int i = 0; i < group.getChildCount(); i++) {
        ScrollView child = scroll(group.getChildAt(i));
        if (child != null) return child;
      }
    }
    return null;
  }

  private void click(String label) {
    main(
        () -> {
          View v = find(label);
          check(v != null, "Missing UI: " + label);
          v.performClick();
        });
  }

  private void shot(String name) throws Exception {
    SystemClock.sleep(600);
    Bitmap b = ui.takeScreenshot();
    check(b != null, "Screenshot unavailable");
    try (FileOutputStream out = new FileOutputStream(new File(evidence, name + ".png"))) {
      b.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    b.recycle();
  }

  private void shell(String command) throws Exception {
    try (ParcelFileDescriptor fd = ui.executeShellCommand(command);
        InputStream in = new FileInputStream(fd.getFileDescriptor())) {
      byte[] buf = new byte[1024];
      while (in.read(buf) != -1) {}
    }
  }

  private String toolText(JSONObject result) throws Exception {
    return result.getJSONArray("content").getJSONObject(0).getString("text");
  }

  private JSONObject request(String method, JSONObject params, boolean authorized)
      throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:8766/mcp").openConnection();
    c.setConnectTimeout(5000);
    c.setReadTimeout(15000);
    c.setRequestMethod("POST");
    c.setDoOutput(true);
    c.setRequestProperty("Content-Type", "application/json");
    if (authorized) c.setRequestProperty("Authorization", "Bearer " + Prefs.token(app));
    JSONObject payload =
        new JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params);
    try (OutputStream out = c.getOutputStream()) {
      out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
    }
    int status = c.getResponseCode();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
      byte[] b = new byte[4096];
      int n;
      while ((n = in.read(b)) != -1) bytes.write(b, 0, n);
    }
    c.disconnect();
    return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8))
        .put("http_status", status);
  }

  private JSONObject tool(String name, JSONObject args) throws Exception {
    return request("tools/call", new JSONObject().put("name", name).put("arguments", args), true)
        .getJSONObject("result");
  }

  private void suite() throws Exception {
    test(
        "home: conversation-first UI",
        () -> {
          launch(MainActivity.class);
          check(find("Co dziś zrobimy?") != null, "Home heading");
          check(find("Historia rozmów") != null, "History affordance");
          shot("01-home-light");
        });
    test(
        "defaults: Codex, no fabricated ready state",
        () -> {
          check("codex".equals(Prefs.backend(app)), "Codex default");
          check(!new SetupState(app).canRun(), "Fresh install incorrectly ready");
        });
    test(
        "device: live battery, RAM, storage",
        () -> {
          JSONObject j = DeviceState.read(app);
          check(j.getInt("api") == Build.VERSION.SDK_INT, "Wrong OS");
          check(j.getLong("ram_total_bytes") > 0, "RAM");
          check(j.getLong("storage_available_bytes") > 0, "Storage");
          check(
              j.getInt("battery_percent") >= 0 && j.getInt("battery_percent") <= 100,
              "Battery range");
        });
    test(
        "terminal: real Android application UID",
        () -> {
          LocalShell.Result r = new LocalShell().run(app, "id; getprop ro.build.version.sdk");
          check(r.exitCode == 0, "Exit");
          check(
              r.output.contains("uid=" + android.os.Process.myUid()),
              "Must execute as application");
          check(r.output.contains("\n" + Build.VERSION.SDK_INT), "Android command output");
        });
    test(
        "terminal: persistent working files and Unicode",
        () -> {
          LocalShell.Result r =
              new LocalShell()
                  .run(
                      app,
                      "printf 'Zażółć gęślą jaźń' > androidagent-test.txt; cat"
                          + " androidagent-test.txt; pwd");
          check(r.exitCode == 0 && r.output.contains("Zażółć gęślą jaźń"), "UTF-8/file write");
          check(r.output.contains("/files/workspace"), "Wrong cwd");
          check(
              new LocalShell().run(app, "cat androidagent-test.txt").output.contains("Zażółć"),
              "File did not persist");
        });
    test(
        "terminal: nonzero exit reported",
        () -> {
          check(new LocalShell().run(app, "exit 7").exitCode == 7, "Lost failure");
        });
    test(
        "terminal: invalid command",
        () -> {
          check(
              new LocalShell().run(app, "pocket_nonexistent_command").exitCode == 127,
              "Expected command-not-found");
        });
    test(
        "terminal: output truncation",
        () -> {
          LocalShell.Result r = new LocalShell().run(app, "head -c 60000 /dev/zero | tr '\\000' x");
          check(r.output.length() < 33000 && r.output.contains("32 KB"), "Unbounded output");
        });
    test(
        "terminal: timeout and process cleanup",
        () -> {
          long start = SystemClock.elapsedRealtime();
          LocalShell.Result r = new LocalShell().run(app, "sleep 30; echo too_late");
          check(r.timedOut && r.exitCode == 124, "Timeout");
          check(SystemClock.elapsedRealtime() - start < 15000, "Timeout too slow");
          check(!r.output.contains("too_late"), "Command continued");
        });
    test(
        "terminal: explicit cancellation",
        () -> {
          LocalShell task = new LocalShell();
          ExecutorService ex = Executors.newSingleThreadExecutor();
          try {
            Future<LocalShell.Result> f =
                ex.submit(() -> task.run(app, "sleep 30; echo not_cancelled"));
            SystemClock.sleep(700);
            task.stop();
            LocalShell.Result r = f.get(5, TimeUnit.SECONDS);
            check(r.exitCode == 130 && !r.output.contains("not_cancelled"), "Cancel failed");
          } finally {
            ex.shutdownNow();
          }
        });
    test(
        "device UI: metrics and command controls",
        () -> {
          launch(DeviceActivity.class);
          check(find("Bateria") != null && find("Komenda powłoki") != null, "Missing controls");
          shot("02-device");
        });
    test(
        "device UI: execute button returns real output",
        () -> {
          main(
              () ->
                  edit(current.getWindow().getDecorView()).setText("printf 'ANDROIDAGENT_UI_OK'"));
          click("Uruchom komendę");
          long until = SystemClock.elapsedRealtime() + 15000;
          while (SystemClock.elapsedRealtime() < until
              && find("$ printf 'ANDROIDAGENT_UI_OK'\n\nANDROIDAGENT_UI_OK\n\n[exit 0]") == null)
            SystemClock.sleep(100);
          check(
              find("$ printf 'ANDROIDAGENT_UI_OK'\n\nANDROIDAGENT_UI_OK\n\n[exit 0]") != null,
              "UI output missing");
          shot("03-terminal");
        });
    final Sessions.Session[] session = {null};
    test(
        "chat: local /status through composer",
        () -> {
          main(() -> session[0] = ChatHub.get(app).create("codex"));
          launch(new Intent(app, ChatActivity.class).putExtra("id", session[0].id));
          main(() -> edit(current.getWindow().getDecorView()).setText("/status"));
          click("Wyślij");
          check(session[0].messages.size() == 2, "Expected user and real status reply");
          check(session[0].messages.get(1).text.contains("Odczyt lokalny"), "Not labeled local");
          shot("04-chat-status");
        });
    test(
        "chat: session persists to disk",
        () -> {
          Sessions saved = new Sessions(app);
          Sessions.Session found = saved.find(session[0].id);
          check(found != null && found.messages.size() == 2, "History not persisted");
        });
    test(
        "chat: draft survives activity recreation",
        () -> {
          main(() -> edit(current.getWindow().getDecorView()).setText("Zachowaj mój szkic"));
          Bundle saved = new Bundle();
          main(() -> callActivityOnSaveInstanceState(current, saved));
          check("Zachowaj mój szkic".equals(saved.getString("draft_text")), "Draft lost");
        });
    test(
        "chat: real model picker opens",
        () -> {
          click("Wybierz model");
          SystemClock.sleep(500);
          check(
              ui.getRootInActiveWindow().findAccessibilityNodeInfosByText("Model Codex").size() > 0,
              "Model picker missing");
          ui.performGlobalAction(
              android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
          SystemClock.sleep(300);
        });
    test(
        "chat: attachments sheet opens",
        () -> {
          click("Dodaj zdjęcie lub plik");
          SystemClock.sleep(500);
          check(
              ui.getRootInActiveWindow().findAccessibilityNodeInfosByText("Dodaj załącznik").size()
                  > 0,
              "Attachments sheet missing");
          shot("05-attachments");
          ui.performGlobalAction(
              android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        });
    test(
        "appearance: dark theme",
        () -> {
          main(
              () -> {
                Prefs.setThemeMode(app, "dark");
                Theme.changed(app);
              });
          launch(MainActivity.class);
          check(Ui.DARK, "Dark mode not applied");
          shot("06-home-dark");
          main(
              () -> {
                Prefs.setThemeMode(app, "light");
                Theme.changed(app);
              });
          launch(MainActivity.class);
        });
    test(
        "setup: accessible onboarding",
        () -> {
          launch(OnboardingActivity.class);
          check(
              find("Poznaj androidagent") != null && find("Zaczynamy") != null,
              "Onboarding missing");
          shot("07-setup");
        });
    test(
        "accessibility: service starts with explicit test grant",
        () -> {
          shell(
              "settings put secure enabled_accessibility_services"
                  + " dev.mateuszsury.androidagent/dev.mateuszsury.androidagent.BuddyService");
          shell("settings put secure accessibility_enabled 1");
          long until = SystemClock.elapsedRealtime() + 30000;
          while (BuddyService.get() == null && SystemClock.elapsedRealtime() < until)
            SystemClock.sleep(250);
          check(BuddyService.get() != null, "Service did not connect");
          launch(MainActivity.class);
        });
    test(
        "MCP: unauthenticated requests rejected",
        () -> {
          check(
              request("initialize", new JSONObject(), false).getInt("http_status") == 401,
              "Expected 401");
        });
    test(
        "MCP: authenticated initialize",
        () -> {
          JSONObject j =
              request("initialize", new JSONObject().put("protocolVersion", "2025-06-18"), true);
          check(j.getInt("http_status") == 200 && j.has("result"), "Handshake");
        });
    test(
        "MCP: tool discovery includes phone and shell",
        () -> {
          JSONArray tools =
              request("tools/list", new JSONObject(), true)
                  .getJSONObject("result")
                  .getJSONArray("tools");
          String text = tools.toString();
          check(
              tools.length() >= 17
                  && text.contains("device_status")
                  && text.contains("run_command")
                  && text.contains("read_screen"),
              "Missing tools");
        });
    test(
        "MCP: device status matches Android",
        () -> {
          JSONObject state = new JSONObject(toolText(tool("device_status", new JSONObject())));
          check(
              state.getInt("api") == Build.VERSION.SDK_INT
                  && state.getBoolean("accessibility_connected"),
              "Incorrect metrics");
        });
    test(
        "MCP: read actual screen",
        () -> {
          launch(MainActivity.class);
          SystemClock.sleep(400);
          check(
              toolText(tool("read_screen", new JSONObject())).contains("Co dziś zrobimy?"),
              "Screen tree missing native UI");
        });
    test(
        "MCP: actual tap focuses command field",
        () -> {
          launch(new Intent(app, DeviceActivity.class).putExtra("terminal", true));
          SystemClock.sleep(700);
          final int[] point = new int[2];
          main(
              () -> {
                EditText field = edit(current.getWindow().getDecorView());
                field.clearFocus();
                field.getLocationOnScreen(point);
                point[0] += field.getWidth() / 2;
                point[1] += field.getHeight() / 2;
              });
          check(
              toolText(tool("tap", new JSONObject().put("x", point[0]).put("y", point[1])))
                  .contains("Tapped"),
              "Tap cancelled");
          SystemClock.sleep(400);
          main(
              () ->
                  check(
                      edit(current.getWindow().getDecorView()).hasFocus(),
                      "Gesture did not focus field"));
        });
    test(
        "MCP: writes Unicode to real native field",
        () -> {
          String value = "printf 'Zażółć z Codexem'";
          check(
              toolText(tool("type_text", new JSONObject().put("text", value))).contains("Typed"),
              "Typing failed");
          main(
              () ->
                  check(
                      value.contentEquals(edit(current.getWindow().getDecorView()).getText()),
                      "Text action did not change field"));
          ui.performGlobalAction(
              android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        });
    test(
        "MCP: scroll moves native content",
        () -> {
          launch(DeviceActivity.class);
          main(() -> scroll(current.getWindow().getDecorView()).scrollTo(0, 0));
          tool("scroll", new JSONObject().put("direction", "down"));
          main(
              () ->
                  check(
                      scroll(current.getWindow().getDecorView()).getScrollY() > 0,
                      "Gesture did not scroll"));
        });
    test(
        "MCP: installed apps",
        () -> {
          check(
              toolText(tool("list_apps", new JSONObject()))
                  .contains("dev.mateuszsury.androidagent"),
              "App list missing androidagent");
        });
    test(
        "MCP: open Settings and read its screen",
        () -> {
          String result =
              toolText(tool("open_app", new JSONObject().put("app", "com.android.settings")));
          check(result.contains("Opened"), result);
          check(
              toolText(tool("read_screen", new JSONObject())).contains("com.android.settings"),
              "Settings not in tree");
        });
    test(
        "MCP: screenshot returns real JPEG",
        () -> {
          JSONObject shot = tool("screenshot", new JSONObject());
          JSONArray content = shot.getJSONArray("content");
          check(
              "image/jpeg".equals(content.getJSONObject(0).getString("mimeType")), "JPEG missing");
          byte[] data =
              android.util.Base64.decode(
                  content.getJSONObject(0).getString("data"), android.util.Base64.DEFAULT);
          check(data.length > 1000 && data[0] == (byte) 0xff, "Invalid screenshot");
        });
    test(
        "MCP: system navigation",
        () -> {
          check(
              toolText(tool("press", new JSONObject().put("button", "home")))
                  .contains("Pressed home"),
              "Home action failed");
          launch(MainActivity.class);
        });
    test(
        "MCP: unknown tool errors",
        () -> {
          check(
              tool("pocket_unknown", new JSONObject()).optBoolean("isError"),
              "Unknown tool should fail");
        });
    test(
        "MCP: shell blocked when developer mode off",
        () -> {
          Prefs.setDevMode(app, false);
          check(
              tool("run_command", new JSONObject().put("command", "id")).optBoolean("isError"),
              "Shell must require dev mode");
        });
    test(
        "MCP: shell approval denied means no execution",
        () -> {
          Prefs.setDevMode(app, true);
          ExecutorService ex = Executors.newSingleThreadExecutor();
          try {
            Future<JSONObject> f =
                ex.submit(
                    () ->
                        tool(
                            "run_command",
                            new JSONObject().put("command", "touch should-not-exist.txt")));
            long until = SystemClock.elapsedRealtime() + 10000;
            while (BuddyService.get().bubble().pendingConfirmText() == null
                && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
            check(BuddyService.get().bubble().pendingConfirmText() != null, "Approval missing");
            main(() -> BuddyService.get().bubble().answerFromApp(false));
            check(toolText(f.get(5, TimeUnit.SECONDS)).contains("DENIED"), "Denial not returned");
            check(
                !new File(LocalShell.workspace(app), "should-not-exist.txt").exists(),
                "Denied command executed");
          } finally {
            ex.shutdownNow();
          }
        });
    test(
        "MCP: approved command executes in app sandbox",
        () -> {
          ExecutorService ex = Executors.newSingleThreadExecutor();
          try {
            Future<JSONObject> f =
                ex.submit(
                    () ->
                        tool(
                            "run_command", new JSONObject().put("command", "printf MCP_APPROVED")));
            long until = SystemClock.elapsedRealtime() + 10000;
            while (BuddyService.get().bubble().pendingConfirmText() == null
                && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
            check(BuddyService.get().bubble().pendingConfirmText() != null, "Approval missing");
            main(() -> BuddyService.get().bubble().answerFromApp(true));
            check(
                toolText(f.get(10, TimeUnit.SECONDS)).contains("MCP_APPROVED"),
                "Approved command missing output");
          } finally {
            ex.shutdownNow();
            Prefs.setDevMode(app, false);
          }
        });
    test(
        "MCP: stop revokes further phone actions",
        () -> {
          main(() -> BuddyService.get().takeBackControl());
          SystemClock.sleep(200);
          String r = toolText(tool("press", new JSONObject().put("button", "home")));
          check(r.toLowerCase().contains("stop"), "Actions not revoked");
          main(() -> BuddyService.get().allowControl());
        });
    test(
        "attachments: provider rejects traversal",
        () -> {
          check(
              Attachments.file(app, "../../shared_prefs/buddy.xml") == null, "Traversal permitted");
        });
    test(
        "sessions: delete removes persisted conversation",
        () -> {
          main(() -> ChatHub.get(app).delete(session[0]));
          check(new Sessions(app).find(session[0].id) == null, "Deleted session remains");
        });
    test(
        "final: clean home",
        () -> {
          launch(MainActivity.class);
          shot("08-home-final");
        });
  }
}
