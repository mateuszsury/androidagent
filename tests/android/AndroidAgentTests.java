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
  private boolean exportOnly;
  private boolean termuxOnly;
  private boolean notificationFixture;
  private boolean notificationFixtureApplied;
  private boolean navigationOnly, navigationHideIme;
  private int navigationIterations = 5;
  private volatile String navigationPhase = "not-started";
  private volatile int navigationIteration;
  private volatile boolean navigationWatch;
  private Thread navigationWatchdog;
  private String runId;

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    exportOnly = arguments != null && "true".equals(arguments.getString("export_only"));
    termuxOnly = arguments != null && "true".equals(arguments.getString("termux_only"));
    notificationFixture = arguments != null && "true".equals(arguments.getString("notification_fixture"));
    navigationOnly = arguments != null && "true".equals(arguments.getString("navigation_only"));
    navigationHideIme = arguments != null && "ime_insets".equals(arguments.getString("navigation_cleanup"));
    if (navigationOnly && arguments != null)
      navigationIterations = "10".equals(arguments.getString("navigation_iterations")) ? 10 : 5;
    runId = arguments == null ? null : arguments.getString("run_id");
    if (runId == null) runId = java.util.UUID.randomUUID().toString();
    start();
  }

  @Override
  public void onStart() {
    app = getTargetContext();
    evidence =
        new File(app.getExternalFilesDir(null), navigationOnly ? "navigation-evidence" : termuxOnly ? "runtime-evidence" : "evidence");
    evidence.mkdirs();
    if (exportOnly) {
      Bundle result = new Bundle();
      try {
        exportEvidence();
        result.putString("stream", "Exported device evidence to Download/androidagent-tests\n");
        finish(Activity.RESULT_OK, result);
      } catch (Exception e) {
        result.putString("stream", "Evidence export failed: " + e + "\n");
        finish(Activity.RESULT_CANCELED, result);
      }
      return;
    }
    ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    try {
      if (notificationFixture && !termuxOnly && !navigationOnly && Build.VERSION.SDK_INT >= 33) {
        // Explicit instrumentation-only consent fixture for a post-runtime-install
        // run. The host driver must revoke POST_NOTIFICATIONS and clear Android's
        // user-set/user-fixed flags before starting this UID; revoking here can kill it.
        check(app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            == android.content.pm.PackageManager.PERMISSION_DENIED,
            "notification_fixture requires the host driver to revoke POST_NOTIFICATIONS before instrumentation");
        check(app.getSharedPreferences("notification_permission", Context.MODE_PRIVATE).edit().clear().commit(),
            "Could not reset the explicitly requested test-only notification consent fixture");
        notificationFixtureApplied = true;
      }
      if (navigationOnly) navigationSuite();
      else if (termuxOnly) termuxSuite();
      else suite();
    } catch (Throwable e) {
      failures++;
      record("suite", false, e.toString(), 0);
    } finally {
      if (navigationOnly) stopNavigationWatchdog();
    }
    try (FileOutputStream out = new FileOutputStream(new File(evidence, "results.json"))) {
      out.write(
          new JSONObject()
              .put("tests", results)
              .put("version", BuildInfo.VERSION)
              .put("failures", failures)
              .put("api", Build.VERSION.SDK_INT)
              .put("model", Build.MODEL)
              .put("run_id", runId)
              .put("navigation_only", navigationOnly)
              .put("navigation_cleanup", navigationHideIme ? "ime_insets" : "global_back")
              .put("navigation_iterations", navigationOnly ? navigationIterations : 0)
              .put("notification_fixture_requested", notificationFixture)
              .put("notification_fixture_applied", notificationFixtureApplied)
              .put("notification_fixture_scope", notificationFixtureApplied
                  ? "Test-only reset of notification_permission preferences; Android permission and flags prepared by host driver before instrumentation. Native permission dialog, refusal and foreground service remain asserted."
                  : "No notification-consent preference reset")
              .toString(2)
              .getBytes(StandardCharsets.UTF_8));
    } catch (Exception ignored) {
    }
    try {
      exportEvidence();
    } catch (Exception e) {
      failures++;
      record("evidence export", false, e.toString(), 0);
    }
    Bundle b = new Bundle();
    b.putString(
        "stream", "\nandroidagent: " + results.length() + " checks, " + failures + " failures\n");
    b.putInt("failures", failures);
    finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, b);
  }

  /** Export fixtures through MediaStore: adb cannot read Android/data on modern Android. */
  private void exportEvidence() throws Exception {
    android.net.Uri downloads =
        android.provider.MediaStore.Downloads.getContentUri(
            android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
    ContentResolver resolver = app.getContentResolver();
    File[] files = evidence.listFiles();
    if (files == null) throw new IOException("No evidence directory");
    for (File file : files) {
      String name = file.getName();
      if (!name.matches("results\\.json|[0-9]{2}-[a-z0-9-]+\\.png")) continue;
      String relative =
          "Download/" + (navigationOnly ? "androidagent-navigation-probe/" : termuxOnly ? "androidagent-runtime-tests/" : "androidagent-tests/");
      resolver.delete(
          downloads, "_display_name = ? AND relative_path = ?", new String[] {name, relative});
      ContentValues values = new ContentValues();
      values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
      values.put(
          android.provider.MediaStore.MediaColumns.MIME_TYPE,
          name.endsWith(".png") ? "image/png" : "application/json");
      values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relative);
      values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
      android.net.Uri uri = resolver.insert(downloads, values);
      if (uri == null) throw new IOException("Cannot export " + name);
      try (InputStream in = new FileInputStream(file);
          OutputStream out = resolver.openOutputStream(uri)) {
        if (out == null) throw new IOException("Cannot open " + name);
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
      }
      values.clear();
      values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
      resolver.update(uri, values, null, null);
    }
  }

  /** Compatibility flag now runs the genuine embedded-runtime bootstrap. Full coverage is separate. */
  private void termuxSuite() {
    test("Embedded terminal: genuine in-app initialization", () -> {
      EmbeddedRuntime.get(app).initializeBlocking();
      check(EmbeddedRuntime.get(app).ready(), "Embedded runtime failed initialization");
    });
  }

  /** Opt-in ANR probe. The ordinary suite and its navigation remain unchanged. */
  private void navigationSuite() throws Exception {
    startNavigationWatchdog();
    navigationPhase = "accessibility-binding";
    String own = new ComponentName(app, BuddyService.class).flattenToString();
    ui.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS);
    try {
      String saved = android.provider.Settings.Secure.getString(app.getContentResolver(),
          android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
      java.util.ArrayList<String> services = new java.util.ArrayList<>();
      if (saved != null) for (String value : saved.split(":")) {
        ComponentName component = ComponentName.unflattenFromString(value);
        if (!value.isEmpty() && !new ComponentName(app, BuddyService.class).equals(component))
          services.add(value);
      }
      // Rebind only this test target; retain every unrelated accessibility grant.
      if (BuddyService.get() == null) {
        android.provider.Settings.Secure.putString(app.getContentResolver(),
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            android.text.TextUtils.join(":", services));
        services.add(own);
        android.provider.Settings.Secure.putString(app.getContentResolver(),
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            android.text.TextUtils.join(":", services));
        android.provider.Settings.Secure.putInt(app.getContentResolver(),
            android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
      }
    } finally { ui.dropShellPermissionIdentity(); }
    long until = SystemClock.elapsedRealtime() + 30000;
    while ((BuddyService.get() == null || !McpHost.get(app).running())
        && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
    check(BuddyService.get() != null && McpHost.get(app).running(), "Probe accessibility/MCP did not connect");
    navigationPhase = "mcp-initialize";
    check(request("initialize", new JSONObject().put("protocolVersion", "2025-06-18"), true)
        .getInt("http_status") == 200, "Probe MCP initialize failed");
    navigationPhase = "initial-home";
    launch(MainActivity.class);
    main(() -> app.getSystemService(android.view.inputmethod.InputMethodManager.class)
        .hideSoftInputFromWindow(current.getWindow().getDecorView().getWindowToken(), 0));
    for (int index = 1; index <= navigationIterations; index++) {
      navigationIteration = index;
      test("navigation probe " + index + ": " + (navigationHideIme ? "IMM hide + IME insets" : "global Back")
          + " then immediate DeviceActivity", () -> {
        navigationPhase = "launch-terminal";
        launch(new Intent(app, DeviceActivity.class).putExtra("terminal", true));
        SystemClock.sleep(700);
        final int[] point = new int[2];
        navigationPhase = "locate-field";
        main(() -> {
          EditText field = edit(current.getWindow().getDecorView());
          check(field != null, "Missing command field");
          field.clearFocus();field.getLocationOnScreen(point);
          point[0] += field.getWidth() / 2;point[1] += field.getHeight() / 2;
        });
        navigationPhase = "mcp-tap";
        check(toolText(tool("tap", new JSONObject().put("x", point[0]).put("y", point[1])))
            .contains("Tapped"), "Probe tap cancelled");
        SystemClock.sleep(400);
        main(() -> check(edit(current.getWindow().getDecorView()).hasFocus(), "Probe field not focused"));
        navigationPhase = "mcp-unicode";
        String value = "printf 'Zażółć z Codexem'";
        check(toolText(tool("type_text", new JSONObject().put("text", value))).contains("Typed"),
            "Probe Unicode typing failed");
        main(() -> check(value.contentEquals(edit(current.getWindow().getDecorView()).getText()),
            "Probe Unicode content differs"));
        if (navigationHideIme) {
          navigationPhase = "probe-only-hide-ime";
          main(() -> app.getSystemService(android.view.inputmethod.InputMethodManager.class)
              .hideSoftInputFromWindow(current.getWindow().getDecorView().getWindowToken(), 0));
          long deadline = SystemClock.elapsedRealtime() + 4000;
          boolean[] hidden = {false};
          do {
            main(() -> {
              WindowInsets insets = current.getWindow().getDecorView().getRootWindowInsets();
              hidden[0] = insets != null && !insets.isVisible(WindowInsets.Type.ime());
            });
            if (!hidden[0]) SystemClock.sleep(25);
          } while (!hidden[0] && SystemClock.elapsedRealtime() < deadline);
          check(hidden[0], "Probe IME did not become hidden");
        } else {
          navigationPhase = "global-back";
          ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        }
        // Intentionally no idle/IME wait between the original Back and launch.
        navigationPhase = "immediate-device-launch";
        launch(DeviceActivity.class);
        navigationPhase = "device-scroll-main";
        main(() -> scroll(current.getWindow().getDecorView()).scrollTo(0, 0));
        navigationPhase = "device-mcp-scroll";
        tool("scroll", new JSONObject().put("direction", "down"));
        main(() -> check(scroll(current.getWindow().getDecorView()).getScrollY() > 0,
            "Probe gesture did not scroll"));
        navigationPhase = "iteration-complete";
      });
    }
    navigationPhase = "probe-complete";
  }

  private void startNavigationWatchdog() {
    final Thread instrumentation = Thread.currentThread();
    final Thread mainThread = Looper.getMainLooper().getThread();
    final Handler handler = new Handler(Looper.getMainLooper());
    final java.util.concurrent.atomic.AtomicLong lastMain = new java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime());
    final java.util.concurrent.atomic.AtomicBoolean heartbeatPending = new java.util.concurrent.atomic.AtomicBoolean();
    navigationWatch = true;
    navigationWatchdog = new Thread(() -> {
      StringBuilder recent = new StringBuilder();
      android.net.Uri[] snapshots = new android.net.Uri[2];
      int sequence = 0;
      do {
        if (heartbeatPending.compareAndSet(false, true)) handler.post(() -> {
          lastMain.set(SystemClock.elapsedRealtime());heartbeatPending.set(false);
        });
        long now = SystemClock.elapsedRealtime();
        String sample = "sample=" + sequence + " epoch_ms=" + System.currentTimeMillis()
            + " elapsed_ms=" + now + " main_heartbeat_age_ms=" + (now - lastMain.get())
            + " iteration=" + navigationIteration + " phase=" + navigationPhase
            + " mode=" + (navigationHideIme ? "ime_insets" : "global_back") + "\n"
            + navigationStack("main", mainThread) + navigationStack("instrumentation", instrumentation);
        // Only clocks, fixed phase labels and stack frames; no user data or credentials.
        for (int offset = 0; offset < sample.length(); offset += 3000)
          android.util.Log.i("AA.NavigationProbe", sample.substring(offset, Math.min(sample.length(), offset + 3000)));
        recent.append(sample).append('\n');
        if (recent.length() > 65536) recent.delete(0, recent.indexOf("\n", recent.length() - 65536) + 1);
        byte[] bytes = recent.toString().getBytes(StandardCharsets.UTF_8);
        android.util.AtomicFile local = new android.util.AtomicFile(new File(evidence, "navigation-snapshot.txt"));
        FileOutputStream pending = null;
        try { pending = local.startWrite();pending.write(bytes);local.finishWrite(pending); }
        catch (Exception failure) {
          if (pending != null) local.failWrite(pending);
          android.util.Log.i("AA.NavigationProbe", "Local snapshot failure: " + failure.getClass().getSimpleName());
        }
        int slot = sequence++ % 2;
        try {
          ContentResolver resolver = app.getContentResolver();
          ContentValues values = new ContentValues();
          values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
          if (snapshots[slot] == null) {
            String safeId = runId.replaceAll("[^A-Za-z0-9_-]", "_");
            if (safeId.length() > 64) safeId = safeId.substring(0, 64);
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "navigation-" + safeId + "-" + slot + ".txt");
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/androidagent-navigation-probe/");
            snapshots[slot] = resolver.insert(android.provider.MediaStore.Downloads.getContentUri(
                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
          } else resolver.update(snapshots[slot], values, null, null);
          if (snapshots[slot] == null) throw new IOException("Cannot create navigation snapshot");
          try (ParcelFileDescriptor fd = resolver.openFileDescriptor(snapshots[slot], "rwt")) {
            if (fd == null) throw new IOException("Cannot open navigation snapshot");
            try (FileOutputStream out = new FileOutputStream(fd.getFileDescriptor())) {
              out.write(bytes);out.flush();out.getFD().sync();
            }
          }
          values.clear();values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
          resolver.update(snapshots[slot], values, null, null);
        } catch (Exception failure) {
          android.util.Log.i("AA.NavigationProbe", "Download snapshot failure: " + failure.getClass().getSimpleName());
        }
        if (navigationWatch) try { Thread.sleep(1000); } catch (InterruptedException stopped) { /* Requested probe shutdown. */ }
      } while (navigationWatch);
    }, "androidagent-navigation-watchdog");
    navigationWatchdog.setDaemon(true);navigationWatchdog.start();
  }

  private static String navigationStack(String label, Thread thread) {
    StringBuilder result = new StringBuilder(label).append(" state=").append(thread.getState()).append('\n');
    StackTraceElement[] stack = thread.getStackTrace();
    for (int index = 0; index < Math.min(20, stack.length); index++) result.append("  at ").append(stack[index]).append('\n');
    return result.toString();
  }

  private void stopNavigationWatchdog() {
    navigationWatch = false;
    if (navigationWatchdog != null) {
      navigationWatchdog.interrupt();
      try { navigationWatchdog.join(1500); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
    }
  }

  private void runUnsignedClaude(Sessions.Session session, boolean dev) throws Exception {
    check(session != null && !SetupState.agentReady(app, "claude"), "No unsigned Claude fixture");
    int before = session.messages.size();
    long cleanupDeadline = SystemClock.elapsedRealtime() + 10000;
    while (true) {
      try { McpHost.get(app).requireReady(session.id); break; }
      catch (IllegalStateException pendingCleanup) {
        if (SystemClock.elapsedRealtime() >= cleanupDeadline) throw pendingCleanup;
        SystemClock.sleep(25);
      }
    }
    String credential = McpHost.get(app).credential(session.id);
    String callbackRun = java.util.UUID.randomUUID().toString();
    main(() -> {
      session.callbackRun = callbackRun;
      session.runAcknowledged = false;
      session.running = true;
      session.lastError = "";
      session.phoneConnected = false;
      session.phoneToolCount = 0;
    });
    Termux.run(
        app, "ask", session.id, "claude", dev ? "1" : "0", "Unsigned integration check", "", "", credential, callbackRun);
    long until = SystemClock.elapsedRealtime() + 60000;
    while (SystemClock.elapsedRealtime() < until) {
      if (!session.running && session.messages.size() > before) break;
      SystemClock.sleep(200);
    }
    check(
        session.messages.size() > before && !session.running,
        "Native headless request did not finish: running=" + session.running
            + ", new_messages=" + (session.messages.size() - before));
    check(McpHost.get(app).principal(credential) == null, "Finished CLI retained its run credential");
    check(
        session.messages.subList(before, session.messages.size()).stream()
            .anyMatch(
                m -> m.kind == Bubble.Kind.ERROR && m.text.toLowerCase().contains("not logged in")),
        "Missing actual CLI login error");
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
    current =
        startActivitySync(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    waitForIdleSync();
    return current;
  }

  private void main(Runnable action) {
    final java.util.concurrent.atomic.AtomicReference<Throwable> error =
        new java.util.concurrent.atomic.AtomicReference<>();
    runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable e) {
            error.set(e);
          }
        });
    waitForIdleSync();
    if (error.get() != null) throw new AssertionError("UI action failed", error.get());
  }

  private void mainCheck(Check action) {
    main(() -> {
      try { action.run(); }
      catch (Exception error) { throw new RuntimeException(error); }
    });
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

  private void dialogClick(String label) {
    long until = SystemClock.elapsedRealtime() + 5000;
    while (SystemClock.elapsedRealtime() < until) {
      android.view.accessibility.AccessibilityNodeInfo root = ui.getRootInActiveWindow();
      if (root != null)
        for (android.view.accessibility.AccessibilityNodeInfo match :
            root.findAccessibilityNodeInfosByText(label)) {
          if (!label.contentEquals(match.getText())) continue;
          android.view.accessibility.AccessibilityNodeInfo node = match;
          while (node != null && !node.isClickable()) node = node.getParent();
          if (node != null
              && node.performAction(
                  android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
            SystemClock.sleep(300);
            waitForIdleSync();
            return;
          }
        }
      SystemClock.sleep(100);
    }
    throw new AssertionError("Dialog action missing: " + label);
  }

  private Activity openHomeChat() {
    ActivityMonitor monitor = addMonitor(ChatActivity.class.getName(), null, false);
    try {
      click("Rozpocznij rozmowę");
      Activity opened = waitForMonitorWithTimeout(monitor, 15000);
      check(opened != null, "Home did not open chat");
      current = opened;
      waitForIdleSync();
      return opened;
    } finally {
      removeMonitor(monitor);
    }
  }

  private void event(Sessions.Session session, String json) throws Exception {
    JSONObject value = new JSONObject(json).put("buddy_session", session.id);
    main(() -> ChatHub.get(app).onEvent(value));
  }

  private void delta(Sessions.Session session, String text) throws Exception {
    JSONObject part =
        new JSONObject()
            .put("type", "content_block_delta")
            .put("delta", new JSONObject().put("type", "text_delta").put("text", text));
    JSONObject value =
        new JSONObject()
            .put("type", "stream_event")
            .put("buddy_session", session.id)
            .put("event", part);
    main(() -> ChatHub.get(app).onEvent(value));
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
    if (Build.VERSION.SDK_INT >= 33)
      test("notifications: native fresh-consent denial keeps foreground tools usable", () -> {
        // DeviceActivity does not bootstrap the embedded runtime. Exercise the
        // first actual Android consent prompt before any test starts a tool host.
        launch(DeviceActivity.class);
        NotificationRegressionChecks.freshConsentDenialKeepsToolsRunning(this, current, ui);
      });
    test("permissions: real state and native consent routes", () -> PermissionRegressionChecks.readOnlyStateAndNativeRoutes(app));
    test("permissions: FlowMesh is never mistaken for our service", () -> PermissionRegressionChecks.accessibilityRecognitionIsScoped(app));
    test("permissions: screen refresh preserves existing consent", () -> {launch(PermissionsActivity.class);PermissionRegressionChecks.screenRefreshAndNativeAccessibilityRoute(this,current);});
    test("commands: native approval works without Accessibility", () -> {launch(new Intent(app,ChatActivity.class).putExtra("id",ChatHub.get(app).sessions.current.id));ApprovedCommandsChecks.inlineApprovalShowsAndExecutesExactCommand(this,current);});
    test("commands: native denial never executes the command", () -> ApprovedCommandsChecks.inlineDenialDoesNotExecuteCommand(this,current));
    test("commands: stale consent cannot approve a replacement", () -> ApprovedCommandsChecks.staleButtonCannotApproveReplacement(this,current));
    test("commands: a revoked run cannot inherit consent", () -> ApprovedCommandRuntimeChecks.revokedRunCannotInheritApproval(app));
    test("commands: cancellation stops the approved shell", () -> ApprovedCommandRuntimeChecks.cancellationStopsApprovedShell(app));
    test("commands: a cancelled shell cannot spawn", () -> ApprovedCommandRuntimeChecks.cancelledShellCannotSpawn(app));
    test(
        "home: conversation-first UI",
        () -> {
          launch(MainActivity.class);
          check(find("Co dziś zrobimy?") != null, "Home heading");
          check(find("Historia rozmów") != null, "History affordance");
          shot("01-home-light");
        });
    test("appearance: native light dialog and custom text stay readable",
        () -> UiRegressionChecks.dialogMatchesPalette(this, current));
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
          LocalShell.Result r =
              new LocalShell()
                  .run(app, "sleep 30 & child=$!; echo CHILD=$child; wait $child; echo too_late");
          check(r.timedOut && r.exitCode == 124, "Timeout");
          check(SystemClock.elapsedRealtime() - start < 15000, "Timeout too slow");
          check(!r.output.contains("too_late"), "Command continued");
          java.util.regex.Matcher pid =
              java.util.regex.Pattern.compile("CHILD=(\\d+)").matcher(r.output);
          check(pid.find(), "Child did not start");
          File stat = new File("/proc/" + pid.group(1) + "/stat");
          if (stat.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(stat))) {
              String line = reader.readLine();
              check(
                  line.substring(line.lastIndexOf(')') + 2).startsWith("Z "),
                  "Child survived timeout");
            } catch (IOException e) {
              // A killed child can disappear between exists(), open() and read().
              // ESRCH on an already-open /proc fd also proves that it exited.
              if (stat.exists() && !String.valueOf(e.getMessage()).contains("ESRCH")) throw e;
            }
          }
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
          ActivityMonitor monitor = addMonitor(ChatActivity.class.getName(), null, false);
          try {
            main(() -> current.recreate());
            Activity recreated = waitForMonitorWithTimeout(monitor, 15000);
            check(recreated != null, "Activity did not recreate");
            current = recreated;
            waitForIdleSync();
            main(
                () ->
                    check(
                        "Zachowaj mój szkic"
                            .contentEquals(edit(current.getWindow().getDecorView()).getText()),
                        "Draft lost on recreated screen"));
          } finally {
            removeMonitor(monitor);
          }
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
        "chat renderer: copy preserves code indentation",
        () -> {
          main(
              () ->
                  ChatHub.get(app)
                      .note(
                          session[0],
                          Bubble.Kind.BUDDY,
                          "Test renderera kodu:\n```python\n    return 42\n```"));
          click("Kopiuj kod");
          main(
              () -> {
                android.content.ClipData clip =
                    app.getSystemService(android.content.ClipboardManager.class).getPrimaryClip();
                check(
                    clip != null && "    return 42".contentEquals(clip.getItemAt(0).getText()),
                    "Code copy changed indentation");
              });
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
          UiRegressionChecks.dialogMatchesPalette(this, current);
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
    test("MCP: Stop closes active and queued client sockets", RuntimeRegressionChecks::mcpShutdown);
    test("MCP: complete chunk framing and bounded headers", RuntimeRegressionChecks::mcpFraming);
    test("MCP: helper events bypass tool calls waiting for the user", AgentRegressionChecks::eventsBypassBlockedTools);
    test("MCP: a new run cannot revive an old credential or queued request",
        () -> RuntimeRegressionChecks.mcpRunGeneration(app));
    test("attachments: bounded panorama decode and file provider projection",
        () -> RuntimeRegressionChecks.attachments(app));
    test("voice: previous speech callbacks and delayed TTS cannot revive after Stop",
        () -> RuntimeRegressionChecks.voice(this));
    test("Stop: main thread stays responsive while USB cleanup is blocked",
        () -> RuntimeRegressionChecks.stopDoesNotBlock(this, app));
    test("terminal: background children obey timeout and cleanup",
        () -> RuntimeRegressionChecks.backgroundCommand(app));
    test(
        "accessibility: service starts with explicit test grant",
        () -> {
          // Instrumentation/app updates stop existing services. Reset the grant
          // so each run exercises a fresh Android binding, rather than a stale value.
          shell("settings put secure enabled_accessibility_services ''");
          shell("settings put secure accessibility_enabled 0");
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
        "MCP: tool discovery includes phone, shell and Docker",
        () -> {
          JSONArray tools =
              request("tools/list", new JSONObject(), true)
                  .getJSONObject("result")
                  .getJSONArray("tools");
          String text = tools.toString();
          check(
              tools.length() >= 29
                  && text.contains("device_status")
                  && text.contains("run_command")
                  && text.contains("read_screen")
                  && text.contains("docker_build")
                  && text.contains("docker_export")
                  && text.contains("docker_cleanup"),
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
          main(
              () ->
                  app.getSystemService(android.view.inputmethod.InputMethodManager.class)
                      .hideSoftInputFromWindow(
                          current.getWindow().getDecorView().getWindowToken(), 0));
          String screen = "";
          long until = SystemClock.elapsedRealtime() + 5000;
          do {
            SystemClock.sleep(250);
            screen = toolText(tool("read_screen", new JSONObject()));
          } while (!screen.contains("Co dziś zrobimy?") && SystemClock.elapsedRealtime() < until);
          check(screen.contains("Co dziś zrobimy?"), "Screen tree missing native UI: " + screen);
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
          String screen = "";
          long until = SystemClock.elapsedRealtime() + 5000;
          do {
            screen = toolText(tool("read_screen", new JSONObject()));
            if (screen.contains("com.android.settings")) break;
            SystemClock.sleep(250);
          } while (SystemClock.elapsedRealtime() < until);
          check(screen.contains("com.android.settings"), "Settings not in tree: " + screen);
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
          launch(new Intent(app, ChatActivity.class).putExtra("id", session[0].id));
          ExecutorService ex = Executors.newSingleThreadExecutor();
          try {
            Future<JSONObject> f =
                ex.submit(
                    () ->
                        tool(
                            "run_command",
                            new JSONObject().put("command", "touch should-not-exist.txt")));
            long until = SystemClock.elapsedRealtime() + 10000;
            while (ApprovedCommands.get(app).pendingForChat(session[0].id) == null
                && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
            check(ApprovedCommands.get(app).pendingForChat(session[0].id) != null, "Approval missing");
            check(
                ApprovedCommands.get(app).pendingForChat(session[0].id).summary
                    .contains("touch should-not-exist.txt"),
                "Approval does not identify exact command");
            click("Odrzuć");
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
            while (ApprovedCommands.get(app).pendingForChat(session[0].id) == null
                && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
            check(ApprovedCommands.get(app).pendingForChat(session[0].id) != null, "Approval missing");
            main(
                () ->
                    check(
                        !BuddyService.get().bubble().approvalOverlayVisible(),
                        "Overlay obscures native chat approval"));
            shot("09-command-approval");
            click("Zatwierdź");
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
    claudeUiSuite();
    test(
        "final: clean home",
        () -> {
          launch(MainActivity.class);
          shot("08-home-final");
        });
  }

  private void claudeUiSuite() throws Exception {
    final Sessions.Session[] claude = {null};
    test(
        "Claude UI: choose backend on home",
        () -> {
          launch(MainActivity.class);
          click("Wybierz agenta");
          dialogClick("Claude Code");
          check("claude".equals(Prefs.backend(app)), "Backend selection not saved");
          check(
              "Napisz do Claude Code"
                  .contentEquals(edit(current.getWindow().getDecorView()).getHint()),
              "Composer uses wrong backend");
          shot("10-home-claude");
        });
    test(
        "Claude UI: home creates a Claude conversation",
        () -> {
          openHomeChat();
          claude[0] = ChatHub.get(app).sessions.current;
          check("claude".equals(claude[0].backend), "Home forced Codex");
          check(
              edit(current.getWindow().getDecorView()).getHint().toString().contains("Claude Code"),
              "Wrong chat composer");
        });
    test(
        "Claude UI: real model picker selects Sonnet alias",
        () -> {
          click("Wybierz model");
          check(
              ui.getRootInActiveWindow()
                      .findAccessibilityNodeInfosByText("Model Claude Code")
                      .size()
                  > 0,
              "Wrong picker backend");
          dialogClick("Sonnet");
          check("sonnet".equals(claude[0].model), "Model not selected");
          check("sonnet".equals(new Sessions(app).find(claude[0].id).model), "Model not persisted");
          shot("11-claude-chat");
        });
    test(
        "Claude UI: local status and unsigned model gate",
        () -> {
          main(() -> edit(current.getWindow().getDecorView()).setText("/status"));
          click("Wyślij");
          check(
              claude[0].messages.stream().anyMatch(m -> m.text.contains("Odczyt lokalny")),
              "Claude lost local diagnostics");
          int before = claude[0].messages.size();
          final String[] error = {null};
          main(() -> error[0] = ChatHub.get(app).send(claude[0], "Unsigned test"));
          check(
              error[0] != null && error[0].contains("Claude Code"),
              "Missing Claude login guidance");
          check(
              claude[0].messages.size() == before && !claude[0].running,
              "Unsigned run was started");
        });
    test(
        "Claude UI: backend switch keeps histories separate",
        () -> {
          launch(MainActivity.class);
          click("Wybierz agenta");
          dialogClick("Codex");
          openHomeChat();
          Sessions.Session codex = ChatHub.get(app).sessions.current;
          check(
              "codex".equals(codex.backend) && codex.model.isEmpty() && codex.messages.isEmpty(),
              "Claude state leaked into Codex");
          Sessions.Session saved = new Sessions(app).find(claude[0].id);
          check(
              saved != null && "claude".equals(saved.backend) && "sonnet".equals(saved.model),
              "Claude history changed");
          launch(new Intent(app, ChatActivity.class).putExtra("id", claude[0].id));
        });
    test(
        "Claude protocol fixture: live Unicode text before final message",
        () -> {
          event(claude[0], "{\"type\":\"system\",\"subtype\":\"init\"}");
          int before = claude[0].messages.size();
          delta(claude[0], "TEST STREAMU: Zażółć ");
          delta(claude[0], "gęślą");
          TextView live = (TextView) find("Odpowiedź na żywo");
          check(
              live != null && "TEST STREAMU: Zażółć gęślą".contentEquals(live.getText()),
              "No live text in native UI");
          check(
              claude[0].messages.size() == before, "Partial tokens persisted as separate messages");
          shot("12-claude-stream-fixture");
        });
    test("chat UI: live response survives leaving and resuming the screen",
        () -> UiRegressionChecks.streamSurvivesResume(this, current));
    test(
        "Claude protocol fixture: final text is persisted once",
        () -> {
          event(
              claude[0],
              "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"TEST"
                  + " STREAMU: Zażółć gęślą\"}]}}");
          event(
              claude[0],
              "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"TEST STREAMU: Zażółć"
                  + " gęślą\"}");
          event(claude[0], "{\"type\":\"buddy_done\"}");
          check(
              claude[0].streamText.isEmpty() && find("Odpowiedź na żywo") == null,
              "Partial view remains");
          long count =
              new Sessions(app)
                  .find(claude[0].id).messages.stream()
                      .filter(m -> m.text.equals("TEST STREAMU: Zażółć gęślą"))
                      .count();
          check(count == 1 && !claude[0].running, "Duplicated final reply or stuck run");
        });
    test(
        "Claude protocol fixture: subagent deltas stay separate",
        () -> {
          event(
              claude[0],
              "{\"type\":\"stream_event\",\"parent_tool_use_id\":\"other-agent\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"SHOULD_NOT_APPEAR\"}}}");
          check(
              claude[0].streamText.isEmpty() && find("SHOULD_NOT_APPEAR") == null,
              "Subagent text mixed into main stream");
        });
    test(
        "Claude protocol fixture: error arrays and auth errors are visible",
        () -> {
          event(claude[0], "{\"type\":\"system\",\"subtype\":\"init\"}");
          event(
              claude[0],
              "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,\"errors\":[\"TEST:"
                  + " brak uprawnienia\"]}");
          event(
              claude[0],
              "{\"type\":\"assistant\",\"error\":\"authentication_failed\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"TEST:"
                  + " zaloguj Claude\"}]}}");
          event(
              claude[0],
              "{\"type\":\"result\",\"is_error\":true,\"result\":\"TEST: zaloguj Claude\"}");
          event(claude[0], "{\"type\":\"buddy_done\"}");
          check(find("TEST: brak uprawnienia") != null, "CLI errors array discarded");
          long authErrors =
              claude[0].messages.stream()
                  .filter(m -> m.kind == Bubble.Kind.ERROR && m.text.equals("TEST: zaloguj Claude"))
                  .count();
          check(authErrors == 1, "Authentication failure hidden or duplicated");
        });
    test(
        "Claude protocol fixture: stop preserves partial text and ends run",
        () -> {
          event(claude[0], "{\"type\":\"system\",\"subtype\":\"init\"}");
          delta(claude[0], "TEST: przerwana odpowiedź");
          event(claude[0], "{\"type\":\"buddy_stopped\"}");
          event(claude[0], "{\"type\":\"buddy_done\"}");
          check(!claude[0].running && claude[0].streamText.isEmpty(), "Stop leaves live run");
          check(
              new Sessions(app)
                  .find(claude[0].id).messages.stream()
                      .anyMatch(m -> m.text.equals("TEST: przerwana odpowiedź")),
              "Partial reply lost on stop");
          main(
              () -> {
                ChatHub.get(app).delete(claude[0]);
                Prefs.setBackend(app, "codex");
              });
        });
    launch(MainActivity.class); // The preceding fixture deletes its displayed chat.
    test("agents: callbacks cannot stop or resurrect another run",
        () -> mainCheck(() -> AgentRegressionChecks.callbackIsolation(app)));
    test("agents: history retention preserves active chats and releases evictions",
        () -> mainCheck(() -> AgentRegressionChecks.retentionKeepsRunningChats(app)));
    test("agents: history migration preserves data and updates only changed conversations",
        () -> mainCheck(() -> AgentRegressionChecks.persistenceMigration(app)));
    test("agents: initial streaming blocks are bounded and subagent replies separated",
        () -> mainCheck(() -> AgentRegressionChecks.streamingBoundsAndSubagents(app)));
    test("agents: failed Stop ends local state and reports undelivered termination",
        () -> mainCheck(() -> AgentRegressionChecks.failedStopEndsLocalRun(app)));
    test("agents: surviving helper after app restart receives an exact Stop",
        () -> mainCheck(() -> AgentRegressionChecks.busyRequestRecoversSurvivingHelper(app)));
    test("agents: rejected dispatch preserves draft and revokes run access",
        () -> mainCheck(() -> AgentRegressionChecks.failedDispatchDoesNotPersistUserMessage(app)));
    test("agents: tool calls carry meta and receive their results in place",
        () -> mainCheck(() -> AgentRegressionChecks.toolCallsCarryResults(app)));
    test("agents: tool argument summaries stay short",
        () -> mainCheck(AgentRegressionChecks::toolArgumentSummaries));
    test("agents: stall watchdog ends dead helpers but never live ones",
        () -> mainCheck(() -> AgentRegressionChecks.stallWatchdog(app)));
    test("agents: running chats are limited to runtime request slots",
        () -> mainCheck(() -> AgentRegressionChecks.runningLimit(app)));
    test("UI: long model name keeps selector chevron within its bounds",
        () -> UiRegressionChecks.longModelChipFits(this, current));
    final Sessions.Session[] uiChat = {null};
    main(() -> uiChat[0] = ChatHub.get(app).create("codex"));
    launch(new Intent(app, ChatActivity.class).putExtra("id", uiChat[0].id));
    main(() -> edit(current.getWindow().getDecorView()).setText("/status"));
    click("Wyślij");
    test("chat UI: long tool histories retain bounded rows and recent messages",
        () -> UiRegressionChecks.toolLogRemainsBounded(this, current));
    test("chat UI: attachment import survives recreation and gates Send",
        () -> current = UiRegressionChecks.attachmentImportSurvivesRecreate(this, current));
    test("chat UI: keyboard input sends local status without fullscreen extraction",
        () -> UiRegressionChecks.keyboardInputSendsLocalStatus(this, current));
    main(() -> ChatHub.get(app).delete(uiChat[0]));
    launch(MainActivity.class);
    test("UI: tall bottom sheet reaches its final option",
        () -> UiRegressionChecks.tallSheetScrolls(this, current));
    test("UI: switch exposes checked state to accessibility",
        () -> UiRegressionChecks.switchReportsState(this, current));
    test("UI: large font landscape keeps composer and useful home content visible", () -> {
      try {
        shell("settings put system font_scale 1.5");
        ui.setRotation(Surface.ROTATION_0);
        SystemClock.sleep(700);
        launch(MainActivity.class);
        check(current.getResources().getConfiguration().fontScale >= 1.4f, "Font scale unchanged");
        shot("13-home-large-font");
        ui.setRotation(Surface.ROTATION_90);
        SystemClock.sleep(700);
        launch(MainActivity.class);
        main(() -> {
          View send = find("Rozpocznij rozmowę");
          android.graphics.Rect visible = new android.graphics.Rect();
          check(send != null && send.getGlobalVisibleRect(visible)
              && visible.height() == send.getHeight(), "Composer action clipped in landscape");
          View heading = find("Co dziś zrobimy?");
          check(heading != null && heading.getGlobalVisibleRect(visible)
              && visible.height() == heading.getHeight(), "Home heading clipped in landscape");
          View firstAction = find("Sprawdź telefon");
          check(firstAction != null, "First device action missing");
          View card = (View) firstAction.getParent().getParent();
          check(card.getGlobalVisibleRect(visible) && visible.height() == card.getHeight(),
              "Decorative home content hides the first useful action in landscape");
        });
        shot("14-home-large-font-landscape");
      } finally {
        shell("settings put system font_scale 1.0");
        ui.setRotation(Surface.ROTATION_0);
        SystemClock.sleep(500);
        launch(MainActivity.class);
      }
    });
    main(() -> Prefs.setBackend(app, "codex"));
  }
}
