package dev.mateuszsury.androidagent;

import android.content.Context;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded asynchronous USB/storage jobs with owner checks and cooperative cancellation. */
final class UsbJobs {
    interface Work { JSONObject run(Job job)throws Exception; }
    static final class Job {
        final String id=UUID.randomUUID().toString(),owner,kind;volatile String state="queued",error="";volatile long done,total;volatile boolean cancelled;volatile JSONObject result;
        volatile String resource="";volatile Thread thread;Job(String o,String k){owner=o;kind=k;}
        void check()throws InterruptedException{if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException("Cancelled");}
        JSONObject json()throws JSONException{return new JSONObject().put("id",id).put("kind",kind).put("state",state).put("done",done).put("total",total).put("error",error).put("result",result);}
    }
    private static UsbJobs instance;
    static synchronized UsbJobs get(Context c){if(instance==null)instance=new UsbJobs();return instance;}
    private final Map<String,Job> jobs=new LinkedHashMap<>();
    private final ExecutorService pool=Executors.newFixedThreadPool(2);
    synchronized JSONObject start(String owner,String kind,Work work)throws Exception{
        if(jobs.values().stream().filter(j->j.state.equals("queued")||j.state.equals("running")).count()>=4)throw new IllegalStateException("USB job queue is full");
        if(jobs.size()>=40)jobs.entrySet().removeIf(e->!e.getValue().state.equals("queued")&&!e.getValue().state.equals("running"));
        Job j=new Job(owner,kind);jobs.put(j.id,j);
        pool.execute(()->{j.thread=Thread.currentThread();try{j.check();j.state="running";j.result=work.run(j);j.check();j.state="succeeded";}catch(Exception|LinkageError e){j.error=String.valueOf(e.getMessage());j.state=j.cancelled||e instanceof InterruptedException?"cancelled":"failed";}finally{j.thread=null;Thread.interrupted();}});return j.json();
    }
    synchronized JSONObject startSession(String owner,String session,String kind,Work work)throws Exception{JSONObject result=start(owner,kind,j->{j.resource=session;return work.run(j);});jobs.get(result.getString("id")).resource=session;return result;}
    synchronized boolean active(String resource){return jobs.values().stream().anyMatch(j->j.resource.equals(resource)&&(j.state.equals("queued")||j.state.equals("running")));}
    synchronized void cancelResource(String resource){for(Job j:jobs.values())if(j.resource.equals(resource)){j.cancelled=true;if(j.thread!=null)j.thread.interrupt();}}
    synchronized JSONObject status(String owner,String id,boolean cancel)throws Exception{
        Job j=jobs.get(id);if(j==null||!j.owner.equals(owner))throw new SecurityException("Job does not belong to this owner");if(cancel&&(j.state.equals("running")||j.state.equals("queued"))){j.cancelled=true;if(j.thread!=null)j.thread.interrupt();}return j.json();
    }
    synchronized void cancelOwner(String owner){for(Job j:jobs.values())if(j.owner.equals(owner)){j.cancelled=true;if(j.thread!=null)j.thread.interrupt();}}
    synchronized void cancelAll(){for(Job j:jobs.values()){j.cancelled=true;if(j.thread!=null)j.thread.interrupt();}}
}
