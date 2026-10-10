package dev.mateuszsury.androidagent;

import android.content.Context;
import android.os.*;
import org.json.*;
import java.io.File;
import java.util.*;

/** One MCP listener, independent of Accessibility; per-run credentials identify USB owners. */
final class McpHost implements McpServer.Handler {
    private static McpHost instance;
    static synchronized McpHost get(Context c) { if(instance==null)instance=new McpHost(c.getApplicationContext());return instance; }
    final Context app;
    private final Set<Object> owners=new HashSet<>();
    private final Map<String,String> runs=new HashMap<>();
    private McpServer server;
    private volatile long revokedUntil;
    private McpHost(Context c){app=c;}
    synchronized void acquire(Object owner){owners.add(owner);if(server==null){server=new McpServer(Prefs.token(app),this);server.start();}}
    synchronized void release(Object owner){owners.remove(owner);if(owners.isEmpty()&&runs.isEmpty()&&server!=null){server.stop();server=null;}}
    synchronized String credential(String chat){revoke(chat);String token=UUID.randomUUID().toString()+UUID.randomUUID();runs.put(token,"chat:"+chat);UsbSessions.get(app).activate(chat);UsbStorage.get(app).activate(chat);acquire("chat:"+chat);return token;}
    synchronized void revoke(String chat){String owner="chat:"+chat;runs.values().removeIf(owner::equals);UsbSessions.get(app).closeOwner(owner);release(owner);}
    @Override public synchronized String principal(String token){return runs.get(token);}
    synchronized boolean running(){return server!=null&&server.listening();}
    String controlRevoked(){return SystemClock.elapsedRealtime()<revokedUntil?"The user pressed Stop. Control has been revoked.":null;}
    void allowControl(){revokedUntil=0;}
    void emergencyStop(){
        revokedUntil=SystemClock.elapsedRealtime()+60000;
        synchronized(this){owners.removeAll(runs.values());runs.clear();}
        UsbSessions.get(app).closeAll();UsbStorage.get(app).revokeAll();UsbJobs.get(app).cancelAll();DockerVm.get(app).emergencyStop();
        new Handler(Looper.getMainLooper()).post(()->ChatHub.get(app).stopAll());
    }
    @Override public JSONArray listTools()throws JSONException{
        JSONArray all=new Tools(BuddyService.get()).list();JSONArray usb=UsbTools.list();for(int i=0;i<usb.length();i++)all.put(usb.get(i));return all;
    }
    @Override public JSONObject callTool(String name,JSONObject a)throws Exception{
        if(controlRevoked()!=null&&!"notify".equals(name))throw new IllegalStateException(controlRevoked());
        String caller=McpServer.caller();
        if(UsbTools.handles(name))return UsbTools.call(app,caller,name,a);
        if(name.startsWith("docker_")){synchronized(UsbDocker.taskAccess){UsbSessions.get(app).authorizeTask(caller,a.optString("task"));return DockerTools.call(app,name,a);}}
        if("device_status".equals(name))return UsbTools.result(DeviceState.read(app));
        BuddyService svc=BuddyService.get();
        if(svc==null)throw new IllegalStateException("This screen-control tool needs Accessibility. USB and Docker work without it.");
        return svc.callTool(name,a);
    }
    @Override public File attachment(String id){return Attachments.file(app,id);}
    @Override public void onEvent(JSONObject event){
        BuddyService svc=BuddyService.get();if(svc!=null){svc.onEvent(event);return;}
        if("buddy_pong".equals(event.optString("type"))){
            Prefs.setHelperStatus(app,event.optString("text"));Prefs.setHelperVersion(app,event.optInt("helper",1));
            if(event.has("signed"))Prefs.setSignedAgents(app,event.optString("signed"));
            JSONArray models=event.optJSONArray("codex_models");if(models!=null&&models.length()>0){Prefs.setCodexModels(app,models.toString());Models.setCodex(models.toString());}return;
        }
        new Handler(Looper.getMainLooper()).post(()->{String t=event.optString("type");if("buddy_procs".equals(t)||"buddy_recent".equals(t)||"buddy_transcript".equals(t))ChatHub.get(app).onMonitorEvent(event);else ChatHub.get(app).onEvent(event);});
    }
}
