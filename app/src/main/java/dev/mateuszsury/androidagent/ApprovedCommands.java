package dev.mateuszsury.androidagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
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

/**
 * Owner approvals independent of Accessibility, scoped to an immutable tool request: exact shell commands
 * (MCP run_command, developer mode only) and generic approvals (MCP confirm). Both render as the same inline
 * card in the caller's chat. Generic approvals are also mirrored in the bubble while Accessibility is on, and
 * a notification points to the chat when no chat screen is showing the card.
 */
final class ApprovedCommands {
    private static ApprovedCommands instance;
    static synchronized ApprovedCommands get(Context context) {
        if (instance == null) instance = new ApprovedCommands(context.getApplicationContext());
        return instance;
    }

    static final long TIMEOUT_MS = 5 * 60 * 1000L;
    /** High-importance channel for things only the owner can do: approvals and tap-to-open offers. */
    static final String ACTION_CHANNEL = "agent-action";
    private static final int NOTICE_ID = 7301;

    static final class Pending {
        final String id = UUID.randomUUID().toString();
        final String owner;
        final String summary;
        /** True for a generic approval (MCP confirm); false for an exact shell command. */
        final boolean generic;
        private final String command;
        private final String credential;
        private final CountDownLatch answered = new CountDownLatch(1);
        private boolean decided, approved, denied, cancelled, timedOut;
        private LocalShell shell;

        Pending(String owner, String command, String credential) {
            this(owner, command, credential, false);
        }

        private Pending(String owner, String text, String credential, boolean generic) {
            this.owner = owner;
            this.command = text;
            this.credential = credential;
            this.generic = generic;
            // The agent's own text is shown verbatim; only external callers get a provenance line.
            summary = (owner.startsWith("chat:") ? "" : "Żądanie zewnętrznego narzędzia.\n\n")
                    + (generic ? text : "Wykonać dokładnie tę komendę w lokalnym katalogu androidagent?\n\n" + text);
        }
    }

    private final Context app;
    private final Map<String, Pending> requests = new LinkedHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    // Main thread only: the generic approval mirrored in the bubble, and the request the notice shows.
    private Pending mirrored;
    private Bubble mirrorBubble;
    private Bubble.ConfirmCallback mirrorCallback;
    private String noticeFor;
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
        register(pending);
        try {
            awaitDecision(pending);
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

    /**
     * Generic owner approval (MCP {@code confirm}). Blocks the tool thread until the owner answers in the chat
     * card or the mirrored bubble, the request times out, or the run ends / Stop is pressed.
     */
    String confirm(String caller, String summary) throws Exception {
        return confirm(caller, summary, McpServer.credential());
    }

    String confirm(String caller, String summary, String credential) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Prośba o zgodę wymaga osobnego wątku narzędzia.");
        if (summary == null || summary.trim().isEmpty())
            throw new IllegalArgumentException("summary is required: describe exactly what you are about to do.");
        if (summary.length() > 8192) throw new IllegalArgumentException("summary is too long (max 8192 characters).");
        if (caller == null) throw new IllegalArgumentException("Brak właściciela prośby");
        Pending pending = new Pending(caller, summary, credential, true);
        register(pending);
        try {
            awaitDecision(pending);
            synchronized (this) {
                if (pending.approved && authorized(pending)) return "APPROVED by the user. Go ahead.";
                if (pending.timedOut)
                    return "TIMEOUT: the user did not answer within 5 minutes. Do not do it; ask what they want instead.";
                if (pending.denied) return "DENIED by the user. Do not do it; ask what they want instead.";
            }
            return "DENIED: the request was cancelled (Stop was pressed or this run ended). Do not do it.";
        } finally {
            synchronized (this) {
                requests.remove(pending.id);
                pending.cancelled = true;
            }
            changed();
        }
    }

    /**
     * Ask the owner, on the same in-chat card as {@code run_command}, to approve the exact action
     * described by {@code question} (e.g. starting a background service); nothing is executed here.
     * True only when approved while this caller's run is still the active one.
     */
    boolean approve(String caller, String question) throws Exception {
        return approve(caller, question, McpServer.credential());
    }

