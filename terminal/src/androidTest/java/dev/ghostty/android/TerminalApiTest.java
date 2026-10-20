package dev.ghostty.android;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import android.view.inputmethod.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

public final class TerminalApiTest {
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    @Test public void testPinnedUnicodeContracts() throws Exception {
        JSONArray results=UnicodeChecks.run();assertEquals(85,results.length());
        for(int i=0;i<results.length();i++){JSONObject r=results.getJSONObject(i);assertTrue(r.toString(),r.getBoolean("pass"));}
    }
    @Test public void testPublicSessionStreamCheckpointAndReplies(){
        byte[] emoji=bytes("👨‍👩‍👧‍👦");
        try(TerminalSession original=new TerminalSession(40,4)){
            original.write(Arrays.copyOfRange(emoji,0,7));
            TerminalSession.Checkpoint saved=original.checkpoint();assertTrue(saved.sizeBytes()>0);
            try(TerminalSession restored=TerminalSession.restore(saved)){
                byte[] tail=Arrays.copyOfRange(emoji,7,emoji.length);
                original.write(tail);restored.write(tail);
                assertEquals("👨‍👩‍👧‍👦",restored.select(0,7));assertEquals(original.select(0,7),restored.select(0,7));
                restored.write(bytes("\033[?1h"));assertEquals("\033OA",new String(restored.encodeKey(TerminalSession.Key.UP),StandardCharsets.UTF_8));
                assertTrue(new String(restored.write(bytes("\033[6n")),StandardCharsets.UTF_8).contains("\033[1;9R"));
                restored.resize(20,8);assertEquals(20,restored.getColumns());assertEquals(8,restored.getRows());
            }
        }
    }
    @Test public void testOwnershipAndClose() throws Exception {
        TerminalSession session=new TerminalSession(10,3);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread thread=new Thread(()->{try{session.write(bytes("x"));}catch(Throwable t){failure.set(t);}});thread.start();thread.join();
        assertTrue(failure.get() instanceof IllegalStateException);
        session.close();session.close();
        try{session.write(bytes("x"));fail("Closed session accepted input");}catch(IllegalStateException expected){}
        try{new TerminalSession(0,4);fail("Invalid grid accepted");}catch(IllegalArgumentException expected){}
    }
    @Test public void testViewBindingAndImeCommit() throws Throwable {
        AtomicReference<Throwable> error=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{
            TerminalSession session=new TerminalSession(40,4);
            TerminalView view=new TerminalView(InstrumentationRegistry.getInstrumentation().getTargetContext());
            TerminalView other=new TerminalView(InstrumentationRegistry.getInstrumentation().getTargetContext());
            List<byte[]> sent=new ArrayList<>();
            view.setListener(new TerminalView.Listener(){public void onInput(byte[] b){sent.add(b);}public void onResize(int c,int r){}});
            view.setSession(session);
            try{other.setSession(session);fail("Second view accepted");}catch(IllegalStateException expected){}
            try{session.close();fail("Attached session closed");}catch(IllegalStateException expected){}
            TerminalInputView input=new TerminalInputView(InstrumentationRegistry.getInstrumentation().getTargetContext());input.setTerminalView(view);
            InputConnection connection=input.onCreateInputConnection(new EditorInfo());assertNotNull(connection);
            connection.setComposingText("zhongwen",1);assertEquals(0,sent.size());
            connection.setComposingText("",1);connection.finishComposingText();assertEquals(0,sent.size());
            connection.commitText("中文",1);assertEquals(1,sent.size());assertEquals("中文",new String(sent.get(0),StandardCharsets.UTF_8));
            connection.deleteSurroundingTextInCodePoints(1,0);assertEquals(2,sent.size());assertTrue(Arrays.equals(new byte[]{127},sent.get(1)));
            view.setSession(null);input.setTerminalView(null);session.close();
        }catch(Throwable t){error.set(t);}});
        if(error.get()!=null)throw error.get();
    }
}
