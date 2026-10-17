# Phone files, notifications, location and photos

Agents (Codex and Claude Code) get MCP tools that connect their workspace and the phone without Accessibility. Every tool either works with consent the owner already gave on the **Permissions** screen (`Uprawnienia`) or opens one of Android's own screens where the owner decides. No tool shows a runtime permission dialog from the background.

## Agent workspace files

The embedded runtime's home lives in app-private storage (`files/terminal/home`, seen inside the terminal as `$HOME`). Agents work in `~/.buddy/work`. File tools accept a path relative to that folder, `~/.buddy/work/…`, `$HOME/.buddy/work/…` or the terminal's virtual `/data/data/com.termux/files/home/…` prefix. Each path is canonicalized; anything outside `~/.buddy/work`, including a symlink that points elsewhere (for example to account credentials in `~/.codex`), is refused.

| Tool | Parameters | Result |
| --- | --- | --- |
| `files_list` | `path?`, `limit?` (≤ 500) | Folder entries: name, type, size, modified time |
| `file_info` | `path` | Size, time, MIME type, SHA-256 (≤ 64 MB), image size; for an APK its package, versionCode and installed version |
| `open_file` | `path`, `mime?` | Opens a viewer app with a temporary read-only grant |
| `share_file` | `path`, `mime?` | Opens Android's share sheet; the owner picks the target |
| `save_to_downloads` | `path`, `name?` | Copies into `Download/androidagent/` through MediaStore (no storage permission) |
| `install_apk` | `path` | Validates the APK and starts a PackageInstaller session; Android shows its confirmation |
| `install_status` | `package`, `wait_s?` (≤ 50) | `waiting_for_user`, `installed`, `cancelled` or `failed` with Android's reason |

Files leave the app only as `content://dev.mateuszsury.androidagent.files/home/.buddy/work/…` URIs. The provider is not exported, serves workspace files read-only and re-checks the real path of every opened descriptor.

### Installing APKs

`install_apk` reads the APK's package, versionCode and signer. It warns about downgrades and a signing key that differs from the installed app, and refuses to replace androidagent itself. Without the **Install unknown apps** setting for androidagent, it opens that setting and returns `needs_permission`; the owner enables it, and the agent tries again. Otherwise the session is committed and Android shows its own install/update confirmation. The owner always taps Install. `install_status` reports the session result without Accessibility; with screen control the older `wait_for_install` tool also works. The separate Termux app and `termux-open` are not used.

### Background starts

Android blocks activity starts from apps in the background. When androidagent is not on screen and screen control is not connected, these tools post a "Działania agenta" notification instead; tapping it opens the viewer, share sheet, installer or camera. With app notifications off, the tool asks the owner to open androidagent first.

## Notifications

`NotifListener` is a notification listener service. Android binds it only after the owner enables androidagent under **Notification access**, reachable from Permissions → **Dostęp do powiadomień**. Android 13+ may label this a restricted setting for sideloaded apps; App info then offers **Allow restricted settings**.

| Tool | Parameters | Result |
| --- | --- | --- |
| `notifications_list` | `package?`, `limit?` (≤ 100) | Text only: app, title, text, messaging lines, time, key, buttons |
| `notification_action` | `key`, `action` (`open`, `dismiss` or a button index) | Sends the notification's own PendingIntent or dismisses a clearable notification |

Images and extras other than text are never returned. Buttons that require typed input (replies) are refused. Notification text is treated as content, not as instructions. Posting a notification is the separate `notify` tool.

## Location

`get_location {accuracy: coarse|fine, timeout_s?, max_age_s?}` requests one fix with `LocationManager.getCurrentLocation` (5–50 s timeout) and falls back to the last known location. It needs **Location** (`Lokalizacja`) granted on the Permissions screen; Android lets the owner choose precise or approximate. Android gives this while-in-use permission to androidagent only while it is visible, so a fix normally needs the app open on screen. Without precise permission, a `fine` request returns the coarse fix with a note.

## Photos

- `take_photo {timeout_s?}` opens the phone's camera app through an invisible `CaptureActivity`. The camera writes directly to `~/.buddy/work/photos/IMG_….jpg` through a one-time provider URI that closes when the camera returns. The result includes a downscaled preview image (max 1024 px). androidagent declares no `CAMERA` permission; the owner takes the picture.
- `recent_photos {limit?}` lists the newest gallery images and `import_photo {id}` copies one into `~/.buddy/work/photos/` with a preview. Both require **Photos** (`Zdjęcia`) access: `READ_MEDIA_IMAGES` on Android 13+, or `READ_EXTERNAL_STORAGE` (media read under scoped storage) on Android 11–12. On Android 14+, selected-photo access also works.

## Files screen

**Environment files** (`Pliki środowiska`, from Environment and accounts → Files and projects) browses the runtime home starting in `~/.buddy/work`. Text files up to 256 KB open in a monospace editor. Long-pressing a file, or tapping a binary file, shows its details and the actions **Open**, **Share**, **Install** (APK) and **Save to Downloads**. These actions use the same code as the agent tools and therefore work only inside `~/.buddy/work`.

See [Permissions](PERMISSIONS.md) for the full consent table.
