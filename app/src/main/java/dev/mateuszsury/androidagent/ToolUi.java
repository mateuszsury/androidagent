package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * Building blocks shared by the tool screens (device, environment, terminal, Docker, USB): grouped
 * row cards, meters, styled selectors, and bottom sheets for options, forms and logs.
 */
final class ToolUi {
    private ToolUi() {
    }

    // ------------------------------------------------------------------ layout

    /** The scrolling body of a tool screen: a column under the top bar with room at the bottom. */
    static LinearLayout body(Context c) {
        LinearLayout b = column(c);
        b.setPadding(0, dp(c, 6), 0, dp(c, 32));
        return b;
    }

    /** Section label aligned with the kit (mono caps, faint). */
    static TextView section(Context c, String s) {
        return sectionLabel(c, s);
    }

    /** A small mono caption whose text stays exact (caps are a display transform only). */
    static TextView caption(Context c, String s) {
        TextView t = single(monoText(c, s, 11, FAINT));
        t.setTypeface(mono(c, 600));
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        return t;
    }

    /** Muted paragraph with side padding matching section labels. */
    static TextView note(Context c, String s, int sp) {
        TextView t = text(c, s, sp, MUTED);
        t.setLineSpacing(dp(c, 3), 1f);
        t.setPadding(dp(c, 20), dp(c, 4), dp(c, 20), dp(c, 4));
        return t;
    }

    /** A rounded card holding rows separated by hairlines; children are clipped to the corners. */
    static LinearLayout group(Context c) {
        LinearLayout g = column(c);
        g.setBackground(outlined(c, CARD, 14, LINE, 1));
        g.setClipToOutline(true);
        return g;
    }

