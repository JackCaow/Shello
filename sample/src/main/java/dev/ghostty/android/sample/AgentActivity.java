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
    private final Runnable update=()->{if(selected==null)render();};
    @Override public void onCreate(Bundle state){super.onCreate(state);hub=AgentHub.get(this);heading.setText(tr(R.string.agent_inbox));render();
        if(state!=null&&state.getString("event")!=null)for(AgentHub.Event event:hub.snapshot())if(event.id().equals(state.getString("event"))){
            detail(event);Bundle values=state.getBundle("answers");if(values!=null)for(Map.Entry<String,EditText> entry:answers.entrySet())entry.getValue().setText(values.getString(entry.getKey(),""));break;}
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putString("event",selected);Bundle values=new Bundle();for(Map.Entry<String,EditText> e:answers.entrySet())values.putString(e.getKey(),e.getValue().getText().toString());state.putBundle("answers",values);super.onSaveInstanceState(state);}
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
    private void render(){body.removeAllViews();heading.setText(tr(R.string.agent_inbox));
        List<AgentHub.Event> events=hub.snapshot();if(events.isEmpty()){section(tr(R.string.no_agent_events_yet));copy(tr(R.string.approval_requests_questions_and_completed_tasks_will_appear_here));item(tr(R.string.set_up_hook),tr(R.string.install_claude_code_hook_on_your_ssh_host),"hook",()->startActivity(new Intent(this,SettingsActivity.class).putExtra("page","hook")));}
        else{section(AppLanguage.quantity(R.plurals.inbox_pending_count,hub.pending(null),hub.pending(null)));for(AgentHub.Event event:events){String kind=event.data.optString("hook_event_name");String title=kind.equals("PermissionRequest")?tr(R.string.approval_request_tool,event.data.optString("tool_name")):kind.equals("PreToolUse")&&event.data.optString("tool_name").equals("AskUserQuestion")?tr(R.string.agent_question):kind.equals("Stop")?tr(R.string.task_completed):kind.equals("StopFailure")?tr(R.string.task_failed):kind.equals("SessionStart")?tr(R.string.agent_session_started):kind.equals("UserPromptSubmit")?tr(R.string.agent_task_started):kind.equals("Interrupt")?tr(R.string.agent_task_interrupted):kind.equals("SessionEnd")?tr(R.string.agent_session_ended):tr(R.string.agent_notification);
                item((hub.read(event)?"":"● ")+title,event.agent()+" · "+event.host+" · "+status(event),"inbox",()->detail(event)).setTag(event.id());}}
        actionGroup();item(tr(R.string.refresh),null,"history",hub::refresh);
    }
    private String status(AgentHub.Event e){switch(e.data.optString("status")){case "pending":return tr(R.string.awaiting_response);case "answered":return tr(R.string.responded);case "expired":return tr(R.string.expired_return_to_the_terminal);case "offline":return tr(R.string.offline_waiting_to_sync);default:return tr(R.string.received);}}
    private void detail(AgentHub.Event event){selected=event.id();hub.markRead(event);body.removeAllViews();heading.setText(tr(R.string.agent_event));section(event.agent()+" · "+event.host+" · "+status(event));
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
        actionGroup();item(tr(R.string.back_to_inbox),null,"back",()->{selected=null;render();});
    }
    private void submit(AgentHub.Event e,String choice,JSONObject answers){if(sending)return;sending=true;hub.respond(e,choice,answers,(ok,message)->{sending=false;if(isFinishing()||isDestroyed())return;toast(message);selected=null;render();});}
    @Override public void onBackPressed(){if(selected!=null){selected=null;render();}else super.onBackPressed();}
}
