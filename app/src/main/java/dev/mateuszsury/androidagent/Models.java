package dev.mateuszsury.androidagent;

/** Models offered per agent. "" means the agent's own default. */
final class Models {
    static final String[][] CLAUDE = {
            {"", "Default"},
            {"sonnet", "Sonnet"},
            {"opus", "Opus"},
            {"haiku", "Haiku"},
    };
    /** Used until the helper reports Codex's own model list. */
    static final String[][] CODEX = {
            {"", "Default"},
    };
    private static volatile String[][] codexLive;

    private Models() {
    }

    static String[][] forBackend(String backend) {
        if (!"codex".equals(backend)) return CLAUDE;
        return codexLive != null ? codexLive : CODEX;
    }

    /** Codex's model list as reported by the helper: JSON [[slug, name], …]. */
    static void setCodex(String json) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            if (a.length() == 0) return;
            String[][] m = new String[a.length() + 1][];
            m[0] = new String[]{"", "Default"};
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONArray e = a.getJSONArray(i);
                m[i + 1] = new String[]{e.getString(0), e.getString(1)};
            }
            codexLive = m;
        } catch (Exception ignored) {
        }
    }

    static String label(String backend, String model) {
        if (model == null || model.isEmpty()) return "Default";
        for (String[] m : forBackend(backend)) if (m[0].equals(model)) return m[1];
        return model;
    }
}
