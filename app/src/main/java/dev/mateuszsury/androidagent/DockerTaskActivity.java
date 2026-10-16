package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.*;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.*;

/** Source editor and bounded asynchronous jobs. No shell command runs on Android. */
public class DockerTaskActivity extends Screen {
    private DockerVm vm;
    private String task, job;
    private TextView output, jobBadge;
    private EditText filename, source, image, command, artifact;
    private CheckBox preview;
    private Typeface bold;
    private boolean polling, visible;
    private LinearLayout links;
    private String shownLinks = "";
    private final Runnable linkTick = new Runnable() {
        @Override public void run() {
            renderLinks();
            if (visible) handler.postDelayed(this, 2000);
        }
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollTick = this::pollJob;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        vm = DockerVm.get(this);
        task = getIntent().getStringExtra("task");
        if (task == null) { finish(); return; }
        if (state != null) job = state.getString("job");
        bold = mono(this, 700);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        root.addView(topBar(this, "Zadanie Docker", "~/zadania/#" + (task.length() > 8 ? task.substring(0, 8) : task)), full());
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = ToolUi.body(this);

        // ------------------------------------------------------------ project files
        body.addView(sectionLabel(this, "Pliki projektu"), full());
        LinearLayout files = card(this);
        files.addView(ToolUi.caption(this, "Ścieżka"), full());
        filename = field("Ścieżka pliku", "Dockerfile", false);
        files.addView(filename, fieldParams(8));
        TextView contentCaption = ToolUi.caption(this, "Zawartość");
        contentCaption.setPadding(0, dp(this, 14), 0, 0);
        files.addView(contentCaption, full());
        source = field("Treść pliku", "FROM alpine:3.23\nWORKDIR /workspace\n", true);
        source.setMinLines(6);
        source.setGravity(Gravity.TOP | Gravity.START);
        source.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        source.setLineSpacing(dp(this, 2), 1f);
        files.addView(source, fieldParams(8));
        Button load = button(this, "Wczytaj plik", false);
        load.setOnClickListener(v -> operation("read", "path", filename.getText().toString()));
        Button list = button(this, "Lista plików", false);
        list.setOnClickListener(v -> operation("files"));
        files.addView(ToolUi.buttons(this, load, list), fieldParams(12));
        Button save = button(this, "Zapisz plik", false);
        save.setTextColor(ACCENT_TEXT);
        save.setOnClickListener(v -> operation("write", "path", filename.getText().toString(), "content", source.getText().toString()));
        Button build = button(this, "Zbuduj obraz", true);
        build.setOnClickListener(v -> operation("build", "dockerfile", filename.getText().toString()));
        files.addView(ToolUi.buttons(this, save, build), fieldParams(8));
        body.addView(files, cardParams(this));

        // ------------------------------------------------------------ run
        body.addView(sectionLabel(this, "Uruchamianie"), full());
        LinearLayout run = card(this);
        run.addView(ToolUi.caption(this, "Obraz"), full());
        image = field("Obraz bazowy (puste: zbudowany obraz)", "", false);
        run.addView(image, fieldParams(8));
        TextView commandCaption = ToolUi.caption(this, "Komenda");
        commandCaption.setPadding(0, dp(this, 14), 0, 0);
        run.addView(commandCaption, full());
        command = field("Komenda w kontenerze", "", false);
        run.addView(command, fieldParams(8));
        preview = ToolUi.checkBox(this, "Opublikuj port 8080 kontenera");
        preview.setChecked(getIntent().getBooleanExtra("demo", false));
        run.addView(preview, fieldParams(8));
        Button start = button(this, "Uruchom kontener", true);
        start.setOnClickListener(v -> operation("run", "image", image.getText().toString(), "command", command.getText().toString(), "port", preview.isChecked() ? 8080 : JSONObject.NULL));
        Button exec = button(this, "Wykonaj komendę", false);
        exec.setOnClickListener(v -> operation("exec", "command", command.getText().toString()));
        run.addView(ToolUi.buttons(this, start, exec), fieldParams(8));
        body.addView(run, cardParams(this));

        // ------------------------------------------------------------ output
        LinearLayout outHead = row(this);
        outHead.addView(sectionLabel(this, "Wynik"), weight1());
        jobBadge = badge(this, "", FAINT);
        jobBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams bp = wrap();
        bp.rightMargin = dp(this, 20);
        bp.topMargin = dp(this, 14);
        outHead.addView(jobBadge, bp);
        body.addView(outHead, full());
        output = terminalText(this, "");
        android.text.SpannableString idle = new android.text.SpannableString("Gotowy. Zapisz pliki, zbuduj obraz i uruchom aplikację.");
        idle.setSpan(new android.text.style.ForegroundColorSpan(CODE_MUTED), 0, idle.length(), 0);
        output.setText(idle);
        output.setMinHeight(dp(this, 150));
        output.setLinkTextColor(ACCENT);
        body.addView(output, sideParams(0));
        Button logs = button(this, "Odśwież dziennik", false);
        logs.setOnClickListener(v -> operation("logs"));
        Button cancel = button(this, "Zatrzymaj zadanie", false);
        cancel.setOnClickListener(v -> operation("cancel"));
        body.addView(ToolUi.buttons(this, logs, cancel), sideParams(10));
        Button view = button(this, "Otwórz podgląd", false);
        // Each task publishes its own phone port; the preview opens exactly that one.
        view.setOnClickListener(v -> startActivity(DockerPreviewActivity.intent(this, vm.previewPort(task), taskName())));
        LinearLayout.LayoutParams vp = sideParams(8);
        vp.height = dp(this, 46);
        body.addView(view, vp);
        links = row(this);
        links.setPadding(dp(this, 20), 0, dp(this, 20), 0);
        links.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = dp(this, 10);
        body.addView(links, lp);

        // ------------------------------------------------------------ export + cleanup
        body.addView(sectionLabel(this, "Zachowaj wynik"), full());
        LinearLayout keep = card(this);
        keep.addView(ToolUi.caption(this, "Plik"), full());
        artifact = field("Plik do zachowania", "out/app.apk", false);
        keep.addView(artifact, fieldParams(8));
        Button export = button(this, "Zachowaj plik", true);
        export.setOnClickListener(v -> {
            final String path = artifact.getText().toString();
            new Thread(() -> {
                try {
                    JSONObject result = vm.export(task, path);
                    handler.post(() -> { if (!isDestroyed()) show("Zachowano " + result.optString("name") + "\nPlik jest dostępny na ekranie Docker także po usunięciu zadania.", null); });
                } catch (Exception e) { handler.post(() -> showError(e)); }
            }, "docker-export").start();
        });
        keep.addView(export, ToolUi.buttonParams(this, 12));
        TextView keepNote = text(this, "Zachowane pliki zostają w aplikacji po usunięciu zadania.", 12, FAINT);
        keepNote.setPadding(0, dp(this, 8), 0, 0);
        keep.addView(keepNote, full());
        body.addView(keep, cardParams(this));
        LinearLayout danger = ToolUi.group(this);
        ToolUi.add(danger, new ToolUi.Row(this, R.drawable.ms_close, "Posprzątaj i usuń zadanie", "kontener, obraz i pliki robocze", false).danger()
                .onClick(v -> confirmSheet(this, "Usunąć zadanie?", "Usuniesz kontener, jego obraz i pliki robocze. Zachowane pliki pozostaną dostępne.", "Posprzątaj", true, () -> operation("cleanup"))).view);
        LinearLayout.LayoutParams dp2 = cardParams(this);
        dp2.topMargin = dp(this, 6);
        body.addView(danger, dp2);

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (state != null) {
            filename.setText(state.getString("filename"));
            source.setText(state.getString("source"));
            image.setText(state.getString("image"));
            command.setText(state.getString("command"));
            artifact.setText(state.getString("artifact"));
            output.setText(state.getString("output"));
            preview.setChecked(state.getBoolean("preview"));
            badgeFor(state.getString("jobState", ""));
        } else {
            final String initial = source.getText().toString();
            new Thread(() -> {
                try {
                    JSONObject file = vm.request(new JSONObject().put("op", "read").put("task", task).put("path", "Dockerfile"));
                    handler.post(() -> { if (!isDestroyed() && source.getText().toString().equals(initial)) source.setText(file.optString("content")); });
                } catch (Exception ignored) {}
            }, "docker-open-source").start();
        }
        Motion.stagger(body, 30, 35);
    }

