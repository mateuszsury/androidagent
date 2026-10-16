package dev.mateuszsury.androidagent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Chats shared by the app screens and the bubble. Runs on the main thread. */
final class ChatHub {
    interface Listener {
        void onChatsChanged();

        void onMessage(Sessions.Session ses, Sessions.Msg msg);

        /** An earlier message changed in place (a tool call got its result or final state). */
        default void onMessageUpdated(Sessions.Session ses, Sessions.Msg msg) {
        }
    }

    interface CommandRunner {
        void run(String... args);
    }

    /** Whether the helper process of one request (by its callback run id) still exists:
     *  TRUE while queued or running, FALSE once it ended, null when this cannot be known. */
    interface RunProbe {
        Boolean alive(String run);
    }

    /** Chats that may work at the same time; the embedded runtime sizes its request pool from it. */
    static final int MAX_RUNNING = 6;
    static final String THINKING = "Myślę…", WORKING = "Pracuję…";
    private static final long NO_RESPONSE_MS = 30000;
    /** Stall watchdog: how often it looks, how long a run whose helper process has ended may
     *  stay silent (late callbacks), and the much longer limit when liveness is unknown. */
    private static final long WATCHDOG_MS = 15000, GONE_SILENCE_MS = 60000, UNKNOWN_SILENCE_MS = 20 * 60000;
    /** A tool result keeps at most this much text in the conversation. */
    private static final int RESULT_LINES = 20, RESULT_CHARS = 2000;
    private static final String TOOL_PREFIX = "mcp__phone__";
    private static ChatHub instance;

    final Sessions sessions;
    private final Context app;
    private final CommandRunner commands;
    /** Replaced by the production constructor; fixtures cannot see helper processes. */
    RunProbe probe = run -> null;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Sessions.Session> viewing = new ArrayList<>();
    private Sessions.Session foregroundChat;
    private String flash;
    private boolean watchdogArmed;
    private final Runnable watchdog = this::checkStalls;
    /** Receives buddy_procs / buddy_recent / buddy_transcript replies for the monitor screens. */
    interface MonitorListener {
        void onMonitor(JSONObject ev);
    }

    MonitorListener monitor;

    void onMonitorEvent(JSONObject ev) {
        if (monitor != null) monitor.onMonitor(ev);
    }

    /** Turn an existing Claude Code / Codex session into a Buddy chat that continues it. */
    Sessions.Session adopt(String backend, String agentSessionId, String title) {
        Sessions.Session s = sessions.create(backend);
        s.title = title == null ? "" : title;
        try {
            commands.run("adopt", s.id, backend, agentSessionId);
        } catch (Exception e) {
            add(s, Bubble.Kind.ERROR, e.getMessage());
            changed();
            return s;
        }
        add(s, Bubble.Kind.STEP, "Kontynuuję wcześniejszą sesję " + s.brainName() + ". Jeśli jest nadal otwarta w terminalu, najpierw ją tam zamknij.");
        changed();
        return s;
    }

    static synchronized ChatHub get(Context c) {
        if (instance == null) instance = new ChatHub(c.getApplicationContext());
        return instance;
    }

    private ChatHub(Context app) {
        this(app, args -> Termux.run(app, args));
        probe = run -> {
            try {
                return EmbeddedRuntime.get(app).askAlive(run);
            } catch (RuntimeException unavailable) {
                return null;
            }
        };
    }

