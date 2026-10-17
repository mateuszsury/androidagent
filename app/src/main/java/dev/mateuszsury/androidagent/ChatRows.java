package dev.mateuszsury.androidagent;

import android.animation.ValueAnimator;
import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static dev.mateuszsury.androidagent.Ui.*;

/** Transcript rows of the chat screen, laid out like Claude Code's terminal transcript. */
final class ChatRows {
    private ChatRows() {
    }

    /** Width of the marker gutter (dot or prompt glyph) and the gap before the content, in dp. */
    static final int GUTTER = 16, GAP = 10;
    /** Result lines shown under a tool call before "… +N linii" (a task plan shows more). */
    static final int PREVIEW = 3, TODO_PREVIEW = 8;
    /** The result ChatHub gives calls that were cut short by Stop. */
    static final String INTERRUPTED = "Przerwano";

    /**
     * A marker gutter and a content column. After layout the marker moves onto the content's first
     * line of text: a glyph shares its baseline, a dot is centred on the line.
     */
    static final class Gutter extends LinearLayout {
        final View marker;
        final LinearLayout body;
        /** False keeps the marker at the top (content that starts with a picture). */
        boolean align = true;

        Gutter(Context c, View marker) {
            super(c);
            setOrientation(HORIZONTAL);
            setBaselineAligned(false);
            this.marker = marker;
            LayoutParams mp = new LayoutParams(dp(c, GUTTER),
                    marker instanceof TextView ? LayoutParams.WRAP_CONTENT : dp(c, GUTTER));
            mp.rightMargin = dp(c, GAP);
            addView(marker, mp);
            body = column(c);
            addView(body, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            float shift = 0;
            TextView first = align ? firstText(body) : null;
            int y = first == null ? -1 : first.getBaseline();
            if (y >= 0) {
                View v = first;
                while (v != this) {
                    y += v.getTop();
                    ViewParent p = v.getParent();
                    if (!(p instanceof View)) return;
                    v = (View) p;
                }
                if (marker instanceof TextView && marker.getBaseline() >= 0) {
                    shift = y - (marker.getTop() + marker.getBaseline());
                } else {
                    shift = y - first.getTextSize() * 0.33f - (marker.getTop() + marker.getHeight() / 2f);
                }
            }
            if (marker.getTranslationY() != shift) marker.setTranslationY(shift);
        }
    }

