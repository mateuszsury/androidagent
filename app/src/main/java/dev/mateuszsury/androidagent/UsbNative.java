package dev.mateuszsury.androidagent;

/** Native Android libusb/usbredir host. FD comes exclusively from UsbManager.openDevice. */
final class UsbNative {
    static { System.loadLibrary("aausb"); }
    static native long start(int fd,String privateSocket);
    static native void stop(long handle);
    static native String state(long handle);
}
