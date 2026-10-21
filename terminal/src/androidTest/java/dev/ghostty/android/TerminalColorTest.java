package dev.ghostty.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Check actual Canvas pixels as well as the VT-to-renderer color contract. */
public final class TerminalColorTest {
    interface Check { void run(TerminalSession session, TerminalView.Renderer renderer) throws Exception; }
    private void check(Check check) {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            try(TerminalSession session=new TerminalSession(12,4)) {
                TerminalView view=new TerminalView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                TerminalView.Renderer renderer=(TerminalView.Renderer)view.getChildAt(0);
                try { check.run(session,renderer); } catch(Throwable t) { failure.set(t); }
            }
        });
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private void write(TerminalSession s,TerminalView.Renderer r,String text)throws Exception {
        s.write(text.getBytes(StandardCharsets.UTF_8));r.setRender(s.render());
    }
    private void background(TerminalView.Renderer r,int row,int first,int end,int expected) {
        Bitmap bitmap=Bitmap.createBitmap((int)Math.ceil(r.cellW*12),(int)Math.ceil(r.cellH*4),Bitmap.Config.ARGB_8888);
        try {
            r.onDraw(new Canvas(bitmap));
            for(int x=first;x<end;x++) {
                assertEquals("cell background at "+x+","+row,expected,r.cells[row][x].bg);
                assertEquals("Canvas background at "+x+","+row,0xff000000|expected,
                        bitmap.getPixel((int)((x+.5f)*r.cellW),(int)(row*r.cellH+1)));
            }
        } finally { bitmap.recycle(); }
    }
    @Test public void erasedRgbBackgroundMatchesPrintedSpaces() { check((s,r)->{
        write(s,r,"\033[?25l\033[48;2;18;52;86m   \033[K");
        background(r,0,0,12,0x123456);
        write(s,r,"\033[2J");
        for(int y=0;y<4;y++)background(r,y,0,12,0x123456);
    }); }
    @Test public void erasedPaletteBackgroundTracksPaletteChangesAndReset() { check((s,r)->{
        write(s,r,"\033[?25l\033]11;#112233\007\033]4;17;#123456\007\033[48;5;17m \033[K");
        background(r,0,0,12,0x123456);
        write(s,r,"\033]4;17;#abcdef\007");background(r,0,0,12,0xabcdef);
        write(s,r,"\033[49m\033[2K");background(r,0,0,12,0x112233);
    }); }
    @Test public void ansiBrightIndexedAndTruecolorRemainExact() { check((s,r)->{
        write(s,r,"\033[?25l\033]4;1;#112233;9;#445566\007\033[31mA\033[91mB\033[38;5;196mC\033[38;2;18;52;86mD");
        int[] expected={0x112233,0x445566,0xff0000,0x123456};
        for(int x=0;x<expected.length;x++)assertEquals(expected[x],r.cells[0][x].fg);
        write(s,r,"\033[2;1H\033[41m \033[101m \033[48;5;196m \033[48;2;18;52;86m ");
        for(int x=0;x<expected.length;x++)background(r,1,x,x+1,expected[x]);
    }); }
    @Test public void inverseAndScreenReverseUseEffectiveDefaultColors() { check((s,r)->{
        write(s,r,"\033[?25l\033]10;#aabbcc\007\033]11;#112233\007A\033[7mB\033[27m\033[38;2;18;52;86;48;2;101;67;33mC\033[7mD");
        assertEquals(0xaabbcc,r.cells[0][0].fg);background(r,0,0,1,0x112233);
        assertEquals(0x112233,r.cells[0][1].fg);background(r,0,1,2,0xaabbcc);
        assertEquals(0x123456,r.cells[0][2].fg);background(r,0,2,3,0x654321);
        assertEquals(0x654321,r.cells[0][3].fg);background(r,0,3,4,0x123456);
        write(s,r,"\033[?5h");
        assertEquals(0x112233,r.cells[0][0].fg);background(r,0,0,1,0xaabbcc);
        assertEquals(0xaabbcc,r.cells[0][1].fg);background(r,0,1,2,0x112233);
    }); }
    @Test public void foregroundAndBackgroundOverridesResetIndependently() { check((s,r)->{
        write(s,r,"\033[?25l\033]10;#123456\007A");
        assertEquals(0x123456,r.cells[0][0].fg);background(r,0,0,1,0);
        write(s,r,"\033]11;#654321\007");background(r,0,0,1,0x654321);
        write(s,r,"\033]110\007");assertEquals(0xffffff,r.cells[0][0].fg);background(r,0,0,1,0x654321);
        write(s,r,"\033]111\007");background(r,0,0,1,0);
    }); }
    @Test public void backgroundSurvivesCheckpointAndScrollback() { check((s,r)->{
        write(s,r,"\033[?25l\033[48;2;18;52;86m\033[2K\033[0m\r\n2\r\n3\r\n4\r\n5");
        s.scroll(-100);r.setRender(s.render());background(r,0,0,12,0x123456);
        try(TerminalSession restored=TerminalSession.restore(s.checkpoint())) {
            // Checkpoints restore the live viewport; scroll back to the saved history.
            restored.scroll(-100);r.setRender(restored.render());background(r,0,0,12,0x123456);
        }
    }); }
}
