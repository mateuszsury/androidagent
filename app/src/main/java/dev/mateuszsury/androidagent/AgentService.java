package dev.mateuszsury.androidagent;

import android.app.*;
import android.content.*;
import android.os.IBinder;

/** User-started local tool host; works without screen-control permission. */
public class AgentService extends Service {
    static volatile boolean active;
    @Override public void onCreate(){super.onCreate();active=true;McpHost.get(this).acquire(this);}
    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i!=null&&"stop".equals(i.getAction())){McpHost.get(this).emergencyStop();stopSelf();return START_NOT_STICKY;}
        NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("agent-tools","Narzędzia agentów",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,904,new Intent(this,UsbActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,905,new Intent(this,AgentService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,"agent-tools").setSmallIcon(R.mipmap.ic_launcher).setContentTitle("androidagent · narzędzia dostępne").setContentText("USB i lokalne narzędzia dla Codexa i Claude Code").setContentIntent(open).setOngoing(true).addAction(0,"Stop",stop).build();
        if(android.os.Build.VERSION.SDK_INT>=34)startForeground(904,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(904,n);
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){active=false;McpHost.get(this).release(this);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
