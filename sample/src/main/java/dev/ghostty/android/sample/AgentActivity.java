package dev.ghostty.android.sample;

import android.os.Bundle;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Inbox of actual remote events, with explicit per-request decisions. */
public final class AgentActivity extends ScreenActivity {
    private AgentHub hub;private String selected;private boolean sending;
    private final Runnable update=()->{if(selected==null)render();};
    @Override public void onCreate(Bundle state){super.onCreate(state);hub=AgentHub.get(this);heading.setText("Agent 收件箱");render();}
    @Override protected void onStart(){super.onStart();hub.attach(update);}
    @Override protected void onStop(){hub.detach(update);super.onStop();}
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
    private void actionGroup(){View gap=new View(this);body.addView(gap,new LinearLayout.LayoutParams(1,dp(12)));}
    private void render(){body.removeAllViews();heading.setText("Agent 收件箱");
        List<AgentHub.Event> events=hub.snapshot();if(events.isEmpty()){section("暂无智能体事件");copy("当智能体请求审批、提问或完成任务时，会显示在这里。");item("设置 Hook","在 SSH 主机上安装 Claude Code Hook","hook",()->startActivity(new Intent(this,SettingsActivity.class).putExtra("page","hook")));}
        else{section("收件箱 · "+hub.pending(null)+" 条待处理");for(AgentHub.Event event:events){String kind=event.data.optString("hook_event_name");String title=kind.equals("PermissionRequest")?"请求审批 · "+event.data.optString("tool_name"):kind.equals("PreToolUse")&&event.data.optString("tool_name").equals("AskUserQuestion")?"智能体提问":kind.equals("Stop")?"任务完成":kind.equals("StopFailure")?"任务出错":"智能体通知";
                item((hub.read(event)?"":"● ")+title,event.host+" · "+status(event),"inbox",()->detail(event));}}
        actionGroup();item("刷新",null,"history",hub::refresh);
    }
    private String status(AgentHub.Event e){switch(e.data.optString("status")){case "pending":return "等待回应";case "answered":return "已回应";case "expired":return "已过期 · 请回到终端";case "offline":return "离线 · 等待重新获取";default:return "已接收";}}
    private void detail(AgentHub.Event event){selected=event.id();hub.markRead(event);body.removeAllViews();heading.setText("Agent 事件");section(event.host+" · "+status(event));
        String cwd=event.data.optString("cwd");if(!cwd.isEmpty())copy(cwd);
        String message=event.data.optString("message",event.data.optString("last_assistant_message"));if(!message.isEmpty())copy(message).setTextIsSelectable(true);
        JSONObject input=event.data.optJSONObject("tool_input");JSONArray questions=input==null?null:input.optJSONArray("questions");
        Map<String,EditText> answers=new LinkedHashMap<>();
        if(questions!=null&&event.data.optString("tool_name").equals("AskUserQuestion")){
            for(int i=0;i<Math.min(questions.length(),4);i++){JSONObject q=questions.optJSONObject(i);if(q==null)continue;String title=q.optString("question");section(title);EditText answer=new EditText(this);SampleUi.field(answer);answer.setHint("选择选项或输入回答");answer.setContentDescription(title);LinearLayout.LayoutParams field=new LinearLayout.LayoutParams(-1,-2);field.bottomMargin=dp(12);body.addView(answer,field);answers.put(title,answer);
                JSONArray options=q.optJSONArray("options");if(options!=null)for(int j=0;j<Math.min(options.length(),20);j++){JSONObject option=options.optJSONObject(j);if(option==null)continue;String label=option.optString("label");item(label,option.optString("description"),"chat",()->{if(q.optBoolean("multiSelect")){String previous=answer.getText().toString();answer.setText(previous.isEmpty()?label:previous+", "+label);}else answer.setText(label);});}}
        }else if(input!=null){String detail=input.toString();if(event.data.optString("tool_name").equals("Bash")){copy(input.optString("description"));detail=input.optString("command");}else try{detail=input.toString(2);}catch(JSONException ignored){}TextView code=copy(detail);code.setTextColor(SampleUi.TEXT);code.setTypeface(Typeface.MONOSPACE);code.setTextIsSelectable(true);}
        if(event.pending()){
            copy("只回应这一次请求。超时或网络中断时，请回到终端处理。");
            item(answers.isEmpty()?"允许一次":"发送回答",null,"check",()->{JSONObject response=new JSONObject();try{for(Map.Entry<String,EditText> a:answers.entrySet()){String value=a.getValue().getText().toString().trim();if(value.isEmpty()){a.getValue().setError("请填写回答");return;}response.put(a.getKey(),value);}}catch(JSONException ignored){}submit(event,"allow",response);});
            item("拒绝",null,"close",()->submit(event,"deny",null));
        }
        actionGroup();item("返回收件箱",null,"back",()->{selected=null;render();});
    }
    private void submit(AgentHub.Event e,String choice,JSONObject answers){if(sending)return;sending=true;hub.respond(e,choice,answers,(ok,message)->{sending=false;if(isFinishing()||isDestroyed())return;toast(message);selected=null;render();});}
    @Override public void onBackPressed(){if(selected!=null){selected=null;render();}else super.onBackPressed();}
}
