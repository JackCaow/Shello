package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Physical opt-in UI acceptance without relying on OEM-wide UI idle. */
public class SshDiscoveryActivityTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private Activity activity;
    @Test public void realHostsSelectionRecreationAndWifiRecovery() throws Exception {
        String expected=InstrumentationRegistry.getArguments().getString("expectedSshHosts");
        org.junit.Assume.assumeTrue(expected!=null);
        Context context=inst.getTargetContext();
        assertEquals("Leave Wi-Fi enabled before this test",1,Settings.Global.getInt(context.getContentResolver(),"wifi_on",0));
        Application app=(Application)context.getApplicationContext();
        BlockingQueue<Activity> resumed=new LinkedBlockingQueue<>();
        Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){}
            public void onActivityStarted(Activity a){}
            public void onActivityResumed(Activity a){if(a instanceof SshDiscoveryActivity)resumed.add(a);}
            public void onActivityPaused(Activity a){}
            public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a,Bundle b){}
            public void onActivityDestroyed(Activity a){}
        };
        app.registerActivityLifecycleCallbacks(lifecycle);
        try{
            // OEM background-launch policy can reject an internal Activity launched by a cold test process.
            shell("am start -n dev.ghostty.android.sample/.MainActivity");
            progress("Launcher opened");
            inst.runOnMainSync(()->context.startActivity(new Intent(context,SshDiscoveryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            activity=resumed.poll(10,TimeUnit.SECONDS);assertNotNull(activity);
            click(AppLanguage.tr(R.string.discover_ssh_servers_on_your_network));awaitHosts(expected);progress("Both hosts visible");
            for(String address:expected.split(",")){
                inst.runOnMainSync(()->{
                    View card=find(pickerRoot()," "+address+":22",true);assertNotNull(card);card.performClick();
                    assertEquals(address,((EditText)find(root(),AppLanguage.tr(R.string.ssh_host_address),false)).getText().toString());
                    assertEquals("22",((EditText)find(root(),AppLanguage.tr(R.string.ssh_port),false)).getText().toString());
                    assertTrue(((EditText)find(root(),AppLanguage.tr(R.string.connection_name),false)).getText().toString().contains("Mac mini"));
                });
                click(AppLanguage.tr(R.string.discover_ssh_servers_on_your_network));awaitHosts(expected);
            }
            progress("Both selections filled");
            String last=expected.split(",")[expected.split(",").length-1];
            inst.runOnMainSync(()->{Dialog d=picker();d.dismiss();activity.recreate();});
            Activity previous=activity;activity=resumed.poll(10,TimeUnit.SECONDS);assertNotNull(activity);assertNotSame(previous,activity);
            inst.runOnMainSync(()->assertEquals(last,((EditText)find(root(),AppLanguage.tr(R.string.ssh_host_address),false)).getText().toString()));
            click(AppLanguage.tr(R.string.discover_ssh_servers_on_your_network));awaitHosts(expected);
            progress("Recreation retained draft");
            shell("svc wifi disable");
            await(()->textContains(pickerRoot(),AppLanguage.tr(R.string.connect_to_wi_fi_and_try_again_ipv4_lan_required)),15000);
            inst.runOnMainSync(()->assertNull(find(pickerRoot(),":22",true)));
            progress("Wi-Fi off clears hosts");
            shell("svc wifi enable");awaitHosts(expected);progress("Wi-Fi return finds both hosts");
        }finally{
            shell("svc wifi enable");
            inst.runOnMainSync(()->{if(activity!=null)activity.finish();});
            app.unregisterActivityLifecycleCallbacks(lifecycle);
        }
    }
    private void awaitHosts(String expected)throws Exception{
        await(()->{
            View root=pickerRoot();if(root==null)return false;
            for(String ip:expected.split(","))if(find(root," "+ip+":22",true)==null)return false;
            return textContains(root,"扫描完成");
        },45000);
    }
    private void await(BooleanSupplier condition,int timeout)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);AtomicBoolean matched=new AtomicBoolean();
        do{inst.runOnMainSync(()->matched.set(condition.getAsBoolean()));if(matched.get())return;Thread.sleep(100);}while(System.nanoTime()<deadline);
        fail("UI condition timed out");
    }
    private void click(String description){inst.runOnMainSync(()->{View v=find(root(),description,false);assertNotNull(description,v);v.performClick();});}
    private View root(){return activity.getWindow().getDecorView();}
    private Dialog picker(){try{Field f=SshDiscoveryActivity.class.getDeclaredField("picker");f.setAccessible(true);return (Dialog)f.get(activity);}catch(Exception e){throw new AssertionError(e);}}
    private View pickerRoot(){Dialog dialog=picker();return dialog==null?null:dialog.getWindow().getDecorView();}
    private View find(View view,String description,boolean contains){
        if(view==null)return null;
        CharSequence desc=view.getContentDescription();
        if(desc!=null&&(contains?desc.toString().contains(description):desc.toString().equals(description)))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            View match=find(((ViewGroup)view).getChildAt(i),description,contains);if(match!=null)return match;
        }
        return null;
    }
    private boolean textContains(View view,String value){
        if(view==null)return false;
        if(view instanceof TextView&&((TextView)view).getText().toString().contains(value))return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(textContains(((ViewGroup)view).getChildAt(i),value))return true;
        return false;
    }
    private void progress(String text){Bundle b=new Bundle();b.putString("stream","\n"+text+"\n");inst.sendStatus(0,b);}
    private void shell(String command)throws IOException{
        try(ParcelFileDescriptor file=inst.getUiAutomation().executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(file)){
            byte[] bytes=new byte[256];while(in.read(bytes)!=-1){}
        }
    }
}
