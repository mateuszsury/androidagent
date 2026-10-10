package dev.mateuszsury.androidagent;

import java.util.Arrays;

/** Binary ring with absolute cursors: UI and agents observe the same received bytes. */
final class UsbBuffer {
    final byte[] bytes;private long end;
    UsbBuffer(int capacity){bytes=new byte[capacity];}
    synchronized void append(byte[] b,int n){for(int i=0;i<n;i++)bytes[(int)(end++%bytes.length)]=b[i];notifyAll();}
    static final class Slice{final byte[] bytes;final long cursor,lost;Slice(byte[] b,long c,long l){bytes=b;cursor=c;lost=l;}}
    synchronized Slice read(long cursor,int limit){
        if(limit<1||limit>65536||cursor<0||cursor>end)throw new IllegalArgumentException("Invalid receive cursor");
        long start=Math.max(cursor,Math.max(0,end-bytes.length));int size=(int)Math.min(end-start,limit);byte[] out=new byte[size];
        for(int i=0;i<size;i++)out[i]=bytes[(int)((start+i)%bytes.length)];return new Slice(out,start+size,start-cursor);
    }
    synchronized long end(){return end;}
}
