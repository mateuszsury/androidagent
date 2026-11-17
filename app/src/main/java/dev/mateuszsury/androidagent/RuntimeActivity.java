package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

/** Installation is automatic; account authentication stays with the device owner. */
public final class RuntimeActivity extends Screen {
    private static final String[] BACKENDS = {"codex", "claude"}, NAMES = {"Codex", "Claude Code"};
    private static final String[] META = {
            "openai · 0.162.1 · ~155 MB",
            "anthropic · 2.1.296 · ~108 MB",
    };
    private EmbeddedRuntime runtime;
    private TextView state, details, percent;
    private Dot stateDot;
    private ToolUi.Meter progress;
    private LinearLayout progressRow;
    private Button prepare, cancel;
    private ToolUi.Row terminal, files;
    private TextView providerSummary;
    private final Button[] installs = new Button[2], logins = new Button[2];
    private final TextView[] badges = new TextView[2];
    private final CheckBox[] picks = new CheckBox[2];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() {
            render();
            handler.postDelayed(this, 750);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        runtime = EmbeddedRuntime.get(this);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        root.addView(topBar(this, "环境与账户", "~/runtime · bash · python · node · git"), full());
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = ToolUi.body(this);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView intro = ToolUi.note(this, "终端、Python、Node.js、Git 和工具桥都在本应用中。环境 "
                + "自动准备。官方客户端通过互联网单独下载；你使用自己的账户登录。", 14);
        intro.setPadding(dp(this, 20), dp(this, 10), dp(this, 20), dp(this, 14));
        body.addView(intro, full());

        // ------------------------------------------------------------ hero: environment status
        LinearLayout hero = terminalBox(this, "环境", ACCENT);
        LinearLayout head = row(this);
        head.setGravity(Gravity.TOP);
        stateDot = toolDot(this, FAINT);
        LinearLayout.LayoutParams dotP = new LinearLayout.LayoutParams(dp(this, 16), dp(this, 16));
        dotP.topMargin = dp(this, 3);
        head.addView(stateDot, dotP);
        state = title(this, "", 16);
        state.setPadding(dp(this, 8), 0, 0, 0);
        head.addView(state, weight1());
        hero.addView(head, full());
        progressRow = row(this);
        progress = new ToolUi.Meter(this, ACCENT);
        progressRow.addView(progress, new LinearLayout.LayoutParams(0, dp(this, 6), 1));
        percent = monoText(this, "", 12, ACCENT_TEXT);
        percent.setTypeface(mono(this, 600));
        percent.setGravity(Gravity.END);
        percent.setMinWidth(dp(this, 44));
        progressRow.addView(percent, wrap());
        LinearLayout.LayoutParams pp = full();
        pp.topMargin = dp(this, 12);
        hero.addView(progressRow, pp);
        details = text(this, "", 13, MUTED);
        details.setLineSpacing(dp(this, 2), 1f);
        details.setPadding(0, dp(this, 10), 0, 0);
        hero.addView(details, full());
        prepare = button(this, "准备所选代理", true);
        prepare.setOnClickListener(v -> {
            try { runtime.prepare(); } catch (Exception e) { error(e); }
            render();
        });
        hero.addView(prepare, ToolUi.buttonParams(this, 14));
        cancel = button(this, "取消准备", false);
        cancel.setOnClickListener(v -> {
            runtime.cancelSetup();
            render();
        });
        hero.addView(cancel, ToolUi.buttonParams(this, 8));
        LinearLayout.LayoutParams heroP = cardParams(this);
        heroP.topMargin = dp(this, 4);
        body.addView(hero, heroP);

        // ------------------------------------------------------------ clients
        body.addView(sectionLabel(this, "代理客户端"), full());
        String wanted = Prefs.agents(this);
        for (int i = 0; i < BACKENDS.length; i++) {
            final int index = i;
            final String backend = BACKENDS[i];
            LinearLayout card = card(this);
            LinearLayout top = row(this);
            top.addView(agentTile(this, backend));
            LinearLayout texts = column(this);
            texts.setPadding(dp(this, 12), 0, dp(this, 8), 0);
            TextView name = single(title(this, NAMES[i], 16));
            texts.addView(name);
            TextView meta = single(monoText(this, META[i], 11, FAINT));
            meta.setPadding(0, dp(this, 3), 0, 0);
            texts.addView(meta);
            top.addView(texts, weight1());
            badges[i] = badge(this, "", FAINT);
            top.addView(badges[i], wrap());
            card.addView(top, full());
            picks[i] = ToolUi.checkBox(this, "在准备中包含");
            picks[i].setTextColor(MUTED);
            picks[i].setTextSize(13);
            picks[i].setChecked("both".equals(wanted) || backend.equals(wanted));
            picks[i].setOnCheckedChangeListener((b, on) -> pick(index, on));
            LinearLayout.LayoutParams cp = full();
            cp.topMargin = dp(this, 8);
            card.addView(picks[i], cp);
            installs[i] = button(this, "Zainstaluj " + NAMES[i], false);
            installs[i].setOnClickListener(v -> {
                try { runtime.installClient(backend); } catch (Exception e) { error(e); }
                render();
            });
            logins[i] = button(this, "登录 " + NAMES[i], false);
            logins[i].setOnClickListener(v -> {
                try { TerminalActivity.openLogin(this, backend); } catch (Exception e) { error(e); }
            });
            // One action at a time: install first, then the official login.
            card.addView(installs[i], ToolUi.buttonParams(this, 8));
            card.addView(logins[i], ToolUi.buttonParams(this, 8));
            body.addView(card, cardParams(this));
        }

        // ------------------------------------------------------------ model provider
        body.addView(sectionLabel(this, "模型供应商"), full());
        LinearLayout providerCard = card(this);
        providerSummary = text(this, "", 13, MUTED);
        providerSummary.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 6));
        providerCard.addView(providerSummary, full());
        Button providerBtn = button(this, "配置模型供应商", true);
        providerBtn.setOnClickListener(v -> showProvider());
        providerCard.addView(providerBtn, ToolUi.buttonParams(this, 6));
        TextView providerNote = text(this, "适用于内置 Codex（OpenAI 兼容接口）。Claude Code 仍使用官方账户。", 12, FAINT);
        providerNote.setPadding(dp(this, 14), 0, dp(this, 14), dp(this, 12));
        providerCard.addView(providerNote, full());
        body.addView(providerCard, cardParams(this));

        // ------------------------------------------------------------ tools
        body.addView(sectionLabel(this, "工具"), full());
        LinearLayout tools = ToolUi.group(this);
        terminal = new ToolUi.Row(this, R.drawable.ms_terminal, "打开终端", "bash · 带键盘的完整会话", true)
                .onClick(v -> TerminalActivity.openShell(this));
        files = new ToolUi.Row(this, R.drawable.ms_folder, "环境文件", "~/ · 项目和主目录", true)
                .onClick(v -> startActivity(new Intent(this, RuntimeFilesActivity.class)));
        ToolUi.Row permissions = new ToolUi.Row(this, R.drawable.ms_shield_person, "手机权限", "代理的 Android 授权", true)
                .onClick(v -> PermissionGuide.open(this));
        ToolUi.Row log = new ToolUi.Row(this, R.drawable.ms_menu, "准备日志", "setup.log", true)
                .onClick(v -> showLog());
        ToolUi.Row sources = new ToolUi.Row(this, R.drawable.ms_description, "环境来源与许可", "SOURCES.txt", true)
                .onClick(v -> showSources());
        ToolUi.add(tools, terminal.view);
        ToolUi.add(tools, files.view);
        ToolUi.add(tools, permissions.view);
        ToolUi.add(tools, log.view);
        ToolUi.add(tools, sources.view);
        body.addView(tools, cardParams(this));
        TextView foot = ToolUi.note(this, "自动配置不会登录你的账户。系统授权请在"
                + "Android 设置中。终端以 androidagent 的权限运行；没有 root。", 12);
        foot.setTextColor(FAINT);
        foot.setPadding(dp(this, 20), dp(this, 6), dp(this, 20), 0);
        body.addView(foot, full());
        setContentView(root);
        render();
        Motion.stagger(body, 30, 35);
    }

    /** Keep at least one client selected; "both" when both are checked. */
    private void pick(int index, boolean on) {
        boolean codex = picks[0].isChecked(), claude = picks[1].isChecked();
        if (!codex && !claude) {
            picks[index].setChecked(true);
            Motion.shake(picks[index]);
            Toast.makeText(this, "请至少选择一个代理", Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.setAgents(this, codex && claude ? "both" : codex ? "codex" : "claude");
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacksAndMessages(null);
        handler.post(refresh);
        if (!runtime.ready() && !runtime.busy() && runtime.error().isEmpty()) runtime.bootstrap();
        else if (runtime.ready()) withToolHost(() -> runtime.refreshAccounts());
    }

    @Override protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void render() {
        boolean busy = runtime.busy(), ready = runtime.ready();
        String error = runtime.error();
        ToolUi.setText(state, runtime.status());
        int color = !error.isEmpty() ? ERR : busy ? ACCENT : ready ? OK : FAINT;
        stateDot.setColor(color);
        stateDot.setPulsing(busy);
        ToolUi.setText(details, error.isEmpty()
                ? (busy ? "你可以返回对话。准备在后台进行。" : "终端：" + (ready ? "就绪" : "需要准备"))
                : error);
        details.setTextColor(error.isEmpty() ? MUTED : ERR);
        int value = runtime.progress();
        progress.set(busy ? Math.max(0.02f, value / 100f) : 0f, ACCENT);
        ToolUi.setText(percent, value + "%");
        ToolUi.setVisible(progressRow, busy);
        ToolUi.setEnabled(prepare, !busy);
        ToolUi.setText(prepare, error.isEmpty() ? "准备所选代理" : "重试准备");
        ToolUi.setVisible(cancel, busy);
        for (int i = 0; i < 2; i++) {
            boolean installed = runtime.agentInstalled(BACKENDS[i]);
            boolean signed = installed && SetupState.agentReady(this, BACKENDS[i]);
            ToolUi.setVisible(installs[i], !installed);
            ToolUi.setVisible(logins[i], installed);
            ToolUi.setEnabled(installs[i], ready && !busy && !installed);
            ToolUi.setText(installs[i], installed ? "客户端已安装" : "Zainstaluj " + NAMES[i]);
            ToolUi.setEnabled(logins[i], installed && !busy);
            ToolUi.setText(logins[i], signed ? "打开登录 · 账户已连接" : "登录 " + NAMES[i]);
            int badgeColor = signed ? OK : installed ? ACCENT_TEXT : FAINT;
            ToolUi.setText(badges[i], signed ? "已连接" : installed ? "已安装" : "无");
            if (badges[i].getCurrentTextColor() != badgeColor) {
                badges[i].setTextColor(badgeColor);
                badges[i].setBackground(rounded(this, Theme.withAlpha(badgeColor, 0x22), 6));
            }
        }
        ToolUi.setText(providerSummary, ModelProvider.summary(this));
        terminal.setEnabled(ready);
        files.setEnabled(ready);
    }

    private void error(Exception e) {
        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
    }

    private void showProvider() {
        LinearLayout content = column(this);
        JSONObject cur = ModelProvider.load(this);
        final String[] chosen = {cur == null ? "openai" : cur.optString("id", "custom")};
        final String[] wire = {cur == null ? "responses" : cur.optString("wire_api", "chat")};
        final EditText[] fields = new EditText[6];

        Button presetBtn = button(this, presetName(chosen[0]), false);
        presetBtn.setOnClickListener(v -> ToolUi.options(this, "选择预设", null, presetLabels(), null, i -> {
            chosen[0] = ModelProvider.PRESETS[i][0];
            ToolUi.setText(presetBtn, ModelProvider.PRESETS[i][1]);
            applyPreset(fields, ModelProvider.PRESETS[i]);
        }));
        content.addView(presetBtn, ToolUi.buttonParams(this, 10));

        fields[0] = field(content, "名称", "显示名称", false);
        fields[1] = field(content, "供应商标识 id", "deepseek", false);
        fields[2] = field(content, "接口地址 base_url", "https://api.example.com/v1", true);
        fields[3] = field(content, "环境变量名", "DEEPSEEK_API_KEY", true);
        fields[4] = field(content, "模型 model", "deepseek-chat", true);
        fields[5] = field(content, "API Key", "sk-…", false);
        fields[5].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView wireLabel = monoText(this, "接口协议", 11, FAINT);
        wireLabel.setTypeface(mono(this, 600));
        wireLabel.setPadding(0, dp(this, 14), 0, dp(this, 6));
        content.addView(wireLabel, full());
        content.addView(segmented(this, new String[][]{{"chat", "chat 补全"}, {"responses", "responses"}},
                wire[0], true, pick -> wire[0] = pick), full());

        if (cur != null) {
            setText(fields[0], cur.optString("name"));
            setText(fields[1], cur.optString("id"));
            setText(fields[2], cur.optString("base_url"));
            setText(fields[3], cur.optString("env_key"));
            setText(fields[4], cur.optString("model"));
            setText(fields[5], cur.optString("api_key"));
            wire[0] = cur.optString("wire_api", "chat");
        } else {
            applyPreset(fields, preset("openai"));
        }

        final Dialog[] sheet = new Dialog[1];
        Button save = button(this, "保存并应用", true);
        save.setOnClickListener(v -> {
            try {
                JSONObject o = new JSONObject();
                o.put("id", fieldText(fields[1]));
                o.put("name", fieldText(fields[0]));
                o.put("base_url", fieldText(fields[2]));
                o.put("env_key", fieldText(fields[3]));
                o.put("model", fieldText(fields[4]));
                o.put("wire_api", wire[0]);
                o.put("api_key", fieldText(fields[5]));
                String summary = ModelProvider.apply(RuntimeActivity.this, o);
                Toast.makeText(this, "已应用 " + summary, Toast.LENGTH_SHORT).show();
                render();
                sheet[0].dismiss();
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        Button reset = button(this, "恢复 OpenAI 官方", false);
        reset.setOnClickListener(v -> {
            try {
                ModelProvider.clear(RuntimeActivity.this);
                render();
                sheet[0].dismiss();
            } catch (Exception e) {
                Toast.makeText(RuntimeActivity.this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        content.addView(save, ToolUi.buttonParams(this, 14));
        content.addView(reset, ToolUi.buttonParams(this, 6));
        sheet[0] = ToolUi.sheet(this, "模型供应商", "内置 Codex 通过 OpenAI 兼容接口连接所选服务；密钥只保存在本应用的终端环境中。", content);
        sheet[0].show();
    }

    private EditText field(LinearLayout parent, String label, String hint, boolean single) {
        TextView l = monoText(this, label.toUpperCase(java.util.Locale.ROOT), 11, FAINT);
        l.setTypeface(mono(this, 600));
        l.setLetterSpacing(0.1f);
        l.setPadding(0, dp(this, 14), 0, dp(this, 6));
        parent.addView(l, full());
        EditText e = input(this, hint, true);
        e.setSingleLine(single);
        e.setContentDescription(label);
        parent.addView(e, full());
        return e;
    }

    private static String[] presetLabels() {
        String[] out = new String[ModelProvider.PRESETS.length];
        for (int i = 0; i < out.length; i++) out[i] = ModelProvider.PRESETS[i][1];
        return out;
    }

    private static String presetName(String id) {
        for (String[] p : ModelProvider.PRESETS) if (p[0].equals(id)) return p[1];
        return id;
    }

    private static String[] preset(String id) {
        for (String[] p : ModelProvider.PRESETS) if (p[0].equals(id)) return p;
        return ModelProvider.PRESETS[0];
    }

    private static void applyPreset(EditText[] f, String[] p) {
        setText(f[0], p[1]);
        setText(f[1], p[0]);
        setText(f[2], p[2]);
        setText(f[3], p[5]);
        setText(f[4], p[3]);
    }

    private static void setText(EditText e, String s) {
        if (s != null && !s.isEmpty()) e.setText(s);
    }

    private static String fieldText(EditText e) {
        return e.getText().toString().trim();
    }

    private void showLog() {
        LinearLayout content = column(this);
        String log = runtime.setupLog();
        ToolUi.logPanel(content, log.isEmpty() ? "没有记录。" : log, ToolUi.screenDp(this, 0.55f), true);
        Button close = button(this, "关闭", false);
        content.addView(close, ToolUi.buttonParams(this, 14));
        Dialog d = ToolUi.sheet(this, "准备环境", "最近配置步骤的日志。", content);
        close.setOnClickListener(v -> d.dismiss());
        d.show();
    }

    private void showSources() {
        try (java.io.InputStream input = getAssets().open("terminal/SOURCES.txt")) {
            java.io.ByteArrayOutputStream content = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (content.size() + count > 256 * 1024) throw new java.io.IOException("来源文档超出限制");
                content.write(buffer, 0, count);
            }
            LinearLayout box = column(this);
            TextView text = ToolUi.logPanel(box, new String(content.toByteArray(), java.nio.charset.StandardCharsets.UTF_8),
                    ToolUi.screenDp(this, 0.55f), false);
            Linkify.addLinks(text, Linkify.WEB_URLS);
            Button close = button(this, "关闭", false);
            box.addView(close, ToolUi.buttonParams(this, 14));
            Dialog d = ToolUi.sheet(this, "来源与许可", null, box);
            close.setOnClickListener(v -> d.dismiss());
            d.show();
        } catch (Exception exception) {
            error(exception);
        }
    }
}
