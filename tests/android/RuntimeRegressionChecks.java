package dev.mateuszsury.androidagent;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.speech.RecognitionListener;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Regression checks exercised by AndroidAgentTests; no model or physical USB required. */
final class RuntimeRegressionChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final String TOKEN="runtime-regression-only";
    private static McpServer server(AtomicInteger calls){
        return new McpServer(TOKEN,new McpServer.Handler(){
            public JSONArray listTools(){return new JSONArray();}
            public JSONObject callTool(String name,JSONObject args){calls.incrementAndGet();return new JSONObject();}
            public void onEvent(JSONObject event){calls.incrementAndGet();}
            public File attachment(String id){return null;}
        },0);
    }
    private static int port(McpServer server)throws Exception{
        Field f=McpServer.class.getDeclaredField("socket");f.setAccessible(true);
        long until=SystemClock.elapsedRealtime()+5000;
        while(SystemClock.elapsedRealtime()<until){ServerSocket socket=(ServerSocket)f.get(server);if(socket!=null&&server.listening())return socket.getLocalPort();Thread.sleep(20);}
        throw new AssertionError("MCP fixture did not bind");
    }
    static void mcpShutdown()throws Exception{
        McpServer server=server(new AtomicInteger());List<Socket> clients=new ArrayList<>();server.start();
        try{
            int port=port(server);
            for(int i=0;i<12;i++){
                Socket s=new Socket("127.0.0.1",port);s.setSoTimeout(3000);clients.add(s);
                s.getOutputStream().write("POST /mcp HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));s.getOutputStream().flush();
            }
            Thread.sleep(100);
            server.stop();
            for(Socket s:clients){
                try{check(s.getInputStream().read()==-1,"MCP client survived stop");}
                catch(SocketTimeoutException e){throw new AssertionError("MCP Stop leaked an active or queued socket",e);}
                catch(SocketException expected){}
            }
        }finally{server.stop();for(Socket s:clients)s.close();}
    }
    private static String exchange(int port,String request)throws Exception{
        return exchange(port,request,3000);
    }
    private static String exchange(int port,String request,int timeout)throws Exception{
        try(Socket s=new Socket("127.0.0.1",port)){
            s.setSoTimeout(timeout);s.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));s.getOutputStream().flush();s.shutdownOutput();
            ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;
            while((n=s.getInputStream().read(b))!=-1)data.write(b,0,n);
            return new String(data.toByteArray(),StandardCharsets.UTF_8);
        }catch(SocketException closed){return "";}
    }
    static void mcpFraming()throws Exception{
        AtomicInteger calls=new AtomicInteger();McpServer server=server(calls);server.start();
        try{
            int port=port(server);
            String headers="POST /event HTTP/1.1\r\nAuthorization: Bearer "+TOKEN+"\r\nTransfer-Encoding: chunked\r\n\r\n";
            String chunk="2\r\n{}\r\n";
            check(exchange(port,headers+chunk+"0\r\n\r\n").startsWith("HTTP/1.1 204"),"Valid chunked event failed");
            check(calls.get()==1,"Valid event was not delivered");
            check(!exchange(port,headers+chunk).contains("204"),"Truncated chunks accepted");
            check(calls.get()==1,"Incomplete chunked event was dispatched");
            check(!exchange(port,headers+"2\r\n{}INVALID\r\n0\r\n\r\n").contains("204"),"Invalid chunk delimiter accepted");
            StringBuilder large=new StringBuilder("POST /event HTTP/1.1\r\nAuthorization: Bearer "+TOKEN+"\r\nContent-Length: 2\r\n");
            for(int i=0;i<130;i++)large.append("X-Header-").append(i).append(": fixture\r\n");
            try{check(!exchange(port,large+"\r\n{}").contains("204"),"Unbounded headers accepted");}catch(SocketException expected){}
            check(calls.get()==1,"Malformed event was dispatched");
        }finally{server.stop();}
    }
    private static String rpcBody(String name)throws Exception{
        return new JSONObject().put("jsonrpc","2.0").put("id",1).put("method","tools/call").put("params",new JSONObject().put("name",name).put("arguments",new JSONObject())).toString();
    }
    private static String rpcHeaders(String credential,String body){
        return "POST /mcp HTTP/1.1\r\nAuthorization: Bearer "+credential+"\r\nContent-Length: "+body.getBytes(StandardCharsets.UTF_8).length+"\r\n\r\n";
    }
    private static JSONObject rpcResult(String response)throws Exception{
        check(response.startsWith("HTTP/1.1 200"),"Missing MCP response");
        return new JSONObject(response.substring(response.indexOf("\r\n\r\n")+4)).getJSONObject("result");
    }
    private static void ownerClosed(McpHost host,String chat)throws Exception{
        long until=SystemClock.elapsedRealtime()+5000;
        while(true){try{host.requireReady(chat);return;}catch(IllegalStateException busy){if(SystemClock.elapsedRealtime()>=until)throw new AssertionError("Conversation cleanup did not finish",busy);Thread.sleep(10);}}
    }
    static void mcpRunGeneration(Context app)throws Exception{
        McpHost host=McpHost.get(app);Object fixtureOwner=new Object();String chat="runtime-generation-"+UUID.randomUUID();
        CountDownLatch authenticated=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> dispatchThread=new java.util.concurrent.atomic.AtomicReference<>();
        McpServer server=new McpServer(TOKEN,new McpServer.Handler(){
            public String principal(String credential){String owner=host.principal(credential);if(owner!=null)authenticated.countDown();return owner;}
            public JSONArray listTools()throws JSONException{return host.listTools();}
            public JSONObject callTool(String name,JSONObject args)throws Exception{dispatchThread.set(Thread.currentThread());return host.callTool(name,args);}
            public void onEvent(JSONObject event){host.onEvent(event);}
            public File attachment(String id){return host.attachment(id);}
        },0);
        ExecutorService client=Executors.newSingleThreadExecutor();host.acquire(fixtureOwner);server.start();
        try{
            int port=port(server);String first=host.credential(chat),body=rpcBody("device_status"),second;
            try(Socket socket=new Socket("127.0.0.1",port)){
                socket.setSoTimeout(10000);socket.getOutputStream().write(rpcHeaders(first,body).getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
                check(authenticated.await(5,TimeUnit.SECONDS),"Old request was not authenticated before revocation");
                host.revoke(chat);ownerClosed(host,chat);second=host.credential(chat);
                socket.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();socket.shutdownOutput();
                String status=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8)).readLine();
                check(status!=null&&status.startsWith("HTTP/1.1 401"),"Restarted chat revived an old authenticated request body");
            }
            check(!rpcResult(exchange(port,rpcHeaders(second,body)+body,10000)).optBoolean("isError"),"Current run credential failed");
            // Also cover an already-dispatched call queued on the Docker task
            // gate: validating once after the HTTP body is insufficient.
            String docker=rpcBody("docker_status"),third;Future<String> queued;
            synchronized(UsbDocker.taskAccess){
                dispatchThread.set(null);final String request=rpcHeaders(second,docker)+docker;
                queued=client.submit(()->exchange(port,request,10000));
                long until=SystemClock.elapsedRealtime()+5000;boolean blocked=false;
                while(SystemClock.elapsedRealtime()<until){Thread thread=dispatchThread.get();if(thread!=null&&thread.getState()==Thread.State.BLOCKED){StackTraceElement[] stack=thread.getStackTrace();if(stack.length>0&&stack[0].getClassName().equals(McpHost.class.getName())&&stack[0].getMethodName().equals("callTool")){blocked=true;break;}}Thread.sleep(10);}
                check(blocked,"Old Docker request did not reach the held task gate");
                host.revoke(chat);ownerClosed(host,chat);third=host.credential(chat);
            }
            JSONObject rejected=rpcResult(queued.get(10,TimeUnit.SECONDS));
            check(rejected.optBoolean("isError")&&rejected.toString().contains("access has ended"),"Restarted chat revived a queued Docker call from its previous run");
            check(!rpcResult(exchange(port,rpcHeaders(third,body)+body,10000)).optBoolean("isError"),"Newest run credential failed");
        }finally{server.stop();client.shutdownNow();host.revoke(chat);ownerClosed(host,chat);host.release(fixtureOwner);}
    }
    static void attachments(Context app)throws Exception{
        String id="runtime-panorama.png",imported=null;File f=Attachments.file(app,id),input=new File(app.getCacheDir(),"runtime..fixture.txt");
        Bitmap original=Bitmap.createBitmap(8192,8,Bitmap.Config.ARGB_8888),thumb=null;
        try{
            try(OutputStream out=new FileOutputStream(f)){check(original.compress(Bitmap.CompressFormat.PNG,100,out),"Fixture encoding failed");}
            thumb=Attachments.thumb(app,id,64);
            check(thumb!=null&&Math.max(thumb.getWidth(),thumb.getHeight())<=128,"Panorama thumbnail decoded the full long edge");
            try(OutputStream out=new FileOutputStream(input)){out.write("fixture".getBytes(StandardCharsets.UTF_8));}
            imported=Attachments.importUri(app,Uri.fromFile(input));
            check(Attachments.validId(imported)&&Attachments.file(app,imported).isFile(),"Imported filename cannot be opened again");
            try(Cursor cursor=app.getContentResolver().query(FilesProvider.attachmentUri(imported),new String[]{OpenableColumns.SIZE},null,null,null)){
                check(cursor!=null&&cursor.getColumnCount()==1&&cursor.moveToFirst()&&cursor.getLong(0)==7,"File provider ignored the requested size projection");
            }
        }finally{original.recycle();if(thumb!=null)thumb.recycle();f.delete();input.delete();if(imported!=null)Attachments.file(app,imported).delete();}
    }
    static void voice(Instrumentation instrumentation)throws Exception{
        final Throwable[] failure={null};
        instrumentation.runOnMainSync(()->{
            try{
                Voice.stopListening();
                Field current=Voice.class.getDeclaredField("current"),generation=Voice.class.getDeclaredField("recognitionGeneration"),pending=Voice.class.getDeclaredField("pending");
                current.setAccessible(true);generation.setAccessible(true);pending.setAccessible(true);
                Method make=Voice.class.getDeclaredMethod("recognitionListener",Voice.Listener.class,long.class);make.setAccessible(true);
                AtomicInteger oldEvents=new AtomicInteger(),newEvents=new AtomicInteger();
                Voice.Listener old=listener(oldEvents),fresh=listener(newEvents);
                generation.setLong(null,1000);current.set(null,old);RecognitionListener stale=(RecognitionListener)make.invoke(null,old,1000L);
                generation.setLong(null,1001);current.set(null,fresh);RecognitionListener active=(RecognitionListener)make.invoke(null,fresh,1001L);
                Bundle result=new Bundle();result.putStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION,new ArrayList<>(Collections.singletonList("fixture")));
                stale.onPartialResults(result);stale.onResults(result);stale.onError(android.speech.SpeechRecognizer.ERROR_CLIENT);
                check(oldEvents.get()==0&&newEvents.get()==0&&current.get(null)==fresh,"Old speech callbacks terminated/replied in the new session");
                active.onResults(result);check(newEvents.get()==1&&!Voice.listening(),"Current speech result was lost");
                pending.set(null,"must not speak after Stop");Voice.stopSpeaking();check(pending.get(null)==null,"TTS initialization can speak after Stop");
            }catch(Throwable e){failure[0]=e;}finally{Voice.stopListening();Voice.stopSpeaking();}
        });
        if(failure[0]!=null)throw new AssertionError("Voice lifecycle regression",failure[0]);
    }
    private static Voice.Listener listener(AtomicInteger events){return new Voice.Listener(){public void onPartial(String text){events.incrementAndGet();}public void onFinal(String text){events.incrementAndGet();}public void onEnd(String text){events.incrementAndGet();}};}
    static void stopDoesNotBlock(Instrumentation instrumentation,Context app)throws Exception{
        McpHost host=McpHost.get(app);UsbSessions hub=UsbSessions.get(app);CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
        Thread holder=new Thread(()->{synchronized(hub){held.countDown();try{release.await(8,TimeUnit.SECONDS);}catch(InterruptedException ignored){}}},"runtime-lock-fixture");holder.start();
        try{
            check(held.await(3,TimeUnit.SECONDS),"USB lock fixture failed");long start=SystemClock.elapsedRealtime();
            instrumentation.runOnMainSync(host::emergencyStop);
            check(SystemClock.elapsedRealtime()-start<3000,"Stop blocked main thread behind USB I/O");
            host.allowControl();check(host.controlRevoked()!=null,"Allow control bypassed unfinished Stop");
            boolean rejected=false;try{host.credential("stop-gate-fixture");}catch(IllegalStateException expected){rejected=true;}
            check(rejected,"New run started during old resource cleanup");
        }finally{release.countDown();holder.join(10000);long until=SystemClock.elapsedRealtime()+20000;while(host.stopping()&&SystemClock.elapsedRealtime()<until)Thread.sleep(25);check(!host.stopping(),"Stop cleanup never completed");host.allowControl();}
    }
    static void backgroundCommand(Context app)throws Exception{
        LocalShell.Result result=new LocalShell().run(app,"sleep 60 & echo BACKGROUND:$!");
        check(result.timedOut&&result.exitCode==124,"Background process escaped the shell time limit");
        String pid=result.output.substring(result.output.indexOf("BACKGROUND:")+11).trim().split("\\s+")[0];
        File stat=new File("/proc/"+pid+"/stat");
        if(stat.exists())try(BufferedReader r=new BufferedReader(new FileReader(stat))){String text=r.readLine();check(text==null||text.substring(text.lastIndexOf(')')+2).startsWith("Z "),"Background child survived cleanup");}
    }
}
