package dev.mateuszsury.androidagent;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bubble conversations. Each has its own brain (claude/codex), history and agent session in Termux. */
final class Sessions {
    static final int MAX_SESSIONS = 20, MAX_MESSAGES = 150;

    static final class Msg {
        final Bubble.Kind kind;
        final String text;
        /** Attachment ids (see Attachments); empty for most messages. */
        final List<String> files;
        /**
         * Optional structured details, persisted under "m". For STEP messages (tool calls):
         * {"tool": raw tool name, "args": short argument summary, "id": tool_use id,
         *  "state": "running"|"ok"|"error", "result": first lines of output, "lines": total lines}.
         * Older records have none; renderers must fall back to {@link #text}.
         */
        org.json.JSONObject meta;

        Msg(Bubble.Kind kind, String text) {
            this(kind, text, null);
        }

        Msg(Bubble.Kind kind, String text, List<String> files) {
            this.kind = kind;
            this.text = text;
            this.files = files == null ? new ArrayList<>() : files;
        }

        /** Text for places that can't show attachments (bubble panel, previews). */
        String label() {
            if (files.isEmpty()) return text;
            String n = files.size() == 1 ? "📎 " + Attachments.name(files.get(0)) : "📎 " + files.size() + " attachments";
            return text.isEmpty() ? n : n + "\n" + text;
        }
    }

    static final class Session {
        final String id;
        final String backend;
        String title = "";
        /** Model for this chat ("" = the agent's default), e.g. sonnet. */
        String model = "";
        long updated = System.currentTimeMillis();
        final List<Msg> messages = new ArrayList<>();
        // runtime only
        boolean running, gotText, unread, runAcknowledged;
        String status = "";
        String lastReply = "";
        String lastError = "";
        /** Ephemeral Claude text deltas; persisted only when the assistant message completes. */
        String streamText = "";
        /** Last CLI initialization, useful for capability state without saving credentials. */
        boolean phoneConnected;
        int phoneToolCount;
        String agentSessionId = "";
        final List<String> agentTools = new ArrayList<>();
        long lastEventAt;
        int runId;
        /** Correlates callbacks with one request, not merely with its reusable chat id. */
        String callbackRun = "";

        Session(String id, String backend) {
            this.id = id;
            this.backend = backend;
        }

        String brainName() {
            return "codex".equals(backend) ? "Codex" : "Claude Code";
        }

        String label() {
            return title.isEmpty() ? "New " + brainName() + " chat" : title;
        }
    }

    private final SharedPreferences sp;
    private static final String INDEX = "index_v2", RECORD = "session_v2_";
    private boolean indexed, legacyComplete = true;
    private final List<String> removed = new ArrayList<>();
    final List<Session> all = new ArrayList<>(); // most recent first
    Session current;
    java.util.function.Consumer<Session> onEvicted;

    Sessions(Context c) {
        sp = c.getSharedPreferences("buddy_sessions", Context.MODE_PRIVATE);
        load();
        if (all.isEmpty()) create(Prefs.backend(c));
        String cur = sp.getString("current", null);
        current = find(cur);
        if (current == null) current = all.get(0);
        if (!indexed) save(null);
    }

    Session create(String backend) {
        Session s = new Session(UUID.randomUUID().toString().replace("-", "").substring(0, 12), backend);
        all.add(0, s);
        // Never hide a live process from Stop. If every existing chat is running,
        // retain one extra empty composer; send() limits simultaneous runs.
        while (all.size() > MAX_SESSIONS) {
            Session oldest = null;
            for (int i = all.size() - 1; i > 0; i--) {
                if (!all.get(i).running) { oldest = all.get(i); break; }
            }
            if (oldest == null) break;
            all.remove(oldest);
            removed.add(oldest.id);
            if (onEvicted != null) onEvicted.accept(oldest);
        }
        current = s;
        save(s);
        return s;
    }

    Session find(String id) {
        if (id == null) return null;
        for (Session s : all) if (s.id.equals(id)) return s;
        return null;
    }

