package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.app.*;
import android.os.*;
import android.graphics.*;
import android.content.*;
import android.view.*;
import android.widget.*;

/** Shared full-screen dark pages; controls keep native accessibility semantics. */
public abstract class ScreenActivity extends LocalizedActivity {
    LinearLayout body,root,header;TextView heading;ScrollView scroll;
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root=column();root.setBackgroundColor(Color.BLACK);setContentView(root);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(i.left,i.top,i.right,Math.max(i.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom));return insets;});
        SampleUi.NavigationBar navigation=new SampleUi.NavigationBar(this,"",this::onBackPressed);
        header=navigation;heading=navigation.title;root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        scroll=new ScrollView(this);body=column();body.setPadding(dp(16),dp(getResources().getConfiguration().screenHeightDp<480?12:24),dp(16),dp(32));scroll.addView(AdaptiveLayout.centered(this,body,contentWidthDp(),header));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }
    int contentWidthDp(){return UiDesign.CONTENT_MAX;}
    int dp(int value){return SampleUi.dp(this,value);}
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);l.setBaselineAligned(false);return l;}
    void section(String text){TextView label=SampleUi.sectionTitle(this,text);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=body.getChildCount()==0?0:dp(UiDesign.SECTION);p.bottomMargin=dp(UiDesign.GAP);body.addView(label,p);}
    TextView copy(String text){TextView label=SampleUi.text(this,text,14,SampleUi.MUTED);UiDesign.paragraph(label);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);p.bottomMargin=dp(16);body.addView(label,p);return label;}
    View item(String title,String detail,String icon,Runnable action){
        LinearLayout row=row();row.setPadding(dp(16),dp(12),dp(16),dp(12));row.setMinimumHeight(dp(UiDesign.ROW));row.setBackground(SampleUi.touch(this,SampleUi.FIELD,16));row.setContentDescription(title);row.setFocusable(true);
        row.addView(SampleUi.icon(this,icon,SampleUi.MUTED),new LinearLayout.LayoutParams(dp(24),dp(24)));
        LinearLayout text=column();text.setPadding(dp(16),0,dp(8),0);text.addView(SampleUi.text(this,title,17,SampleUi.TEXT));if(detail!=null&&!detail.isEmpty()){TextView subtitle=SampleUi.text(this,detail,14,SampleUi.MUTED);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=dp(UiDesign.TEXT_GAP);text.addView(subtitle,sp);}row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(SampleUi.icon(this,"right",SampleUi.MUTED),new LinearLayout.LayoutParams(dp(20),dp(20)));row.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(UiDesign.GAP);body.addView(row,p);return row;
    }
    void toast(String message){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    void clipboard(String title,String text){getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(title,text));toast(tr(R.string.copied));}
}
