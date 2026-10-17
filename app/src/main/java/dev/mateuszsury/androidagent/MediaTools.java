package dev.mateuszsury.androidagent;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Photos for agents. take_photo opens the phone's camera app through {@link CaptureActivity}; the
 * camera writes straight into ~/.buddy/work/photos via a one-time FilesProvider URI. recent_photos and
 * import_photo read the gallery only after the user allowed photo access on the Permissions screen;
 * no tool ever shows a permission dialog.
 */
final class MediaTools implements ToolProviders.Provider {
    private static final int PREVIEW_PX = 1024;
    private static final long IMPORT_LIMIT = 64L * 1024 * 1024;

    static final class Capture {
        final File target;
        final CountDownLatch done = new CountDownLatch(1);
        volatile int result = Integer.MIN_VALUE;
        volatile String error = "";

        Capture(File target) {
            this.target = target;
        }
    }

    private static final Map<String, Capture> CAPTURES = new ConcurrentHashMap<>();

    /** The provider's write target for a pending capture token, or null. */
    static File captureTarget(String token) {
        Capture c = token == null ? null : CAPTURES.get(token);
        return c == null ? null : c.target;
    }

    /** Called by CaptureActivity when the camera returns; closes the write URI. */
    static void finishCapture(String token, int result, String error) {
        Capture c = token == null ? null : CAPTURES.remove(token);
        if (c == null) return;
        c.result = result;
        c.error = error == null ? "" : error;
        if (c.target.length() == 0) c.target.delete();
        c.done.countDown();
    }

    static String photoPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    static boolean photosAllowed(Context c) {
        if (c.checkSelfPermission(photoPermission()) == PackageManager.PERMISSION_GRANTED) return true;
        return Build.VERSION.SDK_INT >= 34
                && c.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED;
    }

    private static File photosDir(Context c) {
        File dir = new File(FileTools.workspace(c), "photos");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    private static File unique(File dir, String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (safe.isEmpty() || safe.startsWith(".")) safe = "photo" + safe;
        File f = new File(dir, safe);
        int dot = safe.lastIndexOf('.');
        String base = dot > 0 ? safe.substring(0, dot) : safe, ext = dot > 0 ? safe.substring(dot) : "";
        for (int i = 2; f.exists(); i++) f = new File(dir, base + "-" + i + ext);
        return f;
    }

    // ------------------------------------------------------------- take_photo

    static JSONObject takePhoto(Context app, int timeoutS) throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        File target = unique(photosDir(app), "IMG_" + stamp + ".jpg");
        String token = UUID.randomUUID().toString();
        Capture capture = new Capture(target);
        CAPTURES.put(token, capture);
        String how;
        try {
            Intent open = new Intent(app, CaptureActivity.class).putExtra(CaptureActivity.TOKEN, token);
            how = FileTools.launch(app, open, "Agent prosi o zdjęcie", "Dotknij, aby otworzyć aparat.");
        } catch (RuntimeException e) {
            CAPTURES.remove(token);
            throw e;
        }
        String path = FileTools.display(app, target);
        if (!capture.done.await(timeoutS, TimeUnit.SECONDS)) {
            return ToolProviders.result(new JSONObject().put("status", "pending").put("path", path).put("message",
                    ("notification".equals(how) ? "androidagent is in the background, so a notification \"Agent prosi o zdjęcie\" "
                            + "opens the camera; ask the user to tap it. " : "The camera is still open. ")
                            + "When the user takes the photo it is saved at " + path + "; check later with file_info."));
        }
        if (!capture.error.isEmpty()) throw new IllegalStateException(capture.error);
        if (capture.result != android.app.Activity.RESULT_OK || !target.isFile() || target.length() == 0) {
            target.delete();
            return ToolProviders.result(new JSONObject().put("status", "cancelled")
                    .put("message", "The user closed the camera without taking a photo."));
        }
        JSONObject info = new JSONObject().put("status", "saved").put("path", path).put("bytes", target.length());
        return imageResult(app, info, target);
    }

    // ------------------------------------------------------------ gallery

