package dev.mateuszsury.androidagent;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.*;
import java.io.*;
import java.util.*;

/** Only selected document trees. Opaque handles never accept caller-provided URIs or paths. */
final class UsbStorage {
    static final int MAX_INLINE=65536;
    private static UsbStorage instance;
    static synchronized UsbStorage get(Context c){if(instance==null)instance=new UsbStorage(c.getApplicationContext());return instance;}
    private static final java.util.concurrent.ScheduledExecutorService TIMEOUTS=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private Cursor query(Uri uri,String[] columns)throws Exception{android.os.CancellationSignal signal=new android.os.CancellationSignal();java.util.concurrent.ScheduledFuture<?> timeout=TIMEOUTS.schedule(signal::cancel,5,java.util.concurrent.TimeUnit.SECONDS);try{return app.getContentResolver().query(uri,columns,null,null,null,signal);}finally{timeout.cancel(false);}}
    final Context app;private final Map<String,Root> roots=new LinkedHashMap<>();private final Map<String,Document> docs=new LinkedHashMap<>();
    static final class Root{final String id;final Uri tree;boolean write, grantedWrite;String owner="panel";Root(String i,Uri t,boolean w){id=i;tree=t;write=w;grantedWrite=w;}}
    static final class Document{final String root;final Uri uri;Document(String r,Uri u){root=r;uri=u;}}
    private UsbStorage(Context c){app=c;try{JSONArray saved=new JSONArray(c.getSharedPreferences("usb_storage",0).getString("roots","[]"));for(int i=0;i<saved.length();i++){JSONObject o=saved.getJSONObject(i);Root r=new Root(o.getString("id"),Uri.parse(o.getString("uri")),o.optBoolean("write"));roots.put(r.id,r);}}catch(Exception ignored){}}
    private void save(){JSONArray a=new JSONArray();try{for(Root r:roots.values())a.put(new JSONObject().put("id",r.id).put("uri",r.tree.toString()).put("write",r.write));app.getSharedPreferences("usb_storage",0).edit().putString("roots",a.toString()).apply();}catch(JSONException e){throw new IllegalStateException(e);}}
    synchronized String add(Uri uri,int flags)throws Exception{
        if(!DocumentsContract.isTreeUri(uri))throw new IllegalArgumentException("Select a directory");int permissions=flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if((permissions&Intent.FLAG_GRANT_READ_URI_PERMISSION)==0)throw new SecurityException("Read permission required");
        try{app.getContentResolver().takePersistableUriPermission(uri,permissions);}catch(SecurityException ignored){}
        for(Root r:roots.values())if(r.tree.equals(uri)){r.write=(permissions&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0;r.grantedWrite=r.write;r.owner="panel";save();return r.id;}Root r=new Root(UUID.randomUUID().toString(),uri,(permissions&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0);roots.put(r.id,r);save();return r.id;
    }
    synchronized void assign(String id,String owner,boolean write){Root r=root("panel",id,false);r.owner=owner;r.grantedWrite=r.write&&write;}
    synchronized void activate(String chat){for(Root r:roots.values())if(r.owner.equals("pending:"+chat))r.owner="chat:"+chat;}
    synchronized void revokeAll(){for(Root r:roots.values()){r.owner="panel";r.grantedWrite=r.write;}}
    synchronized void revokeOwner(String owner){for(Root r:roots.values())if(r.owner.equals(owner)){r.owner="panel";r.grantedWrite=r.write;}}
    synchronized void remove(String id){Root r=roots.remove(id);if(r==null)return;try{app.getContentResolver().releasePersistableUriPermission(r.tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignored){}docs.entrySet().removeIf(e->e.getValue().root.equals(id));save();}
    private synchronized Root root(String owner,String id,boolean write){Root r=roots.get(id);if(r==null||!r.owner.equals(owner)&&!"panel".equals(owner))throw new SecurityException("Directory is not assigned to this caller");if(write&&!("panel".equals(owner)?r.write:r.grantedWrite))throw new SecurityException("Directory is read-only");return r;}
    private String remember(String root,Uri uri){for(Map.Entry<String,Document> e:docs.entrySet())if(e.getValue().root.equals(root)&&e.getValue().uri.equals(uri))return e.getKey();if(docs.size()>=10000)throw new IllegalStateException("Too many document handles");String id=UUID.randomUUID().toString();docs.put(id,new Document(root,uri));return id;}
    synchronized JSONArray roots(String owner)throws Exception{JSONArray out=new JSONArray();for(Root r:roots.values())if(owner.equals("panel")||owner.equals(r.owner)){Uri u=DocumentsContract.buildDocumentUriUsingTree(r.tree,DocumentsContract.getTreeDocumentId(r.tree));JSONObject o;try{o=metadata(r.id,u).put("available",true);}catch(Exception e){o=new JSONObject().put("name","Nośnik niedostępny").put("available",false).put("error","media_unavailable");}o.put("root",r.id).put("document",remember(r.id,u)).put("write","panel".equals(owner)?r.write:r.grantedWrite).put("owner",r.owner);out.put(o);}return out;}
    private JSONObject metadata(String root,Uri uri)throws Exception{
        try(Cursor c=query(uri,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_FLAGS})){
            if(c==null||!c.moveToFirst())throw new FileNotFoundException("media_unavailable");return new JSONObject().put("name",c.getString(0)).put("mime",c.getString(1)).put("bytes",c.isNull(2)?JSONObject.NULL:c.getLong(2)).put("flags",c.getInt(3));}
    }
    synchronized JSONArray list(String owner,String id,int offset,int limit)throws Exception{
        Document d=document(owner,id,false);if(!DocumentsContract.Document.MIME_TYPE_DIR.equals(metadata(d.root,d.uri).getString("mime")))throw new IOException("Not a directory");
        Root r=roots.get(d.root);Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(r.tree,DocumentsContract.getDocumentId(d.uri));JSONArray out=new JSONArray();
        if(offset<0||limit<1||limit>200)throw new IllegalArgumentException("Invalid page");
        try(Cursor c=query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_FLAGS})){
            if(c==null)throw new FileNotFoundException("media_unavailable");int at=0;while(c.moveToNext()){if(at++<offset)continue;Uri u=DocumentsContract.buildDocumentUriUsingTree(r.tree,c.getString(0));out.put(new JSONObject().put("document",remember(d.root,u)).put("name",c.getString(1)).put("mime",c.getString(2)).put("bytes",c.isNull(3)?JSONObject.NULL:c.getLong(3)).put("flags",c.getInt(4)));if(out.length()==limit)break;}}
        return out;
    }
    private synchronized Document document(String owner,String id,boolean write){Document d=docs.get(id);if(d==null)throw new IllegalArgumentException("Unknown document; list the directory first");root(owner,d.root,write);return d;}
    InputStream input(String owner,String id)throws Exception{Document d=document(owner,id,false);if(DocumentsContract.Document.MIME_TYPE_DIR.equals(metadata(d.root,d.uri).getString("mime")))throw new IOException("Not a file");InputStream in=app.getContentResolver().openInputStream(d.uri);if(in==null)throw new FileNotFoundException("media_unavailable");return new FilterInputStream(in){public int read()throws IOException{root(owner,d.root,false);return in.read();}public int read(byte[] b,int off,int len)throws IOException{root(owner,d.root,false);return in.read(b,off,len);}};}
    OutputStream output(String owner,String id)throws Exception{Document d=document(owner,id,true);JSONObject m=metadata(d.root,d.uri);if((m.getInt("flags")&DocumentsContract.Document.FLAG_SUPPORTS_WRITE)==0)throw new IOException("Provider does not support writing");OutputStream out=app.getContentResolver().openOutputStream(d.uri,"wt");if(out==null)throw new FileNotFoundException("media_unavailable");return new FilterOutputStream(out){public void write(int value)throws IOException{root(owner,d.root,true);out.write(value);}public void write(byte[] b,int off,int len)throws IOException{root(owner,d.root,true);out.write(b,off,len);}};}
    synchronized String create(String owner,String parent,String name,String mime)throws Exception{Document d=document(owner,parent,true);if(name.isEmpty()||name.length()>160||name.contains("/")||name.contains("\\")||name.contains("\u0000")||name.equals(".")||name.equals(".."))throw new IllegalArgumentException("Invalid filename");Uri uri=DocumentsContract.createDocument(app.getContentResolver(),d.uri,mime,name);if(uri==null)throw new IOException("Provider could not create the file");return remember(d.root,uri);}
    void delete(String owner,String id)throws Exception{Document d=document(owner,id,true);if((metadata(d.root,d.uri).getInt("flags")&DocumentsContract.Document.FLAG_SUPPORTS_DELETE)==0)throw new IOException("Provider does not support deletion");if(!DocumentsContract.deleteDocument(app.getContentResolver(),d.uri))throw new IOException("Deletion failed");synchronized(this){docs.remove(id);}}
    JSONObject importFile(String owner,String document,UsbJobs.Job job)throws Exception{Document d=document(owner,document,false);String name=metadata(d.root,d.uri).getString("name");try(InputStream in=input(owner,document)){return UsbArtifacts.save(app,in,name,owner,job,512L*1024*1024);}}
    JSONObject verify(String owner,String document,String sha256,long size,UsbJobs.Job job)throws Exception{
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");long count=0;
        try(InputStream in=input(owner,document)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){if(job!=null)job.check();count+=n;if(count>size)throw new IOException("Target verification failed; write may be partial or changed");digest.update(b,0,n);}}
        String actual=UsbArtifacts.hex(digest.digest());if(count!=size||!actual.equals(sha256))throw new IOException("Target verification failed; write may be partial or changed");
        return new JSONObject().put("bytes",size).put("sha256",actual).put("verified",true);
    }
    JSONObject copy(String owner,String source,String target,UsbJobs.Job job)throws Exception{
        Document from=document(owner,source,false),to=document(owner,target,true);if(from.uri.equals(to.uri))throw new IOException("Source and destination are the same file");
        java.security.MessageDigest sha=java.security.MessageDigest.getInstance("SHA-256");
        try(InputStream in=input(owner,source);OutputStream out=output(owner,target)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){job.check();if(job.done+n>512L*1024*1024)throw new IOException("Copy exceeds 512 MiB; target may be partial");out.write(b,0,n);sha.update(b,0,n);job.done+=n;}out.flush();}
        return verify(owner,target,UsbArtifacts.hex(sha.digest()),job.done,job);
    }
}
