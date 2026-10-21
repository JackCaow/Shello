package dev.ghostty.android.sample;

import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class AgentInboxModelTest {
    final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    final long now=System.currentTimeMillis();
    final SharedPreferences prefs=context.getSharedPreferences("inbox-model-test",0);
    @Before public void reset(){prefs.edit().clear().commit();}
    @After public void clear(){prefs.edit().clear().commit();}
    AgentHub.Event event(String host,String session,String id,String kind,double time,String status)throws Exception{
        JSONObject data=new JSONObject().put("id",id).put("session_id",session).put("provider","codex").put("hook_event_name",kind).put("created",time).put("status",status).put("remainingMs",status.equals("pending")?60000:0).put("cwd","/tmp/project");
        return new AgentHub.Event(host,"Host "+host,data);
    }
    @Test public void aggregateKeepsEveryApprovalAndScopesHostAndProvider()throws Exception{
        double t=now/1000d;List<AgentHub.Event> events=new ArrayList<>();events.add(event("a","s","1","PermissionRequest",t-3,"pending"));events.add(event("a","s","2","PermissionRequest",t-2,"pending"));events.add(event("a","s","3","UserPromptSubmit",t-1,"info"));events.add(event("b","s","4","Stop",t,"info"));
        List<AgentInbox.Session> sessions=AgentInbox.sessions(events,prefs,now);assertEquals(2,sessions.size());assertEquals(AgentInbox.State.NEEDS_YOU,sessions.get(0).state);assertEquals(2,sessions.get(0).requests.size());
        events.add(events.get(0));assertEquals(2,AgentInbox.sessions(events,prefs,now).get(0).requests.size());
        AgentHub.Event other=event("a","s","5","Stop",t,"info");other.data.put("provider","claude");events.add(other);assertEquals(3,AgentInbox.sessions(events,prefs,now).size());
    }
    @Test public void newerTurnWinsOverLateOldEventAndDeadProcessIsNotWorking()throws Exception{
        double t=now/1000d;AgentHub.Event stop=event("a","s","1","Stop",t-5,"info"),started=event("a","s","2","UserPromptSubmit",t,"info");
        AgentInbox.Session s=AgentInbox.sessions(Arrays.asList(started,stop),prefs,now).get(0);assertEquals(AgentInbox.State.WORKING,s.state);
        stop.data.put("process_alive",false);assertEquals(AgentInbox.State.DONE,AgentInbox.sessions(Arrays.asList(stop),prefs,now).get(0).state);
        started.data.put("process_alive",false);assertEquals(AgentInbox.State.OFFLINE,AgentInbox.sessions(Arrays.asList(started,stop),prefs,now).get(0).state);
        AgentHub.Event deadRequest=event("a","s","3","PermissionRequest",t,"pending");deadRequest.data.put("process_alive",false);assertFalse(deadRequest.pending());assertEquals(AgentInbox.State.OFFLINE,AgentInbox.sessions(Arrays.asList(deadRequest),prefs,now).get(0).state);
    }
    @Test public void cacheNeverApprovesAndUnresolvedRequestDoesNotAutoArchive()throws Exception{
        AgentHub.Event e=event("a","s","1","PermissionRequest",now/1000d-1000,"pending");e.data.put("cached",true);assertFalse(e.pending());
        AgentInbox.Session session=AgentInbox.sessions(Arrays.asList(e),prefs,now).get(0);assertEquals(AgentInbox.State.OFFLINE,session.state);assertFalse(session.archived);assertTrue(session.unresolved);
        AgentHub.Event expired=event("a","s","2","PermissionRequest",now/1000d,"pending");expired.data.put("remainingMs",0);expired=new AgentHub.Event("a","Host",expired.data);assertFalse(expired.pending());
    }
    @Test public void archiveRestoreAndNewActivity()throws Exception{
        AgentHub.Event e=event("a","s","1","Stop",now/1000d-2,"info");AgentInbox.Session session=AgentInbox.sessions(Arrays.asList(e),prefs,now).get(0);
        prefs.edit().putLong("archive_"+session.key,now).commit();assertEquals(AgentInbox.State.ARCHIVED,AgentInbox.sessions(Arrays.asList(e),prefs,now).get(0).state);
        AgentHub.Event next=event("a","s","2","UserPromptSubmit",now/1000d+1,"info");assertEquals(AgentInbox.State.WORKING,AgentInbox.sessions(Arrays.asList(e,next),prefs,now+1000).get(0).state);
        e.data.put("created",now/1000d-10000);prefs.edit().remove("archive_"+session.key).putLong("restore_"+session.key,now).commit();assertEquals(AgentInbox.State.DONE,AgentInbox.sessions(Arrays.asList(e),prefs,now).get(0).state);
    }
    @Test public void queryAndFiltersCombineWithoutLosingSessionIdentity()throws Exception{
        AgentHub.Event e=event("a","s","1","Stop",now/1000d,"info");e.data.put("model","test-model");AgentInbox.Session s=AgentInbox.sessions(Arrays.asList(e),prefs,now).get(0);
        assertTrue(AgentInbox.matches(s,"TEST-MODEL","codex","a","/tmp/project"));assertFalse(AgentInbox.matches(s,"project","claude",null,null));assertFalse(AgentInbox.matches(s,"project",null,"b",null));
    }
    @Test public void privateCacheSurvivesNewStoreAndCorruptionIsBounded()throws Exception{
        File root=new File(context.getCacheDir(),"inbox-cache-test-"+UUID.randomUUID());assertTrue(root.mkdirs());
        File file=new File(root,"agent-inbox-v2.json");
        try{
            AgentCache store=new AgentCache(root);store.write(new JSONObject().put("events",new JSONArray().put(new JSONObject().put("id","one"))));
            assertEquals("one",new AgentCache(root).read().getJSONArray("events").getJSONObject(0).getString("id"));
            try(FileOutputStream out=new FileOutputStream(file)){out.write("{bad".getBytes(StandardCharsets.UTF_8));}assertEquals(0,new AgentCache(root).read().length());
            store.write(new JSONObject().put("ok",true));assertTrue(store.read().getBoolean("ok"));
        }finally{for(File f:root.listFiles())f.delete();root.delete();}
    }
}
