package dev.ghostty.android.sample;

import android.os.SystemClock;
import android.widget.EditText;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Opt-in real TUI test. Targets only the caller-created private tmux socket. */
public final class AgentSubmitDeviceTest extends AttachmentTestSupport {
    @org.junit.Test public void composerActuallySubmitsToAgent()throws Exception{
        String socket=InstrumentationRegistry.getArguments().getString("submitSocket"),provider=InstrumentationRegistry.getArguments().getString("submitProvider");
        assumeNotNull(socket,provider);assertTrue(socket.matches("/tmp/shello-submit-[0-9a-f]{8}\\.sock"));assertTrue(provider.equals("claude")||provider.equals("codex"));
        String profile=profile();assertNotNull("Use attachmentVia to isolate the test connection",temporaryProfile);open();
        SshSessionService service=(SshSessionService)field(activity,"sshService");String previous=service.selected()==null?null:service.selected().id,active=new SshProfileStore(context).activeId();
        String tmux="/opt/homebrew/bin/tmux -S "+SshCommand.quote(socket),target="probe:"+(provider.equals("codex")?"2":"1");
        String marker="SHELLO_"+provider.charAt(0)+"_"+(SystemClock.uptimeMillis()%100000);
        try{
            run(profile,tmux+" select-window -t "+SshCommand.quote(target));
            inst.runOnMainSync(()->SshSessionService.requestConnect(context,profile));await(()->service.selected()!=null&&profile.equals(service.selected().profile())&&service.connected());
            inst.runOnMainSync(()->service.send(("exec "+tmux+" attach-session -t probe\r").getBytes(StandardCharsets.UTF_8)));
            await(()->{dev.ghostty.android.TerminalSession session=service.selected().terminal();if(session==null)return false;String text=session.select(0,session.getColumns()*session.getRows()-1);return text.contains(provider.equals("codex")?"OpenAI Codex":"Claude Code");});
            draftMode();SystemClock.sleep(400);
            inst.runOnMainSync(()->{
                ((EditText)field(activity,"draft")).setText("请只回复 "+marker+"，不要使用任何工具。\n输入验收：中文 👨‍👩‍👧‍👦");
                try{Method send=MainActivity.class.getDeclaredMethod("sendDraft",boolean.class);send.setAccessible(true);send.invoke(activity,true);}catch(Exception error){throw new AssertionError(error);}
                assertEquals("",((EditText)field(activity,"draft")).getText().toString());
            });
            boolean submitted=false;long until=SystemClock.uptimeMillis()+20000;
            do{String pane=run(profile,tmux+" capture-pane -p -t "+SshCommand.quote(target));
                submitted=pane.contains(marker)&&(provider.equals("codex")?(pane.contains("Working")||pane.contains("Ask Codex to do anything")||pane.contains("• "+marker)):pane.matches("(?s).*\\n❯[ \\u00a0]*\\n.*"));
                if(!submitted)SystemClock.sleep(150);
            }while(!submitted&&SystemClock.uptimeMillis()<until);
            assertTrue("Prompt remained in the agent composer instead of being submitted",submitted);capture("submitted-"+provider);
        }finally{inst.runOnMainSync(()->{service.closeProfile(profile);if(previous!=null)service.select(previous);context.getSharedPreferences("ssh_profiles",0).edit().putString("active",active).commit();});}
    }
}
