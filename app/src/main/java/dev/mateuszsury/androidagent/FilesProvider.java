package dev.mateuszsury.androidagent;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Shares single files with other apps by URI permission: the camera writes the new photo to
 * content://…/capture, and attachments open in a viewer via content://…/a/&lt;id&gt;.
 */
public class FilesProvider extends ContentProvider {
    static final String AUTHORITY = "dev.mateuszsury.androidagent.files";

    static Uri captureUri() {
        return Uri.parse("content://" + AUTHORITY + "/capture");
    }

    static Uri attachmentUri(String id) {
        return Uri.parse("content://" + AUTHORITY + "/a/" + id);
    }

    static Uri dockerUri(String id) {
        return Uri.parse("content://" + AUTHORITY + "/docker/" + id);
    }

    static File captureFile(android.content.Context c) {
        return new File(c.getCacheDir(), "capture.jpg");
    }

    private File fileFor(Uri uri) {
        java.util.List<String> p = uri.getPathSegments();
        if (p.size() == 1 && "capture".equals(p.get(0))) return captureFile(getContext());
        if (p.size() == 2 && "a".equals(p.get(0))) return Attachments.file(getContext(), p.get(1));
        if (p.size() == 2 && "docker".equals(p.get(0))) return DockerVm.exportedFile(getContext(), p.get(1));
        if (p.size() == 2 && "usb".equals(p.get(0))) return UsbArtifacts.file(getContext(), p.get(1));
        return null;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        if (f == null) throw new FileNotFoundException(uri.toString());
        boolean capture = f.equals(captureFile(getContext()));
        if (!capture && !"r".equals(mode)) throw new FileNotFoundException("read-only");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(capture ? mode : "r"));
    }

    @Override
    public String getType(Uri uri) {
        File f = fileFor(uri);
        if (f == null) return null;
        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv")) return "text/plain";
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String order) {
        File f = fileFor(uri);
        if (f == null) return null;
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        String name = f.getParentFile() != null && "attachments".equals(f.getParentFile().getName())
                ? Attachments.name(f.getName()) : "photo.jpg";
        if (uri.getPathSegments().size() == 2 && "docker".equals(uri.getPathSegments().get(0))) {
            try (java.io.InputStream in = new java.io.FileInputStream(new File(f.getPath() + ".json"))) {
                byte[] data = new byte[(int)Math.min(16384, new File(f.getPath() + ".json").length())];
                int count = in.read(data);
                name = new org.json.JSONObject(new String(data, 0, count, java.nio.charset.StandardCharsets.UTF_8)).optString("name", f.getName());
            } catch (Exception ignored) { name = f.getName(); }
        }
        if(uri.getPathSegments().size()==2&&"usb".equals(uri.getPathSegments().get(0))){try{name=UsbArtifacts.metadata(getContext(),f.getName()).optString("name",f.getName());}catch(Exception ignored){name=f.getName();}}
        c.addRow(new Object[]{name, f.length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
