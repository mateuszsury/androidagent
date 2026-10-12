package dev.mateuszsury.androidagent;

import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private, bounded boot evidence. Diagnostic failures never decide whether the VM runs. */
final class DockerDiagnostics {
    static final int MAX_CONSOLE_BYTES = 2 * 1024 * 1024;
    static final int MAX_GUEST_BYTES = 256 * 1024;
    static final int MAX_METADATA_BYTES = 32 * 1024;
    static final int MAX_READ_BYTES = 64 * 1024;
    static final int MAX_BOOTS = 4; // The current boot and its three predecessors.
    private static final String REDACTED = "[REDACTED]";
    private static final int MAX_SECRET_CHARS = 4096;

    private final File directory;
    private final String secret;
    private final Object io = new Object();
    private JSONObject metadata = new JSONObject();
    private volatile String cachedStatus = "{}";
    private volatile File bootDirectory;
    private File canonicalDirectory;
    private FileOutputStream console;
    private String redactionTail = "";
    private char incompleteSurrogate;
    private long consoleBytes, totalOutputBytes, guestBytes, lastMetadataWrite;
    private boolean truncated, sealed, disabled, available;
    private String diagnosticError = "";

    DockerDiagnostics(File directory, String secret) {
        this.directory = directory;
        this.secret = secret == null ? "" : secret;
        if (this.secret.length() > MAX_SECRET_CHARS) {
            disabled = true;
            diagnosticError = "Secret exceeds the supported diagnostics length; output will not be stored.";
        }
        synchronized (io) {
            try { restore(); }
            catch (Exception failure) { ioError(failure); }
            publishStatus();
        }
    }

    void begin(String abi, boolean emulator, int ram, int cores) {
        synchronized (io) {
            closeConsole();
            if (!disabled) diagnosticError = "";
            consoleBytes = totalOutputBytes = guestBytes = 0;
            redactionTail = "";
            incompleteSurrogate = 0;
            truncated = sealed = false;
            available = false;
            metadata = new JSONObject();
            long now = System.currentTimeMillis();
            String id = UUID.randomUUID().toString();
            bootDirectory = new File(directory, id);
            put("format", 1);put("run_id", id);put("boot_id", id);
            put("app_pid", Process.myPid());put("started_at_epoch_ms", now);
            put("abi", boundedString(redact(abi == null ? "unknown" : abi), 80));put("emulator", emulator);
            put("ram_mb", ram);put("cores", cores);put("phase", "starting");
            put("finished", false);put("reason", JSONObject.NULL);
            put("termination_reason", JSONObject.NULL);put("exit_code", JSONObject.NULL);
            if (!disabled) {
                try {
                    guardBaseDirectory();
                    if (!directory.isDirectory() && !directory.mkdirs())
                        throw new IOException("Cannot create the private diagnostics directory");
                    guardBaseDirectory();
                    if (!bootDirectory.mkdir()) throw new IOException("Cannot create a new diagnostics boot directory");
                    console = newOutput(new File(bootDirectory, "console.log"));
                    available = true;
                    persistMetadata();
                    atomicWrite(new File(directory, "last-boot.json"),
                            new JSONObject().put("run_id", id).toString().getBytes(StandardCharsets.UTF_8));
                    prune();
                } catch (Exception failure) { ioError(failure); }
            }
            publishStatus();
        }
    }

