package dev.mateuszsury.androidagent;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A small line-oriented terminal model for streamed process output: ANSI SGR colours (16, 256 and
 * truecolor, foreground and background), bold/dim/italic/underline/inverse/strike, carriage
 * returns and backspaces (progress bars overwrite their line), cursor moves and line erases (so
 * redrawing CLIs such as spinners render as their latest frame), screen clears, the alternate
 * screen and OSC 8 hyperlinks. Lines are kept as text plus a per-character style index and turned
 * into spans on demand. Not thread-safe: callers synchronize.
 */
final class AnsiText {
    /** Answers to terminal queries (cursor position, device attributes) go back to the process. */
    interface Reply {
        void send(String data);
    }

    static final int MAX_LINES = 2500, MAX_LINE = 4000, RENDER_CHARS = 120_000, ROWS = 24;
    static final int BOLD = 1, DIM = 2, ITALIC = 4, UNDERLINE = 8, INVERSE = 16, STRIKE = 32;

    /** Basic 16 colours tuned for the always-dark code surface. */
    static final int[] PALETTE = {
            0xFF4A4743, 0xFFE8806F, 0xFF9BCB8A, 0xFFE5C07B, 0xFF7FB0E8, 0xFFC8A6F2, 0xFF8CC7D9, 0xFFD9D6CE,
            0xFF8A877F, 0xFFF2A193, 0xFFB6E3A2, 0xFFF0D399, 0xFFA6C8F2, 0xFFDCC2F7, 0xFFABDBE8, 0xFFF5F4EF,
    };

