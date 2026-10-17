package dev.mateuszsury.androidagent;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.system.Os;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The agent workspace bridge: lists and inspects files in the embedded runtime's {@code ~/.buddy/work}
 * and hands single files to Android (a viewer, the share sheet, the package installer, Downloads).
 * Every path is canonicalized and must stay inside the workspace, so symlinks cannot reach other
 * private files such as account credentials. The Files screen uses the same code as the MCP tools.
 */
final class FileTools implements ToolProviders.Provider {
    static final String WORK = ".buddy/work";
    static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String ACTION_INSTALL_STATUS = "dev.mateuszsury.androidagent.INSTALL_STATUS";
    /** Per-session secret in the installer's status PendingIntent. The receiver is exported before
     *  Android 13 (no RECEIVER_NOT_EXPORTED), so a broadcast without it is another app's forgery. */
    private static final String EXTRA_INSTALL_SECRET = "dev.mateuszsury.androidagent.extra.INSTALL_SECRET";
    private static final String CHANNEL = "agent-actions";
    private static final long HASH_LIMIT = 64L * 1024 * 1024;
    /** Tap-to-open notices; 7300-7302 are the notify status, the approval notice and PhoneTools' offer. */
    private static final AtomicInteger NOTICE = new AtomicInteger(7400);

    private static final String[] NAMES = {"files_list", "file_info", "open_file", "share_file", "install_apk",
            "install_status", "save_to_downloads"};

    /** A refusal with a stable code; the UI maps codes to Polish, MCP callers read the English message. */
    static final class Problem extends IllegalArgumentException {
        final String code;

