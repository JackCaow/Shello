package dev.ghostty.android.sample;

import android.app.Activity;
import android.content.Context;

/** Rebuilds older Android screens after an in-app language change without stopping SSH. */
public abstract class LocalizedActivity extends Activity {
    private String language;
    @Override protected void attachBaseContext(Context base){
        language=AppLanguage.signature(base);super.attachBaseContext(AppLanguage.wrap(base));
    }
    @Override protected void onResume(){
        super.onResume();
        String current=AppLanguage.signature(getApplicationContext());
        if(!current.equals(language)){language=current;recreate();}
    }
}
