package dev.mateuszsury.androidagent;

import android.content.Context;
import org.json.*;
import java.io.File;

/** Real USB redirection into QEMU; only a selected bus node reaches the task container. */
final class UsbDocker {
    // Serialize attachment with task requests; native Stop never waits on this gate.
    static final Object taskAccess=new Object();
    private static final java.util.Set<Integer> reserved=new java.util.HashSet<>();
    static JSONObject attach(Context c,String owner,String session,String task)throws Exception{
        synchronized(taskAccess){
        UsbSessions hub=UsbSessions.get(c);DockerVm vm=DockerVm.get(c);if(!"ready".equals(vm.state))throw new IllegalStateException("Start Docker before assigning a USB device");
        synchronized(hub){UsbSessions.Session s=hub.owned(owner,session);if(!s.profile.equals("docker"))throw new IllegalStateException("Open the device with the Docker USB profile first; native serial/ADB and Docker cannot own it together");if(!s.task.isEmpty())throw new IllegalStateException("USB already assigned");
            int slot=1;for(;slot<=8;slot++){final int candidate=slot;if(!reserved.contains(candidate))break;}if(slot>8)throw new IllegalStateException("Maximum 8 redirected USB devices");
            UsbSessions.Device device=hub.device(s.device);String id="usb"+s.id.replace("-","");File dir=new File(c.getFilesDir(),"usb-redirect");dir.mkdirs();File socket=new File(dir,id+".sock");
            s.slot=slot;s.task=task;s.qmpId=id;reserved.add(slot);
            try{s.redirection=UsbNative.start(s.connection.getFileDescriptor(),socket.getAbsolutePath());vm.qmp("chardev-add",new JSONObject().put("id",id).put("backend",new JSONObject().put("type","socket").put("data",new JSONObject().put("addr",new JSONObject().put("type","unix").put("data",new JSONObject().put("path",socket.getAbsolutePath()))).put("server",false))));
                vm.qmp("device_add",new JSONObject().put("driver","usb-redir").put("id",id).put("chardev",id).put("bus","aausb.0").put("port",String.valueOf(slot)));
                JSONObject result=vm.request(new JSONObject().put("op","usb_attach").put("task",task).put("slot",slot).put("vid",device.usb.getVendorId()).put("pid",device.usb.getProductId()).put("session",s.id));
                if(!"active".equals(UsbNative.state(s.redirection)))throw new IllegalStateException("USB redirection disconnected during attachment");return result.put("session",session);
            }catch(Exception e){hub.close(owner,session);throw e;}
        }}
    }
    static void revoke(Context c,UsbSessions.Session s){
        if(s.task.isEmpty())return;final String task=s.task,id=s.qmpId,session=s.id;final int slot=s.slot;s.task="";s.slot=0;
        new Thread(()->{DockerVm vm=DockerVm.get(c);try{if(!id.isEmpty())vm.qmp("device_del",new JSONObject().put("id",id));}catch(Exception ignored){}try{if(!id.isEmpty())vm.qmp("chardev-remove",new JSONObject().put("id",id));}catch(Exception ignored){}try{vm.request(new JSONObject().put("op","usb_detach").put("task",task).put("session",session));}catch(Exception ignored){}finally{synchronized(UsbSessions.get(c)){reserved.remove(slot);}}},"usb-docker-revoke").start();
    }
    static void closeTask(Context c,String task){UsbSessions hub=UsbSessions.get(c);synchronized(hub){for(UsbSessions.Session s:new java.util.ArrayList<>(hub.sessions.values()))if(s.task.equals(task))hub.close(s.owner,s.id);}}
    static void closeVm(Context c){UsbSessions hub=UsbSessions.get(c);synchronized(hub){for(UsbSessions.Session s:new java.util.ArrayList<>(hub.sessions.values()))if(!s.task.isEmpty())hub.close(s.owner,s.id);}}
}
