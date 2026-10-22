package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** The host controller removes/restores only this test's adb reverse mapping. */
public class AgentReconnectDeviceTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();final Context context=inst.getTargetContext();
    void await(BooleanSupplier condition)throws Exception{long until=System.currentTimeMillis()+35000;boolean[] ready={false};do{inst.runOnMainSync(()->ready[0]=condition.getAsBoolean());if(ready[0])return;Thread.sleep(100);}while(System.currentTimeMillis()<until);fail("SSH inbox recovery phase timed out");}
    void phase(String value)throws Exception{try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),"inbox-reconnect-phase"))){out.write(value.getBytes(StandardCharsets.UTF_8));}}
    @Test public void cachedEventsCannotApproveAndRecoverAfterSshTransportLoss()throws Exception{
        String host=InstrumentationRegistry.getArguments().getString("reconnectHost"),port=InstrumentationRegistry.getArguments().getString("reconnectPort");assumeNotNull(host,port);
        SshProfileStore store=new SshProfileStore(context);String original=null;for(SshProfileStore.Entry e:store.list())if(host.equals(e.host))original=e.id;assertNotNull(original);
        SshProfileStore.Draft draft=store.read(store.directory(original));draft.host="127.0.0.1";draft.port=Integer.parseInt(port);draft.name="Inbox recovery acceptance";String key=new String(draft.knownHosts,StandardCharsets.UTF_8).trim();draft.knownHosts=("[127.0.0.1]:"+draft.port+" "+key.substring(key.indexOf(' ')+1)+"\n").getBytes(StandardCharsets.UTF_8);String profile=store.save(draft);
        AgentHub hub=AgentHub.get(context);AgentActivity page=null;
        try{
            inst.runOnMainSync(()->hub.preferences.edit().putBoolean("host_"+profile,true).commit());
            try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -W -n dev.ghostty.android.sample/.MainActivity");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
            page=(AgentActivity)inst.startActivitySync(new Intent(context,AgentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->hub.states.getOrDefault(profile,0)==R.string.connected&&hub.events.stream().anyMatch(e->profile.equals(e.profile)&&!e.data.optBoolean("cached")));phase("connected");
            await(()->hub.states.getOrDefault(profile,0)!=R.string.connected&&hub.events.stream().anyMatch(e->profile.equals(e.profile)&&e.data.optBoolean("offline")));
            inst.runOnMainSync(()->{for(AgentHub.Event e:hub.snapshot())if(profile.equals(e.profile)){assertTrue(e.data.optBoolean("cached"));assertFalse(e.pending());}});phase("offline");
            await(()->hub.states.getOrDefault(profile,0)==R.string.connected&&hub.events.stream().anyMatch(e->profile.equals(e.profile)&&!e.data.optBoolean("cached")&&!e.data.optBoolean("offline")));phase("recovered");
        }finally{
            final AgentActivity inbox=page;inst.runOnMainSync(()->{if(inbox!=null)inbox.finish();hub.preferences.edit().remove("host_"+profile).commit();hub.events.removeIf(e->profile.equals(e.profile));hub.states.remove(profile);hub.usages.remove(profile);hub.diagnostics.remove(profile);});store.delete(profile);
        }
    }
}
