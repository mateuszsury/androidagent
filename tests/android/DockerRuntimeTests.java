package dev.mateuszsury.androidagent;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Real QEMU + Docker + native UI tests. No logged-in model and no fake engine. */
public class DockerRuntimeTests extends Instrumentation {
    private Context app; private Activity screen; private UiAutomation ui;
    private File evidence; private int failures; private final JSONArray tests=new JSONArray();
    private String runId, task, other, job, artifactId;
    private boolean uiOnly, reuseCache, stopRaceOnly;
    private final JSONObject observations=new JSONObject();
    private final JSONArray stopEvents=new JSONArray();
    interface Check {void run()throws Exception;}
    @Override public void onCreate(Bundle args){super.onCreate(args);runId=args.getString("run_id",java.util.UUID.randomUUID().toString());uiOnly="true".equals(args.getString("ui_only"));reuseCache="true".equals(args.getString("reuse_cache"));stopRaceOnly="true".equals(args.getString("stop_race_only"));start();}
    @Override public void onStart(){
        app=getTargetContext();evidence=new File(app.getExternalFilesDir(null),stopRaceOnly?"docker-stop-race-evidence":uiOnly?"docker-ui-evidence":"docker-evidence");evidence.mkdirs();
        ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        try{if(stopRaceOnly)stopRaceRegression();else if(uiOnly)uiSuite();else suite();}catch(Throwable e){record("suite",false,e.toString());failures++;}
        try{save();export();}catch(Exception e){failures++;record("evidence",false,e.toString());}
        Bundle out=new Bundle();out.putString("stream","Docker: "+tests.length()+" tests, "+failures+" failures\n");
        finish(failures==0?Activity.RESULT_OK:Activity.RESULT_CANCELED,out);
    }
    boolean test(String name,Check check){
        try{check.run();record(name,true,"");save();return true;}catch(Throwable e){failures++;record(name,false,e.toString());try{save();}catch(Exception ignored){}Bundle b=new Bundle();b.putString("stream","Guest console after failure:\n"+DockerVm.get(app).console()+"\n");sendStatus(0,b);return false;}
    }
    void record(String name,boolean pass,String detail){
        try{tests.put(new JSONObject().put("name",name).put("pass",pass).put("detail",detail));}catch(Exception ignored){}
        Bundle b=new Bundle();b.putString("stream",(pass?"PASS ":"FAIL ")+name+(pass?"":" "+detail)+"\n");sendStatus(0,b);
    }
    void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    void launch(Class<?> cls){launch(new Intent(app,cls));}
    void launch(Intent i){screen=startActivitySync(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();}
    View find(View v,String label){
        if(label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
        if(v instanceof TextView&&label.contentEquals(((TextView)v).getText()))return v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int n=0;n<g.getChildCount();n++){View r=find(g.getChildAt(n),label);if(r!=null)return r;}}return null;
    }
    void click(String label){runOnMainSync(()->{View v=find(screen.getWindow().getDecorView(),label);check(v!=null,"Missing button "+label);v.performClick();});waitForIdleSync();}
    int dockerWorkers(){int count=0;for(Thread thread:Thread.getAllStackTraces().keySet())if(thread.isAlive()&&"docker-vm".equals(thread.getName()))count++;return count;}
    ActivityManager.RunningServiceInfo dockerService(){
        for(ActivityManager.RunningServiceInfo service:app.getSystemService(ActivityManager.class).getRunningServices(100))
            if(DockerService.class.getName().equals(service.service.getClassName()))return service;
        return null;
    }
    void shot(String name)throws Exception{SystemClock.sleep(500);Bitmap image=ui.takeScreenshot();try(OutputStream out=new FileOutputStream(new File(evidence,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
    void navigationContrast()throws Exception{
        final int[] box=new int[4];runOnMainSync(()->{View back=find(screen.getWindow().getDecorView(),"Wróć");check(back!=null,"Back button missing");back.getLocationOnScreen(box);box[2]=back.getWidth();box[3]=back.getHeight();});
        SystemClock.sleep(500);Bitmap bitmap=ui.takeScreenshot();int contrasting=0;
        for(int y=box[1];y<Math.min(bitmap.getHeight(),box[1]+box[3]);y++)for(int x=box[0];x<Math.min(bitmap.getWidth(),box[0]+box[2]);x++){
            int pixel=bitmap.getPixel(x,y);int brightness=(android.graphics.Color.red(pixel)+android.graphics.Color.green(pixel)+android.graphics.Color.blue(pixel))/3;
            if(Ui.DARK?brightness>180:brightness<100)contrasting++;
        }
        bitmap.recycle();check(contrasting>25,"Back arrow is invisible in the actual rendered frame: "+contrasting+" contrasting pixels");
    }
    void shell(String cmd)throws Exception{try(ParcelFileDescriptor p=ui.executeShellCommand(cmd);InputStream in=new FileInputStream(p.getFileDescriptor())){byte[] b=new byte[4096];while(in.read(b)!=-1){}}}
    String shellOutput(String cmd)throws Exception{
        try(ParcelFileDescriptor p=ui.executeShellCommand(cmd);InputStream in=new FileInputStream(p.getFileDescriptor())){
            return read(in);
        }
    }
    static String accessibilityUserState(String dump,int user){
        java.util.regex.Pattern attributes=java.util.regex.Pattern.compile("(?m)^\\s*attributes:\\{id="+user+"(?:,|\\})");
        for(String state:dump.split("User state\\["))if(attributes.matcher(state).find())return "User state["+state;
        return null;
    }
    static boolean accessibilityResetComplete(String dump,int user){
        String state=accessibilityUserState(dump,user);
        if(state==null)return false;
        // The public enabled-service list contains only connected services and is already empty
        // after a crash. Wait for AMS to consume the disable setting and clear its crash marker.
        for(String field:new String[]{"Bound","Enabled","Binding","Crashed"})
            if(!java.util.regex.Pattern.compile("(?m)^\\s*"+field+" services:\\{\\s*\\}(?:\\])?\\s*$").matcher(state).find())return false;
        return true;
    }
    String accessibilityDiagnostic(String dump,int user){
        String state=accessibilityUserState(dump,user);
        if(state==null)state="Missing user "+user+" state in dumpsys:\n"+dump;
        return state.substring(0,Math.min(state.length(),12000))
            +"\nBuddyService="+(BuddyService.get()!=null)+", MCP listening="+McpHost.get(app).running();
    }
    void reconnectAccessibility()throws Exception{
        int user=Integer.parseInt(shellOutput("am get-current-user").trim());
        String settings="settings --user "+user+" put secure ";
        shell(settings+"enabled_accessibility_services ''");
        shell(settings+"accessibility_enabled 0");
        long disabledUntil=SystemClock.elapsedRealtime()+10000;
        String dump="No accessibility state received";
        boolean disabled=false;
        while(SystemClock.elapsedRealtime()<disabledUntil){
            dump=shellOutput("dumpsys -t 2 accessibility");
            if(accessibilityResetComplete(dump,user)&&BuddyService.get()==null){disabled=true;break;}
            SystemClock.sleep(100);
        }
        check(disabled,"Android did not clear the previous accessibility binding/crash state before re-enable:\n"
            +accessibilityDiagnostic(dump,user));
        shell(settings+"enabled_accessibility_services dev.mateuszsury.androidagent/dev.mateuszsury.androidagent.BuddyService");
        shell(settings+"accessibility_enabled 1");
        long until=SystemClock.elapsedRealtime()+30000;
        while((BuddyService.get()==null||!McpHost.get(app).running())&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);
        if(BuddyService.get()==null||!McpHost.get(app).running())
            throw new AssertionError("Fresh accessibility service/MCP listener missing:\n"
                +accessibilityDiagnostic(shellOutput("dumpsys -t 2 accessibility"),user));
        BuddyService.get().allowControl();
    }
    JSONObject call(String name,JSONObject args)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:8766/mcp").openConnection();c.setConnectTimeout(5000);c.setReadTimeout(90000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Authorization","Bearer "+Prefs.token(app));c.setRequestProperty("Content-Type","application/json");
        try{JSONObject payload=new JSONObject().put("jsonrpc","2.0").put("id",1).put("method","tools/call").put("params",new JSONObject().put("name",name).put("arguments",args));
            try(OutputStream out=c.getOutputStream()){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));}
            JSONObject result=new JSONObject(read(c.getInputStream())).getJSONObject("result");
            if(result.optBoolean("isError"))throw new IOException(result.toString());
            return result.getJSONObject("structuredContent");
        }finally{c.disconnect();}
    }
    JSONObject args(Object...pairs)throws Exception{JSONObject a=new JSONObject();for(int i=0;i<pairs.length;i+=2)a.put((String)pairs[i],pairs[i+1]);return a;}
    String read(InputStream in)throws Exception{try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=stream.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
    JSONObject waitJob(String id)throws Exception{
        long until=SystemClock.elapsedRealtime()+300000;long progress=0;
        while(SystemClock.elapsedRealtime()<until){JSONObject j=call("docker_logs",args("task",task,"job",id));String state=j.optString("state");
            if(!state.equals("running")&&!state.equals("queued"))return j;if(SystemClock.elapsedRealtime()>progress){Bundle b=new Bundle();b.putString("stream","Job "+j.optString("kind")+": "+state+"\n"+j.optString("output")+"\n");sendStatus(0,b);progress=SystemClock.elapsedRealtime()+15000;}SystemClock.sleep(1000);}
        throw new IOException("Docker job exceeded test timeout");
    }
    void waitReady()throws Exception{
        long until=SystemClock.elapsedRealtime()+(DockerVm.get(app).emulatedAndroid?660000:330000);
        long nextProgress=0;
        while(SystemClock.elapsedRealtime()<until){
            DockerVm vm=DockerVm.get(app);if("ready".equals(vm.state))return;
            if("error".equals(vm.state))throw new IOException(vm.detail+"\n"+vm.console());
            if(SystemClock.elapsedRealtime()>nextProgress){
                Bundle b=new Bundle();b.putString("stream","VM "+vm.state+": "+vm.detail+"\n"+vm.console().substring(Math.max(0,vm.console().length()-1200))+"\n");sendStatus(0,b);nextProgress=SystemClock.elapsedRealtime()+15000;
            }
            SystemClock.sleep(1000);
        }
        throw new IOException("VM did not become ready: "+DockerVm.get(app).detail+"\n"+DockerVm.get(app).console());
    }
    void waitPreview()throws Exception{
        long until=SystemClock.elapsedRealtime()+60000;String last="No response";
        while(SystemClock.elapsedRealtime()<until){
            HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+DockerVm.PREVIEW_PORT).openConnection(Proxy.NO_PROXY);c.setConnectTimeout(2000);c.setReadTimeout(5000);
            try{if(c.getResponseCode()==200&&read(c.getInputStream()).contains("REAL_ANDROID_DOCKER_APP"))return;last="HTTP "+c.getResponseCode();}
            catch(IOException e){last=e.toString();}finally{c.disconnect();}SystemClock.sleep(500);
        }
        throw new IOException("Preview did not become ready: "+last+"\n"+call("docker_logs",args("task",task)));
    }
    void suite()throws Exception{
        if(!test("QEMU: actual Android binary and app UID",()->{
            String lib=app.getApplicationInfo().nativeLibraryDir;
            ProcessBuilder p=new ProcessBuilder(lib+"/libqemu.so","--version").redirectErrorStream(true);p.environment().put("LD_LIBRARY_PATH",lib);
            java.lang.Process q=p.start();String version=read(q.getInputStream());check(q.waitFor()==0&&version.contains("QEMU emulator version"),version);
            check(android.os.Process.myUid()!=0,"App is running as root");
            check(DockerTools.list().length()==15,"Docker MCP tools including durable diagnostics and VM lifecycle missing");
            JSONObject diagnostics=DockerTools.call(app,"docker_diagnostics",args("offset",0,"limit",128)).getJSONObject("structuredContent");
            check(diagnostics.has("content")&&diagnostics.has("next_offset")&&diagnostics.has("total_bytes"),"Diagnostics tool did not return a bounded local page");
            check(DockerVm.get(app).status().has("diagnostics"),"Local Docker status omitted durable diagnostics");
        }))return;
        if(!test("VM: dedicated emulator workspace and stop guard",()->{
            DockerVm vm=DockerVm.get(app);check(!vm.alive(),"An existing VM is running; stop it before this destructive emulator suite");if(!reuseCache)vm.reset();
        }))return;
        test("UI: Docker dashboard without model account",()->{launch(DockerActivity.class);check(find(screen.getWindow().getDecorView(),"Uruchom środowisko")!=null,"Start button");shot("01-docker-dashboard");});
        if(!test("MCP: real accessibility server connected",()->{
            reconnectAccessibility();
        }))return;
        if(!test("VM: user starts real Linux Docker Engine",()->{
            runOnMainSync(()->((Spinner)find(screen.getWindow().getDecorView(),"Pamięć środowiska Docker")).setSelection(0));
            click("Uruchom środowisko");waitReady();
            // No network call, screenshot or sleep before the durability test kills
            // this first boot: those delays could let an unsynced import reach disk.
            JSONObject state=DockerVm.get(app).status();check(state.getBoolean("ready"),state.toString());
            JSONObject guest=state.getJSONObject("guest");check(guest.getString("engine_version").startsWith("29."),"No actual engine version");
            check(guest.getString("storage_driver").equals("overlay2"),guest.toString());
        }))return;
        if(!test("VM: forced stop after readiness restarts the same disk",()->{
            DockerVm vm=DockerVm.get(app);
            File overlay=new File(vm.directory,"user.qcow2");
            android.system.StructStat original=android.system.Os.stat(overlay.getAbsolutePath());
            java.lang.reflect.Field processField=DockerVm.class.getDeclaredField("process");processField.setAccessible(true);
            java.lang.Process running=(java.lang.Process)processField.get(vm);
            check(running!=null&&running.isAlive(),"No live QEMU process to interrupt");
            // Destroying this service invokes emergencyStop(), without the guest
            // shutdown request or sync used by the dashboard's normal Stop button.
            check(app.stopService(new Intent(app,DockerService.class)),"Docker service was not running");
            check(running.waitFor(5,java.util.concurrent.TimeUnit.SECONDS),"QEMU did not exit after forced stop");
            check(running.exitValue()!=0,"QEMU exited normally instead of being forcibly interrupted");
            waitForIdleSync(); // Finish onDestroy before starting a new service.
            check(!vm.alive()&&"stopped".equals(vm.state),"VM lifecycle did not finish forced stop");
            android.system.StructStat stopped=android.system.Os.stat(overlay.getAbsolutePath());
            check(stopped.st_ino==original.st_ino&&stopped.st_dev==original.st_dev,"Forced stop replaced the working disk");
            launch(DockerActivity.class);
            runOnMainSync(()->((Spinner)find(screen.getWindow().getDecorView(),"Pamięć środowiska Docker")).setSelection(0));
            click("Uruchom środowisko");waitReady();
            android.system.StructStat restarted=android.system.Os.stat(overlay.getAbsolutePath());
            check(restarted.st_ino==original.st_ino&&restarted.st_dev==original.st_dev,"Restart replaced the working disk");
            JSONObject state=call("docker_status",args());check(state.getBoolean("ready"),state.toString());
            JSONObject guest=state.getJSONObject("guest");check(guest.getString("engine_version").startsWith("29."),"No actual engine version");
            check(guest.getString("storage_driver").equals("overlay2"),guest.toString());SystemClock.sleep(2000);shot("02-docker-running");
            // The existing Dockerfile RUN/COPY test below verifies that the
            // persisted base layer can actually execute /bin/sh after this kill.
        }))return;
        test("Guest API: rejects missing bearer token",()->{
            HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+DockerVm.API_PORT+"/request").openConnection();
            c.setRequestMethod("POST");c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write("{\"op\":\"status\"}".getBytes(StandardCharsets.UTF_8));}
            check(c.getResponseCode()==401,"Unauthenticated API accepted");c.disconnect();
        });
        test("MCP: independent task workspaces",()->{task=call("docker_create",args("name","Emulator: aplikacja WWW")).getString("id");other=call("docker_create",args("name","Drugie zadanie")).getString("id");
            call("docker_write",args("task",other,"path","keep.txt","content","KEEP_SECOND_TASK"));check(!task.equals(other),"Shared task ids");});
        test("MCP: writes Dockerfile and Unicode application",()->{
            String dockerfile=read(app.getAssets().open("docker/demo/Dockerfile")).replace("USER 1000:1000", "RUN printf 'REAL_DOCKER_BUILD_OK' > build.txt\nUSER 1000:1000");
            call("docker_write",args("task",task,"path","Dockerfile","content",dockerfile));
            call("docker_write",args("task",task,"path","serve.sh","content",read(app.getAssets().open("docker/demo/serve.sh"))));
            call("docker_write",args("task",task,"path","index.html","content","<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>androidagent Docker</title><h1>Zażółć: REAL_ANDROID_DOCKER_APP</h1><button id=check onclick=\"document.title='ANDROID_DOCKER_INTERACTIVE_OK';this.textContent='Działa!'\">Sprawdź</button>"));
            check(call("docker_read",args("task",task,"path","index.html")).getString("content").contains("Zażółć"),"Source read lost Unicode");
        });
        test("MCP: rejects workspace path traversal",()->{
            boolean denied=false;try{call("docker_write",args("task",task,"path","../../outside","content","wrong"));}catch(IOException e){denied=true;}
            check(denied,"Traversal accepted");
        });
        if(!test("Build: actual Dockerfile RUN, COPY and image creation",()->{
            job=call("docker_build",args("task",task)).getString("id");JSONObject result=waitJob(job);
            check(result.getString("state").equals("succeeded"),result.toString());
        }))return;
        if(!test("Run: actual container and forwarded app preview",()->{
            job=call("docker_run",args("task",task,"port",8080,"memory_mb",128)).getString("id");
            JSONObject result=waitJob(job);check("succeeded".equals(result.optString("state")),result.toString());
            waitPreview();
            launch(new Intent(app,DockerTaskActivity.class).putExtra("task",task));
            ActivityMonitor monitor=addMonitor(DockerPreviewActivity.class.getName(),null,false);click("Otwórz podgląd");
            Activity opened=waitForMonitorWithTimeout(monitor,30000);removeMonitor(monitor);
            check(opened instanceof DockerPreviewActivity,"Native preview did not open");
            DockerPreviewActivity page=(DockerPreviewActivity)opened;
            long until=SystemClock.elapsedRealtime()+30000;
            while(SystemClock.elapsedRealtime()<until){
                final String[] title={null};runOnMainSync(()->{page.web.evaluateJavascript("var b=document.getElementById('check');if(b)b.click()",null);title[0]=page.web.getTitle();});
                if("ANDROID_DOCKER_INTERACTIVE_OK".equals(title[0])){screen=page;shot("03-docker-web-preview");return;}
                SystemClock.sleep(250);
            }
            throw new IOException("JavaScript did not run in the native preview");
        }))return;
        test("Exec: exports actual build output and token remains outside container",()->{
            String cmd="mkdir -p out; cp /app/build.txt out/build.txt; cp /app/index.html out/index.html; test ! -e /dev/vdb; ! grep -q aa_token /proc/cmdline; test \"$(readlink /proc/1/cwd)\" = /app; test \"$(cat /sys/fs/cgroup/memory.max)\" = 134217728";
            job=call("docker_exec",args("task",task,"command",cmd)).getString("id");JSONObject result=waitJob(job);check("succeeded".equals(result.optString("state")),result.toString());
        });
        test("Artifacts: real guest output preserved through Android provider",()->{
            JSONObject file=call("docker_export",args("task",task,"path","out/build.txt"));artifactId=file.getString("id");
            check(read(app.getContentResolver().openInputStream(android.net.Uri.parse(file.getString("uri")))).equals("REAL_DOCKER_BUILD_OK"),"Wrong build artifact");
            check(DockerVm.exportedFile(app,"../../shared_prefs/buddy.xml")==null,"Provider traversal");
        });
        test("Artifacts: rejects guest symlink escape",()->{
            job=call("docker_exec",args("task",task,"command","ln -s /etc/passwd out/escape")).getString("id");check("succeeded".equals(waitJob(job).optString("state")),"Cannot create test symlink");
            boolean denied=false;try{call("docker_export",args("task",task,"path","out/escape"));}catch(IOException e){denied=true;}check(denied,"Export escaped workspace");
        });
        test("Run: image USER can edit sources and export results",()->{
            String original=task;task=other;
            try{
                call("docker_write",args("task",task,"path","src/hello.txt","content","SOURCE"));
                call("docker_write",args("task",task,"path","Dockerfile","content","FROM alpine:3.23\nUSER 1000:1000\nCMD [\"sleep\",\"600\"]\n"));
                check("succeeded".equals(waitJob(call("docker_build",args("task",task)).getString("id")).optString("state")),"Non-root image build");
                check("succeeded".equals(waitJob(call("docker_run",args("task",task,"memory_mb",128)).getString("id")).optString("state")),"Non-root container start");
                check("succeeded".equals(waitJob(call("docker_exec",args("task",task,"command","test \"$(id -u)\" = 1000 && printf 'EDITED' >> src/hello.txt && mkdir -p out && cp src/hello.txt out/nonroot.txt")).getString("id")).optString("state")),"Non-root workspace write");
                JSONObject result=call("docker_export",args("task",task,"path","out/nonroot.txt"));
                check(read(app.getContentResolver().openInputStream(android.net.Uri.parse(result.getString("uri")))).equals("SOURCEEDITED"),"Non-root artifact");
            }finally{task=original;}
        });
        usbPassthrough();
        test("UART Docker: actual guest/container route, scoped token and baud/DTR/RTS/break (serial fixture)",this::serialBridgeTest);
        test("UI: editor keeps draft across rotation",()->{
            launch(new Intent(app,DockerTaskActivity.class).putExtra("task",task));
            runOnMainSync(()->((EditText)find(screen.getWindow().getDecorView(),"Treść pliku")).setText("DRAFT_AFTER_ROTATION"));
            ActivityMonitor monitor=addMonitor(DockerTaskActivity.class.getName(),null,false);runOnMainSync(()->screen.recreate());
            Activity recreated=waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);check(recreated!=null,"Activity recreation");screen=recreated;waitForIdleSync();
            check(((EditText)find(screen.getWindow().getDecorView(),"Treść pliku")).getText().toString().equals("DRAFT_AFTER_ROTATION"),"Draft lost");shot("03-docker-task");
        });
        test("Cancel: real running command and container stopped",()->{
            check("succeeded".equals(waitJob(call("docker_run",args("task",task,"memory_mb",128)).getString("id")).optString("state")),"Cannot start the dedicated cancellation container");
            job=call("docker_exec",args("task",task,"command","while :; do echo WORKING; sleep 1; done")).getString("id");
            SystemClock.sleep(1500);call("docker_cancel",args("task",task));check("cancelled".equals(call("docker_logs",args("task",task,"job",job)).optString("state")),"Job still running");
        });
        test("Cleanup: removes only task resources; exported file survives",()->{
            check(task.equals(call("docker_cleanup",args("task",task)).getString("cleaned")),"Cleanup not confirmed");
            JSONArray files=call("docker_files",args("task",other)).getJSONArray("files");check(files.toString().contains("keep.txt"),"Deleted another task");
            check(DockerVm.exportedFile(app,artifactId)!=null,"Export removed during cleanup");
            String original=task;task=other;
            try{String remaining=call("docker_exec",args("task",task,"command","test \"$(cat keep.txt)\" = KEEP_SECOND_TASK")).getString("id");check("succeeded".equals(waitJob(remaining).optString("state")),"Cleanup stopped another task's container");}
            finally{task=original;}
        });
        test("VM: graceful stop and restart preserves second task",()->{
            DockerVm.get(app).stop(true);check(!DockerVm.get(app).alive(),"VM still running");app.stopService(new Intent(app,DockerService.class));
            SystemClock.sleep(1000);launch(DockerActivity.class);click("Uruchom środowisko");waitReady();
            check(call("docker_files",args("task",other)).toString().contains("keep.txt"),"Task not persisted");SystemClock.sleep(2000);shot("04-docker-persisted");
        });
        test("Stop: user revokes control and terminates QEMU",()->{
            BuddyService.get().takeBackControl();SystemClock.sleep(1000);check(!DockerVm.get(app).alive(),"QEMU survived Stop");
            JSONObject stopped=DockerVm.get(app).status();check(!stopped.optBoolean("ready"),"Reports ready after Stop");
            app.stopService(new Intent(app,DockerService.class));
        });
        test("Reset: removes Docker disk while retaining exported results",()->{
            SystemClock.sleep(500);DockerVm.get(app).reset();check(!new File(DockerVm.get(app).directory,"user.qcow2").exists(),"Disk remains");
            check(DockerVm.exportedFile(app,artifactId)!=null,"Reset deleted exported results");launch(DockerActivity.class);shot("05-docker-export-retained");
        });
    }
    void usbPassthrough()throws Exception{
        final String[] node={""};final boolean[] attached={false};DockerVm vm=DockerVm.get(app);
        test("USB QMP: actual usb-redir driver and private Unix chardev hotplug",this::usbRedirTest);
        test("USB QMP: real virtual HID enumerates in Linux guest (fixture, not OTG)",()->{
            vm.qmp("device_add",args("driver","usb-kbd","id","usb-fixture","bus","aausb.0","port","1"));
            JSONObject grant=vm.request(args("op","usb_attach","task",task,"session","fixture-qmp","slot",1,"vid",0x0627,"pid",1));
            node[0]=grant.getJSONArray("usb").getJSONObject(0).getString("node");attached[0]=true;
            check(node[0].startsWith("/dev/bus/usb/"),grant.toString());
            boolean refused=false;try{vm.request(args("op","usb_attach","task",other,"session","fixture-other","slot",1,"vid",0x0627,"pid",1));}catch(Exception e){refused=true;}check(refused,"USB device granted twice");
        });
        if(attached[0])test("USB Docker: selected real bus node in container, no broad device mount",()->{
            check("succeeded".equals(waitJob(call("docker_run",args("task",task,"memory_mb",128)).getString("id")).optString("state")),"USB-enabled container failed");
            JSONObject result=waitJob(call("docker_exec",args("task",task,"command","test -c "+node[0]+" && exec 3<>"+node[0]+" && exec 3>&- && test ! -e /dev/vdb && test ! -e /dev/bus/usb/001/001 && echo REAL_SCOPED_USB_NODE")).getString("id"));
            check("succeeded".equals(result.optString("state"))&&result.optString("output").contains("REAL_SCOPED_USB_NODE"),result.toString());
        });
        if(attached[0])test("USB: stale detach cannot revoke newer generation; correct detach stops task",()->{
            vm.request(args("op","usb_detach","task",task,"session","wrong-generation"));
            check(vm.request(args("op","status")).getJSONArray("tasks").toString().contains("fixture-qmp"),"Stale detach revoked grant");
            vm.request(args("op","usb_detach","task",task,"session","fixture-qmp"));vm.qmp("device_del",args("id","usb-fixture"));
            String rejectedJob=null;try{rejectedJob=call("docker_exec",args("task",task,"command","echo SHOULD_NOT_RUN")).getString("id");}catch(IOException expected){}
            if(rejectedJob!=null){JSONObject result=waitJob(rejectedJob);check("failed".equals(result.optString("state"))&&!result.optString("output").contains("SHOULD_NOT_RUN"),"Container was not stopped: "+result);}
        });
    }
    void serialBridgeTest()throws Exception{
        UsbRuntimeTests.FixturePort port=new UsbRuntimeTests.FixturePort();
        UsbSessions.Session session=new UsbSessions.Session("synthetic-uart","serial","external",null);
        session.serial=new SerialTransport(port,null);UsbSessions hub=UsbSessions.get(app);synchronized(hub){hub.sessions.put(session.id,session);}
        try{
            JSONObject grant=UsbSerialBridge.attach(app,"external",session.id,task);
            check(grant.optString("url").startsWith("androidagent://10.0.2.2:"),"Missing scoped bridge");
            boolean refused=false;try{UsbSerialBridge.attach(app,"other",session.id,task);}catch(SecurityException e){refused=true;}check(refused,"Other owner bridged the UART");
            check("succeeded".equals(waitJob(call("docker_run",args("task",task,"memory_mb",128)).getString("id")).optString("state")),"UART-enabled container failed");
            String script="set -eu; test -r /opt/androidagent/python/sitecustomize.py || { echo UART_MODULE_NOT_READABLE; exit 1; }; test -n \"${ANDROIDAGENT_SERIAL_URL:-}\" || { echo UART_URL_MISSING; exit 1; }; token=${ANDROIDAGENT_SERIAL_URL##*/}; "
                +"wget -qO- --header=\"Authorization: Bearer $token\" --post-data='{"+"\"op\":\"configure\",\"baud\":9600}' http://10.0.2.2:18782/serial; "
                +"wget -qO- --header=\"Authorization: Bearer $token\" --post-data='{"+"\"op\":\"control\",\"dtr\":true,\"rts\":false,\"break\":true}' http://10.0.2.2:18782/serial; "
                +"wget -qO- --header=\"Authorization: Bearer $token\" --post-data='{"+"\"op\":\"write\",\"data\":\"AAECAw==\"}' http://10.0.2.2:18782/serial; sleep 1; "
                +"wget -qO- --header=\"Authorization: Bearer $token\" --post-data='{"+"\"op\":\"read\",\"cursor\":0}' http://10.0.2.2:18782/serial";
            JSONObject result=waitJob(call("docker_exec",args("task",task,"command",script)).getString("id"));
            check("succeeded".equals(result.optString("state"))&&result.optString("output").contains("AAECAw=="),result.toString());
            check(session.serial.baud==9600&&port.dtr&&!port.rts&&port.brk,"UART settings did not reach native fixture");
            UsbSerialBridge.revoke(session);
            check(UsbSerialBridge.grants.isEmpty(),"Bridge token survived revoke");
        }finally{synchronized(hub){hub.sessions.remove(session.id);}session.serial.close();UsbSerialBridge.revoke(session);DockerVm.get(app).request(args("op","usb_detach","task",task,"session",session.id));}
    }
    void usbRedirTest()throws Exception{
        DockerVm vm=DockerVm.get(app);String socket=new File(vm.directory,"redir-fixture.sock").getAbsolutePath();
        try{
            vm.qmp("chardev-add",args("id","redir-fixture","backend",args("type","socket","data",args("addr",args("type","unix","data",args("path",socket)),"server",true,"wait",false))));
            vm.qmp("device_add",args("driver","usb-redir","id","redir-device-fixture","chardev","redir-fixture","bus","aausb.0","port","2"));
            check(new File(socket).exists(),"QEMU did not create the private socket");
            vm.qmp("device_del",args("id","redir-device-fixture"));
            // QEMU 11 destroys the owned chardev with usb-redir. Older versions
            // may leave it for explicit removal; inspect rather than delete twice.
            if(hasChardev(vm,"redir-fixture"))vm.qmp("chardev-remove",args("id","redir-fixture"));
            check(!hasChardev(vm,"redir-fixture"),"USB chardev survived device removal");
        }finally{
            // Preserve the original failure when setup did not finish.
            try{vm.qmp("device_del",args("id","redir-device-fixture"));}catch(Exception ignored){}
            try{vm.qmp("chardev-remove",args("id","redir-fixture"));}catch(Exception ignored){}
            new File(socket).delete();
        }
    }
    boolean hasChardev(DockerVm vm,String id)throws Exception{
        JSONArray channels=vm.qmp("query-chardev",null).getJSONArray("return");
        for(int i=0;i<channels.length();i++)if(id.equals(channels.getJSONObject(i).optString("label")))return true;
        return false;
    }
    /** Delayed HTTP fixture for races only; real Linux tests remain in suite(). */
    final class DelayedGuest implements java.io.Closeable {
        final ServerSocket listener=new ServerSocket();
        final java.util.concurrent.CountDownLatch attaching=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        final java.util.List<Thread> clients=new java.util.ArrayList<>();
        volatile boolean granted,rejectAttachment;volatile int detaches;volatile Throwable error;
        final Thread accepting;
        DelayedGuest()throws Exception {
            listener.setReuseAddress(true);listener.bind(new InetSocketAddress("127.0.0.1",DockerVm.API_PORT));
            accepting=new Thread(()->{while(!listener.isClosed())try {
                Socket socket=listener.accept();Thread client=new Thread(()->respond(socket),"delayed-guest-client");
                synchronized(clients){clients.add(client);}client.start();
            }catch(IOException e){if(!listener.isClosed())error=e;}},"delayed-guest");accepting.start();
        }
        String line(InputStream in)throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();int ch;while((ch=in.read())!=-1){if(ch==10)return bytes.toString("US-ASCII").trim();if(bytes.size()>8192)throw new IOException("Header limit");bytes.write(ch);}throw new EOFException();}
        void respond(Socket socket) {
            try(Socket c=socket) {
                c.setSoTimeout(10000);InputStream in=c.getInputStream();line(in);int count=0;
                for(String header;(header=line(in)).length()>0;)if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))count=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                if(count<1||count>10000)throw new IOException("Request size");byte[] bytes=new byte[count];new DataInputStream(in).readFully(bytes);
                JSONObject a=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
                boolean reject="serial_attach".equals(a.optString("op"))&&rejectAttachment;
                if("serial_attach".equals(a.optString("op"))){attaching.countDown();if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Fixture deadline");granted=!reject;}
                else if("usb_detach".equals(a.optString("op"))){granted=false;synchronized(this){detaches++;}}
                byte[] body=(reject?"{\"error\":\"fixture attach failure\"}":"{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
                c.getOutputStream().write(("HTTP/1.1 "+(reject?"400 Error":"200 OK")+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));c.getOutputStream().write(body);
            }catch(Throwable e){error=e;}
        }
        public void close()throws IOException {release.countDown();listener.close();try{accepting.join(5000);java.util.List<Thread> pending;synchronized(clients){pending=new java.util.ArrayList<>(clients);}for(Thread t:pending)t.join(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
    void attachmentRegressions()throws Exception {
        test("Docker cancel: malformed task cannot close unrelated USB leases",()->{
            UsbSessions hub=UsbSessions.get(app);UsbSessions.Session session=new UsbSessions.Session("fixture","serial","panel",null);
            synchronized(hub){hub.sessions.put(session.id,session);}
            try {
                for(String op:new String[]{"cancel","cleanup"})for(String taskId:new String[]{"","../bad"}){
                    boolean denied=false;try{DockerVm.get(app).request(args("op",op,"task",taskId));}catch(IllegalArgumentException expected){denied=true;}
                    check(denied&&!session.closed&&hub.owned("panel",session.id)==session,"Invalid task revoked another USB session");
                }
            }finally{hub.close("panel",session.id);}
        });
        test("UART Docker: cannot attach a second caller to an owned task",()->{
            DockerVm vm=DockerVm.get(app);String state=vm.state;vm.state="ready";
            UsbSessions hub=UsbSessions.get(app);UsbSessions.Session first=new UsbSessions.Session("first","docker","owner-one",null),second=new UsbSessions.Session("second","serial","owner-two",null);
            first.task="11111111111111111111111111111111";second.serial=new SerialTransport(new UsbRuntimeTests.FixturePort(),null);
            synchronized(hub){hub.sessions.put(first.id,first);hub.sessions.put(second.id,second);}
            try {boolean denied=false;try{UsbSerialBridge.attach(app,"owner-two",second.id,first.task);}catch(SecurityException expected){denied=true;}
                check(denied&&second.task.isEmpty()&&!second.serial.bridged,"Cross-owner UART assignment accepted");
            }finally{vm.state=state;first.task="";hub.close(first.owner,first.id);hub.close(second.owner,second.id);}
        });
        test("UART Docker: failed attachment closes its generation before retry (HTTP fixture)",()->{
            DockerVm vm=DockerVm.get(app);check(!vm.alive(),"Fixture requires stopped VM");String state=vm.state;vm.state="ready";
            UsbSessions hub=UsbSessions.get(app);UsbSessions.Session session=new UsbSessions.Session("fixture","serial","fixture",null);session.serial=new SerialTransport(new UsbRuntimeTests.FixturePort(),null);
            synchronized(hub){hub.sessions.put(session.id,session);}
            try(DelayedGuest guest=new DelayedGuest()) {
                guest.rejectAttachment=true;guest.release.countDown();boolean failed=false;
                try{UsbSerialBridge.attach(app,"fixture",session.id,"22222222222222222222222222222222");}catch(IOException expected){failed=true;}
                check(failed&&session.closed&&!session.serial.bridged,"Failed attachment left a reusable old generation");
                synchronized(hub){check(!hub.sessions.containsKey(session.id),"Failed session can race a retry against old detach");}
                long until=SystemClock.elapsedRealtime()+5000;while(guest.detaches<2&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(20);
                check(guest.detaches>=2&&guest.error==null,"Failed attach rollback did not finish");
            }finally{vm.state=state;synchronized(hub){if(hub.sessions.get(session.id)==session)hub.close(session.owner,session.id);}UsbSerialBridge.revoke(session);}
        });
        test("UART Docker: Stop stays responsive and late attachment is rolled back (HTTP fixture)",()->{
            DockerVm vm=DockerVm.get(app);check(!vm.alive(),"Fixture requires stopped VM");String state=vm.state;vm.state="ready";
            UsbSessions hub=UsbSessions.get(app);UsbSessions.Session session=new UsbSessions.Session("fixture","serial","fixture",null);session.serial=new SerialTransport(new UsbRuntimeTests.FixturePort(),null);
            synchronized(hub){hub.sessions.put(session.id,session);}
            java.util.concurrent.atomic.AtomicReference<Throwable> result=new java.util.concurrent.atomic.AtomicReference<>();
            Thread attach=new Thread(()->{try{UsbSerialBridge.attach(app,"fixture",session.id,"22222222222222222222222222222222");}catch(Throwable e){result.set(e);}},"test-uart-attach");
            try(DelayedGuest guest=new DelayedGuest()) {
                attach.start();check(guest.attaching.await(5,java.util.concurrent.TimeUnit.SECONDS),"Attachment did not reach HTTP fixture");
                java.util.concurrent.CountDownLatch closed=new java.util.concurrent.CountDownLatch(1);
                Thread stop=new Thread(()->{try{hub.close("fixture",session.id);}finally{closed.countDown();}},"test-uart-stop");stop.start();
                try {check(closed.await(2,java.util.concurrent.TimeUnit.SECONDS),"USB close blocked behind guest I/O");}finally{guest.release.countDown();}
                attach.join(10000);stop.join(5000);check(!attach.isAlive()&&result.get()!=null,"Revoked attachment reported success");
                long until=SystemClock.elapsedRealtime()+5000;while((guest.granted||guest.detaches<2)&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(20);
                check(!guest.granted&&guest.detaches>=2&&guest.error==null,"Late guest grant was not revoked: "+guest.error);
                synchronized(UsbSerialBridge.class){check(UsbSerialBridge.grants.values().stream().noneMatch(g->g.session==session),"Revoked UART token remains live");}
                check(!session.serial.bridged,"UART reservation survived Stop");
            }finally{vm.state=state;attach.join(10000);synchronized(hub){if(hub.sessions.get(session.id)==session)hub.close(session.owner,session.id);}UsbSerialBridge.revoke(session);}
        });
    }
    void uiSuite()throws Exception{
        check(!DockerVm.get(app).alive(),"Stop the VM before this dedicated emulator suite");DockerVm.get(app).reset();
        attachmentRegressions();
        test("UI: readable memory selector in light theme",()->{
            runOnMainSync(()->{Prefs.setThemeMode(app,"light");Theme.changed(app);});launch(DockerActivity.class);
            Spinner s=(Spinner)find(screen.getWindow().getDecorView(),"Pamięć środowiska Docker");
            check(((TextView)s.getSelectedView()).getCurrentTextColor()==Ui.TEXT,"Invisible selected memory");navigationContrast();shot("01-docker-light");
        });
        test("UI: memory selection survives rotation",()->{
            runOnMainSync(()->((Spinner)find(screen.getWindow().getDecorView(),"Pamięć środowiska Docker")).setSelection(0));
            ActivityMonitor monitor=addMonitor(DockerActivity.class.getName(),null,false);runOnMainSync(()->screen.recreate());
            Activity recreated=waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);check(recreated!=null,"Activity recreation");screen=recreated;waitForIdleSync();
            check(((Spinner)find(screen.getWindow().getDecorView(),"Pamięć środowiska Docker")).getSelectedItemPosition()==0,"Selection reset");
        });
        test("UI: two CPU selection remains readable after rotation",()->{
            runOnMainSync(()->((Spinner)find(screen.getWindow().getDecorView(),"Procesory środowiska Docker")).setSelection(1));
            ActivityMonitor monitor=addMonitor(DockerActivity.class.getName(),null,false);runOnMainSync(()->screen.recreate());
            Activity recreated=waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);check(recreated!=null,"Activity recreation");screen=recreated;waitForIdleSync();
            Spinner cores=(Spinner)find(screen.getWindow().getDecorView(),"Procesory środowiska Docker");
            check(cores!=null&&cores.getSelectedItemPosition()==1,"CPU selection reset");
            TextView label=(TextView)cores.getSelectedView();
            check(label!=null&&label.getText().toString().contains("2")&&label.getCurrentTextColor()==Ui.TEXT,"CPU selector label is unreadable");
            runOnMainSync(()->cores.setSelection(0));
        });
        test("UI: readable memory selector in dark theme",()->{
            runOnMainSync(()->{Prefs.setThemeMode(app,"dark");Theme.changed(app);});launch(DockerActivity.class);
            Spinner s=(Spinner)find(screen.getWindow().getDecorView(),"Pamięć środowiska Docker");
            check(Ui.DARK&&((TextView)s.getSelectedView()).getCurrentTextColor()==Ui.TEXT,"Dark selector unreadable");navigationContrast();shot("02-docker-dark");
            runOnMainSync(()->{Prefs.setThemeMode(app,"light");Theme.changed(app);});
        });
        test("QEMU: final APK clock arguments and stop during boot",()->{
            DockerVm vm=DockerVm.get(app);
            check(!new File(vm.directory,"user.qcow2").exists(),"This targeted test requires a fresh dedicated Docker disk");
            launch(DockerActivity.class);runOnMainSync(()->((Spinner)find(screen.getWindow().getDecorView(),"Procesory środowiska Docker")).setSelection(1));click("Uruchom środowisko");
            long until=SystemClock.elapsedRealtime()+30000;while(!vm.alive()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(250);
            check(vm.alive(),vm.detail);SystemClock.sleep(500);String pid=read(new FileInputStream(new File(vm.directory,"vm.pid"))).trim();
            String cmd=read(new FileInputStream("/proc/"+pid+"/cmdline"));
            String[] arguments=cmd.split("\u0000"); boolean actualTwoCpus=false;
            for(int i=0;i+1<arguments.length;i++)if("-smp".equals(arguments[i]))actualTwoCpus="2".equals(arguments[i+1]);
            check(actualTwoCpus,"Actual QEMU command did not receive selected -smp 2");
            check(cmd.contains("-icount")==vm.emulatedAndroid,"Incorrect clock arguments on actual QEMU process");
            if(vm.emulatedAndroid)check(cmd.contains("shift=3,align=off,sleep=on"),"Incorrect emulator clock scale");
            until=SystemClock.elapsedRealtime()+10000;while(find(screen.getWindow().getDecorView(),"Zatrzymaj środowisko")==null&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(250);
            click("Zatrzymaj środowisko");until=SystemClock.elapsedRealtime()+30000;
            while(vm.alive()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(250);
            check(!vm.alive(),"Stop did not terminate booting QEMU");runOnMainSync(()->((Spinner)find(screen.getWindow().getDecorView(),"Procesory środowiska Docker")).setSelection(0));vm.reset();
        });
        stopRaceRegression();
    }
    void stopRaceEvent(String name)throws Exception{
        JSONObject event=new JSONObject().put("name",name).put("guest_wall_ms",System.currentTimeMillis())
            .put("guest_elapsed_ms",SystemClock.elapsedRealtime()).put("guest_nano_time",System.nanoTime());
        stopEvents.put(event);observations.put("stop_race_events",stopEvents);
        Bundle status=new Bundle();status.putString("stream","STOP_RACE_EVENT "+event.toString()+"\n");sendStatus(0,status);
    }
    JSONObject stopRaceState(DockerVm vm,java.lang.Process running,boolean includeStacks)throws Exception{
        ActivityManager.RunningServiceInfo service=dockerService();
        JSONObject state=new JSONObject().put("guest_wall_ms",System.currentTimeMillis()).put("guest_elapsed_ms",SystemClock.elapsedRealtime())
            .put("vm_alive",vm.alive()).put("retained_process_alive",running!=null&&running.isAlive()).put("service_exists",service!=null)
            .put("docker_worker_count",dockerWorkers()).put("vm_state",vm.state).put("vm_detail",vm.detail).put("vm_status",vm.status());
        if(running!=null&&!running.isAlive())try{state.put("actual_exit_code",running.exitValue());}catch(IllegalThreadStateException ignored){}
        if(service!=null)state.put("service",new JSONObject().put("pid",service.pid).put("foreground",service.foreground)
            .put("started",service.started).put("active_since_guest_ms",service.activeSince).put("last_activity_guest_ms",service.lastActivityTime));
        String dump=shellOutput("dumpsys -t 2 activity services "+app.getPackageName()+"/"+DockerService.class.getName());
        state.put("service_dump",dump);java.util.regex.Matcher ids=java.util.regex.Pattern.compile("lastStartId=([0-9]+)").matcher(dump);
        if(ids.find())state.put("latest_start_id",Integer.parseInt(ids.group(1)));
        if(includeStacks){
            JSONArray threads=new JSONArray();
            for(java.util.Map.Entry<Thread,StackTraceElement[]> entry:Thread.getAllStackTraces().entrySet()){
                Thread thread=entry.getKey();String name=thread.getName();
                if(!"main".equals(name)&&!name.startsWith("docker-"))continue;
                JSONArray frames=new JSONArray();for(StackTraceElement frame:entry.getValue())frames.put(frame.toString());
                threads.put(new JSONObject().put("name",name).put("id",thread.getId()).put("state",thread.getState().toString()).put("alive",thread.isAlive()).put("stack",frames));
            }
            state.put("threads",threads);
        }
        return state;
    }
    void stopRaceRegression(){
        test("QEMU service: duplicate starts claim one owner and Stop ends the latest foreground service",()->{
            DockerVm vm=DockerVm.get(app);launch(DockerActivity.class);McpHost.get(app).requireReady();
            long until=SystemClock.elapsedRealtime()+30000;
            while((dockerService()!=null||dockerWorkers()!=0)&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);
            check(!vm.alive()&&"stopped".equals(vm.state)&&dockerService()==null&&dockerWorkers()==0,"Previous VM/service owner has not stopped");
            java.lang.reflect.Field lifecycle=DockerVm.class.getDeclaredField("lifecycle");lifecycle.setAccessible(true);
            Intent boot=new Intent(app,DockerService.class).putExtra("ram",512).putExtra("cores",1);
            java.lang.Process running=null;
            try {
                // Both onStartCommand calls run while the worker cannot enter
                // boot's lifecycle lock or publish preparing. This exposes the
                // old check-state-then-spawn race without timing assumptions.
                synchronized(lifecycle.get(vm)) {
                    stopRaceEvent("before_duplicate_starts");
                    runOnMainSync(()->{app.startForegroundService(new Intent(boot));app.startForegroundService(new Intent(boot));});
                    until=SystemClock.elapsedRealtime()+10000;boolean bothDelivered=false;
                    while(SystemClock.elapsedRealtime()<until){
                        String dump=shellOutput("dumpsys -t 2 activity services "+app.getPackageName()+"/"+DockerService.class.getName());
                        java.util.regex.Matcher ids=java.util.regex.Pattern.compile("lastStartId=([0-9]+)").matcher(dump);
                        if(ids.find()&&Integer.parseInt(ids.group(1))>=2){bothDelivered=true;break;}
                        SystemClock.sleep(50);
                    }
                    check(bothDelivered,"Android did not deliver both service start IDs while the lifecycle barrier was held");
                    runOnMainSync(()->{});waitForIdleSync();
                    check("stopped".equals(vm.state)&&!vm.alive(),"Lifecycle barrier did not precede preparing");
                    check(dockerWorkers()==1,"Duplicate start created "+dockerWorkers()+" VM worker threads before preparing");
                    ActivityManager.RunningServiceInfo owner=dockerService();
                    check(owner!=null&&owner.foreground&&owner.started,"Single owner is not an actual foreground service");
                    observations.put("duplicate_start_barrier",stopRaceState(vm,null,true));
                    stopRaceEvent("duplicate_starts_delivered_before_preparing");
                }
                stopRaceEvent("lifecycle_barrier_released");
                until=SystemClock.elapsedRealtime()+30000;
                while(!vm.alive()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);
                check(vm.alive(),"Duplicate start stopped the actual VM owner: "+vm.detail);
                ActivityManager.RunningServiceInfo owner=dockerService();
                check(owner!=null&&owner.foreground&&dockerWorkers()==1,"Live QEMU lost its sole foreground/worker owner");
                java.lang.reflect.Field process=DockerVm.class.getDeclaredField("process");process.setAccessible(true);
                running=(java.lang.Process)process.get(vm);check(running!=null&&running.isAlive(),"No actual QEMU process");
                stopRaceEvent("qemu_alive_before_stop");
                runOnMainSync(()->app.startService(new Intent(app,DockerService.class).setAction("stop")));
                stopRaceEvent("stop_intent_delivered");
                until=SystemClock.elapsedRealtime()+30000;
                while((vm.alive()||dockerService()!=null||dockerWorkers()!=0)&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);
                observations.put("original_three_condition_loop",stopRaceState(vm,running,true));
                observations.put("original_stop_deadline_guest_ms",until);stopRaceEvent("original_three_condition_loop_ended");
                // The retained Process was part of the assertion but missing
                // from the original wait. Observe all four conditions within
                // the same original budget, without granting additional time.
                while((vm.alive()||running.isAlive()||dockerService()!=null||dockerWorkers()!=0)&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);
                JSONObject stopped=stopRaceState(vm,running,true);observations.put("stop_before_cleanup",stopped);stopRaceEvent("all_condition_wait_ended");
                check(!vm.alive()&&!running.isAlive()&&dockerService()==null&&dockerWorkers()==0,"Stop incomplete before cleanup: "+stopped.toString());
            } catch(Throwable failure) {
                observations.put("failure_before_cleanup",stopRaceState(vm,running,true));stopRaceEvent("failure_before_cleanup");throw failure;
            } finally {
                stopRaceEvent("cleanup_requested");
                runOnMainSync(()->app.stopService(new Intent(app,DockerService.class)));
                if(vm.alive()||"preparing".equals(vm.state)||"booting".equals(vm.state))vm.emergencyStop();
                stopRaceEvent("cleanup_returned");
            }
        });
    }
    void save()throws Exception{
        JSONObject report=new JSONObject().put("version",BuildInfo.VERSION).put("run_id",runId).put("tests",tests).put("failures",failures).put("api",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS[0]).put("cache_reused",reuseCache).put("stop_race_only",stopRaceOnly).put("observations",observations);
        try(OutputStream out=new FileOutputStream(new File(evidence,"results.json"))){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
    }
    void export()throws Exception{
        android.net.Uri downloads=android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);ContentResolver r=app.getContentResolver();
        for(File file:evidence.listFiles()){
            if(!file.getName().matches("results\\.json|[0-9]{2}-[a-z0-9-]+\\.png"))continue;
            String relative=stopRaceOnly?"Download/androidagent-docker-stop-race-tests/":uiOnly?"Download/androidagent-docker-ui-tests/":"Download/androidagent-docker-tests/";r.delete(downloads,"_display_name = ? AND relative_path = ?",new String[]{file.getName(),relative});
            ContentValues v=new ContentValues();v.put("_display_name",file.getName());v.put("mime_type",file.getName().endsWith(".png")?"image/png":"application/json");v.put("relative_path",relative);v.put("is_pending",1);
            android.net.Uri uri=r.insert(downloads,v);try(InputStream in=new FileInputStream(file);OutputStream out=r.openOutputStream(uri)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}v.clear();v.put("is_pending",0);r.update(uri,v,null,null);
        }
    }
}
