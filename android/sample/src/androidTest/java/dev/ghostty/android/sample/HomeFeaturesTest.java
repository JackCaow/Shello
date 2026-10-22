package dev.ghostty.android.sample;

import android.content.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import com.jcraft.jsch.*;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real TLS pairing + OpenSSH/tmux + Hook protocol, isolated from user SSH/Claude settings. */
public class HomeFeaturesTest {
    final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    Properties fixture()throws Exception{File f=new File(context.getCacheDir(),"ssh-flow/test.properties");assumeTrue(f.isFile());Properties p=new Properties();try(InputStream in=new FileInputStream(f)){p.load(in);}assumeTrue(p.containsKey("hookRoot"));return p;}
    String profile()throws Exception{Properties p=fixture();SshProfileStore.Draft d=new SshProfileStore.Draft();d.name="Home feature test";d.host=p.getProperty("host");d.port=Integer.parseInt(p.getProperty("port"));d.user=p.getProperty("user");d.auth="key";d.privateKey=Files.readAllBytes(new File(context.getCacheDir(),"ssh-flow/client_key").toPath());d.tmux=true;
        String pub=p.getProperty("hostPublicKey");d.knownHosts=("["+d.host+"]:"+d.port+" "+pub+"\n").getBytes(StandardCharsets.UTF_8);return new SshProfileStore(context).save(d);}
    String run(String profile,String command,String input)throws Exception{try(SshCommand request=new SshCommand()){return request.run(context,profile,command,input==null?null:input.getBytes(StandardCharsets.UTF_8));}}
    @Test public void pickerWaitsForSshAndCancellationPreventsLateSheet()throws Exception{
        String id=profile();SshProfileStore store=new SshProfileStore(context);SshConnectionFlowTest ui=new SshConnectionFlowTest();
        android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        BlockingQueue<android.app.Activity> resumed=new LinkedBlockingQueue<>();android.app.Application app=(android.app.Application)context.getApplicationContext();
        android.app.Application.ActivityLifecycleCallbacks callback=new android.app.Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(android.app.Activity a,Bundle b){}public void onActivityStarted(android.app.Activity a){}public void onActivityResumed(android.app.Activity a){if(a instanceof MainActivity)resumed.add(a);}
            public void onActivityPaused(android.app.Activity a){}public void onActivityStopped(android.app.Activity a){}public void onActivitySaveInstanceState(android.app.Activity a,Bundle b){}public void onActivityDestroyed(android.app.Activity a){}
        };app.registerActivityLifecycleCallbacks(callback);
        try{try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -n dev.ghostty.android.sample/.MainActivity -f 0x10008000");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}ui.current=resumed.poll(10,TimeUnit.SECONDS);assertNotNull(ui.current);}finally{app.unregisterActivityLifecycleCallbacks(callback);}
        AtomicReference<android.app.Dialog> opened=new AtomicReference<>();CountDownLatch ready=new CountDownLatch(1);
        try{
            inst.runOnMainSync(()->{opened.set(TmuxPicker.show(ui.current,id,tab->{},ready::countDown));assertFalse("Sheet must wait for SSH",opened.get().isShowing());});
            assertTrue(ready.await(15,TimeUnit.SECONDS));ui.await(()->opened.get().isShowing()&&opened.get().getWindow().getDecorView().getWidth()>0,3000);
            inst.runOnMainSync(()->{
                android.view.View root=opened.get().getWindow().getDecorView();android.graphics.Rect bounds=AdaptiveLayout.available(ui.current);
                assertTrue("Tmux sheet exceeds window height",root.getHeight()<=bounds.height());
                assertTrue("Tmux sheet stretches across tablet",root.getWidth()<=SampleUi.dp(ui.current,640));
                android.view.View create=ui.find(root,AppLanguage.tr(R.string.new_tmux_session_76));assertNotNull(create);
                android.graphics.Rect visible=new android.graphics.Rect();assertTrue(create.getGlobalVisibleRect(visible));assertEquals(create.getHeight(),visible.height());
                opened.get().dismiss();
            });
            CountDownLatch cancelled=new CountDownLatch(1);
            inst.runOnMainSync(()->{opened.set(TmuxPicker.show(ui.current,id,tab->{},cancelled::countDown));opened.get().dismiss();});
            assertTrue(cancelled.await(1,TimeUnit.SECONDS));Thread.sleep(800);inst.runOnMainSync(()->assertFalse(opened.get().isShowing()));
            SshProfileStore.Draft bad=store.read(store.directory(id));bad.port=1;store.update(id,bad);CountDownLatch failed=new CountDownLatch(1);
            inst.runOnMainSync(()->opened.set(TmuxPicker.show(ui.current,id,tab->{},failed::countDown)));
            assertTrue(failed.await(15,TimeUnit.SECONDS));inst.runOnMainSync(()->assertFalse("Failed SSH must stay on homepage",opened.get().isShowing()));
        }finally{inst.runOnMainSync(()->{if(opened.get()!=null)opened.get().dismiss();ui.current.finish();});store.delete(id);}
    }
    @Test public void qrPinTokenReplayAndPairedSsh()throws Exception{
        fixture();String qr=new String(Files.readAllBytes(new File(context.getCacheDir(),"ssh-flow/pair.txt").toPath()),StandardCharsets.UTF_8);
        PairingClient.Ticket original=new PairingClient.Ticket(qr);String bad=qr.replace(original.pin,"0000000000000000000000000000000000000000000000000000000000000000");
        try{PairingClient.pair(new PairingClient.Ticket(bad));fail("Wrong TLS pin accepted");}catch(javax.net.ssl.SSLException expected){}
        SshProfileStore.Draft d=PairingClient.pair(original);assertEquals("key",d.auth);assertTrue(d.privateKey.length>1000);assertTrue(d.knownHosts.length>0);
        String id=new SshProfileStore(context).save(d);
        try{assertEquals("PAIR_OK",run(id,"printf PAIR_OK",null));try{PairingClient.pair(original);fail("Pairing token reused for another key");}catch(IOException expected){}}
        finally{new SshProfileStore(context).delete(id);}
    }
    @Test public void existingTmuxTargetSurvivesTransportReconnect()throws Exception{
        String id=profile(),name="ghostty_home_"+UUID.randomUUID().toString().replace("-","");String lookup=SshConnectionSetup.TMUX_LOOKUP;
        try{
            run(id,lookup+"\"$T\" new-session -d -s "+SshCommand.quote(name),null);
            String before=run(id,lookup+"\"$T\" display-message -p -t "+SshCommand.quote("="+name+":")+" '#{pane_pid}'",null).trim();assertTrue(before.matches("[0-9]+"));
            for(int attempt=0;attempt<2;attempt++){
                CountDownLatch connected=new CountDownLatch(1),output=new CountDownLatch(1);ByteArrayOutputStream received=new ByteArrayOutputStream();SshTransport transport=new SshTransport(context,new SshProfileStore(context).directory(id),60,24,new SshTransport.Listener(){public void onState(String s,boolean yes){if(yes)connected.countDown();}public void onOutput(byte[] bytes){received.write(bytes,0,bytes.length);if(new String(received.toByteArray(),StandardCharsets.UTF_8).contains("HOME_TMUX_中文_✓_👩🏽‍💻"))output.countDown();}}).target("="+name);
                try{transport.connect();assertTrue(connected.await(15,TimeUnit.SECONDS));transport.send("printf 'HOME_TMUX_中文_✓_👩🏽‍💻\\n'\r".getBytes(StandardCharsets.UTF_8));assertTrue(output.await(10,TimeUnit.SECONDS));}finally{transport.close();}
            }
            assertEquals(before,run(id,lookup+"\"$T\" display-message -p -t "+SshCommand.quote("="+name+":")+" '#{pane_pid}'",null).trim());
            String listing=run(id,lookup+"\"$T\" list-sessions -F '#{session_name}'",null);assertTrue(listing.contains(name));
            String tab=SessionTarget.id(id,"="+name);assertEquals(id,SessionTarget.profile(tab));assertEquals("="+name,SessionTarget.target(tab));
        }finally{try{run(id,lookup+"\"$T\" kill-session -t "+SshCommand.quote("="+name),null);}finally{new SshProfileStore(context).delete(id);}}
    }
    @Test public void hookApprovalQuestionCompletionOverSsh()throws Exception{
        String id=profile(),root=fixture().getProperty("hookRoot");String command="GHOSTTY_HOOK_STATE="+SshCommand.quote(root+"/events")+" GHOSTTY_CLAUDE_SETTINGS="+SshCommand.quote(root+"/settings.json")+" GHOSTTY_CODEX_SETTINGS="+SshCommand.quote(root+"/codex-hooks.json")+" python3 "+SshCommand.quote(root+"/ghostty_hook.py")+" ";ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            assertTrue(new JSONObject(run(id,command+"install",null)).getBoolean("ok"));
            Future<String> request=worker.submit(()->run(id,command+"hook","{\"hook_event_name\":\"PermissionRequest\",\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"echo hello\"}}"));
            JSONObject event=pending(id,command);assertEquals("Bash",event.getString("tool_name"));
            assertTrue(new JSONObject(run(id,command+"respond",new JSONObject().put("id",event.getString("id")).put("behavior","deny").toString())).getBoolean("ok"));
            assertEquals("deny",new JSONObject(request.get(10,TimeUnit.SECONDS)).getJSONObject("hookSpecificOutput").getJSONObject("decision").getString("behavior"));
            String question="选择语言？";JSONObject input=new JSONObject().put("questions",new JSONArray().put(new JSONObject().put("question",question).put("options",new JSONArray().put(new JSONObject().put("label","中文")))));
            Future<String> asked=worker.submit(()->run(id,command+"hook",new JSONObject().put("hook_event_name","PreToolUse").put("tool_name","AskUserQuestion").put("tool_input",input).toString()));
            JSONObject q=pending(id,command);run(id,command+"respond",new JSONObject().put("id",q.getString("id")).put("behavior","allow").put("answers",new JSONObject().put(question,"中文 👩🏽‍💻")).toString());
            assertEquals("中文 👩🏽‍💻",new JSONObject(asked.get(10,TimeUnit.SECONDS)).getJSONObject("hookSpecificOutput").getJSONObject("updatedInput").getJSONObject("answers").getString(question));
            run(id,command+"hook","{\"hook_event_name\":\"Stop\",\"last_assistant_message\":\"完成\"}");assertTrue(run(id,command+"poll",null).contains("完成"));
        }finally{worker.shutdownNow();try{run(id,command+"uninstall",null);}finally{new SshProfileStore(context).delete(id);}}
    }
    JSONObject pending(String id,String command)throws Exception{long until=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<until){JSONArray events=new JSONObject(run(id,command+"poll",null)).getJSONArray("events");for(int i=0;i<events.length();i++)if(events.getJSONObject(i).optString("status").equals("pending"))return events.getJSONObject(i);Thread.sleep(100);}throw new AssertionError("No pending event");}
    @Test public void rejectMalformedPairingAndSessionTargets(){
        for(String qr:new String[]{"https://example.com","ghostty-pair://v1?host=8.8.8.8","ghostty-pair://v1?host=127.0.0.1"})try{new PairingClient.Ticket(qr);fail("Accepted bad QR");}catch(IllegalArgumentException expected){}
        for(String target:new String[]{"", "=bad\nname", "plain_bad", "name; echo bad"})try{SessionTarget.validate(target);fail("Accepted bad target");}catch(IllegalArgumentException expected){}
        assertTrue(SessionTarget.command("=a'b; echo safe").contains("'\\''"));
    }
}
