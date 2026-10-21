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

public class AttachmentTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();final Context context=inst.getTargetContext();
    MainActivity activity;
    final Application.ActivityLifecycleCallbacks observer=new Application.ActivityLifecycleCallbacks(){
        public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){if(a instanceof MainActivity)activity=(MainActivity)a;}
        public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
    };
    @Before public void observe()throws Exception{
        ((Application)context.getApplicationContext()).registerActivityLifecycleCallbacks(observer);
        // OEM autostart restrictions otherwise block cold-starting this test-only app.
        try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("content query --uri content://"+AttachmentTestProvider.AUTHORITY+"/normal");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
    }
    @After public void unobserve(){if(activity!=null)inst.runOnMainSync(()->{PopupWindow popup=(PopupWindow)field(activity,"connectionMenu");if(popup!=null)popup.dismiss();});((Application)context.getApplicationContext()).unregisterActivityLifecycleCallbacks(observer);}
    Object field(Object target,String name){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new AssertionError(e);}}
    void call(Object target,String name){try{Method m=target.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(target);}catch(Exception e){throw new AssertionError(e);}}
    void await(BooleanSupplier condition){long until=SystemClock.uptimeMillis()+20000;boolean[] ok={false};do{inst.runOnMainSync(()->ok[0]=condition.getAsBoolean());if(!ok[0])SystemClock.sleep(60);}while(!ok[0]&&SystemClock.uptimeMillis()<until);assertTrue("Destination/state did not settle",ok[0]);}
    void open()throws Exception{try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -W -n dev.ghostty.android.sample/.MainActivity");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}await(()->activity!=null&&activity.hasWindowFocus()&&field(activity,"sshService")!=null);}
    View find(View root,String description){if(description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View v=find(((ViewGroup)root).getChildAt(i),description);if(v!=null)return v;}return null;}
    void draftMode()throws Exception{inst.runOnMainSync(()->{call(activity,"resumeSession");try{Method m=MainActivity.class.getDeclaredMethod("setDraftMode",boolean.class,boolean.class);m.setAccessible(true);m.invoke(activity,true,false);}catch(Exception e){throw new AssertionError(e);}});SystemClock.sleep(250);}
    void assertAnchored(){inst.runOnMainSync(()->{
        PopupWindow popup=(PopupWindow)field(activity,"connectionMenu");assertTrue(popup.isShowing());View button=(View)field(activity,"addTextButton");int[] anchor=new int[2],bubble=new int[2];button.getLocationOnScreen(anchor);popup.getContentView().getLocationOnScreen(bubble);
        int gap=anchor[1]-(bubble[1]+popup.getContentView().getHeight());assertTrue("Bubble must sit immediately above +, actual gap="+gap,gap>=0&&gap<=SampleUi.dp(activity,12));
        Rect visible=new Rect();button.getWindowVisibleDisplayFrame(visible);int expectedX=Math.max(visible.left+SampleUi.dp(activity,12),Math.min(anchor[0],visible.right-popup.getWidth()-SampleUi.dp(activity,12)));
        assertTrue("Bubble must align with + or clamp to the window edge: actual="+bubble[0]+", expected="+expectedX,Math.abs(expectedX-bubble[0])<=SampleUi.dp(activity,2));assertTrue("Bubble must avoid system bars",bubble[1]>=visible.top);assertNotNull(find(popup.getContentView(),AppLanguage.tr(R.string.attach_file)));
    });}
    @Test public void menuStaysAtPlusWithKeyboardOpenAndClosed()throws Exception{
        open();boolean[] original={false};inst.runOnMainSync(()->original[0]=(boolean)field(activity,"draftMode"));
        try{draftMode();inst.runOnMainSync(()->call(activity,"hideKeyboard"));SystemClock.sleep(350);inst.runOnMainSync(()->((View)field(activity,"addTextButton")).performClick());SystemClock.sleep(350);assertAnchored();capture("menu-closed-keyboard");
            inst.runOnMainSync(()->((PopupWindow)field(activity,"connectionMenu")).dismiss());await(()->activity.hasWindowFocus());inst.runOnMainSync(()->call(activity,"showKeyboard"));await(()->(boolean)field(activity,"imeVisible"));SystemClock.sleep(350);
            inst.runOnMainSync(()->((View)field(activity,"addTextButton")).performClick());SystemClock.sleep(350);assertAnchored();capture("menu-open-keyboard");
            inst.runOnMainSync(()->call(activity,"hideKeyboard"));SystemClock.sleep(350);assertAnchored();
        }finally{inst.runOnMainSync(()->{PopupWindow popup=(PopupWindow)field(activity,"connectionMenu");if(popup!=null)popup.dismiss();call(activity,"hideKeyboard");try{Method m=MainActivity.class.getDeclaredMethod("setDraftMode",boolean.class,boolean.class);m.setAccessible(true);m.invoke(activity,original[0],false);}catch(Exception e){throw new AssertionError(e);}});}
    }
    void capture(String name)throws Exception{
        android.graphics.Bitmap image=inst.getUiAutomation().takeScreenshot();assertNotNull(image);File dir=new File(context.getCacheDir(),"attachment-review");assertTrue(dir.isDirectory()||dir.mkdirs());try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
    }
    @Test public void menuStaysAtPlusInLandscape()throws Exception{
        open();int before=activity.getRequestedOrientation();
        try{inst.runOnMainSync(()->activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));await(()->activity.hasWindowFocus()&&!activity.isDestroyed()&&activity.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE);draftMode();inst.runOnMainSync(()->call(activity,"hideKeyboard"));SystemClock.sleep(350);inst.runOnMainSync(()->((View)field(activity,"addTextButton")).performClick());SystemClock.sleep(350);assertAnchored();capture("landscape-menu");}
        finally{inst.runOnMainSync(()->{PopupWindow menu=(PopupWindow)field(activity,"connectionMenu");if(menu!=null)menu.dismiss();activity.setRequestedOrientation(before);});SystemClock.sleep(500);}
    }
    @Test public void cancelledTransferNeverPublishesAPath()throws Exception{
        java.util.concurrent.atomic.AtomicBoolean published=new java.util.concurrent.atomic.AtomicBoolean();AttachmentTransfer transfer=new AttachmentTransfer(context,"not-a-profile",AttachmentTestProvider.uri("normal"),()->published.set(true));transfer.close();transfer.start();await(()->!transfer.busy);assertNull(transfer.path);assertEquals(0,transfer.bytes);assertFalse(published.get());
    }
    @Test public void boundedUnknownLengthStreamsAndFileNames()throws Exception{
        byte[] bytes=new byte[33];try(InputStream in=new AttachmentTransfer.BoundedInput(new ByteArrayInputStream(bytes),32,()->{})){assertEquals(32,in.read(new byte[32]));try{in.read();fail("Oversize input accepted");}catch(AttachmentTransfer.TooLarge expected){}}
        try(InputStream in=new AttachmentTransfer.BoundedInput(new ByteArrayInputStream(new byte[32]),32,()->{})){assertEquals(32,in.read(new byte[32]));assertEquals(-1,in.read());}
        try(InputStream in=new AttachmentTransfer.BoundedInput(new ByteArrayInputStream(bytes),32,()->{throw new IOException("cancelled");})){try{in.read();fail("Cancellation ignored");}catch(IOException expected){assertEquals("cancelled",expected.getMessage());}}
        String name=AttachmentTransfer.safeName("../a\\b\n[?*]中文👨‍👩‍👧‍👦.png");assertFalse(name.contains("/"));assertFalse(name.contains("\\"));assertFalse(name.contains("\n"));assertFalse(name.contains("["));assertTrue(name.endsWith("中文👨‍👩‍👧‍👦.png"));assertEquals("attachment",AttachmentTransfer.safeName(".."));assertTrue(AttachmentTransfer.safeName(String.join("",Collections.nCopies(100,"中"))).getBytes(StandardCharsets.UTF_8).length<=180);
    }
    @Test public void declaredOversizeAndUnreadableDocumentDoNotUpload()throws Exception{
        AttachmentTransfer large=new AttachmentTransfer(context,"not-a-profile",AttachmentTestProvider.uri("oversize"),()->{});large.start();await(()->!large.busy);assertEquals(R.string.attachment_too_large,large.failure);assertNull(large.path);
        AttachmentTransfer missing=new AttachmentTransfer(context,"not-a-profile",AttachmentTestProvider.uri("missing"),()->{});missing.start();await(()->!missing.busy);assertNull(missing.path);
    }
    String profile(){String host=InstrumentationRegistry.getArguments().getString("attachmentHost");assumeNotNull(host);for(SshProfileStore.Entry e:new SshProfileStore(context).list())if(host.equals(e.host))return e.id;throw new AssertionError("Saved SSH host missing");}
    String run(String profile,String command)throws Exception{try(SshCommand ssh=new SshCommand()){return ssh.run(context,profile,command,null);}}
    void cleanup(String profile,String path)throws Exception{if(path==null)return;String parent=path.substring(0,path.lastIndexOf('/'));assertTrue(parent.matches(".*/shello/attachments/[0-9a-f-]{36}"));run(profile,"rm -f -- "+SshCommand.quote(path)+"; rmdir -- "+SshCommand.quote(parent));}
    void verify(String profile,String path)throws Exception{
        assertNotNull(path);assertTrue(path.endsWith("/"+AttachmentTransfer.safeName(AttachmentTestProvider.NAME)));
        StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(AttachmentTestProvider.DATA))hash.append(String.format("%02x",b&255));
        String actual=run(profile,"shasum -a 256 -- "+SshCommand.quote(path));assertTrue("Remote content differs",actual.startsWith(hash.toString()+" "));
        assertEquals("600",run(profile,"stat -f '%Lp' "+SshCommand.quote(path)).trim());
    }
    @Test public void realPinnedSftpTransfersUnicodeDocumentWithoutChangingShell()throws Exception{
        String profile=profile();AttachmentTransfer transfer=new AttachmentTransfer(context,profile,AttachmentTestProvider.uri("normal"),()->{});
        try{transfer.start();await(()->!transfer.busy);assertNotNull("Upload failure resource "+transfer.failure+" "+transfer.diagnostic,transfer.path);assertEquals(AttachmentTestProvider.DATA.length,transfer.bytes);verify(profile,transfer.path);}
        finally{transfer.close();cleanup(profile,transfer.path);}
    }
    @Test public void unknownHostKeyCannotUpload()throws Exception{
        String real=profile();SshProfileStore store=new SshProfileStore(context);SshProfileStore.Draft d=store.read(store.directory(real));d.knownHosts=new String(d.knownHosts,StandardCharsets.UTF_8).replaceAll("(?m)^\\S+","untrusted.example.invalid").getBytes(StandardCharsets.UTF_8);String wrong=store.save(d);
        try{AttachmentTransfer transfer=new AttachmentTransfer(context,wrong,AttachmentTestProvider.uri("normal"),()->{});transfer.start();await(()->!transfer.busy);assertNull("Untrusted upload accepted",transfer.path);assertTrue(transfer.diagnostic,transfer.failure==R.string.server_fingerprint_mismatch_connection_stopped||transfer.failure==R.string.server_fingerprint_mismatch_connection_stopped_verify_the_key_wit);}
        finally{store.delete(wrong);}
    }
    @Test public void pickerResultSurvivesActivityRecreationAndInsertsPathOnce()throws Exception{
        String profile=profile();open();await(()->((SshSessionService)field(activity,"sshService")).connected());draftMode();SshSessionService[] service={null};SshSessionService.Tab[] original={null};String[] before={null},path={null};boolean[] mode={false};
        inst.runOnMainSync(()->{service[0]=(SshSessionService)field(activity,"sshService");original[0]=service[0].selected();assertNotNull(original[0]);assertEquals(profile,original[0].profile());assertTrue(service[0].connected());EditText draft=(EditText)field(activity,"draft");before[0]=draft.getText().toString();mode[0]=(boolean)field(activity,"draftMode");draft.setText("inspect ");draft.setSelection(draft.length());});
        IntentFilter filter=new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("*/*");
        Instrumentation.ActivityMonitor picker=inst.addMonitor(filter,new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(AttachmentTestProvider.uri("normal"))),true);
        try{
            inst.runOnMainSync(()->call(activity,"chooseAttachment"));await(()->original[0].attachment!=null);assertTrue("Expected file picker interception",picker.getHits()>0);MainActivity previous=activity;inst.runOnMainSync(activity::recreate);await(()->activity!=previous&&activity.hasWindowFocus()&&field(activity,"sshService")!=null);
            await(()->{String value=((EditText)field(activity,"draft")).getText().toString();return value.startsWith("inspect '")&&original[0].attachment==null;});
            inst.runOnMainSync(()->{String value=((EditText)field(activity,"draft")).getText().toString();String quoted=value.substring("inspect ".length());assertTrue(quoted.endsWith("'"));path[0]=quoted.substring(1,quoted.length()-1).replace("'\\''","'");assertEquals("inspect "+SshCommand.quote(path[0]),value);assertTrue((boolean)field(activity,"draftMode"));});verify(profile,path[0]);
            inst.runOnMainSync(()->call(activity,"updateSshView"));inst.runOnMainSync(()->assertEquals("inspect "+SshCommand.quote(path[0]),((EditText)field(activity,"draft")).getText().toString()));
        }finally{inst.removeMonitor(picker);inst.runOnMainSync(()->{service[0].cancelAttachment(original[0].id);((EditText)field(activity,"draft")).setText(before[0]);original[0].draft=before[0];original[0].draftMode=mode[0];});cleanup(profile,path[0]);}
    }
}
