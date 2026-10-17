package dev.mateuszsury.androidagent;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import static dev.mateuszsury.androidagent.Ui.*;

/** One chat, full screen: a Claude Code-style transcript above a prompt box. */
public class ChatActivity extends Screen implements ChatHub.Listener {
    /** When each chat's current run started (by run id), so the timer survives reopening the screen. */
    private static final java.util.Map<String, long[]> RUN_STARTS = new java.util.HashMap<>();
    private static final int STREAM_FRAME_MS = 50;

    private ChatHub hub;
    private Sessions.Session ses;
    private LinearLayout messages, approval, working, composer;
    private ScrollView scroll;
    private TextView title, chipText, hintText, devHint;
    private Spark.Line spark;
    private Button mic;
    private ImageButton speaker, send;
    private EditText input;
    private View welcome;
    /** The reply being streamed: a Markdown column described as "Odpowiedź na żywo". */
    private ChatRows.Gutter streamingRow;
    private Markdown.Content streamingContent;
    private long lastStreamRender;
    private boolean streamTickPosted;
    private final Runnable streamTick = () -> {
        streamTickPosted = false;
        renderStreamingNow();
    };
    private Bubble.Kind lastKind;
    private boolean workingShown, wasRunning;
    private final java.util.ArrayDeque<Runnable> renderedMessages = new java.util.ArrayDeque<>();
    private final java.util.Map<Sessions.Msg, ChatRows.ToolRow> toolRows = new java.util.IdentityHashMap<>();
    private java.util.List<String> pending = new java.util.ArrayList<>(); // attachments for the next message
    private DraftImports draftImports;
    private TextView importStatus;
    private android.widget.HorizontalScrollView trayScroll;
    private LinearLayout tray;
    private static final int REQ_CAMERA = 21, REQ_PHOTOS = 22, REQ_FILES = 23;
    private boolean firstResume = true;
    private int restoreScrollY = -1;
    private boolean waitingForHost;
    private int sendGeneration;
    private Boolean sendLit;
    private ValueAnimator sendFade;
    private boolean endPending, endSmooth;
    private int endGeneration;
    private final android.os.Handler sendStartup = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ThreadPoolExecutor thumbnails = new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(256),
            runnable -> new Thread(runnable, "chat-thumbnail"),
            new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        hub = ChatHub.get(this);
        ses = hub.sessions.find(getIntent().getStringExtra("id"));
        if (ses == null) {
            finish();
            return;
        }
        Object retained = getLastNonConfigurationInstance();
        draftImports = retained instanceof DraftImports && ses.id.equals(((DraftImports) retained).sessionId)
                ? (DraftImports) retained : new DraftImports(getApplicationContext(), ses.id);
        draftImports.owner = new java.lang.ref.WeakReference<>(this);
        pending = draftImports.pending;

        LinearLayout root = column(this);
        root.setBackgroundColor(BG);

        // header: back, title with the model chip under it, remote control and read-aloud
        ImageButton rc = null;
        if ("claude".equals(ses.backend)) {
            rc = iconButton(this, R.drawable.ic_remote, "Kontynuuj w aplikacji Claude", MUTED);
            rc.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
            rc.setOnClickListener(v -> handoff());
        }
        speaker = iconButton(this, R.drawable.ms_volume_off, "Czytaj odpowiedzi", MUTED);
        speaker.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        speaker.setOnClickListener(v -> {
            Prefs.setSpeakReplies(this, !Prefs.speakReplies(this));
            if (!Prefs.speakReplies(this)) Voice.stopSpeaking();
            Toast.makeText(this, Prefs.speakReplies(this) ? "Odpowiedzi będą czytane na głos"
                    : "Odpowiedzi nie będą czytane na głos", Toast.LENGTH_SHORT).show();
            refresh();
        });
        LinearLayout top = topBar(this, displayTitle(), null, rc, speaker);
        title = topBarTitle(top);
        title.setTextSize(17);
        LinearLayout chip = chip(this, "");
        chipText = (TextView) chip.getChildAt(0);
        chip.setContentDescription("Wybierz model");
        chip.setOnClickListener(v -> chooseModel());
        Motion.press(chip);
        LinearLayout.LayoutParams cp = wrap();
        cp.topMargin = dp(this, 4);
        ((LinearLayout) title.getParent()).addView(chip, cp);
        root.addView(top, full());

