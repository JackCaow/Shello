package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Run prepare, force-stop externally, then verify: no profile, network or user history deletion. */
public class AgentRestoreDeviceTest {
    @Test public void realSessionAndArchiveSurviveProcessRestart()throws Exception{
        String phase=InstrumentationRegistry.getArguments().getString("restorePhase"),tmux=InstrumentationRegistry.getArguments().getString("agentTmux");assumeNotNull(phase,tmux);
        assertTrue(tmux.matches("shello_inbox_[a-z_]+"));Instrumentation inst=InstrumentationRegistry.getInstrumentation();Context context=inst.getTargetContext();AgentHub hub=AgentHub.get(context);SharedPreferences receipt=context.getSharedPreferences("inbox-restore-acceptance",0);
        long until=System.currentTimeMillis()+20000;boolean[] ready={false};do{inst.runOnMainSync(()->ready[0]=!hub.busy);if(!ready[0])Thread.sleep(50);}while(!ready[0]&&System.currentTimeMillis()<until);assertTrue(ready[0]);
        if("prepare".equals(phase)){
            AgentInbox.Session[] match={null};inst.runOnMainSync(()->{for(AgentInbox.Session s:hub.sessions())if(("="+tmux).equals(s.target()))match[0]=s;});assertNotNull(match[0]);assertFalse(match[0].unresolved);
            String key=match[0].key;receipt.edit().putString("key",key).putString("profile",match[0].profile).putLong("archive",hub.preferences.getLong("archive_"+key,0)).commit();
            inst.runOnMainSync(()->hub.archive(match[0],true));hub.worker.submit(()->{}).get(10,TimeUnit.SECONDS);
            assertTrue(new AgentCache(context).read().getJSONArray("events").length()>0);
        }else if("verify".equals(phase)){
            String key=receipt.getString("key",null);assertNotNull("Prepare must run before process restart",key);
            try{
                assertTrue(new SshProfileStore(context).list().stream().anyMatch(e->e.id.equals(receipt.getString("profile",null))));
                JSONArray saved=new AgentCache(context).read().getJSONArray("events");boolean found=false;for(int i=0;i<saved.length();i++)if(tmux.equals(saved.getJSONObject(i).getJSONObject("data").optString("tmux_session")))found=true;assertTrue("Remote session must exist in the persisted private snapshot",found);
                AgentInbox.Session[] restored={null};inst.runOnMainSync(()->{for(AgentInbox.Session s:hub.sessions())if(key.equals(s.key))restored[0]=s;});assertNotNull(restored[0]);assertTrue("Archive choice must survive a fresh app process",restored[0].archived);
                try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -W -n dev.ghostty.android.sample/.MainActivity");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
                AgentActivity page=(AgentActivity)inst.startActivitySync(new Intent(context,AgentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                inst.runOnMainSync(()->{assertNotNull(page.body.findViewWithTag(restored[0].latest.id()));page.finish();});
            }finally{
                long old=receipt.getLong("archive",0);inst.runOnMainSync(()->{SharedPreferences.Editor edit=hub.preferences.edit();if(old==0)edit.remove("archive_"+key);else edit.putLong("archive_"+key,old);edit.commit();hub.changed();});receipt.edit().clear().commit();
            }
        }else fail("Expected prepare or verify");
    }
}
