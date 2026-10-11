package dev.mateuszsury.androidagent;

import android.app.*;
import android.content.*;
import android.os.*;

/** Foreground owner of initialization/downloads and local client processes. */
public final class RuntimeService extends Service {
    private PowerManager.WakeLock wake;
    private int lastStartId;
    @Override public void onCreate(){super.onCreate();McpHost.get(this).acquire(this);wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"androidagent:terminal");wake.acquire(45*60*1000L);}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        lastStartId=id;
        NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("terminal-runtime","Wbudowane środowisko",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,912,new Intent(this,RuntimeActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notice=new Notification.Builder(this,"terminal-runtime").setSmallIcon(R.mipmap.ic_launcher).setContentTitle("androidagent · środowisko terminalowe").setContentText("Przygotowanie narzędzi lub działanie klienta").setOngoing(true).setContentIntent(open).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(912,notice,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(912,notice);
        if(intent!=null)EmbeddedRuntime.get(this).enqueue(intent,()->{if(EmbeddedRuntime.get(this).activeJobs()==0)stopSelfResult(lastStartId);});else stopSelfResult(id);
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){if(wake!=null&&wake.isHeld())wake.release();McpHost.get(this).release(this);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