    /** Add a row to a {@link #group}, with an inset hairline above every row but the first. */
    static void add(LinearLayout group, View row) {
        Context c = group.getContext();
        if (group.getChildCount() > 0) {
            View line = new View(c);
            line.setBackgroundColor(LINE);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1));
            p.leftMargin = dp(c, 16);
            group.addView(line, p);
        }
        group.addView(row, full());
    }

    /** A tappable list row: optional icon tile, a title, a mono meta line and a trailing view. */
    static final class Row {
        final LinearLayout view;
        final TextView title, meta;
        final ImageView icon;
        final LinearLayout trailing;

        Row(Context c, int iconRes, String titleText, String metaText, boolean chevron) {
            view = row(c);
            view.setPadding(dp(c, 14), dp(c, 12), dp(c, 12), dp(c, 12));
            view.setMinimumHeight(dp(c, 56));
            view.setBackgroundResource(ripple(c, false));
            if (iconRes != 0) {
                FrameLayout tile = new FrameLayout(c);
                tile.setBackground(rounded(c, Theme.withAlpha(TEXT, DARK ? 0x0D : 0x0A), 10));
                icon = new ImageView(c);
                icon.setImageResource(iconRes);
                icon.setImageTintList(ColorStateList.valueOf(MUTED));
                tile.addView(icon, new FrameLayout.LayoutParams(dp(c, 18), dp(c, 18), Gravity.CENTER));
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(c, 34), dp(c, 34));
                tp.rightMargin = dp(c, 12);
                view.addView(tile, tp);
            } else {
                icon = null;
            }
            LinearLayout texts = column(c);
            title = single(text(c, titleText, 15, TEXT));
            title.setTypeface(sans(c, 500));
            texts.addView(title);
            meta = monoText(c, metaText == null ? "" : metaText, 12, FAINT);
            meta.setMaxLines(2);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            meta.setPadding(0, dp(c, 2), 0, 0);
            meta.setVisibility(metaText == null || metaText.isEmpty() ? View.GONE : View.VISIBLE);
            texts.addView(meta);
            view.addView(texts, weight1());
            trailing = row(c);
            view.addView(trailing, wrap());
            if (chevron) {
                ImageView ch = Ui.icon(c, R.drawable.ms_chevron_right, FAINT, 20);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20));
                cp.leftMargin = dp(c, 6);
                trailing.addView(ch, cp);
            }
            Motion.press(view);
        }

        Row accent() {
            title.setTextColor(ACCENT_TEXT);
            if (icon != null) {
                icon.setImageTintList(ColorStateList.valueOf(ACCENT_TEXT));
                ((View) icon.getParent()).setBackground(rounded(view.getContext(), ACCENT_SOFT, 10));
            }
            return this;
        }

        Row danger() {
            title.setTextColor(ERR);
            if (icon != null) {
                icon.setImageTintList(ColorStateList.valueOf(ERR));
                ((View) icon.getParent()).setBackground(rounded(view.getContext(), ERR_SOFT, 10));
            }
            return this;
        }

        Row onClick(View.OnClickListener l) {
            view.setOnClickListener(l);
            return this;
        }

        /** Put a view (badge, dot) before the chevron. */
        Row trail(View v) {
            LinearLayout.LayoutParams p = wrap();
            p.gravity = Gravity.CENTER_VERTICAL;
            p.leftMargin = dp(view.getContext(), 8);
            trailing.addView(v, 0, p);
            return this;
        }

        void setMeta(String s) {
            String value = s == null ? "" : s;
            if (!value.contentEquals(meta.getText())) meta.setText(value);
            int vis = value.isEmpty() ? View.GONE : View.VISIBLE;
            if (meta.getVisibility() != vis) meta.setVisibility(vis);
        }

        void setEnabled(boolean on) {
            ToolUi.setEnabled(view, on);
        }
    }

    /** Dim and block a control without changing its look otherwise. */
    static void setEnabled(View v, boolean on) {
        if (v.isEnabled() != on) v.setEnabled(on);
        float a = on ? 1f : 0.42f;
        if (v.getAlpha() != a) v.setAlpha(a);
    }

    static void setText(TextView t, CharSequence s) {
        if (!s.toString().contentEquals(t.getText())) t.setText(s);
    }

    static void setVisible(View v, boolean on) {
        int vis = on ? View.VISIBLE : View.GONE;
        if (v.getVisibility() != vis) v.setVisibility(vis);
    }

    /** A row with a small status dot, a label and a mono value on the right. */
    static final class Status {
        final LinearLayout view;
        final Dot dot;
        final TextView label, value;

        Status(Context c, String name) {
            view = row(c);
            view.setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13));
            dot = toolDot(c, FAINT);
            view.addView(dot);
            label = single(text(c, name, 14, TEXT));
            label.setPadding(dp(c, 8), 0, dp(c, 8), 0);
            view.addView(label, weight1());
            value = single(monoText(c, "", 13, MUTED));
            view.addView(value, wrap());
        }

        void set(String v, int color) {
            setText(value, v);
            dot.setColor(color);
        }
    }

    // ------------------------------------------------------------------ meter

    /** A rounded horizontal meter; the fill eases to new values. */
    static final class Meter extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float target = -1f, shown;
        private ValueAnimator anim;

        Meter(Context c, int color) {
            super(c);
            track.setColor(Theme.withAlpha(TEXT, DARK ? 0x17 : 0x14));
            fill.setColor(color);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(float fraction, int color) {
            if (fill.getColor() != color) {
                fill.setColor(color);
                invalidate();
            }
            float f = Math.max(0f, Math.min(1f, fraction));
            if (f == target) return;
            boolean first = target < 0;
            target = f;
            if (anim != null) anim.cancel();
            if (!Motion.enabled()) {
                shown = f;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(first ? 0f : shown, f);
            anim.setDuration(first ? Motion.LONG + 200 : Motion.LONG);
            anim.setStartDelay(first ? 120 : 0);
            anim.setInterpolator(Motion.EASE_OUT);
            anim.addUpdateListener(a -> {
                shown = (Float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override protected void onDetachedFromWindow() {
            if (anim != null) anim.cancel();
            shown = Math.max(0, target);
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas canvas) {
            float h = getHeight(), w = getWidth(), r = h / 2f;
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, track);
            if (shown <= 0f) return;
            rect.set(0, 0, Math.max(h, w * shown), h);
            canvas.drawRoundRect(rect, r, r, fill);
        }
    }

    static LinearLayout.LayoutParams meterParams(Context c, int heightDp, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp));
        p.topMargin = dp(c, topDp);
        return p;
    }

    // ------------------------------------------------------------------ inputs

    /** A themed check box: accent box, TEXT label. */
    static CheckBox checkBox(Context c, String label) {
        CheckBox b = new CheckBox(c);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(sans(c, 400));
        b.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{ACCENT, MUTED}));
        b.setPadding(dp(c, 6), 0, 0, 0);
        b.setMinHeight(dp(c, 44));
        return b;
    }

    /**
     * Restyle a Spinner: a focusable-like rounded field with a chevron, a themed dropdown, and an
     * adapter whose selected view is a plain TextView in {@link Ui#TEXT}.
     */
    static void styleSpinner(Spinner s, String[] items) {
        Context c = s.getContext();
        GradientDrawable field = outlined(c, CARD, 12, OUTLINE, 1);
        android.graphics.drawable.Drawable chevron = c.getDrawable(R.drawable.ms_expand_more).mutate();
        chevron.setTint(MUTED);
        LayerDrawable bg = new LayerDrawable(new android.graphics.drawable.Drawable[]{field, chevron});
        bg.setLayerGravity(1, Gravity.END | Gravity.CENTER_VERTICAL);
        bg.setLayerSize(1, dp(c, 20), dp(c, 20));
        bg.setLayerInsetEnd(1, dp(c, 12));
        s.setBackground(bg);
        s.setPopupBackgroundDrawable(new InsetDrawable(outlined(c, CARD, 12, OUTLINE, 1), 0));
        s.setDropDownVerticalOffset(dp(c, 6));
        s.setAdapter(new ArrayAdapter<String>(c, android.R.layout.simple_spinner_dropdown_item, items) {
            private TextView label(int position, boolean dropdown) {
                TextView v = single(text(c, getItem(position), 14, TEXT));
                boolean selected = dropdown && position == s.getSelectedItemPosition();
                if (dropdown) {
                    v.setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13));
                    v.setTextColor(selected ? ACCENT_TEXT : TEXT);
                    v.setTypeface(sans(c, selected ? 600 : 400));
                    v.setBackgroundResource(ripple(c, false));
                } else {
                    v.setPadding(dp(c, 14), dp(c, 12), dp(c, 40), dp(c, 12));
                }
                return v;
            }

            @Override public View getView(int position, View recycled, ViewGroup parent) {
                return label(position, false);
            }

            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                return label(position, true);
            }
        });
    }

    /** Wrap a {@link Ui#input} with a leading mono prompt glyph; the frame shows the field's focus. */
    static LinearLayout prompt(Context c, String glyph, android.widget.EditText field) {
        LinearLayout box = row(c);
        box.setBackground(focusable(c, 12));
        box.setAddStatesFromChildren(true);
        box.setPadding(dp(c, 12), 0, 0, 0);
        TextView g = monoText(c, glyph, 15, ACCENT_TEXT);
        g.setTypeface(mono(c, 600));
        g.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(g, wrap());
        field.setBackground(null);
        field.setPadding(dp(c, 8), dp(c, 12), dp(c, 12), dp(c, 12));
        box.addView(field, weight1());
        return box;
    }

    // ------------------------------------------------------------------ panels

    /** A dark terminal panel of a fixed height with its own scroll; returns the text view. */
    static TextView logPanel(LinearLayout parent, String text, int heightDp, boolean toBottom) {
        Context c = parent.getContext();
        FrameLayout frame = new FrameLayout(c);
        frame.setBackground(outlined(c, CODE, 12, CODE_LINE, 1));
        frame.setClipToOutline(true);
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(true);
        // Inside a sheet's own ScrollView: scroll this panel first, then hand over to the sheet.
        scroll.setNestedScrollingEnabled(true);
        TextView t = monoText(c, text, 12, CODE_TEXT);
        t.setLineSpacing(dp(c, 2), 1.05f);
        t.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        t.setTextIsSelectable(true);
        t.setLinkTextColor(ACCENT);
        scroll.addView(t, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        parent.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        if (toBottom) scroll.post(() -> scroll.scrollTo(0, t.getHeight()));
        return t;
    }

    static int screenDp(Context c, float fraction) {
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        return Math.round(m.heightPixels / m.density * fraction);
    }

    // ------------------------------------------------------------------ sheets

    /** A bottom sheet with a title, an optional muted line and a body; not shown yet. */
    static Dialog sheet(Activity a, String title, String sub, View body) {
        LinearLayout c = column(a);
        TextView t = title(a, title, 18);
        t.setAccessibilityHeading(true);
        c.addView(t, full());
        if (sub != null && !sub.isEmpty()) {
            TextView m = text(a, sub, 13, MUTED);
            m.setLineSpacing(dp(a, 2), 1f);
            m.setPadding(0, dp(a, 4), 0, 0);
            c.addView(m, full());
        }
        if (body != null) {
            LinearLayout.LayoutParams p = full();
            p.topMargin = dp(a, 14);
            c.addView(body, p);
        }
        return bottomSheet(a, c);
    }

    interface Choice {
        void pick(int index);
    }

    /** A sheet listing options as rows; destructive ones (by index) are red. */
    static Dialog options(Activity a, String title, String sub, String[] labels, int[] danger, Choice choice) {
        LinearLayout list = group(a);
        final Dialog[] d = new Dialog[1];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Row r = new Row(a, 0, labels[i], null, false);
            r.view.setMinimumHeight(dp(a, 50));
            for (int x : danger == null ? new int[0] : danger) if (x == i) r.title.setTextColor(ERR);
            r.onClick(v -> {
                d[0].dismiss();
                choice.pick(index);
            });
            add(list, r.view);
        }
        d[0] = sheet(a, title, sub, list);
        d[0].show();
        return d[0];
    }

    /** A sheet with a message (selectable; mono in a terminal panel when {@code code}) and "Zamknij". */
    static Dialog message(Activity a, String title, String body, boolean code, boolean error) {
        if (a.isFinishing() || a.isDestroyed()) return null;
        LinearLayout content = column(a);
        if (code) {
            int lines = body == null ? 1 : body.split("\n", -1).length;
            int height = Math.min(screenDp(a, 0.52f), Math.max(120, lines * 18 + 40));
            logPanel(content, body, height, false);
        } else {
            TextView m = text(a, body, 14, error ? ERR : TEXT);
            m.setTextIsSelectable(true);
            m.setLineSpacing(dp(a, 3), 1f);
            content.addView(m, full());
        }
        Button close = button(a, "Zamknij", false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 46));
        p.topMargin = dp(a, 16);
        content.addView(close, p);
        Dialog d = sheet(a, title, null, content);
        close.setOnClickListener(v -> d.dismiss());
        d.show();
        return d;
    }

    /** Two equal buttons side by side. */
    static LinearLayout buttons(Context c, Button first, Button second) {
        LinearLayout r = row(c);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(c, 46), 1);
        r.addView(first, a);
        if (second != null) {
            LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, dp(c, 46), 1);
            b.leftMargin = dp(c, 10);
            r.addView(second, b);
        }
        return r;
    }

    /** Style a native AlertDialog that must stay native (tests drive it): CARD surface, hairline. */
    static AlertDialog theme(AlertDialog d) {
        if (d.getWindow() != null) {
            Context c = d.getContext();
            d.getWindow().setBackgroundDrawable(new InsetDrawable(outlined(c, CARD, 18, LINE, 1),
                    dp(c, 16), dp(c, 24), dp(c, 16), dp(c, 24)));
        }
        return d;
    }

    /** A full-width button with the kit's height. */
    static LinearLayout.LayoutParams buttonParams(Context c, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 46));
        p.topMargin = dp(c, topDp);
        return p;
    }

    /** A quiet "danger" button: ERR text on a soft red fill. */
    static Button dangerButton(Context c, String label) {
        Button b = button(c, label, false);
        b.setTextColor(ERR);
        b.setBackground(outlined(c, ERR_SOFT, 10, Theme.withAlpha(ERR, 0x40), 1));
        return b;
    }

    /** Human size: 812 B, 14.2 KB, 3.1 MB, 1.2 GB. */
    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(java.util.Locale.forLanguageTag("pl"), v >= 100 ? "%.0f %s" : "%.1f %s", v, units[u]);
    }
}
