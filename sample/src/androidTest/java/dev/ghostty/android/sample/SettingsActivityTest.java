package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Settings interactions, recreation and wrapping; no SSH or remote configuration changes. */
public class SettingsActivityTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context context=inst.getTargetContext();
    private SettingsActivity current;
    private SettingsActivity open(String page){
        current=(SettingsActivity)inst.startActivitySync(new Intent(context,SettingsActivity.class).putExtra("page",page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        inst.waitForIdleSync();return current;
    }
    private View find(View view,String description){
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),description);if(found!=null)return found;}}
        return null;
    }
    private void click(String description){inst.runOnMainSync(()->{View view=find(current.root,description);assertNotNull(description,view);assertTrue(description,view.isEnabled());view.performClick();});inst.waitForIdleSync();}
    private void finish(){if(current!=null){inst.runOnMainSync(current::finish);inst.waitForIdleSync();current=null;}}
    private void restore(SharedPreferences prefs,String key,Object value){SharedPreferences.Editor edit=prefs.edit();if(value==null)edit.remove(key);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else edit.putString(key,(String)value);edit.commit();}

    @Test public void fontStepperBoundsAndRecreation()throws Exception{
        SharedPreferences prefs=context.getSharedPreferences("appearance",0);Object before=prefs.getAll().get("fontSize");
        try{
            prefs.edit().putInt("fontSize",14).commit();open("font");click("增大终端字号");assertEquals(15,prefs.getInt("fontSize",0));
            BlockingQueue<SettingsActivity> resumed=new LinkedBlockingQueue<>();Application app=(Application)context.getApplicationContext();
            Application.ActivityLifecycleCallbacks callbacks=new Application.ActivityLifecycleCallbacks(){
                public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){if(a instanceof SettingsActivity)resumed.add((SettingsActivity)a);}
                public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
            };
            app.registerActivityLifecycleCallbacks(callbacks);
            try{inst.runOnMainSync(current::recreate);SettingsActivity next=resumed.poll(10,TimeUnit.SECONDS);assertNotNull(next);current=next;inst.waitForIdleSync();}finally{app.unregisterActivityLifecycleCallbacks(callbacks);}
            inst.runOnMainSync(()->{assertEquals("字体与大小",current.heading.getText().toString());assertNotNull(find(current.root,"终端字号 15"));});
            for(int i=0;i<9;i++)click("增大终端字号");assertEquals(24,prefs.getInt("fontSize",0));
            inst.runOnMainSync(()->assertFalse(find(current.root,"增大终端字号").isEnabled()));
            for(int i=0;i<14;i++)click("减小终端字号");assertEquals(10,prefs.getInt("fontSize",0));
            inst.runOnMainSync(()->assertFalse(find(current.root,"减小终端字号").isEnabled()));
        }finally{finish();restore(prefs,"fontSize",before);}
    }
    @Test public void toolbarSwitchAndThemePersistAcrossPages(){
        SharedPreferences prefs=context.getSharedPreferences("appearance",0);Object tool=prefs.getAll().get("tool_paste"),theme=prefs.getAll().get("theme");
        try{
            prefs.edit().putBoolean("tool_paste",true).putString("theme","Shello").commit();open("toolbar");click("粘贴");assertFalse(prefs.getBoolean("tool_paste",true));
            inst.runOnMainSync(current::onBackPressed);inst.waitForIdleSync();click("工具栏");inst.runOnMainSync(()->assertFalse(((CheckBox)find(current.root,"粘贴")).isChecked()));
            inst.runOnMainSync(current::onBackPressed);inst.waitForIdleSync();click("主题");click("浅色");assertEquals("浅色",prefs.getString("theme",""));
            inst.runOnMainSync(()->assertTrue(find(current.root,"浅色，已选中").isSelected()));
        }finally{finish();restore(prefs,"tool_paste",tool);restore(prefs,"theme",theme);}
    }
    @Test public void legacyThemeMigratesAndNewInstallUsesShello(){
        SharedPreferences prefs=context.getSharedPreferences("appearance",0);Object before=prefs.getAll().get("theme");
        try{
            prefs.edit().putString("theme","Moshi").commit();open("theme");
            assertEquals("Shello",prefs.getString("theme",""));
            inst.runOnMainSync(()->{assertNotNull(find(current.root,"Shello，已选中"));assertNull(find(current.root,"Moshi"));});
            finish();prefs.edit().remove("theme").commit();open("theme");
            inst.runOnMainSync(()->assertNotNull(find(current.root,"Shello，已选中")));
        }finally{finish();restore(prefs,"theme",before);}
    }
    @Test public void allSettingsPagesWrapAndCapture()throws Exception{
        try{for(String page:new String[]{"","font","theme","advanced","hook","notifications","toolbar","gestures","chat","shortcuts"}){
            open(page);inst.runOnMainSync(()->checkLabels(current.body));
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
            for(int i=0;i<text.getLayout().getLineCount();i++)assertTrue("Text clipped horizontally: "+text.getText(),text.getLayout().getLineWidth(i)<=text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight()+1);
        }}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)checkLabels(group.getChildAt(i));}
    }
}
