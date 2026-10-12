package dev.mateuszsury.androidagent;

import android.app.*;
import android.content.*;
import android.os.*;
import android.system.Os;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.*;

/** Sentinel migration checks and a separate real install-r preservation check. */
public class DockerMigrationTests extends Instrumentation {
    private Context app;
    private File suite;
    private String runId, updatePhase;
    private int failures;
    private long started;
    private final JSONArray tests = new JSONArray();
    private final JSONObject observations = new JSONObject();
    interface Check { void run() throws Exception; }
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); runId = args.getString("run_id", java.util.UUID.randomUUID().toString()); updatePhase = args.getString("update_phase", "migration"); start();
    }
    @Override public void onStart() {
        app = getTargetContext(); started = SystemClock.elapsedRealtime();
        suite = new File(app.getCacheDir(), "docker-migration-" + runId); suite.mkdirs();
        if ("seed043".equals(updatePhase) || "verify044".equals(updatePhase)) {
            updateSuite(); finishSuite("Actual in-place 0.4.3 to 0.4.4 Android update; production HOME/history and real QCOW2 retained; no guest engine boot in this suite"); return;
        }
        if ("diagnostics044".equals(updatePhase)) {
            diagnosticsSuite(); finishSuite("Isolated production DockerDiagnostics with synthetic console fragments; no QEMU or Docker engine proof"); return;
        }
        test("Migration: 0.4.2 backing and user disk remain, initramfs updates", () -> {
            Fixture fixture = fixture("preserve");
            long inode = Os.stat(new File(fixture.vm.directory, "user.qcow2").getAbsolutePath()).st_ino;
            fixture.install(0);
            check("old-backing".equals(read(new File(fixture.vm.directory, "base.qcow2"))), "Backing was replaced");
            check("old-user-data".equals(read(new File(fixture.vm.directory, "user.qcow2"))), "User disk changed");
            check(Os.stat(new File(fixture.vm.directory, "user.qcow2").getAbsolutePath()).st_ino == inode, "User disk inode changed");
            check(fixture.current.getJSONObject("files").getString("initramfs-virt").equals(sha(new File(fixture.vm.directory, "initramfs-virt"))), "Wrong runtime initramfs");
            check(fixture.current.toString().equals(new JSONObject(read(new File(fixture.vm.directory, "installed-manifest"))).toString()), "Current manifest was not committed");
            check("retained-export".equals(read(fixture.export)), "Export changed");
        });
        test("Migration: changed backing is rejected without changing files", () -> {
            Fixture fixture = fixture("changed-base");
            fixture.previous.getJSONObject("files").put("base.qcow2.gz", "incompatible");
            write(new File(fixture.vm.directory, "installed-manifest"), fixture.previous.toString());
            try { fixture.install(0); throw new AssertionError("Incompatible backing accepted"); }
            catch (IOException expected) { check(expected.getMessage().contains("bazę dysku"), "Wrong rejection"); }
            check("old-backing".equals(read(new File(fixture.vm.directory, "base.qcow2"))), "Backing changed after rejection");
            check("old-user-data".equals(read(new File(fixture.vm.directory, "user.qcow2"))), "User disk changed after rejection");
            check("old-initramfs".equals(read(new File(fixture.vm.directory, "initramfs-virt"))), "Runtime changed after rejection");
        });
        test("Migration: unknown disk without marker is retained and rejected", () -> {
            Fixture fixture = fixture("unknown");
            check(new File(fixture.vm.directory, "installed-manifest").delete(), "Cannot remove fixture marker");
            try { fixture.install(0); throw new AssertionError("Unknown disk accepted"); }
            catch (IOException expected) { check(expected.getMessage().contains("potwierdzić wersji"), "Wrong rejection"); }
            check("old-user-data".equals(read(new File(fixture.vm.directory, "user.qcow2"))), "Unknown disk changed");
            check("retained-export".equals(read(fixture.export)), "Unknown disk export changed");
        });
        test("Migration: cancellation retains old marker and later retry succeeds", () -> {
            Fixture fixture = fixture("retry");
            Field epoch = DockerVm.class.getDeclaredField("epoch"); epoch.setAccessible(true); epoch.setInt(fixture.vm, 1);
            fixture.install(0);
            check(fixture.previous.toString().equals(new JSONObject(read(new File(fixture.vm.directory, "installed-manifest"))).toString()), "Cancelled install published new marker");
            check("old-initramfs".equals(read(new File(fixture.vm.directory, "initramfs-virt"))), "Cancelled install changed initramfs");
            epoch.setInt(fixture.vm, 0); fixture.install(0);
            check(fixture.current.getJSONObject("files").getString("initramfs-virt").equals(sha(new File(fixture.vm.directory, "initramfs-virt"))), "Retry did not install actual runtime");
            check("old-user-data".equals(read(new File(fixture.vm.directory, "user.qcow2"))), "Retry changed disk");
        });
        finishSuite("Actual DockerVm.install in isolated Android cache; sentinel backing/overlay files. Real ARM QCOW2 migration is separate.");
    }
    private void finishSuite(String scope) {
        try {
            JSONObject report = new JSONObject().put("run_id", runId).put("version", app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName)
                .put("version_code", app.getPackageManager().getPackageInfo(app.getPackageName(), 0).getLongVersionCode())
                .put("phase", updatePhase).put("api", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS[0])
                .put("process_uid", android.os.Process.myUid()).put("page_size_bytes", Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
                .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                .put("failures", failures).put("tests", tests).put("observations", observations)
                .put("fixture", scope);
            if (!"migration".equals(updatePhase)) {
                File baseline = new File(app.getFilesDir(), "update-043-baseline.json");
                if (baseline.isFile()) report.put("baseline", new JSONObject(read(baseline)));
            }
            ContentValues values = new ContentValues(); values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "results.json");
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "migration".equals(updatePhase) ? "Download/androidagent-docker-migration-tests" : "Download/androidagent-update-preservation-tests");
            android.net.Uri uri = app.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            try (OutputStream out = app.getContentResolver().openOutputStream(uri)) { out.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception error) { record("report", false, error.toString()); }
        remove(suite);
        Bundle result = new Bundle(); result.putString("stream", "Docker migration: " + tests.length() + " checks, " + failures + " failures\n");
        finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
    private void updateSuite() {
        if ("seed043".equals(updatePhase)) {
            test("Update baseline: exact installed 0.4.3 and initialized own-UID runtime", () -> {
                android.content.pm.PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                check(info.getLongVersionCode() == 403 && "0.4.3".equals(info.versionName), "Requires installed 0.4.3");
                check(EmbeddedRuntime.get(app).ready(), "Old embedded runtime must already be initialized");
                check(!DockerVm.get(app).alive(), "Stop the existing VM cleanly before this update test");
            });
            if (failures != 0) return;
            test("Update baseline: persisted conversation, HOME project and export are added without resetting data", () -> {
                Sessions sessions = new Sessions(app);
                Sessions.Session chat = new Sessions.Session(java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12), "codex");
                // add() persists a real conversation without create()'s eviction
                // policy; all existing conversations remain in the index.
                sessions.current = chat;
                sessions.add(chat, Bubble.Kind.USER, "0.4.3 update preservation fixture " + runId);
                sessions.add(chat, Bubble.Kind.BUDDY, "Persisted fixture; not a logged-in model response");
                check(app.getSharedPreferences("buddy_sessions", Context.MODE_PRIVATE).edit().commit(), "History did not reach disk");
                EmbeddedRuntime runtime = EmbeddedRuntime.get(app);
                File project = new File(runtime.home(), "projects/update-preserved-" + runId + ".txt");
                File export = new File(app.getFilesDir(), "docker-exports/update-preserved-" + runId + ".txt");
                write(project, "Retained HOME project " + runId); write(export, "Retained Android export " + runId);
                JSONObject baseline = new JSONObject().put("seed_run_id", runId).put("uid", android.os.Process.myUid())
                    .put("certificate_sha256", certificate()).put("chat_id", chat.id).put("chat_text", chat.messages.get(0).text)
                    .put("history_sha256", historyFingerprint()).put("project_path", project.getAbsolutePath())
                    .put("project", fingerprint(project)).put("export_path", export.getAbsolutePath()).put("export", fingerprint(export))
                    .put("runtime", runtime.diagnostics()).put("runtime_prefix_path", runtime.prefix().getAbsolutePath())
                    .put("runtime_prefix_inode", Os.stat(runtime.prefix().getAbsolutePath()).st_ino)
                    .put("runtime_ready", fingerprint(new File(runtime.prefix().getParentFile(), ".ready")));
                write(baselineFile(), baseline.toString(2));
            });
            if (failures != 0) return;
            test("Update baseline: actual QCOW2 overlay and disk metadata are recorded without a reset", () -> {
                DockerVm vm = DockerVm.get(app);
                Method install = DockerVm.class.getDeclaredMethod("install", int.class); install.setAccessible(true);
                Field epoch = DockerVm.class.getDeclaredField("epoch"); epoch.setAccessible(true);
                try { install.invoke(vm, epoch.getInt(vm)); }
                catch (InvocationTargetException error) { throw new IOException("Old native installation failed", error.getCause()); }
                File disk = new File(vm.directory, "user.qcow2");
                boolean existing = disk.isFile();
                if (!existing) nativeImage(vm, "create", "-f", "qcow2", "-F", "qcow2", "-b", new File(vm.directory, "base.qcow2").getAbsolutePath(), disk.getAbsolutePath());
                JSONObject info = new JSONObject(nativeImage(vm, "info", "--output=json", disk.getAbsolutePath()));
                check("qcow2".equals(info.getString("format")), "Actual overlay is not QCOW2");
                check(info.has("backing-filename"), "Actual overlay has no backing file");
                JSONObject files = new JSONObject();
                for (String name : new String[]{"user.qcow2", "base.qcow2", "installed-manifest", "vmlinuz-virt", "initramfs-virt"}) files.put(name, fingerprint(new File(vm.directory, name)));
                JSONObject baseline = new JSONObject(read(baselineFile()));
                baseline.put("disk_directory", vm.directory.getAbsolutePath()).put("disk_files", files).put("disk_info", info)
                    .put("existing_overlay_before_seed", existing).put("guest_payload_exercised", false)
                    .put("disk_scope", existing ? "Existing real overlay retained; no guest payload is opened by this test" : "New empty real overlay created by native qemu-img; no guest payload");
                write(baselineFile(), baseline.toString(2));
            });
        } else {
            final JSONObject[] old = {null};
            test("Update: 0.4.4 has the same Android UID and signing certificate", () -> {
                check(app.getPackageManager().getPackageInfo(app.getPackageName(), 0).getLongVersionCode() == 404, "Requires 0.4.4");
                old[0] = new JSONObject(read(baselineFile()));
                check(old[0].getInt("uid") == android.os.Process.myUid(), "Update changed the app UID");
                check(old[0].getString("certificate_sha256").equals(certificate()), "Update certificate differs");
            });
            if (old[0] == null) return;
            final JSONObject baseline = old[0];
            test("Update: all persisted history and the fixture conversation remain unchanged", () -> {
                check(baseline.getString("history_sha256").equals(historyFingerprint()), "Existing history bytes changed");
                Sessions.Session chat = new Sessions(app).find(baseline.getString("chat_id"));
                check(chat != null && chat.messages.size() == 2 && baseline.getString("chat_text").equals(chat.messages.get(0).text), "Old conversation changed");
            });
            test("Update: HOME project and Android export retain exact bytes and inode", () -> {
                unchanged(new File(baseline.getString("project_path")), baseline.getJSONObject("project"));
                unchanged(new File(baseline.getString("export_path")), baseline.getJSONObject("export"));
            });
            test("Update: initialized terminal prefix and ready marker are reused without bootstrap", () -> {
                EmbeddedRuntime runtime = EmbeddedRuntime.get(app);
                check(runtime.ready(), "Update requires a new terminal bootstrap");
                check(runtime.prefix().getAbsolutePath().equals(baseline.getString("runtime_prefix_path")), "Terminal prefix moved");
                check(Os.stat(runtime.prefix().getAbsolutePath()).st_ino == baseline.getLong("runtime_prefix_inode"), "Terminal prefix replaced");
                check(runtime.diagnostics().getString("runtime_sha256").equals(baseline.getJSONObject("runtime").getString("runtime_sha256")), "Termux asset revision changed");
                unchanged(new File(runtime.prefix().getParentFile(), ".ready"), baseline.getJSONObject("runtime_ready"));
            });
            test("Update: actual disk, backing, kernel and installation marker retain exact bytes/inode", () -> {
                DockerVm vm = DockerVm.get(app); check(!vm.alive(), "VM must remain stopped for exact disk comparison");
                check(vm.directory.getAbsolutePath().equals(baseline.getString("disk_directory")), "Disk directory moved");
                JSONObject files = baseline.getJSONObject("disk_files");
                for (String name : new String[]{"user.qcow2", "base.qcow2", "installed-manifest", "vmlinuz-virt", "initramfs-virt"}) unchanged(new File(vm.directory, name), files.getJSONObject(name));
                JSONObject info = new JSONObject(nativeImage(vm, "info", "--output=json", new File(vm.directory, "user.qcow2").getAbsolutePath()));
                for (String key : new String[]{"format", "virtual-size", "backing-filename", "full-backing-filename", "backing-filename-format"})
                    check(String.valueOf(info.opt(key)).equals(String.valueOf(baseline.getJSONObject("disk_info").opt(key))), "QCOW2 metadata changed: " + key);
            });
            test("Update: real shell and Node use retained HOME under the new APK", () -> {
                EmbeddedRuntime runtime = EmbeddedRuntime.get(app);
                String result = runtime.runBlocking(runtime.command("bash", "-lc", "bash --version | head -1; node --version; python --version; id"), 30);
                check(result.contains("GNU bash") && result.contains("Python") && result.contains("uid=" + android.os.Process.myUid()), "Updated native process did not execute: " + result);
                String source = "const fs=require('fs');process.stdout.write(fs.readFileSync(process.env.HOME+'/projects/" + new File(baseline.getString("project_path")).getName() + "','utf8'));";
                String document = runtime.runBlocking(runtime.command("node", "-e", source), 30);
                check(document.equals(read(new File(baseline.getString("project_path")))), "Node cannot read retained HOME bytes");
            });
            test("Update: installed official clients still execute without a download or model call", () -> {
                EmbeddedRuntime runtime = EmbeddedRuntime.get(app);
                for (String agent : new String[]{"codex", "claude"}) {
                    if (!baseline.getJSONObject("runtime").optBoolean(agent + "_installed")) continue;
                    check(runtime.agentInstalled(agent), "Cached client became unavailable: " + agent);
                    check(!runtime.runBlocking(runtime.command(agent, "--version"), 60).trim().isEmpty(), "Cached official client did not execute");
                }
            });
        }
    }
    private File baselineFile() { return new File(app.getFilesDir(), "update-043-baseline.json"); }
    private String certificate() throws Exception {
        android.content.pm.PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
        android.content.pm.Signature[] signatures = info.signingInfo.getApkContentsSigners();
        check(signatures.length == 1, "Expected one update signer"); return digest(signatures[0].toByteArray());
    }
    private String historyFingerprint() throws Exception {
        java.util.SortedMap<String,Object> values = new java.util.TreeMap<>(app.getSharedPreferences("buddy_sessions", Context.MODE_PRIVATE).getAll());
        return digest(new JSONObject(values).toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String digest(byte[] bytes) throws Exception { StringBuilder result = new StringBuilder(); for (byte item : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255)); return result.toString(); }
    private static JSONObject fingerprint(File file) throws Exception {
        android.system.StructStat stat = Os.stat(file.getAbsolutePath());
        return new JSONObject().put("bytes", file.length()).put("inode", stat.st_ino).put("mtime_seconds", stat.st_mtime).put("sha256", sha(file));
    }
    private static void unchanged(File file, JSONObject expected) throws Exception {
        JSONObject actual = fingerprint(file);
        for (String key : new String[]{"bytes", "inode", "mtime_seconds", "sha256"})
            check(String.valueOf(actual.get(key)).equals(String.valueOf(expected.get(key))), "Retained file changed: " + file.getName() + " / " + key);
    }
    private String nativeImage(DockerVm vm, String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(new File(app.getApplicationInfo().nativeLibraryDir, "libqemu_img.so").getAbsolutePath()); java.util.Collections.addAll(command, args);
        ProcessBuilder builder = new ProcessBuilder(command).directory(vm.directory).redirectErrorStream(true);
        builder.environment().put("LD_LIBRARY_PATH", app.getApplicationInfo().nativeLibraryDir);
        java.lang.Process process = builder.start();
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<String> output = worker.submit(() -> { try (InputStream in = process.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] block = new byte[4096]; int n; while ((n = in.read(block)) != -1) { if (out.size() + n > 65536) throw new IOException("Unexpected native image output size"); out.write(block, 0, n); } return new String(out.toByteArray(), StandardCharsets.UTF_8); } });
        try { check(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "Native qemu-img timed out"); String text = output.get(5, java.util.concurrent.TimeUnit.SECONDS); check(process.exitValue() == 0, "Native qemu-img failed: " + text); return text; }
        finally { if (process.isAlive()) process.destroyForcibly(); worker.shutdownNow(); }
    }
    private void diagnosticsSuite() {
        // Keep every new 0.4.4 class reference in a separate DEX class. The same
        // instrumentation must first seed the original 0.4.3 application, where
        // DockerDiagnostics does not exist.
        new DiagnosticsChecks().run();
    }
    private final class DiagnosticsChecks {
      void run() {
        final String secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        test("Diagnostics: split token, metadata and late output remain redacted after restore", () -> {
            File directory = new File(suite, "split-token");
            DockerDiagnostics log = new DockerDiagnostics(directory, secret);
            log.begin(Build.SUPPORTED_ABIS[0], true, 1024, 2);
            log.append("BEFORE " + secret.substring(0, 17));
            log.append(secret.substring(17, 39));
            log.append(secret.substring(39) + " AFTER\n");
            log.noteStartup(new JSONObject().put("stage", "fixture_error").put("detail", "Stage " + secret));
            log.snapshotGuest(new JSONObject().put("fixture", true).put("detail", "Guest " + secret));
            log.finish("startup_error", 17, "Fixture failure " + secret);
            log.append("LATE_AFTER_STOP\n");
            log.recordExit(17);
            DockerDiagnostics restored = new DockerDiagnostics(directory, secret);
            JSONObject status = restored.status();
            check("startup_error".equals(status.getString("reason")) && status.getInt("exit_code") == 17, "Reason/actual exit code did not survive restore");
            String console = read(new File(restored.currentDirectory(), "console.log"));
            check(console.contains("BEFORE") && console.contains("AFTER") && console.contains("LATE_AFTER_STOP"), "Expected console data was lost");
            check(!console.contains(secret) && !status.toString().contains(secret), "Split secret reached persisted log/status");
            for (File file : restored.currentDirectory().listFiles()) if (file.isFile()) check(!read(file).contains(secret), "Secret reached persisted diagnostic file: " + file.getName());
            DockerDiagnostics noProcess = new DockerDiagnostics(new File(suite, "no-process"), secret);
            noProcess.begin(Build.SUPPORTED_ABIS[0], true, 512, 1);
            noProcess.append("NO_PROCESS " + secret.substring(0, 19)); noProcess.finish("boot_error", null, "Fixture rejected before spawning QEMU");
            noProcess.recordNoProcess(); noProcess.append("AFTER_SEALED");
            DockerDiagnostics noProcessRestored = new DockerDiagnostics(new File(suite, "no-process"), secret);
            check("boot_error".equals(noProcessRestored.status().getString("reason")) && "no_process".equals(noProcessRestored.status().getString("phase"))
                && noProcessRestored.status().isNull("exit_code") && noProcessRestored.status().getBoolean("finished"), "No-process failure invented an exit or lost its cause");
            String noProcessConsole = read(new File(noProcessRestored.currentDirectory(), "console.log"));
            check(noProcessConsole.contains("NO_PROCESS") && !noProcessConsole.contains(secret.substring(0, 19)) && !noProcessConsole.contains("AFTER_SEALED"), "No-process EOF failed to redact/seal its stream");
            File noProcessMetadata = new File(noProcessRestored.currentDirectory(), "metadata.json");
            String originalDetail = new JSONObject(read(noProcessMetadata)).getString("detail");
            noProcessRestored.finish("emergency_stop", null, "New stop after read-only restore");
            JSONObject immutable = new JSONObject(read(noProcessMetadata));
            check("no_process".equals(noProcessRestored.status().getString("phase")) && "boot_error".equals(immutable.getString("reason"))
                && "no_process".equals(immutable.getString("phase")) && originalDetail.equals(immutable.getString("detail")) && immutable.isNull("exit_code"), "Read-only restore overwrote the no-process phase, detail or cause");
        });
        test("Diagnostics: UTF-8 pagination reconstructs exact persisted bytes and bounds oversized requests", () -> {
            DockerDiagnostics log = new DockerDiagnostics(new File(suite, "pages"), secret);
            log.begin(Build.SUPPORTED_ABIS[0], true, 512, 1);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 20; i++) text.append("PAGE ").append(i).append(" zażółć gęślą jaźń 😀\n");
            log.append(text.toString()); log.finish("normal_exit", 0, "Pagination fixture"); log.recordExit(0);
            String expected = read(new File(log.currentDirectory(), "console.log"));
            int offset = 0, pages = 0; StringBuilder joined = new StringBuilder();
            while (true) {
                JSONObject page = log.read(offset, 7);
                int next = page.getInt("next_offset"), total = page.getInt("total_bytes");
                check(next >= offset && next <= total, "Page cursor escaped log bounds");
                String content = page.getString("content"); check(!content.contains("\ufffd"), "Page cut a UTF-8 character"); joined.append(content);
                if (next == total) break;
                check(next > offset && ++pages < 10000, "Pagination made no bounded progress"); offset = next;
            }
            check(expected.equals(joined.toString()), "Paginated content differs from persisted UTF-8");
            byte[] utf8 = expected.getBytes(StandardCharsets.UTF_8);
            int middle = 0;
            for (int i = 1; i < utf8.length; i++) if ((utf8[i] & 0xC0) == 0x80) { middle = i; break; }
            check(middle > 0, "Fixture has no multibyte UTF-8 character");
            int characterStart = middle; while (characterStart > 0 && (utf8[characterStart] & 0xC0) == 0x80) characterStart--;
            JSONObject arbitrary = log.read(middle, 1); int alignedNext = arbitrary.getInt("next_offset");
            check(alignedNext > middle && !arbitrary.getString("content").contains("\ufffd"), "Arbitrary offset did not advance through a complete character");
            check(arbitrary.getString("content").equals(new String(utf8, characterStart, alignedNext - characterStart, StandardCharsets.UTF_8)), "Arbitrary byte offset changed UTF-8 bytes");
            JSONObject end = log.read(Integer.MAX_VALUE, Integer.MAX_VALUE);
            check(end.getString("content").isEmpty() && end.getInt("next_offset") == end.getInt("total_bytes"), "Out-of-range request escaped EOF");
            JSONObject negative = log.read(-200, -7);
            check(negative.getInt("next_offset") >= 0 && negative.getInt("next_offset") <= negative.getInt("total_bytes"), "Negative read escaped bounds");
        });
        test("Diagnostics: console cap is explicit and only the latest four boots are retained", () -> {
            File directory = new File(suite, "limits");
            DockerDiagnostics log = new DockerDiagnostics(directory, secret);
            log.begin(Build.SUPPORTED_ABIS[0], true, 2048, 2);
            char[] overflow = new char[2 * 1024 * 1024 + 4096]; java.util.Arrays.fill(overflow, 'A');
            log.append(new String(overflow));
            char[] largeGuest = new char[300 * 1024]; java.util.Arrays.fill(largeGuest, 'B');
            log.snapshotGuest(new JSONObject().put("oversized", new String(largeGuest)).put("private", secret));
            log.noteStartup(new JSONObject().put("oversized", new String(largeGuest)));
            log.finish("normal_exit", 0, "Bounded log fixture"); log.recordExit(0);
            check(new File(log.currentDirectory(), "console.log").length() <= 2L * 1024 * 1024, "Console exceeds 2MiB");
            check(log.status().getBoolean("console_truncated") && log.status().getLong("total_output_bytes") >= overflow.length, "Truncation/counter is hidden");
            check(log.read(0, Integer.MAX_VALUE).getString("content").getBytes(StandardCharsets.UTF_8).length <= 64 * 1024, "Read page exceeds the 64KiB bound");
            File guest = new File(log.currentDirectory(), "guest.json"), metadata = new File(log.currentDirectory(), "metadata.json");
            check(guest.length() <= 256 * 1024 && metadata.length() <= 32 * 1024, "Snapshot or metadata exceeded its bound");
            check(new JSONObject(read(guest)).getBoolean("truncated") && !read(guest).contains(secret), "Oversized guest snapshot hid truncation or exposed its token");
            java.util.List<File> bootDirectories = new java.util.ArrayList<>(); bootDirectories.add(log.currentDirectory());
            for (int i = 0; i < 4; i++) {
                log = new DockerDiagnostics(directory, secret); log.begin(Build.SUPPORTED_ABIS[0], true, 512, 1);
                log.append("BOOT " + i + "\n"); log.finish("normal_exit", 0, "Retention fixture"); log.recordExit(0); bootDirectories.add(log.currentDirectory());
            }
            check(!bootDirectories.get(0).exists(), "Oldest fifth boot was retained");
            for (int i = 1; i < bootDirectories.size(); i++) check(bootDirectories.get(i).isDirectory(), "A recent boot was removed");
            DockerDiagnostics stress = new DockerDiagnostics(new File(suite, "descriptor-stability"), secret);
            stress.begin(Build.SUPPORTED_ABIS[0], true, 512, 1); stress.snapshotGuest(new JSONObject().put("fixture", "FD stability"));
            // Warm up channel creation before counting; never request GC, which
            // could conceal an unowned Os.open descriptor behind finalization.
            stress.append("FD_WARMUP\n"); stress.noteStartup(new JSONObject().put("fixture", "warmup")); stress.read(0, 128); stress.writeTo(new ByteArrayOutputStream());
            int descriptorsBefore = descriptors();
            try {
                for (int i = 0; i < 200; i++) {
                    stress.append("FD_ITERATION " + i + "\n"); stress.noteStartup(new JSONObject().put("fixture", i));
                    check(stress.read(0, 128).getString("content").contains("FD_WARMUP"), "Repeated descriptor read lost its console");
                    ByteArrayOutputStream exported = new ByteArrayOutputStream(); stress.writeTo(exported);
                    check(exported.size() > 0, "Repeated descriptor export returned no data");
                }
                int descriptorsAfter = descriptors();
                observations.put("diagnostic_fd_stability", new JSONObject().put("iterations", 200)
                    .put("before", descriptorsBefore).put("after", descriptorsAfter).put("gc_requested", false));
                check(descriptorsAfter <= descriptorsBefore + 4, "Diagnostic I/O leaked file descriptors: before=" + descriptorsBefore + " after=" + descriptorsAfter + " iterations=200");
                check(stress.status().optString("diagnostic_error", "").isEmpty(), "Descriptor stress recorded an I/O error");
            } finally { stress.finish("normal_exit", null, "Descriptor fixture; no QEMU process"); stress.recordNoProcess(); }
        });
        test("Diagnostics: interrupted process restore and late prior finish preserve the newest boot pointer", () -> {
            File directory = new File(suite, "interrupted");
            DockerDiagnostics first = new DockerDiagnostics(directory, secret); first.begin(Build.SUPPORTED_ABIS[0], true, 1024, 1); first.append("UNFINISHED\n");
            DockerDiagnostics sameProcess = new DockerDiagnostics(directory, secret);
            check(!"previous_app_session_interrupted".equals(sameProcess.status().optString("reason")), "A live app instance was incorrectly marked interrupted");
            File metadata = new File(first.currentDirectory(), "metadata.json"); JSONObject old = new JSONObject(read(metadata));
            old.put("app_pid", -123); write(metadata, old.toString());
            DockerDiagnostics restored = new DockerDiagnostics(directory, secret);
            check("previous_app_session_interrupted".equals(restored.status().getString("reason")) && restored.status().isNull("exit_code"), "Interrupted process restore invented a known exit code");
            restored.finish("emergency_stop", null, "New stop after interrupted-session restore");
            check("previous_app_session_interrupted".equals(restored.status().getString("reason")) && "previous_app_session_interrupted".equals(restored.status().getString("phase")) && restored.status().isNull("exit_code"), "Read-only interrupted restore replaced its cause or phase");
            DockerDiagnostics newest = new DockerDiagnostics(directory, secret); newest.begin(Build.SUPPORTED_ABIS[0], true, 1024, 2); newest.append("NEWEST\n");
            String newestId = newest.status().getString("run_id");
            first.finish("stopped", null, "Late previous-boot cleanup"); first.recordExit(137);
            check(newestId.equals(new DockerDiagnostics(directory, secret).status().getString("run_id")), "Old boot finish replaced the newest pointer");
            newest.finish("stopped", null, "Owned cleanup fixture"); newest.recordExit(137);
            check("stopped".equals(newest.status().getString("reason")) && newest.status().getInt("exit_code") == 137, "Actual exit overwrote the explicit cleanup reason");
        });
        test("Diagnostics: symlinked files and UUID directories cannot expose or prune HOME data", () -> {
            File home = new File(suite, "symlink-safe/HOME"); home.mkdirs();
            File token = new File(home, "token");
            String sentinel = "PRIVATE_HOME_SENTINEL_" + runId;
            for (String name : new String[]{"console.log", "guest.json", "metadata.json"}) {
                File directory = new File(suite, "symlink-safe/files-" + name);
                DockerDiagnostics log = new DockerDiagnostics(directory, secret);
                log.begin(Build.SUPPORTED_ABIS[0], true, 512, 1); log.append("SAFE_CONSOLE\n");
                log.snapshotGuest(new JSONObject().put("fixture", true)); log.finish("normal_exit", 0, "Symlink fixture"); log.recordExit(0);
                JSONObject privateDocument = new JSONObject(read(new File(log.currentDirectory(), "metadata.json"))).put("private", sentinel);
                write(token, privateDocument.toString()); JSONObject retained = fingerprint(token);
                File fixed = new File(log.currentDirectory(), name);
                check(fixed.delete(), "Cannot replace own diagnostic fixture file"); Os.symlink(token.getAbsolutePath(), fixed.getAbsolutePath());
                check(!log.read(0, 32768).toString().contains(sentinel), "Live log followed a HOME symlink");
                DockerDiagnostics restored = new DockerDiagnostics(directory, secret);
                check(!restored.status().toString().contains(sentinel) && !restored.read(0, 32768).toString().contains(sentinel), "Restore/page exposed HOME data");
                if ("metadata.json".equals(name)) check(!restored.status().optBoolean("available") && !restored.status().optString("diagnostic_error", "").isEmpty(), "Restore accepted symlinked metadata");
                for (DockerDiagnostics instance : new DockerDiagnostics[]{log, restored}) {
                    ByteArrayOutputStream exported = new ByteArrayOutputStream(); boolean denied = false;
                    try { instance.writeTo(exported); } catch (IOException expected) { denied = true; }
                    // Metadata export uses the safe in-memory state; it need
                    // not inspect a substituted metadata file. Streamed files
                    // must be rejected rather than followed.
                    if (!"metadata.json".equals(name)) check(denied, "Export accepted a symlinked " + name);
                    check(!new String(exported.toByteArray(), StandardCharsets.UTF_8).contains(sentinel), "Export exposed HOME data");
                }
                unchanged(token, retained);
            }
            File directory = new File(suite, "symlink-safe/prune"); directory.mkdirs();
            String linkedId = java.util.UUID.randomUUID().toString();
            File homeMetadata = new File(home, "metadata.json"), homeConsole = new File(home, "console.log");
            write(homeMetadata, new JSONObject().put("format", 1).put("run_id", linkedId).put("started_at_epoch_ms", 1).toString());
            write(homeConsole, sentinel); JSONObject retainedToken = fingerprint(token), retainedMetadata = fingerprint(homeMetadata), retainedConsole = fingerprint(homeConsole);
            Os.symlink(home.getAbsolutePath(), new File(directory, linkedId).getAbsolutePath());
            for (int i = 0; i < 5; i++) {
                DockerDiagnostics log = new DockerDiagnostics(directory, secret); log.begin(Build.SUPPORTED_ABIS[0], true, 512, 1);
                log.append("PRUNE_FIXTURE " + i + "\n"); log.finish("normal_exit", 0, "Directory symlink fixture"); log.recordExit(0);
            }
            unchanged(token, retainedToken); unchanged(homeMetadata, retainedMetadata); unchanged(homeConsole, retainedConsole);
        });
      }
      int descriptors() {
          String[] files = new File("/proc/self/fd").list(); check(files != null, "Cannot inspect own Android file descriptors"); return files.length;
      }
    }
    private Fixture fixture(String name) throws Exception {
        File files = new File(suite, name); files.mkdirs();
        Context context = new ContextWrapper(app) { @Override public File getFilesDir() { return files; } };
        Constructor<DockerVm> constructor = DockerVm.class.getDeclaredConstructor(Context.class); constructor.setAccessible(true);
        DockerVm vm = constructor.newInstance(context);
        JSONObject current;
        try (InputStream in = app.getAssets().open("docker/" + Build.SUPPORTED_ABIS[0] + "/manifest.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] block = new byte[4096]; int count;
            while ((count = in.read(block)) != -1) bytes.write(block, 0, count);
            current = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        JSONObject previous = new JSONObject(current.toString()); previous.put("version", 1);
        previous.remove("runtime"); previous.remove("runtime_revision"); previous.remove("migration");
        previous.getJSONObject("files").put("initramfs-virt", "legacy-initramfs-hash");
        write(new File(vm.directory, "installed-manifest"), previous.toString());
        write(new File(vm.directory, "base.qcow2"), "old-backing");
        write(new File(vm.directory, "user.qcow2"), "old-user-data");
        write(new File(vm.directory, "vmlinuz-virt"), "old-kernel");
        write(new File(vm.directory, "initramfs-virt"), "old-initramfs");
        File export = new File(files, "docker-exports/kept.txt"); write(export, "retained-export");
        return new Fixture(vm, current, previous, export);
    }
    static class Fixture {
        final DockerVm vm; final JSONObject current, previous; final File export;
        Fixture(DockerVm vm, JSONObject current, JSONObject previous, File export) { this.vm = vm; this.current = current; this.previous = previous; this.export = export; }
        void install(int generation) throws Exception {
            Method method = DockerVm.class.getDeclaredMethod("install", int.class); method.setAccessible(true);
            try { method.invoke(vm, generation); }
            catch (InvocationTargetException error) { if (error.getCause() instanceof Exception) throw (Exception) error.getCause(); throw error; }
        }
    }
    private void test(String name, Check check) { try { check.run(); record(name, true, null); } catch (Throwable error) { record(name, false, error.toString()); } }
    private void record(String name, boolean pass, String detail) {
        try { JSONObject check = new JSONObject().put("name", name).put("pass", pass); if (detail != null) check.put("detail", detail); tests.put(check); }
        catch (JSONException ignored) {} if (!pass) failures++;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void write(File file, String text) throws IOException { file.getParentFile().mkdirs(); try (OutputStream out = new FileOutputStream(file)) { out.write(text.getBytes(StandardCharsets.UTF_8)); } }
    private static String read(File file) throws IOException { try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] buffer = new byte[4096]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); return new String(out.toByteArray(), StandardCharsets.UTF_8); } }
    private static String sha(File file) throws Exception { MessageDigest hash = MessageDigest.getInstance("SHA-256"); try (InputStream in = new FileInputStream(file)) { byte[] block = new byte[65536]; int n; while ((n = in.read(block)) != -1) hash.update(block, 0, n); } StringBuilder result = new StringBuilder(); for (byte item : hash.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255)); return result.toString(); }
    private static void remove(File file) { if (file.isDirectory()) { File[] files = file.listFiles(); if (files != null) for (File child : files) remove(child); } file.delete(); }
}
