package dev.mateuszsury.androidagent;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Owner-approved long-running services (web servers, bots, watchers) in the embedded userland.
 *
 * <p>Each service is its own PRoot process tree started by the app — not by an agent run — so it
 * survives the agent's turn (whose PRoot is started with --kill-on-exit), the chat's Stop and the
 * global emergency Stop (they only end processes in {@code EmbeddedRuntime.helperProcesses},
 * terminal sessions and approved one-off commands). Only this manager stops a service: the
 * "Usługi w tle" screen, the notification's "Zatrzymaj wszystkie" action or the service_* tools.
 * Definitions persist in {@code files/services/registry.json}; output goes to a rotating
 * {@code files/services/NAME.log} (512 KB × 2).
 */
final class ServiceManager {
    static final String NOT_PREPARED = "Środowisko nie jest przygotowane";
    static final String DEFAULT_CWD = "~/.buddy/work";
    static final String STARTING = "starting", RUNNING = "running", EXITED = "exited", FAILED = "failed",
            STOPPED = "stopped";
    static final String RESTART_NEVER = "never", RESTART_ON_FAILURE = "on-failure";
    static final int MAX_SERVICES = 16, MAX_RESTARTS = 5, MAX_COMMAND = 8192, LOG_LIMIT = 512 * 1024,
            TAIL_LIMIT = 64 * 1024;
    static final long RESTART_WINDOW_MS = 10 * 60 * 1000L;
    private static final long STOP_GRACE_MS = 5000, RUNNING_AFTER_MS = 2000, GUESS_PORTS_MS = 60000;
    /** Probed (only while /proc/net is unreadable) to find a server's port when none was declared. */
    private static final int[] COMMON_PORTS = {3000, 3001, 4000, 4200, 5000, 5173, 5500, 8000, 8008, 8080,
            8081, 8443, 8888, 9000};
    private static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    interface Listener {
        void onServicesChanged();
    }

    /** What the owner approved: everything needed to start the service again. */
    static final class Definition {
        String name = "", command = "", cwd = DEFAULT_CWD, restart = RESTART_ON_FAILURE;
        int port;
        boolean autostart;
        final TreeMap<String, String> env = new TreeMap<>();

