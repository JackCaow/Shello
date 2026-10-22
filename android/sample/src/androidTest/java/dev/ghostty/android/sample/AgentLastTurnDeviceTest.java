package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Actual CLI hooks → saved pinned SSH → visible last-turn pair → private cache. */
public class AgentLastTurnDeviceTest {
    @Test public void actualLastUserAndAssistantMessagesReachCardAndDetails()throws Exception{
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();Context context=inst.getTargetContext();
        String host=InstrumentationRegistry.getArguments().getString("lastTurnHost"),provider=InstrumentationRegistry.getArguments().getString("lastTurnProvider"),prompt=InstrumentationRegistry.getArguments().getString("lastTurnPrompt"),reply=InstrumentationRegistry.getArguments().getString("lastTurnReply");assumeNotNull(host,provider,prompt,reply);
        assertTrue(provider.equals("claude")||provider.equals("codex"));String found=null;for(SshProfileStore.Entry e:new SshProfileStore(context).list())if(host.equals(e.host))found=e.id;assertNotNull(found);final String profile=found;
        AgentHub hub=AgentHub.get(context);SharedPreferences prefs=hub.preferences;String oldSearch=prefs.getString("search",null),oldProvider=prefs.getString("filter_provider",null),oldHost=prefs.getString("filter_host",null),oldProject=prefs.getString("filter_project",null);
        inst.runOnMainSync(()->prefs.edit().remove("search").remove("filter_provider").remove("filter_host").remove("filter_project").commit());AgentActivity page=null;
        try{
            try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -W -n dev.ghostty.android.sample/.MainActivity");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
            page=(AgentActivity)inst.startActivitySync(new Intent(context,AgentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));AgentInbox.Session[] match={null};long until=System.currentTimeMillis()+25000;
            do{inst.runOnMainSync(()->{for(AgentInbox.Session s:hub.sessions())if(profile.equals(s.profile)&&provider.equals(s.provider)&&s.lastPrompt().contains(prompt)&&s.lastAssistant().contains(reply))match[0]=s;});if(match[0]==null)Thread.sleep(100);}while(match[0]==null&&System.currentTimeMillis()<until);
            assertNotNull("Both real last-turn messages must reach the phone",match[0]);final AgentActivity inbox=page;AgentInboxTest ui=new AgentInboxTest();
            inst.runOnMainSync(()->{View card=inbox.body.findViewWithTag(match[0].latest.id());assertNotNull(card);assertNotNull(ui.find(card,(inbox.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?"你：":"You: ")+match[0].lastPrompt()));card.performClick();assertNotNull(ui.find(inbox.contentRoot(),match[0].lastPrompt()));assertNotNull(ui.find(inbox.contentRoot(),match[0].lastAssistant()));});
            inst.waitForIdleSync();Thread.sleep(300);Bitmap image=inst.getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),"agent-last-turn-"+provider+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
            inst.runOnMainSync(()->{try{Method persist=AgentHub.class.getDeclaredMethod("persist");persist.setAccessible(true);persist.invoke(hub);}catch(Exception e){throw new AssertionError(e);}});hub.worker.submit(()->{}).get(10,TimeUnit.SECONDS);
            JSONArray rows=new AgentCache(context).read().getJSONArray("events");boolean savedPrompt=false,savedReply=false;
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i),data=row.getJSONObject("data");if(profile.equals(row.getString("profile"))&&provider.equals(data.optString("provider"))&&match[0].sessionId.equals(data.optString("session_id"))){savedPrompt|=data.optString("prompt_preview").contains(prompt);savedReply|=data.optString("last_assistant_message").contains(reply);}}
            assertTrue("Both messages must survive a fresh cache read",savedPrompt&&savedReply);
        }finally{final AgentActivity inbox=page;inst.runOnMainSync(()->{if(inbox!=null)inbox.finish();prefs.edit().putString("search",oldSearch).putString("filter_provider",oldProvider).putString("filter_host",oldHost).putString("filter_project",oldProject).commit();});}
    }
}
