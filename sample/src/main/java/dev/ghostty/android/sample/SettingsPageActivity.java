package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** Shared settings styling only; every destination owns an Android Activity. */
public abstract class SettingsPageActivity extends ScreenActivity {
    protected SharedPreferences prefs;
    protected boolean working;
    private LinearLayout group;
    protected static final int CARD=0xff111315, DIVIDER=0xff222426;
    private boolean resumed;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);prefs=getSharedPreferences("appearance",0);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        body.setPadding(dp(16),dp(landscape?12:24),dp(16),dp(32));scroll.setVerticalScrollBarEnabled(false);scroll.setId(android.R.id.list);
        if(Build.VERSION.SDK_INT>=34){overrideActivityTransition(OVERRIDE_TRANSITION_OPEN,R.anim.settings_enter,R.anim.settings_exit);overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE,R.anim.settings_pop_enter,R.anim.settings_pop_exit);}
        renderSettings();int y=state==null?getIntent().getIntExtra("initialScroll",0):state.getInt("scroll");scroll.post(()->scroll.scrollTo(0,y));
    }
    protected abstract void renderSettings();
    protected final void refreshSettings(){int y=scroll.getScrollY();group=null;body.removeAllViews();renderSettings();scroll.post(()->scroll.scrollTo(0,y));}
    @Override protected void onResume(){super.onResume();if(resumed)refreshSettings();resumed=true;}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("scroll",scroll.getScrollY());super.onSaveInstanceState(state);}
    protected TextView label(String value,int size,int color){
        TextView text=SampleUi.text(this,value,size,color);
        text.setIncludeFontPadding(false);text.setLineSpacing(dp(3),1);return text;
    }

    @Override void section(String title){
        group=null;
        TextView caption=label(title,13,SampleUi.MUTED);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        p.topMargin=body.getChildCount()==0?0:dp(landscape?16:24);p.bottomMargin=dp(10);
        body.addView(caption,p);
    }

    @Override TextView copy(String value){
        group=null;
        TextView text=label(value,14,SampleUi.MUTED);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);
        body.addView(text,p);return text;
    }

    private void addRow(View row){
        if(group==null){
            group=column();group.setBackground(SampleUi.surface(this,CARD,12));group.setClipToOutline(true);
            body.addView(group,new LinearLayout.LayoutParams(-1,-2));
        }
        if(group.getChildCount()>0){View line=new View(this);line.setBackgroundColor(DIVIDER);group.addView(line,new LinearLayout.LayoutParams(-1,Math.max(1,dp(1))));}
        group.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }

    protected LinearLayout settingRow(String title,String subtitle,String icon,View accessory,Runnable action){
        LinearLayout row=row();row.setPadding(dp(16),dp(12),dp(16),dp(12));row.setMinimumHeight(dp(48));
        if(icon!=null){
            LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(20),dp(20));ip.rightMargin=dp(16);
            row.addView(SampleUi.icon(this,icon,SampleUi.MUTED),ip);
        }
        LinearLayout words=column();words.addView(label(title,16,SampleUi.TEXT));
        if(subtitle!=null){TextView sub=label(subtitle,14,SampleUi.MUTED);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(5);words.addView(sub,p);}
        row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        if(accessory!=null){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.leftMargin=dp(12);row.addView(accessory,p);}
        if(action!=null){row.setContentDescription(title);row.setFocusable(true);row.setBackground(SampleUi.touch(this,Color.TRANSPARENT,0));row.setOnClickListener(v->action.run());}
        addRow(row);return row;
    }

    protected View trailing(String value,boolean chevron){
        LinearLayout end=row();
        if(value!=null){TextView text=label(value,16,SampleUi.MUTED);text.setSingleLine(true);text.setEllipsize(TextUtils.TruncateAt.END);
            text.setMaxWidth(dp(getResources().getConfiguration().fontScale>1.2f?100:146));end.addView(text);}
        if(chevron){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(18),dp(18));if(value!=null)p.leftMargin=dp(10);end.addView(SampleUi.icon(this,"right",SampleUi.MUTED),p);}
        return end;
    }

    protected void navigation(String title,String value,String icon,String target){settingRow(title,null,icon,trailing(value,true),()->SettingsNavigation.open(this,target));}

    @Override View item(String title,String detail,String icon,Runnable action){return settingRow(title,detail,icon,action==null?null:trailing(null,true),action);}

    protected void toggle(String title,String subtitle,String icon,String preferenceGroup,String key,boolean fallback,Runnable changed){
        SharedPreferences source=getSharedPreferences(preferenceGroup,0);
        SettingSwitch toggle=new SettingSwitch(title,subtitle,icon);
        toggle.setChecked(source.getBoolean(key,fallback));
        toggle.setOnCheckedChangeListener((v,checked)->{source.edit().putBoolean(key,checked).apply();if(changed!=null)changed.run();});
        addRow(toggle);
    }

    protected String languageName(String choice){return choice.equals("en")?"English":choice.equals("zh-CN")?"简体中文":tr(R.string.follow_system);}

    /** One accessible switch row, with text wrapping clear of the trailing thumb. */
    private final class SettingSwitch extends CheckBox {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        SettingSwitch(String title,String subtitle,String icon){
            super(SettingsPageActivity.this);setButtonDrawable((android.graphics.drawable.Drawable)null);
            setTextSize(16);setTextColor(SampleUi.TEXT);setIncludeFontPadding(false);setGravity(Gravity.CENTER_VERTICAL);setLineSpacing(dp(3),1);
            setPadding(dp(16),dp(12),dp(80),dp(12));setMinHeight(dp(48));setBackground(SampleUi.touch(SettingsPageActivity.this,Color.TRANSPARENT,0));
            if(subtitle==null)setText(title);else{SpannableString text=new SpannableString(title+"\n"+subtitle);text.setSpan(new ForegroundColorSpan(SampleUi.MUTED),title.length()+1,text.length(),0);text.setSpan(new AbsoluteSizeSpan(Math.round(14*getResources().getDisplayMetrics().scaledDensity)),title.length()+1,text.length(),0);setText(text);}
            setContentDescription(subtitle==null?title:tr(R.string.accessibility_subtitle,title,subtitle));
            if(icon!=null){ToolbarIcon glyph=new ToolbarIcon(icon,dp(20),SampleUi.MUTED);glyph.setBounds(0,0,dp(20),dp(20));setCompoundDrawables(glyph,null,null,null);setCompoundDrawablePadding(dp(16));}
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float right=getWidth()-dp(16),left=right-dp(48),cy=getHeight()/2f;
            paint.setColor(isChecked()?SampleUi.GREEN:0xff242628);c.drawRoundRect(left,cy-dp(12),right,cy+dp(12),dp(12),dp(12),paint);
            paint.setColor(isChecked()?Color.BLACK:Color.WHITE);float x=isChecked()?right-dp(14):left+dp(14);c.drawRoundRect(x-dp(12),cy-dp(10),x+dp(12),cy+dp(10),dp(10),dp(10),paint);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.Switch");}
    }
}
