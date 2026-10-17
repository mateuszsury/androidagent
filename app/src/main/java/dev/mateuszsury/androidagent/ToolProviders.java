package dev.mateuszsury.androidagent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tool groups that work without Accessibility. Each provider lists its own MCP tools and handles them;
 * {@link McpHost} consults these before falling back to the Accessibility tools. Register new groups
 * in {@link #ALL} (one line each).
 */
final class ToolProviders {
    interface Provider {
        /** MCP tool definitions ({name, description, inputSchema}). */
        JSONArray list(Context app) throws JSONException;

        boolean handles(String name);

        /** {@code caller} is "chat:&lt;id&gt;" for agent runs, "external" or "panel" otherwise. */
        JSONObject call(Context app, String caller, String name, JSONObject args) throws Exception;
    }

    static final Provider[] ALL = {
            PhoneTools.INSTANCE, IntentTools.INSTANCE, DeviceTools.INSTANCE,
            new ServiceTools(),
    };

    private ToolProviders() {
    }

    static void listInto(Context app, JSONArray out) throws JSONException {
        for (Provider p : ALL) {
            JSONArray tools = p.list(app);
            for (int i = 0; i < tools.length(); i++) out.put(tools.get(i));
        }
    }

    static Provider find(String name) {
        for (Provider p : ALL) if (p.handles(name)) return p;
        return null;
    }

    // ------------------------------------------------------------ schema helpers

    static JSONObject tool(String name, String description, JSONObject properties, String... required)
            throws JSONException {
        JSONObject schema = new JSONObject().put("type", "object").put("properties", properties);
        if (required.length > 0) {
            JSONArray r = new JSONArray();
            for (String s : required) r.put(s);
            schema.put("required", r);
        }
        return new JSONObject().put("name", name).put("description", description).put("inputSchema", schema);
    }

    static JSONObject prop(String type, String description) throws JSONException {
        return new JSONObject().put("type", type).put("description", description);
    }

    /** An MCP text result with the same JSON as structuredContent. */
    static JSONObject result(Object value) throws JSONException {
        return UsbTools.result(value);
    }

    /** A plain-text MCP result. */
    static JSONObject text(String s) throws JSONException {
        return new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", s)));
    }
}