    ChatHub(Context app, CommandRunner commands) {
        this.app = app;
        this.commands = commands;
        sessions = new Sessions(app);
        sessions.onEvicted = this::releaseSession;
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    void setViewing(Sessions.Session ses, boolean on) {
        if (on) {
            if (!viewing.contains(ses)) viewing.add(ses);
            ses.unread = false;
        } else {
            viewing.remove(ses);
        }
        changed();
    }

    void setForegroundChat(Sessions.Session ses, boolean on) {
        if (on) foregroundChat = ses;
        else if (foregroundChat == ses) foregroundChat = null;
        changed();
    }

    boolean inForegroundChat() {
        return foregroundChat != null && foregroundChat == sessions.current;
    }

    Sessions.Session create(String backend) {
        Sessions.Session s = sessions.create(backend);
        changed();
        return s;
    }

    void select(Sessions.Session s) {
        sessions.select(s);
        changed();
    }

    void delete(Sessions.Session s) {
        releaseSession(s);
        sessions.remove(s);
        changed();
    }

    private void releaseSession(Sessions.Session s) {
        McpHost.get(app).revoke(s.id);
        UsbSessions.get(app).closeOwner("pending:"+s.id);
        try {
            commands.run("delete", s.id);
        } catch (Exception ignored) {
        }
        viewing.remove(s);
        if (foregroundChat == s) foregroundChat = null;
    }

    /** Sends a message. Returns an error text, or null when sent. */
    String send(final Sessions.Session ses, String text) {
        return send(ses, text, null);
    }

    /** Sends a message with attachments (ids from Attachments); the helper fetches them before the agent runs. */
    String send(final Sessions.Session ses, String text, List<String> files) {
        if (ses.running) return "Ta rozmowa nadal pracuje. Zatrzymaj ją albo napisz w innej rozmowie.";
        if ("/status".equals(text.trim())) {
            add(ses, Bubble.Kind.USER, text);
            try { add(ses, Bubble.Kind.BUDDY, DeviceState.summary(app)); }
            catch (Exception e) { add(ses, Bubble.Kind.ERROR, e.getMessage()); }
            changed();
            return null;
        }
        // Each working chat holds one runtime request slot; a queued request would only time out.
        if (sessions.runningCount() >= MAX_RUNNING)
            return "Za dużo równoległych rozmów (maks. " + MAX_RUNNING + "). Zatrzymaj jedną z nich.";
        if (!McpHost.get(app).running()) return "Otwórz wbudowane środowisko, aby uruchomić most narzędzi. Sterowanie ekranem jest opcjonalne.";
        if (!SetupState.helperCurrent(app) || !SetupState.agentReady(app, ses.backend))
            return "Przygotuj " + ses.brainName() + " we wbudowanym środowisku i zaloguj się do własnego konta. /status działa lokalnie bez modelu.";
        boolean hasFiles = files != null && !files.isEmpty();
        if (text.isEmpty() && !hasFiles) return null;
        String forAgent = text.isEmpty() && hasFiles ? "Zobacz, co załączam." : text;
        McpHost.get(app).allowControl();
        // The helper echoes this identifier on every callback. A stopped run may still have
        // an HTTP request in flight when the next request in the same chat begins.
        ses.callbackRun = java.util.UUID.randomUUID().toString();
        try {
            commands.run("ask", ses.id, ses.backend, Prefs.devMode(app) ? "1" : "0", forAgent, ses.model,
                    hasFiles ? android.text.TextUtils.join(",", files) : "", McpHost.get(app).credential(ses.id), ses.callbackRun);
        } catch (Exception e) {
            McpHost.get(app).revoke(ses.id);
            return e.getMessage();
        }
        sessions.add(ses, Bubble.Kind.USER, text, hasFiles ? new ArrayList<>(files) : null);
        Sessions.Msg um = ses.messages.get(ses.messages.size() - 1);
        for (Listener l : new ArrayList<>(listeners)) l.onMessage(ses, um);
        startRun(ses);
        ses.status = THINKING;
        changed();
        final int id = ses.runId;
        main.postDelayed(() -> {
            if (ses.running && id == ses.runId && !ses.runAcknowledged) {
                stop(ses);
                add(ses, Bubble.Kind.ERROR, "Brak odpowiedzi klienta. Otwórz panel środowiska w androidagent i sprawdź jego stan oraz logowanie.");
                changed();
            }
        }, NO_RESPONSE_MS);
        return null;
    }

    /** Change a chat's model; the next message uses it, and the conversation continues. */
    void setModel(Sessions.Session ses, String model) {
        ses.model = model == null ? "" : model.trim();
        add(ses, Bubble.Kind.STEP, "Model: " + Models.label(ses.backend, ses.model));
        changed();
    }

    /** Stop every chat that is working (the bubble's stop button). */
    void stopAll() {
        for (Sessions.Session s : new java.util.ArrayList<>(sessions.all)) if (s.running) stop(s);
    }

    void stop(Sessions.Session ses) {
        McpHost.get(app).revoke(ses.id);
        if (!ses.running) return;
        String deliveryError = null;
        try {
            commands.run("stop", ses.id, ses.callbackRun);
        } catch (Exception e) {
            deliveryError = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        flushStream(ses);
        ses.running = false;
        ses.status = "";
        settleSteps(ses, false, "Przerwano");
        if (deliveryError == null) add(ses, Bubble.Kind.STEP, "Zatrzymano.");
        else add(ses, Bubble.Kind.ERROR, "Dostęp do narzędzi telefonu został zakończony, ale polecenie Stop nie dotarło do klienta: "
                + deliveryError + " Sprawdź procesy we wbudowanym środowisku.");
        changed();
    }

    void note(Sessions.Session ses, Bubble.Kind kind, String text) {
        add(ses, kind, text);
        changed();
    }

    /** Called for any phone-tool use; keeps running chats from timing out. */
    void onToolActivity() {
        for (Sessions.Session s : sessions.all) if (s.running) s.lastEventAt = SystemClock.elapsedRealtime();
    }

    void onEvent(JSONObject ev) {
        Sessions.Session ses = sessions.find(ev.optString("buddy_session", null));
        if (ses == null) return; // unknown chat (deleted, or started outside Buddy)
        // Untagged external/legacy callbacks remain usable only until a managed run starts.
        // A completed/stopped managed run cannot be resurrected by delayed CLI output.
        String callback = ev.optString("buddy_run");
        if ((!ses.callbackRun.isEmpty() || !callback.isEmpty()) &&
                (ses.callbackRun.isEmpty() || !ses.callbackRun.equals(callback) || !ses.running)) return;
        // Subagent traffic (its own tool calls and results) stays out of the main conversation.
        if (!ev.isNull("parent_tool_use_id")) return;
        ses.lastEventAt = SystemClock.elapsedRealtime();
        ses.runAcknowledged = true;
        String type = ev.optString("type");
        if (!ses.running && ("system".equals(type) || "assistant".equals(type))) startRun(ses);
        switch (type) {
            case "buddy_started":
                ses.status = THINKING;
                break;
            case "stream_event": {
                JSONObject part = ev.optJSONObject("event");
                if (part == null) break;
                String eventType = part.optString("type");
                if ("message_start".equals(eventType)) ses.streamText = "";
                else if ("content_block_start".equals(eventType)) {
                    JSONObject block = part.optJSONObject("content_block");
                    if (block != null && "text".equals(block.optString("type")))
                        appendStream(ses, block.optString("text"));
                } else if ("content_block_delta".equals(eventType)) {
                    JSONObject delta = part.optJSONObject("delta");
                    if (delta != null && "text_delta".equals(delta.optString("type")))
                        appendStream(ses, delta.optString("text"));
                }
                break;
            }
            case "system":
                if ("init".equals(ev.optString("subtype"))) {
                    ses.status = THINKING;
                    ses.agentSessionId = ev.optString("session_id");
                    JSONArray servers = ev.optJSONArray("mcp_servers");
                    ses.phoneConnected = false;
                    if (servers != null) for (int i = 0; i < servers.length(); i++) {
                        JSONObject server = servers.optJSONObject(i);
                        if (server != null && "phone".equals(server.optString("name")))
                            ses.phoneConnected = "connected".equals(server.optString("status"));
                    }
                    ses.phoneToolCount = 0;
                    JSONArray tools = ev.optJSONArray("tools");
                    ses.agentTools.clear();
                    if (tools != null) for (int i = 0; i < tools.length(); i++) {
                        String tool = tools.optString(i);
                        ses.agentTools.add(tool);
                        if (tool.startsWith(TOOL_PREFIX)) ses.phoneToolCount++;
                    }
                }
                break;
            case "assistant": {
                ses.streamText = "";
                JSONObject msg = ev.optJSONObject("message");
                JSONArray content = msg == null ? null : msg.optJSONArray("content");
                if (content == null) break;
                for (int i = 0; i < content.length(); i++) {
                    JSONObject c = content.optJSONObject(i);
                    if (c == null) continue;
                    if ("text".equals(c.optString("type"))) {
                        String t = c.optString("text").trim();
                        if (!t.isEmpty()) {
                            if (!ev.optString("error").isEmpty()) add(ses, Bubble.Kind.ERROR, t);
                            else reply(ses, t);
                        }
                    } else if ("tool_use".equals(c.optString("type"))) {
                        String name = c.optString("name");
                        JSONObject input = c.optJSONObject("input");
                        String id = c.optString("id");
                        String step = describeTool(name, input);
                        // A repeated block (same id) must not add a second row.
                        if (step != null && (id.isEmpty() || findStep(ses, id) == null)) {
                            add(ses, Bubble.Kind.STEP, step, toolMeta(name, input, id));
                            ses.status = step;
                        }
                    }
                }
                break;
            }
            case "user": {
                // Claude Code stream-json reports tool output as user turns with tool_result blocks.
                JSONObject msg = ev.optJSONObject("message");
                JSONArray content = msg == null ? null : msg.optJSONArray("content");
                if (content == null) break;
                List<Sessions.Msg> updated = new ArrayList<>();
                for (int i = 0; i < content.length(); i++) {
                    JSONObject c = content.optJSONObject(i);
                    if (c == null || !"tool_result".equals(c.optString("type"))) continue;
                    Sessions.Msg step = findStep(ses, c.optString("tool_use_id"));
                    if (step == null || updated.contains(step)) continue;
                    applyResult(step.meta, c.opt("content"), c.optBoolean("is_error"));
                    updated.add(step);
                }
                if (updated.isEmpty()) break;
                sessions.persist(ses);
                for (Sessions.Msg m : updated) notifyUpdated(ses, m);
                if (ses.running && !hasRunningStep(ses)) ses.status = THINKING;
                break;
            }
            case "result": {
                boolean error = ev.optBoolean("is_error") || !"success".equals(ev.optString("subtype", "success"));
                ses.resultSeen = true;
                String r = ev.optString("result", "").trim();
                if (r.isEmpty() && ev.optJSONArray("errors") != null) {
                    JSONArray errors = ev.optJSONArray("errors");
                    StringBuilder text = new StringBuilder();
                    for (int i = 0; i < errors.length(); i++) {
                        if (i > 0) text.append('\n');
                        text.append(errors.optString(i));
                    }
                    r = text.toString();
                }
                if (!ses.streamText.isEmpty()) {
                    String pending = !error && !r.isEmpty() ? r : ses.streamText;
                    ses.streamText = "";
                    reply(ses, pending);
                }
                if (error) {
                    settleSteps(ses, false, null);
                    add(ses, Bubble.Kind.ERROR, r.isEmpty() ? ses.brainName() + " przerwał pracę." : r);
                } else if (!ses.gotText && !r.isEmpty()) {
                    reply(ses, r);
                }
                break;
            }
            case "buddy_error":
                flushStream(ses);
                add(ses, Bubble.Kind.ERROR, ev.optString("text", "Coś poszło nie tak."));
                String activeRun = ev.optString("active_run");
                if (activeRun.matches("[A-Za-z0-9_-]{1,64}") && !activeRun.equals(ses.callbackRun)) {
                    // The app process may have restarted while its helper survived.
                    // Cancel that exact old run; its late callbacks cannot finish this one.
                    try {
                        commands.run("stop", ses.id, activeRun);
                        add(ses, Bubble.Kind.STEP, "Poproszono klienta o zatrzymanie poprzedniego zadania. Wyślij wiadomość ponownie po jego zakończeniu.");
                    } catch (Exception e) {
                        add(ses, Bubble.Kind.ERROR, "Nie udało się zatrzymać poprzedniego zadania klienta: " + e.getMessage());
                    }
                }
                break;
            case "buddy_stopped":
                flushStream(ses);
                ses.running = false;
                McpHost.get(app).revoke(ses.id);
                ses.status = "";
                settleSteps(ses, false, "Przerwano");
                add(ses, Bubble.Kind.STEP, "Zatrzymano.");
                break;
            case "buddy_done":
                flushStream(ses);
                if (ses.running) {
                    ses.running = false;
                    McpHost.get(app).revoke(ses.id);
                    ses.status = "";
                    // Results that never arrived: the run's outcome decides the final state.
                    settleSteps(ses, ses.lastError.isEmpty(), null);
                    if (!ses.lastReply.isEmpty()) {
                        Prefs.setHadReply(app);
                        flash(ses.lastReply);
                    }
                }
                break;
            default:
                break;
        }
        changed();
    }

    // ---------------------------------------------------------------- state

    private static void appendStream(Sessions.Session ses, String text) {
        int available = 262144 - ses.streamText.length();
        if (available > 0) ses.streamText += text.length() <= available ? text : text.substring(0, available);
    }

    private void reply(Sessions.Session ses, String text) {
        add(ses, Bubble.Kind.BUDDY, text);
        ses.lastReply = text;
        ses.gotText = true;
    }

    private void flushStream(Sessions.Session ses) {
        if (ses.streamText.isEmpty()) return;
        String text = ses.streamText;
        ses.streamText = "";
        reply(ses, text);
    }

    int runningCount() {
        return sessions.runningCount();
    }

    /** Text for the bubble's status pill, or null for none. */
    String pillText() {
        int n = runningCount();
        if (n > 0) {
            Sessions.Session latest = null;
            for (Sessions.Session s : sessions.all)
                if (s.running && (latest == null || s.lastEventAt > latest.lastEventAt)) latest = s;
            String st = latest == null || latest.status.isEmpty() ? WORKING : latest.status;
            return n > 1 ? n + " " + Sessions.plural(n, "rozmowa", "rozmowy", "rozmów") + " · " + st : st;
        }
        return flash;
    }

    boolean anyUnread() {
        return sessions.anyUnread();
    }

    private void flash(String reply) {
        final String text = reply.replace('\n', ' ');
        flash = text.length() > 60 ? text.substring(0, 59) + "…" : text;
        final String mine = flash;
        main.postDelayed(() -> {
            if (mine.equals(flash)) {
                flash = null;
                changed();
            }
        }, 8000);
    }

    private void startRun(Sessions.Session ses) {
        ses.running = true;
        ses.runAcknowledged = false;
        ses.resultSeen = false;
        ses.gotText = false;
        ses.lastReply = "";
        ses.lastError = "";
        ses.streamText = "";
        ses.lastEventAt = SystemClock.elapsedRealtime();
        ses.runId++;
        armWatchdog();
    }

    // ---------------------------------------------------------------- stall watchdog

    private void armWatchdog() {
        if (watchdogArmed) return;
        watchdogArmed = true;
        main.postDelayed(watchdog, WATCHDOG_MS);
    }

    /** Ends runs whose helper died without its final callbacks (lost buddy_done, killed process).
     *  A live helper is never cut off, however long its tool runs without output. */
    private void checkStalls() {
        watchdogArmed = false;
        long now = SystemClock.elapsedRealtime();
        boolean ended = false;
        for (Sessions.Session s : new ArrayList<>(sessions.all)) {
            // Unacknowledged runs belong to the no-response timer in send().
            if (!s.running || !s.runAcknowledged) continue;
            long silent = now - s.lastEventAt;
            if (silent < GONE_SILENCE_MS) continue;
            Boolean alive = s.callbackRun.isEmpty() ? null : probe.alive(s.callbackRun);
            if (Boolean.FALSE.equals(alive)) {
                abandon(s, true);
                ended = true;
            } else if (alive == null && silent >= UNKNOWN_SILENCE_MS) {
                abandon(s, false);
                ended = true;
            }
        }
        if (runningCount() > 0) armWatchdog();
        if (ended) changed();
    }

    /** Finishes a stalled run locally. {@code processGone}: the helper is known to have exited. */
    private void abandon(Sessions.Session ses, boolean processGone) {
        flushStream(ses);
        ses.running = false;
        ses.status = "";
        McpHost.get(app).revoke(ses.id);
        // Clears the helper's run lock (or ends an unresponsive helper); its callback is ignored.
        try {
            commands.run("stop", ses.id, ses.callbackRun);
        } catch (Exception ignored) {
        }
        // The agent finished its turn and only the completion callback was lost.
        boolean completed = processGone && ses.resultSeen && ses.lastError.isEmpty();
        settleSteps(ses, completed, completed ? null : "Przerwano");
        if (completed) {
            if (!ses.lastReply.isEmpty()) {
                Prefs.setHadReply(app);
                flash(ses.lastReply);
            }
        } else if (processGone) {
            add(ses, Bubble.Kind.ERROR, "Proces agenta zakończył się bez odpowiedzi.");
        } else {
            add(ses, Bubble.Kind.ERROR, "Agent nie dał znaku życia od " + (UNKNOWN_SILENCE_MS / 60000)
                    + " minut, więc zadanie zostało zatrzymane. Wyślij wiadomość ponownie, aby kontynuować.");
        }
    }

    // ---------------------------------------------------------------- messages

    private void add(Sessions.Session ses, Bubble.Kind kind, String text) {
        add(ses, kind, text, null);
    }

    private void add(Sessions.Session ses, Bubble.Kind kind, String text, JSONObject meta) {
        if (text == null) return;
        if (kind == Bubble.Kind.ERROR) {
            if (ses.lastError.equals(text)) return;
            ses.lastError = text;
        }
        Sessions.Msg m = sessions.add(ses, kind, text, meta);
        if (kind == Bubble.Kind.BUDDY && !viewing.contains(ses)) ses.unread = true;
        if (kind == Bubble.Kind.BUDDY && Prefs.speakReplies(app)) Voice.speak(app, text);
        for (Listener l : new ArrayList<>(listeners)) l.onMessage(ses, m);
    }

    private void notifyUpdated(Sessions.Session ses, Sessions.Msg m) {
        for (Listener l : new ArrayList<>(listeners)) l.onMessageUpdated(ses, m);
    }

    /** The newest tool call with this tool_use id, or null. */
    private static Sessions.Msg findStep(Sessions.Session ses, String id) {
        if (id == null || id.isEmpty()) return null;
        for (int i = ses.messages.size() - 1; i >= 0; i--) {
            Sessions.Msg m = ses.messages.get(i);
            if (m.kind == Bubble.Kind.STEP && m.meta != null && id.equals(m.meta.optString("id"))) return m;
        }
        return null;
    }

    private static boolean hasRunningStep(Sessions.Session ses) {
        for (int i = ses.messages.size() - 1; i >= 0; i--)
            if ("running".equals(ses.messages.get(i).state())) return true;
        return false;
    }

    /** Gives every still-running tool call of the chat its final state, persists once and
     *  notifies listeners. {@code note} becomes the result of calls that have none. */
    private void settleSteps(Sessions.Session ses, boolean ok, String note) {
        List<Sessions.Msg> done = new ArrayList<>();
        for (Sessions.Msg m : ses.messages) {
            if (m.kind != Bubble.Kind.STEP || !"running".equals(m.state())) continue;
            try {
                m.meta.put("state", ok ? "ok" : "error");
                if (note != null && m.meta.optString("result").isEmpty()) m.meta.put("result", note).put("lines", 1);
            } catch (JSONException ignored) {
            }
            done.add(m);
        }
        if (done.isEmpty()) return;
        sessions.persist(ses);
        for (Sessions.Msg m : done) notifyUpdated(ses, m);
    }

    private boolean wasRunning;
    private long lastRunEndedAt;

    /** When the last running chat finished (elapsedRealtime). */
    long lastRunEndedAt() {
        return lastRunEndedAt;
    }

    void changed() {
        boolean now = runningCount() > 0;
        if (wasRunning && !now) lastRunEndedAt = SystemClock.elapsedRealtime();
        wasRunning = now;
        for (Listener l : new ArrayList<>(listeners)) l.onChatsChanged();
    }

    // ---------------------------------------------------------------- tool calls

    /** STEP details for a tool_use block; see {@link Sessions.Msg#meta}. */
    static JSONObject toolMeta(String name, JSONObject input, String id) {
        JSONObject meta = new JSONObject();
        try {
            meta.put("tool", name).put("args", toolArgs(name, input));
            if (id != null && !id.isEmpty()) meta.put("id", id);
            meta.put("state", "running");
            if ("TodoWrite".equals(name) && input != null) {
                // The plan itself is the useful output; the tool's own reply is boilerplate.
                JSONArray todos = input.optJSONArray("todos");
                String plan = todoPlan(todos);
                if (!plan.isEmpty()) meta.put("result", head(plan)).put("lines", todos.length());
            }
        } catch (JSONException ignored) {
        }
        return meta;
    }

    /** Stores a tool_result: state, the first lines of its text and the total line count. */
    static void applyResult(JSONObject meta, Object content, boolean error) {
        try {
            if (!error && "TodoWrite".equals(meta.optString("tool")) && meta.has("result")) {
                meta.put("state", "ok");
                return;
            }
            String text = cleanResult(resultText(content));
            meta.put("state", error ? "error" : "ok").put("result", head(text))
                    .put("lines", text.isEmpty() ? 0 : count(text, '\n') + 1);
        } catch (JSONException ignored) {
        }
    }

    /** Text of a tool_result's content: a string, or text/image blocks. */
    static String resultText(Object content) {
        if (content instanceof String) return (String) content;
        if (content instanceof JSONObject) content = new JSONArray().put(content);
        if (!(content instanceof JSONArray)) return "";
        JSONArray blocks = (JSONArray) content;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < blocks.length(); i++) {
            Object item = blocks.opt(i);
            String part = null;
            if (item instanceof String) part = (String) item;
            else if (item instanceof JSONObject) {
                String type = ((JSONObject) item).optString("type");
                if ("text".equals(type)) part = ((JSONObject) item).optString("text");
                else if ("image".equals(type)) part = "[obraz]";
            }
            if (part == null) continue;
            if (text.length() > 0) text.append('\n');
            text.append(part);
        }
        return text.toString();
    }

    /** Terminal-like text: CRLF and carriage-return progress collapsed, ANSI escapes removed,
     *  Claude Code's error wrapper dropped, outer blank lines and trailing spaces trimmed. */
    static String cleanResult(String s) {
        if (s.indexOf('\r') >= 0) {
            s = s.replace("\r\n", "\n");
            if (s.indexOf('\r') >= 0) {
                // Progress output rewrites its line: keep what a terminal would finally show.
                StringBuilder out = new StringBuilder(s.length());
                boolean first = true;
                for (String line : s.split("\n", -1)) {
                    if (!first) out.append('\n');
                    first = false;
                    int end = line.length();
                    while (end > 0 && line.charAt(end - 1) == '\r') end--;
                    int start = end == 0 ? 0 : line.lastIndexOf('\r', end - 1) + 1;
                    out.append(line, start, end);
                }
                s = out.toString();
            }
        }
        if (s.indexOf('\u001B') >= 0)
            s = s.replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B\n]*(?:\u0007|\u001B\\\\)?|\u001B[@-_]", "");
        String trimmed = s.trim();
        if (trimmed.startsWith("<tool_use_error>") && trimmed.endsWith("</tool_use_error>"))
            s = trimmed.substring(16, trimmed.length() - 17);
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        int start = 0;
        while (start < end) {
            int nl = s.indexOf('\n', start);
            if (nl < 0 || nl >= end || !s.substring(start, nl).trim().isEmpty()) break;
            start = nl + 1;
        }
        return s.substring(start, end);
    }

