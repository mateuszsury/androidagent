package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.app.Dialog;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * "Pliki środowiska": a small browser for the embedded runtime home, starting in the agent workspace
 * ~/.buddy/work. Text files open in a mono editor; any workspace file can be opened, shared, saved to
 * Downloads or (APKs) installed with the same code as the agent's MCP tools. No storage permission.
 */
public final class RuntimeFilesActivity extends Screen {
    private static final int TEXT_LIMIT = 256 * 1024, MAX_ROWS = 300;
    private static final Locale PL = new Locale("pl", "PL");
    private File boundary, directory;
    private FrameLayout stage;
    private LinearLayout browser, list, crumbs;
    private HorizontalScrollView crumbScroll;
    private ScrollView listScroll;
    private TextView subtitle;
    // Editor state (a second screen inside this activity).
    private LinearLayout editorView;
    private EditText editor;
    private TextView editorStatus;
    private File editing;
    private String original = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            boundary = FileTools.home(this).getCanonicalFile();
            directory = FileTools.workspace(this).getCanonicalFile();
        } catch (IOException e) {
            boundary = FileTools.home(this);
            directory = FileTools.workspace(this);
        }
        stage = new FrameLayout(this);
        stage.setBackgroundColor(BG);
        stage.setFitsSystemWindows(true);
        browser = column(this);
        TextView create = barAction(this, "Nowy");
        create.setContentDescription("Nowy plik lub folder");
        create.setOnClickListener(v -> createSheet());
        LinearLayout bar = topBar(this, "Pliki środowiska", "~/" + FileTools.WORK, create);
        backButton(bar).setOnClickListener(v -> back());
        subtitle = (TextView) ((LinearLayout) ((LinearLayout) bar.getChildAt(0)).getChildAt(1)).getChildAt(1);
        browser.addView(bar, full());
        crumbScroll = new HorizontalScrollView(this);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbs = row(this);
        crumbs.setPadding(dp(this, 12), dp(this, 6), dp(this, 12), dp(this, 6));
        crumbScroll.addView(crumbs);
        browser.addView(crumbScroll, full());
        listScroll = new ScrollView(this);
        listScroll.setFillViewport(true);
        list = column(this);
        list.setPadding(dp(this, 16), dp(this, 6), dp(this, 16), dp(this, 24));
        listScroll.addView(list);
        browser.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        stage.addView(browser, new FrameLayout.LayoutParams(-1, -1));
        setContentView(stage);
        if (Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        // onResume renders the listing (also after returning from a viewer or the installer).
    }

    @Override protected void onResume() {
        super.onResume();
        if (editing == null && list != null) render();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        back();
    }

    private static ImageButton backButton(LinearLayout topBar) {
        return (ImageButton) ((LinearLayout) topBar.getChildAt(0)).getChildAt(0);
    }

    private void back() {
        if (editing != null) closeEditor(false);
        else if (!directory.equals(boundary) && directory.getParentFile() != null && allowed(directory.getParentFile())) {
            directory = directory.getParentFile();
            render();
        } else finish();
    }

    private boolean allowed(File file) {
        try {
            return FileTools.inside(boundary, file.getCanonicalFile());
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- browser

    private void render() {
        subtitle.setText(FileTools.display(this, directory));
        renderCrumbs();
        list.removeAllViews();
        File[] entries = FileTools.sorted(directory);
        int shown = 0;
        LinearLayout box = null;
        for (File file : entries) {
            if (!allowed(file)) continue;
            if (shown >= MAX_ROWS) break;
            if (box == null) {
                box = column(this);
                box.setBackground(outlined(this, CARD, 14, LINE, 1));
                box.setClipToOutline(true);
            } else box.addView(inset(), new LinearLayout.LayoutParams(-1, dp(this, 1)));
            box.addView(fileRow(file), full());
            shown++;
        }
        if (box == null) {
            list.addView(emptyState(this, "Pusty katalog",
                    "Agent zapisuje tu projekty i wyniki pracy. Utwórz plik lub folder przyciskiem Nowy."), full());
        } else {
            list.addView(box, full());
            if (entries.length > shown) {
                TextView more = monoText(this, "… i jeszcze " + (entries.length - shown) + " elementów", 12, FAINT);
                more.setPadding(dp(this, 4), dp(this, 10), 0, 0);
                list.addView(more, full());
            }
        }
        TextView footer = monoText(this, FileTools.inWorkspace(this, directory)
                ? shown + (shown == 1 ? " element" : " elem.") + " · przytrzymaj plik, aby zobaczyć działania"
                : "poza ~/" + FileTools.WORK + " · otwieranie i udostępnianie działa tylko w katalogu roboczym", 11, FAINT);
        footer.setPadding(dp(this, 4), dp(this, 12), dp(this, 4), 0);
        list.addView(footer, full());
        listScroll.scrollTo(0, 0);
        if (box != null) Motion.stagger(box, 0, 18);
        Motion.fadeIn(footer, 120);
    }

    private View inset() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        return v;
    }

    private void renderCrumbs() {
        crumbs.removeAllViews();
        java.util.List<File> chain = new java.util.ArrayList<>();
        for (File f = directory; f != null && allowed(f); f = f.getParentFile()) {
            chain.add(0, f);
            if (f.equals(boundary)) break;
        }
        for (int i = 0; i < chain.size(); i++) {
            File target = chain.get(i);
            boolean last = i == chain.size() - 1;
            if (i > 0) {
                TextView sep = monoText(this, "/", 12, FAINT);
                sep.setPadding(dp(this, 1), 0, dp(this, 1), 0);
                crumbs.addView(sep);
            }
            TextView seg = monoText(this, i == 0 ? "~" : target.getName(), 12, last ? TEXT : ACCENT_TEXT);
            seg.setTypeface(mono(this, last ? 600 : 400));
            seg.setPadding(dp(this, 6), dp(this, 6), dp(this, 6), dp(this, 6));
            seg.setMinHeight(dp(this, 36));
            seg.setGravity(Gravity.CENTER_VERTICAL);
            if (!last) {
                seg.setBackgroundResource(ripple(this, false));
                seg.setContentDescription("Przejdź do " + (i == 0 ? "katalogu domowego" : target.getName()));
                seg.setOnClickListener(v -> {
                    directory = target;
                    render();
                });
            }
            crumbs.addView(seg);
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private int[] look(File file) {
        // {icon, tint}
        if (file.isDirectory()) return new int[]{R.drawable.ms_folder, ACCENT_TEXT};
        if (FileTools.isApk(file)) return new int[]{R.drawable.ms_android, OK};
        if (FileTools.isImage(file)) return new int[]{R.drawable.ms_image, INFO};
        return new int[]{R.drawable.ms_draft, MUTED};
    }

    private View tile(File file, int size) {
        int[] look = look(file);
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(rounded(this, look[1] == MUTED ? CARD2 : Theme.withAlpha(look[1], 0x22), 10));
        ImageView icon = Ui.icon(this, look[0], look[1], size / 2 + 2);
        tile.addView(icon, new FrameLayout.LayoutParams(dp(this, size / 2 + 2), dp(this, size / 2 + 2), Gravity.CENTER));
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.setLayoutParams(new LinearLayout.LayoutParams(dp(this, size), dp(this, size)));
        return tile;
    }

    private View fileRow(File file) {
        LinearLayout r = Ui.row(this);
        r.setPadding(dp(this, 12), dp(this, 10), dp(this, 10), dp(this, 10));
        r.setBackgroundResource(ripple(this, false));
        r.setMinimumHeight(dp(this, 56));
        r.addView(tile(file, 36));
        LinearLayout texts = column(this);
        texts.setPadding(dp(this, 12), 0, dp(this, 8), 0);
        TextView name = title(this, file.getName(), 15);
        name.setTypeface(sans(this, 500));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        if (file.getName().startsWith(".")) name.setTextColor(MUTED);
        texts.addView(name, full());
        TextView meta = single(monoText(this, meta(file), 11, FAINT));
        meta.setPadding(0, dp(this, 2), 0, 0);
        texts.addView(meta, full());
        r.addView(texts, weight1());
        if (file.isDirectory()) r.addView(Ui.icon(this, R.drawable.ms_chevron_right, FAINT, 20));
        r.setContentDescription((file.isDirectory() ? "Folder " : "Plik ") + file.getName() + ", " + meta.getText());
        r.setOnClickListener(v -> {
            if (file.isDirectory()) {
                directory = file;
                render();
            } else if (editable(file)) openEditor(file);
            else fileSheet(file);
        });
        if (!file.isDirectory()) r.setOnLongClickListener(v -> {
            fileSheet(file);
            return true;
        });
        return r;
    }

    private String meta(File file) {
        if (file.isDirectory()) {
            String[] children = file.list();
            int n = children == null ? 0 : children.length;
            return "katalog · " + n + (n == 1 ? " element" : " elem.") + " · " + date(file.lastModified());
        }
        return size(file.length()) + " · " + date(file.lastModified());
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(PL, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(PL, "%.1f MB", bytes / 1048576.0);
        return String.format(PL, "%.2f GB", bytes / 1073741824.0);
    }

    private static String date(long ms) {
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(ms);
        String pattern = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ? "d MMM HH:mm" : "d MMM yyyy";
        return new SimpleDateFormat(pattern, PL).format(new Date(ms));
    }

    private boolean editable(File file) {
        if (!file.isFile() || file.length() > TEXT_LIMIT || FileTools.isApk(file) || FileTools.isImage(file)) return false;
        String mime = FileTools.mime(file.getName());
        if (mime.startsWith("text/") || mime.equals("application/json") || mime.equals("application/octet-stream")) {
            try {
                byte[] head = new byte[(int) Math.min(8192, file.length())];
                try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                    int n = in.read(head);
                    for (int i = 0; i < n; i++) if (head[i] == 0) return false;
                }
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ file sheet

    private void fileSheet(File file) {
        LinearLayout c = column(this);
        LinearLayout head = Ui.row(this);
        head.addView(tile(file, 44));
        LinearLayout texts = column(this);
        texts.setPadding(dp(this, 14), 0, 0, 0);
        TextView name = title(this, file.getName(), 17);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        texts.addView(name, full());
        TextView where = single(monoText(this, FileTools.display(this, file.getParentFile() == null ? file : file.getParentFile()), 11, FAINT));
        where.setPadding(0, dp(this, 3), 0, 0);
        texts.addView(where, full());
        head.addView(texts, weight1());
        c.addView(head, full());
        LinearLayout facts = column(this);
        facts.setPadding(0, dp(this, 12), 0, dp(this, 4));
        facts.addView(keyValue(this, "rozmiar", size(file.length())), full());
        facts.addView(keyValue(this, "zmieniono", date(file.lastModified())), full());
        facts.addView(keyValue(this, "typ", FileTools.mime(file.getName())), full());
        boolean workspace = FileTools.inWorkspace(this, file);
        if (FileTools.isApk(file) && workspace) {
            try {
                JSONObject apk = FileTools.apkInfo(this, file.getCanonicalFile());
                facts.addView(keyValue(this, "pakiet", apk.optString("package")), full());
                String installed = apk.isNull("installed_version_code") ? "nie" : "wersja " + apk.optLong("installed_version_code");
                facts.addView(keyValue(this, "wersja", apk.optString("version_name") + " (" + apk.optLong("version_code") + ")"), full());
                facts.addView(keyValue(this, "zainstalowana", installed), full());
            } catch (Exception e) {
                facts.addView(keyValue(this, "apk", FileTools.polish(e)), full());
            }
        }
        c.addView(facts, full());
        final Dialog[] sheet = {null};
        if (editable(file)) c.addView(sheetButton("Edytuj", R.drawable.ms_description, false, () -> {
            sheet[0].dismiss();
            openEditor(file);
        }), sheetParams());
        if (workspace) {
            if (FileTools.isApk(file)) c.addView(sheetButton("Zainstaluj", R.drawable.ms_install_mobile, true, () -> {
                sheet[0].dismiss();
                install(file);
            }), sheetParams());
            else c.addView(sheetButton("Otwórz", R.drawable.ms_open_in_new, true, () -> {
                sheet[0].dismiss();
                act(() -> FileTools.open(this, file.getCanonicalFile(), ""));
            }), sheetParams());
            c.addView(sheetButton("Udostępnij", R.drawable.ms_share, false, () -> {
                sheet[0].dismiss();
                act(() -> FileTools.share(this, file.getCanonicalFile(), ""));
            }), sheetParams());
            c.addView(sheetButton("Zapisz w Pobranych", R.drawable.ms_download, false, () -> {
                sheet[0].dismiss();
                saveToDownloads(file);
            }), sheetParams());
        } else {
            TextView note = text(this, "Otwieranie, udostępnianie i instalowanie działa tylko dla plików w ~/"
                    + FileTools.WORK + ".", 13, MUTED);
            note.setPadding(0, dp(this, 8), 0, 0);
            c.addView(note, full());
        }
        sheet[0] = bottomSheet(this, c);
        sheet[0].show();
    }

    private LinearLayout.LayoutParams sheetParams() {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, 8);
        return p;
    }

    private Button sheetButton(String label, int icon, boolean primary, Runnable action) {
        Button b = button(this, label, primary);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(this, 16), dp(this, 10), dp(this, 16), dp(this, 10));
        b.setMinHeight(dp(this, 48));
        b.setMinimumHeight(dp(this, 48));
        android.graphics.drawable.Drawable d = getDrawable(icon);
        if (d != null) {
            d = d.mutate();
            d.setTint(primary ? ON_ACCENT : MUTED);
            d.setBounds(0, 0, dp(this, 20), dp(this, 20));
            b.setCompoundDrawablesRelative(d, null, null, null);
            b.setCompoundDrawablePadding(dp(this, 12));
        }
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private interface Action {
        JSONObject run() throws Exception;
    }

    private void act(Action action) {
        try {
            action.run();
        } catch (Exception e) {
            Toast.makeText(this, FileTools.polish(e), Toast.LENGTH_LONG).show();
        }
    }

    private void install(File file) {
        Toast.makeText(this, "Przygotowuję instalator Androida…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message;
            try {
                JSONObject result = FileTools.install(this, file.getCanonicalFile());
                String label = FileTools.statusLabels().get(result.optString("status"));
                message = label == null ? result.optString("message") : label;
                if ("failed".equals(result.optString("status")) && result.has("android_message"))
                    message += " " + result.optString("android_message");
            } catch (Exception e) {
                message = FileTools.polish(e);
            }
            String shown = message;
            runOnUiThread(() -> {
                if (!isDestroyed()) Toast.makeText(this, shown, Toast.LENGTH_LONG).show();
            });
        }, "androidagent-install").start();
    }

    private void saveToDownloads(File file) {
        new Thread(() -> {
            String message;
            try {
                JSONObject result = FileTools.saveToDownloads(this, file.getCanonicalFile(), "");
                message = "Zapisano w " + result.optString("saved");
            } catch (Exception e) {
                message = FileTools.polish(e);
            }
            String shown = message;
            runOnUiThread(() -> {
                if (!isDestroyed()) Toast.makeText(this, shown, Toast.LENGTH_LONG).show();
            });
        }, "androidagent-download").start();
    }

    // ---------------------------------------------------------------- create

    private void createSheet() {
        LinearLayout c = column(this);
        c.addView(title(this, "Nowy element", 18), full());
        TextView where = single(monoText(this, "w " + FileTools.display(this, directory), 11, FAINT));
        where.setPadding(0, dp(this, 4), 0, dp(this, 14));
        c.addView(where, full());
        EditText name = input(this, "nazwa, np. notatki.md", true);
        name.setSingleLine(true);
        name.setContentDescription("Nazwa pliku lub folderu");
        c.addView(name, full());
        LinearLayout buttons = Ui.row(this);
        Button folder = button(this, "Utwórz folder", false);
        Button file = button(this, "Utwórz plik", true);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, dp(this, 48), 1);
        buttons.addView(folder, fp);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, dp(this, 48), 1);
        pp.leftMargin = dp(this, 10);
        buttons.addView(file, pp);
        LinearLayout.LayoutParams bp = full();
        bp.topMargin = dp(this, 16);
        c.addView(buttons, bp);
        Dialog sheet = bottomSheet(this, c);
        folder.setOnClickListener(v -> {
            File target = newChild(name.getText().toString());
            if (target == null) return;
            if (!target.mkdirs()) {
                Toast.makeText(this, "Nie udało się utworzyć folderu", Toast.LENGTH_LONG).show();
                return;
            }
            sheet.dismiss();
            directory = target;
            render();
        });
        file.setOnClickListener(v -> {
            File target = newChild(name.getText().toString());
            if (target == null) return;
            sheet.dismiss();
            openEditor(target);
        });
        if (sheet.getWindow() != null)
            sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.show();
        name.requestFocus();
    }

    private File newChild(String raw) {
        String value = raw.trim();
        if (value.isEmpty() || value.contains("/") || ".".equals(value) || "..".equals(value) || value.indexOf('\0') >= 0) {
            Toast.makeText(this, "Nieprawidłowa nazwa", Toast.LENGTH_LONG).show();
            return null;
        }
        File target = new File(directory, value);
        if (target.exists() || !allowed(target)) {
            Toast.makeText(this, target.exists() ? "Taki element już istnieje" : "Nieprawidłowa nazwa", Toast.LENGTH_LONG).show();
            return null;
        }
        return target;
    }

    // ---------------------------------------------------------------- editor

    private void openEditor(File file) {
        if (!allowed(file) || file.length() > TEXT_LIMIT) {
            Toast.makeText(this, "Edytor obsługuje pliki tekstowe do 256 KB", Toast.LENGTH_LONG).show();
            return;
        }
        String value;
        try {
            value = file.exists() ? new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            Toast.makeText(this, FileTools.polish(e), Toast.LENGTH_LONG).show();
            return;
        }
        if (value.indexOf('\0') >= 0) {
            fileSheet(file);
            return;
        }
        editing = file;
        original = value;
        editorView = column(this);
        editorView.setBackgroundColor(BG);
        TextView save = barAction(this, "Zapisz");
        save.setOnClickListener(v -> saveEditor());
        LinearLayout bar = topBar(this, file.getName(), FileTools.display(this, file), save);
        backButton(bar).setOnClickListener(v -> closeEditor(false));
        editorView.addView(bar, full());
        editor = input(this, "Pusty plik", true);
        editor.setContentDescription("Edytor pliku " + file.getName());
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setSingleLine(false);
        editor.setHorizontallyScrolling(false);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTextSize(13);
        editor.setLineSpacing(dp(this, 2), 1f);
        editor.setVerticalScrollBarEnabled(true);
        editor.setText(value);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, 0, 1);
        ep.setMargins(dp(this, 12), dp(this, 12), dp(this, 12), 0);
        editorView.addView(editor, ep);
        editorStatus = single(monoText(this, "", 11, FAINT));
        editorStatus.setPadding(dp(this, 16), dp(this, 8), dp(this, 16), dp(this, 10));
        editorView.addView(editorStatus, full());
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateStatus(); }
        });
        updateStatus();
        stage.addView(editorView, new FrameLayout.LayoutParams(-1, -1));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        browser.setVisibility(View.GONE);
        Motion.fadeIn(editorView, 0);
    }

    private void updateStatus() {
        if (editor == null || editorStatus == null) return;
        CharSequence s = editor.getText();
        int lines = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') lines++;
        boolean dirty = !s.toString().equals(original);
        String base = "UTF-8 · " + lines + (lines == 1 ? " wiersz" : " wierszy") + " · "
                + size(s.toString().getBytes(StandardCharsets.UTF_8).length);
        editorStatus.setText(dirty ? "● niezapisane · " + base : (editing != null && editing.exists() ? "zapisane · " : "nowy plik · ") + base);
        editorStatus.setTextColor(dirty ? WARN : FAINT);
    }

    private boolean saveEditor() {
        if (editing == null) return false;
        try {
            if (!allowed(editing)) throw new IOException("Ścieżka poza katalogiem");
            File parent = editing.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            String value = editor.getText().toString();
            try (OutputStream out = new FileOutputStream(editing)) {
                out.write(value.getBytes(StandardCharsets.UTF_8));
            }
            original = value;
            updateStatus();
            Toast.makeText(this, "Zapisano " + editing.getName(), Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            Toast.makeText(this, FileTools.polish(e), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void closeEditor(boolean force) {
        if (editing == null) return;
        if (!force && !editor.getText().toString().equals(original)) {
            confirmSheet(this, "Odrzucić zmiany?", "Zmiany w " + editing.getName() + " nie zostały zapisane.",
                    "Odrzuć", true, () -> closeEditor(true));
            return;
        }
        android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(editor.getWindowToken(), 0);
        stage.removeView(editorView);
        editorView = null;
        editor = null;
        editorStatus = null;
        editing = null;
        browser.setVisibility(View.VISIBLE);
        render();
    }
}
