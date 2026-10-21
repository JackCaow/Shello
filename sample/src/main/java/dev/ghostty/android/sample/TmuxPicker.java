package dev.ghostty.android.sample;

import android.app.*;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** Read-only remote tmux and agent history before opening an explicitly chosen target. */
final class TmuxPicker {
    interface Selection {void open(String tabId);}
    static Dialog show(Activity activity,String profile,Selection action,Runnable finished){return new TmuxPicker(activity,profile,action,finished).show();}
    private final Activity activity;private final String profile;private final Selection action;private final Dialog dialog;
    private final Runnable finished;private boolean completed;
    private void finish(){if(!completed){completed=true;finished.run();}}
    private ScrollView scroll;private LinearLayout rows;private TextView tmux,recent,create;private SshCommand request;private int generation;
    private TmuxPicker(Activity a,String p,Selection selection,Runnable done){activity=a;profile=p;action=selection;finished=done;dialog=new Dialog(a){@Override public void dismiss(){generation++;TmuxPicker.this.cancel();finish();super.dismiss();}};}
    private int dp(int v){return SampleUi.dp(activity,v);}
    private Dialog show(){
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);int p=dp(20);panel.setPadding(p,p,p,p);panel.setBackground(SampleUi.surface(activity,SampleUi.PANEL,28));
        View handle=new View(activity);handle.setBackground(SampleUi.surface(activity,0xff41464b,3));LinearLayout.LayoutParams grip=new LinearLayout.LayoutParams(dp(32),dp(3));grip.gravity=Gravity.CENTER_HORIZONTAL;grip.bottomMargin=dp(24);panel.addView(handle,grip);
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);
        tmux=tab("Tmux",()->load(false));recent=tab("最近",()->load(true));LinearLayout segments=new LinearLayout(activity);segments.setPadding(dp(3),dp(3),dp(3),dp(3));segments.setBackground(SampleUi.surface(activity,0xff222527,24));icon(tmux,"terminal",SampleUi.MUTED);icon(recent,"history",SampleUi.TEXT);segments.addView(tmux);segments.addView(recent);header.addView(segments);header.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1));
        TextView skip=SampleUi.text(activity,"跳过",14,SampleUi.GREEN);skip.setPadding(dp(16),dp(12),dp(16),dp(12));skip.setBackground(SampleUi.touch(activity,0xff222527,24));skip.setTextColor(SampleUi.TEXT);ToolbarIcon skipIcon=new ToolbarIcon("skip",dp(18),SampleUi.MUTED);skipIcon.setBounds(0,0,dp(18),dp(18));skip.setCompoundDrawables(null,null,skipIcon,null);skip.setCompoundDrawablePadding(dp(7));skip.setContentDescription("跳过 tmux，打开 shell");skip.setOnClickListener(v->open(SessionTarget.fresh(false)));header.addView(skip);panel.addView(header);
        scroll=new ScrollView(activity);scroll.setPadding(0,dp(20),0,0);rows=new LinearLayout(activity);rows.setOrientation(LinearLayout.VERTICAL);rows.setBackground(SampleUi.surface(activity,0xff1a1d1e,14));rows.setClipToOutline(true);scroll.setVerticalScrollBarEnabled(false);scroll.addView(rows);panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(300)));
        create=SampleUi.text(activity,"＋ 新建 tmux 会话",17,SampleUi.GREEN);create.setGravity(Gravity.CENTER);create.setPadding(p,p,p,p);create.setContentDescription("新建 tmux 会话");create.setOnClickListener(v->open(SessionTarget.fresh(true)));panel.addView(create);
        dialog.setContentView(panel);Window w=dialog.getWindow();w.setBackgroundDrawableResource(android.R.color.transparent);w.setGravity(Gravity.BOTTOM);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.55f);
        load(false);return dialog;
    }
    private void icon(TextView view,String name,int color){ToolbarIcon drawable=new ToolbarIcon(name,dp(16),color);drawable.setBounds(0,0,dp(16),dp(16));view.setCompoundDrawables(drawable,null,null,null);view.setCompoundDrawablePadding(dp(7));view.setIncludeFontPadding(false);}
    private TextView tab(String title,Runnable click){TextView t=SampleUi.text(activity,title,14,SampleUi.TEXT);t.setContentDescription(title);t.setPadding(dp(12),dp(12),dp(12),dp(12));t.setOnClickListener(v->click.run());return t;}
    private void cancel(){SshCommand old=request;request=null;if(old!=null)new Thread(old::close,"tmux-picker-close").start();}
    private void open(String target){dialog.dismiss();action.open(SessionTarget.id(profile,target));}
    private void load(boolean history){
        cancel();int token=++generation;rows.removeAllViews();tmux.setTextColor(history?SampleUi.MUTED:SampleUi.TEXT);recent.setTextColor(history?SampleUi.TEXT:SampleUi.MUTED);icon(tmux,"terminal",history?SampleUi.MUTED:SampleUi.TEXT);icon(recent,"history",history?SampleUi.TEXT:SampleUi.MUTED);tmux.setBackground(SampleUi.surface(activity,history?0x00000000:SampleUi.PANEL,22));recent.setBackground(SampleUi.surface(activity,history?SampleUi.PANEL:0x00000000,22));create.setVisibility(history?View.GONE:View.VISIBLE);scroll.getLayoutParams().height=dp(history?311:300);scroll.requestLayout();create.setEnabled(true);create.setAlpha(1);
        if(history&&!AgentHub.get(activity).enabled(profile)){message("安装主机助手后，可读取 Claude Code 和 Codex 的历史会话。");TextView setup=tab("设置 Hook",()->{dialog.dismiss();activity.startActivity(new Intent(activity,SettingsActivity.class).putExtra("page","hook"));});rows.addView(setup);return;}
        message("正在读取远端会话…");SshCommand current=new SshCommand();request=current;
        new Thread(()->{try(current){String command=history?AgentHub.COMMAND+"recent":SshConnectionSetup.TMUX_LOOKUP+"\"$T\" list-sessions -F '#{session_name}\t#{session_windows}\t#{session_attached}' 2>/dev/null || { r=$?; [ $r = 1 ] || exit $r; }";
            String output=current.run(activity,profile,command,null);List<String[]> entries=new ArrayList<>();
            JSONObject historyData=history?new JSONObject(output):null;
            if(!history)for(String line:output.split("\n")){String[] fields=line.split("\t",3);if(fields.length==3)entries.add(new String[]{fields[0],fields[1]+" 个窗口"+(fields[2].equals("0")?"":" · 活跃")});}
            activity.runOnUiThread(()->{if(activity.isDestroyed()||activity.isFinishing()||token!=generation)return;rows.removeAllViews();
                if(history)recentRows(historyData);else for(String[] e:entries){try{SessionTarget.validate("="+e[0]);}catch(IllegalArgumentException invalid){continue;}row(e[0],e[1]);}
                if(rows.getChildCount()==0)message(history?"暂无 Claude Code 或 Codex 历史会话。":"暂无远端会话，可以新建或跳过。");if(!dialog.isShowing()){dialog.show();dialog.getWindow().setLayout(-1,-2);finish();}});
        }catch(Exception error){activity.runOnUiThread(()->{if(activity.isDestroyed()||activity.isFinishing()||token!=generation)return;if(!dialog.isShowing()){finish();if(current.authenticated()){rows.removeAllViews();message("SSH 已连接，服务器暂未提供可用的 tmux。可以跳过并打开 shell。");create.setEnabled(false);create.setAlpha(.4f);dialog.show();dialog.getWindow().setLayout(-1,-2);}else Toast.makeText(activity,"连接失败，请检查网络、认证与主机指纹。",Toast.LENGTH_LONG).show();return;}rows.removeAllViews();message(history?"无法读取智能体历史，请检查连接并在设置中更新主机助手。":"无法读取 tmux 会话，请检查网络、认证或 tmux 安装状态。");TextView retry=tab("重试",()->load(history));rows.addView(retry);create.setEnabled(false);create.setAlpha(.4f);});}},"tmux-picker").start();
    }
    private void recentRows(JSONObject data){
        JSONArray sessions=data.optJSONArray("sessions");if(sessions==null)return;
        Map<String,List<JSONObject>> groups=new LinkedHashMap<>();
        for(int i=0;i<sessions.length();i++){JSONObject e=sessions.optJSONObject(i);if(e==null||!SessionTarget.agent("agent:"+e.optString("provider")+":"+e.optString("id")))continue;groups.computeIfAbsent(e.optString("cwd"),k->new ArrayList<>()).add(e);}
        for(List<JSONObject> histories:groups.values()){
            List<JSONObject> group=new ArrayList<>();Set<String> providers=new HashSet<>();for(JSONObject e:histories)if(providers.add(e.optString("provider")))group.add(e);
            JSONObject first=group.get(0);LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(16),dp(10),dp(14),dp(10));row.setBackground(SampleUi.touch(activity,0xff1a1d1e,0));
            LinearLayout labels=new LinearLayout(activity);labels.setOrientation(LinearLayout.VERTICAL);
            TextView title=SampleUi.text(activity,first.optString("project"),16,SampleUi.TEXT);title.setSingleLine();title.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(title);
            String cwd=first.optString("cwd"),parent=cwd.substring(0,Math.max(0,cwd.lastIndexOf('/'))),home=data.optString("home");if(!home.isEmpty()&&(parent.equals(home)||parent.startsWith(home+"/")))parent="~"+parent.substring(home.length());
            TextView detail=SampleUi.text(activity,parent+" · "+age(first,data),13,SampleUi.MUTED);detail.setSingleLine();detail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);labels.addView(detail);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            String provider=first.optString("provider").equals("claude")?"Claude":"Codex";
            FrameLayout badge=new FrameLayout(activity);ImageView logo=new ImageView(activity);logo.setImageResource(first.optString("provider").equals("claude")?R.drawable.agent_claude:R.drawable.agent_openai);FrameLayout.LayoutParams iconLp=new FrameLayout.LayoutParams(dp(23),dp(23),Gravity.CENTER);badge.addView(logo,iconLp);row.addView(badge,new LinearLayout.LayoutParams(dp(32),dp(36)));
            if(group.size()>1){TextView count=SampleUi.text(activity,"+"+(group.size()-1),10,SampleUi.MUTED);count.setTypeface(null,android.graphics.Typeface.BOLD);count.setGravity(Gravity.CENTER);count.setIncludeFontPadding(false);count.setBackground(SampleUi.surface(activity,0xff101214,9));FrameLayout.LayoutParams countLp=new FrameLayout.LayoutParams(dp(18),dp(18),Gravity.RIGHT|Gravity.BOTTOM);badge.addView(count,countLp);}
            row.setContentDescription("恢复 "+provider+" 会话 "+first.optString("project"));row.setOnClickListener(v->resume(first));
            if(group.size()>1){badge.setContentDescription("选择 "+first.optString("project")+" 的历史会话");badge.setOnClickListener(v->{rows.removeAllViews();TextView back=tab("‹ 返回项目",()->{rows.removeAllViews();recentRows(data);});rows.addView(back);for(JSONObject e:group){TextView choice=tab((e.optString("provider").equals("claude")?"Claude Code":"Codex")+" · "+age(e,data)+" · "+e.optString("id").substring(0,8),()->resume(e));rows.addView(choice);}});}
            if(rows.getChildCount()>0){View divider=new View(activity);divider.setBackgroundColor(0xff242728);rows.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));}rows.addView(row,new LinearLayout.LayoutParams(-1,dp(66)));
        }
    }
    private String age(JSONObject e,JSONObject data){long seconds=Math.max(0,(long)(data.optDouble("now",System.currentTimeMillis()/1000d)-e.optDouble("updated",0)));return seconds<60?"刚刚":seconds<3600?seconds/60+" 分钟前":seconds<86400?seconds/3600+" 小时前":seconds/86400+" 天前";}
    private void resume(JSONObject e){String target="agent:"+e.optString("provider")+":"+e.optString("id");String id=SessionTarget.id(profile,target);activity.getSharedPreferences("terminal_titles",0).edit().putString(id,e.optString("project")).apply();open(target);}
    private void message(String text){TextView t=SampleUi.text(activity,text,15,SampleUi.MUTED);t.setPadding(dp(12),dp(18),dp(12),dp(18));rows.addView(t);}
    private void row(String name,String detail){LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(20),dp(16),dp(20),dp(16));row.setBackground(SampleUi.touch(activity,SampleUi.FIELD,14));row.setContentDescription("打开 tmux "+name);
        TextView title=SampleUi.text(activity,name,18,SampleUi.TEXT);title.setSingleLine();title.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(title);TextView copy=SampleUi.text(activity,detail,13,SampleUi.MUTED);copy.setSingleLine();copy.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(copy);row.setOnClickListener(v->open("="+name));rows.addView(row,new LinearLayout.LayoutParams(-1,dp(74)));}
}
