package dev.mateuszsury.androidagent;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Own-UID native process with explicit SIGKILL, including Android's SIGTERM-only Process fallback. */
final class RuntimeProcess {
    private RuntimeProcess() {}

    static Process start(ProcessBuilder source) throws IOException {
        String temporaryPath = source.environment().get("PROOT_TMP_DIR");
        if (temporaryPath == null || temporaryPath.isEmpty())
            throw new IOException("Brak prywatnego katalogu procesu środowiska");
        File directory = new File(temporaryPath).getCanonicalFile();
        try {
            if (!directory.isDirectory() || Os.stat(directory.getPath()).st_uid != android.os.Process.myUid())
                throw new IOException("Katalog procesu nie należy do aplikacji");
        } catch (android.system.ErrnoException error) {
            throw new IOException("Nie można sprawdzić katalogu procesu", error);
        }
        if (source.command().isEmpty()) throw new IOException("Brak programu środowiska");
        File marker = File.createTempFile("runtime-", ".pid", directory);
        try {
            Os.chmod(marker.getPath(), 0600);
            // Read starttime before exec so a recycled PID cannot target another
            // process. All original arguments remain separate shell positional
            // arguments; neither command contents nor credentials are interpolated.
            String record = "umask 077; IFS= read -r ANDROIDAGENT_RUNTIME_STAT < /proc/$$/stat || exit 125; "
                + "printf '%s\\n%s\\n' \"$$\" \"$ANDROIDAGENT_RUNTIME_STAT\" > \"$ANDROIDAGENT_RUNTIME_PID_FILE\" || exit 125; "
                + "unset ANDROIDAGENT_RUNTIME_STAT ANDROIDAGENT_RUNTIME_PID_FILE; exec \"$@\"";
            List<String> arguments = new ArrayList<>();
            arguments.add("/system/bin/sh");
            arguments.add("-c");
            arguments.add(record);
            arguments.add("androidagent-runtime");
            arguments.addAll(source.command());
            ProcessBuilder wrapped = new ProcessBuilder(arguments);
            wrapped.environment().clear();
            wrapped.environment().putAll(source.environment());
            wrapped.environment().put("ANDROIDAGENT_RUNTIME_PID_FILE", marker.getPath());
            wrapped.directory(source.directory());
            wrapped.redirectErrorStream(source.redirectErrorStream());
            wrapped.redirectInput(source.redirectInput());
            wrapped.redirectOutput(source.redirectOutput());
            wrapped.redirectError(source.redirectError());
            return new ManagedProcess(wrapped.start(), marker);
        } catch (android.system.ErrnoException error) {
            marker.delete();
            throw new IOException("Nie można zabezpieczyć pliku procesu", error);
        } catch (IOException | RuntimeException error) {
            marker.delete();
            throw error;
        }
    }

    private static final class Identity {
        final int pid, parent;
        final long started;
        final char state;
        Identity(int pid, int parent, long started, char state) {
            this.pid = pid;
            this.parent = parent;
            this.started = started;
            this.state = state;
        }
    }

