package dev.mateuszsury.androidagent;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import org.json.*;

/** Shared MCP adapter: both Codex and Claude receive these same task-scoped tools. */
final class DockerTools {
    static JSONArray list() throws JSONException {
        JSONArray tools=new JSONArray();
        tools.put(schema("docker_status","Read local Docker VM state, architecture, disk usage, tasks and jobs. previews lists each task's published app: url (http://127.0.0.1:PORT on the phone), host_port, container_port and lan_url when the owner enabled Wi-Fi access. No model login or root required.",new String[]{}));
        tools.put(schema("docker_diagnostics","Read a bounded page of the last VM startup console and termination metadata, also after failed boot or app restart. No shell execution; offset is a byte offset and limit is at most 32768.",new String[]{"offset:integer","limit:integer"}));
        tools.put(schema("docker_start","Start the local Docker VM (QEMU Linux guest). Owner opt-in: requires developer mode in androidagent; otherwise ask the user to press Start on the Docker screen. memory_mb 512-2048 (default 1024), cpus 1-2 (default 1). Returns immediately; poll docker_status until ready is true (a cold boot can take 5-20 minutes on a phone). Android may refuse a start while androidagent is in the background; the error says so.",new String[]{"memory_mb:integer","cpus:integer"}));
        tools.put(schema("docker_stop","Stop the local Docker VM gracefully: cancels running operations, stops every task container, syncs the disk and ends QEMU. Task files, images and exports remain. Owner opt-in: requires developer mode in androidagent. Ask the user before stopping a VM you did not start.",new String[]{}));
        tools.put(schema("docker_create","Create a disposable build workspace in the running Docker VM. Save its task id. Start the VM first (docker_start or the Docker screen).",new String[]{"name"},"name"));
        tools.put(schema("docker_write","Write a UTF-8 source file or Dockerfile inside this task. Relative paths only; maximum 256 KiB.",new String[]{"task","path","content"},"task","path","content"));
        tools.put(schema("docker_build","Start an asynchronous Dockerfile build. Returns a job id; use docker_logs to inspect completion. Native Linux architecture of this phone.",new String[]{"task","dockerfile"},"task"));
        tools.put(schema("docker_run","Start this task's built image or a named OCI image, detached. command is optional. port publishes that container port: every task gets its own phone URL, returned as url/host_port/container_port (and lan_url if the owner enabled Wi-Fi access). Servers keep running until docker_cancel/docker_cleanup or VM stop. Containers have memory/CPU/PID limits and only this task's /workspace mount.",new String[]{"task","image","command","port:integer","memory_mb:integer"},"task"));
        tools.put(schema("docker_exec","Start an asynchronous shell command INSIDE this task's container at /workspace. Use docker_logs with the returned job id. A command running over 15 minutes is stopped (timed_out); the container and its server keep running.",new String[]{"task","command"},"task","command"));
        tools.put(schema("docker_logs","Read a job's state, exit code and bounded output, or container logs when job is omitted. No shell polling loops.",new String[]{"task","job"},"task"));
        tools.put(schema("docker_files","List files in this task's workspace.",new String[]{"task"},"task"));
        tools.put(schema("docker_read","Read a UTF-8 source file inside this task; relative paths only, maximum 256 KiB.",new String[]{"task","path"},"task","path"));
        tools.put(schema("docker_cancel","Cancel this task's active jobs, stop its containers and unpublish its port. Keep source files and images.",new String[]{"task"},"task"));
        tools.put(schema("docker_export","Preserve an APK or other output file from this task in Android storage. Returns a content URI; the saved file survives cleanup. Maximum 128 MiB.",new String[]{"task","path"},"task","path"));
        tools.put(schema("docker_cleanup","Remove ONLY this disposable task's containers, generated images and work files. Export valuable results first. Retain other tasks, shared image cache and already exported artifacts.",new String[]{"task"},"task"));
        return tools;
    }
    private static JSONObject schema(String name,String description,String[] fields,String...required)throws JSONException{
        JSONObject properties=new JSONObject();
        for(String field:fields){String[] parts=field.split(":");properties.put(parts[0],new JSONObject().put("type",parts.length>1?parts[1]:"string"));}
        JSONObject s=new JSONObject().put("type","object").put("properties",properties).put("additionalProperties",false);
        JSONArray r=new JSONArray();for(String field:required)r.put(field);if(r.length()>0)s.put("required",r);
        return new JSONObject().put("name",name).put("description",description).put("inputSchema",s);
    }
    static JSONObject call(Context c,String name,JSONObject args)throws Exception{
        DockerVm vm=DockerVm.get(c);
        JSONObject result;
        if("docker_status".equals(name))result=vm.status();
        else if("docker_diagnostics".equals(name))result=vm.diagnostics(args.optInt("offset",0),args.optInt("limit",16384));
        else if("docker_export".equals(name))result=vm.export(args.getString("task"),args.getString("path"));
        else if("docker_start".equals(name))result=start(c,vm,args);
        else if("docker_stop".equals(name))result=stop(c,vm);
        else {
            String op=name.substring("docker_".length());
            if(!java.util.Arrays.asList("create","write","read","build","run","exec","logs","files","cancel","cleanup").contains(op))throw new IllegalArgumentException("Unknown Docker tool");
            JSONObject request=new JSONObject(args.toString()).put("op",op);
            result=vm.request(request);
        }
        return new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",result.toString())))
                .put("structuredContent",result);
    }