    /** At most RESULT_LINES lines and RESULT_CHARS characters, without trailing whitespace. */
    static String head(String s) {
        int limit = Math.min(s.length(), RESULT_CHARS), cut = limit, lines = 0;
        for (int i = 0; i < limit; i++) {
            if (s.charAt(i) == '\n' && ++lines == RESULT_LINES) {
                cut = i;
                break;
            }
        }
        if (cut < s.length() && cut > 0 && Character.isHighSurrogate(s.charAt(cut - 1))) cut--;
        int end = cut;
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private static String todoPlan(JSONArray todos) {
        if (todos == null) return "";
        StringBuilder plan = new StringBuilder();
        for (int i = 0; i < todos.length(); i++) {
            JSONObject todo = todos.optJSONObject(i);
            if (todo == null) continue;
            String status = todo.optString("status");
            String mark = "completed".equals(status) ? "[x] " : "in_progress".equals(status) ? "[~] " : "[ ] ";
            if (plan.length() > 0) plan.append('\n');
            plan.append(mark).append(todo.optString("content").replace('\n', ' '));
        }
        return plan.toString();
    }

    /** Short one-line summary of a tool call's arguments for the STEP row (≤120 chars). */
    static String toolArgs(String name, JSONObject in) {
        if (in == null) in = new JSONObject();
        String s;
        switch (name == null ? "" : name) {
            case "Bash":
                s = in.optString("command");
                break;
            case "Read":
            case "Write":
            case "Edit":
            case "MultiEdit":
                s = shortPath(in.optString("file_path"));
                break;
            case "NotebookEdit":
                s = shortPath(in.optString("notebook_path", in.optString("file_path")));
                break;
            case "WebFetch":
                s = in.optString("url");
                break;
            case "WebSearch":
                s = in.optString("query");
                break;
            case "Grep":
            case "Glob": {
                s = in.optString("pattern");
                String path = shortPath(in.optString("path"));
                if (!path.isEmpty()) s = s.isEmpty() ? path : s + " · " + path;
                break;
            }
            case "Task":
            case "Agent":
                s = in.optString("description");
                break;
            case "TodoWrite": {
                JSONArray todos = in.optJSONArray("todos");
                int n = todos == null ? 0 : todos.length();
                s = n + " " + Sessions.plural(n, "zadanie", "zadania", "zadań");
                break;
            }
            default:
                // MCP tools (mcp__<server>__<name>) and anything else: compact key: value pairs.
                s = pairs(in);
                break;
        }
        return clip(s.trim(), 120);
    }

    private static String pairs(JSONObject in) {
        StringBuilder out = new StringBuilder();
        int shown = 0;
        Iterator<String> keys = in.keys();
        while (keys.hasNext() && shown < 3) {
            String key = keys.next();
            Object value = in.opt(key);
            if (value == null || value == JSONObject.NULL) continue;
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(key).append(": ").append(clip(text, 40));
            shown++;
        }
        return out.toString();
    }

    /** The last two path segments ("…/src/Main.java" → "src/Main.java"). */
    static String shortPath(String path) {
        if (path == null) return "";
        String p = path.trim();
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int last = p.lastIndexOf('/');
        if (last <= 0) return p;
        int previous = p.lastIndexOf('/', last - 1);
        return previous < 0 ? p : p.substring(previous + 1);
    }

    private static String baseName(String path) {
        return path.replaceAll(".*/", "");
    }

    /** One-line Polish description of a tool call, or null for calls that are not shown. */
    static String describeTool(String name, JSONObject in) {
        if (in == null) in = new JSONObject();
        if (name == null) name = "";
        String n = name;
        if (name.startsWith("mcp__")) {
            int separator = name.indexOf("__", 5);
            if (separator > 0) n = name.substring(separator + 2);
        }
        switch (n) {
            case "read_screen": return "Czytam ekran";
            case "screenshot": return "Robię zrzut ekranu";
            case "tap": return in.has("element") ? "Dotykam elementu " + in.optInt("element") : "Dotykam " + in.optInt("x") + "," + in.optInt("y");
            case "long_press": return "Przytrzymuję";
            case "type_text": return "Wpisuję „" + clip(in.optString("text"), 40) + "”";
            case "scroll": return "Przewijam " + direction(in.optString("direction"));
            case "swipe": return "Przesuwam palcem";
            case "press": return "Naciskam " + button(in.optString("button"));
            case "open_app": return "Otwieram " + in.optString("app");
            case "list_apps": return "Sprawdzam aplikacje";
            case "open_url": return "Otwieram " + clip(in.optString("url"), 40);
            case "device_status": return "Sprawdzam stan telefonu";
            case "run_command": return "Uruchamiam: " + clip(in.optString("command"), 40);
            case "wait":
            case "ToolSearch":
                return null;
            case "TodoWrite": return "Aktualizuję listę zadań";
            case "confirm": return "Proszę o Twoją zgodę";
            case "notify": {
                String text = in.optString("text").trim();
                return text.isEmpty() ? "Wysyłam powiadomienie" : text;
            }
            case "WebSearch": return "Szukam w sieci: " + clip(in.optString("query"), 40);
            case "WebFetch": return "Czytam " + clip(in.optString("url"), 40);
            case "Bash": return in.has("command") ? "Uruchamiam: " + clip(in.optString("command"), 40) : "Uruchamiam polecenie";
            case "Edit":
            case "MultiEdit": return in.has("file_path") ? "Edytuję " + clip(baseName(in.optString("file_path")), 40) : "Edytuję pliki";
            case "Write": return in.has("file_path") ? "Zapisuję " + clip(baseName(in.optString("file_path")), 40) : "Zapisuję pliki";
            case "NotebookEdit": return "Edytuję notatnik " + clip(baseName(in.optString("notebook_path", in.optString("file_path"))), 40);
            case "Read": return in.has("file_path") ? "Czytam " + clip(baseName(in.optString("file_path")), 40) : "Czytam plik";
            case "Grep": return "Przeszukuję pliki: " + clip(in.optString("pattern"), 40);
            case "Glob": return "Szukam plików: " + clip(in.optString("pattern"), 40);
            case "Task":
            case "Agent": {
                String what = in.optString("description").trim();
                return what.isEmpty() ? "Zlecam podzadanie" : "Zlecam podzadanie: " + clip(what, 40);
            }
            case "Skill": return "Używam umiejętności " + clip(in.optString("skill", in.optString("command")), 40);
            default: return "Używam " + n;
        }
    }

    private static String direction(String d) {
        switch (d) {
            case "up": return "w górę";
            case "down": return "w dół";
            case "left": return "w lewo";
            case "right": return "w prawo";
            default: return d;
        }
    }

    private static String button(String b) {
        switch (b) {
            case "back": return "Wstecz";
            case "home": return "Ekran główny";
            case "recents": return "Ostatnie aplikacje";
            case "notifications": return "Powiadomienia";
            case "quick_settings": return "Szybkie ustawienia";
            default: return b;
        }
    }

    static String clip(String s, int n) {
        s = s.replace('\n', ' ');
        if (s.length() <= n) return s;
        int cut = n - 1;
        if (cut > 0 && Character.isHighSurrogate(s.charAt(cut - 1))) cut--;
        return s.substring(0, cut) + "…";
    }
}
