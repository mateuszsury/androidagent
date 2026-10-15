package dev.mateuszsury.androidagent;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Process-start hook (no data): restores autostart background services when the app starts, and
 * retries when a screen becomes visible if Android refused the foreground notification from the
 * background or the embedded runtime was not prepared yet. Needs no Application subclass.
 */
public final class ServicesBoot extends ContentProvider {
    @Override public boolean onCreate() {
        Context context = getContext();
        if (context == null) return true;
        Context app = context.getApplicationContext();
        if (app instanceof Application) ((Application) app).registerActivityLifecycleCallbacks(new Visible(app));
        Thread restore = new Thread(() -> {
            try { ServiceManager.get(app).restore(); }
            catch (RuntimeException e) { android.util.Log.w("androidagentServices", "Autostart failed", e); }
        }, "androidagent-services-restore");
        restore.setDaemon(true);
        restore.start();
        return true;
    }

    private static final class Visible implements Application.ActivityLifecycleCallbacks {
        private final Context app;

        Visible(Context app) {
            this.app = app;
        }

        @Override public void onActivityResumed(Activity activity) {
            ServiceManager.get(app).onAppVisible();
        }

        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return null;
    }

    @Override public String getType(Uri uri) {
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