        Problem(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    // ------------------------------------------------------------------ paths

    /** The runtime's persistent home; the same layout as {@link EmbeddedRuntime#home()} (files/terminal/home). */
    static File home(Context c) {
        return new File(new File(c.getFilesDir(), "terminal"), "home");
    }

    static File workspace(Context c) {
        File work = new File(home(c), WORK);
        if (!work.isDirectory()) work.mkdirs();
        return work;
    }

    /**
     * Resolves a path as the agent sees it (relative to the workspace, {@code ~/…}, {@code $HOME/…},
     * the runtime's virtual {@code /data/data/com.termux/files/home/…} or a real absolute path) to a
     * canonical file inside the workspace.
     */
    static File resolve(Context c, String path) throws IOException {
        String p = path == null ? "" : path.trim();
        if (p.indexOf('\0') >= 0) throw new Problem("invalid", "Invalid path.");
        File home = home(c), work = workspace(c), f;
        if (p.isEmpty() || ".".equals(p)) f = work;
        else if ("~".equals(p) || "$HOME".equals(p) || EmbeddedRuntime.HOME.equals(p)) f = home;
        else if (p.startsWith("~/")) f = new File(home, p.substring(2));
        else if (p.startsWith("$HOME/")) f = new File(home, p.substring(6));
        else if (p.startsWith(EmbeddedRuntime.HOME + "/")) f = new File(home, p.substring(EmbeddedRuntime.HOME.length() + 1));
        else if (p.startsWith("/")) f = new File(p);
        else f = new File(work, p);
        File canonical = f.getCanonicalFile();
        if (!inside(work, canonical))
            throw new Problem("outside", "Path is outside the agent workspace ~/" + WORK + ": " + path);
        return canonical;
    }

    static boolean inside(File root, File canonical) throws IOException {
        String r = root.getCanonicalPath(), p = canonical.getPath();
        return p.equals(r) || p.startsWith(r + File.separator);
    }

    static boolean inWorkspace(Context c, File f) {
        try {
            return inside(workspace(c), f.getCanonicalFile());
        } catch (IOException e) {
            return false;
        }
    }

    /** "~/.buddy/work/app/build/app.apk" for a file inside the runtime home. */
    static String display(Context c, File f) {
        try {
            String h = home(c).getCanonicalPath(), p = f.getCanonicalPath();
            if (p.equals(h)) return "~";
            if (p.startsWith(h + File.separator)) return "~/" + p.substring(h.length() + 1);
            return p;
        } catch (IOException e) {
            return f.getPath();
        }
    }

    /** content://…files/home/&lt;path below the runtime home&gt;; the provider serves only workspace files. */
    static Uri uri(Context c, File canonical) throws IOException {
        String h = home(c).getCanonicalPath(), p = canonical.getCanonicalPath();
        if (!p.startsWith(h + File.separator)) throw new Problem("outside", "Path is outside the runtime home.");
        Uri.Builder b = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(FilesProvider.AUTHORITY).appendPath("home");
        for (String segment : p.substring(h.length() + 1).split("/")) b.appendPath(segment);
        return b.build();
    }

    /** The provider's view of a home/… URI: an existing regular file inside the workspace, or null. */
    static File providerFile(Context c, List<String> segments) {
        if (segments.isEmpty()) return null;
        StringBuilder rel = new StringBuilder();
        for (String s : segments) {
            if (s.isEmpty() || s.indexOf('\0') >= 0) return null;
            if (rel.length() > 0) rel.append('/');
            rel.append(s);
        }
        try {
            File f = new File(home(c), rel.toString()).getCanonicalFile();
            return inside(workspace(c), f) && f.isFile() ? f : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Opens read-only and re-checks the opened descriptor's real path (no symlink swap after the check). */
    static ParcelFileDescriptor openChecked(Context c, File f) throws IOException {
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        boolean ok = false;
        try {
            File real = new File(Os.readlink("/proc/self/fd/" + pfd.getFd()));
            File work = workspace(c);
            ok = inside(work, real) || real.getPath().startsWith(work.getAbsolutePath() + File.separator);
        } catch (Exception e) {
            ok = false;
        } finally {
            if (!ok) pfd.close();
        }
        if (!ok) throw new FileNotFoundException("File is outside the agent workspace");
        return pfd;
    }

    static InputStream read(Context c, File f) throws IOException {
        return new ParcelFileDescriptor.AutoCloseInputStream(openChecked(c, f));
    }

    static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        switch (ext) {
            case "apk": return APK_MIME;
            case "txt": case "md": case "log": case "csv": case "ini": case "toml": case "yaml": case "yml":
            case "java": case "kt": case "py": case "sh": case "c": case "h": case "cpp": case "rs": case "go":
            case "ts": case "tsx": case "jsx": case "gradle": case "properties": case "conf": case "env":
                return "text/plain";
            case "json": return "application/json";
            case "js": return "text/javascript";
            case "html": case "htm": return "text/html";
            case "css": return "text/css";
            case "xml": return "text/xml";
            case "svg": return "image/svg+xml";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            default:
                String m = ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                return m == null ? "application/octet-stream" : m;
        }
    }

    static boolean isApk(File f) {
        return f.getName().toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    static boolean isImage(File f) {
        return mime(f.getName()).startsWith("image/");
    }

    private static String iso(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(new Date(ms));
    }

    private static void requireFile(Context c, File f) {
        if (!f.exists()) throw new Problem("missing", "No such file: " + display(c, f));
        if (!f.isFile()) throw new Problem("directory", "Not a regular file: " + display(c, f));
    }

    // ------------------------------------------------------------- launching

    /**
     * Starts an activity for a tool. From a visible screen (or while screen control is connected) it
     * opens directly; otherwise Android would silently block the start, so a notification the user
     * taps carries the intent instead. Returns "opened" or "notification".
     */
    static String launch(Context c, Intent intent, String title, String text) {
        if (c instanceof Activity) {
            c.startActivity(intent);
            return "opened";
        }
        Context app = c.getApplicationContext();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (canStartActivities()) {
            app.startActivity(intent);
            return "opened";
        }
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled())
            throw new Problem("background", "androidagent is in the background, so Android does not let it open this "
                    + "screen, and its notifications are off. Ask the user to open androidagent, then call the tool again.");
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Działania agenta", NotificationManager.IMPORTANCE_HIGH));
        int id = NOTICE.getAndIncrement();
        if (id > 7499) NOTICE.set(7400);
        PendingIntent open = PendingIntent.getActivity(app, id, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(id, new Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER).build());
        return "notification";
    }

    /** True when Android allows this process to start activities right now. */
    static boolean canStartActivities() {
        if (BuddyService.get() != null) return true; // a bound accessibility service may start activities
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    private static String launched(String how, String what) {
        return "notification".equals(how)
                ? "androidagent is in the background, so a notification \"" + what + "\" was posted; ask the user to tap it."
                : "Opened " + what + " on the phone.";
    }

    // ------------------------------------------------------------- operations

    static JSONObject entry(Context c, File f) throws JSONException {
        boolean link = java.nio.file.Files.isSymbolicLink(f.toPath());
        JSONObject o = new JSONObject().put("name", f.getName())
                .put("type", link ? "link" : f.isDirectory() ? "dir" : f.isFile() ? "file" : "other");
        if (link) o.put("target_in_workspace", inWorkspace(c, f));
        if (f.isFile()) o.put("bytes", f.length());
        if (f.exists()) o.put("modified", iso(f.lastModified()));
        return o;
    }

    static File[] sorted(File dir) {
        File[] entries = dir.listFiles();
        if (entries == null) return new File[0];
        Arrays.sort(entries, (a, b) -> a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1)
                : a.getName().compareToIgnoreCase(b.getName()));
        return entries;
    }

    static JSONObject list(Context c, String path, int limit) throws Exception {
        File dir = resolve(c, path);
        if (!dir.isDirectory()) throw new Problem("not_directory", "Not a directory: " + display(c, dir));
        File[] entries = sorted(dir);
        JSONArray items = new JSONArray();
        for (int i = 0; i < entries.length && i < limit; i++) items.put(entry(c, entries[i]));
        return new JSONObject().put("path", display(c, dir)).put("entries", items).put("total", entries.length)
                .put("truncated", entries.length > limit);
    }

    static JSONObject info(Context c, File f) throws Exception {
        if (!f.exists()) throw new Problem("missing", "No such file: " + display(c, f));
        JSONObject o = entry(c, f).put("path", display(c, f));
        if (f.isDirectory()) {
            String[] children = f.list();
            o.put("entries", children == null ? 0 : children.length);
            return o;
        }
        o.put("mime", mime(f.getName()));
        if (f.length() <= HASH_LIMIT) o.put("sha256", sha256(c, f));
        if (isApk(f)) {
            try {
                o.put("apk", apkInfo(c, f));
            } catch (Problem e) {
                o.put("apk_error", e.getMessage());
            }
        } else if (isImage(f)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            if (bounds.outWidth > 0) o.put("width", bounds.outWidth).put("height", bounds.outHeight);
        }
        return o;
    }

    private static String sha256(Context c, File f) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = read(c, f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) digest.update(b, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte value : digest.digest()) s.append(String.format(Locale.ROOT, "%02x", value & 255));
        return s.toString();
    }

    static JSONObject open(Context c, File f, String mime) throws Exception {
        requireFile(c, f);
        if (isApk(f)) throw new Problem("use_install", "This is an APK; use install_apk to install it.");
        String type = mime == null || mime.trim().isEmpty() ? mime(f.getName()) : mime.trim();
        Uri uri = uri(c, f);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri(f.getName(), uri));
        if (view.resolveActivity(c.getPackageManager()) == null)
            throw new Problem("no_app", "No app on this phone can open " + type + " files. Try share_file or save_to_downloads.");
        String how = launch(c, view, "Otwórz " + f.getName(), "Agent chce pokazać plik. Dotknij, aby otworzyć.");
        return new JSONObject().put("path", display(c, f)).put("mime", type).put("status", how)
                .put("message", launched(how, f.getName()));
    }

