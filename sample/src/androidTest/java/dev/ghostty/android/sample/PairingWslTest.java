package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Port/pin contracts and real pairing-screen lifecycle; no saved connection is changed. */
public class PairingWslTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context context=inst.getTargetContext();
    private volatile PairingActivity current;
    private Application.ActivityLifecycleCallbacks callbacks;
    @Before public void observe(){
        callbacks=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}
            public void onActivityResumed(Activity a){if(a instanceof PairingActivity)current=(PairingActivity)a;}
            public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
        };((Application)context.getApplicationContext()).registerActivityLifecycleCallbacks(callbacks);
    }
    @After public void cleanup(){if(current!=null)inst.runOnMainSync(current::finish);inst.waitForIdleSync();((Application)context.getApplicationContext()).unregisterActivityLifecycleCallbacks(callbacks);}
    private void await(BooleanSupplier test){long deadline=SystemClock.uptimeMillis()+12000;boolean[] ok={false};do{inst.runOnMainSync(()->ok[0]=test.getAsBoolean());if(ok[0])return;SystemClock.sleep(80);}while(SystemClock.uptimeMillis()<deadline);fail("Pairing Activity did not settle");}
    private void open(){inst.startActivitySync(new Intent(context,PairingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));await(()->current!=null&&current.hasWindowFocus());}
    private Object field(Object target,String name){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new AssertionError(e);}}
    private void set(Object target,String name,Object value){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(Exception e){throw new AssertionError(e);}}
    private View text(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){View found=text(g.getChildAt(i),label);if(found!=null)return found;}}return null;}
    private PairingClient.Ticket ticket(){return new PairingClient.Ticket("ghostty-pair://v1?host=192.168.15.191&port=8765&pin="+"a".repeat(64)+"&token="+"b".repeat(43)+"&expires="+(System.currentTimeMillis()/1000+300));}
    private JSONObject reply()throws Exception{return new JSONObject().put("version",1).put("name","Windows / Ubuntu (WSL2)").put("user","tester").put("port",2222).put("hostKeys",new JSONArray().put("ssh-ed25519 AAAA"));}
    @Test public void ephemeralCertificateAllowsOnlySmallClockSkew()throws Exception{
        long now=System.currentTimeMillis();PairingClient.checkCertificateTime(now+1000,now+86400000,now);
        PairingClient.checkCertificateTime(now+30000,now+86400000,now);
        for(long[] dates:new long[][]{{now+30001,now+86400000},{now-86400000,now-30001}}){try{PairingClient.checkCertificateTime(dates[0],dates[1],now);fail("Certificate outside bounded skew accepted");}catch(java.security.cert.CertificateException expected){}}
    }
    @Test public void forwardedSshPortUsesWindowsAddressAndOwnPinLabel()throws Exception{
        byte[] key={1,2,3};SshProfileStore.Draft draft=PairingClient.responseDraft(ticket(),reply(),key);key[0]=9;
        assertEquals("192.168.15.191",draft.host);assertEquals(2222,draft.port);assertEquals("tester",draft.user);assertTrue(draft.tmux);assertEquals(1,draft.privateKey[0]);
        assertEquals("[192.168.15.191]:2222 ssh-ed25519 AAAA\n",new String(draft.knownHosts,StandardCharsets.UTF_8));
        assertNull(draft.validate());assertEquals("Windows / Ubuntu (WSL2)",draft.name);
        draft=PairingClient.responseDraft(ticket(),reply().put("port",22),key);assertEquals("192.168.15.191 ssh-ed25519 AAAA\n",new String(draft.knownHosts,StandardCharsets.UTF_8));
    }
    @Test public void rejectsBadResponseVersionPortsAndMissingPins()throws Exception{
        JSONObject[] invalid={reply().put("version",2),reply().put("port",65536),reply().put("port",0),reply().put("hostKeys",new JSONArray()),reply().put("hostKeys",new JSONArray().put("ssh-ed25519 AAAA\nother-host ssh-rsa AAAA"))};
        for(JSONObject response:invalid){try{PairingClient.responseDraft(ticket(),response,new byte[]{1});fail("Invalid pairing response accepted");}catch(java.io.IOException expected){}}
    }
    @Test public void platformNetworkSelectionAndCopiedCommandSurviveRecreation(){
        open();inst.runOnMainSync(()->{View platform=text(current.getWindow().getDecorView(),"Windows · WSL2");assertNotNull(platform);platform.performClick();assertTrue(((RadioButton)platform).isChecked());View tail=text(current.getWindow().getDecorView(),"Tailscale");assertNotNull(tail);tail.performClick();assertTrue(((RadioButton)tail).isChecked());});
        PairingActivity old=current;inst.runOnMainSync(current::recreate);await(()->current!=old&&current.hasWindowFocus());
        inst.runOnMainSync(()->{assertEquals(1,field(current,"platformChoice"));assertEquals(2,field(current,"networkChoice"));View copy=text(current.getWindow().getDecorView(),AppLanguage.tr(R.string.copy_command));assertNotNull(copy);copy.performClick();ClipboardManager clipboard=context.getSystemService(ClipboardManager.class);String command=clipboard.getPrimaryClip().getItemAt(0).getText().toString();assertEquals(PairingActivity.command(1,2),command);assertTrue(command.contains("pair.ps1"));assertTrue(command.endsWith("-Network tailscale"));});
        assertTrue(PairingActivity.command(0,1).endsWith("--network lan"));assertEquals(PairingActivity.COMMAND,PairingActivity.command(0,0));
    }
    @Test public void inFlightPairingStateIsRetainedWithoutSerializingKeys(){
        open();Object[] pending={null};inst.runOnMainSync(()->{pending[0]=field(current,"pending");set(pending[0],"pairing",true);});
        PairingActivity old=current;inst.runOnMainSync(current::recreate);await(()->current!=old&&current.hasWindowFocus());
        inst.runOnMainSync(()->{assertSame(pending[0],field(current,"pending"));assertEquals(true,field(current,"busy"));assertEquals(current,field(pending[0],"owner"));assertEquals(false,field(pending[0],"closed"));assertEquals(View.VISIBLE,((View)field(current,"progress")).getVisibility());});
    }
    @Test public void sshRetryDoesNotDuplicateRowsAndKeepsUnverifiedDraftInMemory()throws Exception{
        open();SshProfileStore.Draft draft=PairingClient.responseDraft(ticket(),reply(),new byte[]{1});Object[] state={null};
        inst.runOnMainSync(()->{state[0]=field(current,"pending");set(state[0],"draft",draft);set(current,"paired",draft);try{Method retry=PairingActivity.class.getDeclaredMethod("showRetry",String.class);retry.setAccessible(true);retry.invoke(current,"Network unavailable");Object first=field(current,"retryAction");retry.invoke(current,"Retry failed");assertSame(first,field(current,"retryAction"));assertSame(draft,field(state[0],"draft"));}catch(Exception e){throw new AssertionError(e);}});
        // A configuration recreation can retry verification; normal finish must close this memory state.
        inst.runOnMainSync(current::finish);await(()->current.isDestroyed());assertEquals(true,field(state[0],"closed"));assertNull(field(state[0],"owner"));
    }
}
