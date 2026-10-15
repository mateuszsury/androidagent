package dev.mateuszsury.androidagent;

import android.content.Context;
import android.widget.LinearLayout;

/** Agent reply rendering: full Markdown (see {@link Markdown}) in a column that can be updated in place. */
final class MessageContent {
  private MessageContent() {}

  /** A rendered reply. The returned column is a {@link Markdown.Content}; see {@link #update}. */
  static LinearLayout render(Context c, String source) {
    Markdown.Content content = new Markdown.Content(c);
    content.setMarkdown(source == null ? "" : source.trim(), false);
    return content;
  }

  /**
   * Re-render {@code view} (from {@link #render}) with new text, rebuilding only blocks that changed.
   * {@code streaming} marks text that is still arriving: an unclosed code fence renders as code.
   */
  static void update(LinearLayout view, String source, boolean streaming) {
    if (view instanceof Markdown.Content) ((Markdown.Content) view).setMarkdown(source == null ? "" : source, streaming);
  }

  /** Inline Markdown only (bold, italic, code, links), for single-line previews. */
  static CharSequence inline(Context c, String text) {
    return Markdown.inline(c, text);
  }
}