    private static Identity parseStat(String text) {
        if (text == null) return null;
        try {
            int end = text.lastIndexOf(')');
            String[] fields = text.substring(end + 2).trim().split("\\s+");
            if (end < 0 || fields.length < 20 || fields[0].length() != 1) return null;
            return new Identity(Integer.parseInt(text.substring(0, text.indexOf(' '))),
                Integer.parseInt(fields[1]), Long.parseLong(fields[19]), fields[0].charAt(0));
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Identity ownProcess(int pid) {
        if (pid <= 1 || pid == android.os.Process.myPid()) return null;
        File folder = new File("/proc/" + pid);
        try (BufferedReader status = new BufferedReader(new FileReader(new File(folder, "status")))) {
            boolean owned = false;
            String line;
            while ((line = status.readLine()) != null) {
                if (!line.startsWith("Uid:")) continue;
                String[] values = line.substring(4).trim().split("\\s+");
                if (values.length != 4) return null;
                for (String value : values) if (Integer.parseInt(value) != android.os.Process.myUid()) return null;
                owned = true;
                break;
            }
            if (!owned) return null;
            try (BufferedReader stat = new BufferedReader(new FileReader(new File(folder, "stat")))) {
                Identity result = parseStat(stat.readLine());
                return result != null && result.pid == pid ? result : null;
            }
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private static boolean sameProcess(Identity expected, boolean parentMustMatch) {
        Identity current = ownProcess(expected.pid);
        return current != null && current.started == expected.started
            && (!parentMustMatch || current.parent == expected.parent);
    }

    private static void signal(Identity process, int value) {
        if (!sameProcess(process, false)) return;
        try { Os.kill(process.pid, value); }
        catch (android.system.ErrnoException ignored) { }
    }

    private static Map<Integer, List<Identity>> children() {
        Map<Integer, List<Identity>> result = new HashMap<>();
        File[] folders = new File("/proc").listFiles();
        if (folders == null) return result;
        for (File folder : folders) {
            int pid;
            try { pid = Integer.parseInt(folder.getName()); }
            catch (NumberFormatException ignored) { continue; }
            Identity process = ownProcess(pid);
            if (process != null) {
                List<Identity> list = result.get(process.parent);
                if (list == null) { list = new ArrayList<>(); result.put(process.parent, list); }
                list.add(process);
            }
        }
        return result;
    }

    /** Freeze parents before discovery can continue; reversed preorder kills descendants first. */
    private static void freezeChildren(int parent, Map<Integer, List<Identity>> byParent,
                                       Set<Integer> visited, List<Identity> frozenPreorder) {
        List<Identity> list = byParent.get(parent);
        if (list == null) return;
        for (Identity process : list) {
            if (!visited.add(process.pid) || !sameProcess(process, true)) continue;
            // Record before stopping: cleanup retains the PID even if a later
            // scan fails or another cancellation reparents this known child.
            frozenPreorder.add(process);
            signal(process, OsConstants.SIGSTOP);
            freezeChildren(process.pid, byParent, visited, frozenPreorder);
        }
    }

    private static final class ManagedProcess extends Process {
        final Process delegate;
        final File marker;
        final AtomicBoolean stopWorkerStarted = new AtomicBoolean();
        volatile Identity root;

        ManagedProcess(Process process, File file) {
            delegate = process;
            marker = file;
            Thread watcher = new Thread(() -> {
                awaitRoot();
                boolean interrupted = false;
                while (true) {
                    try { delegate.waitFor(); break; }
                    catch (InterruptedException error) { interrupted = true; }
                }
                marker.delete();
                if (interrupted) Thread.currentThread().interrupt();
            }, "runtime-process-exit");
            watcher.setDaemon(true);
            watcher.start();
        }

        private Identity awaitRoot() {
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (root == null && delegate.isAlive() && SystemClock.elapsedRealtime() < deadline) {
                if (marker.length() > 0 && marker.length() < 8192) {
                    try (BufferedReader reader = new BufferedReader(new FileReader(marker))) {
                        int pid = Integer.parseInt(reader.readLine());
                        Identity recorded = parseStat(reader.readLine());
                        if (recorded != null && recorded.pid == pid && recorded.parent == android.os.Process.myPid()
                                && sameProcess(recorded, true)) root = recorded;
                    } catch (IOException | RuntimeException ignored) { }
                }
                if (root == null) SystemClock.sleep(10);
            }
            return root;
        }

        private void requestStop() {
            if (!stopWorkerStarted.compareAndSet(false, true)) return;
            Thread worker = new Thread(() -> {
                Identity process = awaitRoot();
                if (process == null) {
                    // Only use the Java fallback if no validated own child PID
                    // was obtained. Android may map this fallback to SIGTERM.
                    if (delegate.isAlive()) delegate.destroyForcibly();
                    return;
                }
                if (!sameProcess(process, true)) return;
                signal(process, OsConstants.SIGSTOP);
                // Re-scan after freezing to include children created just before
                // their parent received SIGSTOP; no unrelated app PID is used.
                List<Identity> frozenPreorder = new ArrayList<>();
                try {
                    for (int pass = 0; pass < 2; pass++) {
                        Set<Integer> visited = new HashSet<>();
                        visited.add(process.pid);
                        freezeChildren(process.pid, children(), visited, frozenPreorder);
                    }
                } finally {
                    Set<String> killed = new HashSet<>();
                    for (int index = frozenPreorder.size() - 1; index >= 0; index--) {
                        Identity child = frozenPreorder.get(index);
                        if (killed.add(child.pid + ":" + child.started)) signal(child, OsConstants.SIGKILL);
                    }
                    signal(process, OsConstants.SIGKILL);
                }
            }, "runtime-process-stop");
            worker.setDaemon(true);
            worker.start();
        }

        @Override public OutputStream getOutputStream() { return delegate.getOutputStream(); }
        @Override public InputStream getInputStream() { return delegate.getInputStream(); }
        @Override public InputStream getErrorStream() { return delegate.getErrorStream(); }
        @Override public int waitFor() throws InterruptedException { return delegate.waitFor(); }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException { return delegate.waitFor(timeout, unit); }
        @Override public int exitValue() { return delegate.exitValue(); }
        @Override public boolean isAlive() { return delegate.isAlive(); }
        @Override public void destroy() { requestStop(); }
        @Override public Process destroyForcibly() { requestStop(); return this; }
    }
}
