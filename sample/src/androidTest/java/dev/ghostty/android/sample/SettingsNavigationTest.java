package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Exercises real Activity launches/back dispatch, without SSH or changing saved profiles. */
public class SettingsNavigationTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();final Context context=inst.getTargetContext();
    Activity current;final List<Activity> opened=new ArrayList<>();
    final Application.ActivityLifecycleCallbacks observer=new Application.ActivityLifecycleCallbacks(){
        public void onActivityCreated(Activity a,Bundle b){if(a instanceof SettingsPageActivity||a instanceof AgentActivity)opened.add(a);}
        public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){current=a;}
        public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
    };
    @Before public void observe()throws Exception{((Application)context.getApplicationContext()).registerActivityLifecycleCallbacks(observer);try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -W -n dev.ghostty.android.sample/.MainActivity");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}}
    @After public void close(){inst.runOnMainSync(()->{for(int i=opened.size()-1;i>=0;i--)if(!opened.get(i).isDestroyed())opened.get(i).finish();});android.os.SystemClock.sleep(80);((Application)context.getApplicationContext()).unregisterActivityLifecycleCallbacks(observer);}
    void await(BooleanSupplier condition){long end=SystemClock.uptimeMillis()+10000;boolean[] ready={false};do{inst.runOnMainSync(()->ready[0]=condition.getAsBoolean());if(!ready[0])SystemClock.sleep(50);}while(!ready[0]&&SystemClock.uptimeMillis()<end);assertTrue("Expected destination did not resume",ready[0]);android.os.SystemClock.sleep(80);}
    void page(Class<?> type){try{await(()->current!=null&&current.getClass()==type&&current.hasWindowFocus()&&!current.isDestroyed());}catch(AssertionError e){throw new AssertionError("Expected "+type.getSimpleName()+", observed "+(current==null?"none":current.getClass().getSimpleName()),e);}}
    View find(View view,String label){if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    void click(String label){inst.runOnMainSync(()->{View view=find(current.getWindow().getDecorView(),label);assertNotNull(label,view);view.performClick();});}
    void systemBack(){assertTrue(inst.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));}
    @Test public void nestedSettingsUseRealBackStackAndRestoreIndexScroll(){
        inst.runOnMainSync(()->context.startActivity(new Intent(context,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));page(SettingsActivity.class);SettingsActivity index=(SettingsActivity)current;
        int[] y={0};android.os.SystemClock.sleep(80);inst.runOnMainSync(()->{index.scroll.scrollTo(0,index.dp(120));y[0]=index.scroll.getScrollY();});assertTrue(y[0]>0);
        click(AppLanguage.tr(R.string.notifications));page(NotificationsSettingsActivity.class);Activity notifications=current;assertNotSame(index,notifications);assertFalse(index.isFinishing());
        click(AppLanguage.tr(R.string.install_hook));page(HookSettingsActivity.class);assertNotSame(notifications,current);assertFalse(notifications.isFinishing());
        systemBack();page(NotificationsSettingsActivity.class);assertSame(notifications,current);
        click(AppLanguage.tr(R.string.back));page(SettingsActivity.class);assertSame(index,current);SystemClock.sleep(100);inst.runOnMainSync(()->assertEquals("Index scroll must survive the child Activity",y[0],index.scroll.getScrollY()));
        systemBack();page(MainActivity.class);
    }
    @Test public void inboxHookShortcutReturnsToItsActualCaller(){
        inst.runOnMainSync(()->context.startActivity(new Intent(context,AgentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));page(AgentActivity.class);AgentActivity inbox=(AgentActivity)current;
        click(AppLanguage.tr(R.string.settings));page(HookSettingsActivity.class);systemBack();page(AgentActivity.class);assertSame(inbox,current);
    }
    @Test public void legacyPageIntentMigratesOnceToIndependentDestination(){
        inst.runOnMainSync(()->context.startActivity(new Intent(context,AgentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));page(AgentActivity.class);Activity source=current;inst.runOnMainSync(()->source.startActivity(new Intent(source,SettingsActivity.class).putExtra("page","theme")));page(AppearanceSettingsActivity.class);
        systemBack();page(SettingsActivity.class);assertNull(current.getIntent().getStringExtra("page"));systemBack();page(AgentActivity.class);assertSame(source,current);
    }
    @Test public void previousWindowCanStillDrawThumbnailAfterTabCloses()throws Exception{
        SshSessionService[] owner={null};CountDownLatch ready=new CountDownLatch(1);
        ServiceConnection binding=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){owner[0]=((SshSessionService.LocalBinder)b).service();ready.countDown();}public void onServiceDisconnected(ComponentName n){}};
        assertTrue(context.bindService(new Intent(context,SshSessionService.class),binding,Context.BIND_AUTO_CREATE));
        try{assertTrue(ready.await(5,TimeUnit.SECONDS));inst.runOnMainSync(()->{
            android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888),canvas=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888);ImageView previousWindow=new ImageView(context);previousWindow.setImageBitmap(image);previousWindow.layout(0,0,32,32);
            try{java.lang.reflect.Field field=SshSessionService.class.getDeclaredField("tabs");field.setAccessible(true);@SuppressWarnings("unchecked") List<SshSessionService.Tab> tabs=(List<SshSessionService.Tab>)field.get(owner[0]);SshSessionService.Tab tab=new SshSessionService.Tab("settings-preview-fixture",null);tab.preview=image;tabs.add(tab);owner[0].close(tab.id);assertFalse("A previous window still owns this bitmap",image.isRecycled());previousWindow.draw(new android.graphics.Canvas(canvas));}catch(ReflectiveOperationException e){throw new AssertionError(e);}finally{previousWindow.setImageDrawable(null);if(!image.isRecycled())image.recycle();canvas.recycle();}
        });}finally{context.unbindService(binding);}
    }
}
