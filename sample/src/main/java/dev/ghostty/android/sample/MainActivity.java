package dev.ghostty.android.sample;

import android.app.Activity;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import android.content.*;
import dev.ghostty.android.*;
import java.nio.charset.StandardCharsets;

/** Offline renderer/IME example. It deliberately does not pretend to provide a shell. */
public final class MainActivity extends Activity {
    private TerminalSession session;
    private TerminalView terminal;
    private TerminalInputView input;
    private TextView status;
    private TerminalSession.Checkpoint checkpoint;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
            android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));return insets;
        });
        setContentView(root);
        status=new TextView(this);status.setText("Offline echo — no shell or network");root.addView(status);
        LinearLayout actions=new LinearLayout(this);root.addView(actions);
        button(actions,"Unicode",()->write("\033cLegacy |👨‍👩‍👧‍👦|👍🏽|🇨🇳|1️⃣|\r\n\033[?2027hCluster |👨‍👩‍👧‍👦|👍🏽|🇨🇳|1️⃣|\r\n\033[?2027l中文 é — type below\r\n"));
        button(actions,"Copy",()->{
            String text=terminal.getSelectedText();
            if(!text.isEmpty()){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Terminal",text));status.setText("Copied: "+text);}
        });
        button(actions,"Save",()->{checkpoint=session.checkpoint();status.setText("Saved in RAM: "+checkpoint.sizeBytes()+" bytes");});
        button(actions,"Restore",()->{
            if(checkpoint==null)return;
            TerminalSession restored=TerminalSession.restore(checkpoint);
            terminal.setSession(null);session.close();session=restored;terminal.setSession(session);status.setText("Restored local checkpoint");
        });
        terminal=new TerminalView(this);root.addView(terminal,new LinearLayout.LayoutParams(-1,0,1));
        session=new TerminalSession(40,20);
        terminal.setListener(new TerminalView.Listener(){
            @Override public void onInput(byte[] bytes){
                // Offline echo only: a real integration writes these bytes to its PTY.
                String text=new String(bytes,StandardCharsets.UTF_8);
                if(text.equals("\r"))text="\r\n";
                write(text);
            }
            @Override public void onResize(int columns,int rows){status.setText("Offline echo · "+columns+" × "+rows);}
        });
        terminal.setSession(session);
        LinearLayout keys=new LinearLayout(this);root.addView(keys);
        button(keys,"Esc",()->terminal.sendKey(TerminalSession.Key.ESCAPE));
        button(keys,"Tab",()->terminal.sendKey(TerminalSession.Key.TAB));
        button(keys,"Enter",()->terminal.sendKey(TerminalSession.Key.ENTER));
        button(keys,"Bottom",terminal::scrollToBottom);
        input=new TerminalInputView(this);input.setTerminalView(terminal);root.addView(input);
        write("Ghostty Android · offline echo\r\n中文 👨‍👩‍👧‍👦 👍🏽 🇨🇳 1️⃣\r\nLong press to select; type below.\r\n");
    }
    private void write(String text){session.write(text.getBytes(StandardCharsets.UTF_8));}
    private void button(LinearLayout row,String title,Runnable action){Button b=new Button(this);b.setText(title);row.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->action.run());}
    @Override protected void onDestroy(){input.setTerminalView(null);terminal.setSession(null);session.close();super.onDestroy();}
}
