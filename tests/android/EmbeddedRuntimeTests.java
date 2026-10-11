package dev.mateuszsury.androidagent;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.system.Os;
import android.system.OsConstants;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real release-UID execution checks. No external Termux, mocked CLI, or model sign-in. */
public final class EmbeddedRuntimeTests extends Instrumentation {
    interface Check { void run() throws Exception; }
    private Context app;
    private String phase, runId, archiveOrigin;
    private boolean migration;
    private int failures;
    private long startedWall, startedElapsed;
    private File evidence;
    private UiAutomation ui;
    private final JSONArray tests = new JSONArray();
    private final JSONObject versions = new JSONObject();
    private static final String FIXTURE_PREFS = "embedded_upgrade_fixture";
    private static final String FIXTURE_TEXT = "androidagent 0.4.2 history survives the update";

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        phase = args == null ? "verify" : args.getString("phase", "verify");
        runId = args == null ? UUID.randomUUID().toString() : args.getString("run_id", UUID.randomUUID().toString());
        migration = args != null && "true".equals(args.getString("migration"));
        archiveOrigin = args == null ? null : args.getString("archive_origin");
        start();
    }

    @Override public void onStart() {
        app = getTargetContext();
        startedWall = System.currentTimeMillis();
        startedElapsed = SystemClock.elapsedRealtime();
        ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        evidence = new File(app.getExternalFilesDir(null), "embedded-runtime-evidence");
        evidence.mkdirs();
        try {
            if ("seed".equals(phase)) seed();
            else if ("verify".equals(phase)) verify();
            else throw new IllegalArgumentException("Unknown test phase");
        } catch (Throwable failure) { record("suite", false, failure.toString(), 0); }
        try {
            android.content.pm.PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            JSONObject report = new JSONObject().put("run_id", runId).put("phase", phase)
                .put("version", info.versionName).put("version_code", info.getLongVersionCode())
                .put("api", Build.VERSION.SDK_INT).put("model", Build.MODEL)
                .put("supported_abis", new JSONArray(Build.SUPPORTED_ABIS)).put("process_uid", Process.myUid())
                .put("page_size_bytes", Os.sysconf(OsConstants._SC_PAGESIZE))
                .put("target_sdk", app.getApplicationInfo().targetSdkVersion)
                .put("debuggable", (app.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                .put("native_library_dir", app.getApplicationInfo().nativeLibraryDir)
                .put("started_epoch_ms", startedWall).put("completed_epoch_ms", System.currentTimeMillis())
                .put("elapsed_ms", SystemClock.elapsedRealtime() - startedElapsed)
                .put("migration_from_0_4_2", migration).put("versions", versions)
                .put("client_installation_source", archiveOrigin == null ? "SHA256-verified official archives in instrumentation APK; Android HTTPS download is not exercised" : "SHA256-verified official archives transferred through local adb reverse fixture; Android HTTPS download is not exercised")
                .put("fixture", "Production embedded runtime under app UID; no external Termux, no account login, no model conversation")
                .put("failures", failures).put("tests", tests);
            write(new File(evidence, "results.json"), report.toString(2));
            exportEvidence();
        } catch (Throwable failure) { record("evidence", false, failure.toString(), 0); }
        Bundle result = new Bundle();
        result.putString("stream", "Embedded runtime " + phase + ": " + tests.length() + " checks, " + failures + " failures\n");
        result.putInt("failures", failures);
        finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    /** Runs against the unchanged 0.4.2 product; this path never resolves EmbeddedRuntime. */
    private void seed() {
        test("Upgrade fixture is created in actual 0.4.2 data", () -> {
            check("0.4.2".equals(app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName), "Seed requires exact old version");
            Sessions sessions = new Sessions(app);
            Sessions.Session chat = sessions.create("codex");
            sessions.add(chat, Bubble.Kind.USER, FIXTURE_TEXT);
            sessions.add(chat, Bubble.Kind.BUDDY, "Persisted fixture, not a model response");
            File document = new File(app.getFilesDir(), "upgrade-retained-document.txt");
            write(document, FIXTURE_TEXT);
            check(app.getSharedPreferences(FIXTURE_PREFS, Context.MODE_PRIVATE).edit()
                .putString("chat", chat.id).putString("document", document.getAbsolutePath()).putString("text", FIXTURE_TEXT).commit(), "Fixture preferences were not committed");
            // Sessions uses apply(); a commit on its same SharedPreferences waits for queued writes.
            check(app.getSharedPreferences("buddy_sessions", Context.MODE_PRIVATE).edit().commit(), "Conversation persistence did not complete");
            check(read(document).equals(FIXTURE_TEXT), "Fixture file differs");
        });
    }

    private void verify() {
        test("Release app has no dependency on com.termux", () -> {
            try { app.getPackageManager().getPackageInfo("com.termux", 0); throw new AssertionError("External Termux must be absent"); }
            catch (android.content.pm.PackageManager.NameNotFoundException expected) { }
            check(app.getApplicationInfo().targetSdkVersion == 36, "Expected target SDK 36 exec constraints");
            check((app.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0, "Must exercise non-debuggable release UID");
        });
        if (migration) test("Update preserves 0.4.2 conversation and files before bootstrap", this::checkUpgrade);
        final EmbeddedRuntime runtime = EmbeddedRuntime.get(app);
        test("Bundled terminal bootstrap initializes without external service", () -> {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try { worker.submit(() -> { runtime.initializeBlocking(); return null; }).get(240, TimeUnit.SECONDS); }
            finally { worker.shutdownNow(); }
            check(runtime.ready(), "Runtime not ready: " + runtime.error());
            check(!runtime.busy(), "Initialization is still busy");
            versions.put("runtime", runtime.diagnostics());
        });
        if (!runtime.ready()) return;
        test("Runtime executes actual bash and child binaries under Android app UID", () -> {
            Result result = command(runtime, 30000, "bash", "-lc", "bash --version; id; printf 'HOME=%s\\nPREFIX=%s\\n' \"$HOME\" \"$PREFIX\"");
            check(result.code == 0 && result.out.contains("GNU bash"), result.summary());
            check(result.out.contains("uid=" + Process.myUid()), "Child UID differs: " + result.out);
            check(result.out.contains("HOME="), "Missing HOME");
            versions.put("bash", firstLine(result.out));
        });
        for (String tool : new String[]{"node", "python", "curl", "jq", "git"}) {
            test("Bundled " + tool + " executes its real version command", () -> {
                Result result = command(runtime, 60000, tool, "--version");
                check(result.code == 0 && !result.out.trim().isEmpty(), result.summary());
                versions.put(tool, firstLine(result.out));
            });
        }
        test("Linux shebang paths /bin/bash and /usr/bin/env execute bundled tools", () -> {
            Result result = command(runtime, 60000, "/bin/bash", "-lc", "/usr/bin/env node --version");
            check(result.code == 0 && result.out.trim().equals(versions.getString("node")), result.summary());
        });
        test("PTY provides terminal stdin/stdout, exact quoted argv, and child exit status", () -> {
            String input = "stdin with ' $ symbols";
            String argument = "space ' quote \" dollar $ parentheses () semicolon ;";
            Result result = execute(runtime.interactiveCommand("/bin/bash", "--noprofile", "--norc", "-c",
                "test -t 0 && test -t 1 || exit 99; read -r line; printf 'PTY_INPUT:%s\\n' \"$line\"; printf 'PTY_ARG:%s\\n' \"$1\"; exit 7", "embedded-pty", argument), 60000, "PTY", input + "\n");
            check(result.code == 7, result.summary());
            String normalized = result.out.replace("\r\n", "\n");
            check(normalized.contains("PTY_INPUT:" + input + "\n") && normalized.contains("PTY_ARG:" + argument + "\n"), result.summary());
            check(!normalized.startsWith(input + "\n"), "PTY echoed potentially sensitive user input");
        });
        test("A terminal acquires a lasting foreground service when a temporary host is already listening", () -> {
            if (Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(app.getPackageName(), android.Manifest.permission.POST_NOTIFICATIONS);
            Object temporaryOwner = new Object();
            McpHost host = McpHost.get(app);
            host.acquire(temporaryOwner);
            TerminalActivity terminal = null;
            try {
                app.stopService(new Intent(app, AgentService.class));
                long until = SystemClock.elapsedRealtime() + 15000;
                while (SystemClock.elapsedRealtime() < until && (AgentService.active || !host.running())) SystemClock.sleep(50);
                check(!AgentService.active && host.running(), "Temporary listening host fixture was not established");
                terminal = (TerminalActivity) startActivitySync(new Intent(app, TerminalActivity.class).putExtra("mode", "shell").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                awaitView(terminal, "Wyślij", 60000, true);
                until = SystemClock.elapsedRealtime() + 15000;
                while (SystemClock.elapsedRealtime() < until && !agentForeground()) SystemClock.sleep(50);
                check(AgentService.active && agentForeground(), "Existing socket skipped the lasting AgentService foreground owner");
                host.release(temporaryOwner);
                check(host.running(), "Tool listener stopped when temporary setup owner was released");
                TerminalActivity opened = terminal;
                String marker = "FGS_HOST_SURVIVED_" + runId;
                runOnMainSync(() -> {
                    EditText input = (EditText) findInput(opened.getWindow().getDecorView());
                    check(input != null, "Terminal command input missing");
                    input.setText("printf '" + marker + "\\n'; exit 0");
                    findLabel(opened.getWindow().getDecorView(), "Wyślij").performClick();
                });
                awaitText(terminal, marker, 60000);
                awaitText(terminal, "[exit 0]", 60000);
            } finally {
                host.release(temporaryOwner);
                if (terminal != null) { TerminalActivity opened = terminal; runOnMainSync(opened::finish); waitForIdleSync(); }
            }
        });
        test("Terminal UI starts a real shell and sends stdin after onResume", () -> {
            if (Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(app.getPackageName(), android.Manifest.permission.POST_NOTIFICATIONS);
            TerminalActivity terminal = (TerminalActivity) startActivitySync(new Intent(app, TerminalActivity.class).putExtra("mode", "shell").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            try {
                awaitView(terminal, "Wyślij", 60000, true);
                String marker = "UI_TERMINAL_" + runId;
                runOnMainSync(() -> {
                    EditText input = (EditText) findInput(terminal.getWindow().getDecorView());
                    check(input != null, "Terminal command input missing");
                    input.setText("printf '" + marker + "\\n'; exit 0");
                    View send = findLabel(terminal.getWindow().getDecorView(), "Wyślij");
                    check(send != null && send.isEnabled(), "Terminal did not become interactive");
                    send.performClick();
                });
                awaitText(terminal, marker, 60000);
                awaitText(terminal, "[exit 0]", 60000);
                screenshot("terminal.png");
            } finally { runOnMainSync(terminal::finish); waitForIdleSync(); }
        });
        test("Node writes HOME file and invokes a nested process with exact bytes", () -> {
            String marker = "embedded-runtime-" + runId;
            Result result = command(runtime, 60000, "node", "-e",
                "const fs=require('fs'),cp=require('child_process');const p=process.env.HOME+'/embedded-test.txt';"
                + "fs.writeFileSync(p," + JSONObject.quote(marker) + ");"
                + "const r=cp.spawnSync('bash',['-lc','cat \"$HOME/embedded-test.txt\"'],{encoding:'utf8'});"
                + "if(r.status!==0||r.stdout!==" + JSONObject.quote(marker) + ")throw Error(JSON.stringify(r));process.stdout.write(r.stdout);");
            check(result.code == 0 && marker.equals(result.out), result.summary());
            check(new File(runtime.home(), "embedded-test.txt").isFile(), "Node HOME file is not in app-owned home");
        });
        test("Python starts a real child process and reads its exit status", () -> {
            Result result = command(runtime, 60000, "python", "-c", "import subprocess; r=subprocess.run(['bash','-lc','printf NESTED_PYTHON; exit 7'],capture_output=True); assert r.returncode==7 and r.stdout==b'NESTED_PYTHON'; print('NESTED_OK')");
            check(result.code == 0 && result.out.contains("NESTED_OK"), result.summary());
        });
        test("Shell propagates a failing command instead of reporting success", () -> {
            Result result = command(runtime, 30000, "bash", "-lc", "false");
            check(result.code == 1, result.summary());
        });
        test("Embedded shell uses advancing host clock", () -> {
            long began = SystemClock.elapsedRealtime();
            Result result = command(runtime, 30000, "python", "-c", "import time; a=time.monotonic(); time.sleep(1); print(time.monotonic()-a)");
            check(result.code == 0, result.summary());
            double seconds = Double.parseDouble(result.out.trim());
            check(seconds >= .75 && seconds <= 20, "Unexpected guest monotonic clock: " + seconds);
            check(SystemClock.elapsedRealtime() - began >= 750, "Android clock did not advance");
        });
        test("Cancelling the PRoot process terminates its running child", () -> {
            java.lang.Process process = RuntimeProcess.start(runtime.command("python", "-u", "-c", "import os,time; print(str(os.getpid())+' '+str(os.getppid()),flush=True); time.sleep(60)"));
            ExecutorService reader = Executors.newSingleThreadExecutor();
            int child = -1;
            try {
                Future<String> first = reader.submit(() -> new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).readLine());
                String line = first.get(30, TimeUnit.SECONDS);
                check(line != null && line.matches("[0-9]+ [0-9]+"), "Child/parent PID was not emitted: " + line);
                child = Integer.parseInt(line.split(" ")[0]);
                int parent = Integer.parseInt(line.split(" ")[1]);
                check(child > 1 && child != Process.myPid(), "Invalid child PID");
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
                check(!process.isAlive(), "PRoot did not stop; child=" + processState(child) + "; parent=" + processState(parent));
                long until = SystemClock.elapsedRealtime() + 10000;
                while (SystemClock.elapsedRealtime() < until && processExists(child)) SystemClock.sleep(100);
                check(!processExists(child), "Cancelled child is still running: " + child);
            } finally {
                if (process.isAlive()) process.destroyForcibly();
                if (child > 1 && processExists(child)) {
                    try { Os.kill(child, 9); } catch (android.system.ErrnoException ignored) { }
                }
                reader.shutdownNow();
            }
        });
        test("Cancelling one native branch preserves an unrelated same-UID process", () -> {
            java.lang.Process neighbor = RuntimeProcess.start(runtime.command("python", "-u", "-c", "import sys; print('READY',flush=True); x=sys.stdin.readline().strip(); print('SURVIVED:'+x,flush=True)"));
            java.lang.Process victim = RuntimeProcess.start(runtime.command("python", "-u", "-c", "import os,time; print(os.getpid(),flush=True); time.sleep(60)"));
            ExecutorService reader = Executors.newSingleThreadExecutor();
            BufferedReader neighborOutput = new BufferedReader(new InputStreamReader(neighbor.getInputStream(), StandardCharsets.UTF_8));
            int child = -1;
            try {
                check("READY".equals(reader.submit(neighborOutput::readLine).get(30, TimeUnit.SECONDS)), "Neighbor process did not start");
                String pid = reader.submit(() -> new BufferedReader(new InputStreamReader(victim.getInputStream(), StandardCharsets.UTF_8)).readLine()).get(30, TimeUnit.SECONDS);
                check(pid != null && pid.matches("[0-9]+"), "Victim child PID missing");
                child = Integer.parseInt(pid);
                check(child > 1 && child != Process.myPid(), "Invalid victim child PID");
                victim.destroyForcibly();
                check(victim.waitFor(10, TimeUnit.SECONDS), "Owned branch did not stop");
                check(neighbor.isAlive(), "Stop affected an unrelated process under the same app UID");
                neighbor.getOutputStream().write("probe\n".getBytes(StandardCharsets.UTF_8)); neighbor.getOutputStream().flush();
                check("SURVIVED:probe".equals(reader.submit(neighborOutput::readLine).get(30, TimeUnit.SECONDS)), "Unrelated process could not continue executing");
                check(neighbor.waitFor(10, TimeUnit.SECONDS) && neighbor.exitValue() == 0, "Unrelated process did not exit normally");
            } finally {
                if (victim.isAlive()) victim.destroyForcibly();
                if (child > 1 && processExists(child)) { try { Os.kill(child, 9); } catch (android.system.ErrnoException ignored) { } }
                if (neighbor.isAlive()) {
                    try { neighbor.getOutputStream().write("cleanup\n".getBytes(StandardCharsets.UTF_8)); neighbor.getOutputStream().flush(); } catch (IOException ignored) { }
                    neighbor.destroyForcibly();
                }
                reader.shutdownNow();
            }
        });
        test("Bootstrap repeated initialization preserves HOME and stays ready", () -> {
            File file = new File(runtime.home(), "embedded-test.txt");
            String before = read(file);
            runtime.initializeBlocking();
            check(runtime.ready() && read(file).equals(before), "Repeated initialization replaced user HOME");
        });
        if (migration) test("Bootstrap preserves pre-update conversations and files", this::checkUpgrade);
        test("Client installation rejects an archive with incorrect bytes and SHA256", () -> {
            File archive = new File(app.getCacheDir(), "bad-client.tgz");
            write(archive, "Incorrect archive test fixture");
            boolean rejected = false;
            try { runtime.installVerifiedClientArchive("codex", archive); }
            catch (IOException expected) { rejected = true; }
            finally { archive.delete(); }
            check(rejected, "Corrupt client archive was accepted");
        });
        for (String agent : new String[]{"codex", "claude"}) {
            test("Official " + agent + " client installs and executes without sign-in", () -> {
                File archive = copyClientArchive(runtime, agent);
                ExecutorService worker = Executors.newSingleThreadExecutor();
                try { worker.submit(() -> { runtime.installVerifiedClientArchive(agent, archive); return null; }).get(240, TimeUnit.SECONDS); }
                finally { worker.shutdownNow(); archive.delete(); }
                check(runtime.agentInstalled(agent), "Client installation is not ready: " + runtime.error());
                Result result = command(runtime, 90000, agent, "--version");
                check(result.code == 0 && !result.out.trim().isEmpty(), result.summary());
                check(result.out.toLowerCase().contains(agent) || "claude".equals(agent) && result.out.toLowerCase().contains("claude code"), "Unexpected official CLI output: " + result.out);
                versions.put(agent, firstLine(result.out));
            });
        }
        test("Retry repairs a missing client symlink using the verified local installation", () -> {
            File link = new File(runtime.home(), ".androidagent/clients/codex");
            java.nio.file.Files.delete(link.toPath());
            check(!runtime.agentInstalled("codex"), "Client with missing launch symlink is incorrectly reported ready");
            runtime.installClientBlocking("codex");
            check(runtime.agentInstalled("codex"), "Verified client symlink was not repaired");
            Result result = command(runtime, 90000, "codex", "--version");
            check(result.code == 0 && firstLine(result.out).equals(versions.getString("codex")), result.summary());
        });
        test("Official Codex account query reports signed-out state without logging in", () -> {
            check(runtime.agentInstalled("codex"), "Codex is not installed");
            Result result = command(runtime, 90000, "codex", "login", "status");
            check(result.code != 0 && result.out.toLowerCase().contains("not logged in"), "Unexpected account result: " + result.summary());
        });
        test("Official Claude account query reports signed-out state without logging in", () -> {
            check(runtime.agentInstalled("claude"), "Claude is not installed");
            Result result = command(runtime, 90000, "claude", "auth", "status");
            JSONObject account = new JSONObject(result.out.trim());
            check(!account.optBoolean("loggedIn", true), "Client unexpectedly authenticated or output missing loggedIn");
        });
        test("Official Codex exposes device login separately from setup", () -> {
            Result result = command(runtime, 90000, "codex", "login", "--help");
            check(result.code == 0 && result.out.contains("--device-auth"), result.summary());
        });
        test("Official Claude exposes account login separately from setup", () -> {
            Result result = command(runtime, 90000, "claude", "auth", "login", "--help");
            check(result.code == 0 && result.out.toLowerCase().contains("login"), result.summary());
            versions.put("final_runtime", runtime.diagnostics());
        });
        test("Runtime UI and real helper callback show installed clients separately from account login", () -> {
            long before = Prefs.helperCheckedAt(app);
            RuntimeActivity settings = (RuntimeActivity) startActivitySync(new Intent(app, RuntimeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            try {
                // The verified-archive seam executes the real installer without
                // the service's final UI state. Exercise that production worker
                // from its button as well; verified clients need no download.
                awaitView(settings, "Przygotuj wybranych agentów", 15000, true);
                runOnMainSync(() -> findLabel(settings.getWindow().getDecorView(), "Przygotuj wybranych agentów").performClick());
                awaitView(settings, "Wbudowany terminal gotowy", 30000, false);
                long until = SystemClock.elapsedRealtime() + 90000;
                while (SystemClock.elapsedRealtime() < until && Prefs.helperCheckedAt(app) <= before) SystemClock.sleep(250);
                check(Prefs.helperCheckedAt(app) > before, "Genuine helper ping did not reach the production callback server");
                String installed = Prefs.helperStatus(app);
                check(installed != null && installed.contains("codex") && installed.contains("claude"), "Callback did not report both actual clients: " + installed);
                check("none".equals(Prefs.signedAgents(app)), "Setup claimed an account was signed in");
                awaitView(settings, "Zaloguj Codex", 15000, true);
                awaitView(settings, "Zaloguj Claude Code", 15000, true);
                screenshot("runtime.png");
            } finally { runOnMainSync(settings::finish); waitForIdleSync(); }
        });
    }

    private void checkUpgrade() throws Exception {
        android.content.SharedPreferences fixture = app.getSharedPreferences(FIXTURE_PREFS, Context.MODE_PRIVATE);
        String id = fixture.getString("chat", "");
        check(!id.isEmpty(), "Missing old installation fixture");
        Sessions.Session chat = new Sessions(app).find(id);
        check(chat != null && chat.messages.size() == 2, "Old conversation missing or altered");
        check(FIXTURE_TEXT.equals(chat.messages.get(0).text), "Old message bytes differ");
        check(FIXTURE_TEXT.equals(read(new File(fixture.getString("document", "")))), "Old document bytes differ");
    }

    private static final class Result {
        final int code; final String out;
        Result(int code, String out) { this.code = code; this.out = out; }
        String summary() { return "exit=" + code + "; output=" + out; }
    }
    private File copyClientArchive(EmbeddedRuntime runtime, String agent) throws Exception {
        File archive = new File(app.getCacheDir(), agent + "-official-test.tgz");
        String abi = runtime.diagnostics().getString("abi");
        InputStream input;
        if (archiveOrigin == null) input = getContext().getAssets().open("embedded-client-archives/" + agent + "-" + abi + ".tgz");
        else {
            java.net.URL origin = new java.net.URL(archiveOrigin);
            check("http".equals(origin.getProtocol()) && "127.0.0.1".equals(origin.getHost()) && (origin.getPort() == 18787 || origin.getPort() == 18788), "Test archive fixture must be localhost:18787 or localhost:18788");
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(archiveOrigin + "/" + agent + "-" + abi + ".tgz").openConnection();
            connection.setConnectTimeout(10000); connection.setReadTimeout(30000);
            check(connection.getResponseCode() == 200, "Local client archive transfer failed");
            input = connection.getInputStream();
        }
        try (InputStream in = input; OutputStream out = new FileOutputStream(archive)) {
            byte[] bytes = new byte[65536]; int n;
            while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
        }
        return archive;
    }
    private static boolean processExists(int pid) {
        try { Os.kill(pid, 0); return true; }
        catch (android.system.ErrnoException error) { return error.errno != OsConstants.ESRCH; }
    }
    private static String processState(int pid) {
        try { return read(new File("/proc/" + pid + "/stat")).trim(); }
        catch (IOException error) { return "pid=" + pid + ", exists=" + processExists(pid) + ", " + error; }
    }
    private boolean agentForeground() {
        android.app.ActivityManager manager = app.getSystemService(android.app.ActivityManager.class);
        for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(100))
            if (AgentService.class.getName().equals(service.service.getClassName())) return service.foreground;
        return false;
    }
    private View findInput(View view) {
        if (view instanceof EditText) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            View found = findInput(((ViewGroup)view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private View findLabel(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView)view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            View found = findLabel(((ViewGroup)view).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private boolean containsText(View view, String text) {
        if (view instanceof TextView && ((TextView)view).getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++)
            if (containsText(((ViewGroup)view).getChildAt(i), text)) return true;
        return false;
    }
    private void awaitView(Activity activity, String label, long timeout, boolean enabled) {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < until) {
            boolean[] found = {false};
            runOnMainSync(() -> { View view = findLabel(activity.getWindow().getDecorView(), label); found[0] = view != null && (!enabled || view.isEnabled()); });
            if (found[0]) return;
            SystemClock.sleep(250);
        }
        throw new AssertionError("UI control was not ready: " + label);
    }
    private void awaitText(Activity activity, String text, long timeout) {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < until) {
            boolean[] found = {false};
            runOnMainSync(() -> found[0] = containsText(activity.getWindow().getDecorView(), text));
            if (found[0]) return;
            SystemClock.sleep(250);
        }
        throw new AssertionError("Terminal did not emit expected text: " + text);
    }
    private void screenshot(String name) throws IOException {
        Bitmap frame = ui.takeScreenshot();
        check(frame != null, "Screenshot is unavailable");
        try (OutputStream out = new FileOutputStream(new File(evidence, name))) { frame.compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { frame.recycle(); }
    }
    private Result command(EmbeddedRuntime runtime, long timeout, String... args) throws Exception {
        return execute(runtime.command(args), timeout, args[0], null);
    }
    private Result execute(ProcessBuilder builder, long timeout, String label, String stdin) throws Exception {
        java.lang.Process process = RuntimeProcess.start(builder.redirectErrorStream(true));
        if (stdin != null) { process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8)); process.getOutputStream().flush(); }
        ExecutorService reader = Executors.newSingleThreadExecutor();
        Future<String> output = reader.submit(() -> {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] bytes = new byte[8192]; int n;
                while ((n = in.read(bytes)) != -1) {
                    if (buffer.size() + n > 262144) throw new IOException("Unexpectedly large command output");
                    buffer.write(bytes, 0, n);
                }
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        });
        try {
            if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) throw new IOException("Timed out: " + label);
            return new Result(process.exitValue(), output.get(10, TimeUnit.SECONDS));
        } finally {
            if (process.isAlive()) { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
            reader.shutdownNow();
        }
    }
    private void test(String name, Check check) {
        long began = SystemClock.elapsedRealtime();
        try { check.run(); record(name, true, "", SystemClock.elapsedRealtime() - began); }
        catch (Throwable failure) { record(name, false, failure.toString(), SystemClock.elapsedRealtime() - began); }
    }
    private void record(String name, boolean pass, String error, long ms) {
        if (!pass) failures++;
        try { tests.put(new JSONObject().put("name", name).put("pass", pass).put("error", error).put("elapsed_ms", ms)); }
        catch (Exception ignored) { }
        Bundle status = new Bundle(); status.putString("stream", (pass ? "PASS " : "FAIL ") + name + (error.isEmpty() ? "" : ": " + error) + "\n");
        sendStatus(pass ? 0 : -1, status);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static String firstLine(String value) { return value.split("\\r?\\n", 2)[0]; }
    private static void write(File file, String value) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) { out.write(value.getBytes(StandardCharsets.UTF_8)); }
    }
    private static String read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192]; int n;
            while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private void exportEvidence() throws Exception {
        String relative = "Download/androidagent-embedded-tests/";
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        for (String name : new String[]{"results.json", "terminal.png", "runtime.png"}) {
        File report = new File(evidence, name); if (!report.isFile()) continue;
        app.getContentResolver().delete(collection, "_display_name = ? AND relative_path = ?", new String[]{name, relative});
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, name.endsWith("png") ? "image/png" : "application/json");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri destination = app.getContentResolver().insert(collection, values);
        if (destination == null) throw new IOException("Cannot publish report");
        try (InputStream in = new FileInputStream(report); OutputStream out = app.getContentResolver().openOutputStream(destination)) {
            if (out == null) throw new IOException("Cannot open report destination");
            byte[] bytes = new byte[8192]; int n; while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
        }
        values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        app.getContentResolver().update(destination, values, null, null);
        }
    }
}
