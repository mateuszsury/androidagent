package dev.mateuszsury.androidagent;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;
import android.view.Window;

/**
 * Computes the palette from the user's appearance settings (mode, accent, surface style) and
 * writes it into the {@link Ui} colour fields. Every screen calls {@link #apply(Activity)} in onCreate.
 *
 * <p>The default look follows Claude Code: a warm near-black terminal surface, cream text, a coral
 * accent and quiet hairlines. Code blocks stay dark in both modes, like a terminal.
 */
final class Theme {
    static final String[][] ACCENTS = {
            {"#D97757", "Koral"}, {"#4F8BFF", "Ocean"}, {"#3FB98A", "Mięta"},
            {"#9A7CFF", "Fiolet"}, {"#E5678F", "Róż"}, {"#E0A43A", "Bursztyn"},
    };
    static final String[][] SURFACES = {{"warm", "Ciepła"}, {"neutral", "Neutralna"}, {"black", "Czarna"}};
    static final String[][] MODES = {{"system", "Systemowy"}, {"light", "Jasny"}, {"dark", "Ciemny"}};

    private static String applied = "";

    private Theme() {
    }

    static boolean isDark(Context c) {
        String m = Prefs.themeMode(c);
        if ("dark".equals(m)) return true;
        if ("light".equals(m)) return false;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Fill Ui's palette for the current settings. Cheap; returns whether anything changed. */
    static boolean load(Context c) {
        boolean dark = isDark(c);
        int accent = Prefs.accent(c);
        // The pre-refresh graphite default is unreadable as an accent on the dark surface.
        if (dark && accent == 0xFF202123) accent = 0xFFECECEC;
        String surface = Prefs.surface(c);
        String key = dark + "|" + accent + "|" + surface;
        if (key.equals(applied)) return false;
        applied = key;

        if (dark) {
            switch (surface) {
                case "neutral":
                    set(0xFF141414, 0xFF1F1F1F, 0xFF2E2E2E, 0xFF141414, 0xFF0C0C0C);
                    break;
                case "black":
                    set(0xFF000000, 0xFF141312, 0xFF242220, 0xFF000000, 0xFF0A0A09);
                    break;
                default: // warm: Claude Code's terminal surface
                    set(0xFF1A1917, 0xFF242320, 0xFF32302C, 0xFF1A1917, 0xFF121110);
            }
            Ui.TEXT = 0xFFF5F4EF;
            Ui.MUTED = 0xFFA8A59C;
            Ui.FAINT = 0xFF6F6C65;
            Ui.OK = 0xFF6CC28A;
            Ui.ERR = 0xFFE87D6E;
            Ui.WARN = 0xFFE5B454;
            Ui.INFO = 0xFFB4A6F5;
            Ui.USER = Theme.withAlpha(0xFFF5F4EF, 0x0F);
        } else {
            if ("neutral".equals(surface)) set(0xFFFFFFFF, 0xFFF5F5F4, 0xFFE9E9E7, 0xFFFFFFFF, 0xFF1C1C1B);
            else set(0xFFFAF9F5, 0xFFFFFFFF, 0xFFF0EEE6, 0xFFFAF9F5, 0xFF1F1E1C);
            Ui.TEXT = 0xFF1F1E1C;
            Ui.MUTED = 0xFF6B6A64;
            Ui.FAINT = 0xFFA3A199;
            Ui.OK = 0xFF2E8B57;
            Ui.ERR = 0xFFC2463A;
            Ui.WARN = 0xFFA86D0A;
            Ui.INFO = 0xFF6A55D8;
            Ui.USER = Theme.withAlpha(0xFF1F1E1C, 0x0B);
        }
        Ui.ACCENT = accent;
        Ui.ON_ACCENT = luminance(accent) > 0.62 ? 0xFF1A1714 : 0xFFFFFFFF;
        Ui.ACCENT_TEXT = dark ? lighten(accent, 0.08f) : darken(accent, luminance(accent) > 0.45 ? 0.72f : 0.88f);
        Ui.LINE = withAlpha(Ui.TEXT, dark ? 0x14 : 0x12);
        Ui.OUTLINE = withAlpha(Ui.TEXT, dark ? 0x26 : 0x24);
        Ui.ACCENT_SOFT = withAlpha(accent, dark ? 0x26 : 0x1F);
        Ui.ERR_SOFT = withAlpha(Ui.ERR, 0x1F);
        Ui.OK_SOFT = withAlpha(Ui.OK, 0x1F);
        Ui.WARN_SOFT = withAlpha(Ui.WARN, 0x1F);
        Ui.INFO_SOFT = withAlpha(Ui.INFO, 0x1F);
        Ui.CODE_TEXT = 0xFFEDEBE4; // the code block stays dark in both modes
        Ui.CODE_MUTED = 0xFF8A877F;
        Ui.CODE_LINE = 0x1FF5F4EF;
        Ui.INLINE_CODE = dark ? 0xFFB9C3F7 : 0xFF4C5BC4;
        Ui.INLINE_CODE_BG = withAlpha(Ui.INLINE_CODE, dark ? 0x1A : 0x14);
        // Syntax colours on the always-dark code surface.
        Ui.SYN_KEYWORD = 0xFFE8916E;
        Ui.SYN_STRING = 0xFFA8CC8C;
        Ui.SYN_COMMENT = 0xFF77746C;
        Ui.SYN_NUMBER = 0xFFE5C07B;
        Ui.SYN_FUNC = 0xFF89B4E8;
        Ui.SYN_TYPE = 0xFFC8A6F2;
        Ui.SYN_KEY = 0xFF8CC7D9;
        Ui.DIFF_ADD = 0xFF9BD8A8;
        Ui.DIFF_ADD_BG = 0x332F9E5A;
        Ui.DIFF_DEL = 0xFFF2A6A0;
        Ui.DIFF_DEL_BG = 0x33C5483A;
        Ui.DARK = dark;
        return true;
    }

    private static void set(int bg, int card, int card2, int nav, int code) {
        Ui.BG = bg;
        Ui.CARD = card;
        Ui.CARD2 = card2;
        Ui.NAV = nav;
        Ui.CODE = code;
    }

    /** Load the palette and style the window (bar colours and icon contrast) for an activity. */
    static void apply(Activity a) {
        load(a);
        // Native dialogs/widgets must follow the app's mode as well as our custom palette.
        // Screen calls this before super.onCreate; rethemed() reapplies it for new dialogs.
        a.setTheme(Ui.DARK ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
        Window w = a.getWindow();
        w.setStatusBarColor(Ui.BG);
        w.setNavigationBarColor(Ui.NAV);
        w.getDecorView().setBackgroundColor(Ui.BG);
        int flags = w.getDecorView().getSystemUiVisibility();
        if (Ui.DARK) {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
    }

    /** Settings changed: recalc, and tell the bubble to restyle itself. */
    static void changed(Context c) {
        applied = "";
        load(c);
        BuddyService s = BuddyService.get();
        if (s != null) s.applyTheme();
    }

    static String key() {
        return applied;
    }

    static int parse(String hex, int fallback) {
        try {
            return Color.parseColor(hex.startsWith("#") ? hex : "#" + hex) | 0xFF000000;
        } catch (Exception e) {
            return fallback;
        }
    }

    static String hex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    static int withAlpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }

    static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(b) + (Color.red(a) - Color.red(b)) * t),
                Math.round(Color.green(b) + (Color.green(a) - Color.green(b)) * t),
                Math.round(Color.blue(b) + (Color.blue(a) - Color.blue(b)) * t));
    }

    static int darken(int c, float f) {
        return Color.rgb(Math.round(Color.red(c) * f), Math.round(Color.green(c) * f), Math.round(Color.blue(c) * f));
    }

    /** Mix towards white by {@code f} (0…1). */
    static int lighten(int c, float f) {
        return blend(0xFFFFFFFF, c, f);
    }

    static double luminance(int c) {
        return (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0;
    }
}
