package dev.mateuszsury.androidagent;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/** Shared look for the app screens: theme colours (set by {@link Theme}), type, and building blocks. */
final class Ui {
    // Palette: filled by Theme.load() from the appearance settings (dark/light, accent, surface).
    static int BG = 0xFF171513, CARD = 0xFF24211E, CARD2 = 0xFF3A3632, TEXT = 0xFFF4EFE6, MUTED = 0xFFA39E95,
            ACCENT = 0xFFD97757, OK = 0xFF5CC08A, USER = 0xFF4A3A2E, ERR = 0xFFE58A7A,
            ON_ACCENT = 0xFF1A1714,   // text on ACCENT
            ACCENT_TEXT = 0xFFD97757, // accent used as text on BG/CARD (darker in light mode)
            NAV = 0xFF1E1B19,         // bottom nav background
            CODE = 0xFF0F0E0D,        // terminal/code block background
            CODE_TEXT = 0xFFF4EFE6,
            LINE = 0x0FF4EFE6,        // dividers
            OUTLINE = 0x1FF4EFE6,     // chip/input borders
            ACCENT_SOFT = 0x29D97757,
            ERR_SOFT = 0x1FE58A7A;
    // Claude Code tokens (filled by Theme.load as well).
    static int FAINT = 0xFF6F6C65,     // tertiary text: hints, timestamps, connectors
            WARN = 0xFFE5B454, INFO = 0xFFB4A6F5,
            OK_SOFT = 0x1F6CC28A, WARN_SOFT = 0x1FE5B454, INFO_SOFT = 0x1FB4A6F5,
            CODE_MUTED = 0xFF8A877F, CODE_LINE = 0x1FF5F4EF,
            INLINE_CODE = 0xFFB9C3F7, INLINE_CODE_BG = 0x1AB9C3F7,
            SYN_KEYWORD = 0xFFE8916E, SYN_STRING = 0xFFA8CC8C, SYN_COMMENT = 0xFF77746C, SYN_NUMBER = 0xFFE5C07B,
            SYN_FUNC = 0xFF89B4E8, SYN_TYPE = 0xFFC8A6F2, SYN_KEY = 0xFF8CC7D9,
            DIFF_ADD = 0xFF9BD8A8, DIFF_ADD_BG = 0x332F9E5A, DIFF_DEL = 0xFFF2A6A0, DIFF_DEL_BG = 0x33C5483A;
    static boolean DARK = true;

    private static final Map<String, Typeface> FONTS = new HashMap<>();

    private Ui() {
    }

