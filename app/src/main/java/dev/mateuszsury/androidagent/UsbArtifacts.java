package dev.mateuszsury.androidagent;

import android.content.Context;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Preserved binary results shared read-only through Android's URI grants. */
final class UsbArtifacts {
    static File file(Context c,String id){if(id==null||!id.matches("[a-f0-9]{32}\\.[A-Za-z0-9]{1,12}"))return null;File f=new File(c.getFilesDir(),"usb-artifacts/"+id);return f.isFile()?f:null;}
    static JSONObject save(Context c,InputStream in,String name,String owner,UsbJobs.Job job,long limit)throws Exception{
        File dir=new File(c.getFilesDir(),"usb-artifacts");dir.mkdirs();String ext=name.contains(".")?name.substring(name.lastIndexOf('.')):".bin";if(!ext.matches("\\.[A-Za-z0-9]{1,12}"))ext=".bin";
        String id=UUID.randomUUID().toString().replace("-","")+ext;File f=new File(dir,id);MessageDigest sha=MessageDigest.getInstance("SHA-256");long size=0;
        try{try(OutputStream out=new FileOutputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){if(job!=null)job.check();size+=n;if(size>limit)throw new IOException("Artifact exceeds size limit");out.write(b,0,n);sha.update(b,0,n);if(job!=null)job.done=size;}}
            JSONObject result=new JSONObject().put("id",id).put("name",name).put("owner",owner).put("bytes",size).put("sha256",hex(sha.digest())).put("uri",uri(id).toString());try(OutputStream out=new FileOutputStream(new File(f.getPath()+".json"))){out.write(result.toString().getBytes(StandardCharsets.UTF_8));}return result;
        }catch(Exception e){f.delete();new File(f.getPath()+".json").delete();throw e;}
    }
    static Uri uri(String id){return Uri.parse("content://"+FilesProvider.AUTHORITY+"/usb/"+id);}
    static JSONObject metadata(Context c,String id)throws Exception{File f=file(c,id);if(f==null)throw new FileNotFoundException("Artifact not found");try(InputStream in=new FileInputStream(new File(f.getPath()+".json"))){return new JSONObject(new String(read(in,16384),StandardCharsets.UTF_8));}}
    static File owned(Context c,String id,String owner)throws Exception{JSONObject m=metadata(c,id);if(!owner.equals(m.optString("owner")))throw new SecurityException("Artifact belongs to another owner");return file(c,id);}
    static byte[] read(InputStream in,int max)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("Result exceeds size limit");out.write(b,0,n);}return out.toByteArray();}
    static String hex(byte[] data){StringBuilder b=new StringBuilder();for(byte x:data)b.append(String.format(java.util.Locale.US,"%02x",x&255));return b.toString();}
}