        // transcript
        scroll = new ScrollView(this);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(this, 20));
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        messages = column(this);
        messages.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 20));
        messages.setClipToPadding(false);
        messages.setClipChildren(false);
        messages.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (endPending && b - t != ob - ot) applyScrollEnd();
        });
        scroll.addView(messages);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // inline approval card (when an agent asks for an OK)
        approval = column(this);
        LinearLayout.LayoutParams ap = full();
        ap.leftMargin = ap.rightMargin = dp(this, 12);
        root.addView(approval, ap);

        // working line: spark, verb or status, elapsed time, Stop
        working = row(this);
        working.setPadding(dp(this, 18), dp(this, 6), dp(this, 12), dp(this, 4));
        spark = new Spark.Line(this);
        // A long tool status ellipsizes before it can push the elapsed time out of view.
        spark.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int max = (r - l) - dp(this, 16 + 10 + 60);
            if (max > 0 && spark.verbView().getMaxWidth() != max) spark.verbView().setMaxWidth(max);
        });
        working.addView(spark, weight1());
        LinearLayout stop = row(this);
        stop.setPadding(dp(this, 10), 0, dp(this, 13), 0);
        stop.setMinimumHeight(dp(this, 34));
        stop.setBackground(outlined(this, ERR_SOFT, 17, Theme.withAlpha(ERR, 0x4D), 1));
        stop.setForeground(getDrawable(ripple(this, false)));
        stop.addView(icon(this, R.drawable.ic_stop, ERR, 16));
        TextView stopLabel = monoText(this, "Stop", 12, ERR);
        stopLabel.setTypeface(mono(this, 600));
        stopLabel.setPadding(dp(this, 4), 0, 0, 0);
        stop.addView(stopLabel);
        stop.setContentDescription("Zatrzymaj");
        stop.setClickable(true);
        stop.setFocusable(true);
        stop.setOnClickListener(v -> hub.stop(ses));
        Motion.press(stop);
        LinearLayout.LayoutParams sp = wrap();
        sp.leftMargin = dp(this, 8);
        working.addView(stop, sp);
        working.setVisibility(View.GONE);
        root.addView(working, full());

        // composer
        importStatus = monoText(this, "Importowanie załączników…", 12, FAINT);
        importStatus.setPadding(dp(this, 18), dp(this, 6), dp(this, 18), dp(this, 2));
        importStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(importStatus, full());
        trayScroll = new android.widget.HorizontalScrollView(this);
        trayScroll.setHorizontalScrollBarEnabled(false);
        tray = row(this);
        tray.setPadding(dp(this, 12), dp(this, 6), dp(this, 12), 0);
        trayScroll.addView(tray);
        trayScroll.setVisibility(View.GONE);
        root.addView(trayScroll, full());

        composer = row(this);
        composer.setGravity(Gravity.TOP); // the prompt glyph shares the editor's first baseline
        composer.setPadding(dp(this, 12), dp(this, 2), dp(this, 4), dp(this, 2));
        composer.setBackground(focusable(this, 14));
        composer.setAddStatesFromChildren(true);
        composer.setMinimumHeight(dp(this, 50));
        TextView prompt = monoText(this, "›", 17, ACCENT_TEXT);
        prompt.setTypeface(mono(this, 600));
        prompt.setPadding(0, 0, dp(this, 9), 0);
        prompt.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        composer.addView(prompt);
        input = new EditText(this);
        input.setHint(composerHint());
        input.setHintTextColor(FAINT);
        input.setTextColor(TEXT);
        input.setTextSize(15);
        input.setTypeface(sans(this, 400));
        input.setMaxLines(getResources().getConfiguration().screenHeightDp < 480 ? 2 : 6);
        input.setBackground(null);
        input.setPadding(0, dp(this, 13), dp(this, 4), dp(this, 13));
        input.setHighlightColor(Theme.withAlpha(ACCENT, 0x55));
        GradientDrawable cursor = new GradientDrawable();
        cursor.setColor(ACCENT);
        cursor.setSize(dp(this, 2), dp(this, 18));
        input.setTextCursorDrawable(cursor);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        composer.addView(input, weight1());
        ImageButton attach = iconButton(this, R.drawable.ms_attach_file, "Dodaj zdjęcie lub plik", MUTED);
        attach.setOnClickListener(v -> attachSheet());
        composer.addView(attach, bottomAligned(40, 40, 0, 3));
        mic = button(this, "", false);
        mic.setContentDescription("Dyktuj");
        mic.setOnClickListener(v -> toggleMic());
        LinearLayout.LayoutParams mp = bottomAligned(-1, 40, 0, 3);
        composer.addView(mic, mp);
        micIdle();
        send = new ImageButton(this);
        send.setImageResource(R.drawable.ms_arrow_upward);
        send.setContentDescription("Wyślij");
        send.setScaleType(ImageView.ScaleType.CENTER);
        send.setBackground(oval(CARD2));
        send.setOnClickListener(v -> send());
        composer.addView(send, bottomAligned(36, 36, 4, 5));
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                paintSend();
            }
        });
        paintSend();
        LinearLayout.LayoutParams pp = full();
        pp.leftMargin = pp.rightMargin = dp(this, 12);
        pp.topMargin = dp(this, 6);
        root.addView(composer, pp);

        // the mode line under the prompt box
        LinearLayout hint = row(this);
        hint.setPadding(dp(this, 18), dp(this, 6), dp(this, 18), dp(this, 10));
        hintText = single(monoText(this, "", 11, FAINT));
        hint.addView(hintText, weight1());
        devHint = single(monoText(this, "›› tryb deweloperski", 11, INFO));
        hint.addView(devHint);
        root.addView(hint, full());

        // the outer frame takes the system-bar insets
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(root);
        setContentView(frame);

        if (state != null) {
            input.setText(state.getString("draft_text", ""));
            int length = input.length();
            input.setSelection(Math.max(0, Math.min(length, state.getInt("selection_start", length))),
                    Math.max(0, Math.min(length, state.getInt("selection_end", length))));
            java.util.ArrayList<String> restored = state.getStringArrayList("draft_files");
            if (retained != draftImports && restored != null) pending.addAll(restored);
            if (!state.getBoolean("scroll_at_end", true)) restoreScrollY = state.getInt("scroll_y");
            if (retained != draftImports && state.getBoolean("importing"))
                Toast.makeText(this, "Import został przerwany. Wybierz niedodane pliki ponownie.", Toast.LENGTH_LONG).show();
        }
        renderTray();
        String draft = getIntent().getStringExtra("draft");
        if (draft != null && state == null) {
            input.setText(draft);
            input.setSelection(draft.length());
        }
        if (state == null && getIntent().getBooleanExtra("show_attachments", false)) {
            input.post(this::attachSheet);
        }
    }

    private LinearLayout.LayoutParams bottomAligned(int widthDp, int heightDp, int leftDp, int bottomDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                widthDp < 0 ? LinearLayout.LayoutParams.WRAP_CONTENT : dp(this, widthDp), dp(this, heightDp));
        p.gravity = Gravity.BOTTOM;
        p.leftMargin = dp(this, leftDp);
        p.bottomMargin = dp(this, bottomDp);
        return p;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ses == null || isFinishing()) return;
        final int previousY = firstResume && restoreScrollY >= 0 ? restoreScrollY : scroll.getScrollY();
        boolean follow = firstResume ? restoreScrollY < 0 : nearEnd();
        rebuild();
        hub.addListener(this);
        hub.setViewing(ses, true);
        hub.select(ses);
        hub.setForegroundChat(ses, true);
        refresh();
        paintSend();
        // The local tool host is independent of Accessibility and may be gone after
        // process restart. Start it from a visible chat before the first model request.
        if (EmbeddedRuntime.get(this).ready()) withToolHost(this::refresh);
        if (follow) scrollToEnd(false);
        else scroll.post(() -> scroll.scrollTo(0, previousY));
        firstResume = false;
    }

    @Override
    protected void onPause() {
        waitingForHost = false;
        sendGeneration++;
        sendStartup.removeCallbacksAndMessages(null);
        if (Voice.listening()) Voice.stopListening();
        if (ses != null) {
            hub.setViewing(ses, false);
            hub.setForegroundChat(ses, false);
            hub.removeListener(this);
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        thumbnails.shutdownNow();
        if (draftImports != null) {
            if (draftImports.owner.get() == this) draftImports.owner.clear();
            if (!isChangingConfigurations()) draftImports.close();
        }
        super.onDestroy();
    }

    @Override public Object onRetainNonConfigurationInstance() { return draftImports; }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (input != null) {
            out.putString("draft_text", input.getText().toString());
            out.putInt("selection_start", input.getSelectionStart());
            out.putInt("selection_end", input.getSelectionEnd());
            out.putInt("scroll_y", scroll.getScrollY());
            out.putBoolean("scroll_at_end", nearEnd());
        }
        out.putStringArrayList("draft_files", new java.util.ArrayList<>(pending));
        out.putBoolean("importing", draftImports != null && draftImports.importing);
        super.onSaveInstanceState(out);
    }

    // ------------------------------------------------------------------ labels

    private String displayTitle() {
        return ses.title.isEmpty() ? "Nowa rozmowa" : ses.title;
    }

    private String modelLabel() {
        return ses.model.isEmpty() ? "domyślny model" : Models.label(ses.backend, ses.model);
    }

    private String composerHint() {
        return "Napisz do " + ses.brainName() + "…";
    }

    // ------------------------------------------------------------------ composer

    private void paintSend() {
        boolean has = (input.getText().toString().trim().length() > 0 || !pending.isEmpty())
                && (draftImports == null || !draftImports.importing) && !waitingForHost;
        send.setEnabled(has);
        if (sendLit != null && sendLit == has) return;
        boolean first = sendLit == null;
        sendLit = has;
        final int bg = has ? ACCENT : CARD2, fg = has ? ON_ACCENT : FAINT;
        if (sendFade != null) sendFade.cancel();
        final GradientDrawable d = (GradientDrawable) send.getBackground();
        if (first || !Motion.enabled() || !send.isAttachedToWindow()) {
            d.setColor(bg);
            send.setImageTintList(ColorStateList.valueOf(fg));
            return;
        }
        final int fromBg = has ? CARD2 : ACCENT, fromFg = has ? FAINT : ON_ACCENT;
        sendFade = ValueAnimator.ofFloat(0f, 1f);
        sendFade.setDuration(Motion.SHORT + 40);
        sendFade.setInterpolator(Motion.EASE_OUT);
        sendFade.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            d.setColor(Theme.blend(fromBg, bg, f));
            send.setImageTintList(ColorStateList.valueOf(Theme.blend(fromFg, fg, f)));
        });
        sendFade.start();
        if (has) {
            send.setScaleX(0.82f);
            send.setScaleY(0.82f);
            send.animate().scaleX(1f).scaleY(1f).setDuration(Motion.MEDIUM)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
        }
    }

    private void send() {
        if (waitingForHost || (draftImports != null && draftImports.importing)) return;
        String draft = input.getText().toString();
        String text = draft.trim();
        java.util.List<String> files = new java.util.ArrayList<>(pending);
        if (text.isEmpty() && files.isEmpty()) return;
        if ("/status".equals(text) || McpHost.get(this).running()) {
            sendReady(draft, files);
            return;
        }
        waitingForHost = true;
        int generation = ++sendGeneration;
        paintSend();
        withToolHost(() -> {
            if (!waitingForHost || generation != sendGeneration) return;
            waitingForHost = false;
            sendStartup.removeCallbacksAndMessages(null);
            paintSend();
            sendReady(draft, files);
        });
        // Screen reports a host startup failure after 5 s. Re-enable the composer too;
        // a failed start or leaving the screen must retain the unsent draft and files.
        sendStartup.postDelayed(() -> {
            if (generation != sendGeneration || !waitingForHost) return;
            waitingForHost = false;
            paintSend();
        }, 5500);
    }

    private void sendReady(String draft, java.util.List<String> files) {
        String err = hub.send(ses, draft.trim(), files);
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            return;
        }
        if (input.getText().toString().equals(draft)) input.setText("");
        pending.removeAll(files);
        renderTray();
        scrollToEnd(true);
    }

    // ------------------------------------------------------------------ hub events

    @Override
    public void onChatsChanged() {
        if (hub.sessions.find(ses.id) == null) {
            finish();
            return;
        }
        refresh();
    }

    @Override
    public void onMessage(Sessions.Session s, Sessions.Msg msg) {
        if (s != ses) return;
        boolean follow = nearEnd();
        View v = msg.kind == Bubble.Kind.BUDDY ? adoptStreaming(msg) : null;
        if (v == null) {
            removeStreaming();
            v = show(msg);
            Motion.enter(v, 0);
        }
        if (follow || msg.kind == Bubble.Kind.USER) scrollToEnd(true);
    }

    @Override
    public void onMessageUpdated(Sessions.Session s, Sessions.Msg msg) {
        if (s != ses) return;
        ChatRows.ToolRow row = toolRows.get(msg);
        if (row == null) return;
        boolean follow = nearEnd();
        row.bind(msg.meta, true);
        if (follow) scrollToEnd(true);
    }

    /** Opens this chat in the embedded terminal with Remote Control for this session only. */
    private void handoff() {
        confirmSheet(this, "Kontynuować w aplikacji Claude?",
                "Otwiera tę rozmowę we wbudowanym terminalu z Remote Control dla tej sesji. "
                        + "Możesz kontynuować ją w oficjalnej aplikacji Claude. Dostępność zależy od konta. Wpisz /exit, aby zakończyć.",
                "Otwórz", false, () -> {
                    if (ses.running) {
                        Toast.makeText(this, "Ta rozmowa wciąż pracuje. Najpierw ją zatrzymaj.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        Termux.runInTerminal(this, "handoff", ses.id, Prefs.devMode(this) ? "1" : "0", ses.model);
                    } catch (Exception e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
    }

    /** Pick this chat's model; the conversation continues with the new one. */
    private void chooseModel() {
        final String[][] options = Models.forBackend(ses.backend);
        LinearLayout c = column(this);
        TextView h = title(this, "Model " + ses.brainName(), 18);
        h.setAccessibilityHeading(true);
        c.addView(h);
        TextView sub = text(this, "Wybór dla tej rozmowy. Dostępność modeli zależy od Twojego konta.", 13, MUTED);
        sub.setPadding(0, dp(this, 4), 0, dp(this, 12));
        c.addView(sub);
        final Dialog[] d = new Dialog[1];
        boolean known = false;
        for (final String[] o : options) {
            boolean on = o[0].equals(ses.model);
            known |= on;
            LinearLayout r = row(this);
            r.setPadding(dp(this, 12), dp(this, 11), dp(this, 12), dp(this, 11));
            r.setBackground(on ? outlined(this, Theme.withAlpha(ACCENT, 0x14), 12, Theme.withAlpha(ACCENT, 0x66), 1) : null);
            r.setForeground(getDrawable(ripple(this, false)));
            r.addView(radio(on));
            LinearLayout t = column(this);
            t.setPadding(dp(this, 14), 0, 0, 0);
            TextView name = title(this, o[0].isEmpty() ? "Domyślny" : o[1], 15);
            name.setTypeface(sans(this, on ? 600 : 500));
            t.addView(name);
            TextView id = single(monoText(this, o[0].isEmpty() ? "model domyślny agenta" : o[0], 12, on ? ACCENT_TEXT : FAINT));
            id.setPadding(0, dp(this, 2), 0, 0);
            t.addView(id);
            r.addView(t, weight1());
            r.setOnClickListener(v -> {
                d[0].dismiss();
                if (!o[0].equals(ses.model)) hub.setModel(ses, o[0]);
            });
            LinearLayout.LayoutParams rp = full();
            rp.bottomMargin = dp(this, 4);
            c.addView(r, rp);
        }
        TextView label = monoText(this, "INNY MODEL", 11, FAINT);
        label.setTypeface(mono(this, 600));
        label.setLetterSpacing(0.1f);
        label.setPadding(dp(this, 2), dp(this, 14), 0, dp(this, 8));
        c.addView(label);
        LinearLayout other = row(this);
        final EditText et = input(this, "Nazwa modelu z Twojego konta…", true);
        et.setSingleLine(true);
        if (!known) et.setText(ses.model);
        other.addView(et, weight1());
        Button use = button(this, "Wybierz", true);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 46));
        up.leftMargin = dp(this, 8);
        other.addView(use, up);
        use.setOnClickListener(v -> {
            d[0].dismiss();
            hub.setModel(ses, et.getText().toString().trim());
        });
        c.addView(other, full());
        d[0] = bottomSheet(this, c);
        d[0].show();
    }

    private View radio(boolean on) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(outlined(this, 0x00000000, 10, on ? ACCENT : OUTLINE, 2));
        if (on) {
            View in = new View(this);
            in.setBackground(oval(ACCENT));
            f.addView(in, new FrameLayout.LayoutParams(dp(this, 9), dp(this, 9), Gravity.CENTER));
        }
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 20), dp(this, 20)));
        return f;
    }

    /** Talk: tap to start, speak, it sends when you stop. Tap again to send right away. */
    private void toggleMic() {
        if (Voice.listening()) {
            Voice.finishListening();
            return;
        }
        if (!Voice.micAllowed(this)) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 3);
            return;
        }
        final String before = input.getText().toString().trim();
        String err = Voice.listen(this, new Voice.Listener() {
            @Override
            public void onPartial(String text) {
                input.setText(join(before, text));
                input.setSelection(input.getText().length());
            }

            @Override
            public void onFinal(String text) {
                input.setText(join(before, text));
                micIdle();
                send();
            }

            @Override
            public void onEnd(String message) {
                micIdle();
                if (message != null) Toast.makeText(ChatActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            return;
        }
        mic.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ms_mic, 0, 0, 0);
        mic.setCompoundDrawableTintList(ColorStateList.valueOf(ON_ACCENT));
        mic.setCompoundDrawablePadding(dp(this, 4));
        mic.setText("Słucham…");
        mic.setTypeface(mono(this, 600));
        mic.setTextSize(12);
        mic.setTextColor(ON_ACCENT);
        mic.setPadding(dp(this, 10), 0, dp(this, 12), 0);
        mic.setBackground(rounded(this, ACCENT, 20));
        Motion.pop(mic, 0);
        input.setHint("Mów teraz…");
    }

    private void micIdle() {
        mic.setText("");
        mic.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ms_mic, 0, 0, 0);
        mic.setCompoundDrawableTintList(ColorStateList.valueOf(MUTED));
        mic.setCompoundDrawablePadding(0);
        mic.setPadding(dp(this, 8), 0, dp(this, 8), 0);
        mic.setBackgroundResource(ripple(this, true));
        input.setHint(composerHint());
    }

    private static String join(String a, String b) {
        return a.isEmpty() ? b : a + " " + b;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 3 && Voice.micAllowed(this)) toggleMic();
    }

    // ------------------------------------------------------------------ state

    private void refresh() {
        boolean speak = Prefs.speakReplies(this);
        speaker.setImageResource(speak ? R.drawable.ms_volume_up : R.drawable.ms_volume_off);
        speaker.setImageTintList(ColorStateList.valueOf(speak ? ACCENT_TEXT : MUTED));
        setChanged(title, displayTitle());
        boolean dev = Prefs.devMode(this);
        String chipLabel = ses.brainName() + " · " + (ses.model.isEmpty() ? "domyślny" : Models.label(ses.backend, ses.model));
        if (!(chipLabel + (dev ? " · dev" : "")).contentEquals(chipText.getText())) {
            SpannableStringBuilder sb = new SpannableStringBuilder(chipLabel);
            if (dev) {
                sb.append(" · dev");
                sb.setSpan(new ForegroundColorSpan(INFO), sb.length() - 3, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            chipText.setText(sb);
        }
        setChanged(hintText, (ses.brainName() + " · " + modelLabel()).toLowerCase(java.util.Locale.ROOT));
        devHint.setVisibility(dev ? View.VISIBLE : View.GONE);
        boolean running = ses.running;
        if (running) {
            spark.start(runStart());
            spark.setStatus(ses.status);
        }
        if (running != workingShown) {
            workingShown = running;
            if (running) Motion.expand(working);
            else Motion.collapse(working);
        }
        if (running != wasRunning) {
            wasRunning = running;
            for (ChatRows.ToolRow r : toolRows.values()) r.setLive(running);
        }
        renderApproval();
        renderStreaming();
    }

    /** The start of the current run: remembered per chat and run, so reopening keeps the timer. */
    private long runStart() {
        long[] s = RUN_STARTS.get(ses.id);
        if (s == null || s[0] != ses.runId) {
            s = new long[]{ses.runId, SystemClock.elapsedRealtime()};
            RUN_STARTS.put(ses.id, s);
        }
        return s[1];
    }

    private static void setChanged(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }

    // ------------------------------------------------------------------ streaming

    private void removeStreaming() {
        messages.removeCallbacks(streamTick);
        streamTickPosted = false;
        if (streamingRow == null) return;
        messages.removeView(streamingRow);
        streamingRow = null;
        streamingContent = null;
    }

    /** The completed reply takes over the live view, so nothing jumps when streaming ends. */
    private View adoptStreaming(Sessions.Msg m) {
        if (streamingRow == null) return null;
        messages.removeCallbacks(streamTick);
        streamTickPosted = false;
        final ChatRows.Gutter row = streamingRow;
        streamingContent.setContentDescription(null);
        streamingContent.setMarkdown(m.text.trim(), false);
        streamingRow = null;
        streamingContent = null;
        lastKind = Bubble.Kind.BUDDY;
        trackMessage(() -> messages.removeView(row));
        return row;
    }

    private void renderStreaming() {
        if (ses.streamText.isEmpty()) {
            removeStreaming();
            return;
        }
        if (streamingRow == null) {
            removeWelcome();
            boolean follow = nearEnd();
            streamingContent = new Markdown.Content(this);
            streamingContent.setContentDescription("Odpowiedź na żywo");
            streamingRow = new ChatRows.Gutter(this, new Dot(this, TEXT));
            streamingRow.body.addView(streamingContent, full());
            messages.addView(streamingRow, spacing(Bubble.Kind.BUDDY));
            streamingContent.setMarkdown(ses.streamText, true);
            lastStreamRender = SystemClock.uptimeMillis();
            Motion.fadeIn(streamingRow, 0);
            if (follow) scrollToEnd(true);
            return;
        }
        long wait = lastStreamRender + STREAM_FRAME_MS - SystemClock.uptimeMillis();
        if (wait > 0) {
            if (!streamTickPosted) {
                streamTickPosted = true;
                messages.postDelayed(streamTick, wait);
            }
            return;
        }
        renderStreamingNow();
    }

    private void renderStreamingNow() {
        if (streamingContent == null || ses.streamText.isEmpty()) return;
        if (ses.streamText.equals(streamingContent.source())) return;
        boolean follow = nearEnd();
        streamingContent.setMarkdown(ses.streamText, true);
        lastStreamRender = SystemClock.uptimeMillis();
        if (follow) scrollToEnd(true);
    }

    // ------------------------------------------------------------------ approval

    private String shownApproval;

    private void renderApproval() {
        ApprovedCommands commandManager = ApprovedCommands.get(getApplicationContext());
        ApprovedCommands.Pending command = commandManager.pendingForChat(ses.id);
        BuddyService svc = BuddyService.get();
        String ask = command != null ? command.summary
                : svc != null && svc.bubble() != null && hub.sessions.current == ses ? svc.bubble().pendingConfirmText() : null;
        String approvalKey = command != null ? "command:" + command.id : ask != null ? "bubble:" + ask : null;
        if (approvalKey == null ? shownApproval == null : approvalKey.equals(shownApproval)) return;
        shownApproval = approvalKey;
        approval.removeAllViews();
        if (ask == null) return;
        showApproval(ask, command != null && command.devOnly, command == null ? 0 : command.framing,
                command == null ? null : command.id);
    }

    /**
     * Claude Code's permission prompt: what the agent wants, the question, and the two answers. Only the
     * first {@code framing} characters (androidagent's own wording) are dimmed: a command containing a blank
     * line must not be able to grey out its own first part.
     */
    private void showApproval(String ask, boolean isCommand, int framing, final String commandId) {
        LinearLayout card = terminalBox(this, "Wymaga zgody", WARN);
        LinearLayout hr = row(this);
        hr.addView(icon(this, R.drawable.ms_shield_person, WARN, 18));
        TextView lab = title(this, isCommand ? "Komenda do uruchomienia" : "Prośba agenta", 15);
        lab.setPadding(dp(this, 8), 0, 0, 0);
        hr.addView(lab, weight1());
        card.addView(hr);
        TextView well = monoText(this, "", 12, CODE_TEXT);
        SpannableStringBuilder summary = new SpannableStringBuilder(ask);
        int cut = Math.min(framing, ask.length());
        if (cut > 0)
            summary.setSpan(new ForegroundColorSpan(CODE_MUTED), 0, cut, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        well.setText(summary);
        well.setLineSpacing(dp(this, 2), 1.06f);
        well.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        ScrollView details = new ScrollView(this) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int limit = dp(ChatActivity.this, 180);
                if (View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED)
                    limit = Math.min(limit, View.MeasureSpec.getSize(heightMeasureSpec));
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            }
        };
        details.setBackground(outlined(this, CODE, 10, CODE_LINE, 1));
        details.setClipToOutline(true);
        details.addView(well);
        LinearLayout.LayoutParams wp = full();
        wp.topMargin = dp(this, 10);
        wp.bottomMargin = dp(this, 12);
        card.addView(details, wp);
        TextView question = title(this, "Czy kontynuować?", 14);
        question.setPadding(0, 0, 0, dp(this, 10));
        card.addView(question);
        LinearLayout btns = row(this);
        Button deny = button(this, "Odrzuć", false);
        deny.setTextColor(ERR);
        deny.setBackground(outlined(this, ERR_SOFT, 10, Theme.withAlpha(ERR, 0x40), 1));
        Button ok = button(this, "Zatwierdź", true);
        btns.addView(deny, new LinearLayout.LayoutParams(0, dp(this, 44), 1));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(0, dp(this, 44), 1);
        op.leftMargin = dp(this, 10);
        btns.addView(ok, op);
        card.addView(btns, full());
        // Bind the exact request ID, never whichever command happens to be pending
        // when this view is clicked after a timeout, cancellation or replacement.
        deny.setOnClickListener(v -> answer(commandId, false));
        ok.setOnClickListener(v -> answer(commandId, true));
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = dp(this, 6);
        lp.bottomMargin = dp(this, 4);
        approval.addView(card, lp);
        Motion.enter(card, 0);
    }

    private void answer(String commandId, boolean approved) {
        if (commandId != null) {
            ApprovedCommands.get(getApplicationContext()).answer(commandId, approved);
            renderApproval();
            return;
        }
        BuddyService svc = BuddyService.get();
        if (svc != null && svc.bubble() != null) svc.bubble().answerFromApp(approved);
        renderApproval();
    }

    // --------------------------------------------------------- attachments

    private void attachSheet() {
        LinearLayout c = column(this);
        TextView h = title(this, "Dodaj załącznik", 18);
        h.setAccessibilityHeading(true);
        h.setPadding(0, 0, 0, dp(this, 8));
        c.addView(h);
        final Dialog[] d = new Dialog[1];
        Object[][] opts = {
                {R.drawable.ms_photo_camera, "Zrób zdjęcie", "Otwórz aparat"},
                {R.drawable.ms_image, "Zdjęcia", "Wybierz z galerii"},
                {R.drawable.ms_description, "Pliki", "Dokumenty i inne pliki do 25 MB"},
        };
        for (int i = 0; i < opts.length; i++) {
            final int which = i;
            LinearLayout r = row(this);
            r.setPadding(dp(this, 4), dp(this, 12), dp(this, 4), dp(this, 12));
            r.setBackgroundResource(ripple(this, false));
            FrameLayout ic = new FrameLayout(this);
            ic.setBackground(rounded(this, ACCENT_SOFT, 12));
            ic.addView(icon(this, (Integer) opts[i][0], ACCENT_TEXT, 22),
                    new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
            r.addView(ic, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
            LinearLayout t = column(this);
            t.setPadding(dp(this, 14), 0, 0, 0);
            t.addView(title(this, (String) opts[i][1], 15));
            t.addView(monoText(this, (String) opts[i][2], 12, FAINT));
            r.addView(t, weight1());
            r.setOnClickListener(v -> {
                d[0].dismiss();
                pick(which);
            });
            c.addView(r, full());
        }
        d[0] = bottomSheet(this, c);
        d[0].show();
    }

    private void pick(int which) {
        if (draftImports.importing) {
            Toast.makeText(this, "Poczekaj na zakończenie importu załączników.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (which == 0) {
                android.content.Intent i = new android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                android.net.Uri out = FilesProvider.captureUri();
                FilesProvider.captureFile(this).delete();
                i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, out);
                i.setClipData(ClipData.newRawUri("photo", out));
                i.addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION | android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, REQ_CAMERA);
            } else if (which == 1) {
                android.content.Intent i;
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    i = new android.content.Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
                            .putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, 10);
                } else {
                    i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).setType("image/*")
                            .putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                startActivityForResult(i, REQ_PHOTOS);
            } else {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, REQ_FILES);
            }
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "Żadna aplikacja na tym telefonie tego nie obsługuje.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int code, int result, android.content.Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK || (code != REQ_CAMERA && code != REQ_PHOTOS && code != REQ_FILES)) return;
        final java.util.List<android.net.Uri> uris = new java.util.ArrayList<>();
        if (code == REQ_CAMERA) {
            if (FilesProvider.captureFile(this).length() > 0) uris.add(FilesProvider.captureUri());
        } else if (data != null) {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        }
        if (uris.isEmpty()) return;
        draftImports.start(uris);
    }

    private void renderTray() {
        importStatus.setVisibility(draftImports.importing ? View.VISIBLE : View.GONE);
        tray.removeAllViews();
        trayScroll.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
        for (final String id : new java.util.ArrayList<>(pending)) {
            FrameLayout f = new FrameLayout(this);
            f.addView(attachmentView(id, false));
            ImageButton x = new ImageButton(this);
            x.setImageResource(R.drawable.ms_close);
            x.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            x.setScaleType(ImageView.ScaleType.FIT_CENTER);
            x.setPadding(dp(this, 3), dp(this, 3), dp(this, 3), dp(this, 3));
            x.setBackground(oval(0xB3000000));
            x.setContentDescription("Usuń załącznik");
            x.setOnClickListener(v -> {
                pending.remove(id);
                renderTray();
            });
            FrameLayout.LayoutParams xp = new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.TOP | Gravity.END);
            xp.setMargins(0, dp(this, 4), dp(this, 4), 0);
            f.addView(x, xp);
            LinearLayout.LayoutParams p = wrap();
            p.rightMargin = dp(this, 8);
            tray.addView(f, p);
        }
        paintSend();
    }

    /** Imports belong to the draft, so rotation cannot drop a completed file or retain an Activity. */
    private static final class DraftImports {
        final android.content.Context app;
        final String sessionId;
        final java.util.List<String> pending = new java.util.ArrayList<>();
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        java.lang.ref.WeakReference<ChatActivity> owner = new java.lang.ref.WeakReference<>(null);
        boolean importing;
        volatile boolean closed;

        DraftImports(android.content.Context app, String sessionId) { this.app = app; this.sessionId = sessionId; }

        void start(java.util.List<android.net.Uri> selection) {
            if (importing || closed) return;
            importing = true;
            ChatActivity screen = owner.get();
            if (screen != null) screen.renderTray();
            java.util.List<android.net.Uri> uris = new java.util.ArrayList<>(selection);
            new Thread(() -> {
                java.util.List<String> ids = new java.util.ArrayList<>();
                String error = null;
                for (android.net.Uri uri : uris) {
                    if (closed) break;
                    try { ids.add(Attachments.importUri(app, uri)); }
                    catch (Exception e) { error = e.getMessage(); }
                }
                final String message = error;
                main.post(() -> {
                    importing = false;
                    if (closed) {
                        for (String id : ids) {
                            java.io.File file = Attachments.file(app, id);
                            if (file != null) file.delete();
                        }
                        return;
                    }
                    pending.addAll(ids);
                    ChatActivity current = owner.get();
                    if (current == null || current.isDestroyed() || current.isFinishing()) return;
                    current.renderTray();
                    if (message != null) Toast.makeText(current, message, Toast.LENGTH_LONG).show();
                });
            }, "chat-attachment-import").start();
        }

        void close() { closed = true; owner.clear(); }
    }

    /** A photo thumbnail (rounded) or a file chip; tapping opens it in another app. */
    private View attachmentView(final String id, boolean inThread) {
        View v;
        if (Attachments.isImage(id)) {
            ImageView iv = new ImageView(this);
            int side = dp(this, inThread ? 168 : 64);
            final int radius = dp(this, inThread ? 10 : 12);
            iv.setBackground(rounded(this, CARD2, inThread ? 10 : 12));
            iv.setContentDescription(Attachments.name(id));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            iv.setLayoutParams(new LinearLayout.LayoutParams(side, side));
            thumbnails.execute(() -> {
                android.graphics.Bitmap bitmap = Attachments.thumb(getApplicationContext(), id, side);
                if (bitmap == null) return;
                runOnUiThread(() -> {
                    if (isDestroyed() || !iv.isAttachedToWindow()) { bitmap.recycle(); return; }
                    iv.setImageBitmap(bitmap);
                });
            });
            v = iv;
        } else {
            LinearLayout chip = row(this);
            chip.setPadding(dp(this, 10), dp(this, 8), dp(this, 12), dp(this, 8));
            chip.setBackground(outlined(this, inThread ? BG : CARD, 10, OUTLINE, 1));
            chip.addView(icon(this, R.drawable.ms_description, ACCENT_TEXT, 18));
            TextView t = single(monoText(this, Attachments.name(id), 13, TEXT));
            t.setMaxWidth(dp(this, 200));
            t.setPadding(dp(this, 8), 0, inThread ? 0 : dp(this, 18), 0);
            chip.addView(t);
            chip.setMinimumHeight(dp(this, inThread ? 0 : 64));
            v = chip;
        }
        v.setOnClickListener(x -> openAttachment(id));
        return v;
    }

    private void openAttachment(String id) {
        android.net.Uri u = FilesProvider.attachmentUri(id);
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(u, getContentResolver().getType(u))
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Żadna aplikacja nie otworzy pliku " + Attachments.name(id) + ".", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ transcript

    /** Re-render the whole history (on resume); rows appear without motion. */
    private void rebuild() {
        thumbnails.getQueue().clear();
        renderTray();
        removeStreaming();
        messages.removeAllViews();
        renderedMessages.clear();
        toolRows.clear();
        welcome = null;
        lastKind = null;
        wasRunning = ses.running;
        if (ses.messages.isEmpty()) showWelcome();
        else for (Sessions.Msg m : ses.messages) show(m);
    }

    private View show(Sessions.Msg m) {
        return add(m.kind, m.text, m.files, m.meta, m);
    }

    private View show(Bubble.Kind kind, final String text) {
        return add(kind, text, null, null, null);
    }

    private View add(Bubble.Kind kind, String text, java.util.List<String> files, org.json.JSONObject meta,
                     final Sessions.Msg msg) {
        removeWelcome();
        final View v;
        switch (kind) {
            case USER:
                v = userRow(text, files);
                break;
            case BUDDY:
                v = replyRow(text);
                break;
            case STEP: {
                final ChatRows.ToolRow row = new ChatRows.ToolRow(this, text, meta, ses.running);
                row.view.setOnLongClickListener(x -> {
                    copy(row.copyText());
                    return true;
                });
                if (msg != null) toolRows.put(msg, row);
                v = row.view;
                break;
            }
            default:
                v = errorRow(text);
        }
        messages.addView(v, spacing(kind));
        lastKind = kind;
        trackMessage(() -> {
            messages.removeView(v);
            if (msg != null) toolRows.remove(msg);
        });
        return v;
    }

    /** Space before a row: wide between turns, tight between consecutive tool calls. */
    private LinearLayout.LayoutParams spacing(Bubble.Kind kind) {
        LinearLayout.LayoutParams p = full();
        Bubble.Kind prev = lastKind;
        int top;
        if (prev == null) top = 2;
        else if (kind == Bubble.Kind.USER) top = 30;
        else if (kind == Bubble.Kind.STEP && prev == Bubble.Kind.STEP) top = 12;
        else if (prev == Bubble.Kind.USER) top = 20;
        else top = 18;
        p.topMargin = dp(this, top);
        if (kind == Bubble.Kind.USER) p.leftMargin = p.rightMargin = -dp(this, 8);
        return p;
    }

    /** "› prompt" on a quiet band, the gutter aligned with the replies' dots. */
    private View userRow(String text, java.util.List<String> files) {
        TextView glyph = monoText(this, "›", 15, ACCENT_TEXT);
        glyph.setTypeface(mono(this, 600));
        glyph.setGravity(Gravity.CENTER_HORIZONTAL);
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ChatRows.Gutter row = new ChatRows.Gutter(this, glyph);
        row.setPadding(dp(this, 8), dp(this, 10), dp(this, 12), dp(this, 10));
        row.setBackground(rounded(this, USER, 12));
        boolean hasFiles = files != null && !files.isEmpty();
        row.align = !hasFiles;
        if (hasFiles) {
            for (int i = 0; i < files.size(); i++) {
                LinearLayout.LayoutParams fp = wrap();
                if (i < files.size() - 1 || !text.isEmpty()) fp.bottomMargin = dp(this, 8);
                row.body.addView(attachmentView(files.get(i), true), fp);
            }
        }
        if (!text.isEmpty()) {
            TextView tv = text(this, text, 15, TEXT);
            tv.setLineSpacing(dp(this, 2), 1.1f);
            row.body.addView(tv, full());
        }
        copyOnLongPress(row, text);
        return row;
    }

    /** "● reply" with the reply rendered as Markdown. */
    private View replyRow(String text) {
        ChatRows.Gutter row = new ChatRows.Gutter(this, new Dot(this, TEXT));
        row.body.addView(MessageContent.render(this, text), full());
        return row;
    }

    private View errorRow(String text) {
        ChatRows.Gutter row = new ChatRows.Gutter(this, new Dot(this, ERR));
        TextView tv = text(this, text, 14, ERR);
        tv.setLineSpacing(dp(this, 2), 1.08f);
        row.body.addView(tv, full());
        copyOnLongPress(row, text);
        return row;
    }

    private void removeWelcome() {
        if (welcome == null) return;
        messages.removeView(welcome);
        welcome = null;
    }

    /** An empty chat: Claude Code's welcome box with a few tips. */
    private void showWelcome() {
        LinearLayout box = terminalBox(this, "androidagent", Theme.withAlpha(ACCENT, 0x99));
        box.setPadding(dp(this, 18), dp(this, 22), dp(this, 18), dp(this, 18));
        LinearLayout head = row(this);
        Spark s = new Spark(this).animated(false).color(ACCENT);
        head.addView(s, new LinearLayout.LayoutParams(dp(this, 20), dp(this, 20)));
        TextView t = title(this, ses.brainName(), 19);
        t.setAccessibilityHeading(true);
        t.setPadding(dp(this, 10), 0, 0, 0);
        head.addView(t);
        box.addView(head);
        TextView sub = text(this, "W czym mogę pomóc?", 14, MUTED);
        sub.setPadding(dp(this, 30), dp(this, 2), 0, dp(this, 14));
        box.addView(sub);
        Object[][] tips = {
                {"/status", " — stan telefonu", (Runnable) () -> {
                    input.setText("/status");
                    input.setSelection(input.length());
                    input.requestFocus();
                }},
                {"", "Dołącz zdjęcie lub plik", (Runnable) this::attachSheet},
                {"", "Agent może sterować telefonem", null},
        };
        for (Object[] tip : tips) {
            SpannableStringBuilder sb = new SpannableStringBuilder((String) tip[0]);
            sb.setSpan(new ForegroundColorSpan(ACCENT_TEXT), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append((String) tip[1]);
            TextView line = monoText(this, "", 13, MUTED);
            line.setText(sb);
            line.setPadding(dp(this, 30), dp(this, 5), 0, dp(this, 5));
            final Runnable action = (Runnable) tip[2];
            if (action != null) {
                line.setBackgroundResource(ripple(this, false));
                line.setOnClickListener(v -> action.run());
            }
            box.addView(line, full());
        }
        View rule = new View(this);
        rule.setBackgroundColor(LINE);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 1));
        rp.topMargin = dp(this, 12);
        rp.bottomMargin = dp(this, 10);
        rp.leftMargin = dp(this, 30);
        box.addView(rule, rp);
        TextView model = single(monoText(this, "model: " + modelLabel().toLowerCase(java.util.Locale.ROOT)
                + (Prefs.devMode(this) ? " · tryb deweloperski" : ""), 11, FAINT));
        model.setPadding(dp(this, 30), 0, 0, 0);
        box.addView(model, full());
        LinearLayout.LayoutParams bp = full();
        bp.topMargin = dp(this, 8);
        messages.addView(box, bp);
        welcome = box;
    }

    private void trackMessage(Runnable remove) {
        renderedMessages.addLast(remove);
        while (renderedMessages.size() > Sessions.MAX_MESSAGES) renderedMessages.removeFirst().run();
    }

    private void copyOnLongPress(View v, String text) {
        v.setOnLongClickListener(x -> {
            copy(text);
            return true;
        });
    }

    private void copy(String text) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("androidagent", text));
        Toast.makeText(this, "Skopiowano", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ scrolling

    private void scrollToEnd(boolean smooth) {
        endSmooth = smooth && Motion.enabled();
        endPending = true;
        final int generation = ++endGeneration;
        // Apply now and again after the next layout (new rows are measured by then).
        scroll.post(() -> {
            applyScrollEnd();
            scroll.postDelayed(() -> {
                if (generation == endGeneration) endPending = false;
            }, 160);
        });
    }

    private void applyScrollEnd() {
        int target = Math.max(0, messages.getHeight() - scroll.getHeight());
        if (target == scroll.getScrollY()) return;
        if (endSmooth && Math.abs(target - scroll.getScrollY()) < scroll.getHeight() * 2) scroll.smoothScrollTo(0, target);
        else scroll.scrollTo(0, target);
    }

    private boolean nearEnd() {
        return messages.getHeight() - scroll.getScrollY() - scroll.getHeight() <= dp(this, 96);
    }
}
