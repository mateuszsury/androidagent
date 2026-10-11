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
    private static DockerVm instance;
    static synchronized DockerVm get(Context c) {
        if (instance == null) instance = new DockerVm(c.getApplicationContext());
        return instance;
    }
    final Context context;
    final File directory;
    private volatile Process process;
    private volatile int epoch;
    volatile String state = "stopped", detail = "Gotowy do uruchomienia";
    volatile JSONObject guest = new JSONObject();
    private volatile String console = "";
    private final String token;
    private final String abi;
    final boolean emulatedAndroid;
    private final Object lifecycle = new Object();
    private final Object preparation = new Object();
    private int pendingStops;
    private DockerVm(Context c) {
        context = c;
        directory = new File(c.getFilesDir(), "docker");
        directory.mkdirs();
        abi = android.os.Build.SUPPORTED_ABIS[0];
        emulatedAndroid = "ranchu".equals(android.os.Build.HARDWARE) || "goldfish".equals(android.os.Build.HARDWARE);
        android.content.SharedPreferences prefs = c.getSharedPreferences("docker_vm", 0);
        String existing = prefs.getString("token", null);
        if (existing == null) {
            byte[] random = new byte[32]; new java.security.SecureRandom().nextBytes(random);
            existing = hex(random); prefs.edit().putString("token", existing).apply();
        }
        token = existing;
    }
    JSONObject status() throws JSONException {
        return new JSONObject().put("state", state).put("detail", detail).put("abi", abi)
                .put("ready", "ready".equals(state)).put("root_required", false)
                .put("clock_mode", emulatedAndroid ? "instruction_count" : "host")
                .put("preview_url", "http://127.0.0.1:" + PREVIEW_PORT)
                .put("guest", guest).put("host_free_bytes", directory.getUsableSpace());
    }
    String console() { return console; }
    boolean alive() { Process p = process; return p != null && p.isAlive(); }

    void boot(int ram, int cores) {
        final int generation;
        synchronized (lifecycle) {
            McpHost.get(context).requireReady();
            if (alive() || "preparing".equals(state) || "booting".equals(state) || "stopping".equals(state)) return;
            generation = ++epoch;
            state = "preparing"; detail = "Przygotowywanie środowiska"; console = "";
        }
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
                process = pb.start(); running = process; state = "booting"; detail = "Uruchamianie Linuxa i Dockera";
            }
            }
            Thread reader = new Thread(() -> {
                try (InputStream in = running.getInputStream()) {
                    byte[] buffer = new byte[2048]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        String text = new String(buffer, 0, n, StandardCharsets.UTF_8).replace(token, "[token]");
                        synchronized (this) { if(epoch != generation)break; console = console + text; if (console.length() > 65536) console = console.substring(console.length() - 65536); }
                    }
                } catch (Exception ignored) {}
            }, "docker-console");
            reader.setDaemon(true); reader.start();
            long deadline = SystemClock.elapsedRealtime() + (emulatedAndroid ? 600000 : 300000);
            while (epoch == generation && running.isAlive()) {
                try {
                    JSONObject result = requestRaw(new JSONObject().put("op", "status"), 12000);
                    synchronized (lifecycle) {
                        if (epoch != generation || process != running) break;
                        guest = result;
                        if (result.optBoolean("ready")) { state = "ready"; detail = "Docker działa na telefonie"; }
                    }
                } catch (Exception ignored) {}
                if (!"ready".equals(state) && SystemClock.elapsedRealtime() > deadline) throw new IOException("Linux nie uruchomił Dockera w wyznaczonym czasie. Sprawdź dziennik środowiska.");
                Thread.sleep(2000);
            }
            if (epoch == generation) throw new IOException("Środowisko zakończyło działanie. " + console.substring(Math.max(0, console.length() - 1000)));
        } catch (Exception e) {
            synchronized (lifecycle) {
                if (epoch == generation) {
                    kill(); state = "error"; detail = e.getMessage() == null ? e.toString() : e.getMessage();
                }
            }
        }
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
        if (new File(directory, "user.qcow2").exists()) throw new IOException("Nowa wersja środowiska wymaga resetu dysku. Najpierw zachowaj wyniki zadań.");
        if (directory.getUsableSpace() < 1024L * 1024 * 1024) throw new IOException("Przygotowanie środowiska wymaga co najmniej 1 GB wolnego miejsca.");
        JSONObject files = new JSONObject(manifest).getJSONObject("files");
        for (String name : Arrays.asList("base.qcow2.gz", "vmlinuz-virt", "initramfs-virt")) {
            if (epoch != generation) return;
            detail = "Rozpakowywanie środowiska Linux";
            File part = new File(directory, name + ".part");
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream in = context.getAssets().open(asset + name); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (epoch != generation) throw new IOException("Przerwano przygotowanie");
                    hash.update(buffer, 0, n); out.write(buffer, 0, n);
                }
            }
            if (!hex(hash.digest()).equals(files.getString(name))) { part.delete(); throw new IOException("Nieprawidłowa suma SHA256 środowiska"); }
            if (name.endsWith(".gz")) {
                File unpacked = new File(directory, "base.qcow2.part");
                try (InputStream in = new GZIPInputStream(new FileInputStream(part), 65536); OutputStream out = new FileOutputStream(unpacked)) {
                    byte[] buffer = new byte[65536]; int n; long total=0;
                    while ((n=in.read(buffer))!=-1) {
                        if(epoch!=generation)throw new IOException("Przerwano przygotowanie");
                        total+=n;if(total>1024L*1024*1024)throw new IOException("Obraz przekracza limit rozpakowania");
                        out.write(buffer,0,n);detail="Rozpakowywanie Linuxa: "+(total/1048576)+" MB";
                    }
                }
                if (epoch != generation) return;
                if (!unpacked.renameTo(new File(directory, "base.qcow2"))) throw new IOException("Nie można zapisać obrazu Linuxa");
                part.delete();
            } else if (!part.renameTo(new File(directory, name))) throw new IOException("Nie można zapisać kernela");
        }
        if (epoch != generation) return;
        copyAssets(asset + "qemu", new File(directory, "qemu"));
        try (OutputStream out = new FileOutputStream(marker)) { out.write(manifest.getBytes(StandardCharsets.UTF_8)); }
    }
    private void copyAssets(String source, File target) throws IOException {
        String[] children = context.getAssets().list(source);
        if (children != null && children.length > 0) {
            target.mkdirs(); for (String child : children) copyAssets(source + "/" + child, new File(target, child));
        } else {
            target.getParentFile().mkdirs();
            try (InputStream in = context.getAssets().open(source); OutputStream out = new FileOutputStream(target)) { copy(in, out, 32 * 1024 * 1024); }
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
        return result;
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
                if(--pendingStops==0)state="stopped";
            }
        }
    }
    void emergencyStop() {
        synchronized (lifecycle) { ++epoch;pendingStops++;kill();guest=new JSONObject();state="stopping";detail="Przerwano pracę środowiska"; }
        try {UsbDocker.closeVm(context);}
        finally {synchronized (lifecycle) {if(--pendingStops==0)state="stopped";}}
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
