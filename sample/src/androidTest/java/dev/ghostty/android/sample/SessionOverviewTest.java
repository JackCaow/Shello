package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.ghostty.android.TerminalSession;
import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real independent SSH sockets, service-owned VTs, menu actions and persisted ordering. */
public class SessionOverviewTest {
    @Test public void orderCloseAndRecreateKeepOtherShellAndSavedProfiles()throws Exception {
        SshConnectionFlowTest ui=new SshConnectionFlowTest();Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        Context context=inst.getTargetContext();File fixture=new File(context.getCacheDir(),"ssh-flow/test.properties");assumeTrue(fixture.isFile());
        Properties p=new Properties();try(InputStream in=new FileInputStream(fixture)){p.load(in);}
        SshProfileStore store=new SshProfileStore(context);String original=store.activeId();
        SharedPreferences tabState=context.getSharedPreferences("terminal_tabs",0),legacy=context.getSharedPreferences("ssh_resume",0);
        Map<String,?> previous=new HashMap<>(tabState.getAll()),previousResume=new HashMap<>(legacy.getAll());
        Map<String,Map<String,?>> previousTabResume=new HashMap<>();
        try{org.json.JSONArray saved=new org.json.JSONArray(tabState.getString("order","[]"));for(int i=0;i<saved.length();i++){String id=saved.getString(i);previousTabResume.put(id,new HashMap<>(context.getSharedPreferences("session_resume_"+id,0).getAll()));}}catch(org.json.JSONException ignored){}
        ArrayList<String> ids=new ArrayList<>();ArrayList<TerminalSession> terminals=new ArrayList<>();
        Application app=(Application)context.getApplicationContext();BlockingQueue<Activity> resumed=new LinkedBlockingQueue<>();
        Application.ActivityLifecycleCallbacks callback=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){if(a instanceof MainActivity)resumed.add(a);}
            public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
        };app.registerActivityLifecycleCallbacks(callback);
        try{
            for(String name:new String[]{"Menu Test A","Menu Test B"}){
                SshProfileStore.Draft d=new SshProfileStore.Draft();d.name=name;d.host=p.getProperty("host");d.port=Integer.parseInt(p.getProperty("passwordPort"));d.user=p.getProperty("user");d.password=p.getProperty("password");
                CountDownLatch trust=new CountDownLatch(1);AtomicReference<byte[]> pin=new AtomicReference<>();
                SshConnectionSetup setup=new SshConnectionSetup();setup.start(d,new SshConnectionSetup.Listener(){
                    public void trustedHostNeeded(String f,byte[] key){pin.set(key);trust.countDown();}public void verified(){trust.countDown();}public void failed(String e){trust.countDown();}
                });assertTrue(trust.await(15,TimeUnit.SECONDS));setup.close();assertNotNull(pin.get());d.knownHosts=pin.get();ids.add(store.save(d));
            }
            try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -n dev.ghostty.android.sample/.MainActivity -f 0x10008000");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
            ui.current=resumed.poll(10,TimeUnit.SECONDS);assertNotNull(ui.current);ui.await(()->ui.service()!=null,10000);
            // Detach existing client tabs for this isolated close-all test, then restore their metadata below.
            inst.runOnMainSync(()->ui.service().closeAll());
            for(String id:ids){
                inst.runOnMainSync(()->assertNull(SshSessionService.requestConnect(context,id)));
                ui.await(()->ui.service().connected()&&ui.service().selected().id.equals(id),15000);
                inst.runOnMainSync(()->terminals.add(ui.service().terminal()));
            }
            inst.runOnMainSync(()->{
                assertTrue(ui.service().find(ids.get(0)).connection.connected);assertTrue(ui.service().find(ids.get(1)).connection.connected);
                assertNotSame(terminals.get(0),terminals.get(1));
                ui.service().select(ids.get(0));ui.service().send("SESSION_A_MARKER\r".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            });
            ui.await(()->terminals.get(0).select(0,terminals.get(0).getColumns()*terminals.get(0).getRows()-1).contains("SESSION_A_MARKER"),5000);
            ui.click("最小化会话");ui.longClick("恢复会话 Menu Test A");
            screenshot(inst,context,"session-context.png");ui.click("重新排序");screenshot(inst,context,"session-order.png");
            drag(inst,ui,"恢复会话 Menu Test A","恢复会话 Menu Test B");
            ui.await(()->ui.service().tabs().get(0).id.equals(ids.get(1)),3000);
            screenshot(inst,context,"session-reordered.png");
            ui.click("向前移动 Menu Test A");ui.click("向后移动 Menu Test A");ui.click("完成会话排序");
            inst.runOnMainSync(()->{assertEquals(ids.get(1),ui.service().tabs().get(0).id);assertSame(terminals.get(0),ui.service().find(ids.get(0)).terminal());});
            Activity first=ui.current;inst.runOnMainSync(first::recreate);ui.current=resumed.poll(10,TimeUnit.SECONDS);assertNotNull(ui.current);ui.await(()->ui.service()!=null,10000);
            inst.runOnMainSync(()->{
                assertEquals(ids.get(1),ui.service().tabs().get(0).id);assertSame(terminals.get(0),ui.service().find(ids.get(0)).terminal());
                assertTrue(ui.service().find(ids.get(0)).connection.connected);assertTrue(ui.service().find(ids.get(1)).connection.connected);
            });
            assertTrue(tabState.getString("order","").indexOf(ids.get(1))<tabState.getString("order","").indexOf(ids.get(0)));
            ui.longClick("恢复会话 Menu Test B");ui.click("关闭");
            inst.runOnMainSync(()->{assertNull(ui.service().find(ids.get(1)));assertSame(terminals.get(0),ui.service().terminal());assertTrue(ui.service().connected());});
            assertTrue(SshProfileStore.exists(store.directory(ids.get(1))));
            ui.longClick("恢复会话 Menu Test A");ui.click("全部关闭");
            inst.runOnMainSync(()->{assertTrue(ui.service().tabs().isEmpty());assertNull(ui.service().terminal());assertFalse(ui.service().active());assertFalse(ui.service().shouldResume());});
            screenshot(inst,context,"session-empty.png");
            assertEquals("[]",tabState.getString("order",null));assertTrue(SshProfileStore.exists(store.directory(ids.get(0))));
            Activity second=ui.current;inst.runOnMainSync(second::recreate);ui.current=resumed.poll(10,TimeUnit.SECONDS);ui.await(()->ui.service()!=null,10000);
            inst.runOnMainSync(()->assertTrue(ui.service().tabs().isEmpty()));
            ui.click("连接 SSH Menu Test A");ui.await(()->ui.service().connected(),15000);
            inst.runOnMainSync(()->{assertEquals(1,ui.service().tabs().size());assertNotSame(terminals.get(0),ui.service().terminal());});
        }finally{
            inst.runOnMainSync(()->{if(ui.current instanceof MainActivity&&ui.service()!=null){for(String id:ids)ui.service().close(id);}if(ui.current!=null)ui.current.finish();});
            app.unregisterActivityLifecycleCallbacks(callback);for(String id:ids)store.delete(id);
            if(original!=null)store.activate(original);else context.getSharedPreferences("ssh_profiles",0).edit().remove("active").commit();
            restore(tabState,previous);restore(legacy,previousResume);for(Map.Entry<String,Map<String,?>> e:previousTabResume.entrySet())restore(context.getSharedPreferences("session_resume_"+e.getKey(),0),e.getValue());
        }
    }
    private static void drag(Instrumentation inst,SshConnectionFlowTest ui,String from,String to)throws Exception {
        int[] start=new int[2],end=new int[2];
        inst.runOnMainSync(()->{android.view.View a=ui.find(ui.root(),from),b=ui.find(ui.root(),to);a.getLocationOnScreen(start);b.getLocationOnScreen(end);start[0]+=a.getWidth()/2;start[1]+=a.getWidth()/2;end[0]+=b.getWidth()/2;end[1]+=b.getWidth()/2;});
        long down=SystemClock.uptimeMillis();pointer(inst,down,android.view.MotionEvent.ACTION_DOWN,start[0],start[1]);Thread.sleep(700);
        for(int i=1;i<=12;i++){pointer(inst,down,android.view.MotionEvent.ACTION_MOVE,start[0]+(end[0]-start[0])*i/12f,start[1]+(end[1]-start[1])*i/12f);Thread.sleep(25);}
        pointer(inst,down,android.view.MotionEvent.ACTION_UP,end[0],end[1]);
    }
    private static void pointer(Instrumentation inst,long down,int action,float x,float y){android.view.MotionEvent event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);assertTrue(inst.getUiAutomation().injectInputEvent(event,true));event.recycle();}
    private static void screenshot(Instrumentation inst,Context context,String name)throws Exception {
        // Allow the frame after a real menu action to reach SurfaceFlinger; do not wait on VT idle.
        Thread.sleep(300);android.graphics.Bitmap image=inst.getUiAutomation().takeScreenshot();assertNotNull(image);
        try(OutputStream out=new FileOutputStream(new File(context.getCacheDir(),name))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
    }
    private static void restore(SharedPreferences preferences,Map<String,?> values){SharedPreferences.Editor editor=preferences.edit().clear();for(Map.Entry<String,?> e:values.entrySet()){if(e.getValue() instanceof String)editor.putString(e.getKey(),(String)e.getValue());else if(e.getValue() instanceof Boolean)editor.putBoolean(e.getKey(),(Boolean)e.getValue());}editor.commit();}
}
