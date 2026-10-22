package dev.ghostty.android.sample;

import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

/** Derived session state; requests remain separate and retain their exact event identities. */
final class AgentInbox {
    enum State { NEEDS_YOU, WORKING, DONE, INTERRUPTED, OFFLINE, ARCHIVED }
    static final class Session {
        final String key,profile,provider,sessionId,host;
        final List<AgentHub.Event> events=new ArrayList<>(),requests=new ArrayList<>();
        AgentHub.Event latest;State state;boolean archived,unresolved,attention;double updated;
        Session(AgentHub.Event e){profile=e.profile;provider=e.data.optString("provider","claude");sessionId=e.data.optString("session_id",e.id());host=e.host;key=profile+":"+provider+":"+sessionId;}
        String agent(){return latest.agent();}
        String cwd(){for(int i=events.size()-1;i>=0;i--){String s=events.get(i).data.optString("cwd");if(!s.isEmpty())return s;}return "";}
        String project(){String s=cwd();int i=s.lastIndexOf('/');return s.isEmpty()?host:s.substring(i+1).isEmpty()?s:s.substring(i+1);}
        String model(){for(int i=events.size()-1;i>=0;i--){String s=events.get(i).data.optString("model");if(!s.isEmpty())return s;}return "";}
        AgentHub.Event currentPrompt(){for(int i=events.size()-1;i>=0;i--)if("UserPromptSubmit".equals(events.get(i).data.optString("hook_event_name")))return events.get(i);return null;}
        String text(JSONObject event,String key){Object value=event.opt(key);return value instanceof String?((String)value).trim():"";}
        String lastPrompt(){AgentHub.Event prompt=currentPrompt();return prompt==null?"":text(prompt.data,"prompt_preview");}
        String lastAssistant(){
            AgentHub.Event prompt=currentPrompt();String turn=prompt==null?"":prompt.data.optString("turn_id");
            for(int i=events.size()-1;i>=0;i--){AgentHub.Event event=events.get(i);if(event==prompt)break;
                String eventTurn=event.data.optString("turn_id");if(!turn.isEmpty()&&!eventTurn.isEmpty()&&!turn.equals(eventTurn))continue;
                String message=text(event.data,"last_assistant_message");if(!message.isEmpty())return message;}
            return "";
        }
        String summary(){String reply=lastAssistant(),prompt=lastPrompt();return !reply.isEmpty()?reply:!prompt.isEmpty()?prompt:!text(latest.data,"message").isEmpty()?text(latest.data,"message"):project();}
        String target(){for(int i=events.size()-1;i>=0;i--){String s=events.get(i).data.optString("tmux_session");if(!s.isEmpty())try{SessionTarget.validate("="+s);return "="+s;}catch(IllegalArgumentException ignored){}}return null;}
    }
    static List<Session> sessions(List<AgentHub.Event> events,SharedPreferences prefs,long now){
        Map<String,AgentHub.Event> unique=new LinkedHashMap<>();for(AgentHub.Event e:events)unique.put(e.id(),e);
        List<AgentHub.Event> ordered=new ArrayList<>(unique.values());ordered.sort(Comparator.comparingDouble(e->e.data.optDouble("localCreated",e.data.optDouble("created",0))));
        Map<String,Session> grouped=new LinkedHashMap<>();
        for(AgentHub.Event e:ordered){Session s=new Session(e);Session present=grouped.get(s.key);if(present==null){present=s;grouped.put(s.key,s);}present.events.add(e);present.latest=e;present.updated=e.data.optDouble("localCreated",e.data.optDouble("created",0));if(e.pending())present.requests.add(e);if("pending".equals(e.data.optString("status"))&&e.data.optLong("localExpiresAt",0)>now)present.unresolved=true;}
        for(Session s:grouped.values()){
            AgentHub.Event prompt=s.currentPrompt();String turn=prompt==null?"":prompt.data.optString("turn_id");
            if(!turn.isEmpty())for(int i=s.events.size()-1;i>=0;i--){AgentHub.Event e=s.events.get(i);String eventTurn=e.data.optString("turn_id");if(eventTurn.isEmpty()||turn.equals(eventTurn)){s.latest=e;s.updated=e.data.optDouble("localCreated",e.data.optDouble("created",0));break;}}
            long archiveAt=prefs.getLong("archive_"+s.key,0);String kind=s.latest.data.optString("hook_event_name");
            boolean working=Arrays.asList("SessionStart","UserPromptSubmit","PreToolUse","PostToolUse").contains(kind);
            boolean stale=(working||"pending".equals(s.latest.data.optString("status")))&&(s.latest.data.optBoolean("cached")||s.latest.data.optBoolean("offline")||Boolean.FALSE.equals(s.latest.data.opt("process_alive"))||(working&&!s.latest.data.optBoolean("process_alive")&&now/1000d-s.updated>120));
            boolean restored=now-prefs.getLong("restore_"+s.key,0)<600000;
            s.archived=!s.unresolved&&(archiveAt>0&&s.updated*1000<=archiveAt||!restored&&(now/1000d-s.updated>21600||(!working&&now/1000d-s.updated>600)));
            s.attention=kind.equals("Notification")&&Arrays.asList("permission_prompt","idle_prompt").contains(s.latest.data.optString("notification_type"));
            s.state=!s.requests.isEmpty()||s.attention&&!s.archived?State.NEEDS_YOU:s.archived?State.ARCHIVED:stale?State.OFFLINE:working?State.WORKING:kind.equals("Interrupt")||kind.equals("StopFailure")?State.INTERRUPTED:kind.equals("Stop")||kind.equals("SessionEnd")?State.DONE:State.OFFLINE;
        }
        List<Session> result=new ArrayList<>(grouped.values());result.sort(Comparator.<Session>comparingInt(s->s.state.ordinal()).thenComparing(Comparator.comparingDouble((Session s)->s.updated).reversed()));return result;
    }
    static boolean matches(Session s,String query,String provider,String host,String project){
        if(provider!=null&&!provider.equals(s.provider)||host!=null&&!host.equals(s.profile)||project!=null&&!project.equals(s.cwd()))return false;
        String needle=query==null?"":query.trim().toLowerCase(Locale.ROOT);return needle.isEmpty()||(s.agent()+" "+s.host+" "+s.project()+" "+s.cwd()+" "+s.summary()+" "+s.lastPrompt()+" "+s.model()).toLowerCase(Locale.ROOT).contains(needle);
    }
}
