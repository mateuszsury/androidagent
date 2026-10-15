package dev.mateuszsury.androidagent;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.StyleSpan;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A small, dependency-free syntax highlighter for code blocks in agent replies. It is a lexer, not a
 * parser: comments, strings, numbers, keywords, types, calls and keys get colours from {@link Ui}'s
 * SYN_* palette. Unknown languages fall back to a generic C-like scheme; diffs get line backgrounds.
 */
final class Syntax {
    private Syntax() {
    }

    private static Set<String> words(String s) {
        return new HashSet<>(Arrays.asList(s.split(" ")));
    }

    private static final Set<String> C_LIKE = words(
            "abstract as assert async await break case catch class const continue default defer delete do else enum "
            + "export extends extern final finally for from func function go goto if impl implements import in "
            + "instanceof interface internal let loop match mod module move mut namespace new of operator override "
            + "package private protected pub public readonly return sealed select static struct super switch "
            + "synchronized this throw throws trait transient try type typedef typeof union unsafe use using val "
            + "var virtual void volatile when where while with yield fun object companion data inline lateinit "
            + "suspend crate dyn ref self Self chan map range fallthrough sizeof template typename");
    private static final Set<String> LITERALS = words("true false null nil None True False undefined NaN Infinity");
    private static final Set<String> C_TYPES = words(
            "int long short byte char float double boolean bool string String void auto unsigned signed size_t "
            + "u8 u16 u32 u64 i8 i16 i32 i64 f32 f64 usize isize str any number never unknown Object Integer "
            + "Boolean List Map Set Array Vec Option Result Promise");
    private static final Set<String> PYTHON = words(
            "and as assert async await break class continue def del elif else except finally for from global if "
            + "import in is lambda nonlocal not or pass raise return try while with yield match case print self "
            + "cls");
    private static final Set<String> SHELL = words(
            "if then else elif fi for while until do done case esac in function return local export readonly "
            + "declare unset shift exit set source alias echo printf cd ls cat grep sed awk find chmod chown mkdir "
            + "rm cp mv curl wget git npm npx node python python3 pip pip3 docker adb sudo apt pkg make cmake "
            + "kill ps tail head touch tar unzip zip ssh scp test exec trap wait eval true false");
    private static final Set<String> SHELL_KEYWORDS = words(
            "if then else elif fi for while until do done case esac in function return local export");
    private static final Set<String> YAML_LITERALS = words("yes no on off y n");
    private static final Set<String> SQL = words(
            "select from where insert into values update set delete create table drop alter add column index "
            + "primary key foreign references join left right inner outer full on as and or not null is in "
            + "between like group by order having limit offset distinct union all case when then else end "
            + "count sum avg min max default unique check exists asc desc integer text varchar real blob");
    private static final Set<String> DOCKER = words(
            "FROM RUN CMD LABEL EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL "
            + "HEALTHCHECK SHELL AS");

    private static final int GENERIC = 0, PY = 1, SH = 2, JSON = 3, YAML = 4, XML = 5, CSS = 6, SQLL = 7,
            DOCKERFILE = 8, DIFF = 9, PLAIN = 10;

    private static int family(String lang, String code) {
        String l = lang == null ? "" : lang.toLowerCase(Locale.ROOT).trim();
        int space = l.indexOf(' ');
        if (space > 0) l = l.substring(0, space);
        switch (l) {
            case "py": case "python": case "python3": case "py3":
                return PY;
            case "sh": case "bash": case "shell": case "zsh": case "console": case "shellsession": case "terminal":
            case "fish": case "ps": case "powershell": case "env": case "dotenv": case "ini": case "toml": case "conf":
                return SH;
            case "json": case "jsonc": case "json5": case "jsonl":
                return JSON;
            case "yaml": case "yml":
                return YAML;
            case "xml": case "html": case "htm": case "svg": case "vue": case "svelte": case "plist": case "xhtml":
                return XML;
            case "css": case "scss": case "sass": case "less":
                return CSS;
            case "sql": case "sqlite": case "postgres": case "mysql": case "psql":
                return SQLL;
            case "dockerfile": case "docker": case "containerfile":
                return DOCKERFILE;
            case "diff": case "patch": case "udiff":
                return DIFF;
            case "text": case "txt": case "plain": case "plaintext": case "output": case "log": case "markdown":
            case "md":
                return PLAIN;
            case "":
                return looksLikeDiff(code) ? DIFF : GENERIC;
            default:
                return GENERIC;
        }
    }

