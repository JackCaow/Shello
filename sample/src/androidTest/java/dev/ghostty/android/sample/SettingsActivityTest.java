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
    @Test public void toolbarSwitchAndThemePersistAcrossPages(){
        SharedPreferences prefs=context.getSharedPreferences("appearance",0);Object tool=prefs.getAll().get("tool_paste"),theme=prefs.getAll().get("theme");
        try{
            prefs.edit().putBoolean("tool_paste",true).putString("theme","Shello").commit();open("");click(AppLanguage.tr(R.string.toolbar));page(ToolbarSettingsActivity.class);click(AppLanguage.tr(R.string.paste));assertFalse(prefs.getBoolean("tool_paste",true));
            back(SettingsActivity.class);click(AppLanguage.tr(R.string.toolbar));page(ToolbarSettingsActivity.class);inst.runOnMainSync(()->assertFalse(((CheckBox)find(current.root,AppLanguage.tr(R.string.paste))).isChecked()));
            back(SettingsActivity.class);click(AppLanguage.tr(R.string.theme));page(ThemeSettingsActivity.class);click(AppLanguage.tr(R.string.light));assertEquals("light",prefs.getString("theme",""));
            inst.runOnMainSync(()->assertTrue(find(current.root,AppLanguage.tr(R.string.selected_option,AppLanguage.tr(R.string.light))).isSelected()));
        }finally{finish();restore(prefs,"tool_paste",tool);restore(prefs,"theme",theme);}
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
        try{for(String page:new String[]{"","language","font","theme","advanced","hook","notifications","toolbar","gestures","chat","shortcuts"}){
            open(page);inst.runOnMainSync(()->{checkLabels(current.body);assertTrue("Settings column stretched across the window",current.body.getWidth()<=current.dp(640));});
            if("true".equals(InstrumentationRegistry.getArguments().getString("settingsScreenshots"))){
                Thread.sleep(700);inst.runOnMainSync(()->assertTrue("Settings must be in the foreground for screenshots",current.hasWindowFocus()));Bitmap image=inst.getUiAutomation().takeScreenshot();assertNotNull(image);
                File dir=new File(context.getCacheDir(),"settings-review");assertTrue(dir.isDirectory()||dir.mkdirs());
                try(FileOutputStream out=new FileOutputStream(new File(dir,(page.isEmpty()?"home":page)+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
            }
            finish();
        }}finally{finish();}
    }
    private void checkLabels(View view){
        assertTrue("View exceeds content width",view.getWidth()<=current.body.getWidth());
        if(view instanceof TextView){TextView text=(TextView)view;if(text.getLayout()!=null&&text.getEllipsize()==null){
            assertTrue("Text clipped vertically: "+text.getText(),text.getLayout().getHeight()<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom()+1);
            // LineWidth includes trailing wrap spaces; LineMax measures the visible glyphs.
            for(int i=0;i<text.getLayout().getLineCount();i++)assertTrue("Text clipped horizontally: "+text.getText(),text.getLayout().getLineMax(i)<=text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight()+1);
        }}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)checkLabels(group.getChildAt(i));}
    }
}
