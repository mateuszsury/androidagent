package dev.mateuszsury.androidagent;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Actual app-UID shell/credential regressions; no model account or Accessibility is needed. */
final class ApprovedCommandRuntimeChecks {
    static void revokedRunCannotInheritApproval(Context app) throws Exception {
        ApprovedCommands commands = ApprovedCommands.get(app);
        McpHost host = McpHost.get(app);
        String chat = "approval-generation-" + UUID.randomUUID();
        Object owner = new Object();
        File marker = new File(LocalShell.workspace(app), "approval-" + UUID.randomUUID() + ".txt");
        String command = "printf GENERATION_GUARD > '" + marker.getName() + "'";
        ExecutorService worker = Executors.newSingleThreadExecutor();
        boolean developer = Prefs.devMode(app);
        host.acquire(owner);
        host.allowControl();
        Prefs.setDevMode(app, true);
        try {
            String oldCredential = host.credential(chat);
            Future<JSONObject> old = worker.submit(() -> commands.run("chat:" + chat, command, oldCredential));
            ApprovedCommands.Pending first = waitForPending(commands, chat);
            host.revoke(chat);
            waitForOwner(host, chat);
            String nextCredential = host.credential(chat);
            Future<JSONObject> next = worker.submit(() -> commands.run("chat:" + chat, command, nextCredential));
            check(result(old.get(5, TimeUnit.SECONDS)).contains("DENIED"), "Revoked run was executed");
            ApprovedCommands.Pending second = waitForPending(commands, chat);
            check(!first.id.equals(second.id), "New run reused the old approval identity");
            check(!commands.answer(first.id, true), "Stale approval answered a new run of the same chat");
            check(!next.isDone() && !marker.exists(), "New run executed without its own approval");
            check(commands.answer(second.id, false), "Current request could not be declined");
            check(result(next.get(5, TimeUnit.SECONDS)).contains("DENIED") && !marker.exists(),
                    "Declined command wrote its marker");
        } finally {
            commands.cancelOwner("chat:" + chat);
            host.revoke(chat);
            waitForOwner(host, chat);
            host.release(owner);
            Prefs.setDevMode(app, developer);
            worker.shutdownNow();
            marker.delete();
        }
    }

    static void cancellationStopsApprovedShell(Context app) throws Exception {
        ApprovedCommands commands = ApprovedCommands.get(app);
        McpHost host = McpHost.get(app);
        String chat = "approval-stop-" + UUID.randomUUID();
        Object owner = new Object();
        File started = new File(LocalShell.workspace(app), "approval-started-" + UUID.randomUUID() + ".txt");
        File finished = new File(LocalShell.workspace(app), "approval-finished-" + UUID.randomUUID() + ".txt");
        String command = "printf STARTED > '" + started.getName() + "'; sleep 30; printf FINISHED > '" + finished.getName() + "'";
        ExecutorService worker = Executors.newSingleThreadExecutor();
        boolean developer = Prefs.devMode(app);
        host.acquire(owner);
        host.allowControl();
        Prefs.setDevMode(app, true);
        try {
            String credential = host.credential(chat);
            Future<JSONObject> running = worker.submit(() -> commands.run("chat:" + chat, command, credential));
            ApprovedCommands.Pending pending = waitForPending(commands, chat);
            check(pending.summary.endsWith(command), "Approval changed or truncated the exact command");
            check(commands.answer(pending.id, true), "Current command could not be approved");
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (!started.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25);
            check(started.exists(), "Approved shell did not actually start");
            commands.cancelOwner("chat:" + chat);
            check(result(running.get(5, TimeUnit.SECONDS)).contains("[exit 130]"), "Owner cancellation did not stop the active shell");
            check(!finished.exists(), "Cancelled shell continued after its sleep");
        } finally {
            commands.cancelOwner("chat:" + chat);
            host.revoke(chat);
            waitForOwner(host, chat);
            host.release(owner);
            Prefs.setDevMode(app, developer);
            worker.shutdownNow();
            started.delete();
            finished.delete();
        }
    }

    static void cancelledShellCannotSpawn(Context app) throws Exception {
        File marker = new File(LocalShell.workspace(app), "approval-no-spawn-" + UUID.randomUUID() + ".txt");
        LocalShell shell = new LocalShell();
        try {
            shell.stop();
            LocalShell.Result result = shell.run(app, "printf FORBIDDEN > '" + marker.getName() + "'");
            check(result.exitCode == 130 && !marker.exists(), "A shell cancelled before execution spawned the command");
        } finally { shell.stop(); marker.delete(); }
    }

    private static ApprovedCommands.Pending waitForPending(ApprovedCommands commands, String chat) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        ApprovedCommands.Pending pending;
        while ((pending = commands.pendingForChat(chat)) == null && SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(25);
        check(pending != null, "Exact command approval did not appear");
        return pending;
    }

    private static void waitForOwner(McpHost host, String chat) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (true) {
            try { host.requireReady(chat); return; }
            catch (IllegalStateException busy) {
                if (SystemClock.elapsedRealtime() >= deadline) throw new AssertionError("Old run cleanup did not finish", busy);
                SystemClock.sleep(25);
            }
        }
    }

    private static String result(JSONObject value) throws Exception {
        return value.getJSONArray("content").getJSONObject(0).getString("text");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