    static JSONObject recentPhotos(Context app, int limit) throws Exception {
        requirePhotos(app);
        String[] projection = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE};
        Bundle args = new Bundle();
        args.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, new String[]{MediaStore.Images.Media.DATE_ADDED});
        args.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        args.putInt(ContentResolver.QUERY_ARG_LIMIT, limit);
        JSONArray items = new JSONArray();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
        try (Cursor c = app.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, args, null)) {
            while (c != null && c.moveToNext() && items.length() < limit) {
                long taken = c.getLong(2), added = c.getLong(3) * 1000L;
                items.put(new JSONObject().put("id", c.getLong(0)).put("name", String.valueOf(c.getString(1)))
                        .put("taken", iso.format(new Date(taken > 0 ? taken : added)))
                        .put("width", c.getInt(4)).put("height", c.getInt(5)).put("bytes", c.getLong(6))
                        .put("album", c.getString(7) == null ? "" : c.getString(7))
                        .put("mime", c.getString(8) == null ? "" : c.getString(8)));
            }
        }
        return ToolProviders.result(new JSONObject().put("photos", items).put("count", items.length())
                .put("hint", "Use import_photo {id} to copy one into the workspace and see it."));
    }

    static JSONObject importPhoto(Context app, long id) throws Exception {
        requirePhotos(app);
        Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
        String name = "photo-" + id + ".jpg";
        long size = 0;
        try (Cursor c = app.getContentResolver().query(uri,
                new String[]{MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE}, null, null, null)) {
            if (c == null || !c.moveToFirst()) throw new IllegalArgumentException("No photo with id " + id + " (see recent_photos).");
            if (c.getString(0) != null) name = c.getString(0);
            size = c.getLong(1);
        }
        if (size > IMPORT_LIMIT) throw new IllegalArgumentException("Photo is larger than 64 MB.");
        File target = unique(photosDir(app), name);
        long bytes = 0;
        try (InputStream in = app.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("Android could not open the photo.");
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) {
                bytes += n;
                if (bytes > IMPORT_LIMIT) throw new IOException("Photo is larger than 64 MB.");
                out.write(b, 0, n);
            }
        } catch (Exception e) {
            target.delete();
            throw e;
        }
        JSONObject info = new JSONObject().put("status", "imported").put("id", id)
                .put("path", FileTools.display(app, target)).put("bytes", bytes);
        return imageResult(app, info, target);
    }

    private static void requirePhotos(Context app) {
        if (!photosAllowed(app))
            throw new SecurityException("Photo access is off. Ask the user to allow Zdjęcia in androidagent > Uprawnienia. "
                    + "This tool never shows a permission dialog itself. take_photo works without this permission.");
    }

    // -------------------------------------------------------------- previews

    /** Text JSON plus a downscaled JPEG preview (max 1024 px, EXIF orientation applied) the model can see. */
    static JSONObject imageResult(Context app, JSONObject info, File photo) throws JSONException {
        byte[] preview = null;
        try {
            preview = preview(photo);
        } catch (Exception | OutOfMemoryError e) {
            info.put("preview_error", String.valueOf(e.getMessage()));
        }
        if (preview != null) info.put("preview", "attached as image (max " + PREVIEW_PX + " px)");
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "text").put("text", info.toString()));
        if (preview != null) content.put(new JSONObject().put("type", "image").put("mimeType", "image/jpeg")
                .put("data", Base64.encodeToString(preview, Base64.NO_WRAP)));
        return new JSONObject().put("content", content).put("structuredContent", info);
    }

    private static byte[] preview(File photo) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(photo.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / (opts.inSampleSize * 2) >= PREVIEW_PX) opts.inSampleSize *= 2;
        Bitmap decoded = BitmapFactory.decodeFile(photo.getPath(), opts);
        if (decoded == null) return null;
        Bitmap scaled = decoded;
        try {
            float scale = Math.min(1f, (float) PREVIEW_PX / Math.max(decoded.getWidth(), decoded.getHeight()));
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            int rotation = new ExifInterface(photo.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            if (rotation == ExifInterface.ORIENTATION_ROTATE_90) m.postRotate(90);
            else if (rotation == ExifInterface.ORIENTATION_ROTATE_180) m.postRotate(180);
            else if (rotation == ExifInterface.ORIENTATION_ROTATE_270) m.postRotate(270);
            if (scale < 1f || !m.isIdentity())
                scaled = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out);
            return out.toByteArray();
        } finally {
            if (scaled != decoded) scaled.recycle();
            decoded.recycle();
        }
    }

    // ------------------------------------------------------------------- MCP

    @Override public JSONArray list(Context app) throws JSONException {
        JSONArray t = new JSONArray();
        t.put(ToolProviders.tool("take_photo", "Ask the user to take a photo with the phone's camera app. The photo is saved "
                        + "in the workspace (~/.buddy/work/photos/…) and returned with a preview image. Waits until the camera "
                        + "closes or timeout_s; on timeout returns status pending with the path to check later. "
                        + "The user decides what to photograph; tell them what you need first.",
                new JSONObject().put("timeout_s", ToolProviders.prop("integer", "Seconds to wait for the camera, 10-50, default 45"))));
        t.put(ToolProviders.tool("recent_photos", "List the newest photos in the phone's gallery (id, name, date, size, album). "
                        + "Needs photo access allowed by the user in androidagent > Uprawnienia > Zdjęcia.",
                new JSONObject().put("limit", ToolProviders.prop("integer", "How many, 1-50, default 10"))));
        t.put(ToolProviders.tool("import_photo", "Copy one gallery photo (id from recent_photos) into ~/.buddy/work/photos and "
                        + "return a preview image. Needs photo access.",
                new JSONObject().put("id", ToolProviders.prop("integer", "Photo id from recent_photos")), "id"));
        return t;
    }

    @Override public boolean handles(String name) {
        return "take_photo".equals(name) || "recent_photos".equals(name) || "import_photo".equals(name);
    }

    @Override public JSONObject call(Context app, String caller, String name, JSONObject a) throws Exception {
        switch (name) {
            case "take_photo": return takePhoto(app, Math.max(10, Math.min(50, a.optInt("timeout_s", 45))));
            case "recent_photos": return recentPhotos(app, Math.max(1, Math.min(50, a.optInt("limit", 10))));
            case "import_photo": return importPhoto(app, a.getLong("id"));
            default: throw new IllegalArgumentException("Unknown tool " + name);
        }
    }
}