    /** The first visible, non-empty TextView inside {@code v}, depth first. */
    static TextView firstText(View v) {
        if (v.getVisibility() == View.GONE) return null;
        if (v instanceof TextView) return ((TextView) v).length() > 0 ? (TextView) v : null;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView t = firstText(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    /** "mcp__phone__device_status" → "device_status"; other names unchanged. */
    static String displayName(String tool) {
        if (tool == null) return "";
        if (tool.startsWith("mcp__")) {
            int i = tool.indexOf("__", 5);
            if (i > 0 && i + 2 < tool.length()) return tool.substring(i + 2);
        }
        return tool;
    }

    /** "1 linia", "3 linie", "12 linii", "22 linie". */
    static String lines(int n) {
        int ones = n % 10, tens = n % 100;
        String word = n == 1 ? "linia" : ones >= 2 && ones <= 4 && (tens < 12 || tens > 14) ? "linie" : "linii";
        return n + " " + word;
    }

    /** "name(args)": the name in TEXT at weight 600, the arguments muted. */
    static CharSequence callLabel(Context c, JSONObject m, String fallback) {
        String name = displayName(m.optString("tool", ""));
        if (name.isEmpty()) name = fallback;
        String args = m.optString("args", "").replace('\n', ' ').trim();
        SpannableStringBuilder sb = new SpannableStringBuilder(name);
        sb.setSpan(new ForegroundColorSpan(TEXT), 0, name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new Markdown.WeightSpan(mono(c, 600)), 0, name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!args.isEmpty()) sb.append('(').append(args).append(')');
        return sb;
    }

    /**
     * One tool call: a state dot, a single "name(args)" line and an indented "└" result preview that
     * expands to the full stored output. A step without details is a quiet note whose only TextView
     * reads exactly the step text.
     */
    static final class ToolRow {
        final Gutter view;
        final Ui.Dot dot;
        /** The one TextView describing the call. */
        final TextView call;
        private final LinearLayout result;
        private final String step;
        private JSONObject meta;
        private int color;
        private boolean live, expanded;
        private String shownResult;
        private ValueAnimator tint;

        ToolRow(Context c, String step, JSONObject meta, boolean live) {
            this.step = step == null ? "" : step;
            this.live = live;
            color = FAINT;
            dot = toolDot(c, color);
            view = new Gutter(c, dot);
            call = monoText(c, "", 13, MUTED);
            call.setLineSpacing(dp(c, 2), 1.04f);
            call.setMaxLines(4);
            call.setEllipsize(TextUtils.TruncateAt.END);
            view.body.addView(call, full());
            result = column(c);
            result.setVisibility(View.GONE);
            view.body.addView(result, full());
            bind(meta, false);
        }

        /** Whether the chat is still working; a call that never finished stops pulsing when it ends. */
        void setLive(boolean on) {
            if (live == on) return;
            live = on;
            if (meta != null && "running".equals(meta.optString("state"))) bind(meta, true);
        }

        void bind(JSONObject m, boolean animate) {
            meta = m;
            Context c = view.getContext();
            if (m == null) {
                if (!step.contentEquals(call.getText())) call.setText(step);
                call.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                call.setTextColor(MUTED);
                setDot(FAINT, false, animate);
                showResult(c, null, animate);
                return;
            }
            String state = m.optString("state", "ok");
            boolean running = "running".equals(state), error = "error".equals(state);
            CharSequence label = callLabel(c, m, step);
            if (!label.toString().contentEquals(call.getText())) call.setText(label);
            call.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            call.setTextColor(MUTED);
            int tone = interrupted(m) ? FAINT : running ? ACCENT : error ? ERR : OK;
            setDot(tone, running && live, animate);
            showResult(c, m, animate);
        }

        /**
         * Stopped before it finished: still "running" after the run ended, settled as "Przerwano",
         * or an error that never got any output (a run cut short by the app process ending).
         */
        private boolean interrupted(JSONObject m) {
            String state = m.optString("state");
            if ("running".equals(state)) return !live;
            return "error".equals(state) && (!m.has("result") || INTERRUPTED.equals(m.optString("result").trim()));
        }

        String copyText() {
            if (meta == null) return step;
            String r = meta.optString("result", "").trim();
            return call.getText().toString() + (r.isEmpty() ? "" : "\n" + r);
        }

        private void setDot(int target, boolean pulse, boolean animate) {
            if (tint != null) {
                tint.cancel();
                tint = null;
            }
            dot.setPulsing(pulse);
            if (color == target) {
                dot.setColor(target);
                return;
            }
            int from = color;
            color = target;
            if (!animate || !Motion.enabled() || !dot.isAttachedToWindow()) {
                dot.setColor(target);
                return;
            }
            tint = ValueAnimator.ofArgb(from, target);
            tint.setDuration(Motion.MEDIUM + 60);
            tint.setInterpolator(Motion.EASE_OUT);
            tint.addUpdateListener(a -> dot.setColor((Integer) a.getAnimatedValue()));
            tint.start();
            if (!pulse) {
                // A short settle, like the marker landing on its final state.
                dot.setScaleX(1.4f);
                dot.setScaleY(1.4f);
                dot.animate().scaleX(1f).scaleY(1f).setDuration(Motion.LONG)
                        .setInterpolator(new OvershootInterpolator(2.2f)).start();
            }
        }

        private void showResult(Context c, JSONObject m, boolean animate) {
            List<String> out = new ArrayList<>();
            int lineCount = 0;
            boolean error = m != null && "error".equals(m.optString("state"));
            boolean interrupted = m != null && interrupted(m);
            boolean todo = m != null && "TodoWrite".equals(m.optString("tool"));
            if (m != null && !interrupted && m.has("result")) {
                String text = m.optString("result", "").replace("\r\n", "\n");
                for (String line : text.split("\n", -1)) out.add(line);
                while (!out.isEmpty() && out.get(out.size() - 1).trim().isEmpty()) out.remove(out.size() - 1);
                lineCount = Math.max(m.optInt("lines", out.size()), out.size());
            }
            final int total = lineCount;
            String signature = (interrupted ? "i" : "") + (error ? "e" : "") + total + "|" + TextUtils.join("\n", out);
            if (signature.equals(shownResult)) return;
            shownResult = signature;
            result.removeAllViews();
            if (!interrupted && out.isEmpty()) {
                result.setVisibility(View.GONE);
                return;
            }
            LinearLayout r = row(c);
            r.setGravity(Gravity.TOP);
            r.setBaselineAligned(false);
            r.setPadding(0, dp(c, 4), 0, 0);
            TextView elbow = monoText(c, "└", 12, FAINT);
            elbow.setPadding(0, 0, dp(c, 8), 0);
            elbow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            r.addView(elbow);
            LinearLayout col = column(c);
            if (interrupted) {
                col.addView(monoText(c, INTERRUPTED, 12, FAINT));
            } else {
                int tone = error ? ERR : MUTED;
                int preview = todo ? TODO_PREVIEW : PREVIEW;
                int shown = Math.min(preview, out.size());
                final TextView head = output(c, styled(out, 0, shown, tone, todo), tone);
                if (!todo) {
                    head.setMaxLines(expanded ? Integer.MAX_VALUE : preview * 2);
                    head.setEllipsize(TextUtils.TruncateAt.END);
                }
                col.addView(head, full());
                final int hidden = total - shown, stored = out.size() - shown;
                if (hidden > 0) {
                    final TextView rest = stored > 0 ? output(c, styled(out, shown, out.size(), tone, todo), tone) : null;
                    if (rest != null) {
                        rest.setVisibility(expanded ? View.VISIBLE : View.GONE);
                        col.addView(rest, full());
                    }
                    final TextView more = monoText(c, moreLabel(hidden, total, stored, rest != null && expanded), 12, FAINT);
                    more.setPadding(0, dp(c, 3), dp(c, 12), dp(c, 3));
                    if (rest != null) {
                        final int limit = preview * 2;
                        View.OnClickListener toggle = v -> {
                            expanded = !expanded;
                            if (head.getEllipsize() != null) head.setMaxLines(expanded ? Integer.MAX_VALUE : limit);
                            if (expanded) Motion.expand(rest);
                            else Motion.collapse(rest);
                            Motion.swapText(more, moreLabel(hidden, total, stored, expanded));
                        };
                        more.setOnClickListener(toggle);
                        more.setBackgroundResource(ripple(c, false));
                        view.setOnClickListener(toggle);
                    }
                    col.addView(more, wrap());
                }
            }
            r.addView(col, weight1());
            result.addView(r, full());
            if (animate && result.getVisibility() != View.VISIBLE) {
                Motion.expand(result);
            } else {
                result.setVisibility(View.VISIBLE);
                if (animate) Motion.fadeIn(r, 0);
            }
        }

        /**
         * Lines {@code from..to} as one text. A task plan ("[x] …", "[~] …", "[ ] …") gets Claude
         * Code's checklist look: done struck through, the current task bright, the rest muted.
         */
        private static CharSequence styled(List<String> lines, int from, int to, int tone, boolean todo) {
            if (!todo) return join(lines, from, to);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            for (int i = from; i < to; i++) {
                if (i > from) sb.append('\n');
                String line = lines.get(i);
                String mark = line.length() >= 4 && line.charAt(0) == '[' && line.charAt(2) == ']' ? line.substring(0, 3) : "";
                String body = mark.isEmpty() ? line : line.substring(Math.min(line.length(), 4));
                int glyphColor, textColor;
                String glyph;
                if ("[x]".equals(mark)) {
                    glyph = "✓";
                    glyphColor = OK;
                    textColor = FAINT;
                } else if ("[~]".equals(mark)) {
                    glyph = "›";
                    glyphColor = ACCENT_TEXT;
                    textColor = TEXT;
                } else {
                    glyph = "○";
                    glyphColor = FAINT;
                    textColor = tone;
                }
                int start = sb.length();
                sb.append(glyph);
                sb.setSpan(new ForegroundColorSpan(glyphColor), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(' ');
                int textStart = sb.length();
                sb.append(body);
                sb.setSpan(new ForegroundColorSpan(textColor), textStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if ("[x]".equals(mark))
                    sb.setSpan(new android.text.style.StrikethroughSpan(), textStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return sb;
        }

        private static TextView output(Context c, CharSequence s, int color) {
            TextView t = monoText(c, "", 12, color);
            t.setText(s);
            t.setLineSpacing(dp(c, 2), 1.04f);
            return t;
        }

        private static String moreLabel(int hidden, int total, int stored, boolean expanded) {
            if (!expanded) return "… +" + lines(hidden);
            return stored < hidden ? "zwiń · łącznie " + lines(total) : "zwiń";
        }

        private static String join(List<String> lines, int from, int to) {
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                if (i > from) sb.append('\n');
                sb.append(lines.get(i));
            }
            return sb.toString();
        }
    }
}
