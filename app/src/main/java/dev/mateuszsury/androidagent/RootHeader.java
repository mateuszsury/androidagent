package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.app.Activity;
import android.content.Context;
import android.view.ViewGroup;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Header for the bottom-navigation roots (Sesje, Ustawienia): no back arrow, a title with a mono
 * context line and trailing actions, then a hairline — the same rhythm as {@link Ui#topBar}.
 */
final class RootHeader {
    private RootHeader() {
    }

    static LinearLayout build(Activity a, String title, String subtitle, View... actions) {
        LinearLayout wrap = column(a);
        LinearLayout bar = row(a);
        bar.setPadding(dp(a, 20), dp(a, 8), dp(a, 8), dp(a, 8));
        bar.setMinimumHeight(dp(a, 60));
        LinearLayout texts = column(a);
        TextView t = single(title(a, title, 20));
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

    /** The mono subtitle of a header built by {@link #build}, or null. */
    static TextView subtitle(LinearLayout header) {
        LinearLayout bar = (LinearLayout) header.getChildAt(0);
        LinearLayout texts = (LinearLayout) bar.getChildAt(0);
        return texts.getChildCount() > 1 ? (TextView) texts.getChildAt(1) : null;
    }

    /** A small live indicator for a header: pulsing dot + mono label, e.g. "na żywo". */
    static LinearLayout live(Context c, String label, int color) {
        LinearLayout l = row(c);
        l.setPadding(dp(c, 10), dp(c, 4), dp(c, 12), dp(c, 4));
        l.setBackground(outlined(c, Theme.withAlpha(color, 0x12), 20, Theme.withAlpha(color, 0x3A), 1));
        Ui.Dot d = new Ui.Dot(c, color);
        l.addView(d, new LinearLayout.LayoutParams(dp(c, 12), dp(c, 12)));
        TextView t = single(monoText(c, label, 11, MUTED));
        t.setPadding(dp(c, 6), 0, 0, 0);
        l.addView(t);
        LinearLayout.LayoutParams p = wrap();
        p.rightMargin = dp(c, 12);
        l.setLayoutParams(p);
        return l;
    }
}
