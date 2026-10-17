package dev.mateuszsury.androidagent;

import android.animation.ValueAnimator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Build;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The floating androidagent bubble and its mini chat panel, drawn as an accessibility overlay.
 *
 * <p>Collapsed: a dark circular chip with a coral face (a breathing spark while an agent works),
 * accent arcs orbiting it, a mono status pill and an unread dot. Expanded: a terminal-like panel
 * in the Claude Code style — "›" user prompts, "●" replies rendered as Markdown, tool calls as
 * coloured dots with a "└" result line — plus the permission prompt and a composer.
 */
final class Bubble implements ChatHub.Listener {
    interface ConfirmCallback {
        void onAnswer(boolean approved);
    }

    /** How many recent messages the panel renders; the full history is in the app's chat screen. */
    private static final int PANEL_MESSAGES = 60;
    /** Collapsed geometry (dp): the ring box, the chip inside it, and the padding around both. */
    private static final int RING = 74, CHIP = 56, FRAME_PAD = 4;
    private static final Pattern CHATS = Pattern.compile("^(\\d+) chats · ");

    private final BuddyService svc;
    private final WindowManager wm;
    private final Vibrator vibrator;

    private FrameLayout root;
    private WindowManager.LayoutParams lp;

    // collapsed
    private LinearLayout collapsed;
    private FrameLayout head;   // ring + chip: scaled and spun as one while dragging
    private FrameLayout chip;   // the dark circle that is touched and dragged
    private ImageView face;
    private Spark chipSpark;
    private View badge;
    private LinearLayout pill;
    private Spark pillSpark;
    private TextView pillText;
    private ValueAnimator breathing;
    private RingView ring;
    private ImageView stopChip;
    private int faceRes;
    private boolean faceShowsSpark, working;
    // expanded
    private LinearLayout panel, messages, live, confirmCard;
    private ScrollView scroll;
    private TextView title, subtitle, backendBadge, confirmText;
    private Spark headerSpark;
    private ImageButton listButton, stopButton, mic, send;
    private android.animation.Animator micPulse;
    private EditText input;
    private Spark.Line statusLine;
    private LinearLayout streamRow;
    private LinearLayout streamContent;
    private View emptyView, olderView;
    private Bubble.Kind lastKind;

    private boolean expanded, running, passThrough, showingList;
    private int imeTop;
    private ConfirmCallback pendingConfirm;
    private ChatHub hub;
    private Sessions sessions;
    private Sessions.Session viewingSes;
    private ScrollView listScroll;
    private LinearLayout sessionList;
    /** When each running chat started, for the status line's elapsed time. */
    private final WeakHashMap<Sessions.Session, Long> runStarts = new WeakHashMap<>();

    Bubble(BuddyService svc) {
        this.svc = svc;
        this.wm = svc.getSystemService(WindowManager.class);
        this.vibrator = svc.getSystemService(Vibrator.class);
    }

    // ------------------------------------------------------------- layout