    /** Called by the console reader. Only the possible secret suffix remains in memory. */
    void append(String text) {
        if (text == null || text.isEmpty()) return;
        synchronized (io) {
            if (disabled || sealed || bootDirectory == null) return;
            try {
                for (int offset = 0; offset < text.length();) {
                    int end = Math.min(text.length(), offset + 4096);
                    if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                    String value = text.substring(offset, end);
                    offset = end;
                    if (incompleteSurrogate != 0) {
                        value = incompleteSurrogate + value;
                        incompleteSurrogate = 0;
                    }
                    if (!value.isEmpty() && Character.isHighSurrogate(value.charAt(value.length() - 1))) {
                        incompleteSurrogate = value.charAt(value.length() - 1);
                        value = value.substring(0, value.length() - 1);
                    }
                    totalOutputBytes += value.getBytes(StandardCharsets.UTF_8).length;
                    writeRedacted(value);
                }
                long now = System.currentTimeMillis();
                if (now - lastMetadataWrite >= 1000) persistMetadata();
            } catch (Exception failure) { ioError(failure); }
            publishStatus();
        }
    }

    /** Persist the known cause before termination. Output may still arrive until recordExit. */
    void finish(String reason, Integer exitCode, String detail) {
        synchronized (io) {
            if (sealed) return;
            setReason(reason);
            put("finished", true);
            put("phase", exitCode == null ? "termination_requested" : "exited");
            put("termination_requested_at_epoch_ms", System.currentTimeMillis());
            if (exitCode != null) put("exit_code", exitCode);
            put("detail", boundedString(redact(detail == null ? "" : detail), 4096));
            syncConsole();
            persistMetadata();
            publishStatus();
        }
    }

    /** The observer supplies an actual process result after joining its console reader. */
    void recordExit(int code) {
        synchronized (io) {
            if (sealed) return;
            setReason("qemu_exited");
            put("exit_code", code);put("finished", true);put("phase", "exited");
            put("process_started", true);
            put("exit_recorded_at_epoch_ms", System.currentTimeMillis());
            sealConsole();persistMetadata();publishStatus();
        }
    }

    /** Finalize a failed/preempted launch without inventing a process exit code. */
    void recordNoProcess() {
        synchronized (io) {
            if (sealed) return;
            setReason("no_process");
            put("exit_code", JSONObject.NULL);put("finished", true);put("phase", "no_process");
            put("process_started", false);put("finished_at_epoch_ms", System.currentTimeMillis());
            sealConsole();persistMetadata();publishStatus();
        }
    }

    void noteStartup(JSONObject startup) {
        synchronized (io) {
            if (sealed || disabled || bootDirectory == null) return;
            try {
                JSONObject value = boundedJson(redactObject(startup), 16 * 1024);
                JSONObject previous = metadata.optJSONObject("startup");
                if (previous != null && value.toString().equals(previous.toString())) return;
                put("startup", value);
            }
            catch (Exception failure) { ioError(failure); }
            persistMetadata();publishStatus();
        }
    }

    void snapshotGuest(JSONObject guest) {
        synchronized (io) {
            if (disabled || bootDirectory == null) return;
            try {
                byte[] value = boundedJson(redactObject(guest), MAX_GUEST_BYTES)
                        .toString(2).getBytes(StandardCharsets.UTF_8);
                // Whitespace expansion is bounded too; persist compact JSON if needed.
                if (value.length > MAX_GUEST_BYTES)
                    value = boundedJson(redactObject(guest), MAX_GUEST_BYTES).toString().getBytes(StandardCharsets.UTF_8);
                atomicWrite(new File(bootDirectory, "guest.json"), value);
                guestBytes = value.length;
                put("guest_snapshot_at_epoch_ms", System.currentTimeMillis());
            } catch (Exception failure) { ioError(failure); }
            persistMetadata();publishStatus();
        }
    }

    /** No locks, filesystem inspection, or I/O: suitable for frequent native UI refreshes. */
    JSONObject status() {
        try { return new JSONObject(cachedStatus); }
        catch (Exception impossible) { return new JSONObject(); }
    }

    File currentDirectory() { return bootDirectory; }

