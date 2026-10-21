package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Checks both language paths, persisted identities, formats and live screen recreation. */
public class LocalizationTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context context=inst.getTargetContext();
    private final BlockingQueue<SettingsActivity> resumed=new LinkedBlockingQueue<>();
    private SettingsActivity current;
    private String original;
    private final Application.ActivityLifecycleCallbacks callbacks=new Application.ActivityLifecycleCallbacks(){
        public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}
        public void onActivityResumed(Activity a){if(a instanceof SettingsActivity){current=(SettingsActivity)a;resumed.add(current);}}
        public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}
        public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
    };
    @Before public void before(){original=AppLanguage.choice(context);((Application)context.getApplicationContext()).registerActivityLifecycleCallbacks(callbacks);}
    @After public void after(){
        if(current!=null){inst.runOnMainSync(current::finish);inst.waitForIdleSync();}
        ((Application)context.getApplicationContext()).unregisterActivityLifecycleCallbacks(callbacks);
        inst.runOnMainSync(()->AppLanguage.select(context,original));
    }
    private void language(String value)throws Exception{
        inst.runOnMainSync(()->AppLanguage.select(context,value));
        long end=SystemClock.elapsedRealtime()+5000;
        while(!AppLanguage.choice(context).equals(value)&&SystemClock.elapsedRealtime()<end)Thread.sleep(50);
        assertEquals(value,AppLanguage.choice(context));
        // Framework configuration delivery follows the LocaleManager binder response.
        if(Build.VERSION.SDK_INT>=33&&!value.equals("system")){
            String expected=value.equals("zh-CN")?"zh":"en";
            while(!context.getApplicationContext().getResources().getConfiguration().getLocales().get(0).getLanguage().equals(expected)&&SystemClock.elapsedRealtime()<end)Thread.sleep(50);
        }
    }
    private android.content.res.Resources resources(String tag){Configuration c=new Configuration(context.getResources().getConfiguration());c.setLocales(LocaleList.forLanguageTags(tag));return context.createConfigurationContext(c).getResources();}
    @Test public void translationsPluralFormatsAndFallback(){
        android.content.res.Resources en=resources("en"),zh=resources("zh-CN"),fr=resources("fr");
        assertEquals("Settings",en.getString(R.string.settings));assertEquals("设置",zh.getString(R.string.settings));assertEquals("Settings",fr.getString(R.string.settings));
        assertEquals("Found 1 server",en.getQuantityString(R.plurals.servers_found,1,1));assertEquals("Found 2 servers",en.getQuantityString(R.plurals.servers_found,2,2));
        assertEquals("找到 2 台服务器",zh.getQuantityString(R.plurals.servers_found,2,2));
        assertEquals("1 minute ago",en.getQuantityString(R.plurals.minutes_ago_count,1,1));
        assertEquals("2 minutes ago",en.getQuantityString(R.plurals.minutes_ago_count,2,2));
        assertEquals("Disconnected. Reconnecting in 3 s",en.getString(R.string.reconnect_countdown,3));
        assertEquals("已断开，3 秒后重连",zh.getString(R.string.reconnect_countdown,3));
        String demo=en.getString(R.string.local_demo_banner);
        assertTrue(demo.startsWith("\033]10;"));assertTrue(demo.contains("\007"));assertTrue(demo.endsWith("\r\n\r\n› "));
        assertEquals("Terminal input",en.getString(dev.ghostty.android.R.string.terminal_input_hint));
        assertEquals("终端输入",zh.getString(dev.ghostty.android.R.string.terminal_input_hint));
    }
    @Test public void themeKeysSurviveLanguageChanges()throws Exception{
        SharedPreferences prefs=context.getSharedPreferences("appearance",0);String old=prefs.getString("theme",null);
        try{
            prefs.edit().putString("theme","浅色").commit();language("en");assertEquals("light",ThemePreferences.current(prefs));assertEquals("Light",ThemePreferences.label("light"));
            language("zh-CN");assertEquals("light",ThemePreferences.current(prefs));assertEquals("浅色",ThemePreferences.label("light"));
            prefs.edit().putString("theme","黑色").commit();assertEquals("black",ThemePreferences.current(prefs));
        }finally{prefs.edit().putString("theme",old).commit();}
    }
    @Test public void languagePickerRecreatesAndKeepsPage()throws Exception{
        language("en");current=(SettingsActivity)inst.startActivitySync(new Intent(context,SettingsActivity.class).putExtra("page","language").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));inst.waitForIdleSync();
        assertEquals("App language",current.heading.getText().toString());
        choose("简体中文","应用语言");choose("English","App language");
        choose("Follow system",resources(android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0).toLanguageTag()).getString(R.string.app_language));
        assertEquals("system",AppLanguage.choice(context));
    }
    private void choose(String label,String heading)throws Exception{
        SettingsActivity old=current;resumed.clear();
        inst.runOnMainSync(()->{View view=find(old.root,label);assertNotNull(label,view);view.performClick();});
        SettingsActivity next=resumed.poll(10,TimeUnit.SECONDS);assertNotNull("Language change must recreate the screen",next);assertNotSame(old,next);current=next;inst.waitForIdleSync();
        assertEquals(heading,current.heading.getText().toString());assertEquals("language",fieldPage(current));
    }
    private String fieldPage(SettingsActivity a)throws Exception{java.lang.reflect.Field f=SettingsActivity.class.getDeclaredField("page");f.setAccessible(true);return (String)f.get(a);}
    private View find(View v,String description){if(description.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),description);if(found!=null)return found;}}return null;}
    @Test public void validationAndBackgroundStateFollowLanguage()throws Exception{
        SshProfileStore.Draft draft=new SshProfileStore.Draft();
        java.io.File folder=new java.io.File(context.getCacheDir(),"locale-empty-profile");
        SshSessionConnection connection=new SshSessionConnection(context,folder,"fixture",new SshSessionConnection.Listener(){public void changed(){}public void detach(){}});
        language("en");assertTrue(draft.validate().startsWith("Enter a hostname"));assertEquals("Disconnected",connection.status());
        language("zh-CN");assertTrue(draft.validate().startsWith("请填写主机名"));assertEquals("已断开",connection.status());
    }
}
