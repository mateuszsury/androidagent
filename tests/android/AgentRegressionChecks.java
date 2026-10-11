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
