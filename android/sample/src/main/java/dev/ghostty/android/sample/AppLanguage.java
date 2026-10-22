package dev.ghostty.android.sample;

import android.app.Application;
import android.app.LocaleManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

/** App-local language on API 27+, synchronized with Android 13 per-app languages. */
public final class AppLanguage extends Application {
    private static Context application;
    private static Resources localized;
    private static Configuration sourceConfiguration;
    private static String legacyChoice;
    @Override public void onCreate(){
        super.onCreate();application=this;
        SharedPreferences prefs=getSharedPreferences("language",0);
        if(Build.VERSION.SDK_INT>=33&&!prefs.getBoolean("platformLocales",false)){
            String saved=prefs.getString("selected","system");LocaleManager manager=getSystemService(LocaleManager.class);
            if(manager.getApplicationLocales().isEmpty()&&(saved.equals("en")||saved.equals("zh-CN")))manager.setApplicationLocales(LocaleList.forLanguageTags(saved));
            prefs.edit().putBoolean("platformLocales",true).apply();
        }
        refreshChannels();
    }
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);invalidateResources();refreshChannels();}
    static void refreshChannels(){
        NotificationManager manager=application.getSystemService(NotificationManager.class);
        String[] ids={"ssh_session","agent_events"};int[] labels={R.string.terminal_sessions,R.string.agent_event};
        for(int i=0;i<ids.length;i++){
            NotificationChannel channel=manager.getNotificationChannel(ids[i]);
            if(channel!=null){channel.setName(tr(labels[i]));manager.createNotificationChannel(channel);}
        }
    }
    static String choice(Context context){
        if(Build.VERSION.SDK_INT>=33){
            LocaleList locales=context.getSystemService(LocaleManager.class).getApplicationLocales();
            return locales.isEmpty()?"system":locales.get(0).getLanguage().equals("zh")?"zh-CN":"en";
        }
        return context.getSharedPreferences("language",0).getString("selected","system");
    }
    static void select(Context context,String language){
        if(!language.equals("system")&&!language.equals("en")&&!language.equals("zh-CN"))throw new IllegalArgumentException("Unsupported language");
        context.getSharedPreferences("language",0).edit().putString("selected",language).apply();
        if(Build.VERSION.SDK_INT>=33)context.getSystemService(LocaleManager.class).setApplicationLocales(language.equals("system")?LocaleList.getEmptyLocaleList():LocaleList.forLanguageTags(language));
        invalidateResources();
        if(Build.VERSION.SDK_INT<33)refreshChannels();
    }
    static Context wrap(Context context){
        String choice=choice(context);
        if(choice.equals("system"))return context;
        Configuration config=new Configuration(context.getResources().getConfiguration());
        config.setLocales(LocaleList.forLanguageTags(choice));
        return context.createConfigurationContext(config);
    }
    static String signature(Context context){return choice(context)+":"+context.getResources().getConfiguration().getLocales().toLanguageTags();}
    private static synchronized void invalidateResources(){localized=null;}
    private static synchronized Resources resources(){
        Configuration current=application.getResources().getConfiguration();
        String selected=Build.VERSION.SDK_INT<33?choice(application):"platform";
        if(localized==null||!current.equals(sourceConfiguration)||!selected.equals(legacyChoice)){
            localized=wrap(application).getResources();sourceConfiguration=new Configuration(current);legacyChoice=selected;
        }
        return localized;
    }
    static String tr(int id,Object... arguments){return arguments.length==0?resources().getString(id):resources().getString(id,arguments);}
    static String quantity(int id,int count,Object... arguments){return resources().getQuantityString(id,count,arguments);}
}
