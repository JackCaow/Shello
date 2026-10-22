package dev.ghostty.android;

final class NativeBridge {
    static { System.loadLibrary("ghostty_android"); }
    /** UTF-16 end offsets using the pinned VT engine's grapheme rules. */
    static native int[] graphemeEnds(String text);
    static native long create(int cols, int rows);
    static native void free(long handle);
    static native void write(long handle, byte[] bytes);
    static native void resize(long handle, int cols, int rows);
    static native void scroll(long handle, int delta);
    static native byte[] render(long handle);
    static native String frame(long handle);
    static native byte[] replies(long handle);
    static native byte[] key(long handle, int key);
    static native byte[] modifiedKey(long handle, int key, int modifiers, byte[] text, int codePoint);
    static native byte[] paste(long handle, byte[] text);
    static native byte[] wheel(long handle, boolean down, float x, float y, int cellWidth, int cellHeight);
    static native byte[] mouse(long handle, int action, boolean right, float x, float y, int cellWidth, int cellHeight);
    static native byte[] selection(long handle, int start, int end);
    static native byte[] snapshot(long handle);
    static native long restore(byte[] bytes);
}
