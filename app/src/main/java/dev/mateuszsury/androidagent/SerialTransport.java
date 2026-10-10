package dev.mateuszsury.androidagent;

import android.hardware.usb.*;
import android.os.SystemClock;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import java.io.*;
import java.util.*;

/** One physical reader; protocol operations receive a private queue while logs remain observable. */
final class SerialTransport implements Closeable {
    final UsbSerialPort port;final UsbBuffer received=new UsbBuffer(65536);
    volatile boolean closed,exclusive,breakState;volatile String error="";volatile long tx;volatile int baud=115200;
    private final ArrayDeque<Byte> queue=new ArrayDeque<>();
    SerialTransport(UsbSerialPort p,UsbDeviceConnection c)throws IOException{
        port=p;try{port.open(c);port.setParameters(baud,8,UsbSerialPort.STOPBITS_1,UsbSerialPort.PARITY_NONE);}catch(IOException|RuntimeException e){c.close();throw e;}
        Thread reader=new Thread(()->{byte[] b=new byte[4096];while(!closed){try{int n=port.read(b,200);if(n>0){received.append(b,n);synchronized(queue){if(exclusive){for(int i=0;i<n;i++){if(queue.size()>=65536){error="Serial protocol queue overflow";closed=true;break;}queue.add(b[i]);}}queue.notifyAll();}}}catch(IOException e){if(!closed){error=String.valueOf(e.getMessage());closed=true;}synchronized(queue){queue.notifyAll();}}}try{port.close();}catch(Exception ignored){}},"usb-serial-rx");reader.setDaemon(true);reader.start();
    }
    synchronized void configure(int rate,int bits,int stop,int parity)throws IOException{if(rate<300||rate>4000000||bits<5||bits>8||stop<1||stop>3||parity<0||parity>4)throw new IllegalArgumentException("Invalid serial parameters");port.setParameters(rate,bits,stop,parity);baud=rate;}
    synchronized int write(byte[] b,int timeout)throws IOException{if(closed)throw new IOException("Device disconnected: "+error);try{port.write(b,timeout);}catch(com.hoho.android.usbserial.driver.SerialTimeoutException e){tx+=e.bytesTransferred;throw new IOException("timeout: "+e.bytesTransferred+" bytes sent before the deadline",e);}tx+=b.length;return b.length;}
    synchronized void control(boolean dtr,boolean rts,boolean brk)throws IOException{if(closed)throw new IOException("Device disconnected");port.setDTR(dtr);port.setRTS(rts);if(brk||breakState){port.setBreak(brk);breakState=brk;}}
    synchronized void beginExclusive(){if(exclusive||closed)throw new IllegalStateException("Port busy or disconnected");synchronized(queue){queue.clear();exclusive=true;}}
    synchronized void endExclusive(){synchronized(queue){exclusive=false;queue.clear();}}
    int protocolRead(byte[] b,int timeout)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+timeout;int n=0;synchronized(queue){while(n<b.length){if(closed)throw new IOException("Device disconnected");if(Thread.currentThread().isInterrupted())throw new InterruptedException();while(n<b.length&&!queue.isEmpty())b[n++]=queue.remove();if(n==b.length)break;long remaining=deadline-SystemClock.elapsedRealtime();if(remaining<=0)break;queue.wait(Math.min(50,remaining));}}return n;
    }
    @Override public void close(){closed=true;synchronized(queue){queue.notifyAll();}try{port.close();}catch(Exception ignored){}}
}
