package dev.mateuszsury.androidagent;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.service.notification.NotificationListenerService;

/**
 * Notification access for the notifications_list / notification_action tools. Android binds it only
 * after the user enabled androidagent under Notification access; it keeps no history of its own.
 */
public class NotifListener extends NotificationListenerService {
    private static volatile NotifListener instance;

    static NotifListener get() {
        return instance;
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, NotifListener.class);
    }

    /** The user's consent in Android settings (the service may still be connecting). */
    static boolean granted(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(component(c));
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
    }

    @Override public void onListenerDisconnected() {
        if (instance == this) instance = null;
        super.onListenerDisconnected();
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
