package dev.mateuszsury.androidagent;

import android.content.Context;
import org.json.*;
import java.io.File;

/** Real USB redirection into QEMU; only a selected bus node reaches the task container. */
final class UsbDocker {
    // Serialize attachment with task requests; native Stop never waits on this gate.
    static final Object taskAccess = new Object();
    private static final java.util.Map<Integer,String> reserved = new java.util.HashMap<>();

    static JSONObject attach(Context c,String owner,String session,String task)throws Exception {
        synchronized(taskAccess) {
            McpHost.get(c).requireCaller(owner);
            if(task==null||!task.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid task id");
            UsbSessions hub=UsbSessions.get(c); DockerVm vm=DockerVm.get(c);
            if(!"ready".equals(vm.state))throw new IllegalStateException("Start Docker before assigning a USB device");
            final UsbSessions.Session s; final UsbSessions.Device device; final int slot; final String id;
            final File socket;
            synchronized(hub) {
                hub.authorizeTask(owner,task);
                s=hub.owned(owner,session);
                if(!s.profile.equals("docker"))throw new IllegalStateException("Open the device with the Docker USB profile first; native serial/ADB and Docker cannot own it together");
                if(s.closed||!s.task.isEmpty())throw new IllegalStateException("USB disconnected or already assigned");
                int available=1;while(available<=8&&reserved.containsKey(available))available++;
                if(available>8)throw new IllegalStateException("Maximum 8 redirected USB devices");
                slot=available; device=hub.device(s.device); id="usb"+s.id.replace("-","");
                File dir=new File(c.getFilesDir(),"usb-redirect");dir.mkdirs();socket=new File(dir,id+".sock");
                s.slot=slot;s.task=task;s.qmpId=id;reserved.put(slot,id);
            }
            try {
                // The session lock protects the native descriptor from close. Never hold
                // the hub over QMP/HTTP: Android detach and the user's Stop need it too.
                synchronized(s) {
                    if(s.closed)throw new IllegalStateException("USB disconnected during attachment");
                    s.redirection=UsbNative.start(s.connection.getFileDescriptor(),socket.getAbsolutePath());
                }
                vm.qmp("chardev-add",new JSONObject().put("id",id).put("backend",new JSONObject().put("type","socket").put("data",new JSONObject().put("addr",new JSONObject().put("type","unix").put("data",new JSONObject().put("path",socket.getAbsolutePath()))).put("server",false))));
                vm.qmp("device_add",new JSONObject().put("driver","usb-redir").put("id",id).put("chardev",id).put("bus","aausb.0").put("port",String.valueOf(slot)));
                JSONObject result=vm.request(new JSONObject().put("op","usb_attach").put("task",task).put("slot",slot).put("vid",device.usb.getVendorId()).put("pid",device.usb.getProductId()).put("session",s.id));
                synchronized(hub) {
                    requireCurrent(hub,owner,s,task);
                    synchronized(s) {
                        if(s.redirection==0||!"active".equals(UsbNative.state(s.redirection)))throw new IllegalStateException("USB redirection disconnected during attachment");
                    }
                }
                return result.put("session",session);
            } catch(Exception|LinkageError e) {
                synchronized(hub) { if(hub.sessions.get(session)==s)hub.close(owner,session); }
                // Close can precede the guest's response. Repeat generation-scoped
                // rollback after that response so a late grant cannot survive Stop.
                revokeGrant(c,task,id,s.id,slot);
                throw e;
            }
        }
    }
    static void requireCurrent(UsbSessions hub,String owner,UsbSessions.Session s,String task) {
        if(Thread.currentThread().isInterrupted()||hub.sessions.get(s.id)!=s||s.closed||!owner.equals(s.owner)||!task.equals(s.task))
            throw new IllegalStateException("USB assignment was revoked during attachment");
    }
    static void revoke(Context c,UsbSessions.Session s) {
        if(s.task.isEmpty())return;
        final String task=s.task,id=s.qmpId;final int slot=s.slot;s.task="";s.slot=0;
        revokeGrant(c,task,id,s.id,slot);
    }
    static void revokeGrant(Context c,String task,String id,String session,int slot) {
        new Thread(()-> {
            DockerVm vm=DockerVm.get(c);
            try {if(!id.isEmpty())vm.qmp("device_del",new JSONObject().put("id",id));}catch(Exception ignored){}
            try {if(!id.isEmpty())vm.qmp("chardev-remove",new JSONObject().put("id",id));}catch(Exception ignored){}
            try {vm.request(new JSONObject().put("op","usb_detach").put("task",task).put("session",session));}catch(Exception ignored){}
            finally {synchronized(UsbSessions.get(c)){reserved.remove(slot,id);}}
        },"usb-docker-revoke").start();
    }
    static void closeTask(Context c,String task){if(task==null||task.isEmpty())return;UsbSessions hub=UsbSessions.get(c);synchronized(hub){for(UsbSessions.Session s:new java.util.ArrayList<>(hub.sessions.values()))if(s.task.equals(task))hub.close(s.owner,s.id);}}
    static void closeVm(Context c){UsbSessions hub=UsbSessions.get(c);synchronized(hub){for(UsbSessions.Session s:new java.util.ArrayList<>(hub.sessions.values()))if(!s.task.isEmpty())hub.close(s.owner,s.id);}}
}
