package dev.mateuszsury.androidagent;

import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.os.Build;
import com.hoho.android.usbserial.driver.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Hardware generations and exclusive leases. Only the foreground UI requests Android permission. */
final class UsbSessions {
    static final String PERMISSION="dev.mateuszsury.androidagent.USB_PERMISSION";
    private static final java.util.concurrent.atomic.AtomicLong GENERATIONS=new java.util.concurrent.atomic.AtomicLong();
    private static UsbSessions instance;
    static synchronized UsbSessions get(Context c){if(instance==null)instance=new UsbSessions(c.getApplicationContext());return instance;}
    final Context app;final UsbManager manager;
    final Map<String,Device> devices=new LinkedHashMap<>();final Map<String,Session> sessions=new LinkedHashMap<>();
    static final class Device{final String id=UUID.randomUUID().toString();final UsbDevice usb;Device(UsbDevice d){usb=d;}}
    static final class Session implements Closeable {
        final long generation=GENERATIONS.incrementAndGet();
        final String id=UUID.randomUUID().toString(),device,profile;String owner;final UsbDeviceConnection connection;SerialTransport serial;AdbClient adb;volatile String task="";volatile int slot;volatile long redirection;volatile boolean closed;volatile String qmpId="";
        Session(String d,String p,String o,UsbDeviceConnection c){device=d;profile=p;owner=o;connection=c;}
        synchronized JSONObject json()throws JSONException{return new JSONObject().put("id",id).put("device",device).put("profile",profile).put("owner",owner).put("task",task).put("connected",!closed&&(serial==null||!serial.closed)&&(adb==null||!adb.closed)&&(redirection==0||"active".equals(UsbNative.state(redirection)))).put("rx",serial==null?0:serial.received.end()).put("tx",serial==null?0:serial.tx).put("error",serial==null?"":serial.error);}
        @Override public synchronized void close(){closed=true;UsbSerialBridge.revoke(this);if(serial!=null)serial.close();if(adb!=null)adb.close();if(redirection!=0){UsbNative.stop(redirection);redirection=0;}if(connection!=null)connection.close();}
    }
    private UsbSessions(Context c){app=c;manager=c.getSystemService(UsbManager.class);IntentFilter f=new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);f.addAction(PERMISSION);BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){synchronized(UsbSessions.this){if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())){UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);if(d!=null){for(Device entry:new ArrayList<>(devices.values()))if(entry.usb.getDeviceName().equals(d.getDeviceName())){closeDevice(entry.id);devices.remove(entry.id);}}}refresh();}}};if(Build.VERSION.SDK_INT>=33)c.registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else c.registerReceiver(receiver,f);}
    synchronized void refresh(){Map<String,UsbDevice> current=manager.getDeviceList();for(Device d:new ArrayList<>(devices.values()))if(!current.containsKey(d.usb.getDeviceName())){closeDevice(d.id);devices.remove(d.id);}for(UsbDevice d:current.values())if(devices.values().stream().noneMatch(x->x.usb.getDeviceName().equals(d.getDeviceName()))) {Device entry=new Device(d);devices.put(entry.id,entry);}}
    synchronized Device device(String id){refresh();Device d=devices.get(id);if(d==null)throw new IllegalArgumentException("Device disconnected or invalid generation");return d;}
    synchronized JSONArray list()throws JSONException{
        refresh();JSONArray out=new JSONArray();for(Device entry:devices.values()){UsbDevice d=entry.usb;JSONArray interfaces=new JSONArray();for(int i=0;i<d.getInterfaceCount();i++){UsbInterface f=d.getInterface(i);JSONArray endpoints=new JSONArray();for(int e=0;e<f.getEndpointCount();e++){UsbEndpoint ep=f.getEndpoint(e);endpoints.put(new JSONObject().put("address",ep.getAddress()).put("type",ep.getType()).put("packet_size",ep.getMaxPacketSize()));}interfaces.put(new JSONObject().put("id",f.getId()).put("class",f.getInterfaceClass()).put("subclass",f.getInterfaceSubclass()).put("protocol",f.getInterfaceProtocol()).put("endpoints",endpoints));}
            JSONObject o=new JSONObject().put("id",entry.id).put("vid",d.getVendorId()).put("pid",d.getProductId()).put("permission",manager.hasPermission(d)).put("interfaces",interfaces).put("serial_supported",UsbSerialProber.getDefaultProber().probeDevice(d)!=null);
            try{o.put("name",d.getProductName());if(manager.hasPermission(d))o.put("serial",d.getSerialNumber());}catch(SecurityException ignored){}out.put(o);}return out;
    }
    void permission(Context foreground,String id){Device d=device(id);PendingIntent pending=PendingIntent.getBroadcast(foreground,Math.abs(id.hashCode()),new Intent(PERMISSION).setPackage(app.getPackageName()),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);manager.requestPermission(d.usb,pending);}
    synchronized Session open(String device,String profile,String owner,int port)throws Exception{
        McpHost.get(app).requireReady();
        Device d=device(device);if(!manager.hasPermission(d.usb))throw new SecurityException("permission_required: open the USB panel and grant Android access");
        if(sessions.values().stream().anyMatch(s->s.device.equals(device)))throw new IllegalStateException("Device busy; close or transfer its existing session");
        if("docker".equals(profile)){for(int i=0;i<d.usb.getInterfaceCount();i++)if(d.usb.getInterface(i).getInterfaceClass()==8)throw new IllegalStateException("Mounted storage uses the document picker and file import/export; raw mass-storage takeover is disabled");}
        UsbDeviceConnection c=manager.openDevice(d.usb);if(c==null)throw new IOException("Android could not open this USB device");Session s=new Session(device,profile,owner,c);
        try{if("serial".equals(profile)){UsbSerialDriver driver=UsbSerialProber.getDefaultProber().probeDevice(d.usb);if(driver==null||port<0||port>=driver.getPorts().size())throw new IOException("Unsupported serial device or port");s.serial=new SerialTransport(driver.getPorts().get(port),c);}else if("adb".equals(profile)){s.adb=new AdbClient(app,new AdbClient.UsbTransport(d.usb,c));}else if(!"docker".equals(profile))throw new IllegalArgumentException("Unknown USB profile");sessions.put(s.id,s);app.startForegroundService(UsbService.startIntent(app,s.generation));return s;}catch(Exception e){sessions.remove(s.id);s.close();throw e;}
    }
    synchronized Session owned(String owner,String id){Session s=sessions.get(id);if(s==null||!s.owner.equals(owner))throw new SecurityException("USB session is not assigned to this caller");return s;}
    synchronized void authorizeTask(String owner,String task){if(task.isEmpty())return;for(Session s:sessions.values())if(task.equals(s.task)&&!owner.equals(s.owner))throw new SecurityException("Docker task USB is assigned to another caller");}
    synchronized JSONArray sessions(String owner)throws JSONException{JSONArray out=new JSONArray();for(Session s:sessions.values())if(owner==null||s.owner.equals(owner))out.put(s.json());return out;}
    synchronized void activate(String chat){for(Session s:sessions.values())if(s.owner.equals("pending:"+chat))s.owner="chat:"+chat;}
    synchronized void assign(String id,String owner){McpHost.get(app).requireReady();Session s=owned("panel",id);if(!s.task.isEmpty()||UsbJobs.get(app).active(id)||s.redirection!=0||s.serial!=null&&(s.serial.exclusive||s.serial.bridged))throw new IllegalStateException("Device busy");s.owner=owner;}
    synchronized void close(String owner,String id){Session s=owned(owner,id);UsbJobs.get(app).cancelResource(id);sessions.remove(id);s.close();UsbDocker.revoke(app,s);if(sessions.isEmpty())app.stopService(new Intent(app,UsbService.class));}
    void closeOwner(String owner){UsbJobs.get(app).cancelOwner(owner);synchronized(this){for(Session s:new ArrayList<>(sessions.values()))if(s.owner.equals(owner))close(owner,s.id);}UsbStorage.get(app).revokeOwner(owner);}
    synchronized void closeDevice(String device){for(Session s:new ArrayList<>(sessions.values()))if(s.device.equals(device))close(s.owner,s.id);}
    synchronized void closeAll(){for(Session s:new ArrayList<>(sessions.values()))close(s.owner,s.id);}
    synchronized void closeThrough(long generation){for(Session s:new ArrayList<>(sessions.values()))if(s.generation<=generation)close(s.owner,s.id);}
}
