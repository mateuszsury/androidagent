package dev.mateuszsury.androidagent;

import org.json.*;
import java.io.File;
import java.util.*;

/** Direct port of Espressif's flasher. No Python, Termux or VM is needed. */
final class EspFlasher {
    static { System.loadLibrary("aausb"); }
    private static native String operate(Port port,String[] files,long[] offsets,int expectedChip);
    static final class Port {
        final SerialTransport serial;final UsbJobs.Job job;final boolean autoReset;
        Port(SerialTransport s,UsbJobs.Job j,boolean reset){serial=s;job=j;autoReset=reset;}
        public int read(byte[] b,int timeout)throws Exception{job.check();return serial.protocolRead(b,Math.min(3000,timeout));}
        public int write(byte[] b,int timeout)throws Exception{job.check();return serial.write(b,Math.min(3000,Math.max(1,timeout)));}
        public void baud(int baud)throws Exception{job.check();serial.configure(baud,8,1,0);}
        public void reset(boolean boot)throws Exception{job.check();if(!autoReset)return;if(boot){serial.control(false,true,false);Thread.sleep(100);serial.control(true,false,false);Thread.sleep(100);serial.control(false,false,false);}else{serial.control(false,true,false);Thread.sleep(100);serial.control(false,false,false);}}
        public void progress(long bytes)throws Exception{job.check();job.done=bytes;}
    }
    static JSONObject run(SerialTransport serial,UsbJobs.Job job,List<File> files,long[] offsets,int expectedChip,boolean autoReset)throws Exception{
        if(files.size()!=offsets.length||files.size()>16)throw new IllegalArgumentException("Invalid flash segment map");
        List<String> paths=new ArrayList<>();long end=0;for(int i=0;i<files.size();i++){File f=files.get(i);if(!f.isFile()||f.length()==0||f.length()>32L*1024*1024||offsets[i]<end||offsets[i]%4096!=0||offsets[i]>32L*1024*1024-f.length())throw new IllegalArgumentException("Use ordered, non-overlapping flash segments aligned to 4 KiB, up to 32 MiB");end=offsets[i]+f.length();job.total+=f.length();paths.add(f.getAbsolutePath());}
        int original=serial.baud;serial.beginExclusive();try{serial.configure(115200,8,1,0);return new JSONObject(operate(new Port(serial,job,autoReset),paths.toArray(new String[0]),offsets,expectedChip));}finally{serial.endExclusive();if(!serial.closed)try{serial.configure(original,8,1,0);}catch(Exception ignored){}}
    }
}
