package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Runs on tablet, phone landscape and narrow multi-window sizes without external accounts. */
public class AdaptiveLayoutTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context context=inst.getTargetContext();
    private volatile Activity current;
    private Application.ActivityLifecycleCallbacks callbacks;
    @Before public void observe(){
        callbacks=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}
            public void onActivityResumed(Activity a){current=a;}public void onActivityPaused(Activity a){}
            public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
        };((Application)context.getApplicationContext()).registerActivityLifecycleCallbacks(callbacks);
    }
    @After public void close(){if(current!=null)inst.runOnMainSync(()->current.finish());inst.waitForIdleSync();((Application)context.getApplicationContext()).unregisterActivityLifecycleCallbacks(callbacks);}
    private void open(Class<? extends Activity> type){
        inst.startActivitySync(new Intent(context,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        await(()->type.isInstance(current)&&!current.isDestroyed()&&current.hasWindowFocus());
        int desired="portrait".equals(InstrumentationRegistry.getArguments().getString("layoutOrientation"))?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        inst.runOnMainSync(()->current.setRequestedOrientation(desired));
        await(()->type.isInstance(current)&&!current.isDestroyed()&&current.hasWindowFocus()&&current.getResources().getConfiguration().orientation==(desired==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT));
        SystemClock.sleep(300);inst.waitForIdleSync();
    }
    private void await(BooleanSupplier condition){long until=SystemClock.uptimeMillis()+12000;boolean[] ready={false};
        do{inst.runOnMainSync(()->ready[0]=condition.getAsBoolean());if(ready[0])return;SystemClock.sleep(80);}while(SystemClock.uptimeMillis()<until);fail("Window did not settle");}
    private Object field(Object target,String name){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new AssertionError(e);}}
    private void call(Object target,String name){try{Method m=target.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(target);}catch(Exception e){throw new AssertionError(e);}}
    private View find(View root,String description){if(description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View v=find(group.getChildAt(i),description);if(v!=null)return v;}}return null;}
    private View decor(){return current.getWindow().getDecorView();}
    private int dp(int n){return SampleUi.dp(current,n);}
    private void inside(View view){Rect visible=new Rect();assertTrue("Not visible: "+view.getContentDescription(),view.getGlobalVisibleRect(visible));
        assertTrue("Clipped vertically: "+view.getContentDescription(),visible.height()>=view.getHeight()-1);
        assertTrue("Clipped horizontally: "+view.getContentDescription(),visible.width()>=view.getWidth()-1);}
    private void capture(String name)throws Exception{if(!"true".equals(InstrumentationRegistry.getArguments().getString("layoutScreenshots")))return;
        SystemClock.sleep(350);Bitmap image=inst.getUiAutomation().takeScreenshot();assertNotNull(image);File dir=new File(context.getCacheDir(),"adaptive-review");assertTrue(dir.isDirectory()||dir.mkdirs());
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}}

    @Test public void homeNavigationStaysFixedWhileContentScrolls()throws Exception{
        open(MainActivity.class);await(()->field(current,"sshService")!=null);
        int[] before=new int[2];ScrollView[] scroller=new ScrollView[1];View[] settings=new View[1];
        inst.runOnMainSync(()->{
            call(current,"minimize");scroller[0]=(ScrollView)field(current,"home");
            settings[0]=find(decor(),AppLanguage.tr(R.string.settings));assertNotNull(settings[0]);settings[0].getLocationOnScreen(before);
            // Ensure real overflow even on an empty tablet; this fixture never touches saved profiles.
            ViewGroup content=(ViewGroup)((ViewGroup)scroller[0].getChildAt(0)).getChildAt(0);
            content.addView(new View(current),new ViewGroup.LayoutParams(1,dp(1400)));
        });inst.waitForIdleSync();
        inst.runOnMainSync(()->scroller[0].scrollTo(0,dp(400)));inst.waitForIdleSync();
        inst.runOnMainSync(()->{
            assertTrue("Content did not scroll",scroller[0].getScrollY()>0);
            int[] after=new int[2];settings[0].getLocationOnScreen(after);assertArrayEquals("Navigation moved with content",before,after);
            inside(settings[0]);inside(find(decor(),AppLanguage.tr(R.string.agent_inbox)));
            call(current,"resumeSession");assertFalse("Home navigation leaked over terminal",settings[0].isShown());
            call(current,"minimize");inside(settings[0]);assertTrue(settings[0].performClick());
        });await(()->current instanceof SettingsActivity&&current.hasWindowFocus());
        inst.runOnMainSync(()->{
            ScreenActivity page=(ScreenActivity)current;page.header.getLocationOnScreen(before);
            page.body.addView(new View(current),new LinearLayout.LayoutParams(1,dp(1400)));
        });inst.waitForIdleSync();
        inst.runOnMainSync(()->((ScreenActivity)current).scroll.scrollTo(0,dp(400)));inst.waitForIdleSync();
        inst.runOnMainSync(()->{
            ScreenActivity page=(ScreenActivity)current;assertTrue(page.scroll.getScrollY()>0);
            int[] after=new int[2];page.header.getLocationOnScreen(after);assertArrayEquals(before,after);inside(page.header);
        });
    }

    @Test public void homeAndNewConnectionSheetFitWindow()throws Exception{
        open(MainActivity.class);await(()->field(current,"sshService")!=null);
        inst.runOnMainSync(()->{call(current,"minimize");View home=(View)field(current,"home");assertTrue(home.getWidth()>0);});
        capture("home");
        inst.runOnMainSync(()->{SessionOverview overview=(SessionOverview)field(current,"overview");LinearLayout cards=(LinearLayout)field(overview,"cards");if(cards.getChildCount()>0)cards.getChildAt(0).performLongClick();});inst.waitForIdleSync();
        inst.runOnMainSync(()->{View close=find(decor(),AppLanguage.tr(R.string.close_all));if(close!=null)inside(close);});capture("session-menu");
        inst.runOnMainSync(()->{((SessionOverview)field(current,"overview")).dismiss();call(current,"chooseConnection");});
        await(()->((Dialog)field(current,"newConnectionDialog")).isShowing());
        inst.runOnMainSync(()->{Dialog dialog=(Dialog)field(current,"newConnectionDialog");View root=dialog.getWindow().getDecorView();
            assertTrue(root.getWidth()<=dp(800));assertTrue(root.getHeight()<=AdaptiveLayout.available(current).height());
            View ssh=find(root,AppLanguage.tr(R.string.use_your_own_ssh));assertNotNull(ssh);ssh.requestRectangleOnScreen(new Rect(0,0,ssh.getWidth(),ssh.getHeight()),true);});
        inst.waitForIdleSync();capture("new-connection");
        Activity before=current;inst.runOnMainSync(current::recreate);await(()->current!=before&&field(current,"newConnectionDialog")!=null&&((Dialog)field(current,"newConnectionDialog")).isShowing());
        inst.runOnMainSync(()->((Dialog)field(current,"newConnectionDialog")).dismiss());
    }
    @Test public void connectionInputSurvivesRotationAndActionsStayReachable()throws Exception{
        open(SshDiscoveryActivity.class);
        inst.runOnMainSync(()->{((EditText)field(current,"name")).setText("Tablet test");((EditText)field(current,"host")).setText("127.0.0.1");
            ((EditText)field(current,"user")).setText("tester");((EditText)field(current,"password")).setText("temporary-test-input");
            ((EditText)field(current,"passphrase")).setText("temporary-passphrase");});
        Activity before=current;int orientation=current.getResources().getConfiguration().orientation;
        inst.runOnMainSync(()->current.setRequestedOrientation(orientation==Configuration.ORIENTATION_LANDSCAPE?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        await(()->current!=before&&current.hasWindowFocus());
        inst.runOnMainSync(()->{assertEquals("temporary-test-input",((EditText)field(current,"password")).getText().toString());assertEquals("temporary-passphrase",((EditText)field(current,"passphrase")).getText().toString());assertEquals("Tablet test",((EditText)field(current,"name")).getText().toString());});
        Activity rotated=current;inst.runOnMainSync(()->current.setRequestedOrientation(orientation==Configuration.ORIENTATION_LANDSCAPE?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));await(()->current!=rotated&&current.hasWindowFocus());
        inst.runOnMainSync(()->{EditText host=(EditText)field(current,"host");assertTrue(host.getWidth()<=dp(640));});capture("connection");
        inst.runOnMainSync(()->{EditText password=(EditText)field(current,"password");password.requestFocus();current.getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(password,0);});
        await(()->decor().getRootWindowInsets()!=null&&(Build.VERSION.SDK_INT>=30?decor().getRootWindowInsets().isVisible(WindowInsets.Type.ime()):((View)field(current,"password")).hasFocus()));
        SystemClock.sleep(600);inst.runOnMainSync(()->{View save=(View)field(current,"connectButton");ViewParent parent=save.getParent();while(!(parent instanceof ScrollView))parent=parent.getParent();((ScrollView)parent).scrollTo(0,((ScrollView)parent).getChildAt(0).getHeight());});SystemClock.sleep(400);
        capture("connection-keyboard");
        inst.runOnMainSync(()->inside((View)field(current,"connectButton")));capture("connection-keyboard");
        inst.runOnMainSync(()->{current.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(decor().getWindowToken(),0);decor().requestFocus();});
        SystemClock.sleep(400);inst.runOnMainSync(()->call(current,"showPicker"));inst.waitForIdleSync();
        inst.runOnMainSync(()->{Dialog picker=(Dialog)field(current,"picker");View root=picker.getWindow().getDecorView();assertTrue(root.getWidth()<=dp(600));assertTrue(root.getHeight()<=AdaptiveLayout.available(current).height());inside(find(root,AppLanguage.tr(R.string.scan_for_servers_again)));});capture("discovery");
        inst.runOnMainSync(()->((Dialog)field(current,"picker")).dismiss());
    }
    @Test public void longConfirmationRemainsScrollable()throws Exception{
        open(MainActivity.class);Dialog[] dialog={null};
        inst.runOnMainSync(()->{StringBuilder message=new StringBuilder();for(int i=0;i<30;i++)message.append("Long host verification message 中文\n");dialog[0]=SampleUi.confirm(current,"Host verification",message.toString(),"SHA256: test-fingerprint","Cancel","Continue",false,null,null);});
        inst.waitForIdleSync();inst.runOnMainSync(()->{View confirm=((android.app.AlertDialog)dialog[0]).getButton(DialogInterface.BUTTON_POSITIVE);ViewParent p=confirm.getParent();while(!(p instanceof ScrollView))p=p.getParent();((ScrollView)p).fullScroll(View.FOCUS_DOWN);});SystemClock.sleep(350);
        inst.runOnMainSync(()->inside(((android.app.AlertDialog)dialog[0]).getButton(DialogInterface.BUTTON_POSITIVE)));capture("confirmation");inst.runOnMainSync(()->dialog[0].dismiss());
    }
    @Test public void pairingAndInboxAreBounded()throws Exception{
        open(PairingActivity.class);inst.runOnMainSync(()->{ScreenActivity page=(ScreenActivity)current;assertTrue(page.body.getWidth()<=dp(1040));View scanner=(View)field(current,"scanner");assertTrue(scanner.getWidth()<=dp(640));});capture("pairing");
        inst.runOnMainSync(()->current.finish());open(AgentActivity.class);inst.runOnMainSync(()->assertTrue(((ScreenActivity)current).body.getWidth()<=dp(640)));capture("inbox");
    }
    @Test public void inboxAnswerSurvivesRecreation()throws Exception{
        AgentHub hub=AgentHub.get(context);await(()->!hub.busy);
        java.util.List<AgentHub.Event> old=new java.util.ArrayList<>(hub.events);
        org.json.JSONObject data=new org.json.JSONObject("{\"id\":\"layout-test\",\"status\":\"pending\",\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"AskUserQuestion\",\"tool_input\":{\"questions\":[{\"question\":\"Choose a language\",\"options\":[{\"label\":\"中文\"}]}]}}");
        AgentHub.Event event=new AgentHub.Event("layout-fixture","Tablet host",data);
        inst.runOnMainSync(()->{hub.busy=true;hub.events.clear();hub.events.add(event);});
        try{
            open(AgentActivity.class);inst.runOnMainSync(()->{try{Method detail=AgentActivity.class.getDeclaredMethod("detail",AgentHub.Event.class);detail.setAccessible(true);detail.invoke(current,event);}catch(Exception e){throw new AssertionError(e);}
                EditText answer=(EditText)find(decor(),"Choose a language");assertNotNull(answer);answer.setText("中文 👩🏽‍💻");});
            Activity before=current;inst.runOnMainSync(current::recreate);await(()->current!=before&&current.hasWindowFocus());
            inst.runOnMainSync(()->{assertEquals(event.id(),field(current,"selected"));assertEquals("中文 👩🏽‍💻",((EditText)find(decor(),"Choose a language")).getText().toString());});capture("inbox-answer");
        }finally{inst.runOnMainSync(()->{current.finish();hub.events.clear();hub.events.addAll(old);hub.busy=false;hub.preferences.edit().remove("read_"+event.id()).commit();});}
    }
    @Test public void terminalUsesWidthAndKeepsDraftAcrossRecreation()throws Exception{
        open(MainActivity.class);await(()->field(current,"sshService")!=null);
        inst.runOnMainSync(()->{call(current,"resumeSession");});
        await(()->((View)field(current,"sheet")).isShown());
        inst.runOnMainSync(()->{View viewport=(View)field(current,"viewport"),toolbar=(View)field(current,"toolScroll");assertTrue(viewport.getWidth()>=decor().getWidth()-dp(100));assertTrue(toolbar.getWidth()<=dp(600));inside(toolbar);});capture("terminal");
        inst.runOnMainSync(()->call(current,"showKeyboard"));SystemClock.sleep(800);inst.runOnMainSync(()->call(current,"togglePad"));inst.waitForIdleSync();
        inst.runOnMainSync(()->inside((View)field(current,"pad")));capture("terminal-pad");
        inst.runOnMainSync(()->{View chat=((InputToolbar)field(current,"toolbarContainer")).pinnedButtons.findViewWithTag("chat");assertNotNull(chat);chat.performClick();((EditText)field(current,"draft")).setText("中文 draft 👩🏽‍💻");});
        await(()->((EditText)field(current,"draft")).hasFocus());SystemClock.sleep(600);
        inst.runOnMainSync(()->{inside((View)field(current,"sendButton"));assertTrue(((View)field(current,"viewport")).getHeight()>=dp(48));});capture("terminal-keyboard");
        Activity before=current;inst.runOnMainSync(current::recreate);await(()->current!=before&&current.hasWindowFocus()&&field(current,"sshService")!=null);
        inst.runOnMainSync(()->assertEquals("中文 draft 👩🏽‍💻",((EditText)field(current,"draft")).getText().toString()));
    }
}