    void remove(Session s) {
        all.remove(s);
        removed.add(s.id);
        if (all.isEmpty()) create(s.backend);
        if (current == s) current = all.get(0);
        save(null);
    }

    void select(Session s) {
        current = s;
        s.unread = false;
        save(null);
    }

    int runningCount() {
        int n = 0;
        for (Session s : all) if (s.running) n++;
        return n;
    }

    boolean anyUnread() {
        for (Session s : all) if (s.unread) return true;
        return false;
    }

    void add(Session s, Bubble.Kind kind, String text) {
        add(s, kind, text, null);
    }

    void add(Session s, Bubble.Kind kind, String text, List<String> files) {
        s.messages.add(new Msg(kind, text, files));
        while (s.messages.size() > MAX_MESSAGES) s.messages.remove(0);
        s.updated = System.currentTimeMillis();
        if (kind == Bubble.Kind.USER && s.title.isEmpty()) {
            String t = text.isEmpty() && files != null && !files.isEmpty() ? Attachments.name(files.get(0)) : text;
            s.title = t.length() > 40 ? t.substring(0, 39) + "…" : t;
        }
        // keep most recently used first
        all.remove(s);
        all.add(0, s);
        save(s);
    }

    /** Serialize only the changed conversation; selecting a chat updates just the index.
     *  SharedPreferences publishes the migration's records, index and legacy removal in
     *  one atomic editor transaction, retaining its file backup until the write succeeds. */
    private void save(Session changed) {
        try {
            SharedPreferences.Editor edit = sp.edit();
            JSONArray ids = new JSONArray();
            for (Session s : all) {
                ids.put(s.id);
                if (!indexed || changed == s) edit.putString(RECORD + s.id, encode(s).toString());
            }
            for (String id : removed) edit.remove(RECORD + id);
            // Keep the original JSON if a malformed legacy record could not be loaded.
            if (!indexed && legacyComplete) edit.remove("all");
            edit.putString(INDEX, ids.toString()).putString("current", current == null ? null : current.id).apply();
            indexed = true;
            removed.clear();
        } catch (Exception ignored) {
        }
    }

    private static JSONObject encode(Session s) throws Exception {
        JSONArray msgs = new JSONArray();
        for (Msg m : s.messages) {
            JSONObject mo = new JSONObject().put("k", m.kind.name()).put("t", m.text);
            if (!m.files.isEmpty()) mo.put("f", new JSONArray(m.files));
            if (m.meta != null && m.meta.length() > 0) mo.put("m", m.meta);
            msgs.put(mo);
        }
        return new JSONObject().put("id", s.id).put("backend", s.backend).put("title", s.title).put("model", s.model)
                .put("updated", s.updated).put("messages", msgs);
    }

    private void load() {
        try {
            indexed = sp.contains(INDEX);
            JSONArray arr = new JSONArray(sp.getString(indexed ? INDEX : "all", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject o = indexed ? new JSONObject(sp.getString(RECORD + arr.getString(i), "")) : arr.getJSONObject(i);
                    Session s = new Session(o.getString("id"), o.optString("backend", "claude"));
                    s.title = o.optString("title");
                    s.model = o.optString("model");
                    s.updated = o.optLong("updated");
                    JSONArray msgs = o.optJSONArray("messages");
                    if (msgs != null) for (int j = 0; j < msgs.length(); j++) {
                        JSONObject m = msgs.getJSONObject(j);
                        List<String> files = new ArrayList<>();
                        JSONArray f = m.optJSONArray("f");
                        if (f != null) for (int k = 0; k < f.length(); k++) files.add(f.getString(k));
                        Msg msg = new Msg(Bubble.Kind.valueOf(m.getString("k")), m.getString("t"), files);
                        msg.meta = m.optJSONObject("m");
                        s.messages.add(msg);
                    }
                    all.add(s);
                } catch (Exception invalidRecord) {
                    legacyComplete = false;
                }
            }
        } catch (Exception ignored) {
            legacyComplete = false;
        }
    }
}
