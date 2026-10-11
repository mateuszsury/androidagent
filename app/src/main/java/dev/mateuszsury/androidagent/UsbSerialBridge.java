package dev.mateuszsury.androidagent;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only, per-task credentials. QEMU's host alias is 10.0.2.2. */
final class UsbSerialBridge {
    static final int PORT=18782;
    static final Map<String,Grant> grants=new HashMap<>();
    static ServerSocket server;
    static final ExecutorService workers=new ThreadPoolExecutor(0,4,30,TimeUnit.SECONDS,new SynchronousQueue<>(),new ThreadPoolExecutor.AbortPolicy());
    static final class Grant{final UsbSessions.Session session;final String token=UUID.randomUUID().toString()+UUID.randomUUID();Grant(UsbSessions.Session s){session=s;}}
    static synchronized void listen()throws IOException{
        if(server!=null)return;ServerSocket candidate=new ServerSocket();
        try{candidate.setReuseAddress(true);candidate.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),PORT));}
        catch(IOException e){try{candidate.close();}catch(IOException ignored){}throw e;}
        server=candidate;final ServerSocket listener=candidate;
        Thread t=new Thread(()->{while(!listener.isClosed())try{Socket s=listener.accept();try{workers.execute(()->serve(s));}catch(RejectedExecutionException e){s.close();}}catch(IOException e){break;}},"usb-serial-bridge");t.setDaemon(true);t.start();
    }
    static JSONObject attach(Context c,String owner,String id,String task)throws Exception {
        synchronized(UsbDocker.taskAccess) {
            McpHost.get(c).requireCaller(owner);
            if(task==null||!task.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid task id");
            UsbSessions hub=UsbSessions.get(c);DockerVm vm=DockerVm.get(c);
            if(!"ready".equals(vm.state))throw new IllegalStateException("Start Docker first");
            final UsbSessions.Session session;final Grant grant;
            synchronized(hub) {
                hub.authorizeTask(owner,task);session=hub.owned(owner,id);
                if(session.closed||!session.task.isEmpty())throw new IllegalStateException("Disconnected or already assigned");
                SerialTransport port=UsbTools.serial(session,true);port.reserveBridge();
                grant=new Grant(session);
                try {synchronized(UsbSerialBridge.class){listen();session.task=task;grants.put(grant.token,grant);}}
                catch(Exception e){port.releaseBridge();throw e;}
            }
            String url="androidagent://10.0.2.2:"+PORT+"/"+grant.token;
            try {
                JSONObject result=vm.request(new JSONObject().put("op","serial_attach").put("task",task).put("session",session.id).put("url",url));
                synchronized(hub) {UsbDocker.requireCurrent(hub,owner,session,task);}
                return result.put("url",url).put("usage","Install pyserial in your image; PYTHONPATH and ANDROIDAGENT_SERIAL_URL are injected. Use serial.serial_for_url(os.environ['ANDROIDAGENT_SERIAL_URL']).");
            } catch(Exception e) {
                synchronized(hub) {if(hub.sessions.get(id)==session)hub.close(owner,id);else revoke(session);}
                // A failed attachment closes the lease. Retrying requires a new
                // session UUID, which old asynchronous detaches cannot revoke.
                UsbDocker.revokeGrant(c,task,"",session.id,0);
                throw e;
            }
        }
    }
    static void revoke(UsbSessions.Session session) {
        synchronized(UsbSerialBridge.class) {
            grants.values().removeIf(g->g.session==session);
            if(grants.isEmpty()&&server!=null){try{server.close();}catch(IOException ignored){}server=null;}
        }
        if(session.serial!=null)session.serial.releaseBridge();
    }
    private static String line(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int x;while((x=in.read())!=-1){if(x==10)return b.toString("US-ASCII").replace("\r","");if(b.size()>=8192)throw new IOException("Header too long");b.write(x);}throw new EOFException();}
    static void serve(Socket socket){try(Socket s=socket){s.setSoTimeout(5000);InputStream in=s.getInputStream();String request=line(in);int size=0;String token="";for(int i=0;i<40;i++){String h=line(in);if(h.isEmpty())break;int at=h.indexOf(':');if(at<0)throw new IOException("Invalid header");String key=h.substring(0,at).toLowerCase(Locale.US),value=h.substring(at+1).trim();if(key.equals("authorization")&&value.startsWith("Bearer "))token=value.substring(7);if(key.equals("content-length"))size=Integer.parseInt(value);if(i==39)throw new IOException("Too many headers");}
        if(!request.equals("POST /serial HTTP/1.1")||size<2||size>180000)throw new IOException("Invalid bridge request");Grant g;synchronized(UsbSerialBridge.class){g=grants.get(token);}JSONObject result;
        if(g==null)reply(s,403,new JSONObject().put("error","revoked"));else{
            byte[] b=new byte[size];int at=0;while(at<size){int n=in.read(b,at,size-at);if(n<0)throw new EOFException();at+=n;}JSONObject a=new JSONObject(new String(b,StandardCharsets.UTF_8));
            try{synchronized(g){synchronized(UsbSerialBridge.class){if(grants.get(token)!=g)throw new SecurityException("revoked");}SerialTransport port=g.session.serial;if(port==null||port.closed)throw new IOException("disconnected");String op=a.getString("op");result=new JSONObject();
                if(op.equals("read")){int limit=a.optInt("limit",4096);if(limit<1||limit>65536)throw new IllegalArgumentException("Invalid read size");UsbBuffer.Slice slice=port.received.read(a.getLong("cursor"),limit);result.put("data",UsbTools.encode(slice.bytes,"base64")).put("cursor",slice.cursor).put("lost",slice.lost);}
                else if(op.equals("write"))result.put("bytes",port.write(UsbTools.decode(a.getString("data"),"base64"),3000));
                else if(op.equals("configure")){port.configure(a.getInt("baud"),a.optInt("bits",8),a.optInt("stop",1),a.optInt("parity",0));result.put("ok",true);}
                else if(op.equals("control")){port.control(a.getBoolean("dtr"),a.getBoolean("rts"),a.optBoolean("break"));result.put("ok",true);}
                else if(op.equals("status"))result.put("cursor",port.received.end()).put("baud",port.baud);
                else throw new IllegalArgumentException("Unknown operation");}reply(s,200,result);}catch(Exception e){reply(s,409,new JSONObject().put("error",String.valueOf(e.getMessage())));}
        }
    }catch(Exception ignored){}}
    private static void reply(Socket s,int code,JSONObject body)throws IOException{byte[] b=body.toString().getBytes(StandardCharsets.UTF_8);OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 "+code+" Result\r\nContent-Type: application/json\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(b);out.flush();}
}
