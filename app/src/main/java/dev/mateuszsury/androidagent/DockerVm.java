package dev.mateuszsury.androidagent;

import android.content.Context;
import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/** Owns one Linux guest in app-private storage. Never runs Docker on the Android kernel. */
final class DockerVm {
    static final int API_PORT = 18780, PREVIEW_PORT = 18781;
    /** Guest ports the guest agent hands out, one per task; 8080 also has the boot-time PREVIEW_PORT forward. */
    static final int GUEST_PREVIEW_FIRST = 8080, GUEST_PREVIEW_LAST = 8179;
    /** Host ports for forwards added at runtime with QMP hostfwd_add, one per published task. */
    static final int HOST_PREVIEW_FIRST = 18800, HOST_PREVIEW_LAST = 18899;
    private static final String NETDEV = "net", PREFS = "docker_vm", LAN_KEY = "lan_preview";
    private static DockerVm instance;
    static synchronized DockerVm get(Context c) {
        if (instance == null) instance = new DockerVm(c.getApplicationContext());
        return instance;
    }
    final Context context;
    final File directory;
    private volatile Process process;
    private volatile int epoch;
    private volatile int stoppedEpoch;
    volatile String state = "stopped", detail = "Gotowy do uruchomienia";
    volatile JSONObject guest = new JSONObject();
    private volatile String console = "";
    private final String token;
    private final String abi;
    final boolean emulatedAndroid;
    private final Object lifecycle = new Object();
    private final Object preparation = new Object();
    private int pendingStops;
    private volatile DockerDiagnostics diagnostics;
    private volatile long lastGuestResponse, bootStarted;
    private volatile String lastProbeError = "";
    private volatile int ramMb, vcpus, startAttempts;
    /** One published guest port -> its host forward in the current QEMU process. */
    private static final class Forward {
        final int guest, host; final String bind; final boolean boot;
        Forward(int guest, int host, String bind, boolean boot) { this.guest = guest; this.host = host; this.bind = bind; this.boot = boot; }
    }
    private final Object forwarding = new Object();
    private final Map<Integer, Forward> forwards = new HashMap<>();
    private final Map<Integer, Long> forwardRetry = new HashMap<>();
    private int forwardGeneration = -1;
    private volatile String lastForwardError = "";
    private DockerVm(Context c) {
        context = c;
        directory = new File(c.getFilesDir(), "docker");
        directory.mkdirs();
        abi = android.os.Build.SUPPORTED_ABIS[0];
        emulatedAndroid = "ranchu".equals(android.os.Build.HARDWARE) || "goldfish".equals(android.os.Build.HARDWARE);
        android.content.SharedPreferences prefs = c.getSharedPreferences(PREFS, 0);
        String existing = prefs.getString("token", null);
        if (existing == null) {
            byte[] random = new byte[32]; new java.security.SecureRandom().nextBytes(random);
            existing = hex(random); prefs.edit().putString("token", existing).apply();
        }
        token = existing;
        diagnostics = new DockerDiagnostics(new File(directory, "diagnostics"), token);
    }
    JSONObject status() throws JSONException {
        return new JSONObject().put("state", state).put("detail", detail).put("abi", abi)
                .put("ready", "ready".equals(state)).put("root_required", false)
                .put("clock_mode", emulatedAndroid ? "instruction_count" : "host")
                .put("preview_url", "http://127.0.0.1:" + PREVIEW_PORT)
                .put("guest", guest).put("host_free_bytes", directory.getUsableSpace())
                .put("ram_mb", ramMb).put("vcpus", vcpus)
                .put("startup_budget_seconds", emulatedAndroid ? 900 : 1200)
                .put("last_guest_response_host_ms", lastGuestResponse)
                .put("last_probe_error", lastProbeError).put("diagnostics", diagnostics.status())
                .put("lan_exposure", lanExposure(context)).put("previews", previews());
    }
    String console() { return console; }
    boolean alive() { Process p = process; return p != null && p.isAlive(); }
    int stoppedGeneration() { return stoppedEpoch; }
    boolean starting() { String s = state; return "preparing".equals(s) || "booting".equals(s); }
    /** Counts boot attempts (and refused starts) so a caller can tell a new error from an old one. */
    int startAttempts() { return startAttempts; }
    /** DockerService could not enter the foreground (Android background-start rules): report it, do not hang. */
    void startRejected(String reason) {
        synchronized (lifecycle) {
            if (alive() || starting() || "stopping".equals(state)) return;
            state = "error"; detail = reason; startAttempts++;
        }
    }

