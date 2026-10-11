package dev.mateuszsury.androidagent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Chats shared by the app screens and the bubble. Runs on the main thread. */
final class ChatHub {
    interface Listener {
        void onChatsChanged();

        void onMessage(Sessions.Session ses, Sessions.Msg msg);
    }

    interface CommandRunner {
        void run(String... args);
    }

    private static final long NO_RESPONSE_MS = 30000;
    private static final String TOOL_PREFIX = "mcp__phone__";
    private static ChatHub instance;

    final Sessions sessions;
    private final Context app;
    private final CommandRunner commands;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Sessions.Session> viewing = new ArrayList<>();
    private Sessions.Session foregroundChat;
    private String flash;
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
        add(s, Bubble.Kind.STEP, "Continuing an earlier " + s.brainName() + " session. If it's still open in a terminal, close it there first.");
        changed();
        return s;
    }

    static synchronized ChatHub get(Context c) {
        if (instance == null) instance = new ChatHub(c.getApplicationContext());
        return instance;
    }

    private ChatHub(Context app) {
        this(app, args -> Termux.run(app, args));
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
        if (ses.running) return "This chat is still working. Tap Stop, or use another chat.";
        if (sessions.runningCount() >= Sessions.MAX_SESSIONS) return "Too many chats are working. Stop one before sending another message.";
        if ("/status".equals(text.trim())) {
            add(ses, Bubble.Kind.USER, text);
            try { add(ses, Bubble.Kind.BUDDY, DeviceState.summary(app)); }
            catch (Exception e) { add(ses, Bubble.Kind.ERROR, e.getMessage()); }
            changed();
            return null;
        }
        if (!McpHost.get(app).running()) return "Włącz dostęp agentów w panelu USB lub sterowanie ekranem w konfiguracji.";
        if (!SetupState.helperCurrent(app) || !SetupState.agentReady(app, ses.backend))
            return "Skonfiguruj i zaloguj " + ses.brainName() + " w Termux. /status działa bez logowania.";
        boolean hasFiles = files != null && !files.isEmpty();
        if (text.isEmpty() && !hasFiles) return null;
        String forAgent = text.isEmpty() && hasFiles ? "Have a look at what I attached." : text;
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
        ses.status = "Thinking…";
        changed();
        final int id = ses.runId;
        main.postDelayed(() -> {
            if (ses.running && id == ses.runId && !ses.runAcknowledged) {
                stop(ses);
                add(ses, Bubble.Kind.ERROR, "No response from Termux. Open androidagent settings and check the Termux helper step.");
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
        if (deliveryError == null) add(ses, Bubble.Kind.STEP, "Stopped.");
        else add(ses, Bubble.Kind.ERROR, "Phone tool access ended, but the Stop command could not reach Termux: "
                + deliveryError + " Check Termux for any process still running.");
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
        if (!ev.isNull("parent_tool_use_id")) return;
        ses.lastEventAt = SystemClock.elapsedRealtime();
        ses.runAcknowledged = true;
        String type = ev.optString("type");
        if (!ses.running && ("system".equals(type) || "assistant".equals(type))) startRun(ses);
        switch (type) {
            case "buddy_started":
                ses.status = "Thinking…";
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
                    ses.status = "Thinking…";
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
                        String step = describeTool(c.optString("name"), c.optJSONObject("input"));
                        if (step != null) {
                            add(ses, Bubble.Kind.STEP, step);
                            ses.status = step;
                        }
                    }
                }
                break;
            }
            case "result": {
                boolean error = ev.optBoolean("is_error") || !"success".equals(ev.optString("subtype", "success"));
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
                if (error) add(ses, Bubble.Kind.ERROR, r.isEmpty() ? ses.brainName() + " stopped." : r);
                else if (!ses.gotText && !r.isEmpty()) {
                    reply(ses, r);
                }
                break;
            }
            case "buddy_error":
                flushStream(ses);
                add(ses, Bubble.Kind.ERROR, ev.optString("text", "Something went wrong."));
                String activeRun = ev.optString("active_run");
                if (activeRun.matches("[A-Za-z0-9_-]{1,64}") && !activeRun.equals(ses.callbackRun)) {
                    // The app process may have restarted while its Termux helper survived.
                    // Cancel that exact old run; its late callbacks cannot finish this one.
                    try {
                        commands.run("stop", ses.id, activeRun);
                        add(ses, Bubble.Kind.STEP, "Asked Termux to stop the earlier request. Send your message again when it has stopped.");
                    } catch (Exception e) {
                        add(ses, Bubble.Kind.ERROR, "Could not stop the earlier Termux request: " + e.getMessage());
                    }
                }
                break;
            case "buddy_stopped":
                flushStream(ses);
                ses.running = false;
                McpHost.get(app).revoke(ses.id);
                ses.status = "";
                add(ses, Bubble.Kind.STEP, "Stopped.");
                break;
            case "buddy_done":
                flushStream(ses);
                if (ses.running) {
                    ses.running = false;
                    McpHost.get(app).revoke(ses.id);
                    ses.status = "";
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
            String st = latest == null || latest.status.isEmpty() ? "Working…" : latest.status;
            return n > 1 ? n + " chats · " + st : st;
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
        ses.gotText = false;
        ses.lastReply = "";
        ses.lastError = "";
        ses.streamText = "";
        ses.lastEventAt = SystemClock.elapsedRealtime();
        ses.runId++;
    }

    private void add(Sessions.Session ses, Bubble.Kind kind, String text) {
        if (text == null) return;
        if (kind == Bubble.Kind.ERROR) {
            if (ses.lastError.equals(text)) return;
            ses.lastError = text;
        }
        sessions.add(ses, kind, text);
        if (kind == Bubble.Kind.BUDDY && !viewing.contains(ses)) ses.unread = true;
        if (kind == Bubble.Kind.BUDDY && Prefs.speakReplies(app)) Voice.speak(app, text);
        Sessions.Msg m = ses.messages.get(ses.messages.size() - 1);
        for (Listener l : new ArrayList<>(listeners)) l.onMessage(ses, m);
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

    static String describeTool(String name, JSONObject in) {
        if (in == null) in = new JSONObject();
        String n = name.startsWith(TOOL_PREFIX) ? name.substring(TOOL_PREFIX.length()) : name;
        switch (n) {
            case "read_screen": return "Reading the screen";
            case "screenshot": return "Taking a screenshot";
            case "tap": return in.has("element") ? "Tapping element " + in.optInt("element") : "Tapping " + in.optInt("x") + "," + in.optInt("y");
            case "long_press": return "Long-pressing";
            case "type_text": return "Typing \"" + clip(in.optString("text"), 40) + "\"";
            case "scroll": return "Scrolling " + in.optString("direction");
            case "swipe": return "Swiping";
            case "press": return "Pressing " + in.optString("button");
            case "open_app": return "Opening " + in.optString("app");
            case "list_apps": return "Listing apps";
            case "open_url": return "Opening " + clip(in.optString("url"), 40);
            case "wait":
            case "ToolSearch":
            case "TodoWrite":
                return null;
            case "confirm": return "Asking for your approval";
            case "notify": return in.optString("text");
            case "WebSearch": return "Searching the web: " + clip(in.optString("query"), 40);
            case "WebFetch": return "Reading " + clip(in.optString("url"), 40);
            case "Bash": return in.has("command") ? "Running: " + clip(in.optString("command"), 40) : "Running a command";
            case "Edit":
            case "Write": return in.has("file_path") ? "Editing " + clip(in.optString("file_path").replaceAll(".*/", ""), 40) : "Editing files";
            case "Read": return "Reading " + clip(in.optString("file_path").replaceAll(".*/", ""), 40);
            default: return "Using " + n;
        }
    }

    static String clip(String s, int n) {
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }
}
