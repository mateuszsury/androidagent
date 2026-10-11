package dev.mateuszsury.androidagent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact-command approvals independent of Accessibility, scoped to an immutable tool request. */
final class ApprovedCommands {
    private static ApprovedCommands instance;
    static synchronized ApprovedCommands get(Context context) {
        if (instance == null) instance = new ApprovedCommands(context.getApplicationContext());
        return instance;
    }

    static final class Pending {
        final String id = UUID.randomUUID().toString();
        final String owner;
        final String summary;
        private final String command;
        private final String credential;
        private final CountDownLatch answered = new CountDownLatch(1);
        private boolean decided, approved, cancelled;
        private LocalShell shell;

        Pending(String owner, String command, String credential) {
            this.owner = owner;
            this.command = command;
            this.credential = credential;
            summary = (owner.startsWith("chat:") ? "" : "Żądanie zewnętrznego narzędzia.\n\n")
                    + "Wykonać dokładnie tę komendę w lokalnym katalogu androidagent?\n\n" + command;
        }
    }

    private final Context app;
    private final Map<String, Pending> requests = new LinkedHashMap<>();
    private ApprovedCommands(Context context) { app = context; }

    JSONObject run(String caller, String command) throws Exception {
        return run(caller, command, McpServer.credential());
    }

    /** Native instrumentation can supply its real host-issued run credential without HTTP thread locals. */
    JSONObject run(String caller, String command, String credential) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Komenda wymaga osobnego wątku narzędzia.");
        if (!Prefs.devMode(app)) throw new IllegalStateException("Włącz tryb programisty w ustawieniach androidagent.");
        if (command == null || command.trim().isEmpty()) throw new IllegalArgumentException("Wpisz komendę");
        if (command.length() > 8192) throw new IllegalArgumentException("Komenda jest za długa");
        if (caller == null) throw new IllegalArgumentException("Brak właściciela komendy");
        Pending pending = new Pending(caller, command, credential);
        synchronized (this) {
            McpHost.get(app).requireCaller(caller);
            if (!authorized(pending)) throw new SecurityException("This command's tool access has ended.");
            if (requests.size() >= 32) throw new IllegalStateException("Za dużo oczekujących komend. Zatrzymaj poprzednie zadania.");
            requests.put(pending.id, pending);
        }
        changed();
        try {
            long deadline = SystemClock.elapsedRealtime() + 5 * 60 * 1000L;
            while (!pending.answered.await(200, TimeUnit.MILLISECONDS)) {
                synchronized (this) {
                    if (!authorized(pending) || SystemClock.elapsedRealtime() >= deadline) {
                        pending.cancelled = true;
                        pending.decided = true;
                        pending.answered.countDown();
                    }
                }
            }
            LocalShell shell;
            synchronized (this) {
                // Revalidate the exact credential after waiting: a new run of this
                // same chat never inherits approval granted to the previous run.
                if (!pending.approved || !authorized(pending)) return text("DENIED. Command was not executed.");
                shell = new LocalShell();
                pending.shell = shell;
            }
            return text(shell.run(app, pending.command).display());
        } finally {
            LocalShell shell;
            synchronized (this) {
                requests.remove(pending.id);
                pending.cancelled = true;
                shell = pending.shell;
            }
            if (shell != null) shell.stop();
            changed();
        }
    }

    synchronized Pending pendingForChat(String chatId) {
        for (Pending pending : requests.values()) {
            if (!pending.decided && !pending.cancelled && authorized(pending)
                    && (pending.owner.equals("chat:" + chatId) || !pending.owner.startsWith("chat:"))) return pending;
        }
        return null;
    }

    /** The UI must answer the ID it displayed, never whichever request is current now. */
    boolean answer(String id, boolean approved) {
        boolean accepted;
        synchronized (this) {
            Pending pending = requests.get(id);
            if (pending == null || pending.decided || pending.cancelled) return false;
            accepted = authorized(pending);
            pending.approved = accepted && approved;
            pending.cancelled = !accepted;
            pending.decided = true;
            pending.answered.countDown();
        }
        changed();
        return accepted;
    }

    void cancelOwner(String owner) { cancel(owner); }
    void cancelAll() { cancel(null); }

    private void cancel(String owner) {
        List<LocalShell> shells = new ArrayList<>();
        synchronized (this) {
            for (Pending pending : requests.values()) {
                if (owner != null && !owner.equals(pending.owner)) continue;
                pending.cancelled = true;
                pending.decided = true;
                pending.answered.countDown();
                if (pending.shell != null) {
                    // Revoke the spawn permit before releasing this request's gate.
                    pending.shell.requestStop();
                    shells.add(pending.shell);
                }
            }
        }
        changed();
        // Killing descendants can wait for signal delivery. Keep it outside the
        // approval monitor so Stop does not block UI reads/answers of other cards.
        Runnable stop = () -> { for (LocalShell shell : shells) shell.stop(); };
        if (!shells.isEmpty() && Looper.myLooper() == Looper.getMainLooper())
            new Thread(stop, "androidagent-approved-command-stop").start();
        else stop.run();
    }

    private boolean authorized(Pending pending) {
        McpHost host = McpHost.get(app);
        if (pending.cancelled || !Prefs.devMode(app) || host.controlRevoked() != null) return false;
        if (!pending.owner.startsWith("chat:")) return true;
        return pending.credential != null && pending.owner.equals(host.principal(pending.credential));
    }

    private void changed() {
        new Handler(Looper.getMainLooper()).post(() -> ChatHub.get(app).changed());
    }

    private static JSONObject text(String value) throws Exception {
        return new JSONObject().put("content", new JSONArray().put(
                new JSONObject().put("type", "text").put("text", value)));
    }
}
