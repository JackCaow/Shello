package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.os.Build;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

/** Independent language settings destination. */
public final class LanguageSettingsActivity extends SettingsPageActivity {
    @Override protected void renderSettings(){
        heading.setText(tr(R.string.app_language));section(tr(R.string.app_language));
        String selected=AppLanguage.choice(this);
        for(String choice:new String[]{"system","en","zh-CN"}){
            String name=languageName(choice);boolean checked=selected.equals(choice);
            ImageView check=SampleUi.icon(this,"check",SampleUi.GREEN);check.setMinimumWidth(dp(20));check.setMinimumHeight(dp(20));check.setVisibility(checked?View.VISIBLE:View.INVISIBLE);
            LinearLayout row=settingRow(name,null,null,check,()->{
                if(checked)return;
                AppLanguage.select(this,choice);
                // Android 13+ recreates via LocaleManager; older versions use our wrapper.
                if(Build.VERSION.SDK_INT<33)recreate();
            });
            row.setSelected(checked);row.setContentDescription(checked?tr(R.string.selected_option,name):name);
        }
        copy(tr(R.string.language_help));
    }
}