    void show() {
        root = new FrameLayout(svc) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (expanded && (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE)) {
                    if (e.getAction() == KeyEvent.ACTION_UP) onBack();
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        buildCollapsed();
        buildPanel();
        root.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE && expanded && pendingConfirm == null) {
                // An edge swipe for Back also lands outside the panel; wait briefly so Back can cancel this.
                root.removeCallbacks(outsideCollapse);
                root.postDelayed(outsideCollapse, 350);
            }
            return false;
        });
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 0, PixelFormat.TRANSLUCENT);
        root.addView(collapsed);
        applyCollapsedParams();
        createArcs();
        createDismissTarget();
        addWindow(root, lp);
        hub = ChatHub.get(svc);
        sessions = hub.sessions;
        hub.addListener(this);
        trackRuns();
        renderCurrent();
        applyEnabled();
    }

    void remove() {
        if (hub != null) cancelConfirm();
        unregisterBack();
        if (root != null) root.removeCallbacks(outsideCollapse);
        if (viewingSes != null && hub != null) hub.setViewing(viewingSes, false);
        viewingSes = null;
        expanded = false;
        if (arcs != null) {
            try {
                wm.removeView(arcs);
            } catch (Exception ignored) {
            }
        }
        if (dismissWindow != null) {
            try {
                wm.removeView(dismissWindow);
            } catch (Exception ignored) {
            }
        }
        if (hub != null) hub.removeListener(this);
        stopBreathing();
        try {
            wm.removeView(root);
        } catch (Exception ignored) {
        }
    }

    private void buildCollapsed() {
        collapsed = new LinearLayout(svc);
        collapsed.setOrientation(LinearLayout.HORIZONTAL);
        collapsed.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout bubbleFrame = new FrameLayout(svc);
        // room around the chip for the working ring, and below it for the stop button
        bubbleFrame.setPadding(dp(FRAME_PAD), dp(FRAME_PAD), dp(FRAME_PAD), dp(FRAME_PAD));
        bubbleFrame.setClipToPadding(false);
        bubbleFrame.setClipChildren(false);

        head = new FrameLayout(svc);
        head.setClipChildren(false);
        ring = new RingView(svc);
        ring.setVisibility(View.GONE);
        head.addView(ring, new FrameLayout.LayoutParams(dp(RING), dp(RING), Gravity.CENTER));

        chip = new FrameLayout(svc);
        chip.setElevation(dp(6));
        chip.setContentDescription("androidagent");
        face = new ImageView(svc);
        face.setScaleType(ImageView.ScaleType.FIT_CENTER);
        face.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        chip.addView(face, new FrameLayout.LayoutParams(dp(CHIP), dp(CHIP), Gravity.CENTER));
        chipSpark = new Spark(svc).animated(false);
        chipSpark.setVisibility(View.GONE);
        chip.addView(chipSpark, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        head.addView(chip, new FrameLayout.LayoutParams(dp(CHIP), dp(CHIP), Gravity.CENTER));

        stopChip = new ImageView(svc);
        stopChip.setImageResource(R.drawable.ic_stop);
        stopChip.setScaleType(ImageView.ScaleType.CENTER);
        stopChip.setElevation(dp(4));
        stopChip.setContentDescription("Zatrzymaj androidagent");
        stopChip.setVisibility(View.GONE);
        stopChip.setOnClickListener(v -> {
            vibrate(25);
            if (controlling) {
                svc.takeBackControl(); // also refuses phone actions from other sessions for a minute
            } else {
                hub.stopAll();
                toast("Zatrzymuję…");
            }
        });
        LinearLayout column = new LinearLayout(svc);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        column.addView(head, new LinearLayout.LayoutParams(dp(RING), dp(RING)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(30), dp(30));
        sp.topMargin = dp(1);
        column.addView(stopChip, sp);
        bubbleFrame.addView(column);

        // unread dot on the chip's upper rim, toward the middle of the screen
        badge = new View(svc);
        badge.setVisibility(View.GONE);
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(13), dp(13), Gravity.TOP | Gravity.END);
        blp.topMargin = dp(FRAME_PAD + 10);
        blp.setMarginEnd(dp(FRAME_PAD + 10));
        bubbleFrame.addView(badge, blp);
        collapsed.addView(bubbleFrame);

        pill = new LinearLayout(svc);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(10), dp(7), dp(12), dp(7));
        pill.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        pillSpark = new Spark(svc).animated(false);
        pill.addView(pillSpark, new LinearLayout.LayoutParams(dp(13), dp(13)));
        pillText = Ui.single(Ui.monoText(svc, "", 12, Ui.TEXT));
        pillText.setTypeface(Ui.mono(svc, 500));
        pillText.setMaxWidth(dp(210));
        pillText.setPadding(dp(7), 0, 0, 0);
        pill.addView(pillText, Ui.wrap());
        pill.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.TOP;
        plp.topMargin = dp(FRAME_PAD + RING / 2 - 15); // level with the chip's centre, not the stop button below it
        plp.leftMargin = dp(2);
        plp.rightMargin = dp(6);
        collapsed.addView(pill, plp);
        pill.setOnClickListener(v -> expand());

        chip.setOnTouchListener(new DragListener());
        chip.setOnClickListener(v -> expand()); // for accessibility: touches go through the drag listener
        styleCollapsed();
        showFace(R.drawable.bubble_face);
    }

    /** Colours of the collapsed views from the current palette (also after a theme change). */
    private void styleCollapsed() {
        chip.setBackground(circle(Ui.CARD, controlling ? Ui.ACCENT : Ui.OUTLINE, 1));
        face.setImageTintList(ColorStateList.valueOf(pendingConfirm != null ? Ui.WARN : Ui.ACCENT));
        chipSpark.color(Ui.ACCENT);
        stopChip.setBackground(circle(Ui.CARD, Ui.OUTLINE, 1));
        stopChip.setImageTintList(ColorStateList.valueOf(controlling ? Ui.ERR : Ui.TEXT));
        badge.setBackground(circle(Ui.ACCENT, Ui.BG, 2));
        stylePill();
    }

    private void stylePill() {
        boolean wait = pendingConfirm != null;
        boolean ctl = controlling && !wait;
        int tone = wait ? Ui.WARN : Ui.ACCENT;
        pill.setBackground(ctl ? rounded(Ui.ACCENT, 20, 0, 0)
                : rounded(Ui.CARD, 20, Theme.withAlpha(tone, 0x8C), 1));
        pillText.setTextColor(ctl ? Ui.ON_ACCENT : Ui.TEXT);
        pillSpark.color(ctl ? Ui.ON_ACCENT : tone);
        pillSpark.animated(running && !wait);
    }

    private void buildPanel() {
        panel = new LinearLayout(svc);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(rounded(Ui.BG, 20, Ui.OUTLINE, 1));
        panel.setElevation(dp(10));

        // header: ✻ androidagent [claude]            chats  new  stop  close
        //           chat title ˅
        LinearLayout top = Ui.row(svc);
        top.setPadding(dp(14), dp(10), dp(6), dp(8));
        LinearLayout titles = Ui.column(svc);
        titles.setOnClickListener(v -> toggleSessionList());
        titles.setBackgroundResource(Ui.ripple(svc, false));
        LinearLayout titleRow = Ui.row(svc);
        headerSpark = new Spark(svc).animated(false).color(Ui.MUTED);
        titleRow.addView(headerSpark, new LinearLayout.LayoutParams(dp(16), dp(16)));
        title = Ui.single(Ui.monoText(svc, "androidagent", 14, Ui.TEXT));
        title.setTypeface(Ui.mono(svc, 600));
        title.setAccessibilityHeading(true);
        title.setPadding(dp(8), 0, 0, 0);
        titleRow.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        backendBadge = Ui.badge(svc, "claude", Ui.ACCENT_TEXT);
        LinearLayout.LayoutParams bp = Ui.wrap();
        bp.leftMargin = dp(8);
        titleRow.addView(backendBadge, bp);
        titles.addView(titleRow);
        subtitle = Ui.single(Ui.text(svc, "", 13, Ui.MUTED));
        chevronForList = null;
        subtitle.setPadding(dp(24), dp(3), 0, 0);
        subtitle.setCompoundDrawablePadding(dp(2));
        subtitle.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.FAINT));
        titles.addView(subtitle, Ui.wrap());
        top.addView(titles, Ui.weight1());
        listButton = panelIcon(R.drawable.ms_forum, "Rozmowy", Ui.MUTED, v -> toggleSessionList());
        top.addView(listButton);
        top.addView(panelIcon(R.drawable.ms_add, "Nowa rozmowa", Ui.MUTED, v -> newChat(Prefs.backend(svc))));
        stopButton = panelIcon(R.drawable.ic_stop, "Zatrzymaj", Ui.ERR, v -> stop());
        stopButton.setVisibility(View.GONE);
        top.addView(stopButton);
        top.addView(panelIcon(R.drawable.ms_close, "Zamknij", Ui.MUTED, v -> collapse()));
        panel.addView(top, Ui.full());
        panel.addView(Ui.divider(svc));

        scroll = new ScrollView(svc);
        scroll.setVerticalScrollBarEnabled(false);
        // Tapping the conversation hides the keyboard.
        scroll.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN && keyboardVisible()) hideKeyboard();
            return false;
        });
        LinearLayout content = Ui.column(svc);
        content.setPadding(dp(14), dp(12), dp(14), dp(14));
        messages = Ui.column(svc);
        content.addView(messages, Ui.full());
        live = Ui.column(svc);
        statusLine = new Spark.Line(svc);
        statusLine.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = Ui.full();
        slp.topMargin = dp(12);
        live.addView(statusLine, slp);
        content.addView(live, Ui.full());
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        listScroll = new ScrollView(svc);
        listScroll.setVerticalScrollBarEnabled(false);
        sessionList = Ui.column(svc);
        sessionList.setPadding(dp(12), dp(12), dp(12), dp(12));
        listScroll.addView(sessionList);
        listScroll.setVisibility(View.GONE);
        panel.addView(listScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // Claude Code's permission prompt: a bordered box with the label set into its top edge
        confirmCard = Ui.terminalBox(svc, "Wymaga zgody", Ui.WARN);
        confirmCard.setPadding(dp(14), dp(18), dp(14), dp(12));
        confirmText = Ui.text(svc, "", 14, Ui.TEXT);
        confirmText.setLineSpacing(dp(2), 1.05f);
        ScrollView cs = new ScrollView(svc) {
            @Override
            protected void onMeasure(int w, int h) {
                int max = dp(180);
                if (MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED) max = Math.min(max, MeasureSpec.getSize(h));
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        cs.addView(confirmText);
        confirmCard.addView(cs, Ui.full());
        TextView ask = Ui.monoText(svc, "Czy chcesz kontynuować?", 12, Ui.MUTED);
        ask.setPadding(0, dp(10), 0, dp(10));
        confirmCard.addView(ask, Ui.full());
        LinearLayout row = Ui.row(svc);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button deny = Ui.outlinedButton(svc, "Odrzuć", Ui.ERR);
        deny.setOnClickListener(v -> answerConfirm(false));
        Button approve = Ui.button(svc, "Zatwierdź", true);
        approve.setOnClickListener(v -> answerConfirm(true));
        row.addView(deny, Ui.wrap());
        LinearLayout.LayoutParams alp = Ui.wrap();
        alp.leftMargin = dp(8);
        row.addView(approve, alp);
        confirmCard.addView(row, Ui.full());
        confirmCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams clp = Ui.full();
        clp.leftMargin = clp.rightMargin = dp(12);
        clp.topMargin = dp(4);
        clp.bottomMargin = dp(4);
        panel.addView(confirmCard, clp);

        LinearLayout inputRow = Ui.row(svc);
        inputRow.setGravity(Gravity.BOTTOM);
        inputRow.setPadding(dp(12), dp(6), dp(12), dp(12));
        input = Ui.input(svc, "Napisz do Claude Code…", false);
        input.setBackground(Ui.focusable(svc, 22));
        input.setPadding(dp(16), dp(11), dp(16), dp(11));
        input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, id, e) -> {
            if (id != EditorInfo.IME_ACTION_SEND) return false;
            send();
            return true;
        });
        inputRow.addView(input, Ui.weight1());
        mic = circleButton(R.drawable.ic_mic, "Dyktuj", Ui.CARD, Ui.OUTLINE, Ui.MUTED);
        mic.setOnClickListener(v -> toggleMic());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(44), dp(44));
        mlp.leftMargin = dp(8);
        inputRow.addView(mic, mlp);
        send = circleButton(R.drawable.ms_arrow_upward, "Wyślij", Ui.ACCENT, 0, Ui.ON_ACCENT);
        send.setOnClickListener(v -> send());
        LinearLayout.LayoutParams sendp = new LinearLayout.LayoutParams(dp(44), dp(44));
        sendp.leftMargin = dp(8);
        inputRow.addView(send, sendp);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSendState();
            }
        });
        updateSendState();
        panel.addView(inputRow, Ui.full());
    }

    private void updateSendState() {
        boolean has = input.getText().toString().trim().length() > 0;
        float a = has ? 1f : 0.45f;
        if (send.getAlpha() == a) return;
        if (Motion.enabled() && send.isAttachedToWindow()) {
            send.animate().alpha(a).setDuration(Motion.SHORT).setInterpolator(Motion.EASE_OUT).start();
        } else {
            send.setAlpha(a);
        }
    }

    private void applyCollapsedParams() {
        Rect screen = screen();
        int[] pos = Prefs.bubblePos(svc);
        boolean right = pos[0] != 0;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        // Anchor to the nearest edge so the status pill grows toward the middle of the screen.
        lp.gravity = Gravity.TOP | (right ? Gravity.END : Gravity.START);
        collapsed.setLayoutDirection(right ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        lp.x = dp(6);
        lp.y = Math.max(dp(40), Math.min(pos[1], screen.height() - dp(120)));
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | (passThrough ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
    }

    /** Called when windows change; keeps the expanded panel above the on-screen keyboard. */
    void onKeyboardTop(int top) {
        if (top == imeTop) return;
        imeTop = top;
        if (expanded && !passThrough) {
            applyExpandedParams();
            relayout();
            scrollToEnd();
        }
    }

    private void applyExpandedParams() {
        Rect screen = screen();
        lp.width = screen.width() - dp(20);
        int bottom = imeTop > 0 ? imeTop : screen.height() - dp(24);
        lp.height = Math.max(dp(220), bottom - dp(44) - dp(10));
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.x = 0;
        lp.y = dp(44);
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | (passThrough ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
    }

    void expand() {
        if (expanded) return;
        expanded = true;
        // the panel grows out of the bubble: remember where its chip was
        int chipCenterY = lp.y + dp(FRAME_PAD + RING / 2);
        root.setVisibility(View.VISIBLE);
        badge.setVisibility(View.GONE);
        updateBreathing();
        root.removeAllViews();
        root.addView(panel);
        applyExpandedParams();
        relayout();
        boolean right = Prefs.bubblePos(svc)[0] != 0;
        panel.animate().cancel();
        panel.setPivotX(right ? lp.width : 0);
        panel.setPivotY(Math.max(0, Math.min(lp.height, chipCenterY - lp.y)));
        if (Motion.enabled()) {
            panel.setScaleX(0.9f);
            panel.setScaleY(0.9f);
            panel.setAlpha(0f);
            panel.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setInterpolator(Motion.EASE_OUT).setDuration(Motion.MEDIUM + 60).start();
        } else {
            panel.setScaleX(1f);
            panel.setScaleY(1f);
            panel.setAlpha(1f);
        }
        scrollToEnd();
        root.post(this::registerBack);
        updateViewing();
        refreshStatus();
        if (pendingConfirm == null && !sessions.current.running && !showingList) {
            input.requestFocus();
            input.postDelayed(() -> {
                InputMethodManager imm = svc.getSystemService(InputMethodManager.class);
                imm.showSoftInput(input, 0);
            }, 150);
        }
    }

    void collapse() {
        if (!expanded) return;
        if (Voice.listening()) Voice.stopListening();
        expanded = false;
        updateViewing();
        unregisterBack();
        hideKeyboard();
        root.removeAllViews();
        root.addView(collapsed);
        applyCollapsedParams();
        relayout();
        applyEnabled();
        refreshStatus();
        popIn();
        if (themeDirty) restyle();
    }

    private boolean themeDirty;

    /** Appearance changed: rebuild the panel now, or on the next collapse if it's open. */
    void applyTheme() {
        themeDirty = true;
        if (!expanded) restyle();
    }

    private void restyle() {
        themeDirty = false;
        if (Voice.listening()) Voice.stopListening();
        CharSequence draft = input.getText();
        CharSequence pendingText = confirmText.getText();
        boolean confirmShown = pendingConfirm != null;
        buildPanel();
        input.setText(draft);
        if (confirmShown) {
            confirmText.setText(pendingText);
            confirmCard.setVisibility(View.VISIBLE);
        }
        styleCollapsed();
        renderedFor = null;
        if (showingList) renderSessionList();
        renderCurrent();
    }

    /** The bubble can be turned off in the app; it then only appears for approval cards. */
    void applyEnabled() {
        if (locked && expanded && pendingConfirm == null) collapse();
        boolean inlineApproval = pendingConfirm != null && hub.inForegroundChat();
        boolean show = !locked && !inlineApproval && (Prefs.bubbleEnabled(svc) || expanded || pendingConfirm != null || controlling);
        boolean wasHidden = root.getVisibility() != View.VISIBLE;
        root.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && wasHidden && !expanded) popIn();
        if (!show) showDismissTarget(false);
        updateBreathing();
    }

    private boolean locked, controlling;

    /** An agent is operating the phone: show the bubble (even if hidden) in its "in control" state. */
    void setControlling(boolean on) {
        if (controlling == on) return;
        controlling = on;
        styleCollapsed();
        applyEnabled();
        refreshStatus();
    }

    /** Hide everything on the lock screen; the service calls this on screen off / unlock. */
    void setLocked(boolean on) {
        if (locked == on) return;
        locked = on;
        applyEnabled();
    }

    // ------------------------------------------------- drag-down to dismiss

    // A roomy window (so the grow animation never clips) holding a circle with a drawn ✕.
    // It is added before the bubble's window, so the bubble always stays on top of it.
    private FrameLayout dismissWindow;
    private ImageView dismissCircle;
    private boolean overTarget;

    // Full-screen, untouchable layer behind the target and bubble for the Tesla-coil effect.
    private ArcView arcs;

    private void createArcs() {
        arcs = new ArcView(svc);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        arcs.setVisibility(View.GONE);
        addWindow(arcs, p);
    }

    private void feedArcs() {
        // Flickering lightning is exactly what "remove animations" is meant to avoid.
        if (arcs == null || dismissCircle == null || !Motion.enabled()) return;
        arcs.setVisibility(View.VISIBLE);
        int[] t = targetCenter();
        arcs.update(lp.x + faceOffX, lp.y + lpToScreenY + faceOffY, t[0], t[1], overTarget);
    }

    /** Let the arcs die down, then hide the layer. */
    private void endArcs(boolean explode) {
        if (arcs == null || arcs.getVisibility() != View.VISIBLE) return;
        if (explode) arcs.explode();
        else arcs.stop();
        arcs.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (arcs.idle()) arcs.setVisibility(View.GONE);
                else arcs.postDelayed(this, 300);
            }
        }, 300);
    }

    /** The dismiss target drawn as an electrode: dark core and coral rim; white-hot when the bubble is on it. */
    private GradientDrawable electrode(boolean hot) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        d.setGradientRadius(dp(hot ? 34 : 32));
        d.setColors(hot ? new int[]{0xFFFFFFFF, Theme.lighten(Ui.ACCENT, 0.7f), Ui.ACCENT}
                : new int[]{Ui.CODE, Theme.blend(Ui.ACCENT, Ui.CODE, 0.18f), Theme.darken(Ui.ACCENT, 0.78f)});
        d.setStroke(dp(hot ? 2 : 1), hot ? Theme.lighten(Ui.ACCENT, 0.85f) : Theme.withAlpha(Ui.ACCENT, 0xCC));
        return d;
    }

    private void createDismissTarget() {
        dismissWindow = new FrameLayout(svc);
        dismissCircle = new ImageView(svc);
        dismissCircle.setImageResource(R.drawable.ic_dismiss);
        dismissCircle.setScaleType(ImageView.ScaleType.CENTER);
        dismissCircle.setBackground(electrode(false));
        dismissCircle.setElevation(dp(4));
        dismissCircle.setContentDescription("Ukryj dymek");
        dismissWindow.addView(dismissCircle, new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER));
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(dp(110), dp(110),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.y = dp(40);
        dismissWindow.setVisibility(View.GONE);
        addWindow(dismissWindow, p);
    }

    private void showDismissTarget(boolean show) {
        if (dismissWindow == null) return;
        if (show && dismissWindow.getVisibility() == View.VISIBLE) return;
        if (show) {
            overTarget = false;
            dismissCircle.setScaleX(1f);
            dismissCircle.setScaleY(1f);
            dismissWindow.setAlpha(0f);
            dismissWindow.setVisibility(View.VISIBLE);
            dismissWindow.animate().alpha(1f).setDuration(120).start();
            dismissCircle.setBackground(electrode(false));
            dismissCircle.setColorFilter(Ui.CODE_TEXT);
            dismissCircle.setTranslationY(dp(70));
            dismissCircle.setScaleX(0.6f);
            dismissCircle.setScaleY(0.6f);
            dismissCircle.animate().translationY(0f).scaleX(1f).scaleY(1f)
                    .setInterpolator(new OvershootInterpolator(2.2f)).setDuration(320).start();
        } else {
            dismissWindow.animate().cancel();
            dismissCircle.animate().cancel();
            dismissWindow.setAlpha(0f);
            dismissWindow.setVisibility(View.GONE);
            dismissCircle.setTranslationY(0f);
        }
    }

    /** Centre of the target, measured on screen (accounts for nav bar and insets). */
    private int[] targetCenter() {
        int[] loc = new int[2];
        dismissCircle.getLocationOnScreen(loc);
        return new int[]{loc[0] + dismissCircle.getWidth() / 2, loc[1] + dismissCircle.getHeight() / 2};
    }

    /** Offset from the window position (lp.x, lp.y) to the chip's centre on screen, measured at drag start. */
    private int faceOffX, faceOffY, lpToScreenY;

    /** Is the bubble's top-left (x,y) near the target? Highlights it and returns true when close. */
    private boolean overDismiss(int x, int y) {
        if (dismissWindow == null || dismissWindow.getVisibility() != View.VISIBLE) return false;
        int[] t = targetCenter();
        boolean over = Math.hypot(x + faceOffX - t[0], y + lpToScreenY + faceOffY - t[1]) < dp(90);
        if (over != overTarget) {
            overTarget = over;
            dismissCircle.animate().scaleX(over ? 1.28f : 1f).scaleY(over ? 1.28f : 1f)
                    .setInterpolator(new OvershootInterpolator(3f)).setDuration(220).start();
            dismissCircle.setBackground(electrode(over));
            // dark ✕ on the white-hot core, light ✕ on the cold electrode
            dismissCircle.setColorFilter(over ? Theme.darken(Ui.ACCENT, 0.35f) : Ui.CODE_TEXT);
            showFace(over ? R.drawable.bubble_face_brace : R.drawable.bubble_face_fired);
            if (over) vibrate(15);
        }
        return over;
    }

    private void dismissToNotification() {
        vibrate(40);
        // spin and shrink into the target, which pops and drops away
        head.animate().scaleX(0f).scaleY(0f).rotation(180f).alpha(0f)
                .setInterpolator(new AccelerateInterpolator(1.6f)).setDuration(240)
                .withEndAction(() -> {
                    Prefs.setBubbleEnabled(svc, false);
                    HiddenNotice.show(svc);
                    applyCollapsedParams();
                    relayout();
                    applyEnabled();
                    head.setScaleX(1f);
                    head.setScaleY(1f);
                    head.setRotation(0f);
                    head.setAlpha(1f);
                    setWorking(running || controlling, true);
                }).start();
        dismissCircle.animate().scaleX(1.45f).scaleY(1.45f).setDuration(110)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> dismissCircle.animate().scaleX(0f).scaleY(0f).translationY(dp(60))
                        .setInterpolator(new AccelerateInterpolator(1.8f)).setDuration(200)
                        .withEndAction(() -> showDismissTarget(false)).start())
                .start();
    }

    // ------------------------------------------------------------ motion

    private ValueAnimator windowAnim;

    /** Animate the bubble window to (x, y) in window coordinates. */
    private void animateWindowTo(int x, int y, long ms, android.animation.TimeInterpolator in, Runnable end) {
        if (windowAnim != null) windowAnim.cancel();
        final int x0 = lp.x, y0 = lp.y;
        windowAnim = ValueAnimator.ofFloat(0f, 1f);
        windowAnim.setDuration(ms);
        windowAnim.setInterpolator(in);
        windowAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            lp.x = Math.round(x0 + (x - x0) * f);
            lp.y = Math.round(y0 + (y - y0) * f);
            relayout();
        });
        windowAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled && end != null) end.run();
            }
        });
        windowAnim.start();
    }

    /** Pop the bubble in from nothing (after unhiding, or closing the panel). */
    private void popIn() {
        head.animate().cancel();
        head.setRotation(0f);
        if (!Motion.enabled()) {
            head.setScaleX(1f);
            head.setScaleY(1f);
            head.setAlpha(1f);
            return;
        }
        head.setScaleX(0.4f);
        head.setScaleY(0.4f);
        head.setAlpha(0f);
        head.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setInterpolator(new OvershootInterpolator(1.8f)).setDuration(Motion.LONG - 40).start();
    }

    /*
     * Android 16 (targetSdk 36) delivers Back through OnBackInvokedDispatcher instead of
     * KEYCODE_BACK, so the panel registers a callback while it is open. Back first closes
     * the keyboard, then denies a pending approval, then collapses the panel.
     */
    private final Runnable outsideCollapse = () -> {
        if (expanded && pendingConfirm == null) collapse();
    };
    private OnBackInvokedCallback backCallback;
    private OnBackInvokedDispatcher backDispatcher;

    private void registerBack() {
        // Android 13+ only; older versions deliver KEYCODE_BACK to dispatchKeyEvent.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (!expanded || backDispatcher != null) return;
        if (backCallback == null) {
            backCallback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    ? new OnBackAnimationCallback() {
                        @Override
                        public void onBackStarted(BackEvent e) {
                            root.removeCallbacks(outsideCollapse); // it's a back gesture, not a tap outside
                        }

                        @Override
                        public void onBackInvoked() {
                            onBack();
                        }
                    }
                    : (OnBackInvokedCallback) this::onBack;
        }
        backDispatcher = root.findOnBackInvokedDispatcher();
        if (backDispatcher != null)
            // Overlay priority so the bubble sees every Back (including gesture start) and closes the keyboard itself.
            backDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCallback);
    }

    private void unregisterBack() {
        if (backDispatcher != null) backDispatcher.unregisterOnBackInvokedCallback(backCallback);
        backDispatcher = null;
    }

    private boolean keyboardVisible() {
        if (imeTop > 0) return true;
        WindowInsets insets = root.getRootWindowInsets();
        return insets != null && insets.isVisible(WindowInsets.Type.ime());
    }

    private void onBack() {
        root.removeCallbacks(outsideCollapse);
        if (keyboardVisible()) hideKeyboard();
        else if (pendingConfirm != null) answerConfirm(false);
        else collapse();
    }

    private void hideKeyboard() {
        InputMethodManager imm = svc.getSystemService(InputMethodManager.class);
        imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    /** Make the overlay invisible and untouchable while a gesture or screenshot runs. */
    void setPassThrough(boolean on) {
        if (passThrough == on) return;
        passThrough = on;
        root.setAlpha(on ? 0f : 1f);
        if (on) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        relayout();
    }

    // ------------------------------------------------------------ actions

    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            Motion.shake(input);
            return;
        }
        String err = hub.send(sessions.current, text);
        if (err != null) {
            toast(err);
            return;
        }
        input.setText("");
        collapse();
    }

    /** Talk in the bubble: tap, speak, it sends when you stop. Tap again to send right away. */
    private void toggleMic() {
        if (Voice.listening()) {
            Voice.finishListening();
            return;
        }
        if (!Voice.micAllowed(svc)) {
            // only an activity can ask for the microphone permission
            toast("Zezwól androidagent na mikrofon, a potem stuknij mikrofon ponownie.");
            svc.startActivity(new Intent(svc, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("askMic", true));
            collapse();
            return;
        }
        final String before = input.getText().toString().trim();
        String err = Voice.listen(svc, new Voice.Listener() {
            @Override
            public void onPartial(String text) {
                input.setText(before.isEmpty() ? text : before + " " + text);
                input.setSelection(input.getText().length());
            }

            @Override
            public void onFinal(String text) {
                input.setText(before.isEmpty() ? text : before + " " + text);
                micIdle();
                send();
            }

            @Override
            public void onEnd(String message) {
                micIdle();
                if (message != null) toast(message);
            }
        });
        if (err != null) {
            toast(err);
            return;
        }
        hideKeyboard();
        mic.setBackground(circleBackground(Ui.ACCENT, 0));
        mic.setImageTintList(ColorStateList.valueOf(Ui.ON_ACCENT));
        mic.setContentDescription("Zakończ dyktowanie");
        if (micPulse != null) micPulse.cancel();
        micPulse = Motion.pulse(mic, 0.55f, 650);
        input.setHint("Słucham… mów teraz");
    }

    private void micIdle() {
        if (micPulse != null) micPulse.cancel();
        micPulse = null;
        mic.setAlpha(1f);
        mic.setBackground(circleBackground(Ui.CARD, Ui.OUTLINE));
        mic.setImageTintList(ColorStateList.valueOf(Ui.MUTED));
        mic.setContentDescription("Dyktuj");
        input.setHint("Napisz do " + sessions.current.brainName() + "…");
    }

    private void stop() {
        if (!sessions.current.running) toast("W tej rozmowie nic teraz nie działa.");
        else hub.stop(sessions.current);
    }

    private void newChat(String backend) {
        hub.create(backend);
        showingList = false;
        renderCurrent();
    }

    private void deleteChat(Sessions.Session ses) {
        hub.delete(ses);
        renderCurrent();
    }

    private void openInApp(Sessions.Session ses) {
        try {
            svc.startActivity(new Intent(svc, ChatActivity.class).putExtra("id", ses.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("Nie udało się otworzyć rozmowy.");
            return;
        }
        collapse();
    }

    // ------------------------------------------------------------ sessions

    private void toggleSessionList() {
        showingList = !showingList;
        if (showingList) {
            hideKeyboard();
            renderSessionList();
            Motion.stagger(sessionList, 0, 28);
        }
        listScroll.setVisibility(showingList ? View.VISIBLE : View.GONE);
        scroll.setVisibility(showingList ? View.GONE : View.VISIBLE);
        if (!showingList) {
            Motion.fadeIn(scroll, 0);
            scrollToEnd();
        }
        updateViewing();
        updateHeader();
        renderStatusLine();
        renderStreaming();
    }

    private void renderSessionList() {
        sessionList.removeAllViews();
        LinearLayout add = Ui.row(svc);
        LinearLayout.LayoutParams ap = Ui.weight1();
        ap.rightMargin = dp(8);
        add.addView(newChatButton("Claude Code", "claude"), ap);
        add.addView(newChatButton("Codex", "codex"), Ui.weight1());
        sessionList.addView(add, Ui.full());

        TextView label = Ui.monoText(svc, ("Rozmowy · " + sessions.all.size()).toUpperCase(Locale.ROOT), 11, Ui.FAINT);
        label.setTypeface(Ui.mono(svc, 600));
        label.setLetterSpacing(0.1f);
        label.setAccessibilityHeading(true);
        label.setPadding(dp(2), dp(16), 0, dp(4));
        sessionList.addView(label, Ui.full());

        for (final Sessions.Session ses : sessions.all) {
            boolean current = ses == sessions.current;
            LinearLayout row = Ui.row(svc);
            row.setPadding(dp(12), dp(10), dp(2), dp(10));
            row.setBackground(rounded(current ? Ui.CARD2 : Ui.CARD, 12,
                    current ? Theme.withAlpha(Ui.ACCENT, 0x8C) : Ui.LINE, 1));
            row.setForeground(svc.getDrawable(Ui.ripple(svc, false)));
            LinearLayout texts = Ui.column(svc);
            LinearLayout line1 = Ui.row(svc);
            if (ses.unread) {
                View dot = Ui.dot(svc, Ui.ACCENT, 7);
                ((LinearLayout.LayoutParams) dot.getLayoutParams()).rightMargin = dp(7);
                line1.addView(dot);
            }
            TextView t = Ui.single(Ui.text(svc, label(ses), 14, Ui.TEXT));
            t.setTypeface(Ui.sans(svc, current ? 600 : 500));
            line1.addView(t, Ui.weight1());
            texts.addView(line1, Ui.full());
            LinearLayout line2 = Ui.row(svc);
            line2.setPadding(0, dp(5), 0, 0);
            line2.addView(backendTag(ses.backend));
            if (ses.running) {
                Spark s = new Spark(svc).color(Ui.ACCENT);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(12), dp(12));
                sp.leftMargin = dp(8);
                line2.addView(s, sp);
                String st = ses.status == null || ses.status.isEmpty() ? "pracuje" : polishStatus(ses.status);
                TextView w = Ui.single(Ui.monoText(svc, st, 11, Ui.ACCENT_TEXT));
                w.setPadding(dp(6), 0, 0, 0);
                line2.addView(w, Ui.weight1());
            } else {
                TextView w = Ui.single(Ui.monoText(svc, ago(ses.updated), 11, Ui.FAINT));
                w.setPadding(dp(8), 0, 0, 0);
                line2.addView(w, Ui.weight1());
            }
            texts.addView(line2, Ui.full());
            row.addView(texts, Ui.weight1());
            ImageButton del = panelIcon(R.drawable.ms_close, "Usuń rozmowę", Ui.FAINT, v -> deleteChat(ses));
            row.addView(del);
            row.setOnClickListener(v -> {
                hub.select(ses);
                showingList = false;
                renderCurrent();
            });
            Motion.press(row);
            LinearLayout.LayoutParams rp = Ui.full();
            rp.topMargin = dp(8);
            sessionList.addView(row, rp);
        }
    }

    private View newChatButton(String name, String backend) {
        LinearLayout b = Ui.row(svc);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(9), dp(12), dp(9));
        b.setBackground(rounded(Ui.CARD, 10, Ui.OUTLINE, 1));
        b.setForeground(svc.getDrawable(Ui.ripple(svc, false)));
        b.addView(Ui.icon(svc, R.drawable.ms_add, Ui.ACCENT_TEXT, 16));
        TextView t = Ui.single(Ui.monoText(svc, name, 12, Ui.TEXT));
        t.setTypeface(Ui.mono(svc, 600));
        t.setPadding(dp(6), 0, 0, 0);
        b.addView(t, Ui.wrap());
        b.setContentDescription("Nowa rozmowa: " + name);
        b.setOnClickListener(v -> newChat(backend));
        Motion.press(b);
        return b;
    }

    /** Show the current chat's history in the panel. */
    private void renderCurrent() {
        listScroll.setVisibility(showingList ? View.VISIBLE : View.GONE);
        scroll.setVisibility(showingList ? View.GONE : View.VISIBLE);
        messages.removeAllViews();
        emptyView = null;
        olderView = null;
        lastKind = null;
        clearStream();
        renderedFor = sessions.current;
        Sessions.Session ses = sessions.current;
        List<Sessions.Msg> all = ses.messages;
        int from = Math.max(0, all.size() - PANEL_MESSAGES);
        if (from > 0) addOlderLine();
        if (all.isEmpty()) {
            emptyView = Ui.emptyState(svc, "Nowa rozmowa z " + ses.brainName(),
                    "Poproś o coś na telefonie, np. „sprawdź baterię” albo „ustaw budzik na 7:00”.");
            messages.addView(emptyView, Ui.full());
        }
        for (int i = from; i < all.size(); i++) addMessage(all.get(i), false);
        if (!Voice.listening()) input.setHint("Napisz do " + ses.brainName() + "…");
        updateViewing();
        refreshStatus();
        renderStreaming();
        scrollToEnd();
    }

    private Sessions.Session renderedFor;

    /** Tell the hub which chat the open panel is showing, so its replies aren't marked unread. */
    private void updateViewing() {
        Sessions.Session now = expanded && !showingList ? sessions.current : null;
        if (now == viewingSes) return;
        if (viewingSes != null) hub.setViewing(viewingSes, false);
        viewingSes = now;
        if (now != null) hub.setViewing(now, true);
    }

    private Boolean chevronForList;

    private void updateHeader() {
        Sessions.Session ses = sessions.current;
        boolean run = ses.running;
        headerSpark.color(run ? Ui.ACCENT : Ui.MUTED);
        headerSpark.animated(run);
        boolean codex = "codex".equals(ses.backend);
        int tone = codex ? Ui.MUTED : Ui.ACCENT_TEXT;
        String tag = codex ? "codex" : "claude";
        if (!tag.contentEquals(backendBadge.getText())) backendBadge.setText(tag);
        backendBadge.setTextColor(tone);
        backendBadge.setBackground(rounded(Theme.withAlpha(tone, 0x22), 6, 0, 0));
        String sub = showingList ? "Wszystkie rozmowy" : label(ses);
        if (!sub.contentEquals(subtitle.getText())) subtitle.setText(sub);
        if (chevronForList == null || chevronForList != showingList) {
            chevronForList = showingList;
            Drawable chev = svc.getDrawable(showingList ? R.drawable.ms_expand_less : R.drawable.ms_expand_more);
            if (chev != null) chev.setBounds(0, 0, dp(16), dp(16));
            subtitle.setCompoundDrawablesRelative(null, null, chev, null);
        }
        subtitle.setContentDescription(sub + (showingList ? ", wróć do rozmowy" : ", pokaż rozmowy"));
        stopButton.setVisibility(run && !showingList ? View.VISIBLE : View.GONE);
        listButton.setImageTintList(ColorStateList.valueOf(showingList ? Ui.ACCENT_TEXT : Ui.MUTED));
        listButton.setSelected(showingList);
    }

    // ------------------------------------------------------- hub listener

    @Override
    public void onChatsChanged() {
        trackRuns();
        if (pendingConfirm != null) {
            if (hub.inForegroundChat()) collapse();
            else if (!expanded) expand();
        }
        if (renderedFor != sessions.current && !showingList) {
            renderCurrent();
            return;
        }
        refreshStatus();
        applyEnabled();
        renderStreaming();
        if (showingList) renderSessionList();
    }

    @Override
    public void onMessage(Sessions.Session ses, Sessions.Msg msg) {
        if (ses == sessions.current && msg.kind == Kind.BUDDY) clearStream();
        if (ses == sessions.current && renderedFor == ses) {
            View v = addMessage(msg, expanded && !showingList);
            if (v != null) scrollToEnd();
        }
        if (msg.kind == Kind.BUDDY && !(expanded && ses == sessions.current && !showingList)) vibrate(30);
    }

    @Override
    public void onMessageUpdated(Sessions.Session ses, Sessions.Msg msg) {
        if (ses != sessions.current || renderedFor != ses) return;
        for (int i = 0; i < messages.getChildCount(); i++) {
            View old = messages.getChildAt(i);
            if (old.getTag() != msg) continue;
            View v = messageView(msg);
            v.setTag(msg);
            ViewGroup.LayoutParams p = old.getLayoutParams();
            messages.removeViewAt(i);
            messages.addView(v, i, p);
            return;
        }
    }

    /** The approval being asked right now (shown inline in the chat screen too), or null. */
    String pendingConfirmText() {
        return pendingConfirm == null ? null : confirmText.getText().toString();
    }

    /** The reply being streamed, rendered as Markdown under a "●" while it arrives. */
    private void renderStreaming() {
        String text = sessions.current.streamText;
        if (text == null || text.isEmpty() || showingList || renderedFor != sessions.current) {
            clearStream();
            return;
        }
        if (streamRow == null) {
            streamRow = gutterRow(Ui.toolDot(svc, Ui.TEXT), dp(3));
            streamContent = MessageContent.render(svc, "");
            streamRow.addView(streamContent, Ui.weight1());
            LinearLayout.LayoutParams p = Ui.full();
            p.topMargin = messages.getChildCount() > 0 ? dp(14) : 0;
            live.addView(streamRow, 0, p);
            Motion.fadeIn(streamRow, 0);
        }
        MessageContent.update(streamContent, text, true);
        unselectable(streamContent);
        scrollToEnd();
    }

    private void clearStream() {
        if (streamRow != null && live != null) live.removeView(streamRow);
        streamRow = null;
        streamContent = null;
    }

    boolean approvalOverlayVisible() {
        return pendingConfirm != null && root.getVisibility() == View.VISIBLE;
    }

    /** Answer from the chat screen's inline approval card. */
    void answerFromApp(boolean approved) {
        if (pendingConfirm != null) answerConfirm(approved);
    }

    void askConfirm(String summary, ConfirmCallback cb) {
        if (pendingConfirm != null) pendingConfirm.onAnswer(false);
        pendingConfirm = cb;
        root.setVisibility(View.VISIBLE);
        confirmText.setText(summary);
        boolean wasShown = confirmCard.getVisibility() == View.VISIBLE;
        confirmCard.setVisibility(View.VISIBLE);
        if (!wasShown) Motion.enter(confirmCard, 60);
        styleCollapsed();
        refreshStatus();
        vibrate(60);
        if (hub.inForegroundChat()) collapse();
        else expand();
        applyEnabled();
        hub.changed();
    }

    void cancelConfirm() {
        ConfirmCallback cb = pendingConfirm;
        pendingConfirm = null;
        confirmCard.setVisibility(View.GONE);
        styleCollapsed();
        refreshStatus();
        hub.changed();
        if (cb != null) cb.onAnswer(false);
    }

    void cancelConfirm(ConfirmCallback expected) {
        if (pendingConfirm == expected) cancelConfirm();
    }

    private void answerConfirm(boolean approved) {
        ConfirmCallback cb = pendingConfirm;
        pendingConfirm = null;
        confirmCard.setVisibility(View.GONE);
        hub.note(sessions.current, Kind.STEP, approved
                ? "Zatwierdzono: " + clip(confirmText.getText().toString(), 80) : "Odrzucono");
        if (cb != null) cb.onAnswer(approved);
        styleCollapsed();
        refreshStatus();
        collapse();
        hub.changed();
    }

    // ------------------------------------------------------ claude events

    void onToolActivity(String tool) {
        hub.onToolActivity();
    }

    // --------------------------------------------------------------- views

    enum Kind { USER, BUDDY, STEP, ERROR }

    /** External status (the notify tool): shown on the current chat until its next step. */
    void setStatus(String text) {
        if (text != null && !text.isEmpty()) sessions.current.status = text;
        refreshStatus();
    }

    /** Remember when each chat started running, for the elapsed time in the status line. */
    private void trackRuns() {
        for (Sessions.Session s : sessions.all) {
            if (s.running && !runStarts.containsKey(s)) runStarts.put(s, SystemClock.elapsedRealtime());
            else if (!s.running) runStarts.remove(s);
        }
    }

    /** Pill, chip, badge, header and status line from the hub's state. */
    private void refreshStatus() {
        running = hub.runningCount() > 0;
        String text = pendingConfirm != null ? "Czeka na Twoją zgodę" : polishStatus(hub.pillText());
        if (controlling && pendingConfirm == null) {
            // a generic "Myślę…" adds nothing next to "in control"; a concrete step does
            boolean generic = text == null || text.isEmpty() || text.equals("Myślę…") || text.equals("Pracuję…");
            text = "Steruję telefonem" + (generic ? "" : " · " + text);
        }
        String shown = text == null ? "" : text;
        if (!shown.contentEquals(pillText.getText())) pillText.setText(shown);
        boolean showPill = text != null;
        if (showPill && pill.getVisibility() != View.VISIBLE) {
            pill.setVisibility(View.VISIBLE);
            if (!expanded && root.isShown()) Motion.fadeIn(pill, 0);
        } else if (!showPill) {
            pill.setVisibility(View.GONE);
        }
        stylePill();
        boolean unread = !expanded && hub.anyUnread();
        if (unread && badge.getVisibility() != View.VISIBLE) {
            badge.setVisibility(View.VISIBLE);
            Motion.pop(badge, 0);
        } else if (!unread) {
            badge.setVisibility(View.GONE);
        }
        setWorking(running || controlling);
        updateHeader();
        renderStatusLine();
        if (!expanded) {
            relayout();
        }
    }

    /** "✻ Kombinuję… (12s)" under the conversation while the current chat runs. */
    private void renderStatusLine() {
        if (statusLine == null) return;
        Sessions.Session ses = sessions.current;
        boolean show = ses.running && !showingList && renderedFor == ses && pendingConfirm == null;
        if (!show) {
            statusLine.setVisibility(View.GONE);
            return;
        }
        Long started = runStarts.get(ses);
        statusLine.start(started == null ? SystemClock.elapsedRealtime() : started);
        statusLine.setStatus(polishStatus(ses.status));
        if (statusLine.getVisibility() != View.VISIBLE) {
            statusLine.setVisibility(View.VISIBLE);
            Motion.fadeIn(statusLine, 0);
            scrollToEnd();
        }
    }

    private boolean faceDragging() {
        return dismissWindow != null && dismissWindow.getVisibility() == View.VISIBLE;
    }

    /** Spark and arcs while an agent works; the coral face when idle. */
    private void setWorking(boolean on) {
        setWorking(on, false);
    }

    private void setWorking(boolean on, boolean force) {
        working = on;
        ring.setSpinning(on);
        stopChip.setVisibility(on ? View.VISIBLE : View.GONE);
        // keep the fired-up / bracing face while the bubble is being dragged
        if (force || !faceDragging()) {
            if (on && pendingConfirm == null) showSpark();
            else showFace(pendingConfirm != null ? R.drawable.bubble_face_thinking : R.drawable.bubble_face);
        }
        updateBreathing();
    }

    private void showFace(int res) {
        face.setImageTintList(ColorStateList.valueOf(pendingConfirm != null && res == R.drawable.bubble_face_thinking
                ? Ui.WARN : Ui.ACCENT));
        if (res != faceRes) {
            face.setImageResource(res);
            faceRes = res;
        }
        if (faceShowsSpark || face.getVisibility() != View.VISIBLE) {
            faceShowsSpark = false;
            chipSpark.animated(false);
            chipSpark.setVisibility(View.GONE);
            face.setVisibility(View.VISIBLE);
            if (!faceDragging()) Motion.pop(face, 0);
        }
    }

    private void showSpark() {
        if (faceShowsSpark) return;
        faceShowsSpark = true;
        face.setVisibility(View.GONE);
        chipSpark.color(Ui.ACCENT);
        chipSpark.setVisibility(View.VISIBLE);
        chipSpark.animated(true);
        Motion.pop(chipSpark, 0);
    }

    /** A slow breath on the chip while working; never with animations off or while hidden. */
    private void updateBreathing() {
        boolean on = working && Motion.enabled() && !expanded && root != null && root.getVisibility() == View.VISIBLE;
        if (on && breathing == null) {
            breathing = ValueAnimator.ofFloat(1f, 0.94f);
            breathing.setDuration(900);
            breathing.setRepeatMode(ValueAnimator.REVERSE);
            breathing.setRepeatCount(ValueAnimator.INFINITE);
            breathing.setInterpolator(Motion.EASE_IN_OUT);
            breathing.addUpdateListener(a -> {
                float s = (float) a.getAnimatedValue();
                chip.setScaleX(s);
                chip.setScaleY(s);
            });
            breathing.start();
        } else if (!on) {
            stopBreathing();
        }
    }

    private void stopBreathing() {
        if (breathing == null) return;
        breathing.cancel();
        breathing = null;
        chip.setScaleX(1f);
        chip.setScaleY(1f);
    }

    /** A line at the top when older messages are not rendered here. */
    private void addOlderLine() {
        if (olderView != null) return;
        TextView t = Ui.single(Ui.monoText(svc, "… wcześniejsze wiadomości · otwórz w aplikacji", 11, Ui.FAINT));
        t.setPadding(0, dp(2), 0, dp(10));
        t.setBackgroundResource(Ui.ripple(svc, false));
        final Sessions.Session ses = sessions.current;
        t.setOnClickListener(v -> openInApp(ses));
        olderView = t;
        messages.addView(t, 0, Ui.full());
    }

    /** Append a message row; returns it (null for nothing to show). */
    private View addMessage(Sessions.Msg m, boolean animate) {
        if (m == null || m.text == null) return null;
        if (emptyView != null) {
            messages.removeView(emptyView);
            emptyView = null;
        }
        View v = messageView(m);
        v.setTag(m);
        LinearLayout.LayoutParams p = Ui.full();
        boolean first = lastKind == null;
        boolean stepRun = m.kind == Kind.STEP && lastKind == Kind.STEP;
        p.topMargin = first ? 0 : dp(stepRun ? 6 : 14);
        messages.addView(v, p);
        lastKind = m.kind;
        // keep the panel light: drop the oldest rows beyond the window
        int rows = 0;
        for (int i = 0; i < messages.getChildCount(); i++) if (messages.getChildAt(i).getTag() instanceof Sessions.Msg) rows++;
        if (rows > PANEL_MESSAGES) {
            for (int i = 0; i < messages.getChildCount() && rows > PANEL_MESSAGES; i++) {
                if (messages.getChildAt(i).getTag() instanceof Sessions.Msg) {
                    messages.removeViewAt(i--);
                    rows--;
                }
            }
            addOlderLine();
        }
        if (animate) Motion.enter(v, 0);
        return v;
    }

    private View messageView(Sessions.Msg m) {
        final String text = m.label();
        View v;
        switch (m.kind) {
            case USER: {
                // "› prompt" on a soft band, like a submitted Claude Code prompt
                LinearLayout band = Ui.row(svc);
                band.setGravity(Gravity.TOP);
                band.setPadding(dp(4), dp(8), dp(12), dp(9));
                band.setBackground(rounded(Ui.USER, 10, 0, 0));
                TextView g = Ui.monoText(svc, "›", 15, Ui.MUTED);
                g.setTypeface(Ui.mono(svc, 600));
                g.setGravity(Gravity.CENTER_HORIZONTAL);
                band.addView(g, new LinearLayout.LayoutParams(dp(20), LinearLayout.LayoutParams.WRAP_CONTENT));
                TextView t = Ui.text(svc, text, 14, Ui.TEXT);
                t.setLineSpacing(dp(2), 1.05f);
                band.addView(t, Ui.weight1());
                v = band;
                break;
            }
            case BUDDY: {
                LinearLayout row = gutterRow(Ui.toolDot(svc, Ui.TEXT), dp(3));
                LinearLayout md = MessageContent.render(svc, text);
                unselectable(md);
                row.addView(md, Ui.weight1());
                v = row;
                break;
            }
            case ERROR: {
                LinearLayout row = gutterRow(Ui.toolDot(svc, Ui.ERR), dp(1));
                TextView t = Ui.text(svc, text, 14, Ui.ERR);
                t.setLineSpacing(dp(2), 1.05f);
                row.addView(t, Ui.weight1());
                v = row;
                break;
            }
            default:
                v = stepView(m);
                break;
        }
        v.setOnLongClickListener(x -> {
            ClipboardManager cm = svc.getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("androidagent", text));
            toast("Skopiowano");
            return true;
        });
        return v;
    }

    /** A tool call: a state-coloured dot, "Tool(args)" in mono and a "└ result" line. */
    private View stepView(Sessions.Msg m) {
        JSONObject meta = m.meta;
        String tool = meta == null ? "" : meta.optString("tool", "");
        String state = meta == null ? "" : meta.optString("state", "");
        boolean err = "error".equals(state);
        boolean run = "running".equals(state);
        int color = err ? Ui.ERR : run ? Ui.ACCENT : "ok".equals(state) ? Ui.OK : Ui.FAINT;
        Ui.Dot dot = Ui.toolDot(svc, color);
        if (run) dot.setPulsing(true);
        LinearLayout row = gutterRow(dot, 0);
        LinearLayout col = Ui.column(svc);
        TextView head = Ui.monoText(svc, "", 12, Ui.MUTED);
        head.setMaxLines(2);
        head.setEllipsize(TextUtils.TruncateAt.END);
        head.setLineSpacing(dp(1), 1f);
        if (tool.isEmpty()) {
            head.setText(m.text);
        } else {
            SpannableStringBuilder sb = new SpannableStringBuilder(toolName(tool));
            sb.setSpan(new ForegroundColorSpan(Ui.TEXT), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.TypefaceSpan(Ui.mono(svc, 600)), 0, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            String args = meta.optString("args", "").replace('\n', ' ').trim();
            if (!args.isEmpty()) sb.append("(").append(args).append(")");
            head.setText(sb);
        }
        col.addView(head, Ui.full());
        String result = meta == null ? "" : meta.optString("result", "").trim();
        if (!result.isEmpty()) {
            int nl = result.indexOf('\n');
            String first = nl < 0 ? result : result.substring(0, nl);
            int more = Math.max(0, meta.optInt("lines", 1) - 1);
            String line = "└ " + first + (more > 0 ? "  +" + more + " " + plural(more, "wiersz", "wiersze", "wierszy") : "");
            TextView r = Ui.single(Ui.monoText(svc, line, 11, err ? Ui.ERR : Ui.FAINT));
            r.setPadding(0, dp(2), 0, 0);
            col.addView(r, Ui.full());
        } else if (run) {
            TextView r = Ui.single(Ui.monoText(svc, "└ w toku…", 11, Ui.FAINT));
            r.setPadding(0, dp(2), 0, 0);
            col.addView(r, Ui.full());
        }
        row.addView(col, Ui.weight1());
        return row;
    }

    /** A row with a 16dp marker in a fixed gutter and the content after it. */
    private LinearLayout gutterRow(View marker, int markerTop) {
        LinearLayout row = Ui.row(svc);
        row.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(16), dp(16));
        mp.topMargin = markerTop;
        mp.rightMargin = dp(8);
        marker.setLayoutParams(mp);
        row.addView(marker);
        return row;
    }

    /** Raw tool name → what Claude Code shows: "Bash", or "service_start" for an MCP tool. */
    private static String toolName(String raw) {
        if (raw.startsWith("mcp__")) {
            int i = raw.indexOf("__", 5);
            return i > 0 ? raw.substring(i + 2) : raw.substring(5);
        }
        return raw;
    }

    /**
     * Rendered Markdown is selectable in the app; in an overlay window a selection toolbar has no
     * activity to attach to, so the panel turns that off (long-press copies the whole message, and
     * code blocks keep their "Kopiuj" button). Links stay tappable.
     */
    private static void unselectable(View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            if (t.isTextSelectable() && !(t instanceof EditText)) {
                t.setTextIsSelectable(false);
                CharSequence s = t.getText();
                if (s instanceof Spanned && ((Spanned) s).getSpans(0, s.length(), ClickableSpan.class).length > 0) {
                    t.setMovementMethod(LinkMovementMethod.getInstance());
                }
            }
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) unselectable(g.getChildAt(i));
        }
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private ImageButton panelIcon(int icon, String description, int tint, View.OnClickListener l) {
        ImageButton b = Ui.iconButton(svc, icon, description, tint);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(40)));
        b.setOnClickListener(l);
        return b;
    }

    private ImageButton circleButton(int icon, String description, int fill, int stroke, int tint) {
        ImageButton b = new ImageButton(svc);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(tint));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setContentDescription(description);
        b.setBackground(circleBackground(fill, stroke));
        Motion.press(b);
        return b;
    }

    private Drawable circleBackground(int fill, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(Theme.withAlpha(Ui.TEXT, 0x30)),
                circle(fill, stroke, stroke == 0 ? 0 : 1), null);
    }

    /** "claude" / "codex" in a small mono tag. */
    private TextView backendTag(String backend) {
        boolean codex = "codex".equals(backend);
        TextView t = Ui.badge(svc, codex ? "codex" : "claude", codex ? Ui.MUTED : Ui.ACCENT_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        return t;
    }

    private static String label(Sessions.Session ses) {
        return ses.title == null || ses.title.isEmpty() ? "Nowa rozmowa" : ses.title;
    }

    /** The hub's generic English statuses, in Polish; concrete tool steps pass through. */
    private static String polishStatus(String s) {
        if (s == null) return null;
        s = s.replace("Thinking…", "Myślę…").replace("Working…", "Pracuję…");
        Matcher m = CHATS.matcher(s);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            s = n + " " + plural(n, "rozmowa", "rozmowy", "rozmów") + " · " + s.substring(m.end());
        }
        return s;
    }

    private static String plural(int n, String one, String few, String many) {
        if (n == 1) return one;
        int d = n % 10, h = n % 100;
        return d >= 2 && d <= 4 && (h < 12 || h > 14) ? few : many;
    }

    /** Short Polish relative time for the chat list. */
    private static String ago(long t) {
        long min = (System.currentTimeMillis() - t) / 60000;
        if (min < 1) return "teraz";
        if (min < 60) return min + " min temu";
        long h = min / 60;
        if (h < 24) return h + " godz. temu";
        long days = h / 24;
        if (days == 1) return "wczoraj";
        if (days < 7) return days + " dni temu";
        return new java.text.SimpleDateFormat("d.MM.yyyy", Locale.ROOT).format(new java.util.Date(t));
    }

    private GradientDrawable rounded(int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private GradientDrawable circle(int fill, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private Rect screen() {
        return wm.getCurrentWindowMetrics().getBounds();
    }

    /** Add an overlay window; the service may already be disconnecting (stale token), so never crash. */
    private void addWindow(View v, WindowManager.LayoutParams p) {
        try {
            wm.addView(v, p);
        } catch (RuntimeException e) {
            android.util.Log.w("Bubble", "overlay window not added", e);
        }
    }

    /** Apply {@link #lp} to the bubble's window (ignored when it is not attached). */
    private void relayout() {
        try {
            wm.updateViewLayout(root, lp);
        } catch (RuntimeException ignored) {
        }
    }

    private int dp(int v) {
        return Math.round(v * svc.getResources().getDisplayMetrics().density);
    }

    private static String clip(String s, int n) {
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }

    private void vibrate(long ms) {
        try {
            if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }

    private void toast(String s) {
        Toast.makeText(svc, s, Toast.LENGTH_SHORT).show();
    }

    /** Drags the collapsed bubble; a tap without movement expands it. */
    private final class DragListener implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(svc).getScaledTouchSlop();
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    if (Motion.enabled()) {
                        head.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).setInterpolator(Motion.EASE_OUT).start();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                        // Switch to left-anchored coordinates for free dragging.
                        int[] loc = new int[2];
                        chip.getLocationOnScreen(loc);
                        lp.gravity = Gravity.TOP | Gravity.START;
                        collapsed.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                        // where the chip's centre is relative to the window position, in screen pixels
                        int[] rootLoc = new int[2];
                        root.getLocationOnScreen(rootLoc);
                        faceOffX = loc[0] - rootLoc[0] + chip.getWidth() / 2;
                        faceOffY = loc[1] - rootLoc[1] + chip.getHeight() / 2;
                        startX = rootLoc[0];
                        lpToScreenY = rootLoc[1] - startY; // window y vs screen y (status bar, insets)
                        if (windowAnim != null) windowAnim.cancel();
                        showDismissTarget(true);
                        showFace(R.drawable.bubble_face_fired);
                        head.animate().scaleX(1.14f).scaleY(1.14f)
                                .setInterpolator(new OvershootInterpolator(3f)).setDuration(220).start();
                    }
                    if (dragging) {
                        int x = startX + (int) dx, y = startY + (int) dy;
                        showDismissTarget(true);
                        boolean wasOver = overTarget;
                        if (overDismiss(x, y)) {
                            // elastic snap onto the target, like chat heads
                            if (!wasOver) {
                                int[] t = targetCenter();
                                animateWindowTo(t[0] - faceOffX, t[1] - faceOffY - lpToScreenY, 200,
                                        new OvershootInterpolator(2.4f), null);
                            }
                            feedArcs();
                            return true;
                        }
                        if (wasOver && windowAnim != null) windowAnim.cancel();
                        lp.x = x;
                        lp.y = y;
                        relayout();
                        feedArcs();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        head.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SHORT).setInterpolator(Motion.EASE_OUT).start();
                        if (overTarget) {
                            endArcs(true);
                            dismissToNotification();
                            return true;
                        }
                        endArcs(false);
                        showDismissTarget(false);
                        setWorking(running || controlling, true);
                        // fling to the nearest edge with a little bounce
                        final boolean right = lp.x + faceOffX > screen().width() / 2;
                        int edgeX = right ? screen().width() - root.getWidth() - dp(6) : dp(6);
                        final int y = Math.max(dp(40), Math.min(lp.y, screen().height() - dp(160)));
                        animateWindowTo(edgeX, y, 340, new OvershootInterpolator(1.4f), () -> {
                            Prefs.saveBubblePos(svc, right ? 1 : 0, lp.y);
                            applyCollapsedParams();
                            relayout();
                        });
                    } else {
                        head.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SHORT).setInterpolator(Motion.EASE_OUT).start();
                        expand();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    head.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SHORT).start();
                    endArcs(false);
                    showDismissTarget(false);
                    setWorking(running || controlling, true);
                    return true;
                default:
                    return false;
            }
        }
    }
}
