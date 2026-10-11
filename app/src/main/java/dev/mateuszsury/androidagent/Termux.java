package dev.mateuszsury.androidagent;

import android.content.Context;

/** Compatibility adapter for existing chats; commands now stay inside androidagent. */
final class Termux {
    private Termux() {}

    static boolean installed(Context context) {
        return EmbeddedRuntime.get(context).ready();
    }

    static boolean permitted(Context context) {
        // No external command permission: this runtime belongs to our own app UID.
        return EmbeddedRuntime.get(context).ready();
    }

    static void run(Context context, String... args) {
        EmbeddedRuntime.get(context).runHelper(args);
    }

    static void runInTerminal(Context context, String... args) {
        TerminalActivity.openHelper(context, args);
    }
}
