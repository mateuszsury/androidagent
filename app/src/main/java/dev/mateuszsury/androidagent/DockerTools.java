package dev.mateuszsury.androidagent;

import android.content.Context;
import org.json.*;

/** Shared MCP adapter: both Codex and Claude receive these same task-scoped tools. */
final class DockerTools {
    static JSONArray list() throws JSONException {
        JSONArray tools=new JSONArray();
        tools.put(schema("docker_status","Read local Docker VM state, architecture, disk usage, tasks and jobs. No model login or root required.",new String[]{}));
        tools.put(schema("docker_diagnostics","Read a bounded page of the last VM startup console and termination metadata, also after failed boot or app restart. No shell execution; offset is a byte offset and limit is at most 32768.",new String[]{"offset:integer","limit:integer"}));
        tools.put(schema("docker_create","Create a disposable build workspace in the running Docker VM. Save its task id. The user starts the VM in androidagent.",new String[]{"name"},"name"));
        tools.put(schema("docker_write","Write a UTF-8 source file or Dockerfile inside this task. Relative paths only; maximum 256 KiB.",new String[]{"task","path","content"},"task","path","content"));
        tools.put(schema("docker_build","Start an asynchronous Dockerfile build. Returns a job id; use docker_logs to inspect completion. Native Linux architecture of this phone.",new String[]{"task","dockerfile"},"task"));
        tools.put(schema("docker_run","Start this task's built image or a named OCI image. command is optional. port publishes a container port to the phone preview URL. Containers have memory/CPU/PID limits and only this task's /workspace mount.",new String[]{"task","image","command","port:integer","memory_mb:integer"},"task"));
        tools.put(schema("docker_exec","Start an asynchronous shell command INSIDE this task's container at /workspace. Use docker_logs with the returned job id.",new String[]{"task","command"},"task","command"));
        tools.put(schema("docker_logs","Read a job's state, exit code and bounded output, or container logs when job is omitted. No shell polling loops.",new String[]{"task","job"},"task"));
        tools.put(schema("docker_files","List files in this task's workspace.",new String[]{"task"},"task"));
        tools.put(schema("docker_read","Read a UTF-8 source file inside this task; relative paths only, maximum 256 KiB.",new String[]{"task","path"},"task","path"));
        tools.put(schema("docker_cancel","Cancel this task's active jobs and stop its containers. Keep source files and images.",new String[]{"task"},"task"));
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
        else {
            String op=name.substring("docker_".length());
            if(!java.util.Arrays.asList("create","write","read","build","run","exec","logs","files","cancel","cleanup").contains(op))throw new IllegalArgumentException("Unknown Docker tool");
            JSONObject request=new JSONObject(args.toString()).put("op",op);
            result=vm.request(request);
        }
        return new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",result.toString())))
                .put("structuredContent",result);
    }
}
