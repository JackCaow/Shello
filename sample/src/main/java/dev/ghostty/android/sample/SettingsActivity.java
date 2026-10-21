package dev.ghostty.android.sample;

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

/** Grouped settings pages. Stored preferences are applied when a terminal resumes. */
public final class SettingsActivity extends ScreenActivity {
    private SharedPreferences prefs;
    private String page="";
    private boolean working;
    private int homeScroll;
    private LinearLayout group,toolbarPreview;
    private static final int CARD=0xff111315, DIVIDER=0xff222426;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences("appearance",0);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setId(android.R.id.list);
        homeScroll=state==null?0:state.getInt("homeScroll");
        show(state==null?getIntent().getStringExtra("page"):state.getString("page"));
        if(state!=null){int y=state.getInt("scroll");scroll.post(()->scroll.scrollTo(0,y));}
    }
    @Override protected void onSaveInstanceState(Bundle out){
        out.putString("page",page);out.putInt("homeScroll",homeScroll);out.putInt("scroll",scroll.getScrollY());
        super.onSaveInstanceState(out);
    }
    private TextView label(String value,int size,int color){
        TextView text=SampleUi.text(this,value,size,color);
        text.setIncludeFontPadding(false);text.setLineSpacing(dp(3),1);return text;
    }
    private void open(String next){if(page.isEmpty())homeScroll=scroll.getScrollY();show(next);}
    private void show(String value){
        page=value==null?"":value;group=null;toolbarPreview=null;body.removeAllViews();
        switch(page){
            case "hook":hook();break;
            case "notifications":notifications();break;
            case "font":font();break;
            case "theme":theme();break;
            case "advanced":advanced();break;
            case "toolbar":toolbar();break;
            case "gestures":gestures();break;
            case "chat":chat();break;
            case "shortcuts":shortcuts();break;
            default:home();break;
        }
        scroll.scrollTo(0,0);
        scroll.post(()->scroll.scrollTo(0,page.isEmpty()?homeScroll:0));
    }
    @Override void section(String title){
        group=null;
        TextView caption=label(title,13,SampleUi.MUTED);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=body.getChildCount()==0?0:dp(24);p.bottomMargin=dp(10);
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
    private LinearLayout settingRow(String title,String subtitle,String icon,View accessory,Runnable action){
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
    private View trailing(String value,boolean chevron){
        LinearLayout end=row();
        if(value!=null){TextView text=label(value,16,SampleUi.MUTED);text.setSingleLine(true);text.setEllipsize(TextUtils.TruncateAt.END);
            text.setMaxWidth(dp(getResources().getConfiguration().fontScale>1.2f?100:146));end.addView(text);}
        if(chevron){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(18),dp(18));if(value!=null)p.leftMargin=dp(10);end.addView(SampleUi.icon(this,"right",SampleUi.MUTED),p);}
        return end;
    }
    private void navigation(String title,String value,String icon,String target){settingRow(title,null,icon,trailing(value,true),()->open(target));}
    @Override View item(String title,String detail,String icon,Runnable action){return settingRow(title,detail,icon,action==null?null:trailing(null,true),action);}
    private void toggle(String title,String subtitle,String icon,String preferenceGroup,String key,boolean fallback,Runnable changed){
        SharedPreferences source=getSharedPreferences(preferenceGroup,0);
        SettingSwitch toggle=new SettingSwitch(title,subtitle,icon);
        toggle.setChecked(source.getBoolean(key,fallback));
        toggle.setOnCheckedChangeListener((v,checked)->{source.edit().putBoolean(key,checked).apply();if(changed!=null)changed.run();});
        addRow(toggle);
    }
    private void home(){
        heading.setText("设置");section("终端");
        navigation("主题",ThemePreferences.current(prefs),"palette","theme");
        navigation("字体与大小",prefs.getInt("fontSize",14)+" sp","font","font");navigation("高级",null,"sliders","advanced");
        section("代理");navigation("Hook",null,"hook","hook");navigation("通知",null,"bell","notifications");navigation("文本草稿",null,"chat","chat");
        section("输入");navigation("工具栏",null,"toolbox","toolbar");navigation("快捷键",null,"keyboard","shortcuts");navigation("手势",null,"pad","gestures");
        section("关于");item("Shello","开源 Android 终端库 · API 27+","server",()->startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/JackCaow/Shello"))));
    }
    private void notifications(){
        heading.setText("通知");
        toggle("Agent 事件通知",null,"bell","agent","notifications",true,null);
        copy("接收来自 Agent Hook 的审批、提问和完成通知。");
        section("权限");item("系统通知设置","查看通知权限与通知渠道","bell",()->startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName())));
        if(Build.VERSION.SDK_INT>=33)item("请求通知权限",null,"bell",()->requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},91));
        section("Agent 更新");item("安装 Hook","在 SSH 主机上安装，接收 Agent 事件","hook",()->open("hook"));
        copy("切后台后，活动 SSH 会话会继续获取事件。没有活动连接、强行停止应用或系统断网时，事件将在重新打开后同步。");
    }
    private void font(){
        heading.setText("字体与大小");section("终端文字");
        LinearLayout stepper=row();stepper.setBackground(SampleUi.surface(this,0xff222426,24));
        TextView number=label("",15,SampleUi.TEXT);number.setGravity(Gravity.CENTER);number.setSingleLine(true);number.setMinWidth(dp(48));
        TextView preview=label("",14,SampleUi.TEXT);preview.setTypeface(Typeface.MONOSPACE);
        View minus=SampleUi.header(this,"minus","减小终端字号",SampleUi.TEXT,()->{});
        View plus=SampleUi.header(this,"plus","增大终端字号",SampleUi.TEXT,()->{});
        stepper.addView(minus,new LinearLayout.LayoutParams(dp(40),dp(48)));
        stepper.addView(number,new LinearLayout.LayoutParams(-2,dp(48)));
        stepper.addView(plus,new LinearLayout.LayoutParams(dp(40),dp(48)));
        Runnable refresh=()->{int size=prefs.getInt("fontSize",14);number.setText(size+" sp");number.setContentDescription("终端字号 "+size);preview.setTextSize(size);minus.setEnabled(size>10);minus.setAlpha(size>10?1:.3f);plus.setEnabled(size<24);plus.setAlpha(size<24?1:.3f);};
        minus.setOnClickListener(v->{prefs.edit().putInt("fontSize",Math.max(10,prefs.getInt("fontSize",14)-1)).apply();refresh.run();});
        plus.setOnClickListener(v->{prefs.edit().putInt("fontSize",Math.min(24,prefs.getInt("fontSize",14)+1)).apply();refresh.run();});
        settingRow("字体大小",null,"font",stepper,null);
        settingRow("字体",null,"font",trailing("系统等宽",false),null);
        copy("支持中文和 emoji 字体回退。字号范围为 10–24 sp。");
        section("预览");preview.setText("$ echo 'Hello, 终端'\nHello, 终端\n❯ 中文输入 👩🏽‍💻 🚀");preview.setPadding(dp(16),dp(20),dp(16),dp(20));preview.setMinHeight(dp(140));preview.setBackground(SampleUi.surface(this,0xff1e1e1e,12));body.addView(preview,new LinearLayout.LayoutParams(-1,-2));refresh.run();
    }
    private void theme(){
        heading.setText("主题");section("终端主题");String selected=ThemePreferences.current(prefs);
        for(String name:new String[]{"Shello","黑色","浅色"}){
            ImageView check=SampleUi.icon(this,"check",SampleUi.GREEN);check.setMinimumWidth(dp(20));check.setMinimumHeight(dp(20));check.setVisibility(selected.equals(name)?View.VISIBLE:View.INVISIBLE);
            LinearLayout row=settingRow(name,null,"palette",check,()->{prefs.edit().putString("theme",name).apply();show("theme");});row.setSelected(selected.equals(name));row.setContentDescription(name+(selected.equals(name)?"，已选中":""));
        }
        copy("设置终端的默认前景和背景色。程序主动指定的颜色保持有效。");
        section("预览");TextView preview=label("$ echo 'Shello'\nShello\n❯ 中文 · Aa Bb 0123",14,selected.equals("浅色")?0xff202124:0xffd4d4d4);preview.setTypeface(Typeface.MONOSPACE);preview.setPadding(dp(16),dp(20),dp(16),dp(20));preview.setMinHeight(dp(140));preview.setBackground(SampleUi.surface(this,selected.equals("浅色")?0xfff5f5f5:selected.equals("黑色")?Color.BLACK:0xff1e1e1e,12));body.addView(preview,new LinearLayout.LayoutParams(-1,-2));
    }
    private void advanced(){
        heading.setText("高级");section("首页");
        toggle("显示连接的 Hook 状态","在保存的连接上显示状态点",null,"appearance","hookStatus",true,null);
        toggle("显示功能引导","在首页显示功能提示",null,"appearance","guide",true,null);
        section("后台运行");item("电池与后台运行","查看厂商省电限制","settings",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName()))));
        copy("SSH 会话在后台保持连接，tmux 会话在远端保留。关闭缩略图会断开本机连接，不会终止远端 tmux。");
    }
    private void toolbar(){
        heading.setText("工具栏");section("预览");
        LinearLayout canvas=column();canvas.setPadding(dp(8),dp(8),dp(8),dp(8));
        GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff063a18,0xff164d48});gradient.setCornerRadius(dp(12));canvas.setBackground(gradient);
        TextView caption=label("工具栏实时预览",16,SampleUi.MUTED);caption.setGravity(Gravity.CENTER);canvas.addView(caption,new LinearLayout.LayoutParams(-1,0,1));
        toolbarPreview=row();toolbarPreview.setPadding(dp(6),dp(6),dp(6),dp(6));toolbarPreview.setBackground(SampleUi.surface(this,Color.BLACK,32));toolbarPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        canvas.addView(toolbarPreview,new LinearLayout.LayoutParams(-1,dp(52)));body.addView(canvas,new LinearLayout.LayoutParams(-1,dp(276)));refreshToolbar();
        section("显示按钮");
        for(String[] tool:new String[][]{{"pad","方向键浮板"},{"history","输入历史"},{"paste","粘贴"},{"chat","文本草稿"}})
            toggle(tool[1],null,null,"appearance","tool_"+tool[0],true,this::refreshToolbar);
        copy("Ctrl、Esc、Tab 和键盘按钮始终保留。更改会在返回终端后生效。");
    }
    private void refreshToolbar(){
        if(toolbarPreview==null)return;toolbarPreview.removeAllViews();
        for(String tool:new String[]{"Ctrl","Esc","Tab","pad","history","paste","chat","keyboard"}){
            boolean fixed=tool.equals("Ctrl")||tool.equals("Esc")||tool.equals("Tab")||tool.equals("keyboard");
            if(!fixed&&!prefs.getBoolean("tool_"+tool,true))continue;
            View view;
            if(tool.length()>1&&Character.isUpperCase(tool.charAt(0))){TextView key=label(tool,13,SampleUi.TEXT);key.setGravity(Gravity.CENTER);key.setTypeface(Typeface.MONOSPACE);key.setSingleLine(true);key.setAutoSizeTextTypeUniformWithConfiguration(9,13,1,android.util.TypedValue.COMPLEX_UNIT_DIP);view=key;}
            else{ImageView icon=SampleUi.icon(this,tool.equals("history")?"panel":tool,SampleUi.TEXT);icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);icon.setPadding(dp(8),dp(8),dp(8),dp(8));view=icon;}
            view.setBackground(SampleUi.surface(this,CARD,24));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(36),1);p.leftMargin=dp(2);p.rightMargin=dp(2);toolbarPreview.addView(view,p);
        }
    }
    private void gestures(){
        heading.setText("手势");section("终端");toggle("长按选择文本",null,null,"appearance","selection",true,null);
        item("上下滑动","查看终端滚动历史","history",null);item("拖动选择手柄","调整文本选区","pad",null);
        section("首页");item("长按会话卡片","重新排序、关闭或全部关闭","reorder",null);
    }
    private void chat(){
        heading.setText("文本草稿");section("输入方式");toggle("默认使用文本草稿","打开终端时，先编辑再发送","chat","appearance","draftDefault",false,null);
        copy("支持多行输入。Agent 的提问与审批在收件箱中逐条回应。");
        section("发送");item("点按发送","发送文本并回车","send",null);item("长按发送","只发送文本，不附加回车","edit",null);
    }
    private void shortcuts(){
        heading.setText("快捷键");section("终端按键");item("Ctrl","点按后与下一个字符组合","keyboard",null);item("Esc / Tab","发送对应的终端按键","keyboard",null);
        section("方向键浮板");item("方向键","上、下、左、右移动","pad",null);item("回车与退格","确认输入或删除字符","return",null);item("清屏","发送清屏快捷键","clear",null);
        section("文本草稿");item("点按发送","发送文本并回车","send",null);item("长按发送","只发送文本","edit",null);
    }
    private void hook(){
        heading.setText("Hook");section("安装");
        TextView intro=copy("在 SSH 主机上安装 Claude Code Hook，接收审批、提问与完成事件。");intro.setTextColor(SampleUi.TEXT);intro.setTextSize(16);
        section("SSH 主机");AgentHub hub=AgentHub.get(this);java.util.List<SshProfileStore.Entry> profiles=new SshProfileStore(this).list();
        if(profiles.isEmpty())item("先添加 SSH 连接",null,"plus",()->startActivity(new Intent(this,SshDiscoveryActivity.class)));
        for(SshProfileStore.Entry p:profiles){boolean enabled=hub.enabled(p.id);item(p.name,enabled?hub.states.getOrDefault(p.id,"已启用"):"未安装","server",()->{
            if(working)return;SampleUi.confirm(this,enabled?"卸载 Hook？":"安装 Hook？",p.user+"@"+p.host+"\n"+(enabled?"只移除本应用注册的 Claude Code Hook。":"保留已有配置，安装后请新开 Claude Code 会话。"),null,"取消",enabled?"卸载":"安装",false,null,()->{
                working=true;toast("正在处理…");hub.configure(p.id,!enabled,(ok,message)->{working=false;if(isDestroyed())return;toast(message);show("hook");});
            });
        });}
        copy("通过 SSH 安装和同步，无需额外开放端口。安装会备份并合并现有 Claude Code 配置；卸载只移除本应用的 Hook。");
        boolean updates=false;for(SshProfileStore.Entry p:profiles)if(hub.enabled(p.id)){
            if(!updates){section("更新");updates=true;}
            item(p.name,"更新主机助手与最近会话读取","hook",()->{if(working)return;working=true;toast("正在更新…");hub.configure(p.id,true,(ok,message)->{working=false;if(isDestroyed())return;toast(message);show("hook");});});
        }
        section("Hook 状态");toggle("显示 Hook 状态","在保存的连接上显示状态点",null,"appearance","hookStatus",true,null);
    }
    @Override public void onBackPressed(){if(!page.isEmpty())show("");else super.onBackPressed();}

    /** One accessible switch row, with text wrapping clear of the trailing thumb. */
    private final class SettingSwitch extends CheckBox {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        SettingSwitch(String title,String subtitle,String icon){
            super(SettingsActivity.this);setButtonDrawable((android.graphics.drawable.Drawable)null);
            setTextSize(16);setTextColor(SampleUi.TEXT);setIncludeFontPadding(false);setGravity(Gravity.CENTER_VERTICAL);setLineSpacing(dp(3),1);
            setPadding(dp(16),dp(12),dp(80),dp(12));setMinHeight(dp(48));setBackground(SampleUi.touch(SettingsActivity.this,Color.TRANSPARENT,0));
            if(subtitle==null)setText(title);else{SpannableString text=new SpannableString(title+"\n"+subtitle);text.setSpan(new ForegroundColorSpan(SampleUi.MUTED),title.length()+1,text.length(),0);text.setSpan(new AbsoluteSizeSpan(Math.round(14*getResources().getDisplayMetrics().scaledDensity)),title.length()+1,text.length(),0);setText(text);}
            setContentDescription(title+(subtitle==null?"":"，"+subtitle));
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