    /**
     * Starting or stopping the VM changes the phone's battery/RAM use for every task, so it needs the
     * owner's opt-in. Until a per-request confirmation exists for non-shell tools, that opt-in is the
     * same developer mode that gates run_command.
     */
    private static void requireOwnerOptIn(Context c,String tool){
        if(!Prefs.devMode(c))throw new SecurityException(tool+" needs the owner's opt-in: developer mode is off in androidagent. Ask the user to start or stop Docker on the Docker screen, or to enable developer mode in Settings.");
    }

    private static JSONObject start(Context c,DockerVm vm,JSONObject args)throws Exception{
        requireOwnerOptIn(c,"docker_start");
        int memory=args.optInt("memory_mb",1024),cpus=args.optInt("cpus",1);
        if(memory<512||memory>2048)throw new IllegalArgumentException("memory_mb must be 512-2048");
        if(cpus<1||cpus>2)throw new IllegalArgumentException("cpus must be 1 or 2");
        if("stopping".equals(vm.state))throw new IllegalStateException("The Docker VM is still stopping. Retry in a few seconds.");
        if(vm.alive()||vm.starting())return vm.status().put("already_running",true);
        Intent start=new Intent(c,DockerService.class).putExtra("ram",memory).putExtra("cores",cpus);
        int attempt=vm.startAttempts();
        try{c.startForegroundService(start);}
        catch(IllegalStateException|SecurityException denied){
            // ForegroundServiceStartNotAllowedException (Android 12+) is an IllegalStateException.
            throw new IllegalStateException("Android did not let androidagent start the Docker VM from the background ("
                    +denied.getClass().getSimpleName()+"). Ask the user to open androidagent and press Start on the Docker screen, then poll docker_status.");
        }
        // Report immediate failures (missing QEMU, refused foreground start) in this call.
        long until=SystemClock.elapsedRealtime()+3000;
        while(SystemClock.elapsedRealtime()<until&&!(vm.startAttempts()!=attempt&&("error".equals(vm.state)||"booting".equals(vm.state)||"ready".equals(vm.state))))SystemClock.sleep(100);
        if(vm.startAttempts()!=attempt&&"error".equals(vm.state))throw new IllegalStateException("Docker VM did not start: "+vm.detail);
        return vm.status().put("starting",true).put("next","Poll docker_status until ready is true before creating or running tasks.");
    }

    private static JSONObject stop(Context c,DockerVm vm)throws Exception{
        requireOwnerOptIn(c,"docker_stop");
        if(!vm.alive()&&!vm.starting())return vm.status().put("already_stopped",true);
        // Same order as the panel: graceful guest shutdown (disk sync), then
        // release the foreground service. Its onDestroy sees a stopped VM.
        vm.stop(true);
        c.stopService(new Intent(c,DockerService.class));
        return vm.status().put("stopped",true);
    }
}
