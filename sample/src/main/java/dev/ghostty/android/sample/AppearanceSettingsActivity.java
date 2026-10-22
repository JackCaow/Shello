package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.widget.*;

/** One destination for app appearance and language; legacy page classes inherit this content. */
public class AppearanceSettingsActivity extends SettingsPageActivity {
    @Override protected void renderSettings(){
        heading.setText(tr(R.string.appearance_and_language));section(tr(R.string.theme));
        String selected=ThemePreferences.current(prefs);
        for(String theme:new String[]{"shello","black","light"}){
            boolean checked=selected.equals(theme);String name=ThemePreferences.label(theme);
            LinearLayout choice=settingRow(name,null,null,check(checked),()->{
                if(checked)return;
                prefs.edit().putString("theme",theme).apply();
                if(uiTheme().light!=theme.equals("light"))recreate();else refreshSettings();
            });
            ImageView swatch=new ImageView(this);swatch.setImageDrawable(new ThemeSwatch(theme));swatch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams image=new LinearLayout.LayoutParams(dp(44),dp(36));image.rightMargin=dp(16);choice.addView(swatch,0,image);
            selected(choice,name,checked);
        }
        copy(tr(R.string.appearance_scope));
        section(tr(R.string.app_language));String language=AppLanguage.choice(this);
        for(String choice:new String[]{"system","en","zh-CN"}){
            boolean checked=language.equals(choice);String name=languageName(choice);
            LinearLayout row=settingRow(name,null,null,check(checked),()->{
                if(checked)return;AppLanguage.select(this,choice);
                if(Build.VERSION.SDK_INT<33)recreate();
            });selected(row,name,checked);
        }
        copy(tr(R.string.language_help));
    }
    private ImageView check(boolean selected){ImageView image=SampleUi.icon(this,"check",SampleUi.GREEN);image.setMinimumWidth(dp(20));image.setMinimumHeight(dp(20));image.setVisibility(selected?View.VISIBLE:View.INVISIBLE);return image;}
    private void selected(LinearLayout row,String name,boolean checked){
        row.setSelected(checked);row.setContentDescription(checked?tr(R.string.selected_option,name):name);
        if(checked&&uiTheme().light)row.setBackground(SampleUi.touch(this,0xfff5f9f6,0));
    }
    /** Miniature app chrome, deliberately showing the candidate theme rather than the current palette. */
    private static final class ThemeSwatch extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final boolean light,black;
        ThemeSwatch(String theme){light=theme.equals("light");black=theme.equals("black");}
        @Override public void draw(Canvas canvas){
            Rect b=getBounds();canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/44f,b.height()/36f);
            paint.setStyle(Paint.Style.FILL);paint.setColor(light?0xfff1f3f5:black?Color.BLACK:0xff202226);canvas.drawRoundRect(0,0,44,36,8,8,paint);
            paint.setColor(light?0xffdce2e8:0xff42464b);canvas.drawRoundRect(6,7,18,9,1,1,paint);
            paint.setColor(light?Color.WHITE:0xff303337);canvas.drawRoundRect(6,14,38,29,4,4,paint);
            paint.setColor(light?0xff087442:SampleUi.GREEN);canvas.drawCircle(11,21,2,paint);
            paint.setColor(light?0xffc5cdd6:0xff737b85);canvas.drawRoundRect(17,20,32,22,1,1,paint);canvas.restore();
        }
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