    /** Byte offsets refer to the redacted UTF-8 file, never to the raw process stream. */
    JSONObject read(int offset, int limit) {
        File file;
        long size;
        synchronized (io) {
            file = bootDirectory == null ? null : new File(bootDirectory, "console.log");
            size = consoleBytes;
        }
        long start = Math.max(0L, Math.min((long) offset, size));
        int count = Math.max(1, Math.min(limit, MAX_READ_BYTES));
        try {
            if (file == null || start >= size) return page("", start, size);
            try (FileInputStream input = openInput(file, MAX_CONSOLE_BYTES)) {
                size = Math.min(size, input.getChannel().size());start = Math.min(start, size);
                // A caller can supply any byte offset. Move back by at most three
                // bytes if it points into a code point, then return whole characters.
                while (start > 0 && start < size) {
                    input.getChannel().position(start);
                    if ((input.read() & 0xC0) != 0x80) break;
                    start--;
                }
                input.getChannel().position(start);
                int available = (int) Math.min(size - start, count + 4L);
                byte[] buffer = new byte[available];
                int read = 0;
                while (read < available) {
                    int countRead = input.read(buffer, read, available - read);
                    if (countRead == -1) throw new IOException("Diagnostics console changed during its paged read");
                    read += countRead;
                }
                int end = completePrefix(buffer, Math.min(count, available));
                if (end == 0 && available > 0)
                    end = completePrefix(buffer, Math.min(4, available));
                return page(new String(buffer, 0, end, StandardCharsets.UTF_8), start + end, size);
            }
        } catch (Exception failure) {
            synchronized (io) { ioError(failure);publishStatus(); }
            JSONObject result = page("", start, size);
            try { result.put("error", status().optString("diagnostic_error")); } catch (Exception ignored) {}
            return result;
        }
    }

    /** Snapshot the bounded files before writing to a user-selected output stream. */
    void writeTo(OutputStream output) throws IOException {
        byte[] header;
        FileInputStream log = null, guest = null;
        long logLength, guestLength;
        synchronized (io) {
            try {
                refreshMetadataCounters();
                header = metadata.toString(2).getBytes(StandardCharsets.UTF_8);
                logLength = consoleBytes;guestLength = guestBytes;
                if (bootDirectory != null) {
                    File logFile = new File(bootDirectory, "console.log");
                    File guestFile = new File(bootDirectory, "guest.json");
                    if (logFile.isFile()) log = openInput(logFile, MAX_CONSOLE_BYTES);
                    if (guestFile.isFile()) guest = openInput(guestFile, MAX_GUEST_BYTES);
                }
            } catch (Exception failure) {
                closeInput(log);closeInput(guest);ioError(failure);publishStatus();
                throw new IOException("Cannot open the private diagnostics snapshot", failure);
            }
        }
        try {
            output.write("Androidagent Docker diagnostics\n\n=== metadata.json ===\n".getBytes(StandardCharsets.UTF_8));
            output.write(header);
            output.write("\n\n=== console.log ===\n".getBytes(StandardCharsets.UTF_8));
            copy(log, output, logLength);
            output.write("\n\n=== guest.json ===\n".getBytes(StandardCharsets.UTF_8));
            copy(guest, output, guestLength);
            output.write('\n');output.flush();
        } finally { closeInput(log);closeInput(guest); }
    }

