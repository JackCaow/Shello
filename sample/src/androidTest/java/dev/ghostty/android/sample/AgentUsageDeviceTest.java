package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.os.ParcelFileDescriptor;
import android.view.*;
import android.graphics.Bitmap;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Read real signed-in quota snapshots through a saved pinned host; no inference or credential export. */
public class AgentUsageDeviceTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();final Context context=inst.getTargetContext();
    @Test public void realUsageAndDiagnosticsReachPhone()throws Exception{
        String host=InstrumentationRegistry.getArguments().getString("usageHost");assumeNotNull(host);
        String found=null;for(SshProfileStore.Entry e:new SshProfileStore(context).list())if(host.equals(e.host))found=e.id;assertNotNull(found);final String profile=found;
        try(SshCommand ssh=new SshCommand()){
            JSONObject info=new JSONObject(ssh.run(context,profile,AgentHub.COMMAND+"doctor",null));assertEquals(2,info.getInt("version"));assertTrue(info.getJSONArray("capabilities").toString().contains("usage"));
        }
        AgentHub hub=AgentHub.get(context);assertTrue("Enable the real host Hook first",hub.enabled(profile));
        inst.runOnMainSync(()->hub.refreshUsage(true));
        JSONObject[] snapshot={null};long until=System.currentTimeMillis()+25000;
        do{inst.runOnMainSync(()->snapshot[0]=hub.usages.get(profile));if(!ready(snapshot[0]))Thread.sleep(100);}while(!ready(snapshot[0])&&System.currentTimeMillis()<until);
        assertTrue("Expected a fresh successful host snapshot",ready(snapshot[0]));JSONArray accounts=snapshot[0].getJSONArray("accounts");assertTrue(accounts.length()>=2);Set<String> providers=new HashSet<>();
        for(int i=0;i<accounts.length();i++){JSONObject a=accounts.getJSONObject(i);assertEquals("Quota unavailable for "+a.optString("provider"),"ok",a.getString("status"));providers.add(a.getString("provider"));assertTrue(a.getJSONArray("windows").length()>0);}
        assertTrue(providers.containsAll(Arrays.asList("claude","codex")));
        try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -W -n dev.ghostty.android.sample/.MainActivity");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
        AgentActivity page=(AgentActivity)inst.startActivitySync(new Intent(context,AgentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            String marker=InstrumentationRegistry.getArguments().getString("expectedClaudeCompletion");
            if(marker!=null){boolean[] received={false};long end=System.currentTimeMillis()+20000;do{inst.runOnMainSync(()->received[0]=hub.sessions().stream().anyMatch(s->profile.equals(s.profile)&&"claude".equals(s.provider)&&s.summary().contains(marker)));if(!received[0])Thread.sleep(100);}while(!received[0]&&System.currentTimeMillis()<end);assertTrue("Real Claude completion must reach the session board",received[0]);}
            AgentInboxTest providerUi=new AgentInboxTest();
            inst.waitForIdleSync();Thread.sleep(300);Bitmap board=inst.getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),"agent-provider-icons.png"))){board.compress(Bitmap.CompressFormat.PNG,100,out);}finally{board.recycle();}
            for(String provider:new String[]{"claude","codex"})inst.runOnMainSync(()->{
                View button=findDescription(page.root,"agent_usage_"+provider);assertNotNull(button);button.performClick();
                for(int i=0;i<accounts.length();i++)try{JSONObject account=accounts.getJSONObject(i);View row=providerUi.find(usageRoot(page),account.getString("provider")+" · "+account.getString("accountLabel"));if(provider.equals(account.getString("provider")))assertNotNull("Selected provider account missing",row);else assertNull("Other provider must not appear in this usage sheet",row);}catch(JSONException e){throw new AssertionError(e);}
                usageDialog(page).dismiss();
            });
            inst.runOnMainSync(()->{View button=findDescription(page.root,"agent_usages");assertNotNull(button);button.performClick();});inst.waitForIdleSync();
            AgentInboxTest ui=new AgentInboxTest();inst.runOnMainSync(()->{for(int i=0;i<accounts.length();i++)try{JSONObject account=accounts.getJSONObject(i);assertNotNull(ui.find(usageRoot(page),account.getString("provider")+" · "+account.getString("accountLabel")));}catch(JSONException e){throw new AssertionError(e);}});
            Thread.sleep(400);Bitmap image=inst.getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),"agent-real-usage.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
        }finally{inst.runOnMainSync(page::finish);}
    }
    private boolean ready(JSONObject snapshot){if(snapshot==null||!"ok".equals(snapshot.optString("status"))||System.currentTimeMillis()/1000d-snapshot.optDouble("capturedAt",0)>120)return false;JSONArray accounts=snapshot.optJSONArray("accounts");if(accounts==null||accounts.length()<2)return false;for(int i=0;i<accounts.length();i++)if(!"ok".equals(accounts.optJSONObject(i).optString("status")))return false;return true;}
    private View usageRoot(AgentActivity page){return usageDialog(page).getWindow().getDecorView();}
    private Dialog usageDialog(AgentActivity page){try{java.lang.reflect.Field field=AgentActivity.class.getDeclaredField("usageDialog");field.setAccessible(true);return (Dialog)field.get(page);}catch(Exception e){throw new AssertionError(e);}}
    private View findDescription(View root,String description){if(description.equals(root.getTag())||root.getContentDescription()!=null&&description.contentEquals(root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View v=findDescription(((ViewGroup)root).getChildAt(i),description);if(v!=null)return v;}return null;}
}
