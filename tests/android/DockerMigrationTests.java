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

/** Actual Android installation path in isolated cache; real QCOW2 is tested in ARM Linux. */
public class DockerMigrationTests extends Instrumentation {
    private Context app;
    private File suite;
    private String runId;
    private int failures;
    private long started;
    private final JSONArray tests = new JSONArray();
    interface Check { void run() throws Exception; }
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); runId = args.getString("run_id", java.util.UUID.randomUUID().toString()); start();
    }
    @Override public void onStart() {
        app = getTargetContext(); started = SystemClock.elapsedRealtime();
        suite = new File(app.getCacheDir(), "docker-migration-" + runId); suite.mkdirs();
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
        try {
            JSONObject report = new JSONObject().put("run_id", runId).put("version", app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName)
                .put("api", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS[0]).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                .put("failures", failures).put("tests", tests)
                .put("fixture", "Actual DockerVm.install in isolated Android cache; sentinel backing/overlay files. Real ARM QCOW2 migration is separate.");
            ContentValues values = new ContentValues(); values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "results.json");
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/androidagent-docker-migration-tests");
            android.net.Uri uri = app.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            try (OutputStream out = app.getContentResolver().openOutputStream(uri)) { out.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception error) { record("report", false, error.toString()); }
        remove(suite);
        Bundle result = new Bundle(); result.putString("stream", "Docker migration: " + tests.length() + " checks, " + failures + " failures\n");
        finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
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
