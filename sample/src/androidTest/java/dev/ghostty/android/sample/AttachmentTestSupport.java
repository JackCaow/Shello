package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Shared opt-in device harness; not a test suite itself. */
abstract class AttachmentTestSupport {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();final Context context=inst.getTargetContext();
    MainActivity activity;String temporaryProfile;ContentProviderClient fixtureProvider;
    final Application.ActivityLifecycleCallbacks observer=new Application.ActivityLifecycleCallbacks(){
        public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){if(a instanceof MainActivity)activity=(MainActivity)a;}
        public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
    };
    @Before public void observe()throws Exception{
        ((Application)context.getApplicationContext()).registerActivityLifecycleCallbacks(observer);
        // OEM autostart restrictions otherwise block cold-starting this test-only app.
        try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("content query --uri content://"+AttachmentTestProvider.AUTHORITY+"/normal");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
        fixtureProvider=context.getContentResolver().acquireContentProviderClient(AttachmentTestProvider.AUTHORITY);assertNotNull("Synthetic fixture provider did not start",fixtureProvider);
    }
    @After public void unobserve()throws Exception{if(fixtureProvider!=null)fixtureProvider.close();if(temporaryProfile!=null)new SshProfileStore(context).delete(temporaryProfile);if(activity!=null)inst.runOnMainSync(()->{PopupWindow popup=(PopupWindow)field(activity,"connectionMenu");if(popup!=null)popup.dismiss();});((Application)context.getApplicationContext()).unregisterActivityLifecycleCallbacks(observer);}
    Object field(Object target,String name){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new AssertionError(e);}}
    void call(Object target,String name){try{Method m=target.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(target);}catch(Exception e){throw new AssertionError(e);}}
    void await(BooleanSupplier condition){long until=SystemClock.uptimeMillis()+20000;boolean[] ok={false};do{inst.runOnMainSync(()->ok[0]=condition.getAsBoolean());if(!ok[0])SystemClock.sleep(60);}while(!ok[0]&&SystemClock.uptimeMillis()<until);assertTrue("Destination/state did not settle",ok[0]);}
    void open()throws Exception{try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -W -n dev.ghostty.android.sample/.MainActivity");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}await(()->activity!=null&&activity.hasWindowFocus()&&field(activity,"sshService")!=null);}
    View find(View root,String description){if(description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View v=find(((ViewGroup)root).getChildAt(i),description);if(v!=null)return v;}return null;}
    void draftMode()throws Exception{inst.runOnMainSync(()->{call(activity,"resumeSession");try{Method m=MainActivity.class.getDeclaredMethod("setDraftMode",boolean.class,boolean.class);m.setAccessible(true);m.invoke(activity,true,false);}catch(Exception e){throw new AssertionError(e);}});SystemClock.sleep(250);}
    void capture(String name)throws Exception{
        android.graphics.Bitmap image=inst.getUiAutomation().takeScreenshot();assertNotNull(image);File dir=new File(context.getCacheDir(),"attachment-review");assertTrue(dir.isDirectory()||dir.mkdirs());try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
    }
    String profile()throws Exception{
        String host=InstrumentationRegistry.getArguments().getString("attachmentHost");assumeNotNull(host);SshProfileStore store=new SshProfileStore(context);
        for(SshProfileStore.Entry e:store.list())if(host.equals(e.host)){
            String via=InstrumentationRegistry.getArguments().getString("attachmentVia");if(via==null)return e.id;
            // A temporary LAN address uses the already pinned keys; saved user profiles stay intact.
            SshProfileStore.Draft draft=store.read(store.directory(e.id));draft.host=via;draft.tmux=false;draft.name="Attachment acceptance";
            String label=draft.port==22?via:"["+via+"]:"+draft.port;
            draft.knownHosts=new String(draft.knownHosts,StandardCharsets.UTF_8).replaceAll("(?m)^\\S+",java.util.regex.Matcher.quoteReplacement(label)).getBytes(StandardCharsets.UTF_8);
            temporaryProfile=store.save(draft);return temporaryProfile;
        }throw new AssertionError("Saved SSH host missing");
    }
    String run(String profile,String command)throws Exception{try(SshCommand ssh=new SshCommand()){return ssh.run(context,profile,command,null);}}
}
