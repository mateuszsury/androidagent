package dev.mateuszsury.androidagent;

import android.content.Context;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Identical, explicitly leased USB API for Codex and Claude Code. */
final class UsbTools {
    static boolean handles(String n){return n.startsWith("usb_")||n.startsWith("adb_")||n.startsWith("esp_");}
    static JSONArray list()throws JSONException{
        JSONArray a=new JSONArray();
        add(a,"usb_devices","Enumerate USB Host devices and generations; does not request Android permission.","","");
        add(a,"usb_open","Return an existing session assigned by the foreground USB panel. Agents cannot create or transfer physical leases.","device,profile","device,profile");
        add(a,"usb_status","List your sessions, counters and connection state. Optional session limits the result.","session","");
        add(a,"usb_close","Close your selected session and revoke Docker access.","session","session");
        add(a,"usb_serial_configure","Set UART parameters. Defaults: 115200, 8 data bits, 1 stop bit, parity 0. Cannot change a port being flashed or bridged.","session,baud:integer,bits:integer,stop:integer,parity:integer","session");
        add(a,"usb_serial_read","Read a copy of the bounded UART log from an absolute cursor. Reports lost bytes. Maximum 65536 bytes; encoding utf8/hex/base64.","session,cursor:integer,limit:integer,encoding","session");
        add(a,"usb_serial_write","Send bytes to the selected UART; encoding utf8/hex/base64, maximum 65536 bytes, timeout 1–5000 ms.","session,data,encoding,timeout_ms:integer","session,data");
        add(a,"usb_serial_control","Explicitly set DTR, RTS and break. May reset a connected board.","session,dtr:boolean,rts:boolean,break:boolean","session,dtr,rts,break");
        add(a,"usb_serial_save","Preserve the current UART log as a read-only artifact.","session","session");
        add(a,"esp_info","Asynchronously identify ESP ROM and security state. auto_reset defaults false: manually enter bootloader or explicitly enable board reset.","session,auto_reset:boolean","session");
        add(a,"esp_flash","Flash and verify explicit firmware segments. expected_chip is the numeric chip from esp_info. segments [{artifact,offset}] must be ordered and non-overlapping. Secure/encrypted targets or unavailable security checks are refused.","session,expected_chip:integer,auto_reset:boolean,segments:array","session,expected_chip,segments");
        add(a,"adb_status","Read target state, or connect=true to start bounded ADB AUTH. Accept the RSA key on the second phone, separately from Android USB permission.","session,connect:boolean","session");
        add(a,"adb_exec","Asynchronous shell command on the selected SECOND Android, with stdout/stderr and shell_v2 exit code. Target shell privileges; timeout maximum 120000 ms.","session,command,timeout_ms:integer","session,command");
        add(a,"adb_logcat","Capture a bounded logcat snapshot. filter is a logcat tag specification (not shell syntax).", "session,filter","session");
        add(a,"adb_push","Copy an owned artifact to an absolute path on the second phone, asynchronously, maximum 512 MiB.","session,artifact,path","session,artifact,path");
        add(a,"adb_pull","Preserve a file from an absolute target path as an artifact, asynchronously, maximum 512 MiB.","session,path","session,path");
        add(a,"adb_install","Install an explicitly owned APK artifact on the selected second phone, asynchronously.","session,artifact","session,artifact");
        add(a,"usb_storage_roots","List only explicitly assigned SAF directories. Unavailable media remain visible with an error.","","");
        add(a,"usb_storage_list","List a known directory handle; page size 1–200.","document,offset:integer,limit:integer","document");
        add(a,"usb_storage_read","Read a file, at most 65536 bytes, as utf8/hex/base64. Larger files use import.","document,encoding","document");
        add(a,"usb_storage_create","Create a named file in a writable directory. Returns a document handle.","parent,name,mime","parent,name");
        add(a,"usb_storage_write","Overwrite an explicitly selected writable file, up to 65536 bytes. A provider error can leave a partial file.","document,data,encoding","document,data");
        add(a,"usb_storage_copy","Copy between explicitly selected documents, asynchronously; limit 512 MiB and SHA256. Partial writes may remain on cancellation.","source,target","source,target");
        add(a,"usb_storage_import","Import a selected document as an owned artifact; asynchronous, limit 512 MiB.","document","document");
        add(a,"usb_storage_export","Copy an owned USB artifact or preserved Docker export into an explicitly selected writable document, asynchronously.","artifact,docker_export,target","target");
        add(a,"usb_storage_delete","Delete only the explicitly selected writable document.","document","document");
        add(a,"usb_job_status","Read your asynchronous USB job state, progress and result.","job","job");
        add(a,"usb_job_cancel","Cancel your USB job; interrupted writes may be partial.","job","job");
        add(a,"usb_docker_attach","Assign your Docker-profile physical USB session to one running VM task. Raw libusb device only, no broad /dev mount. Existing containers must be restarted after attachment.","session,task","session,task");
        add(a,"usb_docker_serial","Assign your serial session to one Docker task using a scoped bridge with baud/DTR/RTS/break. Returns the pyserial URL; existing containers must be restarted.","session,task","session,task");
        add(a,"usb_docker_detach","Revoke this session's Docker grant and close the physical session.","session","session");
        return a;
    }
    private static void add(JSONArray a,String n,String d,String fields,String required)throws JSONException{
        JSONObject p=new JSONObject();if(!fields.isEmpty())for(String f:fields.split(",")){String[] v=f.split(":");JSONObject type=new JSONObject().put("type",v.length==1?"string":v[1]);if(v.length>1&&v[1].equals("array"))type.put("items",new JSONObject().put("type","object").put("properties",new JSONObject().put("artifact",new JSONObject().put("type","string")).put("offset",new JSONObject().put("type","integer"))).put("required",new JSONArray().put("artifact").put("offset")).put("additionalProperties",false));p.put(v[0],type);}
        JSONObject s=new JSONObject().put("type","object").put("properties",p).put("additionalProperties",false);if(!required.isEmpty())s.put("required",new JSONArray(required.split(",")));a.put(new JSONObject().put("name",n).put("description",d).put("inputSchema",s));
    }
    static JSONObject result(Object value)throws JSONException{JSONObject r=value instanceof JSONObject?(JSONObject)value:new JSONObject().put("items",value);return new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",r.toString()))).put("structuredContent",r);}
    static byte[] decode(String data,String encoding)throws Exception{byte[] b;if(encoding.equals("utf8")){if(data.length()>65536)throw new IllegalArgumentException("Data exceeds 65536 bytes");b=data.getBytes(StandardCharsets.UTF_8);}else if(encoding.equals("base64")){if(data.length()>262144)throw new IllegalArgumentException("Encoded data exceeds size limit");b=Base64.decode(data,Base64.DEFAULT);}else if(encoding.equals("hex")){if((data.length()&1)!=0)throw new IllegalArgumentException("Invalid hex");if(data.length()>131072)throw new IllegalArgumentException("Data exceeds 65536 bytes");b=new byte[data.length()/2];for(int i=0;i<b.length;i++){int hi=hexDigit(data.charAt(i*2)),lo=hexDigit(data.charAt(i*2+1));if(hi<0||lo<0)throw new IllegalArgumentException("Invalid hex");b[i]=(byte)((hi<<4)|lo);}}else throw new IllegalArgumentException("encoding must be utf8, hex or base64");if(b.length>65536)throw new IllegalArgumentException("Data exceeds 65536 bytes");return b;}
    private static int hexDigit(char c){return c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:c>='A'&&c<='F'?c-'A'+10:-1;}
    static String logcatCommand(String filter){if(!filter.matches("[A-Za-z0-9_.*:/ -]{1,160}")||filter.trim().isEmpty())throw new IllegalArgumentException("Invalid logcat filter");StringBuilder command=new StringBuilder("logcat -d -t 1000");for(String spec:filter.trim().split(" +")){if(spec.startsWith("-"))throw new IllegalArgumentException("Use logcat tag filters, not command options");command.append(' ').append(AdbClient.quote(spec));}return command.toString();}
    static String encode(byte[] b,String e){if(e.equals("utf8"))return new String(b,StandardCharsets.UTF_8);if(e.equals("hex"))return UsbArtifacts.hex(b);if(e.equals("base64"))return Base64.encodeToString(b,Base64.NO_WRAP);throw new IllegalArgumentException("Invalid encoding");}
    static SerialTransport serial(UsbSessions.Session s,boolean write){if(s.serial==null)throw new IllegalStateException("Not a serial session");if(write&&(s.closed||s.serial.exclusive||s.serial.bridged||!s.task.isEmpty()))throw new IllegalStateException("Port is reserved for firmware or Docker, or disconnected");return s.serial;}
    static AdbClient adb(UsbSessions.Session s){if(s.adb==null)throw new IllegalStateException("Not an ADB session");return s.adb;}
    static JSONObject call(Context c,String owner,String n,JSONObject a)throws Exception{
        UsbSessions hub=UsbSessions.get(c);UsbStorage st=UsbStorage.get(c);UsbJobs jobs=UsbJobs.get(c);Object r;
        if(n.equals("usb_devices"))r=hub.list();
        else if(n.equals("usb_open")){UsbSessions.Session found=null;synchronized(hub){for(UsbSessions.Session s:hub.sessions.values())if(s.owner.equals(owner)&&s.device.equals(a.getString("device"))&&s.profile.equals(a.getString("profile")))found=s;}if(found==null)throw new SecurityException("permission_required: open and assign the requested profile in the USB panel");r=found.json();}
        else if(n.equals("usb_status"))r=a.has("session")?hub.owned(owner,a.getString("session")).json():hub.sessions(owner);
        else if(n.equals("usb_close")||n.equals("usb_docker_detach")){hub.close(owner,a.getString("session"));r=new JSONObject().put("closed",true);}
        else if(n.equals("usb_job_status")||n.equals("usb_job_cancel"))r=jobs.status(owner,a.getString("job"),n.endsWith("cancel"));
        else if(n.equals("usb_docker_attach"))r=jobs.startSession(owner,a.getString("session"),n,j->UsbDocker.attach(c,owner,a.getString("session"),a.getString("task")));
        else if(n.equals("usb_docker_serial"))r=UsbSerialBridge.attach(c,owner,a.getString("session"),a.getString("task"));
        else if(n.startsWith("usb_serial_")){
            UsbSessions.Session session=hub.owned(owner,a.getString("session"));SerialTransport port=serial(session,false);
            synchronized(port){serial(session,!n.equals("usb_serial_read")&&!n.equals("usb_serial_save"));
            if(n.endsWith("configure")){port.configure(a.optInt("baud",115200),a.optInt("bits",8),a.optInt("stop",1),a.optInt("parity",0));r=new JSONObject().put("baud",port.baud);}
            else if(n.endsWith("write")){int timeout=a.optInt("timeout_ms",3000);if(timeout<1||timeout>5000)throw new IllegalArgumentException("Invalid timeout");r=new JSONObject().put("bytes",port.write(decode(a.getString("data"),a.optString("encoding","utf8")),timeout));}
            else if(n.endsWith("control")){port.control(a.getBoolean("dtr"),a.getBoolean("rts"),a.getBoolean("break"));r=new JSONObject().put("ok",true);}
            else if(n.endsWith("save")){UsbBuffer.Slice s=port.received.read(Math.max(0,port.received.end()-65536),65536);r=UsbArtifacts.save(c,new ByteArrayInputStream(s.bytes),"uart.log",owner,null,65536);}
            else {UsbBuffer.Slice s=port.received.read(a.optLong("cursor",0),a.optInt("limit",4096));String encoding=a.optString("encoding","utf8");r=new JSONObject().put("data",encode(s.bytes,encoding)).put("encoding",encoding).put("cursor",s.cursor).put("lost",s.lost).put("connected",!port.closed);}}
        }else if(n.startsWith("esp_")){
            UsbSessions.Session s=hub.owned(owner,a.getString("session"));SerialTransport p=serial(s,true);JSONArray segments=a.optJSONArray("segments");if(n.equals("esp_flash")&&(segments==null||segments.length()==0))throw new IllegalArgumentException("Firmware segments required");int count=segments==null?0:segments.length();File[] files=new File[count];long[] offsets=new long[count];for(int i=0;i<count;i++){JSONObject seg=segments.getJSONObject(i);files[i]=UsbArtifacts.owned(c,seg.getString("artifact"),owner);offsets[i]=seg.getLong("offset");}r=jobs.startSession(owner,a.getString("session"),n,j->EspFlasher.run(serial(hub.owned(owner,a.getString("session")),true),j,java.util.Arrays.asList(files),offsets,a.optInt("expected_chip",-1),a.optBoolean("auto_reset",false)));
        }else if(n.startsWith("adb_")){
            AdbClient adb=adb(hub.owned(owner,a.getString("session")));
            if(n.equals("adb_status"))r=a.optBoolean("connect")?jobs.startSession(owner,a.getString("session"),n,j->adb(hub.owned(owner,a.getString("session"))).connect()):adb.status();
            else if(n.equals("adb_exec"))r=jobs.startSession(owner,a.getString("session"),n,j->adb(hub.owned(owner,a.getString("session"))).exec(a.getString("command"),a.optInt("timeout_ms",30000)));
            else if(n.equals("adb_logcat")){String command=logcatCommand(a.optString("filter","*:I"));r=jobs.startSession(owner,a.getString("session"),n,j->adb(hub.owned(owner,a.getString("session"))).exec(command,30000));}
            else if(n.equals("adb_pull"))r=jobs.startSession(owner,a.getString("session"),n,j->adb(hub.owned(owner,a.getString("session"))).pull(a.getString("path"),owner,j));
            else {File file=UsbArtifacts.owned(c,a.getString("artifact"),owner);if(n.equals("adb_install")){if(!file.getName().endsWith(".apk"))throw new IllegalArgumentException("APK artifact required");r=jobs.startSession(owner,a.getString("session"),n,j->adb(hub.owned(owner,a.getString("session"))).install(file,j));}else if(n.equals("adb_push"))r=jobs.startSession(owner,a.getString("session"),n,j->adb(hub.owned(owner,a.getString("session"))).push(file,a.getString("path"),j));else throw new IllegalArgumentException("Unknown ADB tool");}
        }else if(n.equals("usb_storage_roots"))r=st.roots(owner);
        else if(n.equals("usb_storage_list"))r=st.list(owner,a.getString("document"),a.optInt("offset",0),a.optInt("limit",100));
        else if(n.equals("usb_storage_read")){String e=a.optString("encoding","utf8");try(InputStream in=st.input(owner,a.getString("document"))){byte[] b=UsbArtifacts.read(in,65536);r=new JSONObject().put("data",encode(b,e)).put("encoding",e).put("bytes",b.length);}}
        else if(n.equals("usb_storage_create"))r=new JSONObject().put("document",st.create(owner,a.getString("parent"),a.getString("name"),a.optString("mime","application/octet-stream")));
        else if(n.equals("usb_storage_write")){byte[] b=decode(a.getString("data"),a.optString("encoding","utf8"));try(OutputStream out=st.output(owner,a.getString("document"))){out.write(b);out.flush();}r=st.verify(owner,a.getString("document"),UsbArtifacts.hex(java.security.MessageDigest.getInstance("SHA-256").digest(b)),b.length,null);}
        else if(n.equals("usb_storage_delete")){st.delete(owner,a.getString("document"));r=new JSONObject().put("deleted",true);}
        else if(n.equals("usb_storage_copy"))r=jobs.start(owner,n,j->st.copy(owner,a.getString("source"),a.getString("target"),j));
        else if(n.equals("usb_storage_import"))r=jobs.start(owner,n,j->st.importFile(owner,a.getString("document"),j));
        else if(n.equals("usb_storage_export")){
            File f=a.has("artifact")?UsbArtifacts.owned(c,a.getString("artifact"),owner):DockerVm.exportedFile(c,a.getString("docker_export"));if(f==null||!f.isFile())throw new FileNotFoundException("Export not found");r=jobs.start(owner,n,j->{j.total=f.length();java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f);OutputStream out=st.output(owner,a.getString("target"))){byte[] b=new byte[65536];int count;while((count=in.read(b))!=-1){j.check();out.write(b,0,count);digest.update(b,0,count);j.done+=count;}out.flush();}return st.verify(owner,a.getString("target"),UsbArtifacts.hex(digest.digest()),j.done,j);});
        }else throw new IllegalArgumentException("Unknown USB tool");
        return result(r);
    }
}