    private EditText field(String label, String value, boolean multiline) {
        EditText e = input(this, label, true);
        e.setContentDescription(label);
        e.setText(value);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        e.setSingleLine(!multiline);
        if (multiline) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        else e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }

    private LinearLayout.LayoutParams fieldParams(int top) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, top);
        return p;
    }

    private LinearLayout.LayoutParams sideParams(int top) {
        LinearLayout.LayoutParams p = full();
        p.leftMargin = p.rightMargin = dp(this, 16);
        p.topMargin = dp(this, top);
        return p;
    }

    /** Show text in the output panel (ANSI colours from build logs become spans). */
    private void show(String text, String state) {
        output.setText(AnsiText.render(text, bold));
        badgeFor(state);
    }

    private void badgeFor(String state) {
        if (state == null || state.isEmpty()) { ToolUi.setVisible(jobBadge, false); return; }
        int color = "running".equals(state) || "queued".equals(state) ? ACCENT_TEXT : "succeeded".equals(state) ? OK : "failed".equals(state) ? ERR : MUTED;
        String label = "running".equals(state) ? "trwa" : "queued".equals(state) ? "w kolejce" : "succeeded".equals(state) ? "gotowe"
                : "failed".equals(state) ? "błąd" : "cancelled".equals(state) ? "anulowane" : state;
        ToolUi.setText(jobBadge, label);
        jobBadge.setTag(state);
        jobBadge.setTextColor(color);
        jobBadge.setBackground(rounded(this, Theme.withAlpha(color, 0x22), 6));
        if (jobBadge.getVisibility() != View.VISIBLE) { jobBadge.setVisibility(View.VISIBLE); Motion.pop(jobBadge, 0); }
    }

    private void operation(String op, Object... fields) {
        final JSONObject args = new JSONObject();
        try {
            args.put("op", op).put("task", task);
            for (int i = 0; i < fields.length; i += 2) if (fields[i + 1] != JSONObject.NULL) args.put((String) fields[i], fields[i + 1]);
        } catch (Exception e) { showError(e); return; }
        output.setText("Wykonywanie…");
        new Thread(() -> {
            try {
                JSONObject result = vm.request(args);
                handler.post(() -> {
                    if (isDestroyed()) return;
                    if (result.has("kind") && result.has("id")) { job = result.optString("id"); pollJob(); }
                    else if ("read".equals(op)) {
                        filename.setText(result.optString("path"));
                        source.setText(result.optString("content"));
                        output.setText("Wczytano " + result.optString("path"));
                    } else if ("files".equals(op)) {
                        JSONArray list = result.optJSONArray("files");
                        String[] names = new String[list == null ? 0 : list.length()];
                        for (int i = 0; i < names.length; i++) names[i] = list.optString(i);
                        if (names.length == 0) output.setText("Projekt nie zawiera plików.");
                        else {
                            output.setText(names.length + (names.length == 1 ? " plik w projekcie" : " plików w projekcie"));
                            ToolUi.options(this, "Pliki projektu", "Wybierz plik, aby wczytać go do edytora.", names, null, index -> operation("read", "path", names[index]));
                        }
                    } else if ("cleanup".equals(op)) { finish(); }
                    else show(result.optString("output", result.toString()), null);
                });
            } catch (Exception e) { handler.post(() -> showError(e)); }
        }, "docker-task").start();
    }

    private void pollJob() {
        if (polling || !visible || job == null || isDestroyed()) return;
        handler.removeCallbacks(pollTick);
        polling = true;
        final String id = job;
        new Thread(() -> {
            try {
                JSONObject result = vm.request(new JSONObject().put("op", "logs").put("task", task).put("job", id));
                handler.post(() -> {
                    polling = false;
                    if (isDestroyed()) return;
                    if (!id.equals(job)) { pollJob(); return; }
                    String state = result.optString("state"), text = result.optString("output");
                    show(text.isEmpty() ? state : text, state);
                    if (visible && ("running".equals(state) || "queued".equals(state))) handler.postDelayed(pollTick, 1500);
                });
            } catch (Exception e) {
                handler.post(() -> { polling = false; if (!id.equals(job)) { pollJob(); return; } showError(e); });
            }
        }, "docker-job-poll").start();
    }

    private void showError(Exception e) {
        if (isDestroyed()) return;
        android.text.SpannableString s = new android.text.SpannableString("Błąd: " + e.getMessage());
        s.setSpan(new android.text.style.ForegroundColorSpan(AnsiText.PALETTE[1]), 0, s.length(), 0);
        output.setText(s);
    }

    @Override protected void onResume() { super.onResume(); visible = true; if (job != null) pollJob(); handler.removeCallbacks(linkTick); handler.post(linkTick); }
    @Override protected void onPause() { visible = false; handler.removeCallbacks(pollTick); handler.removeCallbacks(linkTick); super.onPause(); }

    /** This task in the VM heartbeat (in memory, no request): name, published url/host_port, lan_url. */
    private JSONObject taskInfo() {
        JSONArray list = vm.guest.optJSONArray("tasks");
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject t = list.optJSONObject(i);
            if (t != null && task.equals(t.optString("id"))) return t;
        }
        return null;
    }

    private String taskName() {
        JSONObject t = taskInfo();
        return t == null ? null : t.optString("name", null);
    }

    /** The published app's addresses: phone URL (opens the preview) and, when enabled, the Wi-Fi URL. */
    private void renderLinks() {
        JSONObject t = taskInfo();
        String url = t == null ? "" : t.optString("url", "");
        String lan = t == null || t.isNull("lan_url") ? "" : t.optString("lan_url", "");
        int port = t == null ? 0 : t.optInt("host_port", 0);
        String sig = url + "|" + lan + "|" + port;
        if (sig.equals(shownLinks)) return;
        shownLinks = sig;
        links.removeAllViews();
        if (url.isEmpty() || port <= 0) { ToolUi.setVisible(links, false); return; }
        TextView label = monoText(this, "adres", 11, FAINT);
        label.setPadding(0, 0, dp(this, 8), 0);
        links.addView(label, wrap());
        links.addView(ToolUi.urlChip(this, url, ACCENT_TEXT, () -> startActivity(DockerPreviewActivity.intent(this, port, taskName()))), wrap());
        if (!lan.isEmpty()) {
            LinearLayout.LayoutParams p = wrap();
            p.leftMargin = dp(this, 6);
            links.addView(ToolUi.urlChip(this, lan, INFO, null), p);
        }
        if (links.getVisibility() != View.VISIBLE) { links.setVisibility(View.VISIBLE); Motion.fadeIn(links, 0); }
    }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        if (filename != null) {
            out.putString("job", job);
            out.putString("filename", filename.getText().toString());
            out.putString("source", source.getText().toString());
            out.putString("image", image.getText().toString());
            out.putString("command", command.getText().toString());
            out.putString("artifact", artifact.getText().toString());
            out.putString("output", output.getText().toString());
            out.putBoolean("preview", preview.isChecked());
            out.putString("jobState", jobBadge.getVisibility() == View.VISIBLE && jobBadge.getTag() != null ? jobBadge.getTag().toString() : "");
        }
        super.onSaveInstanceState(out);
    }
}
