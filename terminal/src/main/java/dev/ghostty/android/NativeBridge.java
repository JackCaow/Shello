package dev.ghostty.android;

final class NativeBridge {
    static { System.loadLibrary("ghostty_android"); }
    static native long create(int cols, int rows);
    static native void free(long handle);
    static native void write(long handle, byte[] bytes);
    static native void resize(long handle, int cols, int rows);
    static native void scroll(long handle, int delta);
    static native byte[] render(long handle);
    static native String frame(long handle);
    static native byte[] replies(long handle);
    static native byte[] key(long handle, int key);
    static native byte[] selection(long handle, int start, int end);
    static native byte[] snapshot(long handle);
    static native long restore(byte[] bytes);
}
