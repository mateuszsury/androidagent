package dev.mateuszsury.androidagent;

import android.app.*;
import android.content.*;
import android.os.IBinder;

/** Active physical USB connection; Android grants access before this service starts. */
public class UsbService extends Service {
    private static final String GENERATION="usb_session_generation";
    private long acceptedGeneration;
    private int acceptedStartId;
    static Intent startIntent(Context app,long generation){return new Intent(app,UsbService.class).putExtra(GENERATION,generation);}
    // Only starts actually delivered to this instance belong to its cleanup boundary.
    // A newer session can already be registered while its new service start is queued.
    void acceptStart(Intent intent,int startId){
        if(startId<=acceptedStartId)return;
        acceptedStartId=startId;
        if(intent!=null)acceptedGeneration=Math.max(acceptedGeneration,intent.getLongExtra(GENERATION,0));
    }
    @Override public void onCreate(){super.onCreate();McpHost.get(this).acquire(this);}
    @Override public int onStartCommand(Intent i,int flags,int id){
        acceptStart(i,id);
        if(i!=null&&"stop".equals(i.getAction())){McpHost.get(this).emergencyStop();stopSelf();return START_NOT_STICKY;}
        NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("usb-device","Urządzenia USB",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,906,new Intent(this,UsbActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,907,new Intent(this,UsbService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,"usb-device").setSmallIcon(R.mipmap.ic_launcher).setContentTitle("androidagent · aktywne USB").setContentText("Otwórz urządzenia lub naciśnij Stop").setContentIntent(open).setOngoing(true).addAction(0,"Stop",stop).build();
        try{if(android.os.Build.VERSION.SDK_INT>=34)startForeground(906,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(906,n);}
        catch(SecurityException e){android.util.Log.w("androidagentUsb","USB foreground permission was lost before service startup",e);closeAcceptedSessions();stopSelfResult(id);}
        return START_NOT_STICKY;
    }
    private void closeAcceptedSessions(){long generation=acceptedGeneration;Context app=getApplicationContext();new Thread(()->UsbSessions.get(app).closeThrough(generation),"usb-service-stop").start();}
    @Override public void onDestroy(){closeAcceptedSessions();McpHost.get(getApplicationContext()).release(this);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
