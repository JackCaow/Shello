package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Independent font settings destination. */
public final class FontSettingsActivity extends SettingsPageActivity {
    @Override protected void renderSettings(){
        heading.setText(tr(R.string.font_and_size));section(tr(R.string.terminal_text));
        LinearLayout stepper=row();stepper.setBackground(SampleUi.surface(this,0xff222426,24));
        TextView number=label("",15,SampleUi.TEXT);number.setGravity(Gravity.CENTER);number.setSingleLine(true);number.setMinWidth(dp(48));
        TextView preview=label("",14,SampleUi.TEXT);preview.setTypeface(Typeface.MONOSPACE);
        View minus=SampleUi.header(this,"minus",tr(R.string.decrease_terminal_font_size),SampleUi.TEXT,()->{});
        View plus=SampleUi.header(this,"plus",tr(R.string.increase_terminal_font_size),SampleUi.TEXT,()->{});
        stepper.addView(minus,new LinearLayout.LayoutParams(dp(48),dp(48)));
        stepper.addView(number,new LinearLayout.LayoutParams(-2,dp(48)));
        stepper.addView(plus,new LinearLayout.LayoutParams(dp(48),dp(48)));
        Runnable refresh=()->{int size=prefs.getInt("fontSize",14);number.setText(size+" sp");number.setContentDescription(tr(R.string.terminal_font_size_value,size));preview.setTextSize(size);minus.setEnabled(size>10);minus.setAlpha(size>10?1:.3f);plus.setEnabled(size<24);plus.setAlpha(size<24?1:.3f);};
        minus.setOnClickListener(v->{prefs.edit().putInt("fontSize",Math.max(10,prefs.getInt("fontSize",14)-1)).apply();refresh.run();});
        plus.setOnClickListener(v->{prefs.edit().putInt("fontSize",Math.min(24,prefs.getInt("fontSize",14)+1)).apply();refresh.run();});
        settingRow(tr(R.string.font_size),null,"font",stepper,null);
        settingRow(tr(R.string.font),null,"font",trailing(tr(R.string.system_monospace),false),null);
        copy(tr(R.string.chinese_and_emoji_font_fallback_supported_font_size_10_24_sp));
        section(tr(R.string.preview));preview.setText(tr(R.string.font_preview_sample));preview.setPadding(dp(16),dp(20),dp(16),dp(20));preview.setMinHeight(dp(140));preview.setBackground(SampleUi.surface(this,0xff1e1e1e,12));body.addView(preview,new LinearLayout.LayoutParams(-1,-2));refresh.run();
    }
}
