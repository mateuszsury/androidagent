package dev.mateuszsury.androidagent;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** UI regressions without a model account; invoked by the device suite. */
final class UiRegressionChecks {
    static void streamSurvivesResume(Instrumentation instrumentation, Activity screen) throws Exception {
        if (!(screen instanceof ChatActivity)) throw new AssertionError("Expected a live chat fixture");
        final String[] expected = {null};
        final Throwable[] failure = {null};
        onMain(instrumentation, () -> {
            try {
                TextView before = (TextView) description(screen.getWindow().getDecorView(), "Odpowiedź na żywo");
                require(before != null && before.length() > 0, "Missing streaming fixture");
                expected[0] = before.getText().toString();
                instrumentation.callActivityOnPause(screen);
                instrumentation.callActivityOnResume(screen);
                TextView after = (TextView) description(screen.getWindow().getDecorView(), "Odpowiedź na żywo");
                require(after != null && expected[0].contentEquals(after.getText()), "Live reply detached after resume");
            } catch (Throwable e) { failure[0] = e; }
        });
        rethrow(failure[0]);
    }

    static void tallSheetScrolls(Instrumentation instrumentation, Activity screen) throws Exception {
        final Dialog[] dialog = {null};
        final TextView[] last = {null};
        final ScrollView[] viewport = {null};
        try {
            onMain(instrumentation, () -> {
                LinearLayout content = Ui.column(screen);
                for (int i = 0; i < 80; i++) {
                    last[0] = Ui.text(screen, "Opcja " + i, 18, Ui.TEXT);
                    last[0].setPadding(0, Ui.dp(screen, 10), 0, Ui.dp(screen, 10));
                    content.addView(last[0], Ui.full());
                }
                dialog[0] = Ui.bottomSheet(screen, content);
                dialog[0].show();
            });
            instrumentation.waitForIdleSync();
            onMain(instrumentation, () -> {
                viewport[0] = scroll(dialog[0].getWindow().getDecorView());
                if (viewport[0] != null) viewport[0].scrollTo(0, viewport[0].getChildAt(0).getHeight());
            });
            instrumentation.waitForIdleSync();
            final Throwable[] failure = {null};
            onMain(instrumentation, () -> {
                try {
                    require(viewport[0] != null && viewport[0].getHeight() > 0, "No bounded sheet viewport");
                    require(viewport[0].getScrollY() > 0, "Tall sheet cannot scroll");
                    Rect visible = new Rect();
                    require(last[0].getGlobalVisibleRect(visible) && visible.height() > 0,
                            "Final option is clipped and unreachable");
                } catch (Throwable e) { failure[0] = e; }
            });
            rethrow(failure[0]);
        } finally {
            onMain(instrumentation, () -> { if (dialog[0] != null) dialog[0].dismiss(); });
        }
    }

    static void switchReportsState(Instrumentation instrumentation, Activity screen) throws Exception {
        final Throwable[] failure = {null};
        onMain(instrumentation, () -> {
            try {
                final boolean[] changed = {false};
                LinearLayout row = Ui.switchRow(screen, "Odczyt odpowiedzi", "Dźwięk", false, on -> changed[0] = on);
                AccessibilityNodeInfo node = row.createAccessibilityNodeInfo();
                require(node.isCheckable() && !node.isChecked(), "Switch lacks initial accessibility state");
                node.recycle();
                row.performClick();
                node = row.createAccessibilityNodeInfo();
                require(changed[0] && node.isChecked(), "Switch state not exposed after toggle");
                node.recycle();
            } catch (Throwable e) { failure[0] = e; }
        });
        rethrow(failure[0]);
    }

    static void dialogMatchesPalette(Instrumentation instrumentation, Activity screen) throws Exception {
        final Throwable[] failure = {null};
        onMain(instrumentation, () -> {
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(screen)
                    .setTitle("Wygląd okna").setMessage("Czytelny komunikat").setPositiveButton("OK", null).create();
            try {
                dialog.show();
                android.content.res.TypedArray colors = dialog.getContext().obtainStyledAttributes(
                        new int[]{android.R.attr.colorBackgroundFloating});
                int background = colors.getColor(0, 0);
                colors.recycle();
                TextView message = dialog.findViewById(android.R.id.message);
                require(message != null, "Missing native dialog message");
                double surface = Theme.luminance(background);
                require(Ui.DARK ? surface < 0.5 : surface > 0.7, "Native dialog ignores app mode");
                require(Math.abs(Theme.luminance(message.getCurrentTextColor()) - surface) > 0.45,
                        "Native dialog text has insufficient contrast");
                require(Math.abs(Theme.luminance(Ui.TEXT) - surface) > 0.45,
                        "Custom dialog content has insufficient contrast");
            } catch (Throwable e) { failure[0] = e; }
            finally { dialog.dismiss(); }
        });
        rethrow(failure[0]);
    }

    private static View description(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = description(((ViewGroup) view).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }

    private static ScrollView scroll(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            ScrollView found = scroll(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Android does not forward uncaught main-thread failures to the instrumentation caller. */
    private static void onMain(Instrumentation instrumentation, Runnable action) throws Exception {
        final Throwable[] failure = {null};
        instrumentation.runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable e) { failure[0] = e; }
        });
        rethrow(failure[0]);
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure != null) throw new AssertionError(failure);
    }
}