    void boot(int ram, int cores) {
        final int generation;
        final DockerDiagnostics log;
        synchronized (lifecycle) {
            McpHost.get(context).requireReady();
            if (alive() || "preparing".equals(state) || "booting".equals(state) || "stopping".equals(state)) return;
            generation = ++epoch; startAttempts++;
            state = "preparing"; detail = "Przygotowywanie środowiska"; console = "";
            guest = new JSONObject(); lastGuestResponse = 0; lastProbeError = "";
            ramMb = Math.max(512, Math.min(2048, ram)); vcpus = Math.max(1, Math.min(2, cores));
            log = new DockerDiagnostics(new File(directory, "diagnostics"), token);
            log.begin(abi, emulatedAndroid, ramMb, vcpus); diagnostics = log;
        }
        boolean processStarted = false;
        try {
            if (!"arm64-v8a".equals(abi) && !"x86_64".equals(abi)) throw new IOException("Wymagany telefon ARM64 lub x86_64");
            File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
            File qemu = new File(nativeDir, "libqemu.so"), img = new File(nativeDir, "libqemu_img.so");
            if (!qemu.isFile() || !img.isFile()) throw new IOException("Ten APK nie zawiera środowiska QEMU. Zbuduj pełny wariant aplikacji.");
            final Process running;
            synchronized (preparation) {
            if (epoch != generation) return;
            install(generation);
            if (epoch != generation) return;
            File secret = new File(directory, "boot-token");
            try (OutputStream out = new FileOutputStream(secret)) {
                byte[] block = new byte[512];
                System.arraycopy(token.getBytes(StandardCharsets.US_ASCII), 0, block, 0, 64);
                out.write(block);
            }
            File overlay = new File(directory, "user.qcow2");
            if (!overlay.exists()) {
                File creationLog = new File(directory, "image-create.log");
                Process create = nativeProcess(Arrays.asList(img.getAbsolutePath(), "create", "-f", "qcow2", "-F", "qcow2",
                        "-b", new File(directory, "base.qcow2").getAbsolutePath(), overlay.getAbsolutePath())).redirectOutput(creationLog).start();
                if (!create.waitFor(30, TimeUnit.SECONDS) || create.exitValue() != 0) {
                    create.destroyForcibly(); throw new IOException("Nie można utworzyć dysku: " + readBounded(new FileInputStream(creationLog), 8192));
                }
            }
            ArrayList<String> args = new ArrayList<>();
            args.add(qemu.getAbsolutePath());
            Collections.addAll(args, "-L", new File(directory, "qemu").getAbsolutePath(),
                    "-machine", "arm64-v8a".equals(abi) ? "virt" : "q35",
                    "-cpu", "arm64-v8a".equals(abi) ? "cortex-a72" : "qemu64",
                    "-accel", emulatedAndroid ? "tcg,thread=single" : "tcg,thread=multi",
                    "-smp", String.valueOf(Math.max(1, Math.min(2, cores))),
                    "-m", String.valueOf(Math.max(512, Math.min(2048, ram))),
                    "-kernel", new File(directory, "vmlinuz-virt").getAbsolutePath(),
                    "-initrd", new File(directory, "initramfs-virt").getAbsolutePath(),
                    "-append", "console=" + ("arm64-v8a".equals(abi) ? "ttyAMA0" : "ttyS0")
                            + " root=LABEL=androidagent rootfstype=ext4 rootflags=rw rw init=/opt/androidagent/init.sh "
                            + "modules=virtio_pci,virtio_blk,virtio_net,ext4",
                    "-drive", "if=none,file=" + overlay.getAbsolutePath() + ",format=qcow2,id=root,cache=writeback",
                    "-device", "arm64-v8a".equals(abi) ? "virtio-blk-device,drive=root" : "virtio-blk-pci,drive=root",
                    "-drive", "if=none,file=" + secret.getAbsolutePath() + ",format=raw,readonly=on,id=secret",
                    "-device", "arm64-v8a".equals(abi) ? "virtio-blk-device,drive=secret" : "virtio-blk-pci,drive=secret",
                    "-netdev", "user,id=net,hostfwd=tcp:127.0.0.1:" + API_PORT + "-:7080,hostfwd=tcp:127.0.0.1:" + PREVIEW_PORT + "-:8080",
                    "-device", "arm64-v8a".equals(abi) ? "virtio-net-device,netdev=net" : "virtio-net-pci,netdev=net,romfile=",
                    "-device", "qemu-xhci,id=aausb,p2=8,p3=8",
                    "-qmp", "unix:" + new File(directory,"qmp.sock").getAbsolutePath() + ",server=on,wait=off",
                    "-nodefaults", "-display", "none", "-serial", "stdio", "-monitor", "none", "-no-reboot");
            // Only nested Android emulators need an instruction-count clock.
            // Physical phones retain normal Linux wall-clock timeout semantics.
            if (emulatedAndroid) Collections.addAll(args, "-icount", "shift=3,align=off,sleep=on");
            // PID file permits an actual SIGKILL on Android, whose destroyForcibly can be SIGTERM.
            ArrayList<String> shell = new ArrayList<>(Arrays.asList("/system/bin/sh", "-c",
                    "echo $$ > \"$AA_PID_FILE\"; exec \"$@\"", "androidagent-qemu"));
            shell.addAll(args);
            synchronized (lifecycle) {
                if (epoch != generation) return;
                ProcessBuilder pb = nativeProcess(shell);
                File pidFile=new File(directory,"vm.pid");
                if(pidFile.exists()&&!pidFile.delete())throw new IOException("Nie można przygotować pliku procesu środowiska");
                pb.environment().put("AA_PID_FILE", pidFile.getAbsolutePath());
                process = pb.start(); processStarted = true; running = process; state = "booting"; detail = "Uruchamianie Linuxa i Dockera";
                bootStarted = SystemClock.elapsedRealtime();
                log.append("ANDROID_HOST_PROCESS_STARTED epoch_ms=" + System.currentTimeMillis()
                    + " monotonic_ms=" + bootStarted + " ram_mb=" + ramMb + " vcpus=" + vcpus + "\n");
            }
            }
            Thread reader = new Thread(() -> {
                try (Reader in = new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[2048]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        String text = new String(buffer, 0, n);
                        log.append(text);
                        synchronized (this) { if(epoch == generation) { console = (console + text).replace(token, "[token]"); if (console.length() > 65536) console = console.substring(console.length() - 65536); } }
                    }
                } catch (Exception ignored) {}
            }, "docker-console");
            reader.setDaemon(true); reader.start();
            Thread exitObserver = new Thread(() -> {
                try {
                    int code = running.waitFor();
                    reader.join(1500);
                    log.recordExit(code);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "docker-process-exit");
            exitObserver.setDaemon(true); exitObserver.start();
            // Each stage has a real bounded probe in the guest. A physical TCG
            // host must also allow cold image import; failures arrive sooner.
            long deadline = bootStarted + (emulatedAndroid ? 900000 : 1200000);
            long nextProbeLog = 0;
            while (epoch == generation && running.isAlive()) {
                String startupError = null;
                try {
                    JSONObject result = requestRaw(new JSONObject().put("op", "status"), 12000);
                    // Re-adds forwards after a restart, follows the LAN setting
                    // and drops forwards of cleaned tasks. Never under lifecycle.
                    if (result.optBoolean("ready")) syncForwards(result, generation);
                    synchronized (lifecycle) {
                        if (epoch != generation || process != running) break;
                        lastGuestResponse = SystemClock.elapsedRealtime(); lastProbeError = "";
                        guest = result;
                        JSONObject startup = result.optJSONObject("startup");
                        if (startup != null) log.noteStartup(startup);
                        if (startup != null && "error".equals(startup.optString("stage"))) startupError = startup.optString("detail", "Błąd rozruchu Docker Engine");
                        if (result.optBoolean("ready")) { state = "ready"; detail = "Docker działa na telefonie"; }
                        // A busy engine can miss one short heartbeat while a
                        // build is running. Its supervisor publishes failures
                        // explicitly; do not reject cancellation due to jitter.
                        else if (!"ready".equals(state)) { state = "booting"; detail = startup == null ? "Uruchamianie Linuxa i Dockera" : startup.optString("detail", "Uruchamianie Dockera"); }
                    }
                } catch (Exception error) {
                    synchronized (lifecycle) {
                        if (epoch != generation || process != running) break;
                        lastProbeError = error.getClass().getSimpleName() + ": " + error.getMessage();
                        if (SystemClock.elapsedRealtime() >= nextProbeLog) {
                            log.append("ANDROID_HOST_PROBE " + lastProbeError + "\n");
                            nextProbeLog = SystemClock.elapsedRealtime() + 30000;
                        }
                    }
                }
                if (epoch != generation || process != running) break;
                if (startupError != null) {
                    captureGuestDiagnostics(log, generation, running);
                    log.finish("guest_startup_error", null, startupError);
                    throw new IOException(startupError + ". Zachowano diagnostykę rozruchu.");
                }
                if (!"ready".equals(state) && SystemClock.elapsedRealtime() > deadline) {
                    captureGuestDiagnostics(log, generation, running);
                    log.finish("host_startup_timeout", null, "Limit rozruchu Androida");
                    throw new IOException("Linux nie uruchomił Dockera w wyznaczonym czasie. Zachowano diagnostykę rozruchu.");
                }
                Thread.sleep(2000);
            }
            if (epoch == generation) throw new IOException("Środowisko zakończyło działanie. " + console.substring(Math.max(0, console.length() - 1000)));
        } catch (Exception e) {
            synchronized (lifecycle) {
                if (epoch == generation) {
                    if (!log.status().optBoolean("finished")) log.finish("boot_error", null, e.getMessage());
                    kill(); state = "error"; detail = e.getMessage() == null ? e.toString() : e.getMessage();
                }
            }
        } finally { if (!processStarted) log.recordNoProcess(); }
    }

    private void captureGuestDiagnostics(DockerDiagnostics log, int generation, Process running) {
        if (epoch != generation || process != running) return;
        try {
            JSONObject snapshot = requestRaw(new JSONObject().put("op", "diagnostics").put("tail_bytes", 8192).put("max_bytes", 262144), 8000);
            synchronized (lifecycle) { if (epoch == generation && process == running) log.snapshotGuest(snapshot); }
        } catch (Exception e) { log.append("ANDROID_DIAGNOSTICS_UNAVAILABLE " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n"); }
    }

    JSONObject diagnostics(int offset, int limit) throws Exception {
        if (offset < 0 || limit < 1 || limit > 32768) throw new IllegalArgumentException("offset >= 0; limit musi wynosić 1–32768 bajtów");
        DockerDiagnostics current = diagnostics;
        return current.read(offset, limit).put("metadata", current.status());
    }

    JSONObject exportDiagnostics() throws Exception {
        DockerDiagnostics current = diagnostics;
        if (!current.status().optBoolean("available")) throw new IOException("Brak zapisanego dziennika. Najpierw uruchom środowisko.");
        File dir = new File(context.getFilesDir(), "docker-exports"); dir.mkdirs();
        String id = UUID.randomUUID().toString().replace("-", "") + ".txt";
        File target = new File(dir, id);
        File marker = new File(dir, id + ".json");
        try {
            try (OutputStream out = new FileOutputStream(target)) { current.writeTo(out); }
            JSONObject result = new JSONObject().put("id", id).put("name", "docker-startup-" + current.status().optString("boot_id", "unknown") + ".txt")
                .put("task", "diagnostics").put("bytes", target.length()).put("uri", FilesProvider.dockerUri(id).toString());
            try (OutputStream out = new FileOutputStream(marker)) { out.write(result.toString().getBytes(StandardCharsets.UTF_8)); }
            return result;
        } catch (Exception failure) { target.delete(); marker.delete(); throw failure; }
    }

    private ProcessBuilder nativeProcess(List<String> args) {
        ProcessBuilder p = new ProcessBuilder(args).directory(directory).redirectErrorStream(true);
        p.environment().put("LD_LIBRARY_PATH", context.getApplicationInfo().nativeLibraryDir);
        p.environment().put("HOME", directory.getAbsolutePath());
        p.environment().put("TMPDIR", directory.getAbsolutePath());
        return p;
    }
    private void install(int generation) throws Exception {
        String asset = "docker/" + abi + "/";
        String manifest = readBounded(context.getAssets().open(asset + "manifest.json"), 16384);
        File marker = new File(directory, "installed-manifest");
        if (marker.isFile() && manifest.equals(readBounded(new FileInputStream(marker), 16384))
                && new File(directory, "base.qcow2").isFile() && new File(directory, "vmlinuz-virt").isFile()
                && new File(directory, "initramfs-virt").isFile()) return;
        boolean preserveDisk = new File(directory, "user.qcow2").exists();
        JSONObject current = new JSONObject(manifest);
        if (preserveDisk) {
            if (!marker.isFile()) throw new IOException("Nie można potwierdzić wersji zapisanego dysku. Zachowaj wyniki przed zmianą środowiska.");
            JSONObject previous = new JSONObject(readBounded(new FileInputStream(marker), 16384));
            JSONObject priorFiles = previous.getJSONObject("files"), nextFiles = current.getJSONObject("files");
            // Never replace a QCOW2 backing image underneath existing user
            // sectors. 0.4.3 changes only the owned guest runtime in initramfs.
            for (String name : Arrays.asList("base.qcow2.gz", "vmlinuz-virt")) {
                if (!nextFiles.getString(name).equals(priorFiles.optString(name)))
                    throw new IOException("Ta aktualizacja zmienia bazę dysku. Najpierw zachowaj wyniki; dysk nie został zmieniony.");
            }
            if (!new File(directory, "base.qcow2").isFile() || !new File(directory, "vmlinuz-virt").isFile())
                throw new IOException("Brakuje bazy zapisanego dysku. Dysk użytkownika pozostaje zachowany.");
            detail = "Aktualizowanie nadzoru Dockera z zachowaniem dysku i projektów";
        }
        long needed = (preserveDisk ? 64L : 1024L) * 1024 * 1024;
        if (directory.getUsableSpace() < needed) throw new IOException(preserveDisk
                ? "Aktualizacja nadzoru Dockera wymaga co najmniej 64 MB wolnego miejsca."
                : "Przygotowanie środowiska wymaga co najmniej 1 GB wolnego miejsca.");
        JSONObject files = new JSONObject(manifest).getJSONObject("files");
        for (String name : Arrays.asList("base.qcow2.gz", "vmlinuz-virt", "initramfs-virt")) {
            if (preserveDisk && !"initramfs-virt".equals(name)) continue;
            if (epoch != generation) return;
            detail = "Rozpakowywanie środowiska Linux";
            File part = new File(directory, name + ".part");
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream in = context.getAssets().open(asset + name); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (epoch != generation) throw new IOException("Przerwano przygotowanie");
                    hash.update(buffer, 0, n); out.write(buffer, 0, n);
                }
                out.getFD().sync();
            }
            if (!hex(hash.digest()).equals(files.getString(name))) { part.delete(); throw new IOException("Nieprawidłowa suma SHA256 środowiska"); }
            if (name.endsWith(".gz")) {
                File unpacked = new File(directory, "base.qcow2.part");
                try (InputStream in = new GZIPInputStream(new FileInputStream(part), 65536); FileOutputStream out = new FileOutputStream(unpacked)) {
                    byte[] buffer = new byte[65536]; int n; long total=0;
                    while ((n=in.read(buffer))!=-1) {
                        if(epoch!=generation)throw new IOException("Przerwano przygotowanie");
                        total+=n;if(total>1024L*1024*1024)throw new IOException("Obraz przekracza limit rozpakowania");
                        out.write(buffer,0,n);detail="Rozpakowywanie Linuxa: "+(total/1048576)+" MB";
                    }
                    out.getFD().sync();
                }
                if (epoch != generation) return;
                if (!unpacked.renameTo(new File(directory, "base.qcow2"))) throw new IOException("Nie można zapisać obrazu Linuxa");
                part.delete();
            } else if (!part.renameTo(new File(directory, name))) throw new IOException("Nie można zapisać kernela");
        }
        if (epoch != generation) return;
        copyAssets(asset + "qemu", new File(directory, "qemu"));
        if (epoch != generation) return;
        File markerPart = new File(directory, "installed-manifest.part");
        try (FileOutputStream out = new FileOutputStream(markerPart)) {
            out.write(manifest.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
        if (!markerPart.renameTo(marker)) throw new IOException("Nie można zapisać wersji środowiska");
        java.io.FileDescriptor installedDirectory = android.system.Os.open(directory.getAbsolutePath(), android.system.OsConstants.O_RDONLY, 0);
        try { android.system.Os.fsync(installedDirectory); } finally { android.system.Os.close(installedDirectory); }
    }
    private void copyAssets(String source, File target) throws IOException {
        String[] children = context.getAssets().list(source);
        if (children != null && children.length > 0) {
            target.mkdirs(); for (String child : children) copyAssets(source + "/" + child, new File(target, child));
        } else {
            target.getParentFile().mkdirs();
            try (InputStream in = context.getAssets().open(source); FileOutputStream out = new FileOutputStream(target)) {
                copy(in, out, 32 * 1024 * 1024); out.getFD().sync();
            }
        }
    }

    JSONObject request(JSONObject args) throws Exception {
        if("cleanup".equals(args.optString("op"))||"cancel".equals(args.optString("op"))) {
            String task=args.optString("task");
            if(!task.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid task id");
            UsbDocker.closeTask(context,task);
        }
        if (!"ready".equals(state)) throw new IOException("Otwórz Docker w androidagent i uruchom środowisko. " + detail);
        final int generation = epoch;
        JSONObject result = requestRaw(args, 60000);
        if (generation != epoch) throw new IOException("Środowisko zostało zatrzymane");
        published(args.optString("op"), result, generation);
        return result;
    }

    // ------------------------------------------------------------ published app ports

    /** Owner setting (default off): bind preview forwards on all interfaces so Wi-Fi peers can open them. */
    static boolean lanExposure(Context c) { return c.getSharedPreferences(PREFS, 0).getBoolean(LAN_KEY, false); }
    /** Applied to running forwards by the next heartbeat (about 2 s). The control API always stays on loopback. */
    static void setLanExposure(Context c, boolean on) { c.getSharedPreferences(PREFS, 0).edit().putBoolean(LAN_KEY, on).apply(); }

    /** Host port of a task's published app, for opening {@link DockerPreviewActivity}; PREVIEW_PORT if unknown. */
    int previewPort(String task) {
        JSONArray tasks = guest.optJSONArray("tasks");
        if (tasks != null) for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.optJSONObject(i);
            if (t != null && t.optString("id").equals(task) && t.optInt("host_port", 0) > 0) return t.optInt("host_port");
        }
        return PREVIEW_PORT;
    }

    private JSONArray previews() throws JSONException {
        JSONArray out = new JSONArray();
        JSONArray tasks = alive() ? guest.optJSONArray("tasks") : null;
        if (tasks != null) for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.optJSONObject(i);
            if (t == null || t.optJSONObject("preview") == null) continue;
            JSONObject p = new JSONObject().put("task", t.optString("id")).put("name", t.optString("name"));
            for (String key : new String[]{"url", "host_port", "container_port", "guest_port", "lan_url", "preview_error"})
                if (t.has(key)) p.put(key, t.get(key));
            out.put(p);
        }
        return out;
    }

    /** Adds URLs to a run result and drops forwards that the guest released on run/cancel/cleanup. */
    private void published(String op, JSONObject result, int generation) {
        JSONArray released = result.optJSONArray("released_guest_ports");
        if (released != null) for (int i = 0; i < released.length(); i++) unforward(released.optInt(i), generation);
        JSONObject preview = result.optJSONObject("preview");
        if (!"run".equals(op) || preview == null) return;
        try {
            Forward f = forward(preview.getInt("guest_port"), generation);
            describe(result, f, preview.getInt("container_port"));
            describe(preview, f, preview.getInt("container_port"));
        } catch (Exception e) {
            try { result.put("preview_error", e.getMessage()); } catch (JSONException ignored) {}
        }
    }

    private void syncForwards(JSONObject status, int generation) {
        JSONArray tasks = status.optJSONArray("tasks");
        Set<Integer> wanted = new HashSet<>();
        if (tasks != null) for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i), preview = task == null ? null : task.optJSONObject("preview");
            if (preview == null) continue;
            int guestPort = preview.optInt("guest_port", -1);
            wanted.add(guestPort);
            try {
                try { describe(task, forward(guestPort, generation), preview.optInt("container_port")); }
                catch (Exception e) { task.put("preview_error", e.getMessage()); }
            } catch (JSONException ignored) {}
        }
        List<Integer> stale = new ArrayList<>();
        synchronized (forwarding) {
            if (forwardGeneration == generation) for (Integer port : forwards.keySet()) if (!wanted.contains(port)) stale.add(port);
        }
        for (int port : stale) unforward(port, generation);
    }