    private void restore() throws Exception {
        guardBaseDirectory();
        File pointer = new File(directory, "last-boot.json");
        if (!pointer.isFile()) return;
        String id = readJson(pointer, 4096).getString("run_id");
        if (!validId(id)) throw new IOException("Invalid private diagnostics boot identity");
        File boot = new File(directory, id);
        if (!boot.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))
            throw new IOException("Diagnostics directory is outside its private parent");
        JSONObject recorded = readJson(new File(boot, "metadata.json"), MAX_METADATA_BYTES);
        if (!id.equals(recorded.optString("run_id"))) throw new IOException("Diagnostics boot identity differs from its pointer");
        metadata = redactObject(recorded);
        diagnosticError = boundedString(metadata.optString("diagnostic_error", ""), 512);
        bootDirectory = boot;
        available = true;
        File logFile = new File(boot, "console.log");
        File guestFile = new File(boot, "guest.json");
        if (logFile.exists()) try (FileInputStream input = openInput(logFile, MAX_CONSOLE_BYTES)) {
            consoleBytes = input.getChannel().size();
        }
        if (guestFile.exists()) try (FileInputStream input = openInput(guestFile, MAX_GUEST_BYTES)) {
            guestBytes = input.getChannel().size();
        }
        totalOutputBytes = Math.max(consoleBytes, metadata.optLong("total_output_bytes", consoleBytes));
        truncated = metadata.optBoolean("console_truncated");
        // A restored object exposes evidence only. A new begin() starts a new
        // writable boot; Stop must not rewrite a completed or interrupted boot.
        sealed = true;
        if (!metadata.optBoolean("finished") && metadata.optInt("app_pid", -1) != Process.myPid()
                && !metadata.optBoolean("previous_app_session_interrupted")) {
            put("previous_app_session_interrupted", true);
            setReason("previous_app_session_interrupted");
            put("phase", "previous_app_session_interrupted");put("finished", true);
            put("exit_code", JSONObject.NULL);put("interruption_observed_at_epoch_ms", System.currentTimeMillis());
            persistMetadata();
        }
    }

    private void writeRedacted(String text) throws IOException {
        String value = redactionTail + text;
        redactionTail = "";
        if (!secret.isEmpty()) {
            value = value.replace(secret, REDACTED);
            int retain = Math.min(secret.length() - 1, value.length());
            while (retain > 0 && !value.endsWith(secret.substring(0, retain))) retain--;
            if (retain > 0) {
                redactionTail = value.substring(value.length() - retain);
                value = value.substring(0, value.length() - retain);
            }
        }
        writeConsole(value);
    }

    private void writeConsole(String value) throws IOException {
        if (truncated || console == null || value.isEmpty()) return;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int take = completePrefix(bytes, (int) Math.min(bytes.length, MAX_CONSOLE_BYTES - consoleBytes));
        if (take > 0) { console.write(bytes, 0, take);consoleBytes += take; }
        if (take < bytes.length) truncated = true;
    }

    private void setReason(String reason) {
        if (reason == null || reason.isEmpty() || !metadata.isNull("termination_reason")) return;
        String safe = boundedString(redact(reason), 256);
        put("reason", safe);put("termination_reason", safe);
    }

    private void refreshMetadataCounters() {
        put("console_bytes", consoleBytes);put("total_output_bytes", totalOutputBytes);
        put("console_truncated", truncated);put("truncated", truncated);put("guest_bytes", guestBytes);
        put("diagnostic_error", diagnosticError);put("updated_at_epoch_ms", System.currentTimeMillis());
    }

    private void publishStatus() {
        refreshMetadataCounters();
        JSONObject result = new JSONObject();
        for (String key : new String[]{"run_id", "boot_id", "phase", "finished", "reason", "termination_reason", "exit_code",
                "app_pid", "started_at_epoch_ms", "exit_recorded_at_epoch_ms", "process_started", "previous_app_session_interrupted",
                "abi", "emulator", "ram_mb", "cores", "console_bytes", "total_output_bytes", "console_truncated",
                "truncated", "guest_bytes", "diagnostic_error"}) {
            try {
                Object value = metadata.has(key) ? metadata.get(key) : JSONObject.NULL;
                if (value instanceof String) value = boundedString((String) value, 1024);
                result.put(key, value);
            } catch (Exception ignored) {}
        }
        try { result.put("available", available && !disabled); } catch (Exception ignored) {}
        cachedStatus = result.toString();
    }

    private void persistMetadata() {
        if (disabled || bootDirectory == null) return;
        refreshMetadataCounters();
        try {
            byte[] value = metadata.toString(2).getBytes(StandardCharsets.UTF_8);
            if (value.length > MAX_METADATA_BYTES) value = metadata.toString().getBytes(StandardCharsets.UTF_8);
            if (value.length > MAX_METADATA_BYTES) {
                metadata.remove("startup");put("startup_metadata_truncated", true);
                value = metadata.toString().getBytes(StandardCharsets.UTF_8);
            }
            if (value.length > MAX_METADATA_BYTES) throw new IOException("Diagnostics metadata exceeds its bound");
            atomicWrite(new File(bootDirectory, "metadata.json"), value);
            lastMetadataWrite = System.currentTimeMillis();
        } catch (Exception failure) { ioError(failure); }
    }

    private void syncConsole() {
        if (console == null) return;
        try { console.flush();console.getFD().sync(); }
        catch (Exception failure) { ioError(failure); }
    }

    private void sealConsole() {
        try {
            if (incompleteSurrogate != 0) {
                totalOutputBytes += 3;
                incompleteSurrogate = 0;
                writeRedacted("\uFFFD");
            }
            // At EOF a remaining prefix cannot be distinguished from a cut-off
            // credential. Mask it instead of writing a partial secret to disk.
            if (!redactionTail.isEmpty()) {
                redactionTail = "";
                writeConsole(REDACTED);
            }
        } catch (Exception failure) { ioError(failure); }
        sealed = true;
        syncConsole();closeConsole();
    }

    private void closeConsole() {
        if (console == null) return;
        try { console.close(); } catch (Exception failure) { ioError(failure); }
        console = null;
    }

    private void ioError(Exception failure) {
        String message = failure.getClass().getSimpleName() + ": " + (failure.getMessage() == null ? "Diagnostics I/O failed" : failure.getMessage());
        diagnosticError = boundedString(redact(message), 512);
    }

    private String redact(String value) { return secret.isEmpty() ? value : value.replace(secret, REDACTED); }

    private JSONObject redactObject(JSONObject value) throws Exception {
        return value == null ? new JSONObject() : (JSONObject) redactValue(value, 0);
    }

    private Object redactValue(Object value, int depth) throws Exception {
        if (depth > 16) return "[TRUNCATED]";
        if (value instanceof String) return redact((String) value);
        if (value instanceof JSONObject) {
            JSONObject copy = new JSONObject();Iterator<String> keys = ((JSONObject) value).keys();
            while (keys.hasNext()) { String key = keys.next();copy.put(redact(key), redactValue(((JSONObject) value).get(key), depth + 1)); }
            return copy;
        }
        if (value instanceof JSONArray) {
            JSONArray copy = new JSONArray();JSONArray source = (JSONArray) value;
            for (int index = 0; index < source.length(); index++) copy.put(redactValue(source.get(index), depth + 1));
            return copy;
        }
        return value;
    }

    private static JSONObject boundedJson(JSONObject value, int limit) throws Exception {
        byte[] original = value.toString().getBytes(StandardCharsets.UTF_8);
        if (original.length <= limit) return value;
        int keep = completePrefix(original, Math.max(0, (limit - 512) / 6));
        return new JSONObject().put("truncated", true).put("original_bytes", original.length)
                .put("preview", new String(original, 0, keep, StandardCharsets.UTF_8));
    }

    private static String boundedString(String value, int maxChars) {
        if (value.length() <= maxChars) return value;
        int end = maxChars;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "…";
    }

    private static int completePrefix(byte[] bytes, int wanted) {
        int end = Math.max(0, Math.min(wanted, bytes.length));
        if (end == bytes.length) return end;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
        return end;
    }

    private static JSONObject page(String content, long next, long total) {
        JSONObject result = new JSONObject();
        try { result.put("content", content).put("next_offset", next).put("total_bytes", total).put("encoding", "utf-8"); }
        catch (Exception ignored) {}
        return result;
    }

    private JSONObject readJson(File file, int limit) throws Exception {
        try (FileInputStream input = openInput(file, limit)) {
            ByteArrayOutputStream value = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];int count;
            while ((count = input.read(buffer)) != -1) {
                if (value.size() + count > limit) throw new IOException("Private diagnostics JSON exceeds its bound");
                value.write(buffer, 0, count);
            }
            return new JSONObject(new String(value.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private void atomicWrite(File file, byte[] content) throws Exception {
        File parent = file.getParentFile();
        guardParent(file);
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create diagnostics parent");
        File temporary = new File(parent, file.getName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileOutputStream output = newOutput(temporary)) {
                output.write(content);output.flush();output.getFD().sync();
            }
            Os.rename(temporary.getAbsolutePath(), file.getAbsolutePath());
            FileDescriptor descriptor = Os.open(parent.getAbsolutePath(),
                    OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
            try {
                if ((Os.fstat(descriptor).st_mode & OsConstants.S_IFMT) != OsConstants.S_IFDIR)
                    throw new IOException("Diagnostics metadata parent is not a directory");
                Os.fsync(descriptor);
            } finally { Os.close(descriptor); }
        } finally { temporary.delete(); }
    }

    private void prune() {
        try { guardBaseDirectory(); } catch (Exception failure) { ioError(failure);return; }
        File[] children = directory.listFiles();
        if (children == null) return;
        List<RecordedBoot> boots = new ArrayList<>();
        for (File child : children) {
            if (!child.isDirectory() || !validId(child.getName())) continue;
            try {
                if (!child.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) continue;
                JSONObject recorded = readJson(new File(child, "metadata.json"), MAX_METADATA_BYTES);
                if (recorded.optInt("format") != 1 || !child.getName().equals(recorded.optString("run_id"))) continue;
                boots.add(new RecordedBoot(child, recorded.optLong("started_at_epoch_ms", child.lastModified())));
            } catch (Exception failure) { ioError(failure); }
        }
        boots.sort(Comparator.comparingLong((RecordedBoot boot) -> boot.started).reversed());
        int kept = 0;
        for (RecordedBoot boot : boots) {
            if (boot.file.equals(bootDirectory)) continue;
            if (++kept >= MAX_BOOTS) removeBoot(boot.file);
        }
    }

    private static boolean validId(String value) {
        try { return UUID.fromString(value).toString().equals(value); } catch (Exception invalid) { return false; }
    }

    private void removeBoot(File file) {
        // This component creates only flat boot directories. Never traverse a
        // nested directory or symlink introduced by another app-UID process.
        File[] children = file.listFiles();
        if (children == null) return;
        try {
            guardParent(new File(file, "metadata.json"));
            for (File child : children) {
                int mode = Os.lstat(child.getAbsolutePath()).st_mode & OsConstants.S_IFMT;
                String name = child.getName();
                boolean known = "metadata.json".equals(name) || "console.log".equals(name) || "guest.json".equals(name);
                if (!known && (name.startsWith("metadata.json.tmp-") || name.startsWith("guest.json.tmp-")))
                    known = validId(name.substring(name.indexOf(".tmp-") + 5));
                if (!known || mode != OsConstants.S_IFREG) return;
            }
            for (File child : children) child.delete();
        } catch (Exception failure) { ioError(failure);return; }
        file.delete();
    }

    private void guardBaseDirectory() throws Exception {
        if (directory == null) throw new IOException("Missing private diagnostics directory");
        File checked = directory.getAbsoluteFile();
        // Inspect the user-controlled diagnostics/docker/files components, while
        // allowing Android's higher-level system aliases such as /data/user/0.
        for (int depth = 0; checked != null && depth < 3; depth++, checked = checked.getParentFile()) {
            try {
                int mode = Os.lstat(checked.getAbsolutePath()).st_mode & OsConstants.S_IFMT;
                if (mode != OsConstants.S_IFDIR) throw new IOException("Diagnostics parent is not an ordinary private directory");
            } catch (ErrnoException missing) { if (missing.errno != OsConstants.ENOENT) throw missing; }
        }
        File canonical = directory.getCanonicalFile();
        if (canonicalDirectory == null) canonicalDirectory = canonical;
        else if (!canonicalDirectory.equals(canonical)) throw new IOException("Diagnostics parent changed its private location");
    }

    private void guardParent(File file) throws Exception {
        guardBaseDirectory();
        File parent = file.getParentFile();
        if (parent == null) throw new IOException("Missing private diagnostics parent");
        File canonical = parent.getCanonicalFile();
        if (!canonical.equals(canonicalDirectory)
                && (!canonical.getParentFile().equals(canonicalDirectory) || !validId(parent.getName())))
            throw new IOException("Diagnostics file is outside a recorded private boot directory");
        if ((Os.lstat(parent.getAbsolutePath()).st_mode & OsConstants.S_IFMT) != OsConstants.S_IFDIR)
            throw new IOException("Diagnostics boot directory is not an ordinary directory");
    }

    private FileInputStream openInput(File file, int limit) throws Exception {
        guardParent(file);
        FileDescriptor descriptor = Os.open(file.getAbsolutePath(),
                OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
        try {
            android.system.StructStat stat = Os.fstat(descriptor);
            if ((stat.st_mode & OsConstants.S_IFMT) != OsConstants.S_IFREG || stat.st_size > limit)
                throw new IOException("Diagnostics input is not a bounded regular file");
            // libcore streams created from a descriptor do not own it. Keep
            // ownership here so every bounded read closes the Os.open FD.
            return new FileInputStream(descriptor) {
                private boolean released;

                @Override public synchronized void close() throws IOException {
                    if (released) return;
                    released = true;
                    IOException failure = null;
                    try { super.close(); }
                    catch (IOException problem) { failure = problem; }
                    finally { closeOwnedDescriptor(descriptor, failure); }
                }
            };
        } catch (Exception failure) { Os.close(descriptor);throw failure; }
    }

    private FileOutputStream newOutput(File file) throws Exception {
        guardParent(file);
        FileDescriptor descriptor = Os.open(file.getAbsolutePath(), OsConstants.O_WRONLY | OsConstants.O_CREAT
                | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0600);
        try {
            if ((Os.fstat(descriptor).st_mode & OsConstants.S_IFMT) != OsConstants.S_IFREG)
                throw new IOException("Diagnostics output is not a regular file");
            return new FileOutputStream(descriptor) {
                private boolean released;

                @Override public synchronized void close() throws IOException {
                    if (released) return;
                    released = true;
                    IOException failure = null;
                    try { super.close(); }
                    catch (IOException problem) { failure = problem; }
                    finally { closeOwnedDescriptor(descriptor, failure); }
                }
            };
        } catch (Exception failure) { Os.close(descriptor);throw failure; }
    }

    private static void closeOwnedDescriptor(FileDescriptor descriptor, IOException failure) throws IOException {
        try { Os.close(descriptor); }
        catch (ErrnoException problem) {
            IOException closeFailure = new IOException("Cannot close private diagnostics descriptor", problem);
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }

    private static final class RecordedBoot {
        final File file;
        final long started;
        RecordedBoot(File file, long started) { this.file = file;this.started = started; }
    }

    private static void copy(FileInputStream input, OutputStream output, long limit) throws IOException {
        if (input == null) return;
        byte[] buffer = new byte[8192];
        while (limit > 0) { int count = input.read(buffer, 0, (int) Math.min(limit, buffer.length));if (count == -1) break;output.write(buffer, 0, count);limit -= count; }
    }

    private static void closeInput(FileInputStream input) { if (input != null) try { input.close(); } catch (IOException ignored) {} }
    private void put(String key, Object value) { try { metadata.put(key, value); } catch (Exception ignored) {} }
}
