package dev.mateuszsury.androidagent;

import android.content.Context;

/** In-app setup entry points. Environment setup and account login are separate actions. */
final class TermuxSetup {
    private TermuxSetup() {}

    static void prepare(Context context) {
        EmbeddedRuntime.get(context).prepare();
    }

    static void open(Context context) {
        EmbeddedRuntime.open(context);
    }

    static void signIn(Context context, String backend) {
        TerminalActivity.openLogin(context, backend);
    }
}
