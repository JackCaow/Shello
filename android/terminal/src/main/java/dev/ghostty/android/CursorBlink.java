package dev.ghostty.android;

/** Per-view timing: input/movement starts with a readable solid cursor, then a steady blink. */
final class CursorBlink {
    static final long HOLD_MS=750, HALF_PERIOD_MS=500;
    private long visibleUntil;
    void reset(long now){visibleUntil=now+HOLD_MS;}
    boolean visible(long now){return now<visibleUntil||((now-visibleUntil)/HALF_PERIOD_MS)%2==1;}
    long nextChange(long now){return now<visibleUntil?visibleUntil-now:HALF_PERIOD_MS-(now-visibleUntil)%HALF_PERIOD_MS;}
}
