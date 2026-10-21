package dev.ghostty.android;

import android.graphics.*;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class TerminalCursorTest {
    @Test public void blinkRestartsWithVisibleHoldAndDoesNotUseGlobalClockPhase(){
        CursorBlink blink=new CursorBlink();blink.reset(1234);
        assertTrue(blink.visible(1234));assertTrue(blink.visible(1983));assertFalse(blink.visible(1984));
        assertFalse(blink.visible(2483));assertTrue(blink.visible(2484));
        assertEquals(750,blink.nextChange(1234));assertEquals(1,blink.nextChange(1983));assertEquals(500,blink.nextChange(1984));
        blink.reset(2100);assertTrue(blink.visible(2100));assertEquals(750,blink.nextChange(2100));
    }
    interface Check {void run(TerminalSession session,TerminalView view,TerminalView.Renderer renderer)throws Exception;}
    private void check(Check check){
        AtomicReference<Throwable> failure=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            TerminalSession session=new TerminalSession(20,4);
            TerminalView view=new TerminalView(InstrumentationRegistry.getInstrumentation().getTargetContext());
            view.setSession(session);
            try{check.run(session,view,(TerminalView.Renderer)view.getChildAt(0));}
            catch(Throwable t){failure.set(t);}finally{view.setSession(null);session.close();}
        });
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private void write(TerminalSession session,TerminalView.Renderer r,String text)throws Exception{
        session.write(text.getBytes(StandardCharsets.UTF_8));r.setRender(session.render());
    }
    private Bitmap cursor(TerminalView.Renderer r,boolean focus,long now){
        Bitmap b=Bitmap.createBitmap((int)Math.ceil(r.cellW*20),(int)Math.ceil(r.cellH*4),Bitmap.Config.ARGB_8888);
        b.eraseColor(0xff123456);r.drawCursor(new Canvas(b),now,focus,true);return b;
    }
    @Test public void defaultCursorBlinksAfterStyleResetButExplicitSteadyModesWin(){check((s,v,r)->{
        r.setRender(s.render());
        assertTrue(blinking(s));
        for(String steady:new String[]{"\033[2 q","\033[4 q","\033[6 q","\033[?12l"}){
            write(s,r,steady);assertFalse(blinking(s));
            write(s,r,"\033[0 q");assertTrue("Default style reset must restore blink",blinking(s));
            r.cursorBlink.reset(1000);
            Bitmap b=cursor(r,true,1800);try{assertEquals(0xff123456,b.getPixel(1,1));}finally{b.recycle();}
        }
        write(s,r,"\033[2 q\033[ q");assertTrue(blinking(s));
        write(s,r,"\033[2 q\033c");assertTrue("RIS must preserve the configured default",blinking(s));
        write(s,r,"\033[6 q");
        try(TerminalSession restored=TerminalSession.restore(s.checkpoint())){
            assertFalse("Checkpoint retains explicit steady cursor",blinking(restored));
            restored.write("\033[0 q".getBytes(StandardCharsets.UTF_8));
            assertTrue("Checkpoint retains default cursor policy",blinking(restored));
        }
    });}
    private boolean blinking(TerminalSession s){
        return java.nio.ByteBuffer.wrap(s.render()).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(9*4)!=0;
    }
    @Test public void solidBlockRedrawsReadableTextAndWideCell(){check((s,v,r)->{
        write(s,r,"\033c\033]11;#101010\007\033]12;#f0f0f0\007\033[2 qW\033[1G");
        Bitmap b=cursor(r,true,0);
        try{
            assertEquals(0xfff0f0f0,b.getPixel(1,1));
            int ink=0;for(int y=0;y<r.cellH;y++)for(int x=0;x<r.cellW;x++)if(b.getPixel(x,y)==0xff101010)ink++;
            assertTrue("Opaque cursor must redraw the covered W",ink>0);
        }finally{b.recycle();}
        write(s,r,"\033[2J\033[H中文\033[H");
        assertEquals(Math.round(r.cellW*2),r.cursorBounds().width(),0);
        b=cursor(r,true,0);try{assertEquals(0xfff0f0f0,b.getPixel((int)(r.cellW*1.8f),1));}finally{b.recycle();}
        // The trailing column of a wide cell highlights that same complete cell.
        write(s,r,"\033[2G");assertEquals(0,r.cursorBounds().left,0);assertEquals(Math.round(r.cellW*2),r.cursorBounds().right,0);
    });}
    @Test public void remoteShapesVisibilityAndInactiveOutlineAreRespected(){check((s,v,r)->{
        write(s,r,"\033c\033]12;#f0f0f0\007\033[6 q");
        Bitmap b=cursor(r,true,0);try{assertEquals(0xfff0f0f0,b.getPixel(0,1));assertEquals(0xff123456,b.getPixel((int)(r.cellW*.8f),1));}finally{b.recycle();}
        write(s,r,"\033[4 q");b=cursor(r,true,0);try{assertEquals(0xff123456,b.getPixel(1,1));assertEquals(0xfff0f0f0,b.getPixel(1,(int)r.cursorBounds().bottom-1));}finally{b.recycle();}
        write(s,r,"\033[1 q");r.cursorBlink.reset(1000);
        b=cursor(r,true,1800);try{assertEquals(0xff123456,b.getPixel(1,1));}finally{b.recycle();}
        b=cursor(r,false,1800);try{assertEquals(0xfff0f0f0,b.getPixel(0,1));assertEquals(0xff123456,b.getPixel((int)(r.cellW/2),(int)(r.cellH/2)));}finally{b.recycle();}
        write(s,r,"\033[?25l");assertTrue(r.cursorBounds().isEmpty());
        write(s,r,"\033[?25h");assertFalse(r.cursorBounds().isEmpty());
    });}
    @Test public void inputAndCursorMovementResetBlinkButUnrelatedOutputDoesNot(){check((s,v,r)->{
        write(s,r,"\033c\033[1 q");
        v.setListener(new TerminalView.Listener(){public void onInput(byte[] data){}public void onResize(int c,int rows){}});
        long now=SystemClock.uptimeMillis();r.cursorBlink.reset(now-800);assertFalse(r.cursorBlink.visible(now));
        v.sendText("x");assertTrue(r.cursorBlink.visible(SystemClock.uptimeMillis()));
        r.cursorBlink.reset(SystemClock.uptimeMillis()-800);
        write(s,r,"\033[2G");assertTrue(r.cursorBlink.visible(SystemClock.uptimeMillis()));
        r.cursorBlink.reset(SystemClock.uptimeMillis()-800);
        write(s,r,"\033]0;title only\007");assertFalse(r.cursorBlink.visible(SystemClock.uptimeMillis()));
        StringBuilder lines=new StringBuilder();for(int i=0;i<20;i++)lines.append("line\r\n");
        write(s,r,lines.toString());s.scroll(-100);r.setRender(s.render());assertTrue("No live cursor over old scrollback",r.cursorBounds().isEmpty());
    });}
}
