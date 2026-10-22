package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Opt-in saved-host acceptance: the inbox must attach, never restart the agent. */
public class AgentTerminalDeviceTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Context context=inst.getTargetContext();
    @Test public void openExistingTmuxFromSessionCard()throws Exception{
        String host=InstrumentationRegistry.getArguments().getString("agentHost"),session=InstrumentationRegistry.getArguments().getString("agentTmux");
        assumeNotNull(host,session);assertTrue(session.matches("shello_inbox_[a-z_]+"));
        String found=null;for(SshProfileStore.Entry e:new SshProfileStore(context).list())if(host.equals(e.host))found=e.id;
        assertNotNull(found);final String profile=found,target="="+session;
        String command=SshConnectionSetup.TMUX_LOOKUP+"\"$T\" display-message -p -t "+SshCommand.quote(target+":")+" '#{pane_pid}'";
        String before;try(SshCommand ssh=new SshCommand()){before=ssh.run(context,profile,command,null).trim();}assertTrue(before.matches("[0-9]+"));
        AgentHub hub=AgentHub.get(context);android.content.SharedPreferences prefs=hub.preferences;
        String oldSearch=prefs.getString("search",null),oldProvider=prefs.getString("filter_provider",null),oldHost=prefs.getString("filter_host",null),oldProject=prefs.getString("filter_project",null);
        inst.runOnMainSync(()->prefs.edit().remove("search").remove("filter_provider").remove("filter_host").remove("filter_project").commit());
        BlockingQueue<MainActivity> resumed=new LinkedBlockingQueue<>();Application app=(Application)context.getApplicationContext();
        Application.ActivityLifecycleCallbacks listener=new Application.ActivityLifecycleCallbacks(){public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){if(a instanceof MainActivity)resumed.add((MainActivity)a);}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}};
        app.registerActivityLifecycleCallbacks(listener);AgentActivity page=null;SshSessionService[] cleanup={null};
        try{
            try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -W -n dev.ghostty.android.sample/.MainActivity");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
            page=(AgentActivity)inst.startActivitySync(new Intent(context,AgentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AgentInbox.Session[] match={null};long until=System.currentTimeMillis()+20000;
            do{inst.runOnMainSync(()->{for(AgentInbox.Session s:hub.sessions())if(s.profile.equals(profile)&&target.equals(s.target()))match[0]=s;});if(match[0]==null)Thread.sleep(100);}while(match[0]==null&&System.currentTimeMillis()<until);
            assertNotNull("Real host event missing",match[0]);final AgentActivity inbox=page;AgentInboxTest ui=new AgentInboxTest();resumed.clear();
            inst.runOnMainSync(()->{View card=inbox.body.findViewWithTag(match[0].latest.id());assertNotNull(card);card.performClick();View open=ui.find(inbox.contentRoot(),inbox.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?"打开终端":"Open terminal");assertNotNull(open);open.performClick();});
            MainActivity terminal=resumed.poll(10,TimeUnit.SECONDS);assertNotNull("Terminal Activity did not resume",terminal);
            boolean[] attached={false};until=System.currentTimeMillis()+25000;Field field=MainActivity.class.getDeclaredField("sshService");field.setAccessible(true);
            do{inst.runOnMainSync(()->{try{SshSessionService service=(SshSessionService)field.get(terminal);cleanup[0]=service;attached[0]=service!=null&&service.selected()!=null&&service.selected().id.equals(SessionTarget.id(profile,target))&&service.connected();}catch(Exception error){throw new AssertionError(error);}});if(!attached[0])Thread.sleep(100);}while(!attached[0]&&System.currentTimeMillis()<until);
            assertTrue("Inbox target must connect through the saved pinned SSH profile",attached[0]);
            try(SshCommand ssh=new SshCommand()){assertEquals("Agent process must survive attachment",before,ssh.run(context,profile,command,null).trim());}
        }finally{
            final AgentActivity inbox=page;inst.runOnMainSync(()->{if(inbox!=null&&!inbox.isDestroyed())inbox.finish();if(cleanup[0]!=null)cleanup[0].close(SessionTarget.id(profile,target));prefs.edit().putString("search",oldSearch).putString("filter_provider",oldProvider).putString("filter_host",oldHost).putString("filter_project",oldProject).commit();});app.unregisterActivityLifecycleCallbacks(listener);
        }
    }
}
