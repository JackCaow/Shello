package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Isolated UI fixtures; never sends an approval or opens an unverified SSH connection. */
public class AgentBoardUiTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();final Context context=inst.getTargetContext();
    Object field(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    View description(View root,String value){if(root.getContentDescription()!=null&&value.contentEquals(root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View v=description(((ViewGroup)root).getChildAt(i),value);if(v!=null)return v;}return null;}
    AgentHub.Event event(String profile,String session,String id,String kind,String status,String cwd)throws Exception{
        return new AgentHub.Event(profile,"Acceptance host",new JSONObject().put("id",id).put("provider",session.equals("beta")?"claude":"codex").put("session_id",session).put("hook_event_name",kind).put("status",status).put("remainingMs",60000).put("created",System.currentTimeMillis()/1000d).put("cwd",cwd).put("tool_name","Bash").put("tool_input",new JSONObject().put("command","printf fixture_"+id)));
    }
    @Test public void searchFiltersArchiveRestoreMultipleRequestsAndRecreation()throws Exception{
        AgentHub hub=AgentHub.get(context);SshProfileStore store=new SshProfileStore(context);SshProfileStore.Draft draft=new SshProfileStore.Draft();draft.host="192.0.2.10";draft.user="fixture";draft.password="fixture-only";draft.name="Acceptance host";draft.knownHosts="UI fixture only; never connected".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String profile=store.save(draft);List<AgentHub.Event> old=new ArrayList<>();Map<String,?> before=hub.preferences.getAll();
        List<AgentHub.Event> fixtures=new ArrayList<>();fixtures.add(event(profile,"alpha","request-one","PermissionRequest","pending","/work/project-alpha"));fixtures.add(event(profile,"alpha","request-two","PermissionRequest","pending","/work/project-alpha"));fixtures.add(event(profile,"beta","done","Stop","info","/work/project-beta"));fixtures.add(event(profile,"gamma","working","UserPromptSubmit","info","/work/project-gamma"));AgentHub.Event offline=event(profile,"delta","offline","PermissionRequest","pending","/work/project-delta");offline.data.put("cached",true);fixtures.add(offline);
        fixtures.get(2).data.put("last_assistant_message","最新回复 中文 👩🏽‍💻");AgentHub.Event prompt=event(profile,"beta","prompt","UserPromptSubmit","info","/work/project-beta");prompt.data.put("created",fixtures.get(2).data.optDouble("created")-1).put("prompt_preview","最后一轮的请求\n保留换行 👨‍👩‍👧‍👦");fixtures.add(prompt);
        AtomicReference<AgentActivity> current=new AtomicReference<>();BlockingQueue<AgentActivity> resumed=new LinkedBlockingQueue<>();Application app=(Application)context.getApplicationContext();
        Application.ActivityLifecycleCallbacks callback=new Application.ActivityLifecycleCallbacks(){public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){if(a instanceof AgentActivity){current.set((AgentActivity)a);resumed.add((AgentActivity)a);}}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}};
        app.registerActivityLifecycleCallbacks(callback);
        long until=System.currentTimeMillis()+15000;boolean[] busy={true};do{inst.runOnMainSync(()->busy[0]=hub.busy);if(busy[0])Thread.sleep(50);}while(busy[0]&&System.currentTimeMillis()<until);assertFalse(busy[0]);
        inst.runOnMainSync(()->{old.addAll(hub.events);hub.busy=true;hub.events.clear();hub.events.addAll(fixtures);hub.preferences.edit().remove("search").remove("filter_provider").remove("filter_host").remove("filter_project").putBoolean("usageCollection",false).putBoolean("host_"+profile,true).commit();});
        try{
            try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -W -n dev.ghostty.android.sample/.MainActivity");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
            inst.startActivitySync(new Intent(context,AgentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));inst.waitForIdleSync();assertNotNull(current.get());
            inst.runOnMainSync(()->{assertEquals(4,hub.sessions().size());((EditText)field(current.get(),"search")).setText("project-beta");assertNotNull(current.get().body.findViewWithTag(fixtures.get(2).id()));assertNull(current.get().body.findViewWithTag(fixtures.get(0).id()));});
            resumed.clear();AgentActivity first=current.get();inst.runOnMainSync(first::recreate);AgentActivity recreated=resumed.poll(10,TimeUnit.SECONDS);assertNotNull(recreated);inst.waitForIdleSync();
            inst.runOnMainSync(()->assertEquals("project-beta",((EditText)field(recreated,"search")).getText().toString()));
            AgentInboxTest ui=new AgentInboxTest();inst.runOnMainSync(()->{
                ((EditText)field(recreated,"search")).setText("");description(recreated.root,"agent_filters").performClick();Dialog filter=(Dialog)field(recreated,"filterDialog");View codex=ui.find(filter.getWindow().getDecorView(),"Codex · 3");assertNotNull(codex);codex.performClick();assertEquals("codex",hub.preferences.getString("filter_provider",null));assertNull(recreated.body.findViewWithTag(fixtures.get(2).id()));
                description(recreated.root,"agent_filters").performClick();filter=(Dialog)field(recreated,"filterDialog");View clear=ui.find(filter.getWindow().getDecorView(),recreated.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?"清除筛选":"Clear filters");assertNotNull(clear);clear.performClick();
                recreated.body.findViewWithTag(fixtures.get(2).id()).performClick();assertNotNull(ui.find(recreated.contentRoot(),"最后一轮的请求\n保留换行 👨‍👩‍👧‍👦"));assertNotNull(ui.find(recreated.contentRoot(),"最新回复 中文 👩🏽‍💻"));View archive=ui.find(recreated.contentRoot(),recreated.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?"归档":"Archive");assertNotNull(archive);archive.performClick();
                assertTrue(hub.sessions().stream().anyMatch(s->s.sessionId.equals("beta")&&s.archived));
                recreated.body.findViewWithTag(fixtures.get(2).id()).performClick();View restore=ui.find(recreated.contentRoot(),recreated.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?"恢复":"Restore");assertNotNull(restore);restore.performClick();assertTrue(hub.sessions().stream().anyMatch(s->s.sessionId.equals("beta")&&!s.archived));
                recreated.body.findViewWithTag(fixtures.get(1).id()).performClick();assertNotNull(recreated.contentRoot().findViewWithTag(fixtures.get(0).id()));assertNotNull(recreated.contentRoot().findViewWithTag(fixtures.get(1).id()));assertNull(ui.find(recreated.contentRoot(),"Archive"));
            });
        }finally{
            inst.runOnMainSync(()->{if(current.get()!=null)current.get().finish();hub.events.clear();hub.events.addAll(old);hub.busy=false;SharedPreferences.Editor edit=hub.preferences.edit();for(String key:new ArrayList<>(hub.preferences.getAll().keySet()))if(key.contains(profile)||Arrays.asList("search","filter_provider","filter_host","filter_project","usageCollection").contains(key)){edit.remove(key);Object value=before.get(key);if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Long)edit.putLong(key,(Long)value);}edit.commit();try{Method persist=AgentHub.class.getDeclaredMethod("persist");persist.setAccessible(true);persist.invoke(hub);}catch(Exception e){throw new AssertionError(e);}});
            app.unregisterActivityLifecycleCallbacks(callback);store.delete(profile);
        }
    }
}
