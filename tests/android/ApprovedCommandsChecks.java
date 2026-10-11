package dev.mateuszsury.androidagent;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Native command consent, real app-UID shell; no model account or simulated command output. */
final class ApprovedCommandsChecks {
    static void inlineApprovalShowsAndExecutesExactCommand(Instrumentation instrumentation, Activity screen) throws Exception {
        Fixture fixture = new Fixture(instrumentation, screen);
        String marker = "UI_EXACT_COMMAND_" + UUID.randomUUID().toString().replace("-", "");
        File result = new File(LocalShell.workspace(fixture.app), marker + ".txt");
        StringBuilder exact = new StringBuilder();
        for (int line = 0; line < 30; line++) exact.append("# approval line ").append(line).append('\n');
        exact.append("printf '%s' '").append(marker).append("' > '").append(result.getName())
                .append("'; printf '%s\\n' '").append(marker).append("'");
        try {
            Future<JSONObject> running = fixture.request(exact.toString());
            ApprovedCommands.Pending pending = fixture.awaitPending(null);
            require(pending.summary.endsWith(exact.toString()), "Consent does not contain the complete exact command");
            onMain(instrumentation, () -> {
                TextView summary = (TextView) find(screen.getWindow().getDecorView(), pending.summary);
                require(summary != null && summary.getMaxLines() == Integer.MAX_VALUE,
                        "The command is truncated to a fixed number of lines");
                require(summary.getParent() instanceof ScrollView, "Long consent text is not scrollable");
                ScrollView details = (ScrollView) summary.getParent();
                require(details.getHeight() > 0 && details.getHeight() <= Ui.dp(screen, 180),
                        "Long consent text hides the response buttons");
                details.fullScroll(View.FOCUS_DOWN);
                require(find(screen.getWindow().getDecorView(), "Zatwierdź") instanceof Button,
                        "Consent has no native approval button");
                require(!result.exists(), "A command executed before the native approval click");
                find(screen.getWindow().getDecorView(), "Zatwierdź").performClick();
            });
            String response = running.get(30, TimeUnit.SECONDS).toString();
            require(response.contains(marker) && response.contains("[exit 0]"),
                    "Approved command did not report its real successful shell result");
            require(result.isFile() && marker.equals(new String(Files.readAllBytes(result.toPath()), StandardCharsets.UTF_8)),
                    "The exact approved command did not create its expected file");
        } finally { try { fixture.close(); } finally { result.delete(); } }
    }

    static void inlineDenialDoesNotExecuteCommand(Instrumentation instrumentation, Activity screen) throws Exception {
        Fixture fixture = new Fixture(instrumentation, screen);
        File result = new File(LocalShell.workspace(fixture.app), "denied-" + UUID.randomUUID() + ".txt");
        try {
            Future<JSONObject> running = fixture.request("printf 'MUST_NOT_EXECUTE' > '" + result.getName() + "'");
            fixture.awaitPending(null);
            onMain(instrumentation, () -> find(screen.getWindow().getDecorView(), "Odrzuć").performClick());
            running.get(30, TimeUnit.SECONDS);
            require(!result.exists(), "A denied command changed the filesystem");
        } finally { try { fixture.close(); } finally { result.delete(); } }
    }

    static void staleButtonCannotApproveReplacement(Instrumentation instrumentation, Activity screen) throws Exception {
        Fixture fixture = new Fixture(instrumentation, screen);
        File result = new File(LocalShell.workspace(fixture.app), "stale-approval-" + UUID.randomUUID() + ".txt");
        String command = "printf 'MUST_NOT_EXECUTE' > '" + result.getName() + "'";
        View[] staleButton = {null};
        try {
            Future<JSONObject> first = fixture.request(command);
            ApprovedCommands.Pending old = fixture.awaitPending(null);
            onMain(instrumentation, () -> staleButton[0] = find(screen.getWindow().getDecorView(), "Zatwierdź"));
            require(staleButton[0] != null, "First approval was never rendered");
            fixture.manager.cancelOwner(fixture.owner);
            first.get(30, TimeUnit.SECONDS);
            Future<JSONObject> replacement = fixture.request(command);
            ApprovedCommands.Pending current = fixture.awaitPending(old.id);
            require(!old.id.equals(current.id), "A replacement request reused the old consent identity");
            onMain(instrumentation, () -> staleButton[0].performClick());
            instrumentation.waitForIdleSync();
            ApprovedCommands.Pending stillPending = fixture.manager.pendingForChat(fixture.session.id);
            require(stillPending != null && current.id.equals(stillPending.id) && !replacement.isDone(),
                    "Clicking an obsolete view approved a later request with the same command");
            onMain(instrumentation, () -> find(screen.getWindow().getDecorView(), "Odrzuć").performClick());
            replacement.get(30, TimeUnit.SECONDS);
            require(!result.exists(), "An obsolete approval executed the replacement command");
        } finally { try { fixture.close(); } finally { result.delete(); } }
    }

    private static final class Fixture {
        final Instrumentation instrumentation;
        final Activity screen;
        final Context app;
        final Sessions.Session session;
        final ApprovedCommands manager;
        final McpHost host;
        final String owner, credential;
        final boolean oldDev;
        final ExecutorService worker = Executors.newSingleThreadExecutor();

        Fixture(Instrumentation instrumentation, Activity screen) throws Exception {
            require(screen instanceof ChatActivity, "Expected a native chat screen");
            this.instrumentation = instrumentation;this.screen = screen;
            app = instrumentation.getTargetContext();
            java.lang.reflect.Field field = ChatActivity.class.getDeclaredField("ses");
            field.setAccessible(true);session = (Sessions.Session) field.get(screen);
            owner = "chat:" + session.id;
            host = McpHost.get(app);host.acquire(this);host.allowControl();
            oldDev = Prefs.devMode(app);Prefs.setDevMode(app, true);
            manager = ApprovedCommands.get(app);
            credential = host.credential(session.id);
        }

        Future<JSONObject> request(String exactCommand) {
            return worker.submit(() -> manager.run(owner, exactCommand, credential));
        }

        ApprovedCommands.Pending awaitPending(String previous) throws Exception {
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while (SystemClock.elapsedRealtime() < deadline) {
                ApprovedCommands.Pending pending = manager.pendingForChat(session.id);
                if (pending != null && (previous == null || !previous.equals(pending.id))) {
                    boolean[] rendered = {false};
                    onMain(instrumentation, () -> {
                        View summary = find(screen.getWindow().getDecorView(), pending.summary);
                        rendered[0] = summary != null && summary.getHeight() > 0
                                && summary.getParent() instanceof ScrollView
                                && ((ScrollView) summary.getParent()).getHeight() > 0;
                    });
                    if (rendered[0]) return pending;
                }
                SystemClock.sleep(25);
            }
            throw new AssertionError("The exact command consent did not reach its native chat");
        }

        void close() throws Exception {
            manager.cancelOwner(owner);
            host.revoke(session.id);host.release(this);
            Prefs.setDevMode(app, oldDev);
            worker.shutdownNow();worker.awaitTermination(5, TimeUnit.SECONDS);
            // Revocation closes this chat's USB owners asynchronously. Wait for that
            // bounded cleanup so another UI test can reuse the same chat safely.
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (true) {
                try { host.requireReady(session.id); break; }
                catch (IllegalStateException closing) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw closing;
                    SystemClock.sleep(25);
                }
            }
            instrumentation.waitForIdleSync();
        }
    }

    private static View find(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View match = find(group.getChildAt(index), value);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static void onMain(Instrumentation instrumentation, Runnable action) throws Exception {
        Throwable[] failure = {null};
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
