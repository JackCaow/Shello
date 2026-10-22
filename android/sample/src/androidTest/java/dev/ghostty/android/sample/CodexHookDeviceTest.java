package dev.ghostty.android.sample;

import android.app.Instrumentation;
import android.content.*;
import android.os.ParcelFileDescriptor;
import android.graphics.Bitmap;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Opt-in real SSH protocol check; never installs into the user's agent configuration. */
public class CodexHookDeviceTest {
    final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    String run(String profile,String command,String input)throws Exception{
        try(SshCommand ssh=new SshCommand()){return ssh.run(context,profile,command,input==null?null:input.getBytes(StandardCharsets.UTF_8));}
    }
    @Test public void denyRealCodexProbeFromPhone()throws Exception{respondToRealProbe("deny");}
    @Test public void allowRealCodexProbeFromPhone()throws Exception{respondToRealProbe("allow");}
    private void respondToRealProbe(String behavior)throws Exception{
        String session=InstrumentationRegistry.getArguments().getString("hookLiveSession");assumeNotNull(session);
        String host=InstrumentationRegistry.getArguments().getString("hookHost");
        String port=InstrumentationRegistry.getArguments().getString("hookForwardPort");assumeNotNull(host,port);
        SshProfileStore store=new SshProfileStore(context);String original=null;
        for(SshProfileStore.Entry e:store.list())if(host.equals(e.host))original=e.id;
        assertNotNull(original);SshProfileStore.Draft draft=store.read(store.directory(original));draft.host="127.0.0.1";draft.port=Integer.parseInt(port);
        String key=new String(draft.knownHosts,StandardCharsets.UTF_8).trim();draft.knownHosts=("[127.0.0.1]:"+draft.port+" "+key.substring(key.indexOf(' ')+1)+"\n").getBytes(StandardCharsets.UTF_8);
        String profile=store.save(draft);
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();AgentHub hub=AgentHub.get(context);
        AtomicReference<AgentHub.Event> pending=new AtomicReference<>();
        AgentActivity activity=null;
        try{
            long until=System.currentTimeMillis()+15000;boolean[] busy={true};
            do{inst.runOnMainSync(()->busy[0]=hub.busy);if(busy[0])Thread.sleep(50);}while(busy[0]&&System.currentTimeMillis()<until);
            assertFalse("Previous refresh did not finish",busy[0]);
            inst.runOnMainSync(()->{hub.preferences.edit().putBoolean("host_"+profile,true).commit();});
            try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -W -n dev.ghostty.android.sample/.MainActivity");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
            activity=(AgentActivity)inst.startActivitySync(new Intent(context,AgentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            until=System.currentTimeMillis()+20000;
            do{
                inst.runOnMainSync(()->{for(AgentHub.Event e:hub.snapshot())if(profile.equals(e.profile)&&session.equals(e.data.optString("session_id"))&&e.pending())pending.set(e);});
                if(pending.get()==null)Thread.sleep(100);
            }while(pending.get()==null&&System.currentTimeMillis()<until);
            AgentHub.Event event=pending.get();assertNotNull("Real Codex request must reach the phone inbox",event);
            assertEquals("codex",event.data.getString("provider"));
            assertEquals("printf SHELLO_APPROVAL_PROBE",event.data.getJSONObject("tool_input").getString("command"));
            final AgentActivity page=activity;AgentInboxTest ui=new AgentInboxTest();
            inst.runOnMainSync(()->{
                View row=page.body.findViewWithTag(event.id());assertNotNull("Approval row missing",row);row.performClick();
                View request=page.contentRoot().findViewWithTag(event.id());assertNotNull("Pending request missing",request);request.performClick();
                assertNotNull(ui.find(page.body,"printf SHELLO_APPROVAL_PROBE"));
            });
            inst.waitForIdleSync();Bitmap image=inst.getUiAutomation().takeScreenshot();
            if(image!=null)try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),"codex-real-approval-"+behavior+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
            inst.runOnMainSync(()->{View label=ui.find(page.body,AppLanguage.tr("allow".equals(behavior)?R.string.allow_once:R.string.deny));assertNotNull(label);((View)label.getParent().getParent()).performClick();});
            boolean answered=false;until=System.currentTimeMillis()+15000;
            do{
                JSONArray events=new JSONObject(run(profile,AgentHub.COMMAND+"poll",null)).getJSONArray("events");
                for(int i=0;i<events.length();i++){JSONObject e=events.getJSONObject(i);if(event.data.getString("id").equals(e.optString("id")))answered="answered".equals(e.optString("status"))&&behavior.equals(e.optString("answer"));}
                if(!answered)Thread.sleep(100);
            }while(!answered&&System.currentTimeMillis()<until);
            assertTrue("Phone decision must reach the real Codex hook",answered);
            AtomicReference<AgentHub.Event> completed=new AtomicReference<>();until=System.currentTimeMillis()+20000;
            String marker="allow".equals(behavior)?"SHELLO_APPROVAL_ALLOWED":"SHELLO_APPROVAL_DENIED";
            do{
                inst.runOnMainSync(()->{
                    hub.refresh();for(AgentHub.Event e:hub.snapshot())if(profile.equals(e.profile)&&session.equals(e.data.optString("session_id"))
                            &&event.data.optString("turn_id").equals(e.data.optString("turn_id"))&&"Stop".equals(e.data.optString("hook_event_name"))
                            &&e.data.optString("last_assistant_message").contains(marker))completed.set(e);
                });
                if(completed.get()==null)Thread.sleep(100);
            }while(completed.get()==null&&System.currentTimeMillis()<until);
            assertNotNull("Real Codex completion must sync back to the phone",completed.get());
        }finally{
            final AgentActivity page=activity;
            inst.runOnMainSync(()->{if(page!=null)page.finish();hub.preferences.edit().remove("host_"+profile).commit();if(pending.get()!=null)hub.preferences.edit().remove("read_"+pending.get().id()).commit();hub.events.removeIf(e->e.profile.equals(profile));});
            store.delete(profile);
        }
    }
    @Test public void codexLifecycleApprovalAndCompletionOverPinnedSsh()throws Exception{
        String host=InstrumentationRegistry.getArguments().getString("hookHost");assumeNotNull(host);
        String found=null;for(SshProfileStore.Entry e:new SshProfileStore(context).list())if(host.equals(e.host))found=e.id;
        assertNotNull("Expected saved pinned profile",found);
        // Optional USB forwarding exercises real SSH while the phone's VPN is unavailable.
        // Reuse the pinned public host key in a disposable profile, never the saved entry.
        SshProfileStore store=new SshProfileStore(context);String temporary=null;
        String port=InstrumentationRegistry.getArguments().getString("hookForwardPort");
        if(port!=null){
            int forwarded=Integer.parseInt(port);assertTrue(forwarded>1024&&forwarded<65536);
            SshProfileStore.Draft draft=store.read(store.directory(found));draft.host="127.0.0.1";draft.port=forwarded;
            String key=new String(draft.knownHosts,StandardCharsets.UTF_8).trim();
            draft.knownHosts=("[127.0.0.1]:"+forwarded+" "+key.substring(key.indexOf(' ')+1)+"\n").getBytes(StandardCharsets.UTF_8);
            temporary=store.save(draft);found=temporary;
        }
        final String profile=found;
        String root;
        try{root=run(profile,"mktemp -d /tmp/shello-codex-test.XXXXXXXX",null).trim();}
        catch(Exception error){if(temporary!=null)store.delete(temporary);throw error;}
        assertTrue(root.matches("/tmp/shello-codex-test\\.[A-Za-z0-9]+"));
        String q=SshCommand.quote(root);
        String command="GHOSTTY_HOOK_STATE="+SshCommand.quote(root+"/events")+" GHOSTTY_CLAUDE_SETTINGS="+SshCommand.quote(root+"/claude.json")+" GHOSTTY_CODEX_SETTINGS="+SshCommand.quote(root+"/hooks.json")+" python3 "+SshCommand.quote(root+"/helper.py")+" ";
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            try(InputStream in=context.getAssets().open("ghostty_hook.py")){
                run(profile,"umask 077; cat > "+SshCommand.quote(root+"/helper.py"),new String(PairingClient.read(in,262144),StandardCharsets.UTF_8));
            }
            assertTrue(new JSONObject(run(profile,command+"install",null)).getBoolean("ok"));
            for(String kind:new String[]{"SessionStart","UserPromptSubmit","Stop","Interrupt","SessionEnd"}){
                JSONObject value=new JSONObject().put("hook_event_name",kind).put("session_id","fixture").put("turn_id","turn-1").put("cwd",root).put("last_assistant_message","完成 中文 👩🏽‍💻");
                assertEquals("{}",run(profile,command+"codex-hook",value.toString()).trim());
            }
            JSONArray events=new JSONObject(run(profile,command+"poll",null)).getJSONArray("events");assertEquals(5,events.length());
            for(int i=0;i<events.length();i++)assertEquals("codex",events.getJSONObject(i).getString("provider"));
            for(String behavior:new String[]{"allow","deny"}){
                JSONObject value=new JSONObject().put("hook_event_name","PermissionRequest").put("tool_name","Bash").put("tool_input",new JSONObject().put("command","printf SHELLO_CODEX_OK"));
                Future<String> response=worker.submit(()->run(profile,command+"codex-hook",value.toString()));
                JSONObject pending=null;long until=System.currentTimeMillis()+15000;
                while(pending==null&&System.currentTimeMillis()<until){
                    JSONArray rows=new JSONObject(run(profile,command+"poll",null)).getJSONArray("events");
                    for(int i=0;i<rows.length();i++)if("pending".equals(rows.getJSONObject(i).optString("status")))pending=rows.getJSONObject(i);
                    if(pending==null)Thread.sleep(100);
                }
                assertNotNull("No approval received",pending);assertEquals("codex",pending.getString("provider"));
                String reply=new JSONObject().put("id",pending.getString("id")).put("behavior",behavior).toString();
                assertTrue(new JSONObject(run(profile,command+"respond",reply)).getBoolean("ok"));
                JSONObject output=new JSONObject(response.get(10,TimeUnit.SECONDS)).getJSONObject("hookSpecificOutput");
                assertEquals(behavior,output.getJSONObject("decision").getString("behavior"));assertFalse(output.has("updatedInput"));
                try{run(profile,command+"respond",reply);fail("Duplicate approval accepted");}
                catch(IOException expected){assertTrue(expected.getMessage().contains("Remote command failed"));}
            }
            assertTrue(new JSONObject(run(profile,command+"uninstall",null)).getBoolean("ok"));
            assertEquals(0,new JSONObject(run(profile,"cat "+SshCommand.quote(root+"/hooks.json"),null)).getJSONObject("hooks").length());
        }finally{worker.shutdownNow();try{run(profile,"rm -rf -- "+q,null);}finally{if(temporary!=null)store.delete(temporary);}}
    }
}
