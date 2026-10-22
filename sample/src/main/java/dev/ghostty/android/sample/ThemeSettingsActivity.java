package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;

/** Independent theme settings destination. */
public final class ThemeSettingsActivity extends SettingsPageActivity {
    @Override protected void renderSettings(){
        heading.setText(tr(R.string.theme));section(tr(R.string.appearance_and_terminal_theme));String selected=ThemePreferences.current(prefs);
        for(String name:new String[]{"shello","black","light"}){
            ImageView check=SampleUi.icon(this,"check",SampleUi.GREEN);check.setMinimumWidth(dp(20));check.setMinimumHeight(dp(20));check.setVisibility(selected.equals(name)?View.VISIBLE:View.INVISIBLE);
            LinearLayout row=settingRow(ThemePreferences.label(name),null,"palette",check,()->{prefs.edit().putString("theme",name).apply();if(uiTheme().light!=name.equals("light"))recreate();else refreshSettings();});row.setSelected(selected.equals(name));row.setContentDescription(selected.equals(name)?tr(R.string.selected_option,ThemePreferences.label(name)):ThemePreferences.label(name));
        }
        copy(tr(R.string.appearance_theme_description));
        section(tr(R.string.preview));TextView preview=label(tr(R.string.theme_preview_sample),14,selected.equals("light")?0xff202124:0xffd4d4d4);preview.setTypeface(Typeface.MONOSPACE);preview.setPadding(dp(16),dp(20),dp(16),dp(20));preview.setMinHeight(dp(140));preview.setBackground(SampleUi.surface(this,selected.equals("light")?0xfff5f5f5:selected.equals("black")?Color.BLACK:0xff1e1e1e,12));body.addView(preview,new LinearLayout.LayoutParams(-1,-2));
    }
}
