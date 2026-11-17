package dev.mateuszsury.androidagent;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** 模型供应商配置：把内置的 Codex 客户端指向 OpenAI 兼容的国内或自定义服务。
 *  配置写入终端主目录的 {@code .codex/config.toml}，密钥写入 {@code .androidagent/provider.env}，
 *  由 buddy-run 在启动客户端前加载。 */
final class ModelProvider {
    private ModelProvider() {}

    /** 预设：{id, 名称, base_url, model, wire_api, 默认环境变量名}。 */
    static final String[][] PRESETS = {
            {"openai", "OpenAI 官方", "https://api.openai.com/v1", "gpt-5", "responses", ""},
            {"deepseek", "DeepSeek 深度求索", "https://api.deepseek.com/v1", "deepseek-chat", "chat", "DEEPSEEK_API_KEY"},
            {"qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "chat", "DASHSCOPE_API_KEY"},
            {"zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus", "chat", "ZHIPU_API_KEY"},
            {"kimi", "Kimi 月之暗面", "https://api.moonshot.cn/v1", "kimi-latest", "chat", "MOONSHOT_API_KEY"},
            {"longcat", "LongCat", "https://api.longcat.chat/openai/v1", "LongCat-2.5", "responses", "LONGCAT_API_KEY"},
            {"custom", "自定义", "", "", "chat", ""},
    };

    private static File home(Context c) {
        return FileTools.home(c);
    }

    private static File configFile(Context c) {
        return new File(new File(home(c), ".codex"), "config.toml");
    }

    private static File envFile(Context c) {
        return new File(new File(home(c), ".androidagent"), "provider.env");
    }

    /** 已保存的配置；未配置时返回 null。 */
    static JSONObject load(Context c) {
        String json = Prefs.provider(c);
        if (json == null || json.isEmpty()) return null;
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    static void save(Context c, JSONObject o) {
        Prefs.setProvider(c, o.toString());
    }

    /** 恢复 OpenAI 官方：移除自定义供应商与密钥。 */
    static void clear(Context c) throws IOException {
        Prefs.setProvider(c, "");
        writeConfig(c, null);
        writeEnv(c, null, null);
    }

    /**
     * 应用配置到终端环境。返回当前生效配置的摘要，失败时抛出 IOException。
     * 只重写 {@code model}、{@code model_provider} 与所有 {@code [model_providers.*]} 段，
     * 其余配置（登录、MCP、审批策略）保持不变。
     */
    static String apply(Context c, JSONObject o) throws IOException {
        String id = o.optString("id", "custom");
        if (!id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("供应商标识只能是字母、数字、下划线或连字符");
        }
        String envKey = o.optString("env_key", "");
        if (!envKey.isEmpty() && !envKey.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("环境变量名只能是字母、数字和下划线，且以字母或下划线开头");
        }
        save(c, o);
        writeConfig(c, o);
        writeEnv(c, o.optString("env_key", ""), o.optString("api_key", ""));
        return summary(c);
    }

    /** 读取 codex 实际使用的配置，返回中文摘要。 */
    static String summary(Context c) {
        try {
            List<String> lines = readLines(configFile(c));
            String model = "", provider = "";
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("model =") && model.isEmpty()) model = unquote(t.substring(t.indexOf('=') + 1).trim());
                else if (t.startsWith("model_provider =") && provider.isEmpty()) provider = unquote(t.substring(t.indexOf('=') + 1).trim());
            }
            if (!model.isEmpty()) return "当前：" + model + (provider.isEmpty() ? "" : " · 供应商 " + provider);
        } catch (Exception ignored) {
        }
        return "当前：OpenAI 官方（默认）";
    }

    private static void writeConfig(Context c, JSONObject o) throws IOException {
        File f = configFile(c);
        StringBuilder sb = new StringBuilder();
        if (f.isFile()) {
            boolean inProviders = false;
            for (String line : readLines(f)) {
                String t = line.trim();
                if (t.startsWith("[")) {
                    inProviders = t.startsWith("[model_providers.");
                    if (inProviders) continue;
                    sb.append(line).append('\n');
                    continue;
                }
                if (inProviders) continue;
                if (t.startsWith("model =") || t.startsWith("model_provider =")) continue;
                sb.append(line).append('\n');
            }
        } else {
            f.getParentFile().mkdirs();
        }
        if (o != null) {
            String id = o.optString("id", "custom");
            sb.append("\nmodel = \"").append(esc(o.optString("model", ""))).append("\"\n");
            sb.append("model_provider = \"").append(esc(id)).append("\"\n");
            sb.append("\n[model_providers.").append(esc(id)).append("]\n");
            sb.append("name = \"").append(esc(o.optString("name", id))).append("\"\n");
            sb.append("base_url = \"").append(esc(o.optString("base_url", ""))).append("\"\n");
            sb.append("env_key = \"").append(esc(o.optString("env_key", ""))).append("\"\n");
            sb.append("wire_api = \"").append(esc(o.optString("wire_api", "chat"))).append("\"\n");
        }
        write(f, sb.toString());
    }

    private static void writeEnv(Context c, String key, String value) throws IOException {
        File f = envFile(c);
        f.getParentFile().mkdirs();
        StringBuilder sb = new StringBuilder();
        sb.append("# 由 androidagent 的模型设置写入；供 buddy-run 加载供应商密钥。\n");
        if (key != null && !key.isEmpty() && value != null && !value.isEmpty()) {
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("环境变量名无效：" + key);
            }
            sb.append("export ").append(key).append("='").append(value.replace("'", "'\\''")).append("'\n");
        }
        write(f, sb.toString());
    }

    private static List<String> readLines(File f) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        }
        return out;
    }

    private static void write(File f, String s) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }
}