    private void describe(JSONObject target, Forward f, int containerPort) throws JSONException {
        target.put("url", "http://127.0.0.1:" + f.host).put("host_port", f.host)
                .put("container_port", containerPort).put("guest_port", f.guest);
        if ("0.0.0.0".equals(f.bind)) {
            String address = wifiAddress(context);
            target.put("lan_url", address == null ? JSONObject.NULL : "http://" + address + ":" + f.host);
        }
    }

    /**
     * Ensures one QEMU user-network forward for a guest port. Loopback 8080 reuses the boot forward on
     * PREVIEW_PORT (the legacy URL); everything else is added with QMP {@code hostfwd_add} from
     * 18800–18899, preferring 18800 + (guest − 8080). A setting change re-binds the forward.
     */
    private Forward forward(int guestPort, int generation) throws Exception {
        if (guestPort < GUEST_PREVIEW_FIRST || guestPort > GUEST_PREVIEW_LAST) throw new IOException("Nieprawidłowy port podglądu w Linuxie: " + guestPort);
        String bind = lanExposure(context) ? "0.0.0.0" : "127.0.0.1";
        synchronized (forwarding) {
            if (forwardGeneration != generation) { forwards.clear(); forwardRetry.clear(); forwardGeneration = generation; }
            Forward current = forwards.get(guestPort);
            if (current != null && current.bind.equals(bind)) return current;
            if (current != null) { forwards.remove(guestPort); if (!current.boot) removeForward(current); }
            if (guestPort == GUEST_PREVIEW_FIRST && "127.0.0.1".equals(bind)) {
                Forward legacy = new Forward(guestPort, PREVIEW_PORT, bind, true);
                forwards.put(guestPort, legacy);
                return legacy;
            }
            Long retry = forwardRetry.get(guestPort);
            if (retry != null && SystemClock.elapsedRealtime() < retry) throw new IOException(lastForwardError);
            Set<Integer> taken = new HashSet<>();
            for (Forward f : forwards.values()) taken.add(f.host);
            int preferred = HOST_PREVIEW_FIRST + guestPort - GUEST_PREVIEW_FIRST;
            String failure = "Brak wolnego portu " + HOST_PREVIEW_FIRST + "–" + HOST_PREVIEW_LAST + " dla podglądu";
            for (int n = 0; n <= HOST_PREVIEW_LAST - HOST_PREVIEW_FIRST; n++) {
                int host = HOST_PREVIEW_FIRST + (preferred - HOST_PREVIEW_FIRST + n) % (HOST_PREVIEW_LAST - HOST_PREVIEW_FIRST + 1);
                if (taken.contains(host)) continue;
                if (epoch != generation) throw new IOException("Środowisko zostało zatrzymane");
                String reply = hmp("hostfwd_add " + NETDEV + " tcp:" + bind + ":" + host + "-:" + guestPort);
                if (reply.isEmpty()) {
                    Forward added = new Forward(guestPort, host, bind, false);
                    forwards.put(guestPort, added); forwardRetry.remove(guestPort);
                    return added;
                }
                // Another Android app holds this host port: try the next one.
                if (!reply.startsWith("Could not set up host forwarding rule")) { failure = "QEMU: " + reply; break; }
            }
            lastForwardError = failure;
            forwardRetry.put(guestPort, SystemClock.elapsedRealtime() + 30000);
            throw new IOException(failure);
        }
    }

