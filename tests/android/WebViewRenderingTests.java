package dev.mateuszsury.androidagent;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

/** Exercises the production preview with a local HTTP fixture, independently of Docker. */
public class WebViewRenderingTests extends Instrumentation {
    private Context app;
    private DockerPreviewActivity page;
    private UiAutomation ui;
    private ServerSocket server;
    private File evidence;
    private String runId;
    private int failures;
    private boolean toolbarFrameVerified;
    private JSONObject toolbarBounds;
    private final JSONArray tests = new JSONArray();
    interface Check { void run() throws Exception; }

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        runId = args.getString("run_id", java.util.UUID.randomUUID().toString());
        start();
    }
    @Override public void onStart() {
        app = getTargetContext();
        ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        evidence = new File(app.getExternalFilesDir(null), "webview-evidence");
        evidence.mkdirs();
        try {
            startServer();
            test("WebView: production preview loads a local page", () -> { open(); ready(); });
            test("WebView: JavaScript handles a DOM button", () -> {
                check("\"clicked\"".equals(js("document.getElementById('check').click(); document.getElementById('result').textContent")), "DOM interaction failed");
            });
            test("WebView: PNG decoding and canvas pixels", () -> {
                check("true".equals(js("(function(){var i=document.getElementById('image');var c=document.createElement('canvas');c.width=c.height=16;c.getContext('2d').drawImage(i,0,0);return i.complete&&i.naturalWidth===16&&c.getContext('2d').getImageData(0,0,16,16).data.some(function(x){return x!==0})})()")), "PNG did not decode");
            });
            test("WebView: refresh retains origin storage", () -> {
                js("localStorage.setItem('androidagent-webview-test'," + JSONObject.quote(runId) + ")");
                click("Odśwież"); ready();
                check(JSONObject.quote(runId).equals(js("localStorage.getItem('androidagent-webview-test')")), "Storage lost on refresh");
            });
            test("WebView: activity recreation preserves a working preview", () -> {
                ActivityMonitor monitor = addMonitor(DockerPreviewActivity.class.getName(), null, false);
                try {
                    runOnMainSync(() -> page.recreate());
                    Activity next = waitForMonitorWithTimeout(monitor, 30000);
                    check(next instanceof DockerPreviewActivity, "Preview was not recreated");
                    page = (DockerPreviewActivity) next; ready();
                    check(JSONObject.quote(runId).equals(js("localStorage.getItem('androidagent-webview-test')")), "Storage lost after recreation");
                } finally { removeMonitor(monitor); }
            });
            test("WebView: unavailable server shows the error page", () -> {
                stopServer();
                runOnMainSync(() -> page.web.loadUrl("http://127.0.0.1:" + DockerVm.PREVIEW_PORT + "/missing"));
                await("document.body&&document.body.textContent.indexOf('Aplikacja jeszcze nie odpowiada')>=0", "Missing error page");
            });
            test("WebView: refresh recovers after the server returns", () -> { startServer(); click("Odśwież"); ready(); });
            test("WebView: close and reopen preserve usable state", () -> {
                js("localStorage.removeItem('androidagent-webview-test')");
                runOnMainSync(() -> page.finish()); waitForIdleSync(); open(); ready();
                Bitmap bitmap = headerFrame();
                try (OutputStream out = new FileOutputStream(new File(evidence, "preview.png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
                finally { bitmap.recycle(); }
            });
        } catch (Throwable e) { record("suite", false, e.toString()); }
        finally {
            stopServer();
            if (page != null) runOnMainSync(() -> page.finish());
        }
        try {
            JSONObject report = new JSONObject().put("version", app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName)
                .put("api", Build.VERSION.SDK_INT).put("run_id", runId).put("failures", failures)
                .put("native_toolbar_frame_verified", toolbarFrameVerified)
                .put("native_toolbar_bounds", toolbarBounds)
                .put("fixture", "Local HTTP server; production DockerPreviewActivity; no model or Docker engine")
                .put("tests", tests);
            android.content.pm.PackageInfo provider = android.webkit.WebView.getCurrentWebViewPackage();
            if (provider != null) report.put("provider", new JSONObject().put("package", provider.packageName).put("version", provider.versionName).put("version_code", provider.getLongVersionCode()));
            try (OutputStream out = new FileOutputStream(new File(evidence, "results.json"))) { out.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
            for (String name : new String[]{"results.json", "preview.png"}) {
                File file = new File(evidence, name); if (!file.exists()) continue;
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, name.endsWith("png") ? "image/png" : "application/json");
                values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/androidagent-webview-tests");
                android.net.Uri uri = app.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                try (InputStream in = new FileInputStream(file); OutputStream out = app.getContentResolver().openOutputStream(uri)) {
                    byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
            }
        } catch (Exception e) { record("evidence", false, e.toString()); }
        Bundle out = new Bundle(); out.putString("stream", "WebView: " + tests.length() + " checks, " + failures + " failures\n");
        finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, out);
    }
    private void open() { page = (DockerPreviewActivity) startActivitySync(new Intent(app, DockerPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)); }
    private void ready() throws Exception { await("document.title==='ANDROIDAGENT_WEBVIEW_READY'&&document.getElementById('image').complete", "Page did not become ready"); }
    private void await(String expression, String message) throws Exception {
        long until = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < until) { if ("true".equals(js(expression))) return; SystemClock.sleep(250); }
        throw new AssertionError(message);
    }
    private String js(String code) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        runOnMainSync(() -> page.web.evaluateJavascript(code, result::complete));
        return result.get(15, TimeUnit.SECONDS);
    }
    private void click(String label) { runOnMainSync(() -> { View button = find(page.getWindow().getDecorView(), label); check(button != null, "Missing button " + label); button.performClick(); }); }
    private View find(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int n = 0; n < ((ViewGroup) view).getChildCount(); n++) { View found = find(((ViewGroup) view).getChildAt(n), label); if (found != null) return found; }
        return null;
    }
    private Bitmap headerFrame() throws Exception {
        // A WebView surface can display its page before the parent window has
        // painted the native toolbar. Validate the actual frame, not just DOM
        // readiness or the presence of a View in the hierarchy.
        Rect back = new Rect(), refresh = new Rect(), title = new Rect();
        runOnMainSync(() -> {
            View b = find(page.getWindow().getDecorView(), "Wróć");
            View r = find(page.getWindow().getDecorView(), "Odśwież");
            View t = find(page.getWindow().getDecorView(), "Podgląd aplikacji");
            check(b != null && r != null && t != null, "Missing native preview controls");
            check(b.getGlobalVisibleRect(back) && r.getGlobalVisibleRect(refresh) && t.getGlobalVisibleRect(title), "Native preview controls are hidden");
            check(back.width() == b.getWidth() && back.height() == b.getHeight()
                && refresh.width() == r.getWidth() && refresh.height() == r.getHeight()
                && title.width() == t.getWidth() && title.height() == t.getHeight(), "Native preview controls are clipped");
            int[] location = new int[2];
            b.getLocationOnScreen(location); back.offsetTo(location[0], location[1]);
            r.getLocationOnScreen(location); refresh.offsetTo(location[0], location[1]);
            t.getLocationOnScreen(location); title.offsetTo(location[0], location[1]);
        });
        toolbarBounds = new JSONObject().put("back", rect(back)).put("refresh", rect(refresh)).put("title", rect(title));
        long until = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < until) {
            Bitmap frame = ui.takeScreenshot();
            if (frame != null) {
                if (ink(frame, back) && ink(frame, refresh) && ink(frame, title)) {
                    toolbarFrameVerified = true;
                    return frame;
                }
                frame.recycle();
            }
            SystemClock.sleep(250);
        }
        Bitmap failure = ui.takeScreenshot();
        if (failure != null) {
            try (OutputStream out = new FileOutputStream(new File(evidence, "preview.png"))) { failure.compress(Bitmap.CompressFormat.PNG, 100, out); }
            finally { failure.recycle(); }
        }
        throw new AssertionError("Native toolbar did not paint in the actual preview frame: " + toolbarBounds);
    }
    private JSONArray rect(Rect box) { return new JSONArray().put(box.left).put(box.top).put(box.right).put(box.bottom); }
    private boolean ink(Bitmap frame, Rect box) {
        // Measure variation within each control. A flat white frame must fail
        // regardless of the current palette.
        int darkest = 255, lightest = 0, darkPixels = 0, lightPixels = 0;
        for (int y = Math.max(0, box.top); y < Math.min(frame.getHeight(), box.bottom); y++)
            for (int x = Math.max(0, box.left); x < Math.min(frame.getWidth(), box.right); x++) {
                int p = frame.getPixel(x, y);
                int brightness = (android.graphics.Color.red(p) + android.graphics.Color.green(p) + android.graphics.Color.blue(p)) / 3;
                darkest = Math.min(darkest, brightness); lightest = Math.max(lightest, brightness);
                if (brightness < 100) darkPixels++;
                if (brightness > 180) lightPixels++;
            }
        return lightest - darkest > 100 && darkPixels > 20 && lightPixels > 20;
    }
    private void test(String name, Check check) { try { check.run(); record(name, true, ""); } catch (Throwable e) { record(name, false, e.toString()); } }
    private void record(String name, boolean pass, String detail) {
        if (!pass) failures++;
        try { tests.put(new JSONObject().put("name", name).put("pass", pass).put("detail", detail)); } catch (JSONException ignored) {}
        Bundle b = new Bundle(); b.putString("stream", (pass ? "PASS " : "FAIL ") + name + (pass ? "" : " " + detail) + "\n"); sendStatus(0, b);
    }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private void startServer() throws IOException {
        final ServerSocket listener = new ServerSocket(); listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress("127.0.0.1", DockerVm.PREVIEW_PORT)); server = listener;
        Thread thread = new Thread(() -> {
            while (!listener.isClosed()) try (Socket socket = listener.accept()) {
                socket.setSoTimeout(10000);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String line; do { line = in.readLine(); } while (line != null && !line.isEmpty());
                byte[] body = ("<!doctype html><meta name='viewport' content='width=device-width'><title>ANDROIDAGENT_WEBVIEW_READY</title>"
                    + "<body style='font:20px sans-serif;padding:24px;background:#fafafa;color:#171717'><h1>Podgląd działa</h1><p>Androidagent · test WebView</p>"
                    + "<button id='check' onclick=\"document.getElementById('result').textContent='clicked'\">Sprawdź interakcję</button><p id='result'>gotowy</p>"
                    + "<img id='image' src='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAHUlEQVR4nGMUWRD1n4ECwESJ5lEDRg0YNWAwGQAAk/oCLU4oGjEAAAAASUVORK5CYII='>").getBytes(StandardCharsets.UTF_8);
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
            } catch (IOException ignored) { if (listener.isClosed()) return; }
        }, "webview-http-fixture"); thread.setDaemon(true); thread.start();
    }
    private void stopServer() { if (server != null) { try { server.close(); } catch (IOException ignored) {} server = null; } }
}
