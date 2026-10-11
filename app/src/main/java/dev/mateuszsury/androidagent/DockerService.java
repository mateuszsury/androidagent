package dev.mateuszsury.androidagent;

import android.app.*;
import android.content.Intent;
import android.os.*;

/** Foreground service owns the VM independently of accessibility and screen rotation. */
public class DockerService extends Service {
    private PowerManager.WakeLock wake;
    private boolean started;
    private static final int NOTICE = 730;
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "stop".equals(intent.getAction())) {
            new Thread(() -> { DockerVm.get(this).stop(true); stopSelf(id); }, "docker-stop").start();
            return START_NOT_STICKY;
        }
        if (started) return START_NOT_STICKY;
        started = true;
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("docker", "Środowisko Docker", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, DockerActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, DockerService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification note = new Notification.Builder(this, "docker").setSmallIcon(R.drawable.ms_terminal)
                .setContentTitle("Docker na telefonie").setContentText("Środowisko tworzenia aplikacji jest aktywne")
                .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "Zatrzymaj", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTICE, note, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTICE, note);
        if (wake == null) {
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "androidagent:docker");
            wake.acquire(24 * 60 * 60 * 1000L);
        }
        final int ram = intent == null ? 1024 : intent.getIntExtra("ram", 1024);
        final int cores = intent == null ? 1 : intent.getIntExtra("cores", 1);
        new Thread(() -> {
            try {DockerVm.get(this).boot(ram, cores);}
            catch(IllegalStateException ignored) { /* Stop is still releasing resources. */ }
            finally {if(!DockerVm.get(this).alive())stopSelf(id);}
        }, "docker-vm").start();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        DockerVm vm = DockerVm.get(this);
        if (vm.alive() || "preparing".equals(vm.state) || "booting".equals(vm.state)) vm.emergencyStop();
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(true);
        super.onDestroy();
    }
}
