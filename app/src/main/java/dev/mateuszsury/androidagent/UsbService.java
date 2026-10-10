package dev.mateuszsury.androidagent;

import android.app.*;
import android.content.*;
import android.os.IBinder;

/** Active physical USB connection; Android grants access before this service starts. */
public class UsbService extends Service {
    @Override public void onCreate(){super.onCreate();McpHost.get(this).acquire(this);}
    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i!=null&&"stop".equals(i.getAction())){McpHost.get(this).emergencyStop();stopSelf();return START_NOT_STICKY;}
        NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("usb-device","Urządzenia USB",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,906,new Intent(this,UsbActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,907,new Intent(this,UsbService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,"usb-device").setSmallIcon(R.mipmap.ic_launcher).setContentTitle("androidagent · aktywne USB").setContentText("Otwórz urządzenia lub naciśnij Stop").setContentIntent(open).setOngoing(true).addAction(0,"Stop",stop).build();
        if(android.os.Build.VERSION.SDK_INT>=34)startForeground(906,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(906,n);return START_NOT_STICKY;
    }
    @Override public void onDestroy(){UsbSessions.get(this).closeAll();McpHost.get(this).release(this);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
