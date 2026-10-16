package dev.mateuszsury.androidagent;

import android.app.*;
import android.content.Intent;
import android.os.*;

/** Foreground service owns the VM independently of accessibility and screen rotation. */
public class DockerService extends Service {
    private PowerManager.WakeLock wake;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread worker;
    private Intent retry;
    private boolean stopping, destroyed;
    private int latestStartId;
    private int workerStoppedEpoch;
    private static final int NOTICE = 730;
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        latestStartId = id;
        if (intent != null && "stop".equals(intent.getAction())) {
            retry = null;
            if (stopping) return START_NOT_STICKY;
            stopping = true;
            new Thread(() -> {
                try { DockerVm.get(this).stop(true); }
                finally { main.post(() -> { if (!destroyed) stopSelf(latestStartId); }); }
            }, "docker-stop").start();
            return START_NOT_STICKY;
        }
        if (stopping) return START_NOT_STICKY;
        // Claim the worker on the main thread before it can start preparing.
        // Repeated intents must not launch a short-lived duplicate that stops
        // the service while the actual owner is still unpacking its disk.
        if (worker != null) {
            DockerVm current = DockerVm.get(this);
            if (intent != null && ("error".equals(current.state)
                    || ("stopped".equals(current.state) && current.stoppedGeneration() != workerStoppedEpoch)))
                retry = new Intent(intent);
            return START_NOT_STICKY;
        }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("docker", "Środowisko Docker", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, DockerActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, DockerService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification note = new Notification.Builder(this, "docker").setSmallIcon(R.drawable.ms_terminal)
                .setContentTitle("Docker na telefonie").setContentText("Środowisko tworzenia aplikacji jest aktywne")
                .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "Zatrzymaj", stop).build()).build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTICE, note, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTICE, note);
        } catch (RuntimeException denied) {
            // Android 12+ can refuse a foreground start that an agent requested
            // while androidagent was in the background. Never boot without it.
            DockerVm.get(this).startRejected("Android nie pozwolił uruchomić środowiska w tle. Otwórz androidagent → Docker i uruchom je tam.");
            stopSelf(id);
            return START_NOT_STICKY;
        }
        if (wake == null) {
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "androidagent:docker");
            wake.acquire(24 * 60 * 60 * 1000L);
        }
        final int ram = intent == null ? 1024 : intent.getIntExtra("ram", 1024);
        final int cores = intent == null ? 1 : intent.getIntExtra("cores", 1);
        workerStoppedEpoch = DockerVm.get(this).stoppedGeneration();
        worker = new Thread(() -> {
            Thread owner = Thread.currentThread();
            try {DockerVm.get(this).boot(ram, cores);}
            catch(IllegalStateException ignored) { /* Stop is still releasing resources. */ }
            finally {
                main.post(() -> {
                    if (destroyed || worker != owner) return;
                    worker = null;
                    if (stopping) return;
                    if (retry != null) {
                        Intent next = retry; retry = null;
                        onStartCommand(next, 0, latestStartId);
                    } else if (!DockerVm.get(this).alive()) stopSelf(latestStartId);
                });
            }
        }, "docker-vm");
        worker.start();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        destroyed = true; retry = null;
        DockerVm vm = DockerVm.get(this);
        if (vm.alive() || "preparing".equals(vm.state) || "booting".equals(vm.state)) vm.emergencyStop();
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(true);
        super.onDestroy();
    }
}
