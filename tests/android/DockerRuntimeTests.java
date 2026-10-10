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
    private boolean uiOnly, reuseCache;
    interface Check {void run()throws Exception;}
    @Override public void onCreate(Bundle args){super.onCreate(args);runId=args.getString("run_id",java.util.UUID.randomUUID().toString());uiOnly="true".equals(args.getString("ui_only"));reuseCache="true".equals(args.getString("reuse_cache"));start();}
    @Override public void onStart(){
        app=getTargetContext();evidence=new File(app.getExternalFilesDir(null),uiOnly?"docker-ui-evidence":"docker-evidence");evidence.mkdirs();
        ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        try{if(uiOnly)uiSuite();else suite();}catch(Throwable e){record("suite",false,e.toString());failures++;}
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
            check(DockerTools.list().length()==12,"Docker MCP tools missing");
        }))return;
        if(!test("VM: dedicated emulator workspace and stop guard",()->{
            DockerVm vm=DockerVm.get(app);check(!vm.alive(),"An existing VM is running; stop it before this destructive emulator suite");if(!reuseCache)vm.reset();
        }))return;
        test("UI: Docker dashboard without model account",()->{launch(DockerActivity.class);check(find(screen.getWindow().getDecorView(),"Uruchom środowisko")!=null,"Start button");shot("01-docker-dashboard");});
        if(!test("MCP: real accessibility server connected",()->{
            shell("settings put secure enabled_accessibility_services ''");shell("settings put secure accessibility_enabled 0");
            shell("settings put secure enabled_accessibility_services dev.mateuszsury.androidagent/dev.mateuszsury.androidagent.BuddyService");shell("settings put secure accessibility_enabled 1");
            long until=SystemClock.elapsedRealtime()+30000;while(BuddyService.get()==null&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(250);
            check(BuddyService.get()!=null,"MCP server missing");BuddyService.get().allowControl();
        }))return;
        if(!test("VM: user starts real Linux Docker Engine",()->{
            runOnMainSync(()->((Spinner)find(screen.getWindow().getDecorView(),"Pamięć środowiska Docker")).setSelection(0));
            click("Uruchom środowisko");waitReady();
            JSONObject state=call("docker_status",args());check(state.getBoolean("ready"),state.toString());
            JSONObject guest=state.getJSONObject("guest");check(guest.getString("engine_version").startsWith("29."),"No actual engine version");
            check(guest.getString("storage_driver").equals("overlay2"),guest.toString());SystemClock.sleep(2000);shot("02-docker-running");
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
        test("UI: editor keeps draft across rotation",()->{
            launch(new Intent(app,DockerTaskActivity.class).putExtra("task",task));
            runOnMainSync(()->((EditText)find(screen.getWindow().getDecorView(),"Treść pliku")).setText("DRAFT_AFTER_ROTATION"));
            ActivityMonitor monitor=addMonitor(DockerTaskActivity.class.getName(),null,false);runOnMainSync(()->screen.recreate());
            Activity recreated=waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);check(recreated!=null,"Activity recreation");screen=recreated;waitForIdleSync();
            check(((EditText)find(screen.getWindow().getDecorView(),"Treść pliku")).getText().toString().equals("DRAFT_AFTER_ROTATION"),"Draft lost");shot("03-docker-task");
        });
        test("Cancel: real running command and container stopped",()->{
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
    void uiSuite()throws Exception{
        check(!DockerVm.get(app).alive(),"Stop the VM before this dedicated emulator suite");DockerVm.get(app).reset();
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
        test("UI: readable memory selector in dark theme",()->{
            runOnMainSync(()->{Prefs.setThemeMode(app,"dark");Theme.changed(app);});launch(DockerActivity.class);
            Spinner s=(Spinner)find(screen.getWindow().getDecorView(),"Pamięć środowiska Docker");
            check(Ui.DARK&&((TextView)s.getSelectedView()).getCurrentTextColor()==Ui.TEXT,"Dark selector unreadable");navigationContrast();shot("02-docker-dark");
            runOnMainSync(()->{Prefs.setThemeMode(app,"light");Theme.changed(app);});
        });
        test("QEMU: final APK clock arguments and stop during boot",()->{
            DockerVm vm=DockerVm.get(app);
            check(!new File(vm.directory,"user.qcow2").exists(),"This targeted test requires a fresh dedicated Docker disk");
            launch(DockerActivity.class);click("Uruchom środowisko");
            long until=SystemClock.elapsedRealtime()+30000;while(!vm.alive()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(250);
            check(vm.alive(),vm.detail);SystemClock.sleep(500);String pid=read(new FileInputStream(new File(vm.directory,"vm.pid"))).trim();
            String cmd=read(new FileInputStream("/proc/"+pid+"/cmdline"));
            check(cmd.contains("-icount")==vm.emulatedAndroid,"Incorrect clock arguments on actual QEMU process");
            if(vm.emulatedAndroid)check(cmd.contains("shift=3,align=off,sleep=on"),"Incorrect emulator clock scale");
            until=SystemClock.elapsedRealtime()+10000;while(find(screen.getWindow().getDecorView(),"Zatrzymaj środowisko")==null&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(250);
            click("Zatrzymaj środowisko");until=SystemClock.elapsedRealtime()+30000;
            while(vm.alive()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(250);
            check(!vm.alive(),"Stop did not terminate booting QEMU");vm.reset();
        });
    }
    void save()throws Exception{
        JSONObject report=new JSONObject().put("run_id",runId).put("tests",tests).put("failures",failures).put("api",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS[0]).put("cache_reused",reuseCache);
        try(OutputStream out=new FileOutputStream(new File(evidence,"results.json"))){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
    }
    void export()throws Exception{
        android.net.Uri downloads=android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);ContentResolver r=app.getContentResolver();
        for(File file:evidence.listFiles()){
            if(!file.getName().matches("results\\.json|[0-9]{2}-[a-z0-9-]+\\.png"))continue;
            String relative=uiOnly?"Download/androidagent-docker-ui-tests/":"Download/androidagent-docker-tests/";r.delete(downloads,"_display_name = ? AND relative_path = ?",new String[]{file.getName(),relative});
            ContentValues v=new ContentValues();v.put("_display_name",file.getName());v.put("mime_type",file.getName().endsWith(".png")?"image/png":"application/json");v.put("relative_path",relative);v.put("is_pending",1);
            android.net.Uri uri=r.insert(downloads,v);try(InputStream in=new FileInputStream(file);OutputStream out=r.openOutputStream(uri)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}v.clear();v.put("is_pending",0);r.update(uri,v,null,null);
        }
    }
}
