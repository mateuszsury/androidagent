---
name: android-app
description: Create, build, install and update a small Android app (APK) directly on this phone, without Gradle or a computer. Use when the user asks to make, build, change or install an Android app.
---

# Building Android apps on this phone

Everything runs in androidagent's embedded terminal on the phone (no separate Termux app). There is no Gradle and no Android Studio. Use plain Java and the Android framework only (no AndroidX, no Kotlin, no external libraries).

The scripts live next to this file. `SKILL_DIR` below means this skill's folder:
`$HOME/.buddy/work/.claude/skills/android-app`.

## 1. Toolchain (first time only)

Run `bash $SKILL_DIR/setup-toolchain.sh`. It installs `openjdk-17 aapt2 d8 apksigner` and puts the Android platform at `~/android-sdk/android-36/android.jar`. Check before reinstalling: it is fast when everything exists.

## 2. Project layout

Create one folder per app in `~/.buddy/work/<appname>/`:

```
AndroidManifest.xml      <manifest package="com.buddyapps.<name>"> … one launcher <activity>
java/com/buddyapps/<name>/MainActivity.java
res/values/strings.xml   (optional; you can also build the UI in code)
app.properties           versionCode=1  versionName=1.0  minSdk=26
```

Rules that avoid the usual failures:
- Package names start with `com.buddyapps.` so they never clash with real apps.
- Put `package="…"` on `<manifest>`, and `android:exported="true"` on the launcher activity.
- Use `android:theme="@android:style/Theme.DeviceDefault"` (no AppCompat).
- Build the UI in Java code (LinearLayout, Button, TextView…) unless the user asks otherwise: it avoids XML layout errors.
- Java 8 syntax is fine (lambdas OK). Don't use `R.layout` unless you created that layout.
- For an icon, reuse `@android:drawable/sym_def_app_icon` or add `res/drawable/ic_launcher.xml` (a vector).

## 3. Build

`bash $SKILL_DIR/build-apk.sh ~/.buddy/work/<appname>` produces `~/.buddy/work/<appname>/build/app.apk`.
If compilation fails, read the error, fix the Java, and build again. Each update must bump `versionCode` in `app.properties`.

## 4. Install (the user taps the final button)

Android's installer and Play Protect dialogs ignore taps from accessibility apps, so you can open
the installer but **the user has to tap Install/Update and confirm (often with a fingerprint)**.
Everything below uses androidagent's own phone tools; no separate Termux app or `termux-open` is involved.

1. Call `install_apk` with the APK path, e.g. `{"path": "<appname>/build/app.apk"}` (relative to
   `~/.buddy/work`). It checks the APK (package, versionCode, signature against the installed app) and
   hands it to Android's package installer, which shows its own confirmation screen.
2. If it returns `status: needs_permission`, androidagent may not install apps yet. The tool already opened
   Android's "Install unknown apps" setting for androidagent: tell the user to turn on **Allow from this
   source** (also reachable in androidagent → Uprawnienia → Instalowanie aplikacji), then call `install_apk`
   again. Never change that setting yourself.
3. If it returns `status: waiting_for_user`, tell the user to tap Install/Update and confirm. Then call
   `install_status` with the package name and `wait_s: 45` (it works without screen control and reports
   `installed`, `cancelled` or `failed` with Android's reason). When screen control is enabled you may use
   `wait_for_install` (package and `min_version_code` for an update) instead. Do not tap Install yourself,
   never claim you did, and never write your own shell loop to wait.
4. A `warning` about a downgrade means: bump `versionCode` and rebuild. A different signing key means the user
   must uninstall the old app first; ask them, don't do it yourself. If the result says a notification was
   posted (androidagent was in the background), ask the user to tap it.
5. If the user cancelled or the installer timed out, ask whether they want to try again instead of waiting longer.
6. Open the new app with `open_app` and `read_screen` to check it works (needs screen control), then report what
   you built, including that the user confirmed the install. To hand the APK to the user instead, use
   `save_to_downloads` or `share_file`.