        void validate() {
            validateName(name);
            if (command == null || command.trim().isEmpty())
                throw new IllegalArgumentException("Command is empty. (Wpisz komendę usługi.)");
            if (command.length() > MAX_COMMAND || command.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Command is too long or contains NUL. (Komenda jest za długa.)");
            if (cwd == null || cwd.trim().isEmpty()) cwd = DEFAULT_CWD;
            cwd = cwd.trim();
            if (cwd.length() > 512 || cwd.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Working directory path is invalid. (Nieprawidłowy katalog.)");
            if (port != 0 && (port < 1024 || port > 65535))
                throw new IllegalArgumentException("Port must be 1024–65535: ports below 1024 need root. "
                        + "(Port musi być w zakresie 1024–65535.)");
            if (!RESTART_NEVER.equals(restart) && !RESTART_ON_FAILURE.equals(restart))
                throw new IllegalArgumentException("restart must be \"never\" or \"on-failure\".");
            if (env.size() > 32) throw new IllegalArgumentException("At most 32 environment variables.");
            for (Map.Entry<String, String> e : env.entrySet()) {
                String k = e.getKey();
                if (!ENV_KEY.matcher(k).matches() || k.startsWith("LD_") || k.startsWith("PROOT_")
                        || k.startsWith("ANDROIDAGENT_") || k.startsWith("TERMUX") || "HOME".equals(k) || "PREFIX".equals(k))
                    throw new IllegalArgumentException("Environment variable \"" + k + "\" is not allowed.");
                if (e.getValue() == null || e.getValue().length() > 4096 || e.getValue().indexOf('\0') >= 0)
                    throw new IllegalArgumentException("Environment value of \"" + k + "\" is too long or invalid.");
            }
        }

        /** A stable fingerprint of every field (for change detection). */
        String key() {
            return name + '\0' + command + '\0' + cwd + '\0' + port + autostart + restart + env;
        }

        boolean sameAs(Definition o) {
            return o != null && name.equals(o.name) && command.equals(o.command) && cwd.equals(o.cwd)
                    && restart.equals(o.restart) && port == o.port && autostart == o.autostart && env.equals(o.env);
        }

        /** The exact text the owner approves (the command is last and verbatim). */
        String approvalText(boolean replacing) {
            StringBuilder s = new StringBuilder();
            s.append(replacing ? "Zastąpić i uruchomić usługę w tle „" : "Uruchomić usługę w tle „").append(name).append("”?\n\n");
            s.append("Będzie działać po zakończeniu pracy agenta, aż ją zatrzymasz (Usługi w tle).\n");
            s.append("Katalog: ").append(cwd).append('\n');
            s.append("Port: ").append(port == 0 ? "—" : String.valueOf(port));
            s.append(" · autostart: ").append(autostart ? "tak" : "nie");
            s.append(" · restart: ").append(RESTART_ON_FAILURE.equals(restart) ? "po błędzie" : "nie").append('\n');
            for (Map.Entry<String, String> e : env.entrySet()) s.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            s.append('\n').append(command);
            return s.toString();
        }

        JSONObject json() throws JSONException {
            JSONObject e = new JSONObject();
            for (Map.Entry<String, String> v : env.entrySet()) e.put(v.getKey(), v.getValue());
            return new JSONObject().put("name", name).put("command", command).put("cwd", cwd).put("port", port)
                    .put("autostart", autostart).put("restart", restart).put("env", e);
        }

        static Definition from(JSONObject j) {
            Definition d = new Definition();
            d.name = j.optString("name");
            d.command = j.optString("command");
            d.cwd = j.optString("cwd", DEFAULT_CWD);
            d.port = j.optInt("port", 0);
            d.autostart = j.optBoolean("autostart", false);
            d.restart = j.optString("restart", RESTART_ON_FAILURE);
            JSONObject e = j.optJSONObject("env");
            if (e != null) for (Iterator<String> it = e.keys(); it.hasNext(); ) {
                String k = it.next();
                d.env.put(k, e.optString(k));
            }
            return d;
        }
    }

    /** A consistent, immutable view of one service for the UI and tools. */
    static final class Status {
        Definition def;
        String owner = "", state = STOPPED, error = "";
        int pid, exitCode = Integer.MIN_VALUE, restarts;
        boolean wanted, portOpen, lanReachable, loopbackOnly, portsFromProc;
        long created, startedAtWall, uptimeMs, restartInMs;
        List<Integer> ports = new ArrayList<>();
        /** {url, label}: phone loopback first, then each LAN address. */
        List<String[]> urls = new ArrayList<>();

        boolean active() {
            return STARTING.equals(state) || RUNNING.equals(state);
        }

        /** The port URLs point at: the declared one, else the first detected. */
        int mainPort() {
            return def.port != 0 ? def.port : ports.isEmpty() ? 0 : ports.get(0);
        }

        JSONObject json() throws JSONException {
            JSONObject j = def.json();
            JSONArray envKeys = new JSONArray();
            for (String k : def.env.keySet()) envKeys.put(k);
            j.remove("env");
            j.put("env_keys", envKeys).put("state", state).put("owner", owner).put("error", error)
                    .put("pid", pid == 0 ? JSONObject.NULL : (Object) pid)
                    .put("uptime_seconds", active() && startedAtWall > 0 ? uptimeMs / 1000 : 0)
                    .put("started_at", startedAtWall > 0 ? iso(startedAtWall) : JSONObject.NULL)
                    .put("exit_code", exitCode == Integer.MIN_VALUE ? JSONObject.NULL : (Object) exitCode)
                    .put("restarts", restarts).put("autostart_enabled", def.autostart && wanted);
            if (restartInMs > 0) j.put("restart_in_seconds", (restartInMs + 999) / 1000);
            JSONArray ps = new JSONArray();
            for (int p : ports) ps.put(p);
            j.put("listening_ports", ps).put("port_open", portOpen);
            if (mainPort() != 0) j.put("reachable_from_lan", lanReachable).put("loopback_only", loopbackOnly);
            JSONArray us = new JSONArray();
            for (String[] u : urls) us.put(u[0]);
            j.put("urls", us).put("log", "files/services/" + def.name + ".log");
            return j;
        }
    }

    private static final class Service {
        Definition def;
        String owner = "";
        long created;
        boolean wanted;
        // Runtime state, guarded by the manager.
        String state = STOPPED, error = "";
        int exitCode = Integer.MIN_VALUE, restarts, pid, mainPid;
        long generation, startedElapsed, startedWall, restartAt;
        boolean stopping, portOpen, lanReachable, loopbackOnly, portsFromProc;
        Process process;
        ScheduledFuture<?> pendingRestart;
        final ArrayDeque<Long> restartTimes = new ArrayDeque<>();
        Set<Integer> baseline = Collections.emptySet();
        List<Integer> ports = new ArrayList<>();
        int probeSkip;
        LogFile log;
    }

    private static ServiceManager instance;

    static synchronized ServiceManager get(Context c) {
        if (instance == null) instance = new ServiceManager(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final File dir, registry;
    private final Map<String, Service> services = new LinkedHashMap<>();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "androidagent-services");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "androidagent-services-monitor");
        t.setDaemon(true);
        return t;
    });
    private final Object monitorLock = new Object();
    private ScheduledFuture<?> monitor;
    private volatile List<NetAddresses.Address> network = Collections.emptyList();
    private volatile String foregroundError = "";
    private volatile boolean restored, restorePending;
    private String launcher;

    private ServiceManager(Context c) {
        app = c;
        dir = new File(c.getFilesDir(), "services");
        registry = new File(dir, "registry.json");
        load();
    }

    // ------------------------------------------------------------------ queries