    // ------------------------------------------------------------------ basics

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Geist at a weight (400…700); falls back to the system font. */
    static Typeface sans(Context c, int weight) {
        return font(c, "fonts/Geist.ttf", weight, weight >= 600 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    /** Geist Mono at a weight; falls back to the system monospace. */
    static Typeface mono(Context c, int weight) {
        return font(c, "fonts/GeistMono.ttf", weight, Typeface.MONOSPACE);
    }

    private static Typeface font(Context c, String path, int weight, Typeface fallback) {
        String k = path + weight;
        Typeface t = FONTS.get(k);
        if (t == null) {
            try {
                t = new Typeface.Builder(c.getAssets(), path).setFontVariationSettings("'wght' " + weight).build();
            } catch (Exception e) {
                t = fallback;
            }
            if (t == null) t = fallback;
            FONTS.put(k, t);
        }
        return t;
    }

    static TextView text(Context c, String s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(sans(c, 400));
        return t;
    }

    static TextView title(Context c, String s, int sp) {
        TextView t = text(c, s, sp, TEXT);
        t.setTypeface(sans(c, 600));
        return t;
    }

    static TextView monoText(Context c, String s, int sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(mono(c, 400));
        return t;
    }

    static TextView single(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static GradientDrawable rounded(Context c, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable outlined(Context c, int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable d = rounded(c, fill, radiusDp);
        d.setStroke(dp(c, strokeDp), stroke);
        return d;
    }

    /** Per-corner radii in dp: top-left, top-right, bottom-right, bottom-left. */
    static GradientDrawable corners(Context c, int color, int tl, int tr, int br, int bl) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        float a = dp(c, tl), b = dp(c, tr), e = dp(c, br), f = dp(c, bl);
        d.setCornerRadii(new float[]{a, a, b, b, e, e, f, f});
        return d;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    static ImageView icon(Context c, int res, int color, int sizeDp) {
        ImageView i = new ImageView(c);
        i.setImageResource(res);
        i.setImageTintList(ColorStateList.valueOf(color));
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return i;
    }

    static View dot(Context c, int color, int sizeDp) {
        View v = new View(c);
        v.setBackground(oval(color));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp));
        p.gravity = Gravity.CENTER_VERTICAL;
        v.setLayoutParams(p);
        return v;
    }

    static int ripple(Context c, boolean borderless) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(borderless ? android.R.attr.selectableItemBackgroundBorderless
                : android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    /** A transparent icon button with a round ripple; 48dp touch target. */
    static ImageButton iconButton(Context c, int icon, String description) {
        return iconButton(c, icon, description, TEXT);
    }

    static ImageButton iconButton(Context c, int icon, String description, int tint) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(tint));
        b.setContentDescription(description);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackgroundResource(ripple(c, true));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        return b;
    }

    static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setTextColor(primary ? ON_ACCENT : TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(sans(c, 600));
        b.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        b.setMinHeight(dp(c, 40));
        b.setMinimumHeight(dp(c, 40));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setLetterSpacing(0.005f);
        b.setBackground(primary ? rounded(c, ACCENT, 10) : outlined(c, CARD2, 10, LINE, 1));
        Motion.press(b);
        return b;
    }

    static Button outlinedButton(Context c, String label, int color) {
        Button b = button(c, label, false);
        b.setTextColor(color);
        b.setBackground(outlined(c, 0x00000000, 10, Theme.withAlpha(color, 0x70), 1));
        return b;
    }

    static LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    // ------------------------------------------------------------- components

    /** Mono ALL CAPS label in the faint colour, like a terminal section rule; padding 20/20/20/6. */
    static TextView sectionLabel(Context c, String s) {
        TextView t = text(c, s.toUpperCase(java.util.Locale.ROOT), 11, FAINT);
        t.setTypeface(mono(c, 600));
        t.setLetterSpacing(0.1f);
        t.setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 6));
        t.setAccessibilityHeading(true);
        return t;
    }

    /** 40×40 tile with "CC" (Claude Code) or "CX" (Codex). */
    static TextView agentTile(Context c, String backend) {
        boolean codex = "codex".equals(backend);
        TextView t = monoText(c, codex ? "CX" : "CC", 12, codex ? TEXT : ACCENT_TEXT);
        t.setTypeface(mono(c, 600));
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(c, codex ? CARD2 : ACCENT_SOFT, 12));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
        return t;
    }

    /** Outlined mono chip with a trailing expand icon. */
    static LinearLayout chip(Context c, String s) {
        LinearLayout l = row(c);
        l.setPadding(dp(c, 8), dp(c, 3), dp(c, 4), dp(c, 3));
        l.setBackground(outlined(c, 0x00000000, 7, OUTLINE, 1));
        TextView t = single(monoText(c, s, 11, MUTED));
        // Shrink long/custom model labels before clipping the dropdown affordance.
        l.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView i = icon(c, R.drawable.ms_expand_more, MUTED, 16);
        l.addView(i);
        return l;
    }

    /** A plain flat row with a ripple (for list rows). */
    static LinearLayout listRow(Context c) {
        LinearLayout r = row(c);
        r.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12));
        r.setBackgroundResource(ripple(c, false));
        r.setGravity(Gravity.TOP);
        return r;
    }

    interface Toggle {
        void onToggle(boolean on);
    }

    /** Title + subtitle with a switch on the right. */
    static LinearLayout switchRow(Context c, String title, String sub, boolean on, Toggle l) {
        LinearLayout r = row(c);
        r.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        r.setBackgroundResource(ripple(c, false));
        LinearLayout texts = column(c);
        texts.addView(title(c, title, 15));
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(c, sub, 13, MUTED);
            s.setPadding(0, dp(c, 2), dp(c, 12), 0);
            texts.addView(s);
        }
        r.addView(texts, weight1());
        final FrameLayout track = new FrameLayout(c);
        final View knob = new View(c);
        final boolean[] state = {on};
        Runnable paint = () -> {
            track.setBackground(state[0] ? rounded(c, ACCENT, 14) : outlined(c, CARD2, 14, OUTLINE, 1));
            knob.setBackground(oval(state[0] ? ON_ACCENT : MUTED));
            FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(dp(c, 20), dp(c, 20),
                    Gravity.CENTER_VERTICAL | Gravity.START);
            kp.leftMargin = kp.rightMargin = dp(c, 4);
            knob.setLayoutParams(kp);
            float x = state[0] ? dp(c, 20) : 0;
            if (Motion.enabled() && knob.isAttachedToWindow()) {
                knob.animate().translationX(x).setDuration(Motion.SHORT + 40).setInterpolator(Motion.EASE_OUT).start();
            } else {
                knob.setTranslationX(x);
            }
        };
        track.addView(knob);
        track.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        paint.run();
        r.addView(track, new LinearLayout.LayoutParams(dp(c, 48), dp(c, 28)));
        r.setOnClickListener(v -> {
            state[0] = !state[0];
            paint.run();
            l.onToggle(state[0]);
        });
        r.setFocusable(true);
        r.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(state[0]);
                info.setText(title + (sub == null || sub.isEmpty() ? "" : ". " + sub));
            }
        });
        return r;
    }

    interface Pick {
        void onPick(String value);
    }

    /** Segmented control: options are {value, label}. */
    static LinearLayout segmented(Context c, String[][] options, String selected, boolean monoLabels, Pick l) {
        LinearLayout box = row(c);
        box.setPadding(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3));
        box.setBackground(outlined(c, BG, 12, LINE, 1));
        for (String[] o : options) {
            boolean on = o[0].equals(selected);
            TextView t = text(c, o[1], 14, on ? TEXT : MUTED);
            t.setSelected(on);
            t.setTypeface(monoLabels ? mono(c, on ? 600 : 400) : sans(c, on ? 600 : 500));
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
            if (on) t.setBackground(outlined(c, CARD2, 9, OUTLINE, 1));
            else t.setBackgroundResource(ripple(c, false));
            t.setOnClickListener(v -> {
                if (!on) l.onPick(o[0]);
            });
            box.addView(t, weight1());
        }
        return box;
    }

    /** Terminal-style block with an accent "$ " prompt. */
    static TextView codeBlock(Context c, String cmd) {
        TextView t = monoText(c, "", 12, CODE_TEXT);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("$ " + cmd);
        sb.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), 0, 2, 0);
        t.setText(sb);
        t.setLineSpacing(0, 1.4f);
        t.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        t.setBackground(rounded(c, CODE, 12));
        return t;
    }

    static ProgressBar thinBar(Context c) {
        ProgressBar p = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        p.setIndeterminate(true);
        p.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        p.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 2)));
        return p;
    }

    static ProgressBar spinner(Context c, int sizeDp) {
        ProgressBar p = new ProgressBar(c, null, android.R.attr.progressBarStyleSmall);
        p.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        p.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return p;
    }

    // --------------------------------------------------------------- bottom sheet

    /** A bottom sheet dialog: CARD, top radius 28, a handle, and the given content. */
    static Dialog bottomSheet(Activity a, View content) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = column(a);
        box.setPadding(dp(a, 20), dp(a, 10), dp(a, 20), dp(a, 28));
        GradientDrawable sheetBg = corners(a, CARD, 22, 22, 0, 0);
        sheetBg.setStroke(dp(a, 1), LINE);
        box.setBackground(sheetBg);
        View handle = new View(a);
        handle.setBackground(rounded(a, OUTLINE, 2));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(a, 32), dp(a, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(a, 14);
        box.addView(handle, hp);
        android.widget.ScrollView scroll = new android.widget.ScrollView(a);
        scroll.setFillViewport(false);
        scroll.addView(content, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setDimAmount(0.55f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setWindowAnimations(R.style.SheetAnimation);
        }
        return d;
    }

    /** A sheet asking to confirm; the action runs on the destructive button. */
    static void confirmSheet(Activity a, String title, String message, String action, boolean destructive, Runnable onYes) {
        LinearLayout c = column(a);
        c.addView(title(a, title, 18));
        if (message != null) {
            TextView m = text(a, message, 14, MUTED);
            m.setPadding(0, dp(a, 6), 0, dp(a, 18));
            c.addView(m);
        }
        LinearLayout btns = row(a);
        Button no = button(a, "Anuluj", false);
        Button yes = button(a, action, !destructive);
        if (destructive) {
            yes.setTextColor(ERR);
            yes.setBackground(rounded(a, ERR_SOFT, 12));
        }
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(a, 48), 1);
        btns.addView(no, bp);
        LinearLayout.LayoutParams yp = new LinearLayout.LayoutParams(0, dp(a, 48), 1);
        yp.leftMargin = dp(a, 10);
        btns.addView(yes, yp);
        c.addView(btns, full());
        Dialog d = bottomSheet(a, c);
        no.setOnClickListener(v -> d.dismiss());
        yes.setOnClickListener(v -> {
            d.dismiss();
            onYes.run();
        });
        d.show();
    }

    // ---------------------------------------------------------------- bottom nav

    static final int NAV_CHATS = 0, NAV_SESSIONS = 1, NAV_SETTINGS = 2;

    /** Czaty · Sesje · Ustawienia: mono labels, an accent pill under the active icon. */
    static LinearLayout bottomNav(Activity a, int active, int sessionsBadge) {
        LinearLayout bar = row(a);
        bar.setBackgroundColor(NAV);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(a, 8), dp(a, 2), dp(a, 8), dp(a, 2));
        Object[][] items = {
                {R.drawable.ms_chat_bubble, "Czaty", MainActivity.class},
                {R.drawable.ms_terminal, "Sesje", MonitorActivity.class},
                {R.drawable.ms_tune, "Ustawienia", SetupActivity.class},
        };
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            boolean on = i == active;
            LinearLayout it = column(a);
            it.setGravity(Gravity.CENTER_HORIZONTAL);
            it.setPadding(0, dp(a, 8), 0, dp(a, 8));
            FrameLayout pill = new FrameLayout(a);
            pill.setBackground(on ? rounded(a, ACCENT_SOFT, 14) : null);
            ImageView ic = icon(a, (Integer) items[i][0], on ? ACCENT_TEXT : MUTED, 21);
            pill.addView(ic, new FrameLayout.LayoutParams(dp(a, 21), dp(a, 21), Gravity.CENTER));
            if (i == NAV_SESSIONS && sessionsBadge > 0) {
                TextView b = monoText(a, String.valueOf(sessionsBadge), 10, ON_ACCENT);
                b.setTypeface(mono(a, 700));
                b.setGravity(Gravity.CENTER);
                b.setBackground(rounded(a, ACCENT, 8));
                b.setPadding(dp(a, 4), 0, dp(a, 4), 0);
                FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(a, 16), Gravity.TOP | Gravity.END);
                bp.topMargin = dp(a, 1);
                bp.rightMargin = dp(a, 8);
                pill.addView(b, bp);
            }
            it.addView(pill, new LinearLayout.LayoutParams(dp(a, 58), dp(a, 30)));
            if (on) Motion.pop(pill, 0);
            TextView label = monoText(a, (String) items[i][1], 11, on ? TEXT : MUTED);
            label.setTypeface(mono(a, on ? 600 : 400));
            label.setPadding(0, dp(a, 4), 0, 0);
            label.setGravity(Gravity.CENTER);
            it.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            it.setBackgroundResource(ripple(a, false));
            it.setContentDescription((String) items[i][1]);
            it.setSelected(on);
            it.setOnClickListener(v -> {
                if (idx == active) return;
                Intent in = new Intent(a, (Class<?>) items[idx][2]).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                // Chats is the root: go back to it instead of stacking another copy
                if (idx == NAV_CHATS) in.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                a.startActivity(in);
                a.overridePendingTransition(0, 0);
                if (!(a instanceof MainActivity)) a.finish();
            });
            bar.addView(it, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        LinearLayout wrap = column(a);
        View line = new View(a);
        line.setBackgroundColor(LINE);
        wrap.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 1)));
        wrap.addView(bar, full());
        return wrap;
    }

    // ------------------------------------------------------------ claude code kit

    /**
     * A screen's top bar: back arrow ("Wróć"), a title with an optional mono subtitle (a path-like
     * context line, e.g. "~/docker · 2 zadania") and trailing actions. A hairline separates it.
     */
    static LinearLayout topBar(Activity a, String title, String subtitle, View... actions) {
        LinearLayout wrap = column(a);
        LinearLayout bar = row(a);
        bar.setPadding(dp(a, 4), dp(a, 6), dp(a, 8), dp(a, 6));
        bar.setMinimumHeight(dp(a, 60));
        ImageButton back = iconButton(a, R.drawable.ms_arrow_back, "Wróć");
        back.setOnClickListener(v -> a.finish());
        bar.addView(back);
        LinearLayout texts = column(a);
        texts.setPadding(dp(a, 4), 0, dp(a, 8), 0);
        TextView t = single(title(a, title, 18));
        t.setAccessibilityHeading(true);
        texts.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = single(monoText(a, subtitle, 11, FAINT));
            s.setPadding(0, dp(a, 2), 0, 0);
            texts.addView(s);
        }
        bar.addView(texts, weight1());
        for (View action : actions) if (action != null) bar.addView(action);
        wrap.addView(bar, full());
        wrap.addView(divider(a), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 1)));
        return wrap;
    }

    /** The title TextView of a {@link #topBar} (to update it later). */
    static TextView topBarTitle(LinearLayout topBar) {
        LinearLayout bar = (LinearLayout) topBar.getChildAt(0);
        return (TextView) ((LinearLayout) bar.getChildAt(1)).getChildAt(0);
    }

    /** A text action for a top bar, e.g. "Odśwież". */
    static TextView barAction(Context c, String label) {
        TextView t = monoText(c, label, 13, ACCENT_TEXT);
        t.setTypeface(mono(c, 600));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        t.setBackgroundResource(ripple(c, true));
        t.setMinHeight(dp(c, 44));
        return t;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)));
        return v;
    }

    /** A surface card: CARD with a hairline border, radius 14, padding 16. */
    static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        l.setBackground(outlined(c, CARD, 14, LINE, 1));
        return l;
    }

    /** Margins for a card in a padded list: 16dp sides, 10dp below. */
    static LinearLayout.LayoutParams cardParams(Context c) {
        LinearLayout.LayoutParams p = full();
        p.leftMargin = p.rightMargin = dp(c, 16);
        p.bottomMargin = dp(c, 10);
        return p;
    }

    /**
     * Claude Code's signature box: a rounded border in the accent colour with a mono label set into
     * the top edge, e.g. "✻ androidagent". Children are added to the returned column.
     */
    static LinearLayout terminalBox(Context c, String label, int strokeColor) {
        LinearLayout l = column(c);
        TitledBorder border = new TitledBorder(c, label, strokeColor);
        l.setBackground(border);
        l.setPadding(dp(c, 16), dp(c, 18), dp(c, 16), dp(c, 14));
        return l;
    }

    /** Rounded stroke with a gap in the top edge for a label. */
    static final class TitledBorder extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint stroke = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint label = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final String text;
        private final float radius, inset, gapPad, labelX;

        TitledBorder(Context c, String text, int color) {
            this.text = text == null ? "" : text;
            stroke.setStyle(android.graphics.Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(c, 1));
            stroke.setColor(color);
            fill.setColor(CARD);
            label.setColor(color);
            label.setTypeface(mono(c, 600));
            label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, c.getResources().getDisplayMetrics()));
            radius = dp(c, 12);
            inset = label.getTextSize() / 2f + dp(c, 1);
            gapPad = dp(c, 6);
            labelX = dp(c, 16);
        }

        @Override public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float half = stroke.getStrokeWidth() / 2f;
            android.graphics.RectF r = new android.graphics.RectF(b.left + half, b.top + inset, b.right - half, b.bottom - half);
            canvas.drawRoundRect(r, radius, radius, fill);
            if (text.isEmpty()) {
                canvas.drawRoundRect(r, radius, radius, stroke);
                return;
            }
            float w = label.measureText(text);
            int save = canvas.save();
            canvas.clipRect(b.left + labelX - gapPad, b.top, b.left + labelX + w + gapPad, b.top + inset * 2,
                    android.graphics.Region.Op.DIFFERENCE);
            canvas.drawRoundRect(r, radius, radius, stroke);
            canvas.restoreToCount(save);
            android.graphics.Paint.FontMetrics fm = label.getFontMetrics();
            float baseline = b.top + inset - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, b.left + labelX, baseline, label);
        }

        @Override public void setAlpha(int alpha) {
            stroke.setAlpha(alpha);
            label.setAlpha(alpha);
            fill.setAlpha(alpha);
        }

        @Override public void setColorFilter(android.graphics.ColorFilter cf) {
            stroke.setColorFilter(cf);
        }

        @Override public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** Small mono status label, e.g. "dev", "działa", "błąd". */
    static TextView badge(Context c, String s, int color) {
        TextView t = single(monoText(c, s, 11, color));
        t.setTypeface(mono(c, 600));
        t.setPadding(dp(c, 7), dp(c, 2), dp(c, 7), dp(c, 2));
        t.setBackground(rounded(c, Theme.withAlpha(color, 0x22), 6));
        return t;
    }

    /** A filled circle drawn on a Canvas: the "⏺" marker of Claude Code (the glyph is not in our fonts). */
    static final class Dot extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private android.animation.Animator pulse;

        Dot(Context c, int color) {
            super(c);
            paint.setColor(color);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setColor(int color) {
            if (paint.getColor() == color) return;
            paint.setColor(color);
            invalidate();
        }

        /** Breathe while a tool runs; stop to show a final state. */
        void setPulsing(boolean on) {
            if (on && pulse == null) {
                pulse = Motion.pulse(this, 0.25f, 700);
            } else if (!on && pulse != null) {
                pulse.cancel();
                pulse = null;
                setAlpha(1f);
            }
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r * 0.62f, paint);
        }
    }

    /** A {@link Dot} sized for a text line: 16dp square box with a ~10dp circle. */
    static Dot toolDot(Context c, int color) {
        Dot d = new Dot(c, color);
        d.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 16), dp(c, 16)));
        return d;
    }

    /** Status pill: a coloured dot and a mono label on an outlined capsule. */
    static LinearLayout statusPill(Context c, String s, int color) {
        LinearLayout l = row(c);
        l.setPadding(dp(c, 10), dp(c, 5), dp(c, 12), dp(c, 5));
        l.setBackground(outlined(c, Theme.withAlpha(color, 0x14), 20, Theme.withAlpha(color, 0x40), 1));
        View d = dot(c, color, 7);
        l.addView(d);
        TextView t = single(monoText(c, s, 12, TEXT));
        t.setPadding(dp(c, 8), 0, 0, 0);
        l.addView(t);
        return l;
    }

    /** A themed text field: CARD fill, hairline border that turns accent on focus, accent cursor. */
    static android.widget.EditText input(Context c, String hint, boolean monoFont) {
        android.widget.EditText e = new android.widget.EditText(c);
        e.setHint(hint);
        e.setTextColor(TEXT);
        e.setHintTextColor(FAINT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, monoFont ? 14 : 15);
        e.setTypeface(monoFont ? mono(c, 400) : sans(c, 400));
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        e.setBackground(focusable(c, 12));
        e.setHighlightColor(Theme.withAlpha(ACCENT, 0x55));
        GradientDrawable cursor = new GradientDrawable();
        cursor.setColor(ACCENT);
        cursor.setSize(dp(c, 2), dp(c, 18));
        e.setTextCursorDrawable(cursor);
        return e;
    }

    /** CARD background with a LINE border, ACCENT when focused. */
    static android.graphics.drawable.StateListDrawable focusable(Context c, int radiusDp) {
        android.graphics.drawable.StateListDrawable d = new android.graphics.drawable.StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, outlined(c, CARD, radiusDp, Theme.withAlpha(ACCENT, 0xB0), 1));
        d.addState(new int[]{}, outlined(c, CARD, radiusDp, OUTLINE, 1));
        d.setEnterFadeDuration(Motion.SHORT);
        d.setExitFadeDuration(Motion.SHORT);
        return d;
    }

    /** Centered empty state: a still spark, a title and a muted line. */
    static LinearLayout emptyState(Context c, String title, String sub) {
        LinearLayout l = column(c);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(dp(c, 24), dp(c, 36), dp(c, 24), dp(c, 36));
        Spark s = new Spark(c).animated(false).color(Theme.withAlpha(ACCENT, 0xB0));
        l.addView(s, new LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)));
        TextView t = title(c, title, 16);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(c, 14), 0, 0);
        l.addView(t, full());
        if (sub != null && !sub.isEmpty()) {
            TextView m = text(c, sub, 13, MUTED);
            m.setGravity(Gravity.CENTER);
            m.setPadding(0, dp(c, 6), 0, 0);
            m.setLineSpacing(dp(c, 2), 1f);
            l.addView(m, full());
        }
        return l;
    }

    /** A label/value line for info cards: mono key in MUTED, value in TEXT, right-aligned. */
    static LinearLayout keyValue(Context c, String key, String value) {
        LinearLayout r = row(c);
        r.setPadding(0, dp(c, 6), 0, dp(c, 6));
        TextView k = single(monoText(c, key, 12, MUTED));
        r.addView(k, weight1());
        TextView v = text(c, value, 14, TEXT);
        v.setGravity(Gravity.END);
        v.setTextIsSelectable(true);
        r.addView(v, wrap());
        return r;
    }

    /** A dark terminal output panel (CODE background, mono CODE_TEXT). */
    static TextView terminalText(Context c, String s) {
        TextView t = monoText(c, s, 12, CODE_TEXT);
        t.setLineSpacing(dp(c, 2), 1.1f);
        t.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        t.setBackground(outlined(c, CODE, 12, CODE_LINE, 1));
        t.setTextIsSelectable(true);
        return t;
    }
}
