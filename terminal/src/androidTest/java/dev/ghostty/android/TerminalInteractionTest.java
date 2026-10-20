package dev.ghostty.android;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Runs the actual View, GestureDetector and InputConnection on the device OS. */
public final class TerminalInteractionTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private InteractionActivity activity;
    private TerminalView.Renderer renderer;
    private void ui(Runnable task) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { task.run(); } catch (Throwable t) { error.set(t); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private void screenshot(String name) {
        android.graphics.Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        java.io.File directory = new java.io.File(instrumentation.getContext().getExternalFilesDir(null), "evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        finally { bitmap.recycle(); }
    }
    private void settle() { SystemClock.sleep(100); instrumentation.waitForIdleSync(); }
    @Before public void open() {
        Intent intent = new Intent(instrumentation.getContext(), InteractionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (InteractionActivity) instrumentation.startActivitySync(intent);
        settle();
        ui(() -> renderer = (TerminalView.Renderer) activity.terminal.getChildAt(0));
    }
    @After public void close() { if (activity != null) ui(activity::finish); }
    private void write(String text) {
        ui(() -> activity.session.write(text.getBytes(StandardCharsets.UTF_8)));
        settle();
    }
    private void motion(long down, int action, float x, float y) {
        ui(() -> {
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
            activity.terminal.dispatchTouchEvent(event);
            event.recycle();
        });
    }
    private void longPress(float x, float y) {
        long down = SystemClock.uptimeMillis();
        motion(down, MotionEvent.ACTION_DOWN, x, y);
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150);
        motion(down, MotionEvent.ACTION_UP, x, y);
        settle();
    }
    private void swipe(float x, float from, float to) {
        long down = SystemClock.uptimeMillis();
        motion(down, MotionEvent.ACTION_DOWN, x, from);
        for (int i = 1; i <= 8; i++) {
            SystemClock.sleep(16);
            motion(down, MotionEvent.ACTION_MOVE, x, from + (to - from) * i / 8);
        }
        motion(down, MotionEvent.ACTION_UP, x, to);
        settle();
    }
    @Test public void chineseCompositionCommitCancelAndDelete() {
        ui(() -> {
            activity.input.requestFocus();
            InputConnection connection = activity.input.onCreateInputConnection(new EditorInfo());
            assertNotNull(connection);
            connection.beginBatchEdit();
            connection.setComposingText("zhong", 1);
            connection.setComposingText("zhongwen", 1);
            assertTrue(activity.sent.isEmpty());
            connection.commitText("中文", 1);
            connection.endBatchEdit();
            assertEquals(1, activity.sent.size());
            assertEquals("中文", new String(activity.sent.get(0), StandardCharsets.UTF_8));
            assertEquals("", activity.input.getText().toString());
            connection.setComposingText("ceshi", 1);
            connection.setComposingText("", 1);
            connection.finishComposingText();
            assertEquals(1, activity.sent.size());
            connection.commitText("👩🏽‍🚀", 1);
            assertEquals("👩🏽‍🚀", new String(activity.sent.get(1), StandardCharsets.UTF_8));
            connection.deleteSurroundingTextInCodePoints(1, 0);
            assertArrayEquals(new byte[]{127}, activity.sent.get(2));
        });
    }
    @Test public void complexEmojiLongPressSelectsWholeCluster() {
        String[] clusters = {"👨‍👩‍👧‍👦", "👍🏽", "🇨🇳", "1️⃣", "👩‍❤️‍👩", "🧑🏽‍🚀", "👩🏻‍🤝‍👨🏿", "🐦‍🔥", "🙂‍↔️", "🫱🏻‍🫲🏿", "é"};
        for (String cluster : clusters) {
            write("\033c" + cluster + "X");
            // Select the last occupied legacy column, including wide-cell padding.
            int[] column = new int[1];
            ui(() -> {
                for (int x = 0; x < renderer.cells[0].length; x++)
                    if (renderer.cells[0][x].text.equals("X")) { column[0] = x - 1; return; }
                fail("Missing sentinel");
            });
            longPress((column[0] + .5f) * renderer.cellW, renderer.cellH * .5f);
            ui(() -> assertEquals("Selection split " + cluster, cluster, activity.terminal.getSelectedText()));
            if (cluster.equals("👩‍❤️‍👩")) screenshot("couple-selection");
        }
    }
    @Test public void scrollbackAndHandleSelection() {
        StringBuilder lines = new StringBuilder("\033c");
        for (int i = 0; i < 180; i++) lines.append(String.format(java.util.Locale.ROOT, "L%03d 中文 👨‍👩‍👧‍👦\r\n", i));
        write(lines.toString());
        String[] before = new String[1];
        ui(() -> before[0] = renderer.cells[0][1].text + renderer.cells[0][2].text + renderer.cells[0][3].text);
        swipe(renderer.cellW * 15, renderer.cellH * 3, renderer.cellH * 12);
        ui(() -> {
            String now = renderer.cells[0][1].text + renderer.cells[0][2].text + renderer.cells[0][3].text;
            assertTrue("Swipe down should reveal earlier scrollback", Integer.parseInt(now) < Integer.parseInt(before[0]));
        });
        longPress(renderer.cellW * .5f, renderer.cellH * 2.5f);
        ui(() -> assertEquals("L", activity.terminal.getSelectedText()));
        float density = activity.getResources().getDisplayMetrics().density;
        long down = SystemClock.uptimeMillis();
        motion(down, MotionEvent.ACTION_DOWN, renderer.cellW, renderer.cellH * 3 + 7 * density);
        motion(down, MotionEvent.ACTION_MOVE, renderer.cellW * 7.5f, renderer.cellH * 2.5f);
        motion(down, MotionEvent.ACTION_UP, renderer.cellW * 7.5f, renderer.cellH * 2.5f);
        settle();
        ui(() -> assertTrue(activity.terminal.getSelectedText(), activity.terminal.getSelectedText().matches("L\\d{3} 中文")));
        screenshot("scrollback-selection");
        ui(activity.terminal::clearSelection);
        ui(() -> assertEquals("", activity.terminal.getSelectedText()));
    }
    @Test public void edgeDragScrollsAndStopsAfterRelease() {
        StringBuilder lines = new StringBuilder("\033c");
        for (int i = 0; i < 180; i++) lines.append(String.format(java.util.Locale.ROOT, "L%03d 中文\r\n", i));
        write(lines.toString());
        swipe(renderer.cellW * 15, renderer.cellH * 3, renderer.cellH * 12);
        longPress(renderer.cellW * .5f, renderer.cellH * 2.5f);
        float density = activity.getResources().getDisplayMetrics().density;
        long down = SystemClock.uptimeMillis();
        motion(down, MotionEvent.ACTION_DOWN, 0, renderer.cellH * 3 + 7 * density);
        motion(down, MotionEvent.ACTION_MOVE, renderer.cellW * .5f, 0);
        SystemClock.sleep(400);
        motion(down, MotionEvent.ACTION_UP, renderer.cellW * .5f, 0);
        settle();
        String[] selected = new String[1];
        ui(() -> {
            selected[0] = activity.terminal.getSelectedText();
            assertTrue("Expected a multiline selection", selected[0].contains("\n"));
            assertTrue(selected[0], selected[0].startsWith("L"));
            assertTrue(selected[0], selected[0].contains("中文"));
        });
        SystemClock.sleep(300);
        ui(() -> assertEquals("Autoscroll continued after pointer release", selected[0], activity.terminal.getSelectedText()));
    }

}