    static void validateName(String name) {
        if (name == null || !NAME.matcher(name).matches())
            throw new IllegalArgumentException("Invalid service name \"" + name + "\": use 1–32 characters a-z, 0-9 "
                    + "and '-', e.g. \"web\". (Nazwa: małe litery, cyfry i „-”.)");
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    synchronized List<Status> list() {
        List<Status> out = new ArrayList<>();
        for (Service s : services.values()) out.add(status(s));
        return out;
    }

    synchronized Status status(String name) {
        Service s = services.get(name);
        return s == null ? null : status(s);
    }

    synchronized int activeCount() {
        int n = 0;
        for (Service s : services.values()) if (STARTING.equals(s.state) || RUNNING.equals(s.state)) n++;
        return n;
    }

    synchronized List<Status> active() {
        List<Status> out = new ArrayList<>();
        for (Service s : services.values()) if (STARTING.equals(s.state) || RUNNING.equals(s.state)) out.add(status(s));
        return out;
    }

    /** The cached network view (refreshed by the monitor and {@link #refreshNetwork}). */
    List<NetAddresses.Address> network() {
        return network;
    }

    void refreshNetwork() {
        List<NetAddresses.Address> fresh = NetAddresses.read(app);
        if (!sameIps(fresh, network)) {
            network = fresh;
            changed();
        }
    }

    /** Empty, or why Android refused the notification that keeps services alive. */
    String foregroundError() {
        return foregroundError;
    }

    /** Whether the embedded userland exists; never throws (dev builds ship no runtime). */
    boolean runtimeReady() {
        try {
            return EmbeddedRuntime.get(app).ready();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Tail of the service's log (current + rotated file), at most {@link #TAIL_LIMIT} bytes. */
    String logs(String name, int lines) throws IOException {
        validateName(name);
        File current = logFile(name), old = new File(current.getPath() + ".1");
        lines = Math.max(1, Math.min(lines, 5000));
        byte[] tail = readTail(current, TAIL_LIMIT);
        boolean cut = current.length() > tail.length;
        if (tail.length < TAIL_LIMIT && old.isFile()) {
            byte[] earlier = readTail(old, TAIL_LIMIT - tail.length);
            cut = old.length() > earlier.length;
            byte[] both = new byte[earlier.length + tail.length];
            System.arraycopy(earlier, 0, both, 0, earlier.length);
            System.arraycopy(tail, 0, both, earlier.length, tail.length);
            tail = both;
        }
        String text = new String(tail, StandardCharsets.UTF_8);
        if (cut) {
            int nl = text.indexOf('\n');
            text = nl < 0 ? "" : text.substring(nl + 1);
        }
        List<String> all = new ArrayList<>();
        Collections.addAll(all, text.split("\n", -1));
        if (!all.isEmpty() && all.get(all.size() - 1).isEmpty()) all.remove(all.size() - 1);
        int from = Math.max(0, all.size() - lines);
        StringBuilder out = new StringBuilder();
        for (int i = from; i < all.size(); i++) out.append(all.get(i)).append('\n');
        return out.toString();
    }

    File logFile(String name) {
        return new File(dir, name + ".log");
    }

    // ---------------------------------------------------------------- lifecycle

    /** Create or replace a definition (the service must not be running). */
    void define(Definition d, String owner) {
        d.validate();
        synchronized (this) {
            Service s = services.get(d.name);
            if (s != null && (s.process != null || STARTING.equals(s.state) || RUNNING.equals(s.state)))
                throw new IllegalStateException("Service \"" + d.name + "\" is running; stop it first. (Usługa już działa.)");
            if (s == null && services.size() >= MAX_SERVICES)
                throw new IllegalStateException("At most " + MAX_SERVICES + " services; remove an unused one. (Limit usług.)");
            if (s == null) {
                s = new Service();
                s.created = System.currentTimeMillis();
                s.log = new LogFile(logFile(d.name));
                services.put(d.name, s);
            }
            s.def = d;
            s.owner = owner == null ? "" : owner;
            s.restarts = 0;
            s.restartTimes.clear();
            s.error = "";
            s.exitCode = Integer.MIN_VALUE;
            s.state = STOPPED;
            save();
        }
        changed();
    }

    synchronized Definition definition(String name) {
        Service s = services.get(name);
        return s == null ? null : s.def;
    }

    /** Start a registered service. Returns once the process is launched (or failed to launch). */
    void start(String name) {
        Service s;
        long gen;
        synchronized (this) {
            s = require(name);
            if (s.process != null || RUNNING.equals(s.state) || (STARTING.equals(s.state) && s.pendingRestart == null)) return;
            cancelRestart(s);
            s.wanted = true;
            s.restartTimes.clear();
            s.state = STARTING;
            s.error = "";
            gen = ++s.generation;
            save();
        }
        changed();
        launch(s, gen);
    }

    /** SIGTERM to the service's processes, SIGKILL to the whole PRoot tree after 5 s. Blocking. */
    void stop(String name) {
        Service s;
        Process p;
        synchronized (this) {
            s = require(name);
            cancelRestart(s);
            s.wanted = false;
            save();
            p = s.process;
            if (p == null) {
                s.generation++;
                if (STARTING.equals(s.state) || RUNNING.equals(s.state)) s.state = STOPPED;
                s.stopping = false;
            } else s.stopping = true;
        }
        changed();
        if (p == null) return;
        s.log.line("── zatrzymywanie · " + clock(System.currentTimeMillis()) + " ──");
        terminate(s, p);
    }

    void restart(String name) {
        stop(name);
        start(name);
    }

    void remove(String name) {
        stop(name);
        synchronized (this) {
            Service s = services.remove(name);
            if (s != null) {
                s.generation++;
                s.log.retire();
            }
            save();
        }
        logFile(name).delete();
        new File(logFile(name).getPath() + ".1").delete();
        changed();
    }

    /** The owner's "Zatrzymaj wszystkie usługi": stops every service in parallel. Blocking. */
    void stopAll() {
        List<String> names;
        synchronized (this) {
            names = new ArrayList<>(services.keySet());
        }
        List<Thread> threads = new ArrayList<>();
        for (String name : names) {
            Thread t = new Thread(() -> {
                try { stop(name); } catch (RuntimeException ignored) { }
            }, "androidagent-service-stop");
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            try { t.join(STOP_GRACE_MS + 4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** Run a blocking operation off the caller's thread; failures surface in the service state. */
    void async(Runnable action) {
        work.execute(() -> {
            try { action.run(); } catch (RuntimeException e) { android.util.Log.w("androidagentServices", "Operation failed", e); }
        });
    }

    /** Wait until a freshly started service settles (running with its port, or ended), at most timeoutMs. */
    Status awaitSettled(String name, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (true) {
            tick();
            Status st = status(name);
            if (st == null) return null;
            boolean settled = !st.active() || (RUNNING.equals(st.state) && (st.def.port == 0 || st.portOpen));
            if (settled || SystemClock.elapsedRealtime() >= deadline) return st;
            SystemClock.sleep(300);
        }
    }

    /** Start autostart services the owner left running; once per process unless deferred. */
    void restore() {
        List<String> names = new ArrayList<>();
        boolean ready = runtimeReady();
        synchronized (this) {
            if (restored) return;
            restored = true;
            restorePending = false;
            for (Service s : services.values())
                if (s.def.autostart && s.wanted && s.process == null && !RUNNING.equals(s.state)) names.add(s.def.name);
            if (names.isEmpty()) return;
            if (!ready) {
                for (String n : names) {
                    Service s = services.get(n);
                    s.state = FAILED;
                    s.error = NOT_PREPARED + " · autostart wstrzymany";
                }
                restored = false;
            } else {
                for (String n : names) {
                    Service s = services.get(n);
                    s.state = STARTING;
                    s.error = "";
                }
            }
        }
        if (!restored) {
            changed();
            return;
        }
        // Services run only with their ongoing notification; Android may refuse it from the background.
        if (!ensureForeground()) {
            synchronized (this) {
                restored = false;
                restorePending = true;
                for (String n : names) {
                    Service s = services.get(n);
                    if (s != null && STARTING.equals(s.state) && s.process == null) {
                        s.state = STOPPED;
                        s.error = "Autostart czeka na otwarcie aplikacji";
                    }
                }
            }
            changed();
            return;
        }
        for (String n : names) {
            async(() -> {
                Service s;
                long gen;
                synchronized (this) {
                    s = services.get(n);
                    if (s == null || s.process != null || !STARTING.equals(s.state)) return;
                    s.restartTimes.clear();
                    gen = ++s.generation;
                }
                launch(s, gen);
            });
        }
    }

    /** An app screen became visible: retry a deferred autostart (now allowed, or the runtime got ready). */
    void onAppVisible() {
        if (restored && !restorePending) return;
        async(this::restore);
    }

    // ------------------------------------------------------------------ process

    private void launch(Service s, long gen) {
        Definition d;
        synchronized (this) {
            d = s.def;
        }
        try {
            if (!runtimeReady()) throw new IllegalStateException(NOT_PREPARED);
            ProcessBuilder pb = EmbeddedRuntime.get(app).serviceCommand(launcher(), d.name, d.cwd, d.command);
            Map<String, String> env = pb.environment();
            if (d.port != 0) env.put("PORT", String.valueOf(d.port));
            env.putAll(d.env);
            Set<Integer> baseline = d.port == 0 ? listeningNow() : Collections.<Integer>emptySet();
            s.log.line("── start · " + clock(System.currentTimeMillis()) + " · " + d.cwd + " ──\n$ " + d.command);
            Process p = RuntimeProcess.start(pb);
            synchronized (this) {
                if (gen != s.generation) {
                    p.destroyForcibly();
                    return;
                }
                s.process = p;
                s.pid = 0;
                s.mainPid = 0;
                s.startedElapsed = SystemClock.elapsedRealtime();
                s.startedWall = System.currentTimeMillis();
                s.exitCode = Integer.MIN_VALUE;
                s.baseline = baseline;
                s.ports = new ArrayList<>();
                s.portOpen = s.lanReachable = s.loopbackOnly = s.portsFromProc = false;
                s.probeSkip = 0;
            }
            try { p.getOutputStream().close(); } catch (IOException ignored) { }
            Thread pump = new Thread(() -> pump(s, p), "androidagent-service-log");
            pump.setDaemon(true);
            pump.start();
            Thread watcher = new Thread(() -> watch(s, p, gen), "androidagent-service-wait");
            watcher.setDaemon(true);
            watcher.start();
            int pid = RuntimeProcess.pid(p);
            synchronized (this) {
                if (s.process == p) s.pid = Math.max(pid, 0);
            }
        } catch (Exception e) {
            String message = e instanceof IllegalStateException && NOT_PREPARED.equals(e.getMessage()) ? NOT_PREPARED
                    : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            synchronized (this) {
                if (gen != s.generation) return;
                s.process = null;
                s.state = FAILED;
                s.error = message;
            }
            s.log.line("── błąd uruchomienia · " + message + " ──");
        } finally {
            changed();
        }
    }

    private void pump(Service s, Process p) {
        byte[] buffer = new byte[8192];
        try (InputStream in = p.getInputStream()) {
            int n;
            while ((n = in.read(buffer)) != -1) s.log.write(buffer, 0, n);
        } catch (IOException ignored) {
            // The process ended or the stream was closed by a kill.
        }
    }

    private void watch(Service s, Process p, long gen) {
        int code;
        while (true) {
            try {
                code = p.waitFor();
                break;
            } catch (InterruptedException ignored) {
                // Keep waiting: this thread exists only to observe the exit.
            }
        }
        SystemClock.sleep(150); // let the pump drain the last lines before the exit banner
        String last = code == 0 ? "" : lastLine(s);
        long ran;
        String banner;
        synchronized (this) {
            if (s.process != p) return;
            s.process = null;
            s.pid = s.mainPid = 0;
            s.ports = new ArrayList<>();
            s.portOpen = s.lanReachable = s.loopbackOnly = false;
            s.exitCode = code;
            ran = SystemClock.elapsedRealtime() - s.startedElapsed;
            if (s.stopping || gen != s.generation) {
                s.stopping = false;
                s.state = STOPPED;
                s.error = "";
                banner = "── zatrzymano · po " + duration(ran) + " ──";
            } else if (RESTART_ON_FAILURE.equals(s.def.restart) && code != 0) {
                long now = SystemClock.elapsedRealtime();
                while (!s.restartTimes.isEmpty() && now - s.restartTimes.peekFirst() > RESTART_WINDOW_MS) s.restartTimes.pollFirst();
                if (s.restartTimes.size() >= MAX_RESTARTS) {
                    s.state = FAILED;
                    s.error = "Zbyt wiele awarii: " + MAX_RESTARTS + " ponownych uruchomień w 10 min. Sprawdź logi.";
                    banner = "── koniec · kod " + code + " · limit ponownych uruchomień ──";
                } else {
                    long delay = 1000L << s.restartTimes.size(); // 1, 2, 4, 8, 16 s
                    s.restartTimes.addLast(now);
                    s.restarts++;
                    s.state = STARTING;
                    s.error = last;
                    s.restartAt = now + delay;
                    long next = ++s.generation;
                    s.pendingRestart = timer.schedule(() -> async(() -> relaunch(s, next)), delay, TimeUnit.MILLISECONDS);
                    banner = "── koniec · kod " + code + " · ponowne uruchomienie za " + delay / 1000 + " s ──";
                }
            } else {
                s.state = EXITED;
                s.error = last;
                banner = "── koniec · kod " + code + " · po " + duration(ran) + " ──";
            }
        }
        s.log.line(banner);
        changed();
    }

    private void relaunch(Service s, long gen) {
        synchronized (this) {
            if (gen != s.generation || s.process != null || !STARTING.equals(s.state)) return;
            s.pendingRestart = null;
            s.restartAt = 0;
        }
        launch(s, gen);
    }

    private void cancelRestart(Service s) {
        if (s.pendingRestart != null) s.pendingRestart.cancel(false);
        s.pendingRestart = null;
        s.restartAt = 0;
    }

    private void terminate(Service s, Process p) {
        int root = RuntimeProcess.pid(p);
        if (root > 0) {
            // PRoot itself stays: it forwards the signal and ends the tree when the command exits.
            for (int pid : descendants(root)) {
                try { Os.kill(pid, OsConstants.SIGTERM); } catch (Exception ignored) { }
            }
        }
        try {
            if (!p.waitFor(root > 0 ? STOP_GRACE_MS : 0, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                p.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private String lastLine(Service s) {
        try {
            String[] lines = logs(s.def.name, 6).split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (!line.isEmpty() && !line.startsWith("──")) return line.length() > 200 ? line.substring(0, 200) + "…" : line;
            }
        } catch (IOException ignored) {
            // No log yet.
        }
        return "";
    }

    private synchronized String launcher() throws IOException {
        if (launcher == null) {
            try (InputStream in = app.getAssets().open("termux/service-launch.sh")) {
                launcher = new String(readAll(in, 16384), StandardCharsets.UTF_8);
            }
        }
        return launcher;
    }

    // ------------------------------------------------------------------ monitor

    private void scheduleMonitor() {
        synchronized (monitorLock) {
            if (monitor != null && !monitor.isDone()) return;
            if (activeCount() == 0) return;
            monitor = timer.scheduleWithFixedDelay(() -> {
                try { tick(); } catch (RuntimeException e) { android.util.Log.w("androidagentServices", "Monitor failed", e); }
            }, 400, 2000, TimeUnit.MILLISECONDS);
        }
    }

    /** Refresh pids, listening ports, LAN reachability and starting→running; cheap, idempotent. */
    void tick() {
        synchronized (monitorLock) {
            List<Service> live = new ArrayList<>();
            synchronized (this) {
                for (Service s : services.values()) if (s.process != null) live.add(s);
                if (live.isEmpty() && activeCount() == 0 && monitor != null) {
                    monitor.cancel(false);
                    monitor = null;
                }
            }
            List<NetAddresses.Address> net = NetAddresses.read(app);
            boolean netChanged = !sameIps(net, network);
            network = net;
            List<NetAddresses.Address> lan = NetAddresses.lan(net);
            List<long[]> table = live.isEmpty() ? null : readListeners();
            boolean changedAny = netChanged;
            Set<Integer> declared = new HashSet<>();
            synchronized (this) {
                for (Service s : services.values()) if (s.def.port != 0) declared.add(s.def.port);
            }
            for (Service s : live) {
                int pid, mainPid, port;
                long started;
                Set<Integer> baseline;
                List<Integer> known;
                int probeSkip;
                synchronized (this) {
                    pid = s.pid;
                    mainPid = s.mainPid;
                    port = s.def.port;
                    started = s.startedElapsed;
                    baseline = s.baseline;
                    known = new ArrayList<>(s.ports);
                    probeSkip = s.probeSkip;
                }
                List<Integer> tree = pid > 0 ? descendants(pid) : Collections.<Integer>emptyList();
                if (mainPid == 0 && !tree.isEmpty()) mainPid = tree.get(0);
                List<Integer> ports = new ArrayList<>();
                boolean fromProc = false, loopbackOnly = false, portOpen, probed = true;
                boolean lanBefore;
                synchronized (this) {
                    lanBefore = s.lanReachable;
                }
                if (table != null) {
                    Set<Long> inodes = socketInodes(tree);
                    Map<Integer, Boolean> found = new TreeMap<>();
                    for (long[] row : table) if (inodes.contains(row[1])) merge(found, (int) row[0], row[2] == 1);
                    if (found.isEmpty() && inodes.isEmpty()) {
                        // fd links unreadable: ports this app opened since the service started.
                        int uid = android.os.Process.myUid();
                        for (long[] row : table)
                            if (row[3] == uid && !baseline.contains((int) row[0]) && (port == 0 || row[0] == port)) merge(found, (int) row[0], row[2] == 1);
                    }
                    fromProc = true;
                    ports.addAll(found.keySet());
                    boolean allLoop = !found.isEmpty();
                    for (boolean loop : found.values()) allLoop &= loop;
                    loopbackOnly = allLoop;
                    portOpen = port != 0 ? found.containsKey(port) : !found.isEmpty();
                } else {
                    // /proc/net is closed to apps on Android 10+: probe the loopback interface instead.
                    long age = SystemClock.elapsedRealtime() - started;
                    if (port != 0) {
                        boolean wasOpen = known.contains(port);
                        if (!wasOpen || probeSkip <= 0) {
                            portOpen = probe("127.0.0.1", port);
                            probeSkip = portOpen ? 4 : 0;
                        } else {
                            portOpen = true;
                            probed = false;
                            probeSkip--;
                        }
                        if (portOpen) ports.add(port);
                    } else {
                        Set<Integer> candidates = new LinkedHashSet<>(known);
                        if (age < GUESS_PORTS_MS) for (int c : COMMON_PORTS) candidates.add(c);
                        for (int c : candidates)
                            if (!baseline.contains(c) && !declared.contains(c) && probe("127.0.0.1", c)) ports.add(c);
                        portOpen = !ports.isEmpty();
                    }
                }
                int main = port != 0 ? port : ports.isEmpty() ? 0 : ports.get(0);
                boolean lanOk = false;
                if (portOpen && main != 0 && !lan.isEmpty()) {
                    if (fromProc) lanOk = !loopbackOnly;
                    else {
                        // A server bound to 127.0.0.1 refuses the phone's own LAN address too.
                        lanOk = probed || netChanged ? probe(lan.get(0).ip, main) : lanBefore;
                        loopbackOnly = !lanOk;
                    }
                }
                synchronized (this) {
                    if (s.process == null) continue;
                    String before = s.state + s.pid + s.mainPid + s.ports + s.portOpen + s.lanReachable + s.loopbackOnly;
                    s.mainPid = mainPid;
                    s.ports = ports;
                    s.portsFromProc = fromProc;
                    s.portOpen = portOpen;
                    s.lanReachable = lanOk;
                    s.loopbackOnly = loopbackOnly && portOpen;
                    s.probeSkip = probeSkip;
                    if (STARTING.equals(s.state)
                            && (portOpen || SystemClock.elapsedRealtime() - s.startedElapsed >= RUNNING_AFTER_MS)) {
                        s.state = RUNNING;
                        s.error = "";
                    }
                    String after = s.state + s.pid + s.mainPid + s.ports + s.portOpen + s.lanReachable + s.loopbackOnly;
                    if (!before.equals(after)) changedAny = true;
                }
            }
            if (changedAny) changed();
        }
    }

    private static void merge(Map<Integer, Boolean> found, int port, boolean loopback) {
        Boolean prior = found.get(port);
        found.put(port, prior == null ? loopback : prior && loopback);
    }

    /** LISTEN sockets from /proc/net/tcp{,6} as {port, inode, loopback?1:0, uid}; null when unreadable. */
    static List<long[]> readListeners() {
        List<long[]> rows = new ArrayList<>();
        boolean any = false;
        for (String path : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            try (BufferedReader r = new BufferedReader(new FileReader(path))) {
                any = true;
                String line = r.readLine(); // header
                while ((line = r.readLine()) != null) {
                    long[] row = parseListener(line);
                    if (row != null) rows.add(row);
                }
            } catch (IOException | RuntimeException ignored) {
                // Denied for apps on Android 10+ (SELinux); the caller falls back to probing.
            }
        }
        return any ? rows : null;
    }

    /** One /proc/net/tcp line → {port, inode, loopback, uid} if it is a LISTEN socket, else null. */
    static long[] parseListener(String line) {
        String[] f = line.trim().split("\\s+");
        if (f.length < 10 || !"0A".equals(f[3])) return null;
        int colon = f[1].lastIndexOf(':');
        if (colon < 0) return null;
        try {
            String addr = f[1].substring(0, colon);
            long port = Long.parseLong(f[1].substring(colon + 1), 16);
            boolean loop = addr.length() == 8 ? addr.endsWith("7F")
                    : "00000000000000000000000001000000".equals(addr)
                    || (addr.startsWith("0000000000000000FFFF0000") && addr.endsWith("7F"));
            return new long[]{port, Long.parseLong(f[9]), loop ? 1 : 0, Long.parseLong(f[7])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Set<Integer> listeningNow() {
        Set<Integer> out = new HashSet<>();
        List<long[]> table = readListeners();
        if (table != null) {
            for (long[] row : table) out.add((int) row[0]);
        } else {
            for (int c : COMMON_PORTS) if (probe("127.0.0.1", c)) out.add(c);
        }
        return out;
    }

    private static boolean probe(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 400);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Own processes below root (children first-found order), by walking /proc ppid links. */
    static List<Integer> descendants(int root) {
        Map<Integer, List<Integer>> byParent = new HashMap<>();
        File[] entries = new File("/proc").listFiles();
        if (entries != null) for (File e : entries) {
            int pid;
            try { pid = Integer.parseInt(e.getName()); } catch (NumberFormatException ignored) { continue; }
            try (BufferedReader r = new BufferedReader(new FileReader(new File(e, "stat")))) {
                String line = r.readLine();
                String[] f = line.substring(line.lastIndexOf(')') + 2).split(" ");
                int parent = Integer.parseInt(f[1]);
                List<Integer> list = byParent.get(parent);
                if (list == null) byParent.put(parent, list = new ArrayList<>());
                list.add(pid);
            } catch (IOException | RuntimeException ignored) {
                // Exited meanwhile, or not ours.
            }
        }
        List<Integer> out = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(root);
        Set<Integer> seen = new HashSet<>();
        while (!queue.isEmpty() && out.size() < 512) {
            List<Integer> kids = byParent.get(queue.poll());
            if (kids == null) continue;
            Collections.sort(kids);
            for (int k : kids) if (seen.add(k)) {
                out.add(k);
                queue.add(k);
            }
        }
        return out;
    }

    private static Set<Long> socketInodes(List<Integer> pids) {
        Set<Long> out = new HashSet<>();
        for (int pid : pids) {
            File[] fds = new File("/proc/" + pid + "/fd").listFiles();
            if (fds == null) continue;
            for (File fd : fds) {
                try {
                    String target = Os.readlink(fd.getPath());
                    if (target.startsWith("socket:[")) out.add(Long.parseLong(target.substring(8, target.length() - 1)));
                } catch (Exception ignored) {
                    // Closed meanwhile.
                }
            }
        }
        return out;
    }

    // --------------------------------------------------------- foreground owner

    private void changed() {
        main.post(() -> {
            for (Listener l : listeners) l.onServicesChanged();
            syncForeground();
        });
        scheduleMonitor();
    }

    private void syncForeground() {
        if (activeCount() > 0 && !ServicesService.running()) ensureForeground();
        else ServicesService.refresh();
    }

    /** Start (or update) the ongoing notification that keeps running services alive. */
    boolean ensureForeground() {
        try {
            app.startForegroundService(new Intent(app, ServicesService.class));
            foregroundError = "";
            return true;
        } catch (RuntimeException e) {
            foregroundError = "Android nie pozwolił pokazać powiadomienia usług w tle. Otwórz aplikację, aby je przywrócić.";
            return false;
        }
    }

    // ------------------------------------------------------------------ storage

    private Service require(String name) {
        validateName(name);
        Service s = services.get(name);
        if (s == null)
            throw new IllegalArgumentException("Unknown service \"" + name + "\". Use service_list to see names. "
                    + "(Nie ma usługi „" + name + "”.)");
        return s;
    }

    private Status status(Service s) {
        Status st = new Status();
        st.def = s.def;
        st.owner = s.owner;
        st.state = s.state;
        st.error = s.error;
        st.pid = s.mainPid != 0 ? s.mainPid : s.pid;
        st.exitCode = s.exitCode;
        st.restarts = s.restarts;
        st.wanted = s.wanted;
        st.created = s.created;
        st.portOpen = s.portOpen;
        st.lanReachable = s.lanReachable;
        st.loopbackOnly = s.loopbackOnly;
        st.portsFromProc = s.portsFromProc;
        st.ports = new ArrayList<>(s.ports);
        if (s.process != null) {
            st.startedAtWall = s.startedWall;
            st.uptimeMs = SystemClock.elapsedRealtime() - s.startedElapsed;
        }
        if (s.restartAt > 0) st.restartInMs = Math.max(0, s.restartAt - SystemClock.elapsedRealtime());
        int port = st.mainPort();
        if (port != 0) {
            st.urls.add(new String[]{"http://127.0.0.1:" + port, "telefon"});
            for (NetAddresses.Address a : NetAddresses.lan(network)) st.urls.add(new String[]{"http://" + a.ip + ":" + port, a.label()});
        }
        return st;
    }

    private synchronized void load() {
        try {
            if (!registry.isFile()) return;
            JSONObject root = new JSONObject(new String(readAll(new FileInputStream(registry), 1024 * 1024), StandardCharsets.UTF_8));
            JSONArray list = root.optJSONArray("services");
            if (list == null) return;
            for (int i = 0; i < list.length() && services.size() < MAX_SERVICES; i++) {
                JSONObject j = list.optJSONObject(i);
                if (j == null) continue;
                Definition d = Definition.from(j);
                try { d.validate(); } catch (RuntimeException invalid) { continue; }
                Service s = new Service();
                s.def = d;
                s.owner = j.optString("owner");
                s.created = j.optLong("created", System.currentTimeMillis());
                s.wanted = j.optBoolean("wanted", false);
                s.log = new LogFile(logFile(d.name));
                services.put(d.name, s);
            }
        } catch (Exception e) {
            android.util.Log.w("androidagentServices", "Registry unreadable", e);
        }
    }

    private synchronized void save() {
        try {
            JSONArray list = new JSONArray();
            for (Service s : services.values())
                list.put(s.def.json().put("owner", s.owner).put("created", s.created).put("wanted", s.wanted));
            dir.mkdirs();
            File tmp = new File(dir, "registry.json.new");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(new JSONObject().put("version", 1).put("services", list).toString(1).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            if (!tmp.renameTo(registry)) throw new IOException("rename failed");
        } catch (Exception e) {
            android.util.Log.w("androidagentServices", "Registry not saved", e);
        }
    }

    /** Append-only log with one rotated generation: NAME.log + NAME.log.1, each ≤ 512 KB. */
    static final class LogFile {
        private final File file, old;
        private OutputStream out;
        private long size = -1;
        private boolean retired;

        LogFile(File file) {
            this.file = file;
            this.old = new File(file.getPath() + ".1");
        }

        synchronized void write(byte[] b, int off, int len) {
            if (retired) return; // removed: a draining pump must not recreate the file
            try {
                if (out == null) {
                    file.getParentFile().mkdirs();
                    out = new FileOutputStream(file, true);
                    size = file.length();
                }
                if (size + len > LOG_LIMIT && size > 0) {
                    out.close();
                    old.delete();
                    if (!file.renameTo(old)) file.delete();
                    out = new FileOutputStream(file, false);
                    size = 0;
                }
                out.write(b, off, len);
                size += len;
            } catch (IOException e) {
                close();
            }
        }

        void line(String text) {
            byte[] b = ("\n" + text + "\n").getBytes(StandardCharsets.UTF_8);
            write(b, 0, b.length);
        }

        synchronized void close() {
            if (out != null) try { out.close(); } catch (IOException ignored) { }
            out = null;
        }

        synchronized void retire() {
            close();
            retired = true;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] readTail(File f, int max) throws IOException {
        if (!f.isFile() || max <= 0) return new byte[0];
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            int n = (int) Math.min(len, max);
            byte[] b = new byte[n];
            r.seek(len - n);
            r.readFully(b);
            return b;
        }
    }

    private static byte[] readAll(InputStream input, int limit) throws IOException {
        try (InputStream in = input) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) {
                if (out.size() + n > limit) throw new IOException("File too large");
                out.write(b, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static boolean sameIps(List<NetAddresses.Address> a, List<NetAddresses.Address> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).ip.equals(b.get(i).ip) || !a.get(i).type.equals(b.get(i).type)) return false;
        return true;
    }

    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + " s";
        long m = s / 60;
        if (m < 60) return m + " min";
        long h = m / 60;
        if (h < 24) return h + " h " + (m % 60) + " min";
        return (h / 24) + " d " + (h % 24) + " h";
    }

    private static String clock(long wall) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(wall));
    }

    private static String iso(long wall) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(wall));
    }

    /** "1 działa", "2 działają", "5 działa" — Polish agreement for a count of running services. */
    static String runningLabel(int n) {
        int tens = n % 100, ones = n % 10;
        return n + (n != 1 && ones >= 2 && ones <= 4 && (tens < 12 || tens > 14) ? " działają" : " działa");
    }
}
