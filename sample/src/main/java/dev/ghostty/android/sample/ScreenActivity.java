package dev.ghostty.android.sample;

import android.app.*;
import android.os.*;
import android.graphics.*;
import android.content.*;
import android.view.*;
import android.widget.*;

/** Shared full-screen dark pages; controls keep native accessibility semantics. */
public abstract class ScreenActivity extends Activity {
    LinearLayout body,root,header;TextView heading;ScrollView scroll;
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root=column();root.setBackgroundColor(Color.BLACK);setContentView(root);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(i.left,i.top,i.right,Math.max(i.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom));return insets;});
        SampleUi.NavigationBar navigation=new SampleUi.NavigationBar(this,"",this::onBackPressed);
        header=navigation;heading=navigation.title;root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        scroll=new ScrollView(this);body=column();body.setPadding(dp(16),dp(24),dp(16),dp(32));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }
    int dp(int value){return SampleUi.dp(this,value);}
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    void section(String text){TextView label=SampleUi.text(this,text,13,SampleUi.MUTED);label.setPadding(0,dp(24),0,dp(10));body.addView(label);}
    TextView copy(String text){TextView label=SampleUi.text(this,text,15,SampleUi.MUTED);label.setPadding(0,dp(12),0,dp(16));label.setLineSpacing(dp(4),1);body.addView(label);return label;}
    View item(String title,String detail,String icon,Runnable action){
        LinearLayout row=row();row.setPadding(dp(16),dp(14),dp(14),dp(14));row.setMinimumHeight(dp(54));row.setBackground(SampleUi.touch(this,SampleUi.FIELD,16));row.setContentDescription(title);row.setFocusable(true);
        row.addView(SampleUi.icon(this,icon,SampleUi.MUTED),new LinearLayout.LayoutParams(dp(24),dp(24)));
        LinearLayout text=column();text.setPadding(dp(14),0,dp(8),0);text.addView(SampleUi.text(this,title,17,SampleUi.TEXT));if(detail!=null&&!detail.isEmpty())text.addView(SampleUi.text(this,detail,13,SampleUi.MUTED));row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(SampleUi.icon(this,"right",SampleUi.MUTED),new LinearLayout.LayoutParams(dp(20),dp(20)));row.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(2);body.addView(row,p);return row;
    }
    void toast(String message){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    void clipboard(String title,String text){getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(title,text));toast("已复制");}
}
