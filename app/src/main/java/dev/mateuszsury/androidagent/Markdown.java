package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native Markdown for agent replies: headings, paragraphs, emphasis, inline code, links, nested and
 * task lists, block quotes, rules, GitHub tables and fenced code with syntax highlighting, a language
 * label and copy. {@link View} re-renders incrementally, so streaming text only rebuilds the tail.
 */
final class Markdown {
    private Markdown() {
    }

    // ---------------------------------------------------------------- model

    static final int PARAGRAPH = 0, HEADING = 1, CODE = 2, QUOTE = 3, LIST = 4, TABLE = 5, RULE = 6;

    static final class Block {
        final int type;
        final String source;
        int level;          // heading level
        String lang = "";   // code language
        boolean closed = true;
        final List<Item> items = new ArrayList<>();
        final List<String[]> rows = new ArrayList<>();
        int[] align;

        Block(int type, String source) {
            this.type = type;
            this.source = source;
        }

        String key() {
            return type + ":" + level + ":" + closed + ":" + source;
        }
    }

    static final class Item {
        int depth;
        boolean ordered;
        String marker;      // "3." for ordered lists
        int task = -1;      // -1 none, 0 open, 1 done
        final StringBuilder text = new StringBuilder();
    }

    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})\\s*([^`\\s]*)?.*$");
    private static final Pattern HEAD = Pattern.compile("^ {0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern RULE_LINE = Pattern.compile("^ {0,3}([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)([-*+•])\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\s*)(\\d{1,9})([.)])\\s+(.*)$");
    private static final Pattern TASK = Pattern.compile("^\\[([ xX])\\]\\s+(.*)$");
    private static final Pattern QUOTE_LINE = Pattern.compile("^ {0,3}>\\s?(.*)$");
    private static final Pattern TABLE_DELIM = Pattern.compile("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$");

    static List<Block> parse(String src) {
        List<Block> out = new ArrayList<>();
        if (src == null) return out;
        String[] lines = src.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int i = 0;
        StringBuilder para = new StringBuilder();
        while (i < lines.length) {
            String line = lines[i];
            Matcher m;
            if (line.trim().isEmpty()) {
                flush(out, para);
                i++;
                continue;
            }
            if ((m = FENCE.matcher(line)).matches()) {
                flush(out, para);
                String fence = m.group(1);
                String lang = m.group(2) == null ? "" : m.group(2);
                int indent = line.indexOf(fence.charAt(0));
                StringBuilder code = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                for (; j < lines.length; j++) {
                    String l = lines[j];
                    String t = l.trim();
                    if (t.startsWith(fence) && t.replace(String.valueOf(fence.charAt(0)), "").isEmpty()) {
                        closed = true;
                        break;
                    }
                    // Fences indented inside a list item keep their relative indentation.
                    int strip = 0;
                    while (strip < indent && strip < l.length() && l.charAt(strip) == ' ') strip++;
                    if (code.length() > 0) code.append('\n');
                    code.append(l.substring(strip));
                }
                Block b = new Block(CODE, code.toString());
                b.lang = lang;
                b.closed = closed;
                out.add(b);
                i = closed ? j + 1 : lines.length;
                continue;
            }
            if ((m = HEAD.matcher(line)).matches()) {
                flush(out, para);
                Block b = new Block(HEADING, m.group(2));
                b.level = m.group(1).length();
                out.add(b);
                i++;
                continue;
            }
            // Setext headings: a paragraph line underlined with === or ---.
            if (para.length() > 0 && para.indexOf("\n") < 0 && line.trim().matches("^(=+|-{2,})$")) {
                Block b = new Block(HEADING, para.toString().trim());
                b.level = line.trim().charAt(0) == '=' ? 1 : 2;
                para.setLength(0);
                out.add(b);
                i++;
                continue;
            }
            if (RULE_LINE.matcher(line).matches()) {
                flush(out, para);
                out.add(new Block(RULE, ""));
                i++;
                continue;
            }
            if (QUOTE_LINE.matcher(line).matches()) {
                flush(out, para);
                StringBuilder q = new StringBuilder();
                int j = i;
                while (j < lines.length) {
                    Matcher qm = QUOTE_LINE.matcher(lines[j]);
                    if (qm.matches()) q.append(qm.group(1)).append('\n');
                    else if (!lines[j].trim().isEmpty() && q.length() > 0 && !startsBlock(lines[j])) q.append(lines[j]).append('\n');
                    else break;
                    j++;
                }
                out.add(new Block(QUOTE, q.toString()));
                i = j;
                continue;
            }
            if (i + 1 < lines.length && line.contains("|") && TABLE_DELIM.matcher(lines[i + 1]).matches()
                    && lines[i + 1].contains("-")) {
                flush(out, para);
                Block b = new Block(TABLE, "");
                String[] head = cells(line);
                String[] delim = cells(lines[i + 1]);
                b.align = new int[head.length];
                for (int k = 0; k < head.length && k < delim.length; k++) {
                    String d = delim[k].trim();
                    boolean left = d.startsWith(":"), right = d.endsWith(":");
                    b.align[k] = left && right ? Gravity.CENTER_HORIZONTAL : right ? Gravity.END : Gravity.START;
                }
                b.rows.add(head);
                StringBuilder raw = new StringBuilder(line).append('\n').append(lines[i + 1]);
                int j = i + 2;
                while (j < lines.length && lines[j].contains("|") && !lines[j].trim().isEmpty()) {
                    b.rows.add(cells(lines[j]));
                    raw.append('\n').append(lines[j]);
                    j++;
                }
                out.add(withSource(b, raw.toString()));
                i = j;
                continue;
            }
            if (BULLET.matcher(line).matches() || ORDERED.matcher(line).matches()) {
                flush(out, para);
                int j = i;
                List<Item> items = new ArrayList<>();
                StringBuilder raw = new StringBuilder();
                int baseIndent = leading(line);
                while (j < lines.length) {
                    String l = lines[j];
                    Matcher bm = BULLET.matcher(l), om = ORDERED.matcher(l);
                    if (bm.matches() || om.matches()) {
                        Item it = new Item();
                        int lead = leading(l);
                        it.depth = Math.max(0, Math.min(5, (lead - baseIndent + 1) / 2));
                        String body;
                        if (om.matches()) {
                            it.ordered = true;
                            it.marker = om.group(2) + ".";
                            body = om.group(4);
                        } else {
                            body = bm.group(3);
                        }
                        Matcher tm = TASK.matcher(body);
                        if (tm.matches()) {
                            it.task = tm.group(1).trim().isEmpty() ? 0 : 1;
                            body = tm.group(2);
                        }
                        it.text.append(body);
                        items.add(it);
                    } else if (l.trim().isEmpty()) {
                        // A blank line continues the list only if another item follows.
                        int k = j + 1;
                        while (k < lines.length && lines[k].trim().isEmpty()) k++;
                        if (k < lines.length && (BULLET.matcher(lines[k]).matches() || ORDERED.matcher(lines[k]).matches()
                                || (leading(lines[k]) > baseIndent + 1 && !FENCE.matcher(lines[k]).matches()))) {
                            raw.append('\n');
                            j++;
                            continue;
                        }
                        break;
                    } else if (FENCE.matcher(l).matches() || HEAD.matcher(l).matches() || QUOTE_LINE.matcher(l).matches()
                            || RULE_LINE.matcher(l).matches()) {
                        break;
                    } else if (!items.isEmpty()) {
                        items.get(items.size() - 1).text.append('\n').append(l.trim());
                    } else {
                        break;
                    }
                    raw.append(l).append('\n');
                    j++;
                }
                Block b = withSource(new Block(LIST, ""), raw.toString());
                b.items.addAll(items);
                out.add(b);
                i = Math.max(j, i + 1);
                continue;
            }
            if (para.length() > 0) para.append('\n');
            para.append(line);
            i++;
        }
        flush(out, para);
        return out;
    }

    private static Block withSource(Block b, String source) {
        Block c = new Block(b.type, source);
        c.level = b.level;
        c.lang = b.lang;
        c.closed = b.closed;
        c.items.addAll(b.items);
        c.rows.addAll(b.rows);
        c.align = b.align;
        return c;
    }

    private static boolean startsBlock(String l) {
        return FENCE.matcher(l).matches() || HEAD.matcher(l).matches() || RULE_LINE.matcher(l).matches()
                || BULLET.matcher(l).matches() || ORDERED.matcher(l).matches();
    }

    private static int leading(String l) {
        int n = 0;
        for (int i = 0; i < l.length(); i++) {
            char ch = l.charAt(i);
            if (ch == ' ') n++;
            else if (ch == '\t') n += 4;
            else break;
        }
        return n;
    }

    private static void flush(List<Block> out, StringBuilder para) {
        if (para.length() == 0) return;
        out.add(new Block(PARAGRAPH, para.toString().trim()));
        para.setLength(0);
    }

    private static String[] cells(String row) {
        String r = row.trim();
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|") && !r.endsWith("\\|")) r = r.substring(0, r.length() - 1);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean code = false;
        for (int i = 0; i < r.length(); i++) {
            char ch = r.charAt(i);
            if (ch == '\\' && i + 1 < r.length() && r.charAt(i + 1) == '|') {
                cur.append('|');
                i++;
            } else if (ch == '`') {
                code = !code;
                cur.append(ch);
            } else if (ch == '|' && !code) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString().trim());
        return out.toArray(new String[0]);
    }

    // ---------------------------------------------------------------- inline

    /** Inline Markdown as styled text: **bold**, *italic*, ~~strike~~, `code`, [links](url) and URLs. */
    static SpannableStringBuilder inline(Context c, String text) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        inlineInto(c, out, text, 0);
        return out;
    }

    private static final Pattern BARE_URL = Pattern.compile("^https?://[^\\s<>()\\[\\]]*[^\\s<>()\\[\\].,;:!?'\"*_~]");

    private static void inlineInto(Context c, SpannableStringBuilder out, String s, int depth) {
        int n = s.length();
        int i = 0;
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < n && "\\`*_{}[]()#+-.!|~<>".indexOf(s.charAt(i + 1)) >= 0) {
                out.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (ch == '`') {
                int run = 1;
                while (i + run < n && s.charAt(i + run) == '`') run++;
                String fence = s.substring(i, i + run);
                int close = s.indexOf(fence, i + run);
                while (close >= 0 && close + run < n && s.charAt(close + run) == '`') close = s.indexOf(fence, close + run + 1);
                if (close > 0) {
                    String code = s.substring(i + run, close);
                    if (code.length() > 2 && code.startsWith(" ") && code.endsWith(" ")) code = code.substring(1, code.length() - 1);
                    int start = out.length();
                    out.append(' ').append(code).append(' ');
                    out.setSpan(new CodeSpan(mono(c, 450)), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(INLINE_CODE), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(INLINE_CODE_BG), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(0.9f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + run;
                    continue;
                }
                out.append(fence);
                i += run;
                continue;
            }
            if ((ch == '[' || (ch == '!' && i + 1 < n && s.charAt(i + 1) == '[')) && depth < 4) {
                int open = ch == '!' ? i + 1 : i;
                int close = matching(s, open, '[', ']');
                if (close > 0 && close + 1 < n && s.charAt(close + 1) == '(') {
                    int paren = matching(s, close + 1, '(', ')');
                    if (paren > 0) {
                        String label = s.substring(open + 1, close);
                        String url = s.substring(close + 2, paren).trim();
                        int sp = url.indexOf(' ');
                        if (sp > 0) url = url.substring(0, sp);
                        if (url.startsWith("<") && url.endsWith(">")) url = url.substring(1, url.length() - 1);
                        int start = out.length();
                        if (ch == '!') out.append("🖼 ");
                        inlineInto(c, out, label.isEmpty() ? url : label, depth + 1);
                        link(c, out, start, out.length(), url);
                        i = paren + 1;
                        continue;
                    }
                }
            }
            if (ch == '<') {
                int close = s.indexOf('>', i);
                if (close > i) {
                    String inner = s.substring(i + 1, close);
                    if (inner.matches("(https?://|mailto:)[^\\s<>]+")) {
                        int start = out.length();
                        out.append(inner);
                        link(c, out, start, out.length(), inner);
                        i = close + 1;
                        continue;
                    }
                }
            }
            if ((ch == 'h' || ch == 'H') && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
                Matcher m = BARE_URL.matcher(s.substring(i, Math.min(n, i + 2048)));
                if (m.find()) {
                    int start = out.length();
                    out.append(m.group());
                    link(c, out, start, out.length(), m.group());
                    i += m.group().length();
                    continue;
                }
            }
            if (ch == '~' && i + 1 < n && s.charAt(i + 1) == '~') {
                int close = s.indexOf("~~", i + 2);
                if (close > i + 2) {
                    int start = out.length();
                    inlineInto(c, out, s.substring(i + 2, close), depth + 1);
                    out.setSpan(new StrikethroughSpan(), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(MUTED), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 2;
                    continue;
                }
            }
            if ((ch == '*' || ch == '_') && depth < 6) {
                int run = 1;
                while (i + run < n && s.charAt(i + run) == ch && run < 3) run++;
                boolean leftOk = i + run < n && !Character.isWhitespace(s.charAt(i + run));
                boolean wordBefore = i > 0 && Character.isLetterOrDigit(s.charAt(i - 1));
                if (leftOk && !(ch == '_' && wordBefore)) {
                    int close = closing(s, i + run, ch, run);
                    if (close > i + run) {
                        int start = out.length();
                        inlineInto(c, out, s.substring(i + run, close), depth + 1);
                        int end = out.length();
                        if (run >= 2) {
                            out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            out.setSpan(new WeightSpan(sans(c, 650)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        if (run == 1 || run == 3) out.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = close + run;
                        continue;
                    }
                }
                // Literal run; keep it whole so "**" is not re-read as two italics.
                out.append(s, i, i + run);
                i += run;
                continue;
            }
            out.append(ch);
            i++;
        }
    }

    private static int matching(String s, int open, char o, char cl) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') { i++; continue; }
            if (ch == '`') {
                int e = s.indexOf('`', i + 1);
                if (e > 0) { i = e; continue; }
            }
            if (ch == o) depth++;
            else if (ch == cl && --depth == 0) return i;
            if (ch == '\n' && o == '(') return -1;
        }
        return -1;
    }

    private static int closing(String s, int from, char ch, int run) {
        int i = from;
        int n = s.length();
        while (i < n) {
            char d = s.charAt(i);
            if (d == '\\') { i += 2; continue; }
            if (d == '`') {
                int e = s.indexOf('`', i + 1);
                if (e < 0) return -1;
                i = e + 1;
                continue;
            }
            if (d == ch) {
                int r = 0;
                while (i + r < n && s.charAt(i + r) == ch) r++;
                boolean rightOk = !Character.isWhitespace(s.charAt(i - 1));
                boolean wordAfter = i + r < n && Character.isLetterOrDigit(s.charAt(i + r));
                if (r >= run && rightOk && !(ch == '_' && wordAfter)) {
                    // For a longer run, the closing delimiter is its last `run` characters.
                    return i + (r - run);
                }
                i += r;
                continue;
            }
            i++;
        }
        return -1;
    }

    private static void link(Context c, SpannableStringBuilder out, int start, int end, String url) {
        final String target = url;
        out.setSpan(new ClickableSpan() {
            @Override public void onClick(android.view.View widget) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    widget.getContext().startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(widget.getContext(), "Nie można otworzyć: " + target, Toast.LENGTH_SHORT).show();
                }
            }

            @Override public void updateDrawState(TextPaint ds) {
                ds.setColor(ACCENT_TEXT);
                ds.setUnderlineText(true);
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Monospace (Geist Mono) inside proportional prose. */
    static final class CodeSpan extends MetricAffectingSpan {
        private final Typeface face;

        CodeSpan(Typeface face) {
            this.face = face;
        }

        @Override public void updateMeasureState(TextPaint p) {
            p.setTypeface(face);
        }

        @Override public void updateDrawState(TextPaint p) {
            p.setTypeface(face);
        }
    }

    /** Applies a real variable-font weight; StyleSpan(BOLD) alone would synthesize it. */
    static final class WeightSpan extends MetricAffectingSpan {
        private final Typeface face;

        WeightSpan(Typeface face) {
            this.face = face;
        }

        private void apply(TextPaint p) {
            Typeface current = p.getTypeface();
            boolean italic = current != null && current.isItalic();
            p.setTypeface(face);
            p.setFakeBoldText(false);
            if (italic) p.setTextSkewX(-0.2f);
        }

        @Override public void updateMeasureState(TextPaint p) {
            apply(p);
        }

        @Override public void updateDrawState(TextPaint p) {
            apply(p);
        }
    }

    // ---------------------------------------------------------------- views

    static final int BODY_SP = 15;

    private static TextView prose(Context c, CharSequence s, int sp, int color) {
        TextView t = text(c, "", sp, color);
        t.setText(s);
        t.setLineSpacing(dp(c, 2), 1.18f);
        t.setTextIsSelectable(true);
        if (s instanceof Spanned && ((Spanned) s).getSpans(0, s.length(), ClickableSpan.class).length > 0) {
            t.setMovementMethod(LinkMovementMethod.getInstance());
        }
        return t;
    }

    static android.view.View render(Context c, Block b, boolean last) {
        switch (b.type) {
            case HEADING: return heading(c, b);
            case CODE: return code(c, b);
            case QUOTE: return quote(c, b);
            case LIST: return list(c, b);
            case TABLE: return table(c, b);
            case RULE: return rule(c);
            default: {
                TextView t = prose(c, inline(c, b.source), BODY_SP, TEXT);
                t.setTag(b.key());
                return t;
            }
        }
    }

    private static android.view.View heading(Context c, Block b) {
        int sp = b.level == 1 ? 21 : b.level == 2 ? 18 : b.level == 3 ? 16 : 15;
        int weight = b.level <= 2 ? 700 : 650;
        SpannableStringBuilder s = inline(c, b.source);
        TextView t = prose(c, s, sp, b.level >= 4 ? MUTED : TEXT);
        t.setTypeface(sans(c, weight));
        t.setLetterSpacing(b.level == 1 ? -0.01f : 0f);
        t.setAccessibilityHeading(true);
        if (b.level <= 2) {
            LinearLayout box = column(c);
            box.addView(t, full());
            View line = new View(c);
            line.setBackgroundColor(LINE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1));
            lp.topMargin = dp(c, 6);
            if (b.level == 1) box.addView(line, lp);
            return box;
        }
        return t;
    }

    private static android.view.View rule(Context c) {
        View v = new View(c);
        v.setBackgroundColor(OUTLINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)));
        return v;
    }

    private static android.view.View quote(Context c, Block b) {
        LinearLayout box = column(c);
        box.setPadding(dp(c, 14), dp(c, 2), 0, dp(c, 2));
        box.setBackground(new QuoteBar(dp(c, 3), Theme.withAlpha(ACCENT, 0x99)));
        Content inner = new Content(c);
        inner.setTint(MUTED);
        inner.setMarkdown(b.source.trim(), false);
        box.addView(inner, full());
        return box;
    }

    private static final String[] BULLETS = {"•", "◦", "▪", "‣", "•", "◦"};

    private static android.view.View list(Context c, Block b) {
        LinearLayout box = column(c);
        for (int k = 0; k < b.items.size(); k++) {
            Item it = b.items.get(k);
            LinearLayout row = row(c);
            row.setGravity(Gravity.TOP);
            row.setPadding(dp(c, 4 + it.depth * 18), dp(c, 2), 0, dp(c, 2));
            TextView marker;
            if (it.task >= 0) {
                marker = monoText(c, it.task == 1 ? "✓" : "○", 14, it.task == 1 ? OK : MUTED);
                marker.setTypeface(mono(c, 700));
            } else if (it.ordered) {
                marker = monoText(c, it.marker, 13, ACCENT_TEXT);
                marker.setTypeface(mono(c, 600));
            } else {
                marker = text(c, BULLETS[Math.min(it.depth, BULLETS.length - 1)], BODY_SP + 1, ACCENT_TEXT);
            }
            marker.setGravity(Gravity.END);
            marker.setPadding(0, it.ordered ? dp(c, 2) : 0, dp(c, 10), 0);
            marker.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(marker, new LinearLayout.LayoutParams(dp(c, it.ordered ? 30 : 20), ViewGroup.LayoutParams.WRAP_CONTENT));
            SpannableStringBuilder s = inline(c, it.text.toString());
            if (it.task == 1) {
                s.setSpan(new ForegroundColorSpan(MUTED), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                s.setSpan(new StrikethroughSpan(), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            TextView t = prose(c, s, BODY_SP, TEXT);
            row.addView(t, weight1());
            box.addView(row, full());
        }
        return box;
    }

    private static android.view.View table(Context c, Block b) {
        TableLayout table = new TableLayout(c);
        table.setStretchAllColumns(false);
        int cols = 0;
        for (String[] r : b.rows) cols = Math.max(cols, r.length);
        int maxCell = dp(c, 260);
        for (int r = 0; r < b.rows.size(); r++) {
            String[] cellsInRow = b.rows.get(r);
            TableRow tr = new TableRow(c);
            if (r == 0) tr.setBackgroundColor(Theme.withAlpha(TEXT, 0x0D));
            else if (r % 2 == 0) tr.setBackgroundColor(Theme.withAlpha(TEXT, 0x05));
            for (int k = 0; k < cols; k++) {
                String cell = k < cellsInRow.length ? cellsInRow[k] : "";
                TextView t = text(c, "", 14, r == 0 ? TEXT : TEXT);
                t.setText(inline(c, cell));
                if (r == 0) t.setTypeface(sans(c, 650));
                t.setLineSpacing(dp(c, 1), 1.1f);
                t.setMaxWidth(maxCell);
                t.setTextIsSelectable(true);
                t.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
                int g = b.align != null && k < b.align.length ? b.align[k] : Gravity.START;
                t.setGravity(g | Gravity.CENTER_VERTICAL);
                tr.addView(t);
            }
            table.addView(tr);
            if (r < b.rows.size() - 1) {
                View sep = new View(c);
                sep.setBackgroundColor(r == 0 ? OUTLINE : LINE);
                table.addView(sep, new TableLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)));
            }
        }
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.addView(table);
        LinearLayout frame = column(c);
        frame.setBackground(outlined(c, 0x00000000, 12, OUTLINE, 1));
        frame.setClipToOutline(true);
        frame.addView(scroll, full());
        return frame;
    }

    private static android.view.View code(Context c, Block b) {
        String lang = b.lang == null ? "" : b.lang.trim();
        final String copied = b.source;
        LinearLayout panel = column(c);
        panel.setBackground(outlined(c, CODE, 12, CODE_LINE, 1));
        panel.setClipToOutline(true);
        LinearLayout header = row(c);
        header.setPadding(dp(c, 14), dp(c, 4), dp(c, 4), dp(c, 2));
        TextView dot = monoText(c, "●", 9, Syntax.looksLikeDiff(copied) || "diff".equalsIgnoreCase(lang) ? SYN_NUMBER : ACCENT);
        dot.setPadding(0, 0, dp(c, 8), 0);
        header.addView(dot);
        TextView label = monoText(c, lang.isEmpty() ? "code" : lang.toLowerCase(Locale.ROOT), 11, CODE_MUTED);
        label.setLetterSpacing(0.04f);
        header.addView(label, weight1());
        if (!b.closed) {
            TextView live = monoText(c, "…", 12, CODE_MUTED);
            live.setPadding(0, 0, dp(c, 8), 0);
            header.addView(live);
        }
        TextView copy = monoText(c, "Kopiuj", 11, CODE_MUTED);
        copy.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        copy.setContentDescription("Kopiuj kod");
        copy.setBackgroundResource(ripple(c, true));
        copy.setOnClickListener(v -> {
            c.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Kod", copied));
            Toast.makeText(c, "Skopiowano kod", Toast.LENGTH_SHORT).show();
            copy.setText("Skopiowano ✓");
            copy.setTextColor(SYN_STRING);
            copy.postDelayed(() -> {
                copy.setText("Kopiuj");
                copy.setTextColor(CODE_MUTED);
            }, 1600);
        });
        header.addView(copy);
        panel.addView(header, full());
        View sep = new View(c);
        sep.setBackgroundColor(CODE_LINE);
        panel.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)));
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        // Short lines still span the panel, so diff line backgrounds reach the edge.
        scroll.setFillViewport(true);
        TextView codeView = monoText(c, "", 12, CODE_TEXT);
        codeView.setText(Syntax.highlight(copied, lang));
        codeView.setTextIsSelectable(true);
        codeView.setLineSpacing(dp(c, 2), 1.12f);
        codeView.setPadding(dp(c, 14), dp(c, 12), dp(c, 18), dp(c, 14));
        codeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        scroll.addView(codeView);
        panel.addView(scroll, full());
        return panel;
    }

    /** The vertical accent bar to the left of a block quote. */
    static final class QuoteBar extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int width;

        QuoteBar(int width, int color) {
            this.width = width;
            paint.setColor(color);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect r = getBounds();
            float rad = width / 2f;
            canvas.drawRoundRect(r.left, r.top, r.left + width, r.bottom, rad, rad, paint);
        }

        @Override public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override public void setColorFilter(android.graphics.ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** Hanging indent for wrapped list lines (unused by the row layout, kept for plain text). */
    static final class Hang implements LeadingMarginSpan {
        private final int first, rest;

        Hang(int first, int rest) {
            this.first = first;
            this.rest = rest;
        }

        @Override public int getLeadingMargin(boolean firstLine) {
            return firstLine ? first : rest;
        }

        @Override public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom,
                                                CharSequence text, int start, int end, boolean first, Layout layout) {
        }
    }

    // ---------------------------------------------------------------- incremental container

    /**
     * A column of rendered blocks. {@link #setMarkdown} keeps views for unchanged leading blocks, so a
     * streaming reply rebuilds only its last paragraph or code block.
     */
    static final class Content extends LinearLayout {
        private final List<String> keys = new ArrayList<>();
        private int tint = 0;
        private String source = "";

        Content(Context c) {
            super(c);
            setOrientation(VERTICAL);
        }

        void setTint(int color) {
            tint = color;
        }

        String source() {
            return source;
        }

        void setMarkdown(String md, boolean streaming) {
            if (md == null) md = "";
            if (md.equals(source) && getChildCount() > 0) return;
            source = md;
            List<Block> blocks = parse(md);
            int same = 0;
            while (same < blocks.size() && same < keys.size() && same < getChildCount()
                    && blocks.get(same).key().equals(keys.get(same))) same++;
            // Update a trailing paragraph in place to avoid flicker while streaming.
            if (same == keys.size() - 1 && same == blocks.size() - 1 && same < getChildCount()
                    && blocks.get(same).type == PARAGRAPH && keys.get(same).startsWith(PARAGRAPH + ":")
                    && getChildAt(same) instanceof TextView) {
                TextView t = (TextView) getChildAt(same);
                Block b = blocks.get(same);
                t.setText(inline(getContext(), b.source));
                if (tint != 0) t.setTextColor(tint);
                keys.set(same, b.key());
                return;
            }
            while (getChildCount() > same) removeViewAt(getChildCount() - 1);
            while (keys.size() > same) keys.remove(keys.size() - 1);
            for (int k = same; k < blocks.size(); k++) {
                Block b = blocks.get(k);
                android.view.View v = render(getContext(), b, k == blocks.size() - 1);
                if (tint != 0 && v instanceof TextView) ((TextView) v).setTextColor(tint);
                LinearLayout.LayoutParams p = full();
                p.topMargin = k == 0 ? 0 : spacingBefore(b, blocks.get(k - 1));
                addView(v, p);
                keys.add(b.key());
            }
        }

        private int spacingBefore(Block b, Block prev) {
            Context c = getContext();
            if (b.type == HEADING) return dp(c, b.level <= 2 ? 20 : 16);
            if (b.type == CODE || b.type == TABLE || prev.type == CODE || prev.type == TABLE) return dp(c, 12);
            if (b.type == RULE || prev.type == RULE) return dp(c, 14);
            if (prev.type == HEADING) return dp(c, 8);
            return dp(c, 10);
        }
    }
}