    boolean approve(String caller, String question, String credential) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Zgoda wymaga osobnego wątku narzędzia.");
        if (!Prefs.devMode(app)) throw new IllegalStateException("Włącz tryb programisty w ustawieniach androidagent.");
        if (caller == null) throw new IllegalArgumentException("Brak właściciela żądania");
        Pending pending = new Pending(caller, question, credential, true);
        register(pending);
        try {
            awaitDecision(pending);
            synchronized (this) {
                return pending.approved && authorized(pending);
            }
        } finally {
            synchronized (this) {
                requests.remove(pending.id);
                pending.cancelled = true;
            }
            changed();
        }
    }

    private void register(Pending pending) {
        synchronized (this) {
            McpHost.get(app).requireCaller(pending.owner);
            if (!authorized(pending)) throw new SecurityException("This request's tool access has ended.");
            if (requests.size() >= 32) throw new IllegalStateException("Za dużo oczekujących próśb. Zatrzymaj poprzednie zadania.");
            requests.put(pending.id, pending);
        }
        changed();
    }

    private void awaitDecision(Pending pending) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (!pending.answered.await(200, TimeUnit.MILLISECONDS)) {
            synchronized (this) {
                boolean expired = SystemClock.elapsedRealtime() >= deadline;
                if (!authorized(pending) || expired) {
                    pending.timedOut = expired && authorized(pending);
                    pending.cancelled = true;
                    pending.decided = true;
                    pending.answered.countDown();
                }
            }
        }
    }

    synchronized Pending pendingForChat(String chatId) {
        for (Pending pending : requests.values()) {
            if (!pending.decided && !pending.cancelled && authorized(pending)
                    && (pending.owner.equals("chat:" + chatId) || !pending.owner.startsWith("chat:"))) return pending;
        }
        return null;
    }

    /** The oldest undecided request of any owner (only generic approvals if asked), or null. */
    private synchronized Pending firstPending(boolean genericOnly) {
        for (Pending pending : requests.values()) {
            if (!pending.decided && !pending.cancelled && (!genericOnly || pending.generic) && authorized(pending))
                return pending;
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
            pending.denied = accepted && !approved;
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
        if (pending.cancelled || host.controlRevoked() != null) return false;
        if (!pending.generic && !Prefs.devMode(app)) return false;
        if (!pending.owner.startsWith("chat:")) return true;
        return pending.credential != null && pending.owner.equals(host.principal(pending.credential));
    }

    private void changed() {
        main.post(() -> {
            ChatHub.get(app).changed();
            syncMirrors();
        });
    }

    // ------------------------------------------- bubble mirror and notice (main thread)

    /** The bubble is going away: its cancellation must not deny the request, the chat card still shows it. */
    void detachBubble(Bubble bubble) {
        if (bubble == null || bubble != mirrorBubble) return;
        mirrored = null;
        mirrorBubble = null;
        mirrorCallback = null;
    }

    private void syncMirrors() {
        Pending next = firstPending(true);
        BuddyService svc = BuddyService.get();
        Bubble bubble = svc == null ? null : svc.bubble();
        if (next != mirrored || bubble != mirrorBubble) {
            Bubble.ConfirmCallback old = mirrorCallback;
            Bubble oldBubble = mirrorBubble;
            mirrored = null;
            mirrorBubble = null;
            mirrorCallback = null;
            // The old callback is no longer current, so this cancellation answers nothing.
            if (old != null && oldBubble != null) oldBubble.cancelConfirm(old);
            if (next != null && bubble != null) {
                final String id = next.id;
                final Bubble.ConfirmCallback[] self = {null};
                self[0] = approved -> { if (mirrorCallback == self[0]) answer(id, approved); };
                mirrored = next;
                mirrorBubble = bubble;
                mirrorCallback = self[0];
                bubble.askConfirm(next.summary, self[0]);
            }
        }
        syncNotice();
    }

    /** A heads-up notification while a request waits and no chat screen is in front to show its card. */
    private void syncNotice() {
        Pending first = firstPending(false);
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null) return;
        String key = first == null || ChatHub.get(app).inForegroundChat() ? null : first.id;
        if (key == null ? noticeFor == null : key.equals(noticeFor)) return;
        noticeFor = key;
        try {
            if (key == null) {
                nm.cancel(NOTICE_ID);
                return;
            }
            createActionChannel(app);
            String chat = first.owner.startsWith("chat:") ? first.owner.substring(5) : null;
            Intent open = chat != null
                    ? new Intent(app, ChatActivity.class).putExtra("id", chat)
                    : new Intent(app, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent tap = PendingIntent.getActivity(app, NOTICE_ID, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String body = first.summary.length() > 600 ? first.summary.substring(0, 599) + "…" : first.summary;
            Notification n = new Notification.Builder(app, ACTION_CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(first.generic ? "Agent czeka na Twoją zgodę" : "Agent chce wykonać komendę")
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build();
            nm.notify(NOTICE_ID, n);
        } catch (RuntimeException ignored) {
            // Notifications disabled: the chat card (and the bubble) still show the request.
        }
    }

    static void createActionChannel(Context c) {
        NotificationChannel channel = new NotificationChannel(ACTION_CHANNEL, "Agent · wymaga Twojej akcji",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Prośby agenta o zgodę i akcje, które musisz wykonać sam.");
        c.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private static JSONObject text(String value) throws Exception {
        return new JSONObject().put("content", new JSONArray().put(
                new JSONObject().put("type", "text").put("text", value)));
    }
}
