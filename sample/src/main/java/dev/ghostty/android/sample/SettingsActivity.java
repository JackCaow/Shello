package dev.ghostty.android.sample;

import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.widget.*;

/** Settings are stored independently of Activities and applied when a terminal resumes. */
public final class SettingsActivity extends ScreenActivity {
    private SharedPreferences prefs;private String page="";private boolean working;
    @Override public void onCreate(Bundle state){super.onCreate(state);prefs=getSharedPreferences("appearance",0);show(state==null?getIntent().getStringExtra("page"):state.getString("page"));}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("page",page);super.onSaveInstanceState(out);}
    private void show(String value){page=value==null?"":value;body.removeAllViews();
        switch(page){
            case "hook":hook();break;
            case "notifications":heading.setText("通知");toggle("Agent 事件通知","agent","notifications",true);copy("切后台后，活动 SSH 会话会继续获取 Hook 事件。没有活动连接、强行停止应用或系统断网时，事件将在重新打开后同步。");item("系统通知设置","查看通知权限与通知渠道","bell",()->startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName())));if(Build.VERSION.SDK_INT>=33)item("请求通知权限",null,"bell",()->requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},91));break;
            case "font":heading.setText("字体与大小");copy("使用 Android 等宽字体，保留中文和 emoji 字体回退。");section("字号 · "+prefs.getInt("fontSize",14)+" sp");for(int size:new int[]{10,12,14,16,18,20,24})item(size+" sp",prefs.getInt("fontSize",14)==size?"已选中":null,"font",()->{prefs.edit().putInt("fontSize",size).apply();show("font");});break;
            case "theme":heading.setText("主题");copy("设置终端的默认前景和背景色。程序主动指定的颜色保持有效。");for(String theme:new String[]{"Moshi","黑色","浅色"})item(theme,prefs.getString("theme","Moshi").equals(theme)?"已选中":null,"settings",()->{prefs.edit().putString("theme",theme).apply();show("theme");});break;
            case "advanced":heading.setText("高级");toggle("显示连接的 Hook 状态","appearance","hookStatus",true);toggle("显示首页功能引导","appearance","guide",true);copy("SSH 会话由前台服务托管；tmux 会话在远端保留。关闭缩略图会断开本机连接，不会终止远端 tmux。");item("电池与后台运行","查看厂商省电限制","settings",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName()))));break;
            case "toolbar":heading.setText("工具栏");for(String[] tool:new String[][]{{"history","输入历史"},{"paste","粘贴"},{"chat","文本草稿"},{"pad","方向键浮板"}})toggle(tool[1],"appearance","tool_"+tool[0],true);copy("Ctrl、Esc、Tab 和键盘按钮始终保留。");break;
            case "gestures":heading.setText("手势");toggle("长按选择终端文本","appearance","selection",true);copy("上下滑动查看滚动历史；长按文本后拖动选择手柄；首页长按会话卡片可排序或关闭。");break;
            case "chat":heading.setText("文本草稿");toggle("打开终端时使用文本草稿","appearance","draftDefault",false);copy("先编辑、多行输入再发送。Agent 的提问与审批在收件箱中逐条回应。");break;
            case "shortcuts":heading.setText("快捷键");copy("Ctrl：点按后与下一个字符组合。\nEsc / Tab：发送终端按键。\n方向键浮板：方向键、回车、退格和清屏。\n发送按钮：点按发送并回车，长按仅发送文本。");break;
            default:heading.setText("设置");section("终端");item("主题",prefs.getString("theme","Moshi"),"settings",()->show("theme"));item("字体与大小","等宽 · "+prefs.getInt("fontSize",14)+" sp","font",()->show("font"));item("高级",null,"toolbox",()->show("advanced"));section("代理");item("Hook",null,"hook",()->show("hook"));item("通知",null,"bell",()->show("notifications"));item("文本草稿",null,"chat",()->show("chat"));section("输入");item("工具栏",null,"toolbox",()->show("toolbar"));item("快捷键",null,"keyboard",()->show("shortcuts"));item("手势",null,"pad",()->show("gestures"));section("关于");item("Ghostty Android","开源 Android 终端库 · API 27+","server",()->startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/JackCaow/ghostty-android"))));break;
        }
    }
    private void toggle(String title,String group,String key,boolean fallback){SharedPreferences source=getSharedPreferences(group,0);SampleUi.Toggle toggle=new SampleUi.Toggle(this);toggle.setText(title);toggle.setContentDescription(title);toggle.setChecked(source.getBoolean(key,fallback));toggle.setOnCheckedChangeListener((v,checked)->source.edit().putBoolean(key,checked).apply());body.addView(toggle);}
    private void hook(){heading.setText("Hook");copy("在保存的 SSH 主机上安装 Claude Code Hook，接收审批、提问与完成事件。事件通过 SSH 同步，不需要额外开放端口。");copy("安装会写入 ~/.local/state/ghostty-android，并备份后合并 ~/.claude/settings.json。已有 Hook 会保留；卸载只移除本应用的 Hook。");section("SSH 主机");AgentHub hub=AgentHub.get(this);java.util.List<SshProfileStore.Entry> profiles=new SshProfileStore(this).list();
        if(profiles.isEmpty())item("先添加 SSH 连接",null,"plus",()->startActivity(new Intent(this,SshDiscoveryActivity.class)));
        for(SshProfileStore.Entry p:profiles){boolean enabled=hub.enabled(p.id);item(p.name,enabled?hub.states.getOrDefault(p.id,"已启用") :"未安装","server",()->{if(working)return;SampleUi.confirm(this,enabled?"卸载 Hook？":"安装 Hook？",p.user+"@"+p.host+"\n"+(enabled?"只移除本应用注册的 Claude Code Hook。":"保留已有配置，安装后请新开 Claude Code 会话。"),null,"取消",enabled?"卸载":"安装",false,null,()->{working=true;toast("正在处理…");hub.configure(p.id,!enabled,(ok,message)->{working=false;if(isDestroyed())return;toast(message);show("hook");});});});}
        for(SshProfileStore.Entry p:profiles)if(hub.enabled(p.id))item("更新主机助手 · "+p.name,"更新最近会话读取与 Hook，保留已有设置","hook",()->{if(working)return;working=true;toast("正在更新…");hub.configure(p.id,true,(ok,message)->{working=false;if(isDestroyed())return;toast(message);show("hook");});});
        section("显示");toggle("显示连接的 Hook 状态","appearance","hookStatus",true);
    }
    @Override public void onBackPressed(){if(!page.isEmpty())show("");else super.onBackPressed();}
}
