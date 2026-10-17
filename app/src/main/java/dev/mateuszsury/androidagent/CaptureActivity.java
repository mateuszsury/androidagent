package dev.mateuszsury.androidagent;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

/**
 * Invisible trampoline for the take_photo tool: opens the camera app with a one-time write URI into
 * the agent workspace and reports the result back to {@link MediaTools}. It holds no permission of its
 * own (no CAMERA permission is declared, so the camera app takes the picture).
 */
public class CaptureActivity extends Activity {
    static final String TOKEN = "token";
    private static final int CAMERA = 31;
    private String token;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        token = getIntent().getStringExtra(TOKEN);
        if (state != null) return; // the camera is already open; wait for its result
        if (MediaTools.captureTarget(token) == null) {
            finish();
            return;
        }
        Uri out = FilesProvider.captureUri(token);
        Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, out)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        camera.setClipData(ClipData.newRawUri("photo", out));
        try {
            startActivityForResult(camera, CAMERA);
        } catch (ActivityNotFoundException | SecurityException e) {
            MediaTools.finishCapture(token, RESULT_CANCELED, "No camera app on this phone can take a photo for androidagent.");
            finish();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CAMERA) return;
        MediaTools.finishCapture(token, result, null);
        finish();
    }

    @Override protected void onDestroy() {
        // Leaving without a result (e.g. the task was removed) also closes the write URI.
        if (isFinishing() && !isChangingConfigurations()) MediaTools.finishCapture(token, RESULT_CANCELED, null);
        super.onDestroy();
    }
}
