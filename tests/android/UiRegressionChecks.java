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
    /** Exercise the Android editor connection and native Send without any model account. */
    static void keyboardInputSendsLocalStatus(Instrumentation instrumentation, Activity screen) throws Exception {
        require(screen instanceof ChatActivity, "Expected a chat fixture");
        onMain(instrumentation, () -> {
            android.widget.EditText input = edit(screen.getWindow().getDecorView());
            require(input != null, "Chat has no editor");
            input.setText("");
            input.requestFocus();
            android.view.inputmethod.EditorInfo info = new android.view.inputmethod.EditorInfo();
            android.view.inputmethod.InputConnection connection = input.onCreateInputConnection(info);
            require(connection != null, "Keyboard cannot obtain the editor connection");
            require((info.imeOptions & android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0,
                    "Landscape keyboard can hide the native Send button behind extract mode");
            require(connection.commitText("/status", 1), "Keyboard commit failed");
            View send = description(screen.getWindow().getDecorView(), "Wyślij");
            require(send != null && send.isEnabled(), "Keyboard input did not enable Send");
            send.performClick();
            require(input.length() == 0 && !send.isEnabled(), "Local keyboard submission was not accepted exactly once");
            connection.closeConnection();
        });
    }

    static void longModelChipFits(Instrumentation instrumentation, Activity screen) throws Exception {
        onMain(instrumentation, () -> {
            LinearLayout chip = Ui.chip(screen, "Claude Code · very-long-custom-model-name-from-account");
            int width = Ui.dp(screen, 150);
            chip.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(Ui.dp(screen, 100), View.MeasureSpec.AT_MOST));
            chip.layout(0, 0, width, chip.getMeasuredHeight());
            View chevron = chip.getChildAt(1);
            require(chevron.getWidth() > 0 && chevron.getRight() <= width - chip.getPaddingRight(),
                    "A custom model name clips the dropdown affordance");
            require(chip.getChildAt(0).getRight() <= chevron.getLeft(), "Model text overlaps its dropdown affordance");
        });
    }

    static void toolLogRemainsBounded(Instrumentation instrumentation, Activity screen) throws Exception {
        require(screen instanceof ChatActivity, "Expected a chat fixture");
        java.lang.reflect.Method show = ChatActivity.class.getDeclaredMethod("show", Bubble.Kind.class, String.class);
        show.setAccessible(true);
        try {
            onMain(instrumentation, () -> {
                try {
                    for (int i = 0; i < 500; i++) show.invoke(screen, Bubble.Kind.STEP, "tool fixture " + i);
                    View root = screen.getWindow().getDecorView();
                    require(countText(root, "tool fixture ") == Sessions.MAX_MESSAGES,
                            "Consecutive tool calls exceeded the chat history bound");
                    require(text(root, "tool fixture 499") != null && text(root, "tool fixture 0") == null,
                            "Tool retention discarded recent calls or kept stale calls");
                    for (int i = 0; i < Sessions.MAX_MESSAGES; i++)
                        show.invoke(screen, Bubble.Kind.BUDDY, "replacement fixture " + i);
                    require(countText(root, "tool fixture ") == 0,
                            "Old tool group rows survive after newer replies replace history");
                    require(text(root, "0 tool calls") == null, "An empty tool header remains in chat");
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
        } finally {
            onMain(instrumentation, () -> {
                instrumentation.callActivityOnPause(screen);
                instrumentation.callActivityOnResume(screen);
            });
        }
    }

    /** A real provider read waits on a FIFO while Android recreates the receiving chat. */
    static Activity attachmentImportSurvivesRecreate(Instrumentation instrumentation, Activity screen) throws Exception {
        require(screen instanceof ChatActivity, "Expected a chat fixture");
        android.content.Context app = instrumentation.getTargetContext();
        String name = "rotation" + java.util.UUID.randomUUID().toString().replace("-", "") + ".txt";
        java.io.File fixture = Attachments.file(app, name);
        android.system.Os.mkfifo(fixture.getAbsolutePath(), 0600);
        java.io.FileDescriptor pipe = android.system.Os.open(fixture.getAbsolutePath(),
                android.system.OsConstants.O_RDWR | android.system.OsConstants.O_NONBLOCK, 0600);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(ChatActivity.class.getName(), null, false);
        Activity recreated = null;
        try {
            onMain(instrumentation, () -> {
                android.widget.EditText input = edit(screen.getWindow().getDecorView());
                input.setText("Szkic z oczekującym załącznikiem");
                input.setSelection(6, 9);
                ((ChatActivity) screen).onActivityResult(23, Activity.RESULT_OK,
                        new android.content.Intent().setData(FilesProvider.attachmentUri(name)));
                View send = description(screen.getWindow().getDecorView(), "Wyślij");
                require(send != null && !send.isEnabled(), "Send permits text before its attachment finishes importing");
                screen.recreate();
            });
            recreated = instrumentation.waitForMonitorWithTimeout(monitor, 15000);
            require(recreated != null && recreated != screen, "Activity did not recreate during import");
            instrumentation.waitForIdleSync();
            final Activity restored = recreated;
            onMain(instrumentation, () -> {
                android.widget.EditText input = edit(restored.getWindow().getDecorView());
                require("Szkic z oczekującym załącznikiem".contentEquals(input.getText()), "Import rotation lost draft text");
                require(input.getSelectionStart() == 6 && input.getSelectionEnd() == 9, "Rotation lost draft selection");
                View send = description(restored.getWindow().getDecorView(), "Wyślij");
                require(send != null && !send.isEnabled(), "Rotation detached the import from Send state");
            });
            byte[] payload = "ATTACHMENT_ROTATION_OK".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            android.system.Os.write(pipe, payload, 0, payload.length);
            android.system.Os.close(pipe);
            pipe = null;
            java.lang.reflect.Field pendingField = ChatActivity.class.getDeclaredField("pending");
            pendingField.setAccessible(true);
            final String[] imported = {null};
            long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
            while (imported[0] == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                onMain(instrumentation, () -> {
                    try {
                        for (Object id : (java.util.List<?>) pendingField.get(restored))
                            if (((String) id).endsWith(name)) imported[0] = (String) id;
                    } catch (IllegalAccessException e) { throw new AssertionError(e); }
                });
                if (imported[0] == null) android.os.SystemClock.sleep(50);
            }
            require(imported[0] != null, "Imported attachment disappeared during Activity recreation");
            byte[] actual = java.nio.file.Files.readAllBytes(Attachments.file(app, imported[0]).toPath());
            require(java.util.Arrays.equals(payload, actual), "Imported attachment bytes changed");
            onMain(instrumentation, () -> {
                View send = description(restored.getWindow().getDecorView(), "Wyślij");
                require(send != null && send.isEnabled(), "Completed import left Send disabled");
            });
            return recreated;
        } finally {
            instrumentation.removeMonitor(monitor);
            if (pipe != null) android.system.Os.close(pipe);
            fixture.delete();
        }
    }

    static void streamSurvivesResume(Instrumentation instrumentation, Activity screen) throws Exception {
        if (!(screen instanceof ChatActivity)) throw new AssertionError("Expected a live chat fixture");
        final String[] expected = {null};
        final Throwable[] failure = {null};
        onMain(instrumentation, () -> {
            try {
                // The live reply is rendered as Markdown; its raw source is the streamed text.
                View before = description(screen.getWindow().getDecorView(), "Odpowiedź na żywo");
                require(before instanceof Markdown.Content && ((Markdown.Content) before).source().length() > 0,
                        "Missing streaming fixture");
                expected[0] = ((Markdown.Content) before).source();
                instrumentation.callActivityOnPause(screen);
                instrumentation.callActivityOnResume(screen);
                View after = description(screen.getWindow().getDecorView(), "Odpowiedź na żywo");
                require(after instanceof Markdown.Content && expected[0].equals(((Markdown.Content) after).source())
                        && ((Markdown.Content) after).getChildCount() > 0, "Live reply detached after resume");
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

    private static TextView text(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = text(((ViewGroup) view).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }

    private static int countText(View view, String prefix) {
        int count = view instanceof TextView && ((TextView) view).getText().toString().startsWith(prefix) ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            count += countText(((ViewGroup) view).getChildAt(i), prefix);
        return count;
    }

    private static android.widget.EditText edit(View view) {
        if (view instanceof android.widget.EditText) return (android.widget.EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            android.widget.EditText found = edit(((ViewGroup) view).getChildAt(i));
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
