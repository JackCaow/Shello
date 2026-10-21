package dev.ghostty.android;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** A thread-confined VT engine. Use the UI thread when attached to a TerminalView. */
public final class TerminalSession implements AutoCloseable {
    public enum Key { UP, DOWN, LEFT, RIGHT, PAGE_UP, PAGE_DOWN, HOME, END, ENTER, TAB, ESCAPE, BACKSPACE }
    private final Thread owner = Thread.currentThread();
    private long handle;
    interface Observer { void changed(boolean clearSelection); }
    private Observer observer;
    private int columns, rows;

    public TerminalSession(int columns, int rows) {
        validateSize(columns, rows);
        handle = NativeBridge.create(columns, rows);
        if (handle == 0) throw new IllegalStateException("Failed to create native terminal");
        this.columns = columns;
        this.rows = rows;
    }

    private TerminalSession(Checkpoint checkpoint) {
        handle = NativeBridge.restore(checkpoint.bytes);
        if (handle == 0) throw new IllegalStateException("Failed to restore native terminal");
        columns = checkpoint.columns;
        rows = checkpoint.rows;
    }

    /** Feed ordered PTY output. Return VT replies that the transport must send back to the PTY. */
    public byte[] write(byte[] output) {
        checkOpen();
        Objects.requireNonNull(output, "output");
        NativeBridge.write(handle, output);
        changed(true);
        return NativeBridge.replies(handle);
    }

    public void resize(int columns, int rows) {
        checkOpen();
        validateSize(columns, rows);
        if (this.columns == columns && this.rows == rows) return;
        NativeBridge.resize(handle, columns, rows);
        this.columns = columns;
        this.rows = rows;
        changed(true);
    }

    public int getColumns() { checkOpen(); return columns; }
    public int getRows() { checkOpen(); return rows; }
    public void scroll(int delta) { checkOpen(); NativeBridge.scroll(handle, delta); changed(false); }
    public byte[] encodeKey(Key key) { checkOpen(); return NativeBridge.key(handle, Objects.requireNonNull(key).ordinal()); }

    /** Encode a bulk text insertion using current bracketed-paste mode. Does not submit Enter. */
    public byte[] encodePaste(String text) {
        checkOpen();Objects.requireNonNull(text,"text");
        return text.isEmpty()?new byte[0]:NativeBridge.paste(handle,text.getBytes(StandardCharsets.UTF_8));
    }

    // View geometry is normalized to integer cell dimensions for the native encoder.
    byte[] encodeWheel(boolean down, float x, float y, int cellWidth, int cellHeight) {
        checkOpen();return NativeBridge.wheel(handle, down, x, y, cellWidth, cellHeight);
    }

    /** Inclusive, zero-based screen coordinates (including scrollback), in row-major order. */
    public String select(int start, int end) {
        checkOpen();
        if (start < 0 || end < start) throw new IllegalArgumentException("Invalid selection range");
        return new String(NativeBridge.selection(handle, start, end), StandardCharsets.UTF_8);
    }

    /** Opaque in-memory checkpoint; not a portable persistence or network format. */
    public static final class Checkpoint {
        private final byte[] bytes;
        private final int columns, rows;
        private Checkpoint(byte[] bytes, int columns, int rows) { this.bytes = bytes; this.columns = columns; this.rows = rows; }
        public int sizeBytes() { return bytes.length; }
    }

    public Checkpoint checkpoint() { checkOpen(); return new Checkpoint(NativeBridge.snapshot(handle), columns, rows); }
    public static TerminalSession restore(Checkpoint checkpoint) { return new TerminalSession(Objects.requireNonNull(checkpoint)); }

    /** Detach a view before closing. Calling close more than once on the owner thread is safe. */
    @Override public void close() {
        checkThread();
        if (observer != null) throw new IllegalStateException("Detach the TerminalView before closing");
        if (handle != 0) { NativeBridge.free(handle); handle = 0; }
    }

    byte[] render() { checkOpen(); return NativeBridge.render(handle); }
    void attach(Observer observer) {
        checkOpen();
        if (this.observer != null) throw new IllegalStateException("A session supports one view at a time");
        this.observer = observer;
    }
    void detach() { checkOpen(); observer = null; }
    private void changed(boolean clearSelection) { if (observer != null) observer.changed(clearSelection); }
    private void checkThread() { if (Thread.currentThread() != owner) throw new IllegalStateException("Use the terminal's owner thread"); }
    private void checkOpen() { checkThread(); if (handle == 0) throw new IllegalStateException("Terminal is closed"); }
    private static void validateSize(int columns, int rows) {
        if (columns < 1 || rows < 1 || columns > 1000 || rows > 1000 || (long) columns * rows > 65536)
            throw new IllegalArgumentException("Grid must be 1..1000 per axis, at most 65536 cells");
    }
}
