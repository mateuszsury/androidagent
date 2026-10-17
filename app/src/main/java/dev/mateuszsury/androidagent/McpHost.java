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
    private final Set<String> startingOwners=new HashSet<>(), closingOwners=new HashSet<>(), cancelledStarts=new HashSet<>();
    private long controlGeneration;
    private McpServer server;
    private volatile long revokedUntil;
    private volatile boolean stopping;
    private McpHost(Context c){app=c;}
    synchronized void acquire(Object owner){owners.add(owner);if(server==null){server=new McpServer(Prefs.token(app),this);server.start();}}
    synchronized void release(Object owner){owners.remove(owner);if(owners.isEmpty()&&runs.isEmpty()&&server!=null){server.stop();server=null;}}
    String credential(String chat){
        String owner="chat:"+chat,token=UUID.randomUUID().toString()+UUID.randomUUID();
        final long generation;
        synchronized(this){requireReady(chat);startingOwners.add(owner);generation=controlGeneration;}
        try{
            UsbSessions.get(app).activate(chat);UsbStorage.get(app).activate(chat);
            synchronized(this){requireReady();if(generation!=controlGeneration||cancelledStarts.contains(owner))throw new IllegalStateException("Start rozmowy anulowano przez Stop.");runs.put(token,owner);acquire(owner);}
            return token;
        }catch(RuntimeException e){revoke(chat);throw e;}
        finally{synchronized(this){startingOwners.remove(owner);cancelledStarts.remove(owner);notifyAll();}}
    }
    void revoke(String chat){
        String owner="chat:"+chat;boolean close;
        synchronized(this){runs.values().removeIf(owner::equals);if(startingOwners.contains(owner))cancelledStarts.add(owner);release(owner);close=!stopping&&closingOwners.add(owner);}
        ApprovedCommands.get(app).cancelOwner(owner);
        PhoneTools.clearStatus(app,owner);
        if(close)new Thread(()->{
            try{synchronized(this){while(startingOwners.contains(owner))try{wait();}catch(InterruptedException e){Thread.currentThread().interrupt();return;}}UsbSessions.get(app).closeOwner(owner);}
            finally{synchronized(this){closingOwners.remove(owner);notifyAll();}}
        },"androidagent-run-stop").start();
    }
    @Override public synchronized String principal(String token){return runs.get(token);}
    synchronized boolean running(){return server!=null&&server.listening();}
    boolean stopping(){return stopping;}
    void requireReady(){if(stopping)throw new IllegalStateException("Trwa zatrzymywanie narzędzi. Spróbuj ponownie za chwilę.");}
    synchronized void requireReady(String chat){requireReady();String owner="chat:"+chat;if(startingOwners.contains(owner)||closingOwners.contains(owner)||runs.containsValue(owner))throw new IllegalStateException("Poprzednie narzędzia tej rozmowy są jeszcze aktywne lub zatrzymywane. Spróbuj ponownie za chwilę.");}
    private synchronized void requireActiveCaller(String caller){
        if(!caller.startsWith("chat:"))return;
        String credential=McpServer.credential();
        // Native UI and direct test fixtures have no HTTP request credential.
        // Authenticated requests must retain the exact run, not just its chat id.
        boolean active=credential==null?runs.containsValue(caller):caller.equals(runs.get(credential));
        if(!active)throw new SecurityException("This conversation's tool access has ended.");
    }
    void requireCaller(String caller){requireReady();if(!"panel".equals(caller)&&controlRevoked()!=null)throw new IllegalStateException(controlRevoked());requireActiveCaller(caller);}
    String controlRevoked(){return stopping||SystemClock.elapsedRealtime()<revokedUntil?"The user pressed Stop. Control has been revoked.":null;}
    void allowControl(){revokedUntil=0;}
    void emergencyStop(){
        revokedUntil=SystemClock.elapsedRealtime()+60000;
        synchronized(this){if(stopping)return;stopping=true;++controlGeneration;owners.removeAll(runs.values());runs.clear();}
        java.util.concurrent.CountDownLatch chatsStopped=new java.util.concurrent.CountDownLatch(1);
        Runnable stopChats=()->{try{ChatHub.get(app).stopAll();BuddyService svc=BuddyService.get();if(svc!=null)svc.onControlStopped();}finally{chatsStopped.countDown();}};
        if(Looper.myLooper()==Looper.getMainLooper())stopChats.run();else new Handler(Looper.getMainLooper()).post(stopChats);
        new Thread(()->{
            try{
                try{chatsStopped.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
                synchronized(this){while(!startingOwners.isEmpty())try{wait();}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
                cleanup(()->EmbeddedRuntime.get(app).emergencyStop());
                cleanup(()->ApprovedCommands.get(app).cancelAll());
                cleanup(()->PhoneTools.clearAllStatus(app));
                cleanup(()->UsbJobs.get(app).cancelAll());
                cleanup(()->UsbSessions.get(app).closeAll());
                cleanup(()->UsbStorage.get(app).revokeAll());
                cleanup(()->DockerVm.get(app).emergencyStop());
            }finally{stopping=false;synchronized(this){if(owners.isEmpty()&&runs.isEmpty()&&server!=null){server.stop();server=null;}}}
        },"androidagent-stop").start();
    }
    private static void cleanup(Runnable action){try{action.run();}catch(RuntimeException e){android.util.Log.w("androidagentStop","Resource cleanup failed",e);}}
    /** Lists only what can run now: screen-control tools appear only while Accessibility is connected. */
    @Override public JSONArray listTools()throws JSONException{
        JSONArray all=DockerTools.list();
        all.put(ToolProviders.tool("device_status","Read live Android version, battery, RAM, storage, connectivity and granted capabilities (accessibility_connected tells whether screen tools are available).",new JSONObject()));
        all.put(ToolProviders.tool("run_command","Run an Android shell command under the androidagent app UID (not root). Requires developer mode and explicit approval of this exact command in its chat. Works without Accessibility. Timeout 10 seconds; output 32 KB. Working directory is the app workspace.",new JSONObject().put("command",ToolProviders.prop("string","Exact shell command")),"command"));
        if(BuddyService.get()!=null){JSONArray screen=Tools.list();for(int i=0;i<screen.length();i++)all.put(screen.get(i));}
        JSONArray usb=UsbTools.list();for(int i=0;i<usb.length();i++)all.put(usb.get(i));
        ToolProviders.listInto(app,all);return all;
    }
    @Override public JSONObject callTool(String name,JSONObject a)throws Exception{
        if(controlRevoked()!=null&&!"notify".equals(name))throw new IllegalStateException(controlRevoked());
        String caller=McpServer.caller();
        requireActiveCaller(caller);
        if(UsbTools.handles(name))return UsbTools.call(app,caller,name,a);
        if(name.startsWith("docker_")){synchronized(UsbDocker.taskAccess){requireCaller(caller);UsbSessions.get(app).authorizeTask(caller,a.optString("task"));return DockerTools.call(app,name,a);}}
        if("device_status".equals(name))return UsbTools.result(DeviceState.read(app));
        if("run_command".equals(name))return ApprovedCommands.get(app).run(caller,a.getString("command"));
        ToolProviders.Provider provider=ToolProviders.find(name);
        // notify stays usable after Stop so the agent can still tell the user what happened.
        if(provider!=null){if(!"notify".equals(name))requireCaller(caller);return provider.call(app,caller,name,a);}
        if(!Tools.handles(name))throw new IllegalArgumentException("Unknown tool "+name);
        BuddyService svc=BuddyService.get();
        if(svc==null)throw new IllegalStateException("Screen control (Accessibility) is off, so "+name+" is unavailable. Ask the user to enable androidagent's screen control in Android's Accessibility settings (open_settings panel 'accessibility'). Apps, links, intents, device controls, USB and Docker work without it.");
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
