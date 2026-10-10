package dev.mateuszsury.androidagent;

import android.content.Context;
import android.hardware.usb.*;
import android.os.SystemClock;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import javax.crypto.Cipher;

/** AOSP ADB framing/AUTH, shell_v2 and sync over a physical Android USB transport. */
final class AdbClient implements Closeable {
    interface Transport extends Closeable { int read(byte[] b,int off,int size)throws IOException;void write(byte[] b)throws IOException; }
    static final class UsbTransport implements Transport {
        final UsbDeviceConnection connection;final UsbInterface face;final UsbEndpoint input,output;private final byte[] buffer=new byte[16384];int at,count;volatile boolean closed;
        UsbTransport(UsbDevice d,UsbDeviceConnection c)throws IOException{
            connection=c;UsbInterface found=null;UsbEndpoint in=null,out=null;
            for(int i=0;i<d.getInterfaceCount();i++){UsbInterface f=d.getInterface(i);if(f.getInterfaceClass()==255&&f.getInterfaceSubclass()==66&&f.getInterfaceProtocol()==1){for(int n=0;n<f.getEndpointCount();n++){UsbEndpoint e=f.getEndpoint(n);if(e.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK){if(e.getDirection()==UsbConstants.USB_DIR_IN)in=e;else out=e;}}if(in!=null&&out!=null){found=f;break;}}}
            if(found==null||!c.claimInterface(found,false))throw new IOException("ADB USB interface unavailable");face=found;input=in;output=out;
        }
        public int read(byte[] b,int off,int size)throws IOException{while(count==at){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("ADB cancelled");if(closed)return -1;count=connection.bulkTransfer(input,buffer,buffer.length,1000);at=0;if(count<=0){count=0;continue;}}int n=Math.min(size,count-at);System.arraycopy(buffer,at,b,off,n);at+=n;return n;}
        public synchronized void write(byte[] b)throws IOException{int at=0;while(at<b.length){if(closed)throw new IOException("ADB disconnected");int n=connection.bulkTransfer(output,b,at,Math.min(16384,b.length-at),3000);if(n<=0)throw new IOException("ADB USB write failed");at+=n;}if(b.length>0&&b.length%output.getMaxPacketSize()==0)connection.bulkTransfer(output,new byte[0],0,1000);}
        public void close(){closed=true;connection.releaseInterface(face);connection.close();}
    }
    static final class TcpTransport implements Transport {
        final Socket socket;TcpTransport(String host,int port)throws IOException{socket=new Socket();socket.connect(new java.net.InetSocketAddress(host,port),5000);socket.setSoTimeout(90000);}
        public int read(byte[] b,int off,int n)throws IOException{return socket.getInputStream().read(b,off,n);}public void write(byte[] b)throws IOException{socket.getOutputStream().write(b);socket.getOutputStream().flush();}public void close()throws IOException{socket.close();}
    }
    static final int CNXN=0x4e584e43,AUTH=0x48545541,OPEN=0x4e45504f,OKAY=0x59414b4f,WRTE=0x45545257,CLSE=0x45534c43;
    final Context app;final Transport transport;private final Map<Integer,Stream> streams=new HashMap<>();private int next=1,maxData=65536;volatile boolean connected,closed;volatile String banner="",error="";private boolean skipChecksum;
    AdbClient(Context c,Transport t){app=c;transport=t;}
    private static final class Packet{int command,a,b;byte[] data;}
    static byte[] le(int...values){ByteBuffer b=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN);for(int v:values)b.putInt(v);return b.array();}
    static int number(byte[] b,int off){return ByteBuffer.wrap(b,off,4).order(ByteOrder.LITTLE_ENDIAN).getInt();}
    private byte[] exact(int n)throws IOException{if(n<0||n>1024*1024)throw new IOException("Invalid ADB packet length");byte[] b=new byte[n];int at=0;while(at<n){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("ADB cancelled");if(closed)throw new IOException("ADB disconnected");int got=transport.read(b,at,n-at);if(got<0)throw new EOFException("ADB disconnected");at+=got;}return b;}
    private Packet readPacket()throws IOException{byte[] h=exact(24);Packet p=new Packet();p.command=number(h,0);p.a=number(h,4);p.b=number(h,8);if(number(h,20)!=(p.command^-1))throw new IOException("Invalid ADB magic");p.data=exact(number(h,12));int sum=0;for(byte b:p.data)sum+=b&255;if(!(skipChecksum||(p.command==CNXN&&p.a>=0x01000001))&&sum!=number(h,16))throw new IOException("ADB checksum mismatch");return p;}
    private synchronized void send(int command,int a,int b,byte[] data)throws IOException{int sum=0;if(!skipChecksum)for(byte x:data)sum+=x&255;transport.write(le(command,a,b,data.length,sum,command^-1));if(data.length>0)transport.write(data);}
    synchronized JSONObject connect()throws Exception{
        if(connected)return status();if(closed)throw new IOException("ADB disconnected");
        java.util.Timer timer=new java.util.Timer(true);timer.schedule(new java.util.TimerTask(){public void run(){error="unauthorized: accept the RSA key on the target phone";close();}},90000);
        try { KeyPair key=key();send(CNXN,0x01000001,maxData,"host::features=shell_v2,cmd\u0000".getBytes(StandardCharsets.UTF_8));boolean signed=false;long deadline=SystemClock.elapsedRealtime()+90000;
        while(SystemClock.elapsedRealtime()<deadline){Packet p=readPacket();if(p.command==AUTH&&p.a==1){if(!signed){send(AUTH,2,0,signature(key,p.data));signed=true;}else send(AUTH,3,0,publicKey((RSAPublicKey)key.getPublic()));}else if(p.command==CNXN){maxData=Math.min(65536,p.b);if(maxData<4096)throw new IOException("Invalid ADB maxdata");skipChecksum=p.a>=0x01000001;banner=new String(p.data,StandardCharsets.UTF_8).replace("\u0000","");connected=true;Thread reader=new Thread(this::pump,"adb-usb-rx");reader.setDaemon(true);reader.start();return status();}}
        throw new IOException("ADB authorization timed out; accept the key on the target phone");
        }catch(Exception e){if(error.isEmpty())error=String.valueOf(e.getMessage());close();throw new IOException(error,e);}finally{timer.cancel();}
    }
    JSONObject status()throws JSONException{return new JSONObject().put("connected",connected&&!closed).put("state",connected?"connected":error.startsWith("unauthorized")?"unauthorized":closed?"offline":"unauthorized").put("banner",banner).put("error",error).put("access","target Android shell, not root");}
    private static final Object KEY_LOCK=new Object();
    private KeyPair key()throws Exception{synchronized(KEY_LOCK){
        File dir=new File(app.getFilesDir(),"adb-key");dir.mkdirs();File priv=new File(dir,"private.pk8"),pub=new File(dir,"public.der");KeyFactory f=KeyFactory.getInstance("RSA");
        if(priv.isFile()&&pub.isFile())return new KeyPair(f.generatePublic(new X509EncodedKeySpec(java.nio.file.Files.readAllBytes(pub.toPath()))),f.generatePrivate(new PKCS8EncodedKeySpec(java.nio.file.Files.readAllBytes(priv.toPath()))));
        KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);KeyPair k=g.generateKeyPair();java.nio.file.Files.write(priv.toPath(),k.getPrivate().getEncoded());java.nio.file.Files.write(pub.toPath(),k.getPublic().getEncoded());return k;}
    }
    static byte[] signature(KeyPair key,byte[] token)throws Exception{if(token.length!=20)throw new IOException("Invalid ADB AUTH challenge");byte[] prefix=new byte[]{0x30,0x21,0x30,9,6,5,0x2b,0x0e,3,2,0x1a,5,0,4,0x14};byte[] digest=new byte[prefix.length+20];System.arraycopy(prefix,0,digest,0,prefix.length);System.arraycopy(token,0,digest,prefix.length,20);Cipher cipher=Cipher.getInstance("RSA/ECB/PKCS1Padding");cipher.init(Cipher.ENCRYPT_MODE,key.getPrivate());return cipher.doFinal(digest);}
    static byte[] publicKey(RSAPublicKey key){BigInteger n=key.getModulus(),r=BigInteger.ONE.shiftLeft(32);ByteBuffer b=ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);b.putInt(64).putInt(r.subtract(n.mod(r).modInverse(r)).intValue());little(b,n,256);little(b,BigInteger.ONE.shiftLeft(4096).mod(n),256);b.putInt(key.getPublicExponent().intValue());return (Base64.encodeToString(b.array(),Base64.NO_WRAP)+" androidagent@android\u0000").getBytes(StandardCharsets.US_ASCII);}
    static void little(ByteBuffer out,BigInteger n,int bytes){byte[] a=n.toByteArray();for(int i=0;i<bytes;i++)out.put(i<a.length?a[a.length-1-i]:0);}
    private void pump(){try{while(!closed){Packet p=readPacket();Stream s;synchronized(streams){s=streams.get(p.b);}if(s==null){if(p.command==OPEN)send(CLSE,0,p.a,new byte[0]);continue;}synchronized(s){if(p.command==OKAY){s.remote=p.a;s.ready=true;s.notifyAll();}else if(p.command==WRTE){if(s.queued+p.data.length>1048576)throw new IOException("ADB stream receive limit exceeded");s.chunks.add(p.data);s.queued+=p.data.length;send(OKAY,s.id,p.a,new byte[0]);s.notifyAll();}else if(p.command==CLSE){s.eof=true;send(CLSE,s.id,p.a,new byte[0]);s.notifyAll();}}}}catch(Exception e){if(!closed)error=String.valueOf(e.getMessage());close();}}
    final class Stream implements Closeable {
        final int id;int remote,at,queued;boolean ready,eof;final ArrayDeque<byte[]> chunks=new ArrayDeque<>();Stream(int n){id=n;}
        synchronized int read(byte[] b,int size,long deadline)throws Exception{while(chunks.isEmpty()&&!eof&&!closed){long left=deadline-SystemClock.elapsedRealtime();if(left<=0)throw new java.net.SocketTimeoutException("ADB stream timeout");wait(Math.min(200,left));}if(chunks.isEmpty())return -1;byte[] head=chunks.peek();int n=Math.min(size,head.length-at);System.arraycopy(head,at,b,0,n);at+=n;queued-=n;if(at==head.length){chunks.remove();at=0;}return n;}
        byte[] readExact(int size,long deadline)throws Exception{if(size<0||size>1048576)throw new IOException("ADB stream frame exceeds limit");byte[] result=new byte[size],temp=new byte[Math.min(65536,size)];int at=0;while(at<size){int n=read(temp,Math.min(temp.length,size-at),deadline);if(n<0)throw new EOFException("ADB stream ended early");System.arraycopy(temp,0,result,at,n);at+=n;}return result;}
        synchronized void write(byte[] data,long deadline)throws Exception{for(int at=0;at<data.length;){while(!ready&&!eof&&!closed){long left=deadline-SystemClock.elapsedRealtime();if(left<=0)throw new java.net.SocketTimeoutException("ADB write timeout");wait(Math.min(200,left));}if(eof||closed)throw new EOFException("ADB stream closed");ready=false;int n=Math.min(maxData,data.length-at);send(WRTE,id,remote,Arrays.copyOfRange(data,at,at+n));at+=n;}}
        public void close(){synchronized(streams){streams.remove(id);}synchronized(this){eof=true;notifyAll();}try{if(remote!=0&&!AdbClient.this.closed)send(CLSE,id,remote,new byte[0]);}catch(IOException ignored){}}
    }
    private Stream open(String service)throws Exception{if(!connected||closed)throw new IOException("Connect and authorize ADB first");Stream s; synchronized(streams){s=new Stream(next++);streams.put(s.id,s);}try{send(OPEN,s.id,0,(service+"\u0000").getBytes(StandardCharsets.UTF_8));return s;}catch(Exception e){s.close();throw e;}}
    JSONObject exec(String command,int timeout)throws Exception{
        if(command.isEmpty()||command.length()>8192||command.indexOf('\u0000')>=0)throw new IllegalArgumentException("Invalid ADB command");long deadline=SystemClock.elapsedRealtime()+Math.max(1000,Math.min(120000,timeout));boolean v2=banner.contains("shell_v2");ByteArrayOutputStream stdout=new ByteArrayOutputStream(),stderr=new ByteArrayOutputStream();Integer exit=null;
        try(Stream s=open((v2?"shell,v2,raw:":"shell:")+command)){
            if(v2){while(true){byte[] h;try{h=s.readExact(5,deadline);}catch(EOFException e){throw new IOException("ADB shell ended without an exit frame",e);}int id=h[0]&255;byte[] data=s.readExact(number(h,1),deadline);if(id==1)bounded(stdout,data);else if(id==2)bounded(stderr,data);else if(id==3){if(data.length!=1)throw new IOException("Invalid shell exit frame");exit=data[0]&255;break;}}}
            else{byte[] b=new byte[8192];int n;while((n=s.read(b,b.length,deadline))!=-1)bounded(stdout,Arrays.copyOf(b,n));}
        }
        return new JSONObject().put("stdout",stdout.toString("UTF-8")).put("stderr",stderr.toString("UTF-8")).put("exit_code",exit==null?JSONObject.NULL:exit).put("exit_code_supported",v2);
    }
    private static void bounded(ByteArrayOutputStream out,byte[] data){byte[] old=out.toByteArray();out.reset();int keep=Math.min(old.length,Math.max(0,65536-data.length));out.write(old,old.length-keep,keep);int size=Math.min(data.length,65536);out.write(data,data.length-size,size);}
    private static byte[] sync(String command,byte[] value){byte[] b=new byte[8+value.length];System.arraycopy(command.getBytes(StandardCharsets.US_ASCII),0,b,0,4);System.arraycopy(le(value.length),0,b,4,4);System.arraycopy(value,0,b,8,value.length);return b;}
    private static String path(String path)throws IOException{if(!path.startsWith("/")||path.length()>1024||path.indexOf('\u0000')>=0||path.indexOf(',')>=0)throw new IOException("Use an absolute target path without commas");return path;}
    JSONObject push(File file,String path,UsbJobs.Job job)throws Exception{
        path(path);job.total=file.length();if(job.total>512L*1024*1024)throw new IOException("File exceeds 512 MiB");long deadline=SystemClock.elapsedRealtime()+600000;
        try(Stream s=open("sync:");InputStream in=new FileInputStream(file)){s.write(sync("SEND",(path+",33188").getBytes(StandardCharsets.UTF_8)),deadline);byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){job.check();s.write(sync("DATA",Arrays.copyOf(b,n)),deadline);job.done+=n;}s.write(concat("DONE".getBytes(StandardCharsets.US_ASCII),le((int)(System.currentTimeMillis()/1000))),deadline);byte[] h=s.readExact(8,deadline);String code=new String(h,0,4,StandardCharsets.US_ASCII);if(!code.equals("OKAY"))throw new IOException(new String(s.readExact(number(h,4),deadline),StandardCharsets.UTF_8));return new JSONObject().put("bytes",job.done).put("path",path);}
    }
    JSONObject pull(String path,String owner,UsbJobs.Job job)throws Exception{
        path(path);long deadline=SystemClock.elapsedRealtime()+600000;try(Stream s=open("sync:")){s.write(sync("RECV",path.getBytes(StandardCharsets.UTF_8)),deadline);
            InputStream input=new InputStream(){byte[] block=new byte[0];int at;boolean end;public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}public int read(byte[] b,int off,int n)throws IOException{try{job.check();while(at==block.length){if(end)return -1;byte[] h=s.readExact(8,deadline);String c=new String(h,0,4,StandardCharsets.US_ASCII);if(c.equals("DONE")){end=true;return -1;}int size=number(h,4);if(!c.equals("DATA"))throw new IOException(new String(s.readExact(size,deadline),StandardCharsets.UTF_8));if(size>65536)throw new IOException("Invalid sync data length");block=s.readExact(size,deadline);at=0;}int size=Math.min(n,block.length-at);System.arraycopy(block,at,b,off,size);at+=size;return size;}catch(Exception e){throw new IOException(e);}}};return UsbArtifacts.save(app,input,new File(path).getName(),owner,job,512L*1024*1024);}
    }
    JSONObject install(File file,UsbJobs.Job job)throws Exception{String temp="/data/local/tmp/androidagent-"+UUID.randomUUID()+".apk";push(file,temp,job);try{JSONObject result=exec("pm install -r "+quote(temp),120000);if(!result.optString("stdout").contains("Success")||result.has("exit_code")&&!result.isNull("exit_code")&&result.getInt("exit_code")!=0)throw new IOException(result.toString());return result;}finally{try{exec("rm -f "+quote(temp),10000);}catch(Exception ignored){}}}
    static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
    static byte[] concat(byte[] a,byte[] b){byte[] o=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,o,a.length,b.length);return o;}
    @Override public void close(){closed=true;connected=false;try{transport.close();}catch(IOException ignored){}synchronized(streams){for(Stream s:streams.values())synchronized(s){s.eof=true;s.notifyAll();}streams.clear();}}
}
