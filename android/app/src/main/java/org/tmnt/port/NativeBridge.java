package org.tmnt.port;

/** Methods are implemented in the port runtime, not in the downloadable game module. */
public final class NativeBridge {
    private NativeBridge() {}
    public static volatile boolean loaded;
    public static native void requestQuit();
    public static native void touchButtons(int mask);
    public static native void touchStick(float x, float y);
    public static native void clearInput();
    public static native void setPaused(boolean paused);
    public static native void setPerf(boolean enabled);
    public static native float fps();
    public static native float frameMs();
}
