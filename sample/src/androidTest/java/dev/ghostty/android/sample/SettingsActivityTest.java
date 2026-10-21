package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Settings interactions, recreation and wrapping; no SSH or remote configuration changes. */
public class SettingsActivityTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context context=inst.getTargetContext();
    private SettingsPageActivity current;
    private SettingsPageActivity open(String page){
        Application app=(Application)context.getApplicationContext();BlockingQueue<SettingsPageActivity> resumed=new LinkedBlockingQueue<>();
        Application.ActivityLifecycleCallbacks callback=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}
            public void onActivityResumed(Activity a){if(a instanceof SettingsPageActivity)resumed.add((SettingsPageActivity)a);}
            public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
        };app.registerActivityLifecycleCallbacks(callback);
        try{
            try(android.os.ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -W -n dev.ghostty.android.sample/.MainActivity");InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}catch(IOException e){throw new AssertionError(e);}
            inst.runOnMainSync(()->context.startActivity(SettingsNavigation.intent(context,page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            boolean orientationRequested=false;
            long deadline=android.os.SystemClock.uptimeMillis()+10000;boolean[] ready={false};
            do{
                SettingsPageActivity next;while((next=resumed.poll())!=null)current=next;
                if(current!=null&&!orientationRequested&&"landscape".equals(InstrumentationRegistry.getArguments().getString("settingsOrientation"))){orientationRequested=true;inst.runOnMainSync(()->current.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));}
                inst.runOnMainSync(()->{
                    boolean landscape="landscape".equals(InstrumentationRegistry.getArguments().getString("settingsOrientation"));
                    ready[0]=current!=null&&!current.isDestroyed()&&current.hasWindowFocus()
                            &&(!landscape||current.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE);
                });
                if(!ready[0])android.os.SystemClock.sleep(50);
            }while(!ready[0]&&android.os.SystemClock.uptimeMillis()<deadline);
            assertTrue("Settings did not finish orientation recreation",ready[0]);android.os.SystemClock.sleep(80);return current;
        }finally{app.unregisterActivityLifecycleCallbacks(callback);}
    }
    private View find(View view,String description){
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),description);if(found!=null)return found;}}
        return null;
    }
    private final List<SettingsPageActivity> opened=new ArrayList<>();
    private final Application.ActivityLifecycleCallbacks track=new Application.ActivityLifecycleCallbacks(){
        public void onActivityCreated(Activity a,Bundle b){if(a instanceof SettingsPageActivity)opened.add((SettingsPageActivity)a);}
        public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){if(a instanceof SettingsPageActivity)current=(SettingsPageActivity)a;}
        public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
    };
    @Before public void trackPages(){((Application)context.getApplicationContext()).registerActivityLifecycleCallbacks(track);}
    @After public void cleanPages(){finish();((Application)context.getApplicationContext()).unregisterActivityLifecycleCallbacks(track);}
    private void page(Class<? extends SettingsPageActivity> type){long until=android.os.SystemClock.uptimeMillis()+10000;boolean[] ready={false};do{inst.runOnMainSync(()->ready[0]=current!=null&&current.getClass()==type&&!current.isDestroyed()&&current.hasWindowFocus());if(!ready[0])android.os.SystemClock.sleep(50);}while(!ready[0]&&android.os.SystemClock.uptimeMillis()<until);assertTrue("Expected "+type.getSimpleName(),ready[0]);android.os.SystemClock.sleep(80);}
    private void click(String description){inst.runOnMainSync(()->{View view=find(current.root,description);assertNotNull(description,view);assertTrue(description,view.isEnabled());view.performClick();});android.os.SystemClock.sleep(80);}
    private void back(Class<? extends SettingsPageActivity> type){inst.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);page(type);}
    private void finish(){inst.runOnMainSync(()->{for(int i=opened.size()-1;i>=0;i--){SettingsPageActivity a=opened.get(i);if(!a.isDestroyed())a.finish();}opened.clear();current=null;});android.os.SystemClock.sleep(80);}
    private void restore(SharedPreferences prefs,String key,Object value){SharedPreferences.Editor edit=prefs.edit();if(value==null)edit.remove(key);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else edit.putString(key,(String)value);edit.commit();}

    @Test public void fontStepperBoundsAndRecreation()throws Exception{
        SharedPreferences prefs=context.getSharedPreferences("appearance",0);Object before=prefs.getAll().get("fontSize");
        try{
            prefs.edit().putInt("fontSize",14).commit();open("font");click(AppLanguage.tr(R.string.increase_terminal_font_size));assertEquals(15,prefs.getInt("fontSize",0));
            BlockingQueue<SettingsPageActivity> resumed=new LinkedBlockingQueue<>();Application app=(Application)context.getApplicationContext();
            Application.ActivityLifecycleCallbacks callbacks=new Application.ActivityLifecycleCallbacks(){
                public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){if(a instanceof SettingsPageActivity)resumed.add((SettingsPageActivity)a);}
                public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
            };
            app.registerActivityLifecycleCallbacks(callbacks);
            try{inst.runOnMainSync(current::recreate);SettingsPageActivity next=resumed.poll(10,TimeUnit.SECONDS);assertNotNull(next);current=next;android.os.SystemClock.sleep(80);}finally{app.unregisterActivityLifecycleCallbacks(callbacks);}
            inst.runOnMainSync(()->{assertEquals(AppLanguage.tr(R.string.font_and_size),current.heading.getText().toString());assertNotNull(find(current.root,AppLanguage.tr(R.string.terminal_font_size_value,15)));});
            for(int i=0;i<9;i++)click(AppLanguage.tr(R.string.increase_terminal_font_size));assertEquals(24,prefs.getInt("fontSize",0));
            inst.runOnMainSync(()->assertFalse(find(current.root,AppLanguage.tr(R.string.increase_terminal_font_size)).isEnabled()));
            for(int i=0;i<14;i++)click(AppLanguage.tr(R.string.decrease_terminal_font_size));assertEquals(10,prefs.getInt("fontSize",0));
            inst.runOnMainSync(()->assertFalse(find(current.root,AppLanguage.tr(R.string.decrease_terminal_font_size)).isEnabled()));
        }finally{finish();restore(prefs,"fontSize",before);}
    }
    @Test public void toolbarLayoutAndThemePersistAcrossPages(){
        SharedPreferences prefs=context.getSharedPreferences("appearance",0);Object theme=prefs.getAll().get("theme");InputPreferences controls=new InputPreferences(context);Object layout=controls.prefs.getAll().get("layout_toolbar");
        try{
            open("");click(AppLanguage.tr(R.string.toolbar));page(ToolbarSettingsActivity.class);click(AppLanguage.tr(R.string.input_configure_toolbar));page(InputLayoutActivity.class);click(AppLanguage.tr(R.string.input_remove)+AppLanguage.tr(R.string.input_paste));assertFalse(controls.list("layout_toolbar").contains("paste"));
            back(ToolbarSettingsActivity.class);click(AppLanguage.tr(R.string.input_configure_toolbar));page(InputLayoutActivity.class);assertFalse(controls.list("layout_toolbar").contains("paste"));back(ToolbarSettingsActivity.class);back(SettingsActivity.class);
            click(AppLanguage.tr(R.string.theme));page(ThemeSettingsActivity.class);click(AppLanguage.tr(R.string.light));assertEquals("light",prefs.getString("theme",""));
        }finally{finish();restore(controls.prefs,"layout_toolbar",layout);restore(prefs,"theme",theme);}
    }
    @Test public void legacyThemeMigratesAndNewInstallUsesShello(){
        SharedPreferences prefs=context.getSharedPreferences("appearance",0);Object before=prefs.getAll().get("theme");
        try{
            prefs.edit().putString("theme","Moshi").commit();open("theme");
            assertEquals("shello",prefs.getString("theme",""));
            inst.runOnMainSync(()->{assertNotNull(find(current.root,AppLanguage.tr(R.string.selected_option,"Shello")));assertNull(find(current.root,"Moshi"));});
            finish();prefs.edit().remove("theme").commit();open("theme");
            inst.runOnMainSync(()->assertNotNull(find(current.root,AppLanguage.tr(R.string.selected_option,"Shello"))));
        }finally{finish();restore(prefs,"theme",before);}
    }
    @Test public void allSettingsPagesWrapAndCapture()throws Exception{
        try{for(String page:new String[]{"","language","font","theme","advanced","hook","notifications","toolbar","gestures","chat","shortcuts","hardware"}){
            open(page);inst.runOnMainSync(()->{checkLabels(current.body);assertTrue("Settings column stretched across the window",current.body.getWidth()<=current.dp(640));});
            if("true".equals(InstrumentationRegistry.getArguments().getString("settingsScreenshots"))){
                Thread.sleep(700);inst.runOnMainSync(()->assertTrue("Settings must be in the foreground for screenshots",current.hasWindowFocus()));Bitmap image=inst.getUiAutomation().takeScreenshot();assertNotNull(image);
                File dir=new File(context.getCacheDir(),"settings-review");assertTrue(dir.isDirectory()||dir.mkdirs());
                try(FileOutputStream out=new FileOutputStream(new File(dir,(page.isEmpty()?"home":page)+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
            }
            finish();
        }}finally{finish();}
    }
    private void dragSetting(String from,String to){
        inst.runOnMainSync(()->{View a=find(current.root,from),b=find(current.root,to);assertNotNull(from,a);assertNotNull(to,b);android.graphics.Rect bounds=new android.graphics.Rect(0,0,a.getWidth(),a.getHeight()),other=new android.graphics.Rect(0,0,b.getWidth(),b.getHeight());current.body.offsetDescendantRectToMyCoords(a,bounds);current.body.offsetDescendantRectToMyCoords(b,other);bounds.union(other);current.scroll.requestChildRectangleOnScreen(current.body,bounds,true);});
        inst.waitForIdleSync();android.os.SystemClock.sleep(100);
        int[] start=new int[2],end=new int[2];inst.runOnMainSync(()->{View a=find(current.root,from),b=find(current.root,to);assertNotNull(from,a);assertNotNull(to,b);a.getLocationOnScreen(start);b.getLocationOnScreen(end);start[0]+=a.getWidth()/2;start[1]+=a.getHeight()/2;end[0]+=b.getWidth()/2;end[1]+=b.getHeight()/2;});
        long down=android.os.SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,start[0],start[1],0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);assertTrue(inst.getUiAutomation().injectInputEvent(event,true));event.recycle();android.os.SystemClock.sleep(100);
        for(int i=1;i<=8;i++){long time=android.os.SystemClock.uptimeMillis();event=MotionEvent.obtain(down,time,MotionEvent.ACTION_MOVE,start[0]+(end[0]-start[0])*i/8f,start[1]+(end[1]-start[1])*i/8f,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);assertTrue(inst.getUiAutomation().injectInputEvent(event,true));event.recycle();android.os.SystemClock.sleep(30);}
        event=MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,end[0],end[1],0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);assertTrue(inst.getUiAutomation().injectInputEvent(event,true));event.recycle();android.os.SystemClock.sleep(350);
    }
    @Test public void inputLayoutDragAndEditorSurviveRecreation()throws Exception{
        InputPreferences controls=new InputPreferences(context);Map<String,?> before=controls.prefs.getAll();
        try{
            controls.reset("layout_toolbar");open("toolbar");click(AppLanguage.tr(R.string.input_configure_toolbar));page(InputLayoutActivity.class);
            dragSetting(AppLanguage.tr(R.string.input_drag_to_reorder)+"Esc",AppLanguage.tr(R.string.input_drag_to_reorder)+"Tab");assertTrue(controls.list("layout_toolbar").indexOf("key_TAB")<controls.list("layout_toolbar").indexOf("key_ESCAPE"));
            click(AppLanguage.tr(R.string.input_custom_shortcut));page(ShortcutEditorActivity.class);SettingsPageActivity original=current;
            inst.runOnMainSync(()->{try{java.lang.reflect.Field n=ShortcutEditorActivity.class.getDeclaredField("name"),b=ShortcutEditorActivity.class.getDeclaredField("binding"),e=ShortcutEditorActivity.class.getDeclaredField("enter");n.setAccessible(true);b.setAccessible(true);e.setAccessible(true);((EditText)n.get(current)).setText("UI acceptance");((EditText)b.get(current)).setText("text:中文 👨‍👩‍👧‍👦");((CheckBox)e.get(current)).setChecked(true);current.recreate();}catch(Exception error){throw new AssertionError(error);}});
            long deadline=android.os.SystemClock.uptimeMillis()+10000;while(current==original&&android.os.SystemClock.uptimeMillis()<deadline)android.os.SystemClock.sleep(50);page(ShortcutEditorActivity.class);assertNotSame(original,current);
            click(AppLanguage.tr(R.string.input_save));page(InputLayoutActivity.class);String custom=controls.list("layout_toolbar").stream().filter(id->"UI acceptance".equals(controls.label(id))).findFirst().orElse(null);assertNotNull("Unsaved editor text was lost on recreation",custom);assertTrue(controls.action(custom).enter());assertEquals("text:中文 👨‍👩‍👧‍👦",controls.action(custom).binding());
            back(ToolbarSettingsActivity.class);click(AppLanguage.tr(R.string.input_configure_input_box));page(InputLayoutActivity.class);assertFalse(controls.list("layout_composer").contains(custom));
        }finally{finish();SharedPreferences.Editor edit=controls.prefs.edit().clear();for(var item:before.entrySet()){Object value=item.getValue();String key=item.getKey();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Long)edit.putLong(key,(Long)value);}assertTrue(edit.commit());}
    }
    private void checkLabels(View view){
        assertTrue("View exceeds content width",view.getParent() instanceof HorizontalScrollView||view.getWidth()<=current.body.getWidth());
        if(view instanceof TextView){TextView text=(TextView)view;if(text.getLayout()!=null&&text.getEllipsize()==null){
            assertTrue("Text clipped vertically: "+text.getText(),text.getLayout().getHeight()<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom()+1);
            // LineWidth includes trailing wrap spaces; LineMax measures the visible glyphs.
            for(int i=0;i<text.getLayout().getLineCount();i++)assertTrue("Text clipped horizontally: "+text.getText(),text.getLayout().getLineMax(i)<=text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight()+1);
        }}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)checkLabels(group.getChildAt(i));}
    }
}
