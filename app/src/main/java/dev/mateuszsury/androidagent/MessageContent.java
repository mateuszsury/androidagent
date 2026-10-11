package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.content.*;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.*;
import android.widget.*;
import java.util.regex.*;

/** Small native renderer: selectable prose, bold, inline code, fenced code with copy. */
final class MessageContent {
  private static final Pattern INLINE = Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`]+)`", Pattern.DOTALL);
  static LinearLayout render(Context c, String source) {
    LinearLayout content = column(c);
    String[] blocks = source.split("```", -1);
    for (int i = 0; i < blocks.length; i++) {
      if (blocks[i].trim().isEmpty()) continue;
      if (i % 2 == 0) {
        TextView body = text(c, "", 16, TEXT);
        body.setTextIsSelectable(true);
        body.setLineSpacing(dp(c, 3), 1.12f);
        body.setText(inline(blocks[i].trim()));
        LinearLayout.LayoutParams p = full();
        p.topMargin = p.bottomMargin = dp(c, 6);
        content.addView(body, p);
      } else {
        String code = blocks[i];
        String language = "code";
        int nl = code.indexOf('\n');
        if (nl >= 0) {
          language = code.substring(0, nl).trim();
          code = code.substring(nl + 1);
        }
        if (language.isEmpty()) language = "code";
        // Fence separators aren't part of the code; indentation and blank lines are.
        final String copied =
            code.endsWith("\r\n")
                ? code.substring(0, code.length() - 2)
                : code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
        LinearLayout panel = column(c);
        panel.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 14));
        panel.setBackground(rounded(c, CODE, 14));
        LinearLayout header = row(c);
        header.addView(monoText(c, language, 11, CODE_TEXT), weight1());
        TextView copy = text(c, "Kopiuj", 12, CODE_TEXT);
        copy.setPadding(dp(c, 12), dp(c, 8), 0, dp(c, 8));
        copy.setContentDescription("Kopiuj kod");
        copy.setOnClickListener(
            v -> {
              c.getSystemService(ClipboardManager.class)
                  .setPrimaryClip(ClipData.newPlainText("Kod", copied));
              Toast.makeText(c, "Skopiowano kod", Toast.LENGTH_SHORT).show();
            });
        header.addView(copy);
        panel.addView(header, full());
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        TextView codeView = monoText(c, copied, 13, CODE_TEXT);
        codeView.setTextIsSelectable(true);
        scroll.addView(codeView);
        panel.addView(scroll, full());
        LinearLayout.LayoutParams p = full();
        p.topMargin = p.bottomMargin = dp(c, 10);
        content.addView(panel, p);
      }
    }
    return content;
  }

  static CharSequence inline(String text) {
    SpannableStringBuilder s = new SpannableStringBuilder();
    Matcher m = INLINE.matcher(text);
    int previous = 0;
    while (m.find()) {
      s.append(text.substring(previous, m.start()));
      int start = s.length();
      s.append(m.group(1) != null ? m.group(1) : m.group(2));
      s.setSpan(
          m.group(1) != null ? new StyleSpan(Typeface.BOLD) : new TypefaceSpan("monospace"),
          start,
          s.length(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      previous = m.end();
    }
    s.append(text.substring(previous));
    return s;
  }
}