    private void unforward(int guestPort, int generation) {
        synchronized (forwarding) {
            if (forwardGeneration != generation) return;
            forwardRetry.remove(guestPort);
            Forward f = forwards.remove(guestPort);
            if (f != null && !f.boot) removeForward(f);
        }
    }

    private void removeForward(Forward f) {
        try { hmp("hostfwd_remove " + NETDEV + " tcp:" + f.bind + ":" + f.host); }
        catch (Exception e) { lastForwardError = "hostfwd_remove: " + e.getMessage(); }
    }

    /** HMP through QMP: slirp reports hostfwd failures as the returned text, not as a QMP error. */
    private String hmp(String line) throws Exception {
        return qmp("human-monitor-command", new JSONObject().put("command-line", line)).optString("return", "").trim();
    }

    /** The phone's IPv4 on Wi-Fi/Ethernet, or on its own hotspot; null when there is none. */
    static String wifiAddress(Context c) {
        try {
            android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
            if (cm != null) for (android.net.Network network : cm.getAllNetworks()) {
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null || !(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
                android.net.LinkProperties link = cm.getLinkProperties(network);
                if (link != null) for (android.net.LinkAddress address : link.getLinkAddresses())
                    if (address.getAddress() instanceof Inet4Address && !address.getAddress().isLoopbackAddress())
                        return address.getAddress().getHostAddress();
            }
        } catch (RuntimeException ignored) {}
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = nic.getName();
                if (!nic.isUp() || !(name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("eth"))) continue;
                for (InetAddress address : Collections.list(nic.getInetAddresses()))
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) return address.getHostAddress();
            }
        } catch (Exception ignored) {}
        return null;
    }
    private JSONObject requestRaw(JSONObject args, int timeout) throws Exception {
        HttpURLConnection connection = connect("/request", timeout);
        try {
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] data = args.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(data.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(data); }
            int code = connection.getResponseCode();
            String text = readBounded(code == 200 ? connection.getInputStream() : connection.getErrorStream(), 1024 * 1024);
            JSONObject result = new JSONObject(text);
            if (code != 200 || result.has("error")) throw new IOException(result.optString("error", "Błąd środowiska " + code));
            return result;
        } finally { connection.disconnect(); }
    }
    private HttpURLConnection connect(String path, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + API_PORT + path).openConnection(Proxy.NO_PROXY);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Authorization", "Bearer " + token);
        // Each VM request owns its connection. A connection pooled across
        // QEMU user-networking requests can already have been closed by Linux.
        c.setRequestProperty("Connection", "close");
        c.setConnectTimeout(5000); c.setReadTimeout(timeout);
        return c;
    }
    JSONObject export(String task, String path) throws Exception {
        if (!"ready".equals(state)) throw new IOException("Środowisko jest zatrzymane");
        HttpURLConnection c = connect("/artifact?task=" + URLEncoder.encode(task, "UTF-8") + "&path=" + URLEncoder.encode(path, "UTF-8"), 120000);
        File dir = new File(context.getFilesDir(), "docker-exports"); dir.mkdirs();
        String display = new File(path).getName();
        String ext = display.contains(".") ? display.substring(display.lastIndexOf('.')) : ".bin";
        if (!ext.matches("\\.[A-Za-z0-9]{1,12}")) ext = ".bin";
        String id = UUID.randomUUID().toString().replace("-", "") + ext;
        File target = new File(dir, id);
        try {
            if (c.getResponseCode() != 200) throw new IOException(readBounded(c.getErrorStream(), 8192));
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(target)) { copy(in, out, 128L * 1024 * 1024); }
            JSONObject result = new JSONObject().put("id", id).put("name", display).put("task", task)
                    .put("bytes", target.length()).put("uri", FilesProvider.dockerUri(id).toString());
            try (OutputStream out = new FileOutputStream(new File(dir, id + ".json"))) { out.write(result.toString().getBytes(StandardCharsets.UTF_8)); }
            return result;
        } catch (Exception e) { target.delete(); throw e; }
        finally { c.disconnect(); }
    }
    static File exportedFile(Context c, String id) {
        if (!id.matches("[a-f0-9]{32}\\.[A-Za-z0-9]{1,12}")) return null;
        File f = new File(c.getFilesDir(), "docker-exports/" + id);
        return f.isFile() ? f : null;
    }
    JSONArray exports() {
        JSONArray list = new JSONArray();
        File[] files = new File(context.getFilesDir(), "docker-exports").listFiles();
        if (files != null) for (File file : files) if (file.getName().endsWith(".json")) {
            try { list.put(new JSONObject(readBounded(new FileInputStream(file), 16384))); } catch (Exception ignored) {}
        }
        return list;
    }
    void stop(boolean graceful) {
        final int generation;final Process running;
        synchronized (lifecycle) {
            generation=++epoch;pendingStops++;running=process;state="stopping";detail="Zatrzymywanie środowiska";
            diagnostics.finish(graceful ? "user_stop" : "forced_stop", null, detail);
        }
        try {
            UsbDocker.closeVm(context);
            if (graceful && running!=null && running.isAlive() && epoch==generation) {
                try { requestRaw(new JSONObject().put("op", "shutdown"), 12000); } catch (Exception ignored) {}
                try { running.waitFor(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
        } finally {
            synchronized (lifecycle) {
                // Keep start disabled until every old shutdown request completes.
                // Otherwise a late HTTP shutdown could reach a new VM on this port.
                if(epoch==generation) {kill();guest=new JSONObject();detail="Środowisko zatrzymane";}
                if(--pendingStops==0){stoppedEpoch=epoch;state="stopped";}
            }
        }
    }
    void emergencyStop() {
        synchronized (lifecycle) { ++epoch;pendingStops++;diagnostics.finish("emergency_stop", null, "Przerwano pracę środowiska");kill();guest=new JSONObject();state="stopping";detail="Przerwano pracę środowiska"; }
        try {UsbDocker.closeVm(context);}
        finally {synchronized (lifecycle) {if(--pendingStops==0){stoppedEpoch=epoch;state="stopped";}}}
    }
    private void kill() {
        Process p = process;
        if (p != null) {
            try {
                if(!p.isAlive()){process=null;return;}
                String pid = readBounded(new FileInputStream(new File(directory, "vm.pid")), 32).trim();
                android.os.Process.sendSignal(Integer.parseInt(pid), 9);
            } catch (Exception ignored) {}
            p.destroyForcibly(); process = null;
        }
    }
    JSONObject qmp(String command,JSONObject arguments)throws Exception{
        android.net.LocalSocket socket=new android.net.LocalSocket();
        try{
            socket.connect(new android.net.LocalSocketAddress(new File(directory,"qmp.sock").getAbsolutePath(),android.net.LocalSocketAddress.Namespace.FILESYSTEM));socket.setSoTimeout(10000);
            java.io.BufferedReader in=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            String greeting=in.readLine();if(greeting==null||!new JSONObject(greeting).has("QMP"))throw new IOException("Missing QMP greeting");
            java.io.OutputStream out=socket.getOutputStream();
            for(String execute:new String[]{"qmp_capabilities",command}){
                String id=UUID.randomUUID().toString();JSONObject request=new JSONObject().put("execute",execute).put("id",id);if(execute.equals(command)&&arguments!=null)request.put("arguments",arguments);
                out.write((request.toString()+"\n").getBytes(StandardCharsets.UTF_8));out.flush();
                while(true){String line=in.readLine();if(line==null||line.length()>1024*1024)throw new IOException("QMP disconnected or response exceeds limit");JSONObject reply=new JSONObject(line);if(!id.equals(reply.optString("id")))continue;if(reply.has("error"))throw new IOException(reply.getJSONObject("error").optString("desc"));if(execute.equals(command))return reply;break;}
            }throw new IOException("QMP returned no response");
        }finally{socket.close();}
    }
    void reset() throws Exception {
        synchronized (lifecycle) {
        if (alive() || "preparing".equals(state) || "booting".equals(state) || "stopping".equals(state)) throw new IOException("Najpierw zatrzymaj środowisko");
        // Only this app-owned disk. Exported files and model accounts live elsewhere.
        File overlay = new File(directory, "user.qcow2");
        if (overlay.exists() && !overlay.delete()) throw new IOException("Nie można usunąć dysku");
        guest = new JSONObject(); detail = "Usunięto zadania i cache Dockera";
        }
    }
    private static String readBounded(InputStream in, int max) throws IOException {
        if (in == null) throw new IOException("Brak odpowiedzi");
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) { copy(stream, out, max); return new String(out.toByteArray(), StandardCharsets.UTF_8); }
    }
    private static void copy(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buffer = new byte[65536]; long count = 0; int n;
        while ((n = in.read(buffer)) != -1) { count += n; if (count > max) throw new IOException("Plik przekracza limit rozmiaru"); out.write(buffer, 0, n); }
    }
    private static String hex(byte[] data) { StringBuilder s = new StringBuilder(); for (byte b : data) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return s.toString(); }
}
