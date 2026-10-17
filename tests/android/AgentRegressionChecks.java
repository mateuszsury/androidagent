package dev.mateuszsury.androidagent;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** Run on the main instrumentation thread; protocol fixtures never contact a model. */
final class AgentRegressionChecks {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static JSONObject event(Sessions.Session session, String type, String run) throws Exception {
        return new JSONObject().put("buddy_session", session.id).put("type", type).put("buddy_run", run);
    }

    private static Context isolated(Context app, String prefix) {
        return new ContextWrapper(app) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(prefix + name, mode);
            }
        };
    }

    static void failedStopEndsLocalRun(Context app) {
        Context context = isolated(app, "agent_failed_stop_");
        ChatHub hub = new ChatHub(context, args -> { throw new IllegalStateException("Termux unavailable"); });
        Sessions.Session session = hub.sessions.current;
        try {
            session.running = true;
            session.callbackRun = "failed-stop-run";
            session.streamText = "Partial reply before the failure";
            hub.stop(session);
            check(!session.running && session.status.isEmpty(), "Failed Stop left the chat permanently running");
            check(session.streamText.isEmpty() && session.messages.stream().anyMatch(m -> m.text.equals("Partial reply before the failure")), "Failed Stop lost the received text");
            check(session.messages.stream().anyMatch(m -> m.kind == Bubble.Kind.ERROR && m.text.contains("polecenie Stop nie dotarło do klienta")), "Stop failure was presented as successful termination");
        } finally {
            hub.delete(session);
            context.getSharedPreferences("buddy_sessions", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    static void busyRequestRecoversSurvivingHelper(Context app) throws Exception {
        Context context = isolated(app, "agent_surviving_helper_");
        java.util.List<String[]> commands = new java.util.ArrayList<>();
        ChatHub hub = new ChatHub(context, args -> commands.add(args.clone()));
        Sessions.Session session = hub.sessions.current;
        try {
            session.running = true;
            session.callbackRun = "after-app-restart";
            hub.onEvent(event(session, "buddy_error", "after-app-restart")
                    .put("text", "An earlier request is still running").put("active_run", "before-app-restart"));
            check(commands.size() == 1 && "stop".equals(commands.get(0)[0]) && session.id.equals(commands.get(0)[1])
                    && "before-app-restart".equals(commands.get(0)[2]), "Recovery did not target exactly the surviving helper");
            hub.onEvent(event(session, "buddy_stopped", "before-app-restart"));
            check(session.running, "Old Stop callback ended the new request");
            hub.onEvent(event(session, "buddy_done", "after-app-restart"));
            check(!session.running, "Rejected duplicate request remains running");
            check(session.messages.stream().anyMatch(m -> m.text.contains("Wyślij wiadomość ponownie")), "Recovery gives no retry guidance");
        } finally {
            hub.delete(session);
            context.getSharedPreferences("buddy_sessions", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    static void failedDispatchDoesNotPersistUserMessage(Context app) {
        Context context = isolated(app, "agent_failed_dispatch_");
        java.util.List<String[]> commands = new java.util.ArrayList<>();
        ChatHub hub = new ChatHub(context, args -> { commands.add(args.clone()); throw new IllegalStateException("RUN_COMMAND refused"); });
        Sessions.Session session = hub.sessions.current;
        try {
            Prefs.setHelperStatus(context, "codex");
            Prefs.setHelperVersion(context, SetupState.HELPER_VERSION);
            Prefs.setSignedAgents(context, "codex");
            String error = hub.send(session, "Never dispatched");
            check("RUN_COMMAND refused".equals(error), "Dispatch failure was not returned to the composer: " + error);
            check(!session.running && session.messages.isEmpty(), "Failed dispatch persisted a phantom sent message");
            check(commands.size() == 1 && "ask".equals(commands.get(0)[0]), "No launch was attempted");
            check(McpHost.get(app).principal(commands.get(0)[7]) == null, "Failed start retained a live run credential");
            hub.adopt("codex", "saved-conversation-id", "Cannot adopt");
            check(hub.sessions.current.messages.stream().noneMatch(m -> m.kind == Bubble.Kind.STEP && m.text.startsWith("Kontynuuję")), "Failed adoption claims that resume succeeded");
        } finally {
            hub.delete(session);
            context.getSharedPreferences("buddy_sessions", Context.MODE_PRIVATE).edit().clear().commit();
            context.getSharedPreferences("buddy", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    static void callbackIsolation(Context app) throws Exception {
        ChatHub hub = ChatHub.get(app);
        Sessions.Session session = hub.create("claude");
        try {
            session.callbackRun = "current-run";
            session.running = true;
            hub.onEvent(event(session, "buddy_done", "previous-run"));
            check(session.running, "Late completion stopped the current run");
            hub.onEvent(event(session, "buddy_stopped", "previous-run"));
            check(session.running, "Late Stop stopped the current run");
            JSONObject oldText = event(session, "assistant", "previous-run").put("message",
                    new JSONObject("{\"content\":[{\"type\":\"text\",\"text\":\"stale reply\"}]}"));
            hub.onEvent(oldText);
            check(session.messages.isEmpty(), "Late reply entered the new conversation");
            hub.onEvent(event(session, "buddy_done", ""));
            check(session.running, "Untagged legacy completion stopped a managed run");
            hub.onEvent(event(session, "buddy_started", "current-run"));
            check(session.runAcknowledged, "Helper startup did not acknowledge delivery");
            hub.onEvent(event(session, "buddy_done", "current-run"));
            check(!session.running, "Current completion did not finish the run");
            hub.onEvent(oldText.put("buddy_run", "current-run"));
            check(!session.running && session.messages.isEmpty(), "Completed run resurrected");
            // callbackRun is deliberately runtime-only: the same guard covers process recreation.
            session.callbackRun = "";
            hub.onEvent(oldText);
            check(!session.running && session.messages.isEmpty(), "Callback from before process recreation revived a run");
        } finally {
            session.running = false;
            hub.delete(session);
        }
    }

    static void retentionKeepsRunningChats(Context app) {
        Context isolated = new ContextWrapper(app) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("agent_retention_test_" + name, mode);
            }
        };
        isolated.getSharedPreferences("buddy_sessions", Context.MODE_PRIVATE).edit().clear().commit();
        Sessions sessions = new Sessions(isolated);
        try {
            sessions.all.clear();
            Sessions.Session oldest = null;
            for (int i = 0; i < Sessions.MAX_SESSIONS; i++) {
                Sessions.Session session = sessions.create("codex");
                session.running = true;
                if (oldest == null) oldest = session;
            }
            sessions.create("claude");
            check(sessions.find(oldest.id) == oldest, "Creating a chat evicted an active process");
            check(sessions.runningCount() == Sessions.MAX_SESSIONS, "Running chat disappeared from Stop");
            final int[] evicted = {0};
            sessions.onEvicted = session -> evicted[0]++;
            oldest.running = false;
            sessions.create("claude");
            check(sessions.find(oldest.id) == null, "Finished oldest chat was not pruned");
            check(evicted[0] > 0, "Eviction did not release backend resources");
            check(sessions.all.size() == Sessions.MAX_SESSIONS, "Inactive history retention is unbounded");
        } finally {
            isolated.getSharedPreferences("buddy_sessions", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    static void streamingBoundsAndSubagents(Context app) throws Exception {
        ChatHub hub = ChatHub.get(app);
        Sessions.Session session = hub.create("claude");
        try {
            StringBuilder large = new StringBuilder();
            for (int i = 0; i < 300000; i++) large.append('x');
            JSONObject block = new JSONObject().put("type", "text").put("text", large.toString());
            JSONObject stream = event(session, "stream_event", "").put("event",
                    new JSONObject().put("type", "content_block_start").put("content_block", block));
            hub.onEvent(stream);
            check(session.streamText.length() == 262144, "Initial streamed block bypassed the text limit");
            session.streamText = "main answer";
            JSONObject child = event(session, "assistant", "").put("parent_tool_use_id", "child-agent")
                    .put("message", new JSONObject("{\"content\":[{\"type\":\"text\",\"text\":\"child answer\"}]}"));
            hub.onEvent(child);
            check("main answer".equals(session.streamText), "Subagent final reply cleared the main stream");
            check(session.messages.isEmpty(), "Subagent final reply entered main conversation");
        } finally {
            session.running = false;
            session.streamText = "";
            hub.delete(session);
        }
    }

    private static final class Updates implements ChatHub.Listener {
        final java.util.List<Sessions.Msg> updated = new java.util.ArrayList<>();
        @Override public void onChatsChanged() {}
        @Override public void onMessage(Sessions.Session ses, Sessions.Msg msg) {}
        @Override public void onMessageUpdated(Sessions.Session ses, Sessions.Msg msg) { updated.add(msg); }
    }

    private static JSONObject toolUse(Sessions.Session session, String run, String id, String name, JSONObject input) throws Exception {
        JSONObject block = new JSONObject().put("type", "tool_use").put("id", id).put("name", name).put("input", input);
        return event(session, "assistant", run).put("parent_tool_use_id", JSONObject.NULL)
                .put("message", new JSONObject().put("content", new JSONArray().put(block)));
    }

    private static JSONObject toolResult(Sessions.Session session, String run, String id, Object content, boolean error) throws Exception {
        JSONObject block = new JSONObject().put("type", "tool_result").put("tool_use_id", id).put("content", content).put("is_error", error);
        return event(session, "user", run).put("message", new JSONObject().put("role", "user").put("content", new JSONArray().put(block)));
    }

    private static Sessions.Msg step(Sessions.Session session, String id) {
        for (Sessions.Msg m : session.messages) if (m.meta != null && id.equals(m.meta.optString("id"))) return m;
        throw new AssertionError("No tool call row for " + id);
    }

    /** Tool calls become STEP rows with structured meta; results update them in place. */
    static void toolCallsCarryResults(Context app) throws Exception {
        Context context = isolated(app, "agent_tool_meta_");
        ChatHub hub = new ChatHub(context, args -> {});
        Updates listener = new Updates();
        hub.addListener(listener);
        Sessions.Session session = hub.sessions.current;
        try {
            session.running = true;
            session.callbackRun = "tool-run";
            hub.onEvent(toolUse(session, "tool-run", "toolu_bash", "Bash", new JSONObject().put("command", "ls -la /sdcard\necho done")));
            hub.onEvent(toolUse(session, "tool-run", "toolu_read", "Read", new JSONObject().put("file_path", "/data/home/project/src/Main.java")));
            hub.onEvent(toolUse(session, "tool-run", "toolu_phone", "mcp__phone__type_text", new JSONObject().put("text", "Zażółć gęślą jaźń").put("element", 4)));
            hub.onEvent(toolUse(session, "tool-run", "toolu_bash", "Bash", new JSONObject().put("command", "ls")));
            Sessions.Msg bash = step(session, "toolu_bash"), read = step(session, "toolu_read"), phone = step(session, "toolu_phone");
            check(session.messages.stream().filter(m -> m.kind == Bubble.Kind.STEP).count() == 3, "A repeated tool_use block added a second row");
            check(bash.kind == Bubble.Kind.STEP && bash.text.equals(ChatHub.describeTool("Bash", new JSONObject().put("command", "ls -la /sdcard\necho done"))), "Step text no longer comes from describeTool");
            check("Bash".equals(bash.meta.optString("tool")) && "ls -la /sdcard echo done".equals(bash.meta.optString("args"))
                    && "running".equals(bash.meta.optString("state")), "Bash meta: " + bash.meta);
            check("src/Main.java".equals(read.meta.optString("args")), "File arguments are not the last two path segments: " + read.meta);
            check("mcp__phone__type_text".equals(phone.meta.optString("tool")) && "text: Zażółć gęślą jaźń, element: 4".equals(phone.meta.optString("args")), "MCP arguments: " + phone.meta);
            check(session.status.equals(phone.text), "Status does not follow the newest tool call");

            StringBuilder output = new StringBuilder("\n");
            for (int i = 1; i <= 30; i++) output.append("line ").append(i).append("   \n");
            hub.onEvent(toolResult(session, "tool-run", "toolu_bash", output.toString(), false));
            check("ok".equals(bash.meta.optString("state")) && bash.meta.optInt("lines") == 30, "Bash result state/lines: " + bash.meta);
            check(bash.meta.optString("result").startsWith("line 1") && bash.meta.optString("result").endsWith("line 20"), "Result is not the first 20 lines: " + bash.meta);
            check(listener.updated.size() == 1 && listener.updated.get(0) == bash, "Result did not notify onMessageUpdated");

            JSONArray blocks = new JSONArray().put(new JSONObject().put("type", "text").put("text", "Brak elementu"))
                    .put(new JSONObject().put("type", "image").put("source", new JSONObject().put("data", "AAAA")));
            hub.onEvent(toolResult(session, "tool-run", "toolu_phone", blocks, true));
            check("error".equals(phone.meta.optString("state")) && "Brak elementu\n[obraz]".equals(phone.meta.optString("result"))
                    && phone.meta.optInt("lines") == 2, "Error result: " + phone.meta);
            check(!ChatHub.THINKING.equals(session.status), "Status reset while a tool is still running");

            hub.onEvent(toolResult(session, "tool-run", "toolu_read", "child output", false).put("parent_tool_use_id", "toolu_agent"));
            check("running".equals(read.meta.optString("state")), "A subagent's tool result changed the main conversation");
            hub.onEvent(toolResult(session, "previous-run", "toolu_read", "stale", false));
            check("running".equals(read.meta.optString("state")), "A stale run's tool result was applied");

            Sessions.Msg saved = null;
            for (Sessions.Msg m : new Sessions(context).find(session.id).messages) if (m.meta != null && "toolu_phone".equals(m.meta.optString("id"))) saved = m;
            check(saved != null && "error".equals(saved.meta.optString("state")) && "Brak elementu\n[obraz]".equals(saved.meta.optString("result")), "Tool result was not persisted");

            hub.onEvent(event(session, "buddy_done", "tool-run"));
            check(!session.running && "ok".equals(read.meta.optString("state")), "Completion left a tool call running: " + read.meta);
            check(listener.updated.contains(read), "Completion did not notify the settled tool call");

            session.running = true;
            session.callbackRun = "stopped-run";
            hub.onEvent(toolUse(session, "stopped-run", "toolu_wait", "mcp__phone__confirm", new JSONObject().put("text", "Wysłać?")));
            hub.onEvent(event(session, "buddy_stopped", "stopped-run"));
            Sessions.Msg waiting = step(session, "toolu_wait");
            check("error".equals(waiting.meta.optString("state")) && "Przerwano".equals(waiting.meta.optString("result")), "Stop left a tool call running: " + waiting.meta);

            session.running = true;
            session.callbackRun = "todo-run";
            JSONArray todos = new JSONArray()
                    .put(new JSONObject().put("content", "Utworzyć projekt").put("status", "completed"))
                    .put(new JSONObject().put("content", "Zbudować APK").put("status", "in_progress"))
                    .put(new JSONObject().put("content", "Zainstalować").put("status", "pending"));
            hub.onEvent(toolUse(session, "todo-run", "toolu_todo", "TodoWrite", new JSONObject().put("todos", todos)));
            Sessions.Msg plan = step(session, "toolu_todo");
            check("3 zadania".equals(plan.meta.optString("args")) && plan.meta.optString("result").startsWith("[x] Utworzyć projekt"), "Plan: " + plan.meta);
            hub.onEvent(toolResult(session, "todo-run", "toolu_todo", "Todos have been modified successfully.", false));
            check("ok".equals(plan.meta.optString("state")) && plan.meta.optString("result").contains("[ ] Zainstalować"), "Tool boilerplate replaced the plan: " + plan.meta);
            hub.stop(session);
            check(!session.running, "Stop did not end the run");
        } finally {
            hub.removeListener(listener);
            session.running = false;
            hub.delete(session);
            context.getSharedPreferences("buddy_sessions", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    /** Argument summaries stay short and readable for every tool family. */
    static void toolArgumentSummaries() throws Exception {
        check("app/build.gradle".equals(ChatHub.toolArgs("Edit", new JSONObject().put("file_path", "/home/w/app/build.gradle"))), "Edit path");
        check("TODO · src/main".equals(ChatHub.toolArgs("Grep", new JSONObject().put("pattern", "TODO").put("path", "/w/app/src/main"))), "Grep pattern and path");
        check("https://example.com".equals(ChatHub.toolArgs("WebFetch", new JSONObject().put("url", "https://example.com").put("prompt", "x"))), "WebFetch url");
        check("Sprawdź testy".equals(ChatHub.toolArgs("Task", new JSONObject().put("description", "Sprawdź testy").put("prompt", "long"))), "Task description");
        check("1 zadanie".equals(ChatHub.toolArgs("TodoWrite", new JSONObject().put("todos", new JSONArray().put(new JSONObject())))), "One task");
        check("".equals(ChatHub.toolArgs("mcp__phone__device_status", new JSONObject())), "No arguments");
        StringBuilder longValue = new StringBuilder();
        for (int i = 0; i < 100; i++) longValue.append('x');
        String mcp = ChatHub.toolArgs("mcp__phone__usb_write", new JSONObject().put("a", longValue.toString()).put("b", 2).put("c", true).put("d", "hidden"));
        check(mcp.startsWith("a: " + longValue.substring(0, 39) + "…, b: 2, c: true") && !mcp.contains("hidden") && mcp.length() <= 120, "MCP pairs: " + mcp);
        check(ChatHub.toolArgs("Bash", new JSONObject().put("command", longValue.toString() + longValue)).length() == 120, "Total clip");
    }

    /** A run whose helper has exited without buddy_done ends; a live helper is never cut off. */
    static void stallWatchdog(Context app) throws Exception {
        Context context = isolated(app, "agent_watchdog_");
        java.util.List<String[]> commands = new java.util.ArrayList<>();
        ChatHub hub = new ChatHub(context, args -> commands.add(args.clone()));
        java.lang.reflect.Method stalls = ChatHub.class.getDeclaredMethod("checkStalls");
        stalls.setAccessible(true);
        Sessions.Session session = hub.sessions.current;
        final Boolean[] alive = {Boolean.TRUE};
        hub.probe = run -> alive[0];
        try {
            session.running = true;
            session.runAcknowledged = true;
            session.callbackRun = "watched-run";
            hub.onEvent(toolUse(session, "watched-run", "toolu_long", "Bash", new JSONObject().put("command", "./gradlew build")));
            session.lastEventAt = android.os.SystemClock.elapsedRealtime() - 3 * 3600 * 1000L;
            stalls.invoke(hub);
            check(session.running, "Watchdog cut off a live helper running a long tool");
            alive[0] = null;
            session.lastEventAt = android.os.SystemClock.elapsedRealtime() - 5 * 60 * 1000L;
            stalls.invoke(hub);
            check(session.running, "Unknown liveness ended a run after a short silence");
            alive[0] = Boolean.FALSE;
            session.lastEventAt = android.os.SystemClock.elapsedRealtime() - 20 * 1000L;
            stalls.invoke(hub);
            check(session.running, "Watchdog did not wait for late callbacks");
            session.lastEventAt = android.os.SystemClock.elapsedRealtime() - 61 * 1000L;
            stalls.invoke(hub);
            check(!session.running && session.status.isEmpty(), "Dead helper left the chat running");
            check(session.messages.stream().anyMatch(m -> m.kind == Bubble.Kind.ERROR && m.text.equals("Proces agenta zakończył się bez odpowiedzi.")), "No stall error");
            check("error".equals(step(session, "toolu_long").meta.optString("state")), "Stalled tool call still running");
            check(commands.stream().anyMatch(c -> "stop".equals(c[0]) && "watched-run".equals(c[2])), "Helper run state was not cleaned up");
        } finally {
            session.running = false;
            hub.delete(session);
            context.getSharedPreferences("buddy_sessions", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    /** Helper callbacks (curl -m 5) are answered while every tool worker waits for the user.
     *  Runs off the main thread: it uses real loopback sockets. */
    static void eventsBypassBlockedTools() throws Exception {
        final String token = "agent-regression-only";
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger events = new java.util.concurrent.atomic.AtomicInteger();
        McpServer server = new McpServer(token, new McpServer.Handler() {
            public JSONArray listTools() { return new JSONArray(); }
            public JSONObject callTool(String name, JSONObject args) throws Exception {
                release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                return new JSONObject();
            }
            public void onEvent(JSONObject event) { events.incrementAndGet(); }
            public java.io.File attachment(String id) { return null; }
        }, 0);
        java.util.List<java.net.Socket> waiting = new java.util.ArrayList<>();
        server.start();
        try {
            java.lang.reflect.Field field = McpServer.class.getDeclaredField("socket");
            field.setAccessible(true);
            int port = -1;
            long until = android.os.SystemClock.elapsedRealtime() + 5000;
            while (port < 0 && android.os.SystemClock.elapsedRealtime() < until) {
                java.net.ServerSocket socket = (java.net.ServerSocket) field.get(server);
                if (socket != null && server.listening()) port = socket.getLocalPort();
                else Thread.sleep(20);
            }
            check(port > 0, "MCP fixture did not bind");
            String body = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
                    .put("params", new JSONObject().put("name", "confirm").put("arguments", new JSONObject())).toString();
            byte[] call = ("POST /mcp HTTP/1.1\r\nAuthorization: Bearer " + token + "\r\nContent-Length: "
                    + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + "\r\n\r\n" + body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            // More blocked approvals than the old shared pool had threads and queue slots in total.
            for (int i = 0; i < 24; i++) {
                java.net.Socket s = new java.net.Socket("127.0.0.1", port);
                waiting.add(s);
                s.getOutputStream().write(call);
                s.getOutputStream().flush();
            }
            Thread.sleep(300);
            long started = android.os.SystemClock.elapsedRealtime();
            String reply;
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
                s.setSoTimeout(5000);
                s.getOutputStream().write(("POST /event HTTP/1.1\r\nAuthorization: Bearer " + token
                        + "\r\nContent-Length: 2\r\n\r\n{}").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                s.getOutputStream().flush();
                byte[] head = new byte[12];
                int n = 0, r;
                while (n < head.length && (r = s.getInputStream().read(head, n, head.length - n)) > 0) n += r;
                reply = new String(head, 0, n, java.nio.charset.StandardCharsets.US_ASCII);
            }
            check(reply.equals("HTTP/1.1 204") && events.get() == 1, "Event was starved by blocked tool calls: " + reply);
            check(android.os.SystemClock.elapsedRealtime() - started < 3000, "Event waited for tool workers");
        } finally {
            release.countDown();
            server.stop();
            for (java.net.Socket s : waiting) s.close();
        }
    }

    /** Only as many chats may work as the runtime has request slots. */
    static void runningLimit(Context app) {
        Context context = isolated(app, "agent_running_limit_");
        ChatHub hub = new ChatHub(context, args -> {});
        java.util.List<Sessions.Session> busy = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < ChatHub.MAX_RUNNING; i++) {
                Sessions.Session s = hub.create("claude");
                s.running = true;
                busy.add(s);
            }
            Sessions.Session next = hub.create("claude");
            String error = hub.send(next, "Kolejne zadanie");
            check(("Za dużo równoległych rozmów (maks. " + ChatHub.MAX_RUNNING + "). Zatrzymaj jedną z nich.").equals(error), "Limit message: " + error);
            check(next.messages.isEmpty() && !next.running, "Refused message was kept");
            check(hub.send(next, "/status") == null, "Local /status was refused by the running limit");
        } finally {
            for (Sessions.Session s : busy) s.running = false;
            context.getSharedPreferences("buddy_sessions", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    static void persistenceMigration(Context app) throws Exception {
        Context isolated = new ContextWrapper(app) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("agent_migration_test_" + name, mode);
            }
        };
        SharedPreferences preferences = isolated.getSharedPreferences("buddy_sessions", Context.MODE_PRIVATE);
        try {
            JSONArray messages = new JSONArray();
            for (int i = 0; i < 151; i++) messages.put(new JSONObject().put("k", "BUDDY").put("t", "Zażółć " + i));
            StringBuilder large = new StringBuilder();
            for (int i = 0; i < 300000; i++) large.append('ą');
            messages.put(new JSONObject().put("k", "USER").put("t", large.toString())
                    .put("f", new JSONArray().put("saved_attachment.txt")));
            JSONObject older = new JSONObject().put("id", "legacy-first").put("backend", "claude")
                    .put("title", "Complete history").put("model", "sonnet").put("updated", 1234).put("messages", messages);
            JSONObject newer = new JSONObject().put("id", "legacy-second").put("backend", "codex")
                    .put("title", "Second history").put("messages", new JSONArray());
            preferences.edit().clear().putString("all", new JSONArray().put(older).put(newer).toString())
                    .putString("current", "legacy-second").commit();
            Sessions migrated = new Sessions(isolated);
            check(migrated.all.size() == 2 && "legacy-second".equals(migrated.current.id), "Migration changed chat order or selection");
            Sessions.Session first = migrated.find("legacy-first");
            check(first.messages.size() == 152, "Migration truncated historical messages");
            check(first.messages.get(151).text.equals(large.toString()), "Migration truncated historical text");
            check(first.messages.get(151).files.get(0).equals("saved_attachment.txt"), "Migration lost attachment metadata");
            check("sonnet".equals(first.model) && first.updated == 1234, "Migration lost session metadata");
            check(preferences.contains("index_v2") && !preferences.contains("all"), "Migration was not published atomically");
            String unchanged = preferences.getString("session_v2_legacy-first", null);
            migrated.add(migrated.find("legacy-second"), Bubble.Kind.BUDDY, "Another reply");
            check(unchanged == preferences.getString("session_v2_legacy-first", null), "Appending to one chat reserialized untouched history");
            Sessions restored = new Sessions(isolated);
            check(restored.find("legacy-first").messages.size() == 152, "Indexed reload lost migrated history");
            check(restored.find("legacy-second").messages.size() == 1, "Changed conversation did not persist");
            migrated.remove(migrated.find("legacy-second"));
            check(!preferences.contains("session_v2_legacy-second"), "Deleted conversation record remains");

            String partiallyInvalid = new JSONArray().put(older).put("invalid record").toString();
            preferences.edit().clear().putString("all", partiallyInvalid).commit();
            Sessions recovered = new Sessions(isolated);
            check(recovered.find("legacy-first") != null, "A malformed record erased the other conversation");
            check(partiallyInvalid.equals(preferences.getString("all", null)), "Incomplete migration discarded the original backup");
        } finally {
            preferences.edit().clear().commit();
        }
    }
}
