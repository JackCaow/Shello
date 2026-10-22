package dev.ghostty.android.sample;

import android.app.Activity;
import android.content.Context;

/** Rebuilds screens after language or appearance changes; SSH remains owned by its service. */
public abstract class LocalizedActivity extends Activity {
    private String language;
    private UiTheme palette;
    final UiTheme uiTheme(){return palette;}
    @Override protected void attachBaseContext(Context base){
        language=AppLanguage.signature(base);palette=UiTheme.current(base);super.attachBaseContext(AppLanguage.wrap(base));
    }
    @Override protected void onCreate(android.os.Bundle state){
        try{int theme=getPackageManager().getActivityInfo(getComponentName(),0).theme;setTheme(palette.light?(theme==R.style.SettingsTheme?R.style.SettingsLightTheme:R.style.SampleLightTheme):(theme==0?R.style.SampleTheme:theme));}
        catch(android.content.pm.PackageManager.NameNotFoundException ignored){setTheme(palette.light?R.style.SampleLightTheme:R.style.SampleTheme);}
        super.onCreate(state);UiTheme.window(this,getWindow());
    }
    @Override protected void onResume(){
        super.onResume();
        String current=AppLanguage.signature(getApplicationContext());
        if(!current.equals(language)||palette.light!=UiTheme.current(getApplicationContext()).light){language=current;recreate();}
    }
}