    static boolean looksLikeDiff(String code) {
        String[] lines = code.split("\n", 40);
        int marks = 0, hunks = 0;
        for (String line : lines) {
            if (line.startsWith("@@") || line.startsWith("+++ ") || line.startsWith("--- ")) hunks++;
            else if (line.startsWith("+") || line.startsWith("-")) marks++;
        }
        return hunks > 0 && marks > 0;
    }

    /** Highlighted copy of {@code code}; never throws. */
    static CharSequence highlight(String code, String lang) {
        SpannableStringBuilder s = new SpannableStringBuilder(code);
        try {
            int f = family(lang, code);
            if (code.length() > 120_000 || f == PLAIN) return s;
            switch (f) {
                case DIFF: diff(s, code); break;
                case XML: xml(s, code); break;
                default: lex(s, code, f);
            }
        } catch (RuntimeException ignored) {
            // Highlighting is cosmetic; plain text is always a valid result.
        }
        return s;
    }

    private static void color(SpannableStringBuilder s, int start, int end, int color) {
        if (end > start) s.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void lex(SpannableStringBuilder s, String c, int f) {
        int n = c.length();
        int i = 0;
        boolean lineStart = true;
        while (i < n) {
            char ch = c.charAt(i);
            if (ch == '\n') { lineStart = true; i++; continue; }
            // Comments
            boolean hashComment = (f == PY || f == SH || f == YAML || f == DOCKERFILE)
                    && ch == '#' && (i == 0 || Character.isWhitespace(c.charAt(i - 1)));
            boolean slashComment = (f == GENERIC || f == CSS || f == JSON) && ch == '/' && i + 1 < n && c.charAt(i + 1) == '/'
                    && (i == 0 || c.charAt(i - 1) != ':');
            boolean dashComment = f == SQLL && ch == '-' && i + 1 < n && c.charAt(i + 1) == '-';
            if (hashComment || slashComment || dashComment) {
                int e = c.indexOf('\n', i);
                if (e < 0) e = n;
                color(s, i, e, Ui.SYN_COMMENT);
                s.setSpan(new StyleSpan(Typeface.ITALIC), i, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = e;
                continue;
            }
            if ((f == GENERIC || f == CSS || f == SQLL || f == JSON) && ch == '/' && i + 1 < n && c.charAt(i + 1) == '*') {
                int e = c.indexOf("*/", i + 2);
                e = e < 0 ? n : e + 2;
                color(s, i, e, Ui.SYN_COMMENT);
                s.setSpan(new StyleSpan(Typeface.ITALIC), i, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = e;
                continue;
            }
            // Strings
            if (ch == '"' || ch == '\'' || (ch == '`' && f == GENERIC)) {
                if (ch == '\'' && f == SH) {
                    int e = c.indexOf('\'', i + 1);
                    e = e < 0 ? n : e + 1;
                    color(s, i, e, Ui.SYN_STRING);
                    i = e;
                    continue;
                }
                // Python triple-quoted strings
                if (f == PY && i + 2 < n && c.charAt(i + 1) == ch && c.charAt(i + 2) == ch) {
                    String q = "" + ch + ch + ch;
                    int e = c.indexOf(q, i + 3);
                    e = e < 0 ? n : e + 3;
                    color(s, i, e, Ui.SYN_STRING);
                    i = e;
                    continue;
                }
                // An apostrophe inside prose-like words (Rust lifetimes, English) is not a string.
                if (ch == '\'' && i > 0 && Character.isLetterOrDigit(c.charAt(i - 1))) { i++; lineStart = false; continue; }
                int e = i + 1;
                boolean multi = ch == '`';
                while (e < n) {
                    char d = c.charAt(e);
                    if (d == '\\') { e += 2; continue; }
                    if (d == ch) { e++; break; }
                    if (d == '\n' && !multi) break;
                    e++;
                }
                if (e > n) e = n;
                int end = e;
                // A JSON/YAML key: "name": …
                int k = end;
                while (k < n && (c.charAt(k) == ' ' || c.charAt(k) == '\t')) k++;
                boolean key = (f == JSON || f == GENERIC || f == YAML) && k < n && c.charAt(k) == ':';
                color(s, i, end, key && f != GENERIC ? Ui.SYN_KEY : Ui.SYN_STRING);
                i = end;
                lineStart = false;
                continue;
            }
            // Shell variables
            if (ch == '$' && (f == SH || f == DOCKERFILE || f == GENERIC) && i + 1 < n) {
                int e = i + 1;
                if (c.charAt(e) == '{') {
                    int close = c.indexOf('}', e);
                    e = close < 0 ? n : close + 1;
                } else if (c.charAt(e) == '(') {
                    color(s, i, i + 2, Ui.SYN_KEYWORD);
                    i += 2;
                    continue;
                } else {
                    while (e < n && (Character.isLetterOrDigit(c.charAt(e)) || c.charAt(e) == '_')) e++;
                    if (e == i + 1 && e < n && "?#@*!$0123456789".indexOf(c.charAt(e)) >= 0) e++;
                }
                if (e > i + 1) {
                    color(s, i, e, Ui.SYN_NUMBER);
                    i = e;
                    lineStart = false;
                    continue;
                }
            }
            // Shell prompt at line start: "$ cmd"
            if (f == SH && lineStart && (ch == '$' || ch == '>' || ch == '#') && i + 1 < n && c.charAt(i + 1) == ' ') {
                color(s, i, i + 1, Ui.ACCENT);
                i += 2;
                lineStart = false;
                continue;
            }
            // Numbers
            if (Character.isDigit(ch) && (i == 0 || !isWord(c.charAt(i - 1)))) {
                int e = i + 1;
                while (e < n && (Character.isLetterOrDigit(c.charAt(e)) || c.charAt(e) == '.' || c.charAt(e) == '_')) e++;
                color(s, i, e, Ui.SYN_NUMBER);
                i = e;
                lineStart = false;
                continue;
            }
            // Shell/CLI flags
            if (f == SH && ch == '-' && (i == 0 || Character.isWhitespace(c.charAt(i - 1))) && i + 1 < n
                    && (Character.isLetter(c.charAt(i + 1)) || c.charAt(i + 1) == '-')) {
                int e = i + 1;
                while (e < n && (isWord(c.charAt(e)) || c.charAt(e) == '-')) e++;
                color(s, i, e, Ui.SYN_TYPE);
                i = e;
                lineStart = false;
                continue;
            }
            // Words
            if (Character.isLetter(ch) || ch == '_' || ch == '@') {
                int e = i + 1;
                while (e < n && (isWord(c.charAt(e)) || (f == CSS && c.charAt(e) == '-'))) e++;
                String w = c.substring(i, e);
                int k = e;
                while (k < n && c.charAt(k) == ' ') k++;
                char next = k < n ? c.charAt(k) : 0;
                Integer col = wordColor(w, f, next, lineStart, ch);
                if (col != null) {
                    color(s, i, e, col);
                    if (col == Ui.SYN_KEYWORD && f != SH) {
                        s.setSpan(new StyleSpan(Typeface.BOLD), i, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                i = e;
                lineStart = false;
                continue;
            }
            if (!Character.isWhitespace(ch)) lineStart = false;
            i++;
        }
    }

    private static boolean isWord(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private static Integer wordColor(String w, int f, char next, boolean lineStart, char first) {
        if (first == '@') return Ui.SYN_TYPE; // annotations and decorators
        switch (f) {
            case PY:
                if (PYTHON.contains(w)) return Ui.SYN_KEYWORD;
                if (LITERALS.contains(w)) return Ui.SYN_NUMBER;
                if (next == '(') return Ui.SYN_FUNC;
                if (Character.isUpperCase(w.charAt(0)) && w.length() > 1) return Ui.SYN_TYPE;
                return null;
            case SH:
                if (SHELL_KEYWORDS.contains(w)) return Ui.SYN_KEYWORD;
                if (lineStart && SHELL.contains(w)) return Ui.SYN_FUNC;
                if (lineStart) return Ui.SYN_FUNC;
                if (next == '=') return Ui.SYN_KEY;
                return null;
            case JSON:
                return LITERALS.contains(w) ? Ui.SYN_NUMBER : null;
            case YAML:
                if (next == ':') return Ui.SYN_KEY;
                return LITERALS.contains(w) || YAML_LITERALS.contains(w.toLowerCase(Locale.ROOT)) ? Ui.SYN_NUMBER : null;
            case CSS:
                if (next == ':') return Ui.SYN_KEY;
                return null;
            case SQLL:
                return SQL.contains(w.toLowerCase(Locale.ROOT)) ? Ui.SYN_KEYWORD : next == '(' ? Ui.SYN_FUNC : null;
            case DOCKERFILE:
                if (DOCKER.contains(w)) return Ui.SYN_KEYWORD;
                return null;
            default:
                if (C_LIKE.contains(w)) return Ui.SYN_KEYWORD;
                if (LITERALS.contains(w)) return Ui.SYN_NUMBER;
                if (C_TYPES.contains(w)) return Ui.SYN_TYPE;
                if (next == '(') return Ui.SYN_FUNC;
                if (Character.isUpperCase(w.charAt(0)) && w.length() > 1 && !w.equals(w.toUpperCase(Locale.ROOT)))
                    return Ui.SYN_TYPE;
                return null;
        }
    }

    private static void xml(SpannableStringBuilder s, String c) {
        int n = c.length();
        int i = 0;
        while (i < n) {
            if (c.startsWith("<!--", i)) {
                int e = c.indexOf("-->", i + 4);
                e = e < 0 ? n : e + 3;
                color(s, i, e, Ui.SYN_COMMENT);
                i = e;
                continue;
            }
            if (c.charAt(i) == '<') {
                int e = c.indexOf('>', i);
                if (e < 0) e = n - 1;
                color(s, i, Math.min(n, i + 1), Ui.SYN_COMMENT);
                int j = i + 1;
                if (j < n && (c.charAt(j) == '/' || c.charAt(j) == '?' || c.charAt(j) == '!')) j++;
                int nameEnd = j;
                while (nameEnd < n && nameEnd <= e && (isWord(c.charAt(nameEnd)) || c.charAt(nameEnd) == '-'
                        || c.charAt(nameEnd) == ':' || c.charAt(nameEnd) == '.')) nameEnd++;
                color(s, j, nameEnd, Ui.SYN_KEYWORD);
                int k = nameEnd;
                while (k < e) {
                    char ch = c.charAt(k);
                    if (ch == '"' || ch == '\'') {
                        int q = c.indexOf(ch, k + 1);
                        q = q < 0 || q > e ? e : q + 1;
                        color(s, k, q, Ui.SYN_STRING);
                        k = q;
                        continue;
                    }
                    if (isWord(ch) || ch == '-' || ch == ':') {
                        int w = k;
                        while (w < e && (isWord(c.charAt(w)) || c.charAt(w) == '-' || c.charAt(w) == ':')) w++;
                        color(s, k, w, Ui.SYN_FUNC);
                        k = w;
                        continue;
                    }
                    k++;
                }
                color(s, e, Math.min(n, e + 1), Ui.SYN_COMMENT);
                i = e + 1;
                continue;
            }
            i++;
        }
    }

    private static void diff(SpannableStringBuilder s, String c) {
        int start = 0;
        int n = c.length();
        while (start <= n) {
            int end = c.indexOf('\n', start);
            if (end < 0) end = n;
            String line = c.substring(start, end);
            Integer fg = null, bg = null;
            if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ")) {
                fg = Ui.CODE_TEXT;
                s.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.startsWith("@@")) {
                fg = Ui.SYN_FUNC;
            } else if (line.startsWith("+")) {
                fg = Ui.DIFF_ADD;
                bg = Ui.DIFF_ADD_BG;
            } else if (line.startsWith("-")) {
                fg = Ui.DIFF_DEL;
                bg = Ui.DIFF_DEL_BG;
            } else {
                fg = Ui.CODE_MUTED;
            }
            if (fg != null) color(s, start, end, fg);
            if (bg != null) {
                int lineEnd = Math.min(n, end + 1);
                s.setSpan(new FullLineBackground(bg), start, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = end + 1;
        }
    }

    /** Paints a whole visual line, including the space to the right of short lines. */
    static final class FullLineBackground implements LineBackgroundSpan {
        private final int color;

        FullLineBackground(int color) {
            this.color = color;
        }

        @Override
        public void drawBackground(Canvas canvas, Paint paint, int left, int right, int top, int baseline, int bottom,
                                   CharSequence text, int start, int end, int lineNumber) {
            int old = paint.getColor();
            paint.setColor(color);
            canvas.drawRect(left - 4000, top, right + 4000, bottom, paint);
            paint.setColor(old);
        }
    }
}