    private static final class Style {
        final int fg, bg, flags, link; // fg/bg: 0 = default
        Style(int fg, int bg, int flags, int link) {
            this.fg = fg;
            this.bg = bg;
            this.flags = flags;
            this.link = link;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Style)) return false;
            Style s = (Style) o;
            return s.fg == fg && s.bg == bg && s.flags == flags && s.link == link;
        }

        @Override public int hashCode() {
            return ((fg * 31 + bg) * 31 + flags) * 31 + link;
        }
    }

    private static final class Line {
        final StringBuilder text = new StringBuilder();
        int[] styles = new int[32];

        void put(int col, char ch, int style) {
            if (col >= MAX_LINE) return;
            while (text.length() < col) {
                ensure(text.length() + 1);
                styles[text.length()] = 0;
                text.append(' ');
            }
            ensure(col + 1);
            if (col < text.length()) text.setCharAt(col, ch);
            else text.append(ch);
            styles[col] = style;
        }

        void ensure(int n) {
            if (n > styles.length) {
                int[] next = new int[Math.max(n, styles.length * 2)];
                System.arraycopy(styles, 0, next, 0, styles.length);
                styles = next;
            }
        }

        void truncate(int col) {
            if (col < text.length()) text.setLength(Math.max(0, col));
        }

        void blank(int from, int to) {
            for (int i = Math.max(0, from); i < Math.min(to, text.length()); i++) {
                text.setCharAt(i, ' ');
                styles[i] = 0;
            }
        }

        void delete(int col, int n) {
            if (col >= text.length()) return;
            int end = Math.min(text.length(), col + n);
            System.arraycopy(styles, end, styles, col, text.length() - end);
            text.delete(col, end);
        }

        void insert(int col, int n) {
            if (col >= text.length()) return;
            n = Math.min(n, MAX_LINE - text.length());
            if (n <= 0) return;
            ensure(text.length() + n);
            System.arraycopy(styles, col, styles, col + n, text.length() - col);
            for (int i = 0; i < n; i++) styles[col + i] = 0;
            StringBuilder spaces = new StringBuilder();
            for (int i = 0; i < n; i++) spaces.append(' ');
            text.insert(col, spaces);
        }
    }

    private enum State { TEXT, ESC, CSI, OSC, OSC_ESC, STRING, STRING_ESC, CHARSET }

    private ArrayList<Line> lines = new ArrayList<>();
    private ArrayList<Line> savedScreen;
    private final List<Style> styles = new ArrayList<>();
    private final Map<Style, Integer> styleIds = new HashMap<>();
    private final List<String> links = new ArrayList<>();
    private int row, col, savedRow, savedCol;
    private int fg, bg, flags, link;
    private State state = State.TEXT;
    private final StringBuilder seq = new StringBuilder();
    private boolean appCursor;
    private Reply reply;
    private long version;

    AnsiText() {
        styles.add(new Style(0, 0, 0, 0));
        styleIds.put(styles.get(0), 0);
        links.add(null);
        lines.add(new Line());
    }

    void setReply(Reply r) {
        reply = r;
    }

    /** Changes on every append; cheap to compare from a UI poll. */
    long version() {
        return version;
    }

    /** Application cursor keys mode (DECCKM): arrows are sent as ESC O A instead of ESC [ A. */
    boolean applicationCursor() {
        return appCursor;
    }

    /** One-shot rendering of a complete text, e.g. a build log. */
    static CharSequence render(CharSequence text, Typeface bold) {
        AnsiText t = new AnsiText();
        t.append(text);
        return t.spanned(bold);
    }

    /** Text without escape sequences, after carriage returns and erases were applied. */
    static String plain(CharSequence text) {
        AnsiText t = new AnsiText();
        t.append(text);
        return t.plain();
    }

    void clear() {
        lines = new ArrayList<>();
        lines.add(new Line());
        row = col = 0;
        version++;
    }

    void append(CharSequence s) {
        if (s == null || s.length() == 0) return;
        version++;
        for (int i = 0; i < s.length(); i++) feed(s.charAt(i));
        trim();
    }

    private void feed(char ch) {
        switch (state) {
            case TEXT:
                text(ch);
                break;
            case ESC:
                escape(ch);
                break;
            case CSI:
                if (ch >= 0x40 && ch <= 0x7E) {
                    state = State.TEXT;
                    csi(seq.toString(), ch);
                } else if (ch == 0x1B) {
                    state = State.ESC;
                } else if (ch < 0x20) {
                    text(ch); // C0 controls execute inside sequences
                } else if (seq.length() < 64) {
                    seq.append(ch);
                }
                break;
            case OSC:
                if (ch == 0x07) {
                    state = State.TEXT;
                    osc(seq.toString());
                } else if (ch == 0x1B) {
                    state = State.OSC_ESC;
                } else if (seq.length() < 2048) {
                    seq.append(ch);
                }
                break;
            case OSC_ESC:
                state = State.TEXT;
                osc(seq.toString());
                if (ch != '\\') feed(ch);
                break;
            case STRING:
                if (ch == 0x07) state = State.TEXT;
                else if (ch == 0x1B) state = State.STRING_ESC;
                break;
            case STRING_ESC:
                state = ch == '\\' ? State.TEXT : State.STRING;
                break;
            case CHARSET:
                state = State.TEXT;
                break;
            default:
                state = State.TEXT;
        }
    }

    private void text(char ch) {
        switch (ch) {
            case '\n':
                lineFeed();
                col = 0;
                return;
            case '\r':
                col = 0;
                return;
            case '\b':
                if (col > 0) col--;
                return;
            case '\t':
                col = Math.min(MAX_LINE - 1, (col / 8 + 1) * 8);
                return;
            case 0x1B:
                state = State.ESC;
                seq.setLength(0);
                return;
            default:
                if (ch < 0x20 || ch == 0x7F) return;
                lines.get(row).put(col, ch, style());
                col++;
        }
    }

    private void lineFeed() {
        row++;
        if (row >= lines.size()) {
            lines.add(new Line());
            row = lines.size() - 1;
        }
    }

    private void escape(char ch) {
        seq.setLength(0);
        switch (ch) {
            case '[':
                state = State.CSI;
                return;
            case ']':
                state = State.OSC;
                return;
            case 'P': case 'X': case '^': case '_':
                state = State.STRING;
                return;
            case '(': case ')': case '*': case '+': case '#': case '%':
                state = State.CHARSET;
                return;
            case '7':
                savedRow = row;
                savedCol = col;
                break;
            case '8':
                row = Math.min(savedRow, lines.size() - 1);
                col = savedCol;
                break;
            case 'M':
                if (row > 0) row--;
                break;
            case 'D':
                lineFeed();
                break;
            case 'E':
                lineFeed();
                col = 0;
                break;
            case 'c':
                fg = bg = flags = link = 0;
                clear();
                break;
            default:
                break;
        }
        state = State.TEXT;
    }

    private void csi(String params, char fin) {
        String priv = "";
        if (!params.isEmpty() && "?<=>".indexOf(params.charAt(0)) >= 0) {
            priv = params.substring(0, 1);
            params = params.substring(1);
        }
        int[] p = numbers(params);
        int n = p.length == 0 || p[0] <= 0 ? 1 : p[0];
        Line line = lines.get(row);
        switch (fin) {
            case 'm':
                if (priv.isEmpty()) sgr(p, params);
                break;
            case 'A':
                row = Math.max(screenTop(), row - n);
                break;
            case 'B':
                row = Math.min(lines.size() - 1, row + n);
                break;
            case 'C':
                col = Math.min(MAX_LINE - 1, col + n);
                break;
            case 'D':
                col = Math.max(0, col - n);
                break;
            case 'E':
                row = Math.min(lines.size() - 1, row + n);
                col = 0;
                break;
            case 'F':
                row = Math.max(screenTop(), row - n);
                col = 0;
                break;
            case 'G':
            case '`':
                col = Math.min(MAX_LINE - 1, n - 1);
                break;
            case 'd':
                moveRow(n);
                break;
            case 'H':
            case 'f':
                moveRow(n);
                col = Math.min(MAX_LINE - 1, (p.length > 1 && p[1] > 0 ? p[1] : 1) - 1);
                break;
            case 'J': {
                int mode = p.length == 0 ? 0 : p[0];
                if (mode == 0) {
                    line.truncate(col);
                    while (lines.size() > row + 1) lines.remove(lines.size() - 1);
                } else if (mode == 1) {
                    for (int r = screenTop(); r < row; r++) lines.get(r).truncate(0);
                    line.blank(0, col + 1);
                } else {
                    // A clear screen: what the user sees in a real terminal is an empty window.
                    clear();
                }
                break;
            }
            case 'K': {
                int mode = p.length == 0 ? 0 : p[0];
                if (mode == 0) line.truncate(col);
                else if (mode == 1) line.blank(0, col + 1);
                else line.truncate(0);
                break;
            }
            case 'P':
                line.delete(col, n);
                break;
            case '@':
                line.insert(col, n);
                break;
            case 'X':
                line.blank(col, col + n);
                break;
            case 's':
                savedRow = row;
                savedCol = col;
                break;
            case 'u':
                row = Math.min(savedRow, lines.size() - 1);
                col = savedCol;
                break;
            case 'h':
            case 'l':
                if ("?".equals(priv)) mode(p, fin == 'h');
                break;
            case 'n':
                if (reply != null && p.length > 0 && p[0] == 6)
                    reply.send("\u001b[" + (row - screenTop() + 1) + ";" + (col + 1) + "R");
                else if (reply != null && p.length > 0 && p[0] == 5) reply.send("\u001b[0n");
                break;
            case 'c':
                if (reply != null && priv.isEmpty() && (p.length == 0 || p[0] == 0)) reply.send("\u001b[?1;2c");
                break;
            default:
                break;
        }
    }

    private void moveRow(int n) {
        int top = screenTop();
        int target = top + Math.max(1, n) - 1;
        while (lines.size() <= target && lines.size() < top + ROWS) lines.add(new Line());
        row = Math.min(target, lines.size() - 1);
    }

    private int screenTop() {
        return Math.max(0, lines.size() - ROWS);
    }

    private void mode(int[] p, boolean on) {
        for (int m : p) {
            if (m == 1) appCursor = on;
            else if (m == 1049 || m == 1047 || m == 47) {
                if (on && savedScreen == null) {
                    savedScreen = lines;
                    savedRow = row;
                    savedCol = col;
                    lines = new ArrayList<>();
                    lines.add(new Line());
                    row = col = 0;
                } else if (!on && savedScreen != null) {
                    lines = savedScreen;
                    savedScreen = null;
                    row = Math.min(savedRow, lines.size() - 1);
                    col = savedCol;
                }
            }
        }
    }

    private void sgr(int[] p, String raw) {
        if (p.length == 0) {
            fg = bg = flags = 0;
            return;
        }
        for (int i = 0; i < p.length; i++) {
            int c = p[i];
            if (c == 0) {
                fg = bg = 0;
                flags = 0;
            } else if (c == 1) flags |= BOLD;
            else if (c == 2) flags |= DIM;
            else if (c == 3) flags |= ITALIC;
            else if (c == 4) flags |= UNDERLINE;
            else if (c == 7) flags |= INVERSE;
            else if (c == 9) flags |= STRIKE;
            else if (c == 21 || c == 22) flags &= ~(BOLD | DIM);
            else if (c == 23) flags &= ~ITALIC;
            else if (c == 24) flags &= ~UNDERLINE;
            else if (c == 27) flags &= ~INVERSE;
            else if (c == 29) flags &= ~STRIKE;
            else if (c >= 30 && c <= 37) fg = PALETTE[c - 30];
            else if (c == 39) fg = 0;
            else if (c >= 40 && c <= 47) bg = PALETTE[c - 40];
            else if (c == 49) bg = 0;
            else if (c >= 90 && c <= 97) fg = PALETTE[c - 90 + 8];
            else if (c >= 100 && c <= 107) bg = PALETTE[c - 100 + 8];
            else if (c == 38 || c == 48) {
                int color = 0;
                if (i + 2 < p.length && p[i + 1] == 5) {
                    color = xterm(p[i + 2]);
                    i += 2;
                } else if (i + 4 < p.length && p[i + 1] == 2) {
                    color = 0xFF000000 | (clamp(p[i + 2]) << 16) | (clamp(p[i + 3]) << 8) | clamp(p[i + 4]);
                    i += 4;
                } else {
                    break;
                }
                if (c == 38) fg = color;
                else bg = color;
            }
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** xterm 256-colour index to ARGB: 16 tuned basics, the 6×6×6 cube and the grey ramp. */
    static int xterm(int n) {
        if (n < 0 || n > 255) return 0;
        if (n < 16) return PALETTE[n];
        if (n < 232) {
            int i = n - 16;
            int[] level = {0, 95, 135, 175, 215, 255};
            return 0xFF000000 | (level[i / 36] << 16) | (level[(i / 6) % 6] << 8) | level[i % 6];
        }
        int g = 8 + (n - 232) * 10;
        return 0xFF000000 | (g << 16) | (g << 8) | g;
    }

    private void osc(String body) {
        // OSC 8 ; params ; URI — hyperlinks. Titles and other OSC strings are dropped.
        if (body.startsWith("8;")) {
            int sep = body.indexOf(';', 2);
            String uri = sep < 0 ? "" : body.substring(sep + 1);
            if (uri.isEmpty()) link = 0;
            else {
                link = links.indexOf(uri);
                if (link < 0) {
                    if (links.size() > 512) links.subList(1, 256).clear();
                    links.add(uri);
                    link = links.size() - 1;
                }
            }
        }
    }

    private static int[] numbers(String s) {
        if (s.isEmpty()) return new int[0];
        String[] parts = s.split("[;:]", -1);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            int v = 0;
            String part = parts[i];
            for (int k = 0; k < part.length() && k < 9; k++) {
                char d = part.charAt(k);
                if (d < '0' || d > '9') break;
                v = v * 10 + (d - '0');
            }
            out[i] = v;
        }
        return out;
    }

    private int style() {
        if (fg == 0 && bg == 0 && flags == 0 && link == 0) return 0;
        Style s = new Style(fg, bg, flags, link);
        Integer id = styleIds.get(s);
        if (id == null) {
            if (styles.size() > 4096) return 0; // pathological colour churn: stop adding styles
            id = styles.size();
            styles.add(s);
            styleIds.put(s, id);
        }
        return id;
    }

    private void trim() {
        if (lines.size() > MAX_LINES) {
            int drop = lines.size() - MAX_LINES;
            lines.subList(0, drop).clear();
            row = Math.max(0, row - drop);
            savedRow = Math.max(0, savedRow - drop);
        }
    }

    private int firstRenderedLine() {
        int total = 0, i = lines.size() - 1;
        for (; i > 0; i--) {
            total += lines.get(i).text.length() + 1;
            if (total > RENDER_CHARS) break;
        }
        return Math.max(0, i);
    }

    String plain() {
        StringBuilder out = new StringBuilder();
        int first = firstRenderedLine();
        for (int i = first; i < lines.size(); i++) {
            if (i > first) out.append('\n');
            out.append(lines.get(i).text);
        }
        return out.toString();
    }

    /** Spans for the visible tail; {@code bold} is the mono face used for bold runs (may be null). */
    CharSequence spanned(Typeface bold) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        int first = firstRenderedLine();
        for (int i = first; i < lines.size(); i++) {
            if (i > first) out.append('\n');
            Line line = lines.get(i);
            int base = out.length();
            out.append(line.text);
            int n = line.text.length();
            int start = 0;
            while (start < n) {
                int id = line.styles[start];
                int end = start + 1;
                while (end < n && line.styles[end] == id) end++;
                if (id != 0) apply(out, styles.get(id), base + start, base + end, bold);
                start = end;
            }
        }
        return out;
    }

    private void apply(SpannableStringBuilder out, Style s, int a, int b, Typeface bold) {
        int flagsSpan = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
        int fore = s.fg, back = s.bg;
        if ((s.flags & INVERSE) != 0) {
            int f = fore == 0 ? Ui.CODE_TEXT : fore;
            fore = back == 0 ? Ui.CODE : back;
            back = f;
        }
        if ((s.flags & BOLD) != 0 && fore == 0) fore = 0xFFFFFFFF;
        if ((s.flags & DIM) != 0) fore = Theme.withAlpha(fore == 0 ? Ui.CODE_TEXT : fore, 0x99);
        if (fore != 0) out.setSpan(new ForegroundColorSpan(fore), a, b, flagsSpan);
        if (back != 0) out.setSpan(new BackgroundColorSpan(back), a, b, flagsSpan);
        if ((s.flags & BOLD) != 0) {
            if (bold != null) out.setSpan(new TypefaceSpan(bold), a, b, flagsSpan);
            else out.setSpan(new StyleSpan(Typeface.BOLD), a, b, flagsSpan);
        }
        if ((s.flags & ITALIC) != 0) out.setSpan(new StyleSpan(Typeface.ITALIC), a, b, flagsSpan);
        if ((s.flags & UNDERLINE) != 0) out.setSpan(new UnderlineSpan(), a, b, flagsSpan);
        if ((s.flags & STRIKE) != 0) out.setSpan(new StrikethroughSpan(), a, b, flagsSpan);
        if (s.link > 0 && s.link < links.size()) {
            String uri = links.get(s.link);
            if (uri != null && (uri.startsWith("https://") || uri.startsWith("http://")))
                out.setSpan(new URLSpan(uri), a, b, flagsSpan);
        }
    }
}
