package dev.mateuszsury.androidagent;

/** Single source of the app version; read by build.sh and app/build.gradle.kts. */
final class BuildInfo {
    static final String VERSION = "0.3.0";
    static final int VERSION_CODE = 300;

    private BuildInfo() {
    }
}
