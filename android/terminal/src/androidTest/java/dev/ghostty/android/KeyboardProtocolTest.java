package dev.ghostty.android;

import android.view.*;
import android.view.inputmethod.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Byte-level assertions against the native VT encoder, including the Android IME bridge. */
public class KeyboardProtocolTest {
    String text(byte[] b){return new String(b,StandardCharsets.UTF_8);}
    @Test public void legacyModifiersAndFunctionKeys(){try(TerminalSession s=new TerminalSession(40,4)){
        assertEquals("\033[Z",text(s.encodeKey(TerminalSession.Key.TAB,TerminalSession.SHIFT)));
        assertEquals("\033[1;5D",text(s.encodeKey(TerminalSession.Key.LEFT,TerminalSession.CTRL)));
        assertEquals("\033[1;3C",text(s.encodeKey(TerminalSession.Key.RIGHT,TerminalSession.ALT)));
        assertEquals("\033OP",text(s.encodeKey(TerminalSession.Key.F1,0)));
        assertEquals("\033[24~",text(s.encodeKey(TerminalSession.Key.F12,0)));
        assertEquals("\003",text(s.encodeCharacter('c',"c",TerminalSession.CTRL)));
        assertEquals("\033x",text(s.encodeCharacter('x',"x",TerminalSession.ALT)));
        assertEquals("中",text(s.encodeCharacter('中',"中",0)));
        s.write("\033[?1h".getBytes(StandardCharsets.UTF_8));assertEquals("\033OA",text(s.encodeKey(TerminalSession.Key.UP,0)));
    }}
    @Test public void imeCompositionAndHardwareModifiers(){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
        try(TerminalSession s=new TerminalSession(40,4)){
            TerminalView view=new TerminalView(InstrumentationRegistry.getInstrumentation().getTargetContext());List<String> sent=new ArrayList<>();view.setListener(new TerminalView.Listener(){public void onInput(byte[] b){sent.add(text(b));}public void onResize(int c,int r){}});view.setSession(s);
            TerminalInputView input=new TerminalInputView(view.getContext());input.setTerminalView(view);
            input.onKeyDown(KeyEvent.KEYCODE_TAB,new KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_TAB,0,KeyEvent.META_SHIFT_ON));assertEquals(List.of("\033[Z"),sent);sent.clear();
            input.onKeyDown(KeyEvent.KEYCODE_C,new KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_C,0,KeyEvent.META_CTRL_ON));assertEquals(List.of("\003"),sent);sent.clear();
            input.setOptionAsMeta(true);input.onKeyDown(KeyEvent.KEYCODE_X,new KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_X,0,KeyEvent.META_ALT_ON));assertEquals(List.of("\033x"),sent);sent.clear();
            InputConnection ic=input.onCreateInputConnection(new EditorInfo());ic.setComposingText("zhong",1);assertTrue(sent.isEmpty());ic.commitText("中文 👨‍👩‍👧‍👦",1);assertEquals(List.of("中文 👨‍👩‍👧‍👦"),sent);
            view.setSession(null);input.setTerminalView(null);
        }
    });}
    @Test public void emptyCompositionCannotSwallowFollowingTerminalKeys(){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
        try(TerminalSession session=new TerminalSession(40,4)){
            TerminalView view=new TerminalView(InstrumentationRegistry.getInstrumentation().getTargetContext());List<String> sent=new ArrayList<>();
            view.setListener(new TerminalView.Listener(){public void onInput(byte[] bytes){sent.add(text(bytes));}public void onResize(int c,int r){}});view.setSession(session);
            TerminalInputView input=new TerminalInputView(view.getContext());input.setTerminalView(view);
            InputConnection connection=input.onCreateInputConnection(new EditorInfo());
            connection.setComposingText("zhong",1);assertTrue(sent.isEmpty());connection.setComposingText("",1);
            input.onKeyDown(KeyEvent.KEYCODE_T,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_T));
            input.onKeyUp(KeyEvent.KEYCODE_T,new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_T));
            assertEquals(List.of("t"),sent);assertEquals("",input.getText().toString());
            connection.setComposingText("zhongwen",1);assertEquals(1,sent.size());connection.commitText("中文",1);assertEquals(List.of("t","中文"),sent);
            view.setSession(null);input.setTerminalView(null);
        }
    });}
    @Test public void pendingGesturesCannotSendToTheNextSession()throws Exception{
        TerminalView[] view={null};TerminalSession[] sessions=new TerminalSession[2];List<String> sent=new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{sessions[0]=new TerminalSession(40,4);sessions[1]=new TerminalSession(40,4);view[0]=new TerminalView(InstrumentationRegistry.getInstrumentation().getTargetContext());view[0].setListener(new TerminalView.Listener(){public void onInput(byte[] b){sent.add(text(b));}public void onResize(int c,int r){}});view[0].setSession(sessions[0]);view[0].setGestureListener((g,x,y)->{view[0].sendKey(TerminalSession.Key.ESCAPE);return true;});View touch=view[0].getChildAt(1);long t=android.os.SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(t,t,0,20,20,0),up=MotionEvent.obtain(t,t+20,1,20,20,0);touch.dispatchTouchEvent(down);touch.dispatchTouchEvent(up);down.recycle();up.recycle();view[0].setSession(sessions[1]);});
        android.os.SystemClock.sleep(android.view.ViewConfiguration.getDoubleTapTimeout()+150);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{assertTrue("A delayed tap reached the next PTY",sent.isEmpty());view[0].setGestureListener(null);view[0].setSession(null);sessions[0].close();sessions[1].close();});
    }
    @Test public void negotiatedMousePressReleaseAndDrag(){try(TerminalSession s=new TerminalSession(40,4)){
        assertEquals("",text(s.encodeMouse(0,false,12,20,6,10)));
        s.write("\033[?1002h\033[?1006h".getBytes(StandardCharsets.UTF_8));
        assertEquals("\033[<0;3;3M",text(s.encodeMouse(0,false,12,20,6,10)));
        assertEquals("\033[<32;3;3M",text(s.encodeMouse(2,false,12,20,6,10)));
        assertEquals("\033[<0;3;3m",text(s.encodeMouse(1,false,12,20,6,10)));
        assertEquals("\033[<2;3;3M",text(s.encodeMouse(0,true,12,20,6,10)));
    }}
}
