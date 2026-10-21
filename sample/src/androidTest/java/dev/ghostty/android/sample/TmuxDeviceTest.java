package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Opt-in acceptance using a saved pinned profile; only its own remote fixture is removed. */
public class TmuxDeviceTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Context context=inst.getTargetContext();
    String run(String profile,String command)throws Exception{try(SshCommand ssh=new SshCommand()){return ssh.run(context,profile,command,null);}}
    @Test public void newSessionHasColorAndReconnectPreservesItsShell()throws Exception{
        String host=InstrumentationRegistry.getArguments().getString("tmuxHost");assumeNotNull(host);
        String profile=null;for(SshProfileStore.Entry e:new SshProfileStore(context).list())if(host.equals(e.host))profile=e.id;
        assertNotNull("Expected saved SSH profile",profile);
        String target=SessionTarget.fresh(true),quoted=SshCommand.quote("="+target);
        String lookup=SshConnectionSetup.TMUX_LOOKUP;
        String globalCommand=lookup+"\"$T\" show-environment -g NO_COLOR 2>/dev/null || true";
        String before=run(profile,globalCommand),pid=null;
        try{
            for(int attempt=0;attempt<2;attempt++){
                CountDownLatch connected=new CountDownLatch(1),output=new CountDownLatch(1);
                StringBuilder text=new StringBuilder();
                SshTransport transport=new SshTransport(context,new SshProfileStore(context).directory(profile),80,24,new SshTransport.Listener(){
                    public void onState(String state,boolean yes){if(yes)connected.countDown();}
                    public void onOutput(byte[] bytes){
                        text.append(new String(bytes,StandardCharsets.UTF_8));
                        if(text.toString().contains("SHELLO_COLOR:<>:<truecolor>"))output.countDown();
                    }
                }).target(target);
                try{
                    transport.connect();assertTrue("SSH connection",connected.await(15,TimeUnit.SECONDS));
                    // Split the marker so PTY echo cannot satisfy the output assertion.
                    transport.send("printf '\\033[38;2;200;120;60mSHELLO_%s:<%s>:<%s>\\033[0m\\n' COLOR \"$NO_COLOR\" \"$COLORTERM\"\r".getBytes(StandardCharsets.UTF_8));
                    assertTrue("New shell must enable color",output.await(10,TimeUnit.SECONDS));
                    String actual=run(profile,lookup+"\"$T\" display-message -p -t "+quoted+" '#{pane_pid}'").trim();
                    if(pid==null)pid=actual;else assertEquals("Reconnect must keep the shell",pid,actual);
                }finally{transport.close();}
                if(attempt==0)run(profile,lookup+"\"$T\" set-environment -t "+quoted+" NO_COLOR SHELLO_KEEP");
            }
            assertEquals("Attach must not overwrite an existing session's environment","NO_COLOR=SHELLO_KEEP",
                    run(profile,lookup+"\"$T\" show-environment -t "+quoted+" NO_COLOR").trim());
            assertEquals("Do not change global server preferences",before,run(profile,globalCommand));
        }finally{run(profile,lookup+"\"$T\" kill-session -t "+quoted);}
    }
    @Test public void existingSessionIsListedVisibleSelectableAndAttachable()throws Exception{
        String host=InstrumentationRegistry.getArguments().getString("tmuxHost");assumeNotNull(host);
        String id=null;for(SshProfileStore.Entry e:new SshProfileStore(context).list())if(host.equals(e.host))id=e.id;
        assertNotNull("Expected saved SSH profile",id);final String profile=id;
        String name="00 Shello 中文_"+UUID.randomUUID().toString().substring(0,8),quoted=SshCommand.quote("="+name+":");
        String lookup=SshConnectionSetup.TMUX_LOOKUP;AtomicReference<Dialog> dialog=new AtomicReference<>();
        SshConnectionFlowTest ui=new SshConnectionFlowTest();
        run(profile,lookup+"\"$T\" -u new-session -d -s "+SshCommand.quote(name));
        try{
            List<TmuxSessions.Entry> entries=TmuxSessions.parse(run(profile,TmuxSessions.COMMAND));
            assertTrue("Remote listing lost the existing fixture",entries.stream().anyMatch(e->name.equals(e.name)));
            String pid=run(profile,lookup+"\"$T\" display-message -p -t "+quoted+" '#{pane_pid}'").trim();
            BlockingQueue<Activity> resumed=new LinkedBlockingQueue<>();Application app=(Application)context.getApplicationContext();
            Application.ActivityLifecycleCallbacks callback=new Application.ActivityLifecycleCallbacks(){
                public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){if(a instanceof MainActivity)resumed.add(a);}
                public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
            };app.registerActivityLifecycleCallbacks(callback);
            try{try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -n dev.ghostty.android.sample/.MainActivity -f 0x10008000");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
                ui.current=resumed.poll(10,TimeUnit.SECONDS);assertNotNull(ui.current);
            }finally{app.unregisterActivityLifecycleCallbacks(callback);}
            CountDownLatch ready=new CountDownLatch(1);AtomicReference<String> selected=new AtomicReference<>();
            inst.runOnMainSync(()->dialog.set(TmuxPicker.show(ui.current,profile,selected::set,ready::countDown)));
            assertTrue(ready.await(15,TimeUnit.SECONDS));inst.waitForIdleSync();
            ui.await(()->dialog.get().getWindow().getDecorView().getHeight()>0,3000);
            inst.runOnMainSync(()->{
                View row=ui.find(dialog.get().getWindow().getDecorView(),AppLanguage.tr(R.string.open_tmux_name,name));assertNotNull("Existing session missing in sheet",row);
                android.graphics.Rect visible=new android.graphics.Rect();assertTrue("Row must be visible",row.getGlobalVisibleRect(visible));assertTrue(visible.height()>0);
                assertTrue(row.performClick());
            });
            assertEquals("="+name,SessionTarget.target(selected.get()));assertEquals(profile,SessionTarget.profile(selected.get()));
            CountDownLatch connected=new CountDownLatch(1),output=new CountDownLatch(1);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            SshTransport transport=new SshTransport(context,new SshProfileStore(context).directory(profile),60,24,new SshTransport.Listener(){
                public void onState(String state,boolean yes){if(yes)connected.countDown();}
                public void onOutput(byte[] value){bytes.write(value,0,value.length);if(new String(bytes.toByteArray(),StandardCharsets.UTF_8).contains("SHELLO_LIST_ATTACH_OK"))output.countDown();}
            }).target(SessionTarget.target(selected.get()));
            try{transport.connect();assertTrue(connected.await(15,TimeUnit.SECONDS));transport.send("printf 'SHELLO_LIST_ATTACH_OK\\n'\r".getBytes(StandardCharsets.UTF_8));assertTrue(output.await(10,TimeUnit.SECONDS));}
            finally{transport.close();}
            assertEquals("Must attach to the same shell",pid,run(profile,lookup+"\"$T\" display-message -p -t "+quoted+" '#{pane_pid}'").trim());
        }finally{
            inst.runOnMainSync(()->{if(dialog.get()!=null)dialog.get().dismiss();if(ui.current!=null)ui.current.finish();});
            run(profile,lookup+"\"$T\" kill-session -t "+SshCommand.quote("="+name));
        }
    }
}