    static JSONObject share(Context c, File f, String mime) throws Exception {
        requireFile(c, f);
        String type = mime == null || mime.trim().isEmpty() ? mime(f.getName()) : mime.trim();
        Uri uri = uri(c, f);
        Intent send = new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(f.getName(), uri));
        Intent chooser = Intent.createChooser(send, "Udostępnij " + f.getName());
        String how = launch(c, chooser, "Udostępnij " + f.getName(), "Agent przygotował plik do udostępnienia. Dotknij, aby wybrać aplikację.");
        return new JSONObject().put("path", display(c, f)).put("mime", type).put("status", how)
                .put("message", "notification".equals(how) ? launched(how, "Udostępnij " + f.getName())
                        : "The Android share sheet is open; the user picks the app. Nothing is sent until they do.");
    }

    static JSONObject saveToDownloads(Context c, File f, String name) throws Exception {
        requireFile(c, f);
        String target = name == null || name.trim().isEmpty() ? f.getName() : name.trim();
        if (target.contains("/") || target.startsWith(".") || target.length() > 120)
            throw new Problem("invalid", "Invalid file name for Downloads: " + target);
        ContentResolver resolver = c.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, target);
        // MediaStore appends the extension of a MIME type that does not match the name ("a.md" as
        // text/plain becomes "a.md.txt"), so use Android's own mapping or the neutral binary type.
        int dot = target.lastIndexOf('.');
        String known = dot < 0 ? null : MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(target.substring(dot + 1).toLowerCase(Locale.ROOT));
        values.put(MediaStore.MediaColumns.MIME_TYPE, known != null ? known
                : isApk(new File(target)) ? APK_MIME : "application/octet-stream");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/androidagent");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Android refused to create the file in Downloads.");
        long bytes = 0;
        try {
            try (InputStream in = read(c, f); OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("Downloads is not writable.");
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) != -1) {
                    out.write(b, 0, n);
                    bytes += n;
                }
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        } catch (Exception e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        String saved = target;
        try (Cursor cursor = resolver.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) saved = cursor.getString(0);
        }
        return new JSONObject().put("path", display(c, f)).put("saved", Environment.DIRECTORY_DOWNLOADS + "/androidagent/" + saved)
                .put("bytes", bytes).put("uri", uri.toString())
                .put("message", "Saved to Download/androidagent/" + saved + "; it is visible in the Files app.");
    }

    // ---------------------------------------------------------------- install

    static final class Install {
        final int session;
        final String pkg;
        final long version;
        final String secret = java.util.UUID.randomUUID().toString();
        volatile String state = "committed", message = "", how = "";
        final CountDownLatch prompted = new CountDownLatch(1), finished = new CountDownLatch(1);

        Install(int session, String pkg, long version) {
            this.session = session;
            this.pkg = pkg;
            this.version = version;
        }
    }

    private static final Map<String, Install> INSTALLS = new ConcurrentHashMap<>();
    private static final Map<Integer, Install> SESSIONS = new ConcurrentHashMap<>();
    private static BroadcastReceiver receiver;

    static long installedVersion(PackageManager pm, String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    static JSONObject apkInfo(Context c, File f) throws JSONException {
        requireFile(c, f);
        PackageManager pm = c.getPackageManager();
        PackageInfo p = pm.getPackageArchiveInfo(f.getPath(), 0);
        if (p == null) throw new Problem("not_apk", "Not a valid APK (Android could not read its manifest): " + display(c, f));
        JSONObject o = new JSONObject().put("path", display(c, f)).put("package", p.packageName)
                .put("version_code", p.getLongVersionCode()).put("version_name", p.versionName == null ? "" : p.versionName)
                .put("bytes", f.length());
        if (p.applicationInfo != null) {
            p.applicationInfo.sourceDir = f.getPath();
            p.applicationInfo.publicSourceDir = f.getPath();
            try {
                o.put("label", String.valueOf(p.applicationInfo.loadLabel(pm)));
            } catch (RuntimeException ignored) {
            }
        }
        long installed = installedVersion(pm, p.packageName);
        o.put("installed_version_code", installed >= 0 ? (Object) installed : JSONObject.NULL);
        if (installed > p.getLongVersionCode())
            o.put("warning", "Older than the installed version " + installed + "; Android refuses downgrades. Bump versionCode.");
        else if (installed >= 0 && !sameSigner(pm, f, p.packageName))
            o.put("warning", "Signed with a different key than the installed app; Android will refuse the update. "
                    + "The user would have to uninstall the old app first (ask them; never do it yourself).");
        return o;
    }

    private static boolean sameSigner(PackageManager pm, File apk, String pkg) {
        try {
            PackageInfo archive = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo installed = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            if (archive == null || archive.signingInfo == null || installed.signingInfo == null) return true;
            Signature[] a = archive.signingInfo.getApkContentsSigners(), b = installed.signingInfo.getApkContentsSigners();
            return a != null && b != null && new java.util.HashSet<>(Arrays.asList(a)).equals(new java.util.HashSet<>(Arrays.asList(b)));
        } catch (Exception e) {
            return true; // unknown: let Android decide
        }
    }

    /** Copies the APK into a PackageInstaller session; Android shows its own confirmation, the user taps Install. */
    static JSONObject install(Context c, File apk) throws Exception {
        Context app = c.getApplicationContext();
        JSONObject out = apkInfo(app, apk);
        String pkg = out.getString("package");
        if (pkg.equals(app.getPackageName()))
            throw new Problem("self", "Refusing to replace androidagent itself.");
        PackageManager pm = app.getPackageManager();
        if (!pm.canRequestPackageInstalls()) {
            String how = launch(c, PermissionGuide.installAppsIntent(app), "Zezwól na instalowanie aplikacji",
                    "Agent chce zainstalować " + apk.getName() + ". Dotknij, aby otworzyć ustawienia.");
            return out.put("status", "needs_permission").put("settings", how).put("message",
                    "Android does not yet allow androidagent to install apps. "
                            + ("notification".equals(how) ? "A notification for the 'Install unknown apps' setting was posted. "
                            : "The 'Install unknown apps' setting for androidagent is open. ")
                            + "Ask the user to turn on 'Allow from this source' (also in androidagent > Uprawnienia > "
                            + "Instalowanie aplikacji), then call install_apk again. Never change that setting yourself.");
        }
        ensureReceiver(app);
        PackageInstaller installer = pm.getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(pkg);
        params.setSize(apk.length());
        int id = installer.createSession(params);
        Install record = new Install(id, pkg, out.getLong("version_code"));
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = read(app, apk); OutputStream write = session.openWrite("base.apk", 0, apk.length())) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) != -1) write.write(b, 0, n);
                session.fsync(write);
            }
            Intent status = new Intent(ACTION_INSTALL_STATUS).setPackage(app.getPackageName())
                    .putExtra(EXTRA_INSTALL_SECRET, record.secret);
            PendingIntent callback = PendingIntent.getBroadcast(app, id, status,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            SESSIONS.put(id, record);
            INSTALLS.put(pkg, record);
            session.commit(callback.getIntentSender());
        } catch (Exception e) {
            SESSIONS.remove(id);
            try {
                installer.abandonSession(id);
            } catch (Exception ignored) {
            }
            throw e;
        }
        record.prompted.await(12, TimeUnit.SECONDS);
        return describe(out.put("session", id), record);
    }

    private static JSONObject describe(JSONObject out, Install r) throws JSONException {
        out.put("status", r.state);
        if (!r.message.isEmpty()) out.put("android_message", r.message);
        switch (r.state) {
            case "waiting_for_user":
                return out.put("message", ("notification".equals(r.how)
                        ? "androidagent is in the background, so Android's installer waits behind a notification "
                        + "\"Zainstaluj aplikację\"; ask the user to tap it. "
                        : "Android's installer is open. ")
                        + "The user must tap Install/Update and confirm; never tap it yourself or claim you did. "
                        + "Then call install_status {package, wait_s: 45} (or wait_for_install when screen control is on).");
            case "installed":
                return out.put("message", "Installed " + r.pkg + ". The user confirmed the install.");
            case "cancelled":
                return out.put("message", "The user cancelled the installation. Ask whether they want to try again.");
            case "failed":
                String m = r.message;
                String hint = m.contains("UPDATE_INCOMPATIBLE") || m.contains("signatures")
                        ? " The installed app is signed with a different key; the user must uninstall it first (ask them)."
                        : m.contains("VERSION_DOWNGRADE") ? " Bump versionCode above the installed one." : "";
                return out.put("message", "Android did not install the app: " + m + hint);
            default:
                return out.put("message", "Android is still preparing its installer; call install_status in a few seconds.");
        }
    }

    static JSONObject installStatus(Context c, String pkg, int waitSeconds) throws Exception {
        Install r = INSTALLS.get(pkg);
        PackageManager pm = c.getPackageManager();
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        while (r != null && ("waiting_for_user".equals(r.state) || "committed".equals(r.state))
                && installedVersion(pm, pkg) < r.version && System.currentTimeMillis() < deadline) {
            r.finished.await(1, TimeUnit.SECONDS);
        }
        long installed = installedVersion(pm, pkg);
        JSONObject out = new JSONObject().put("package", pkg)
                .put("installed_version_code", installed >= 0 ? (Object) installed : JSONObject.NULL);
        if (r == null)
            return out.put("status", installed >= 0 ? "installed" : "unknown").put("message",
                    installed >= 0 ? "The package is installed." : "No install_apk session for this package in this app run.");
        if (installed >= r.version && !"installed".equals(r.state)) r.state = "installed";
        out.put("version_code", r.version);
        describe(out, r);
        if ("waiting_for_user".equals(r.state))
            out.put("message", "Still waiting for the user to tap Install/Update in Android's installer. "
                    + "Ask them whether they want to install it instead of waiting longer.");
        return out;
    }

    private static synchronized void ensureReceiver(Context app) {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                onInstallStatus(context.getApplicationContext(), intent);
            }
        };
        app.registerReceiver(receiver, new IntentFilter(ACTION_INSTALL_STATUS),
                Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
    }

    private static void onInstallStatus(Context app, Intent intent) {
        Install r = SESSIONS.get(intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1));
        // Only Android's installer holds our PendingIntent (and so the secret). A forged broadcast could
        // otherwise make androidagent start any EXTRA_INTENT, e.g. with URI grants to its own provider.
        if (r == null || !r.secret.equals(intent.getStringExtra(EXTRA_INSTALL_SECRET))) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            try {
                if (confirm == null) throw new IllegalStateException("Android returned no installer screen.");
                r.how = launch(app, confirm, "Zainstaluj aplikację", "Agent przygotował " + r.pkg + ". Dotknij, aby otworzyć instalator.");
                r.state = "waiting_for_user";
            } catch (RuntimeException e) {
                r.state = "failed";
                r.message = String.valueOf(e.getMessage());
                r.finished.countDown();
            }
            r.prompted.countDown();
            return;
        }
        r.state = status == PackageInstaller.STATUS_SUCCESS ? "installed"
                : status == PackageInstaller.STATUS_FAILURE_ABORTED ? "cancelled" : "failed";
        r.message = message == null ? "" : message;
        SESSIONS.remove(r.session);
        r.prompted.countDown();
        r.finished.countDown();
    }

    // ------------------------------------------------------------------- MCP

    private static JSONObject path(String what) throws JSONException {
        return ToolProviders.prop("string", what + " Relative to the agent workspace ~/.buddy/work (e.g. myapp/build/app.apk), "
                + "or ~/.buddy/work/… / $HOME/.buddy/work/… as seen in the terminal. Files outside the workspace are refused.");
    }

    @Override public JSONArray list(Context app) throws JSONException {
        JSONArray t = new JSONArray();
        t.put(ToolProviders.tool("files_list", "List a folder of the agent workspace ~/.buddy/work (names, type, size, "
                        + "modified time; folders first). Works without Accessibility.",
                new JSONObject().put("path", path("Folder; empty for the workspace root."))
                        .put("limit", ToolProviders.prop("integer", "Maximum entries, default 200, max 500"))));
        t.put(ToolProviders.tool("file_info", "Details of one workspace file: size, time, MIME type, SHA-256 (files up to 64 MB), "
                        + "image size, and for an APK its package, versionCode and the installed version.",
                new JSONObject().put("path", path("File or folder.")), "path"));
        t.put(ToolProviders.tool("open_file", "Open a workspace file in a viewer app on the phone (PDF, image, HTML, text...) "
                        + "with a temporary read-only grant. For APKs use install_apk.",
                new JSONObject().put("path", path("File to open."))
                        .put("mime", ToolProviders.prop("string", "Optional MIME type override, e.g. text/plain")), "path"));
        t.put(ToolProviders.tool("share_file", "Open Android's share sheet for a workspace file; the user picks the target app "
                        + "(messenger, mail, Drive...). Nothing is sent until the user chooses.",
                new JSONObject().put("path", path("File to share."))
                        .put("mime", ToolProviders.prop("string", "Optional MIME type override")), "path"));
        t.put(ToolProviders.tool("install_apk", "Install or update an APK from the workspace with Android's package installer. "
                        + "Checks the APK (package, versionCode, signature vs the installed app). If androidagent may not "
                        + "install apps yet, opens that Android setting and returns status needs_permission. Otherwise Android "
                        + "shows its confirmation and the USER taps Install/Update; then call install_status (or wait_for_install).",
                new JSONObject().put("path", path("The .apk file, e.g. myapp/build/app.apk.")), "path"));
        t.put(ToolProviders.tool("install_status", "Result of the last install_apk for a package: waiting_for_user, installed, "
                        + "cancelled or failed (with Android's reason), plus the installed versionCode. Works without Accessibility.",
                new JSONObject().put("package", ToolProviders.prop("string", "Package name, e.g. com.buddyapps.flappy"))
                        .put("wait_s", ToolProviders.prop("integer", "Wait up to this many seconds for the user to finish (0-50, default 0)")),
                "package"));
        t.put(ToolProviders.tool("save_to_downloads", "Copy a workspace file to the phone's public Download/androidagent folder "
                        + "so the user can find it in the Files app. Needs no storage permission.",
                new JSONObject().put("path", path("File to copy."))
                        .put("name", ToolProviders.prop("string", "Optional file name in Downloads")), "path"));
        return t;
    }

    @Override public boolean handles(String name) {
        for (String n : NAMES) if (n.equals(name)) return true;
        return false;
    }

    @Override public JSONObject call(Context app, String caller, String name, JSONObject a) throws Exception {
        switch (name) {
            case "files_list":
                return ToolProviders.result(list(app, a.optString("path", ""), Math.max(1, Math.min(500, a.optInt("limit", 200)))));
            case "file_info":
                return ToolProviders.result(info(app, resolve(app, a.getString("path"))));
            case "open_file":
                return ToolProviders.result(open(app, resolve(app, a.getString("path")), a.optString("mime", "")));
            case "share_file":
                return ToolProviders.result(share(app, resolve(app, a.getString("path")), a.optString("mime", "")));
            case "install_apk":
                return ToolProviders.result(install(app, resolve(app, a.getString("path"))));
            case "install_status":
                return ToolProviders.result(installStatus(app, a.getString("package"), Math.max(0, Math.min(50, a.optInt("wait_s", 0)))));
            case "save_to_downloads":
                return ToolProviders.result(saveToDownloads(app, resolve(app, a.getString("path")), a.optString("name", "")));
            default:
                throw new IllegalArgumentException("Unknown tool " + name);
        }
    }

    /** Polish text for a refusal shown on the Files screen. */
    static String polish(Exception e) {
        String code = e instanceof Problem ? ((Problem) e).code : "";
        switch (code) {
            case "outside": return "Ten plik jest poza katalogiem ~/.buddy/work.";
            case "missing": return "Plik już nie istnieje.";
            case "no_app": return "Żadna aplikacja w telefonie nie otworzy tego pliku. Spróbuj Udostępnij albo Zapisz w Pobranych.";
            case "not_apk": return "To nie jest poprawny plik APK.";
            case "self": return "Nie można zastąpić androidagent z poziomu aplikacji.";
            case "use_install": return "To plik APK. Użyj przycisku Zainstaluj.";
            case "invalid": return "Nieprawidłowa nazwa pliku.";
            default:
                String m = e.getMessage();
                return m == null || m.isEmpty() ? "Nie udało się wykonać operacji." : m;
        }
    }

    static Map<String, String> statusLabels() {
        Map<String, String> m = new HashMap<>();
        m.put("needs_permission", "Zezwól androidagent na instalowanie aplikacji, a potem spróbuj ponownie.");
        m.put("waiting_for_user", "Potwierdź instalację w oknie Androida.");
        m.put("installed", "Aplikacja zainstalowana.");
        m.put("cancelled", "Instalacja anulowana.");
        m.put("failed", "Android nie zainstalował aplikacji.");
        m.put("committed", "Android przygotowuje instalator…");
        return m;
    }
}
