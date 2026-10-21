package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.os.Bundle;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Inbox of actual remote events, with explicit per-request decisions. */
public final class AgentActivity extends ScreenActivity {
    private AgentHub hub;private String selected;private boolean sending;private Map<String,EditText> answers=new LinkedHashMap<>();
    private android.app.Dialog usageDialog;private LinearLayout usageContent;private String usageProvider;
    private boolean requestWasPending;private AgentHub.Event shownRequest;
    private String requestedEvent,requestedSession;
    private final Runnable update=()->{restoreRequested();if(selected==null)render();else if(requestWasPending&&shownRequest!=null&&!shownRequest.pending())detail(shownRequest);refreshSessionDetails();if(usageDialog!=null&&usageDialog.isShowing()&&usageContent!=null)renderUsageContent(usageContent);};
    private int lastPending=-1;private long lastMinute;
    private final Runnable tick=new Runnable(){public void run(){
        if(selected!=null&&requestWasPending&&shownRequest!=null&&!shownRequest.pending())detail(shownRequest);
        long minute=System.currentTimeMillis()/60000;int count=hub.pending(null);if(selected==null&&(minute!=lastMinute||count!=lastPending)){renderCards();refreshSessionDetails();lastMinute=minute;lastPending=count;}
        hub.main.postDelayed(this,1000);
    }};
    @Override public void onCreate(Bundle state){super.onCreate(state);hub=AgentHub.get(this);requestedEvent=state==null?getIntent().getStringExtra("event"):state.getString("event");requestedSession=state==null?null:state.getString("session");heading.setText(tr(R.string.agent_inbox));render();if(state==null)restoreRequested();
        if(state!=null&&state.getString("event")!=null)for(AgentHub.Event event:hub.snapshot())if(event.id().equals(state.getString("event"))){
            detail(event);Bundle values=state.getBundle("answers");if(values!=null)for(Map.Entry<String,EditText> entry:answers.entrySet())entry.getValue().setText(values.getString(entry.getKey(),""));break;}
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);requestedEvent=intent.getStringExtra("event");restoreRequested();}
    private void restoreRequested(){
        if(requestedEvent!=null)for(AgentHub.Event event:hub.snapshot())if(requestedEvent.equals(event.id())){requestedEvent=null;if(sessionDialog!=null)sessionDialog.dismiss();detail(event);break;}
        if(requestedSession!=null)for(AgentInbox.Session session:hub.sessions())if(requestedSession.equals(session.key)){requestedSession=null;sessionDetails(session.key);break;}
        if(selected!=null&&shownRequest!=null)for(AgentHub.Event event:hub.snapshot())if(selected.equals(event.id())&&event!=shownRequest){
            if(event.pending()!=shownRequest.pending()||!event.data.optString("status").equals(shownRequest.data.optString("status"))){Map<String,String> values=new LinkedHashMap<>();for(Map.Entry<String,EditText> e:answers.entrySet())values.put(e.getKey(),e.getValue().getText().toString());detail(event);for(Map.Entry<String,EditText> e:answers.entrySet())if(values.containsKey(e.getKey()))e.getValue().setText(values.get(e.getKey()));}break;
        }
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putString("event",selected);if(sessionDialog!=null&&sessionDialog.isShowing())state.putString("session",dialogSession);Bundle values=new Bundle();for(Map.Entry<String,EditText> e:answers.entrySet())values.putString(e.getKey(),e.getValue().getText().toString());state.putBundle("answers",values);super.onSaveInstanceState(state);}
    @Override protected void onStart(){super.onStart();if(hub.preferences.getBoolean("keepScreenOn",false))getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);hub.attach(update);hub.main.post(tick);}
    @Override protected void onStop(){getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);hub.main.removeCallbacks(tick);hub.detach(update);super.onStop();}
    @Override void section(String text){
        TextView label=SampleUi.text(this,text,13,SampleUi.MUTED);
        label.setPadding(0,body.getChildCount()==0?0:dp(16),0,dp(12));
        label.setLineSpacing(dp(4),1);body.addView(label);
    }
    @Override View item(String title,String detail,String icon,Runnable action){
        LinearLayout card=row();card.setPadding(dp(16),dp(18),dp(16),dp(18));
        card.setMinimumHeight(dp(64));card.setBackground(SampleUi.touch(this,SampleUi.FIELD,16));
        card.setContentDescription(title);card.setFocusable(true);
        card.addView(SampleUi.icon(this,icon,SampleUi.MUTED),new LinearLayout.LayoutParams(dp(24),dp(24)));
        LinearLayout text=column();text.setPadding(dp(14),0,dp(12),0);
        TextView label=SampleUi.text(this,title,17,SampleUi.TEXT);label.setLineSpacing(dp(3),1);text.addView(label);
        if(detail!=null&&!detail.isEmpty()){
            TextView subtitle=SampleUi.text(this,detail,13,SampleUi.MUTED);subtitle.setLineSpacing(dp(3),1);
            LinearLayout.LayoutParams caption=new LinearLayout.LayoutParams(-1,-2);caption.topMargin=dp(6);text.addView(subtitle,caption);
        }
        card.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        card.addView(SampleUi.icon(this,"right",SampleUi.MUTED),new LinearLayout.LayoutParams(dp(20),dp(20)));
        card.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin=dp(12);body.addView(card,p);return card;
    }
    private final java.util.List<android.app.Dialog> dialogs=new ArrayList<>();private android.app.Dialog filterDialog;
    @Override protected void onDestroy(){for(android.app.Dialog d:new ArrayList<>(dialogs))d.dismiss();super.onDestroy();}
    private LinearLayout cards,usageBar;private EditText search;private String provider,hostFilter,projectFilter;
    private android.app.Dialog sessionDialog;private LinearLayout sessionContent;private String dialogSession;private boolean built;
    View contentRoot(){return sessionDialog!=null&&sessionDialog.isShowing()?sessionDialog.getWindow().getDecorView():body;}
    private String label(String en,String zh){return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?zh:en;}
    private void actionGroup(){View gap=new View(this);body.addView(gap,new LinearLayout.LayoutParams(1,dp(12)));}
    private boolean requestHeader;
    private void inboxHeader(){requestHeader=false;header.removeAllViews();header.setMinimumHeight(dp(72));header.setPadding(dp(16),0,dp(16),0);
        View spacer=new View(this);header.addView(spacer,new LinearLayout.LayoutParams(0,1,1));
        header.addView(SampleUi.header(this,"home",label("Home","首页"),SampleUi.TEXT,this::finish),new LinearLayout.LayoutParams(dp(48),dp(48)));
        header.addView(SampleUi.header(this,"settings",tr(R.string.settings),SampleUi.TEXT,()->startActivity(new Intent(this,SettingsActivity.class).putExtra("page","hook"))),new LinearLayout.LayoutParams(dp(48),dp(48)));
    }
    private void buildInbox(){
        if(built)return;built=true;inboxHeader();
        android.content.SharedPreferences prefs=hub.preferences;provider=prefs.getString("filter_provider",null);hostFilter=prefs.getString("filter_host",null);projectFilter=prefs.getString("filter_project",null);
        LinearLayout controls=row();controls.setPadding(dp(16),dp(8),dp(16),dp(12));search=new EditText(this);SampleUi.field(search);search.setSingleLine();search.setTextSize(15);search.setHint(label("Search agents, hosts, projects","搜索智能体、主机、项目"));search.setContentDescription("agent_search");search.setText(prefs.getString("search",""));
        controls.addView(search,new LinearLayout.LayoutParams(0,dp(48),1));
        View filter=SampleUi.header(this,"filter",label("Filters","筛选"),SampleUi.TEXT,()->filters());filter.setContentDescription("agent_filters");filter.setBackground(SampleUi.touch(this,SampleUi.FIELD,24));LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(dp(44),dp(44));fp.leftMargin=dp(8);controls.addView(filter,fp);
        View usage=SampleUi.header(this,"pulse",label("Usages","用量"),SampleUi.TEXT,this::showUsages);usage.setContentDescription("agent_usages");usage.setBackground(SampleUi.touch(this,SampleUi.FIELD,24));LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(dp(44),dp(44));up.leftMargin=dp(8);controls.addView(usage,up);
        root.addView(AdaptiveLayout.centered(this,controls,640,null),1);body.setPadding(dp(16),0,dp(16),dp(16));cards=column();body.addView(cards);
        usageBar=row();usageBar.setMinimumHeight(dp(52));usageBar.setPadding(dp(8),0,dp(8),0);usageBar.setBackground(SampleUi.surface(this,SampleUi.FIELD,28));LinearLayout.LayoutParams foot=new LinearLayout.LayoutParams(-1,dp(52));foot.setMargins(dp(16),dp(8),dp(16),dp(12));root.addView(AdaptiveLayout.centered(this,usageBar,608,null),foot);
        search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence v,int a,int b,int c){}public void onTextChanged(CharSequence v,int a,int b,int c){prefs.edit().putString("search",v.toString()).apply();renderCards();}public void afterTextChanged(android.text.Editable e){}});
    }
    private String state(AgentInbox.State s){switch(s){case NEEDS_YOU:return label("Needs you","待处理");case WORKING:return label("Working","进行中");case DONE:return label("Done","完成");case INTERRUPTED:return label("Interrupted","已中断");case OFFLINE:return label("Offline / stale","离线 / 状态待确认");default:return label("Archived","已归档");}}
    private String kindTitle(AgentHub.Event event){String kind=event.data.optString("hook_event_name");return kind.equals("PermissionRequest")?tr(R.string.approval_request_tool,event.data.optString("tool_name")):kind.equals("PreToolUse")&&event.data.optString("tool_name").equals("AskUserQuestion")?tr(R.string.agent_question):kind.equals("Stop")?tr(R.string.task_completed):kind.equals("StopFailure")?tr(R.string.task_failed):kind.equals("SessionStart")?tr(R.string.agent_session_started):kind.equals("UserPromptSubmit")?tr(R.string.agent_task_started):kind.equals("Interrupt")?tr(R.string.agent_task_interrupted):kind.equals("SessionEnd")?tr(R.string.agent_session_ended):tr(R.string.agent_notification);}
    private String age(double seconds){if(System.currentTimeMillis()-(long)(seconds*1000)<60000)return label("Just now","刚刚");return android.text.format.DateUtils.getRelativeTimeSpanString((long)(seconds*1000),System.currentTimeMillis(),android.text.format.DateUtils.MINUTE_IN_MILLIS).toString();}
    private void render(){if(selected!=null)return;buildInbox();if(requestHeader)inboxHeader();if(body.getChildCount()==0)body.addView(cards);renderCards();}
    private void renderCards(){
        if(cards==null)return;cards.removeAllViews();java.util.List<AgentInbox.Session> sessions=hub.sessions();AgentInbox.State previous=null;int count=0;
        for(AgentInbox.Session session:sessions){if(!AgentInbox.matches(session,search.getText().toString(),provider,hostFilter,projectFilter))continue;
            count++;if(previous!=session.state){TextView section=SampleUi.sectionTitle(this,state(session.state));section.setPadding(0,dp(16),0,dp(12));cards.addView(section);previous=session.state;}
            LinearLayout card=column();card.setPadding(dp(16),dp(16),dp(16),dp(16));card.setBackground(SampleUi.touch(this,SampleUi.FIELD,18));card.setTag(session.latest.id());card.setContentDescription("agent_session:"+session.key);card.setFocusable(true);
            LinearLayout title=row();ImageView badge=agentLogo(session.provider);LinearLayout.LayoutParams badgeSize=new LinearLayout.LayoutParams(dp(24),dp(24));badgeSize.rightMargin=dp(8);title.addView(badge,badgeSize);
            String summary=session.summary();if(summary.equals(session.project())||summary.equals(session.host))summary=kindTitle(session.latest);
            if(session.lastAssistant().isEmpty()&&!session.lastPrompt().isEmpty())summary=label("You: ","你：")+session.lastPrompt();
            TextView name=SampleUi.text(this,(hub.read(session.latest)?"":"● ")+summary,17,SampleUi.TEXT);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));name.setSingleLine();name.setEllipsize(android.text.TextUtils.TruncateAt.END);title.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            TextView time=SampleUi.text(this,age(session.updated),12,SampleUi.MUTED);time.setPadding(dp(12),0,0,0);title.addView(time);card.addView(title);
            String subtitle=!session.lastAssistant().isEmpty()&&!session.lastPrompt().isEmpty()?label("You: ","你：")+session.lastPrompt():session.agent()+" · "+kindTitle(session.latest)+(session.requests.isEmpty()?"":" · "+session.requests.size()+label(" pending"," 项待处理"));
            TextView caption=SampleUi.text(this,subtitle,14,SampleUi.MUTED);caption.setPadding(0,dp(8),0,dp(6));caption.setMaxLines(2);caption.setEllipsize(android.text.TextUtils.TruncateAt.END);caption.setTag("agent_last_prompt");card.addView(caption);
            LinearLayout meta=row();TextView location=SampleUi.text(this,session.project()+" · "+session.host+(session.model().isEmpty()?"":" · "+session.model()),12,SampleUi.MUTED);location.setSingleLine();location.setEllipsize(android.text.TextUtils.TruncateAt.END);meta.addView(location,new LinearLayout.LayoutParams(0,-2,1));
            TextView dot=SampleUi.text(this,session.state==AgentInbox.State.WORKING?"◌":"●",16,session.state==AgentInbox.State.NEEDS_YOU?0xffffbd4a:session.state==AgentInbox.State.OFFLINE?SampleUi.MUTED:SampleUi.GREEN);meta.addView(dot);card.addView(meta);
            card.setOnClickListener(v->sessionDetails(session.key));card.setOnLongClickListener(v->{if(!session.unresolved)SampleUi.menu(this,card,java.util.Arrays.asList(new SampleUi.Action(session.archived?label("Restore","恢复"):label("Archive","归档"),"inbox",true,false,()->hub.archive(session,!session.archived))));return true;});
            LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-1,-2);gap.bottomMargin=dp(12);cards.addView(card,gap);
        }
        if(count==0){TextView empty=SampleUi.text(this,sessions.isEmpty()?tr(R.string.no_agent_events_yet):label("No matching sessions","没有匹配的会话"),18,SampleUi.MUTED);empty.setPadding(dp(8),dp(44),dp(8),dp(24));cards.addView(empty);TextView setup=SampleUi.text(this,tr(R.string.set_up_hook),16,SampleUi.GREEN);setup.setPadding(dp(8),dp(16),dp(8),dp(16));setup.setOnClickListener(v->startActivity(new Intent(this,SettingsActivity.class).putExtra("page","hook")));cards.addView(setup);}
        TextView background=SampleUi.text(this,label("Updates use SSH while this screen or an SSH session is active. Offline requests cannot be approved.","此页面或 SSH 会话活跃时同步。离线请求不可审批。"),12,SampleUi.MUTED);background.setPadding(0,dp(16),0,dp(12));cards.addView(background);renderUsageBar();
    }
    private android.app.Dialog sheet(String title,java.util.function.Consumer<LinearLayout> build){
        android.app.Dialog dialog=new android.app.Dialog(this);LinearLayout panel=column();panel.setPadding(dp(20),dp(16),dp(20),dp(24));panel.setBackground(SampleUi.surface(this,SampleUi.PANEL,24));TextView heading=SampleUi.text(this,title,22,SampleUi.TEXT);heading.setPadding(0,dp(8),0,dp(20));panel.addView(heading);build.accept(panel);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.addView(panel);dialog.setContentView(scroll);android.view.Window w=dialog.getWindow();w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);android.view.WindowManager.LayoutParams a=w.getAttributes();a.gravity=android.view.Gravity.BOTTOM|android.view.Gravity.CENTER_HORIZONTAL;a.dimAmount=.55f;w.setAttributes(a);dialogs.add(dialog);dialog.setOnDismissListener(ignored->dialogs.remove(dialog));dialog.show();android.graphics.Rect available=AdaptiveLayout.available(this);int width=Math.min(dp(640),available.width());panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));w.setLayout(width,Math.min(panel.getMeasuredHeight(),(int)(available.height()*.88f)));return dialog;
    }
    private void line(LinearLayout panel,String text,int size,int color){TextView view=SampleUi.text(this,text,size,color);view.setPadding(0,dp(6),0,dp(8));view.setTextIsSelectable(true);panel.addView(view);}
    private void message(LinearLayout panel,String role,String text){if(text.isEmpty())return;LinearLayout block=column();block.setPadding(dp(16),dp(12),dp(16),dp(12));block.setBackground(SampleUi.surface(this,SampleUi.FIELD,16));line(block,role,12,SampleUi.MUTED);line(block,text,16,SampleUi.TEXT);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);panel.addView(block,p);}
    private View action(LinearLayout panel,String title,Runnable run){TextView button=SampleUi.text(this,title,16,SampleUi.TEXT);button.setPadding(dp(16),dp(16),dp(16),dp(16));button.setBackground(SampleUi.touch(this,SampleUi.FIELD,16));button.setContentDescription(title);button.setOnClickListener(v->run.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);panel.addView(button,p);return button;}
    private void sessionDetails(String key){
        AgentInbox.Session session=null;for(AgentInbox.Session s:hub.sessions())if(s.key.equals(key))session=s;if(session==null)return;final AgentInbox.Session s=session;dialogSession=key;
        if(sessionDialog!=null)sessionDialog.dismiss();sessionDialog=sheet(s.agent()+" · "+state(s.state),panel->{sessionContent=panel;fillSessionDetails(panel,s);});hub.markSessionRead(s);
    }
    private void refreshSessionDetails(){
        if(sessionDialog==null||!sessionDialog.isShowing()||sessionContent==null)return;
        for(AgentInbox.Session s:hub.sessions())if(s.key.equals(dialogSession)){fillSessionDetails(sessionContent,s);return;}
        sessionDialog.dismiss();
    }
    private void fillSessionDetails(LinearLayout panel,AgentInbox.Session s){
            ((TextView)panel.getChildAt(0)).setText(s.agent()+" · "+state(s.state));
            if(panel.getChildCount()>1)panel.removeViews(1,panel.getChildCount()-1);
            TextView summary=SampleUi.text(this,s.summary(),23,SampleUi.TEXT);summary.setMaxLines(3);summary.setEllipsize(android.text.TextUtils.TruncateAt.END);panel.addView(summary);line(panel,s.project()+" · "+s.host,14,SampleUi.MUTED);if(!s.model().isEmpty())line(panel,s.model(),14,SampleUi.MUTED);line(panel,s.cwd(),13,SampleUi.MUTED);line(panel,age(s.updated),13,SampleUi.MUTED);
            if(!s.lastPrompt().isEmpty()||!s.lastAssistant().isEmpty()){line(panel,label("Last turn","最后一轮"),13,SampleUi.MUTED);message(panel,label("You","你"),s.lastPrompt());message(panel,s.agent(),s.lastAssistant());}
            if(s.target()!=null)action(panel,label("Open terminal","打开终端"),()->{sessionDialog.dismiss();MainActivity.openAgent(this,s.profile,s.target());});
            else line(panel,label("No live tmux target. Open the host or resume the session from Recent.","无可附加的 tmux 会话，可从主机的最近列表恢复。"),13,SampleUi.MUTED);
            for(AgentHub.Event e:s.requests)action(panel,kindTitle(e),()->{sessionDialog.dismiss();detail(e);}).setTag(e.id());
            if(!s.unresolved)action(panel,s.archived?label("Restore","恢复"):label("Archive","归档"),()->{hub.archive(s,!s.archived);sessionDialog.dismiss();});
            line(panel,label("Session ID: ","会话 ID：")+s.sessionId,12,SampleUi.MUTED);
            for(int i=s.events.size()-1;i>=Math.max(0,s.events.size()-10);i--){AgentHub.Event e=s.events.get(i);action(panel,kindTitle(e)+" · "+age(e.data.optDouble("created")),()->{sessionDialog.dismiss();detail(e);});}
    }
    private void filters(){
        final java.util.List<AgentInbox.Session> sessions=hub.sessions();filterDialog=sheet(label("Filters","筛选"),panel->{
            action(panel,label("Clear filters","清除筛选"),()->{provider=null;hostFilter=null;projectFilter=null;saveFilters();});
            line(panel,label("Agent","智能体"),13,SampleUi.MUTED);for(String p:new String[]{"claude","codex"}){long n=sessions.stream().filter(s->p.equals(s.provider)).count();action(panel,(p.equals(provider)?"✓ ":"")+(p.equals("codex")?"Codex":"Claude Code")+" · "+n,()->{provider=p.equals(provider)?null:p;saveFilters();});}
            Map<String,String> hosts=new LinkedHashMap<>(),projects=new LinkedHashMap<>();for(AgentInbox.Session s:sessions){hosts.put(s.profile,s.host);projects.put(s.cwd(),s.project());}
            line(panel,label("Host","主机"),13,SampleUi.MUTED);for(Map.Entry<String,String> e:hosts.entrySet())action(panel,(e.getKey().equals(hostFilter)?"✓ ":"")+e.getValue()+" · "+sessions.stream().filter(s->e.getKey().equals(s.profile)).count(),()->{hostFilter=e.getKey().equals(hostFilter)?null:e.getKey();saveFilters();});
            line(panel,label("Project","项目"),13,SampleUi.MUTED);for(Map.Entry<String,String> e:projects.entrySet())action(panel,(e.getKey().equals(projectFilter)?"✓ ":"")+e.getValue()+" · "+sessions.stream().filter(s->e.getKey().equals(s.cwd())).count(),()->{projectFilter=e.getKey().equals(projectFilter)?null:e.getKey();saveFilters();});
        });
    }
    private void saveFilters(){if(filterDialog!=null)filterDialog.dismiss();hub.preferences.edit().putString("filter_provider",provider).putString("filter_host",hostFilter).putString("filter_project",projectFilter).apply();renderCards();}

    private java.util.List<JSONObject> quotaAccounts(){
        Map<String,JSONObject> combined=new LinkedHashMap<>();
        for(SshProfileStore.Entry host:new SshProfileStore(this).list())if(hub.enabled(host.id)){
            JSONObject snapshot=hub.usages.get(host.id);if(snapshot==null)continue;JSONArray accounts=snapshot.optJSONArray("accounts");if(accounts==null)continue;
            for(int i=0;i<accounts.length();i++){JSONObject account=accounts.optJSONObject(i);if(account==null)continue;try{
                JSONObject copy=new JSONObject(account.toString());copy.put("hostName",host.name);copy.put("stale",!snapshot.optString("status","ok").equals("ok")||System.currentTimeMillis()/1000d-account.optDouble("capturedAt",0)>120);
                String key=account.optString("accountId",host.id+":"+account.optString("provider"));if(key.endsWith(":unknown"))key=host.id+":"+account.optString("provider");JSONObject previous=combined.get(key);
                if(previous==null||account.optDouble("capturedAt",0)>previous.optDouble("capturedAt",0))combined.put(key,copy);
            }catch(JSONException ignored){}}
        }return new ArrayList<>(combined.values());
    }
    private ImageView agentLogo(String provider){ImageView logo=new ImageView(this);logo.setImageResource(provider.equals("codex")?R.drawable.agent_openai:R.drawable.agent_claude);logo.setImageTintList(android.content.res.ColorStateList.valueOf(provider.equals("codex")?0xff00b99a:0xffd98b68));logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return logo;}
    private void renderUsageBar(){usageBar.removeAllViews();for(String provider:new String[]{"claude","codex"}){
        Double used=null;boolean stale=false;int count=0;for(JSONObject account:quotaAccounts())if(provider.equals(account.optString("provider"))){count++;stale|=account.optBoolean("stale");JSONArray windows=account.optJSONArray("windows");if(windows!=null&&windows.length()>0){JSONObject window=windows.optJSONObject(0);if(window!=null&&window.has("usedPercent")&&!window.isNull("usedPercent")){double value=window.optDouble("usedPercent");if(Double.isFinite(value))used=used==null?value:Math.max(used,value);}}}
        int tint=provider.equals("codex")?0xff00b99a:0xffd98b68;String percentage=(used==null?"—":Math.round(used)+"%")+(stale?" · "+label("stale","旧"):"");
        LinearLayout button=row();button.setPadding(dp(8),0,dp(8),0);button.setFocusable(true);button.setBackground(SampleUi.touch(this,android.graphics.Color.TRANSPARENT,24));button.setTag("agent_usage_"+provider);button.setContentDescription((provider.equals("codex")?"Codex":"Claude Code")+" · "+label("Usages","用量")+" · "+percentage+(count>1?" · "+count+label(" accounts"," 个账号"):""));button.setOnClickListener(v->showUsages(provider));
        button.addView(agentLogo(provider),new LinearLayout.LayoutParams(dp(26),dp(26)));
        ProgressBar meter=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);meter.setIndeterminate(false);meter.setMax(100);meter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        android.graphics.drawable.LayerDrawable track=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{SampleUi.surface(this,0xff303438,3),new android.graphics.drawable.ClipDrawable(SampleUi.surface(this,stale?SampleUi.MUTED:tint,3),android.view.Gravity.LEFT,android.graphics.drawable.ClipDrawable.HORIZONTAL)});track.setId(0,android.R.id.background);track.setId(1,android.R.id.progress);meter.setProgressDrawable(track);meter.setProgress(used==null?0:(int)Math.max(0,Math.min(100,Math.round(used))));LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,dp(4),1);mp.leftMargin=dp(10);mp.rightMargin=dp(8);button.addView(meter,mp);
        TextView value=SampleUi.text(this,percentage,12,stale?SampleUi.MUTED:tint);value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);button.addView(value);usageBar.addView(button,new LinearLayout.LayoutParams(0,dp(52),1));
    }}
    private void showUsages(){showUsages(null);}
    private void showUsages(String provider){usageProvider=provider;hub.refreshUsage(false);if(usageDialog!=null)usageDialog.dismiss();usageDialog=sheet((provider==null?"":provider.equals("claude")?"Claude Code · ":"Codex · ")+label("Usages","用量"),panel->{usageContent=panel;renderUsageContent(panel);});}
    private void renderUsageContent(LinearLayout panel){
        if(panel.getChildCount()>1)panel.removeViews(1,panel.getChildCount()-1);
        java.util.List<JSONObject> accounts=quotaAccounts();if(usageProvider!=null)accounts.removeIf(a->!usageProvider.equals(a.optString("provider")));if(!hub.preferences.getBoolean("usageCollection",true))line(panel,label("Collection disabled; showing saved snapshots.","用量采集已关闭，当前展示保存的快照。"),13,SampleUi.MUTED);
        if(accounts.isEmpty())line(panel,label("No usage snapshot yet. Enable Hook on a host and refresh.","暂无用量快照，请在主机上启用 Hook 后刷新。"),14,SampleUi.MUTED);
        for(JSONObject account:accounts){
            line(panel,account.optString("provider")+" · "+account.optString("accountLabel"),17,SampleUi.TEXT);line(panel,account.optString("hostName"),13,SampleUi.MUTED);
            if(account.optBoolean("stale"))line(panel,label("Offline / stale snapshot","离线 / 旧快照"),13,SampleUi.DANGER);
            if(!account.optString("status","ok").equals("ok"))line(panel,"not_signed_in".equals(account.optString("status"))?label("Sign into this agent on the host.","请先在主机上登录此智能体。"):label("Usage unavailable. Check host login, network, or the quota worker.","暂时无法读取用量，请检查主机登录、网络或用量助手。"),13,SampleUi.MUTED);
            JSONArray windows=account.optJSONArray("windows");if(windows!=null)for(int j=0;j<windows.length();j++){JSONObject window=windows.optJSONObject(j);if(window==null||!window.has("usedPercent")||window.isNull("usedPercent"))continue;
                line(panel,window.optString("label")+" · "+window.optDouble("usedPercent")+label("% used","% 已用"),16,SampleUi.GREEN);
                ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgress((int)Math.round(window.optDouble("usedPercent")));progress.setProgressTintList(android.content.res.ColorStateList.valueOf(SampleUi.GREEN));panel.addView(progress,new LinearLayout.LayoutParams(-1,dp(8)));
                if(window.optDouble("resetsAt",0)>0)line(panel,label("Resets: ","重置时间：")+android.text.format.DateFormat.format("MM-dd HH:mm",(long)(window.optDouble("resetsAt")*1000)),12,SampleUi.MUTED);
            }
            if(account.optDouble("capturedAt",0)>0)line(panel,label("Updated ","更新于 ")+age(account.optDouble("capturedAt")),12,SampleUi.MUTED);
        }
        action(panel,label("Refresh now","立即刷新"),()->hub.refreshUsage(true));
        action(panel,hub.preferences.getBoolean("usageCollection",true)?label("Disable usage collection","关闭用量采集"):label("Enable usage collection","开启用量采集"),()->{hub.preferences.edit().putBoolean("usageCollection",!hub.preferences.getBoolean("usageCollection",true)).apply();renderUsageContent(panel);renderUsageBar();});
        line(panel,label("Quota is per account, not task progress or context remaining. Credentials stay on the host. Collection runs only during active app/SSH sync.","额度按账号计算，与任务进度、上下文剩余不同。凭据保留在主机，只在 App / SSH 活跃同步时采集。"),12,SampleUi.MUTED);
        line(panel,label("Host diagnostics","主机诊断"),16,SampleUi.TEXT);
        for(SshProfileStore.Entry host:new SshProfileStore(this).list())if(hub.enabled(host.id)){
            JSONObject diagnostics=hub.diagnostics.get(host.id);line(panel,host.name+" · "+(hub.states.getOrDefault(host.id,0)==R.string.connected?label("Connected","已连接"):label("Offline / check SSH","离线 / 请检查 SSH")),14,SampleUi.TEXT);
            if(diagnostics==null||diagnostics.optInt("version",1)<2)line(panel,label("Update the host helper in Settings → Hook.","请在设置 → Hook 更新主机助手。"),12,SampleUi.MUTED);
            else {JSONObject hooks=diagnostics.optJSONObject("hooks");for(String provider:new String[]{"claude","codex"})if(usageProvider==null||usageProvider.equals(provider))line(panel,(provider.equals("codex")?"Codex":"Claude Code")+" Hook · "+(hooks!=null&&"installed".equals(hooks.optString(provider))?label("Registered","已注册"):label("Not installed","未安装")),12,SampleUi.MUTED);if(usageProvider==null||usageProvider.equals("codex"))line(panel,label("Codex hooks must also be reviewed in /hooks.","Codex 还需在 /hooks 中审核已注册的 Hook。"),12,SampleUi.MUTED);}
            action(panel,label("Hook settings","Hook 设置"),()->startActivity(new Intent(this,SettingsActivity.class).putExtra("page","hook")));
        }
    }
    private String status(AgentHub.Event e){switch(e.data.optString("status")){case "pending":return eventStatus(e);case "answered":return tr(R.string.responded);case "expired":return tr(R.string.expired_return_to_the_terminal);case "offline":return tr(R.string.offline_waiting_to_sync);default:return tr(R.string.received);}}
    private String eventStatus(AgentHub.Event e){return e.pending()?tr(R.string.awaiting_response):e.data.optBoolean("cached")||e.data.optBoolean("offline")?tr(R.string.offline_waiting_to_sync):tr(R.string.expired_return_to_the_terminal);}
    private void detail(AgentHub.Event event){shownRequest=event;requestWasPending=event.pending();selected=event.id();requestHeader=true;header.removeAllViews();header.addView(SampleUi.header(this,"back",tr(R.string.back),SampleUi.TEXT,this::onBackPressed),new LinearLayout.LayoutParams(dp(48),dp(48)));heading=SampleUi.text(this,tr(R.string.agent_event),20,SampleUi.TEXT);header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));hub.markRead(event);body.removeAllViews();heading.setText(tr(R.string.agent_event));section(event.agent()+" · "+event.host+" · "+status(event));
        String cwd=event.data.optString("cwd");if(!cwd.isEmpty())copy(cwd);
        String message=event.data.optString("message",event.data.optString("last_assistant_message"));if(!message.isEmpty())copy(message).setTextIsSelectable(true);
        JSONObject input=event.data.optJSONObject("tool_input");JSONArray questions=input==null?null:input.optJSONArray("questions");
        answers=new LinkedHashMap<>();
        if(questions!=null&&event.data.optString("tool_name").equals("AskUserQuestion")){
            for(int i=0;i<Math.min(questions.length(),4);i++){JSONObject q=questions.optJSONObject(i);if(q==null)continue;String title=q.optString("question");section(title);EditText answer=new EditText(this);SampleUi.field(answer);answer.setHint(tr(R.string.choose_an_option_or_type_an_answer));answer.setContentDescription(title);LinearLayout.LayoutParams field=new LinearLayout.LayoutParams(-1,-2);field.bottomMargin=dp(12);body.addView(answer,field);answers.put(title,answer);
                JSONArray options=q.optJSONArray("options");if(options!=null)for(int j=0;j<Math.min(options.length(),20);j++){JSONObject option=options.optJSONObject(j);if(option==null)continue;String label=option.optString("label");item(label,option.optString("description"),"chat",()->{if(q.optBoolean("multiSelect")){String previous=answer.getText().toString();answer.setText(previous.isEmpty()?label:previous+", "+label);}else answer.setText(label);});}}
        }else if(input!=null){String detail=input.toString();if(event.data.optString("tool_name").equals("Bash")||event.data.optString("tool_name").equals("apply_patch")){copy(input.optString("description"));detail=input.optString("command");}else try{detail=input.toString(2);}catch(JSONException ignored){}TextView code=copy(detail);code.setTextColor(SampleUi.TEXT);code.setTypeface(Typeface.MONOSPACE);code.setTextIsSelectable(true);}
        if(event.pending()){
            copy(tr(R.string.respond_to_this_request_only_if_it_expires_or_the_network_disconn));
            item(answers.isEmpty()?tr(R.string.allow_once):tr(R.string.send_answer),null,"check",()->{JSONObject response=new JSONObject();try{for(Map.Entry<String,EditText> a:answers.entrySet()){String value=a.getValue().getText().toString().trim();if(value.isEmpty()){a.getValue().setError(tr(R.string.enter_an_answer));return;}response.put(a.getKey(),value);}}catch(JSONException ignored){}submit(event,"allow",response);});
            item(tr(R.string.deny),null,"close",()->submit(event,"deny",null));
        }
        actionGroup();item(tr(R.string.back_to_inbox),null,"back",()->{selected=null;body.removeAllViews();if(cards!=null)body.addView(cards);render();});
    }
    private void submit(AgentHub.Event e,String choice,JSONObject answers){if(sending)return;sending=true;hub.respond(e,choice,answers,(ok,message)->{sending=false;if(isFinishing()||isDestroyed())return;toast(message);selected=null;body.removeAllViews();if(cards!=null)body.addView(cards);render();});}
    @Override public void onBackPressed(){if(selected!=null){selected=null;body.removeAllViews();body.addView(cards);render();}else super.onBackPressed();}
}
