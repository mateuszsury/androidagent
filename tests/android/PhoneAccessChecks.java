package dev.mateuszsury.androidagent;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Agent workspace boundary, provider routing and the agent-tool rows of the permission guide. */
final class PhoneAccessChecks {
    static void workspaceBoundaryAndProvider(Context app) throws Exception {
        File home = FileTools.home(app), work = FileTools.workspace(app);
        File secretDir = new File(home, ".phone-access-check");
        secretDir.mkdirs();
        File secret = new File(secretDir, "auth.json");
        write(secret, "{\"token\":\"never\"}");
        File inside = new File(work, "phone-access-check/hello.txt");
        inside.getParentFile().mkdirs();
        write(inside, "hello");
        File link = new File(work, "phone-access-check/escape");
        link.delete();
        Os.symlink(secretDir.getPath(), link.getPath());
        try {
            require(FileTools.resolve(app, "phone-access-check/hello.txt").equals(inside.getCanonicalFile()), "Relative path");
            require(FileTools.resolve(app, "~/.buddy/work/phone-access-check/hello.txt").equals(inside.getCanonicalFile()), "~ path");
            require(FileTools.resolve(app, "$HOME/.buddy/work/phone-access-check/hello.txt").equals(inside.getCanonicalFile()), "$HOME path");
            require(FileTools.resolve(app, EmbeddedRuntime.HOME + "/.buddy/work/phone-access-check/hello.txt")
                    .equals(inside.getCanonicalFile()), "Virtual runtime path");
            refused(app, "phone-access-check/escape/auth.json");
            refused(app, "../../.phone-access-check/auth.json");
            refused(app, "~/.phone-access-check/auth.json");
            refused(app, secret.getAbsolutePath());
            refused(app, "/data/local/tmp");
            Uri good = FileTools.uri(app, inside.getCanonicalFile());
            require(good.toString().startsWith("content://" + FilesProvider.AUTHORITY + "/home/.buddy/work/"), "Provider URI " + good);
            require("text/plain".equals(app.getContentResolver().getType(good)), "Provider MIME");
            try (ParcelFileDescriptor pfd = app.getContentResolver().openFileDescriptor(good, "r")) {
                require(pfd != null && pfd.getStatSize() == 5, "Provider did not serve the workspace file");
            }
            Uri escaped = Uri.parse("content://" + FilesProvider.AUTHORITY + "/home/.buddy/work/phone-access-check/escape/auth.json");
            Uri dotted = Uri.parse("content://" + FilesProvider.AUTHORITY + "/home/.phone-access-check/auth.json");
            for (Uri u : new Uri[]{escaped, dotted}) {
                boolean denied = false;
                try (ParcelFileDescriptor ignored = app.getContentResolver().openFileDescriptor(u, "r")) {
                    denied = ignored == null;
                } catch (FileNotFoundException | SecurityException expected) {
                    denied = true;
                }
                require(denied, "Provider served a file outside the workspace: " + u);
            }
            boolean readOnly = false;
            try (ParcelFileDescriptor ignored = app.getContentResolver().openFileDescriptor(good, "w")) {
                readOnly = ignored == null;
            } catch (FileNotFoundException | SecurityException expected) {
                readOnly = true;
            }
            require(readOnly, "Workspace files must be read-only through the provider");
            JSONObject listing = FileTools.list(app, "phone-access-check", 50);
            JSONArray entries = listing.getJSONArray("entries");
            require(entries.length() == 2 && listing.getString("path").equals("~/.buddy/work/phone-access-check"), "Listing " + listing);
            require(FileTools.info(app, inside.getCanonicalFile()).getLong("bytes") == 5, "file_info size");
            require(ToolProviders.find("install_apk") != null && ToolProviders.find("notifications_list") != null
                    && ToolProviders.find("get_location") != null && ToolProviders.find("take_photo") != null, "Tools not registered");
        } finally {
            link.delete();
            inside.delete();
            inside.getParentFile().delete();
            secret.delete();
            secretDir.delete();
        }
    }

    static void permissionRowsForAgentTools(Instrumentation instrumentation, Activity screen) throws Exception {
        Throwable[] error = {null};
        instrumentation.runOnMainSync(() -> {
            try {
                View root = screen.getWindow().getDecorView();
                for (String label : new String[]{"Dostęp do powiadomień", "Lokalizacja", "Zdjęcia", "Instalowanie aplikacji",
                        "Zmiana ustawień systemu"})
                    require(find(root, label) != null, "Missing permission row " + label);
                View access = find(root, PermissionGuide.read(screen).notificationAccess
                        ? "Ustawienia dostępu do powiadomień" : "Włącz dostęp do powiadomień");
                require(access instanceof Button && ((Button) access).getEllipsize() == null, "Notification access button");
            } catch (Throwable t) {
                error[0] = t;
            }
        });
        if (error[0] != null) throw new AssertionError(error[0]);
    }

    private static void refused(Context app, String path) throws Exception {
        try {
            File f = FileTools.resolve(app, path);
            throw new AssertionError("Path escaped the workspace: " + path + " -> " + f);
        } catch (FileTools.Problem expected) {
            require("outside".equals(expected.code), "Unexpected refusal for " + path + ": " + expected.code);
        }
    }

    private static void write(File f, String s) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static View find(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View result = find(parent.getChildAt(i), value);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
