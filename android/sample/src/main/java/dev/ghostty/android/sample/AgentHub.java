package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;

/** Host events and quota snapshots use pinned SSH; cached requests cannot grant permission. */
final class AgentHub {
    static final String COMMAND="python3 \"$HOME/.local/state/ghostty-android/ghostty_hook.py\" ";
    static final String INSTALL="umask 077; mkdir -p \"$HOME/.local/state/ghostty-android\" && cat > \"$HOME/.local/state/ghostty-android/ghostty_hook.py.new\" && mv \"$HOME/.local/state/ghostty-android/ghostty_hook.py.new\" \"$HOME/.local/state/ghostty-android/ghostty_hook.py\" && "+COMMAND+"install";
    static final class Event {
        final String profile,host;final JSONObject data;final long deadline;
        Event(String p,String h,JSONObject d){profile=p;host=h;data=d;deadline=SystemClock.elapsedRealtime()+Math.max(0,d.optLong("remainingMs",0));if(!d.optBoolean("cached"))try{d.put("localExpiresAt",System.currentTimeMillis()+Math.max(0,d.optLong("remainingMs",0)));}catch(JSONException ignored){}}
        String agent(){return "codex".equals(data.optString("provider"))?"Codex":"Claude Code";}
        String id(){return profile+":"+data.optString("id");}
        boolean pending(){return "pending".equals(data.optString("status"))&&!data.optBoolean("cached")&&!data.optBoolean("offline")&&!Boolean.FALSE.equals(data.opt("process_alive"))&&SystemClock.elapsedRealtime()<deadline;}
    }
    private static AgentHub instance;
    static synchronized AgentHub get(Context c){if(instance==null)instance=new AgentHub(c.getApplicationContext());return instance;}
    final Context context;final SharedPreferences preferences;final Handler main=new Handler(Looper.getMainLooper());
    final ExecutorService worker=Executors.newSingleThreadExecutor(),usageWorker=Executors.newSingleThreadExecutor();
    final ArrayList<Event> events=new ArrayList<>();final Map<String,Integer> states=new HashMap<>();
    final Map<String,JSONObject> usages=new LinkedHashMap<>(),diagnostics=new LinkedHashMap<>();
    final Set<Runnable> observers=new HashSet<>();final Set<String> notified=new LinkedHashSet<>();
    private final AgentCache cache;private long lastUsage;private boolean usageBusy;
    boolean keepAlive,busy=true,scheduled;int users;
    private AgentHub(Context c){
        context=c;preferences=c.getSharedPreferences("agent",0);cache=new AgentCache(c);
        c.getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("agent_events",tr(R.string.agent_event),NotificationManager.IMPORTANCE_DEFAULT));
        worker.execute(()->{JSONObject saved=cache.read();main.post(()->{
            JSONArray rows=saved.optJSONArray("events");if(rows!=null)for(int i=0;i<Math.min(rows.length(),400);i++)try{
                JSONObject row=rows.getJSONObject(i),data=row.getJSONObject("data");String profile=row.getString("profile");if(!enabled(profile))continue;
                data.put("cached",true);Event e=new Event(profile,row.getString("host"),data);events.add(e);notified.add(e.id());
            }catch(JSONException ignored){}
            JSONObject quota=saved.optJSONObject("usages");if(quota!=null)for(Iterator<String> it=quota.keys();it.hasNext();){String id=it.next();if(enabled(id))usages.put(id,quota.optJSONObject(id));}
            busy=false;changed();refresh();
        });});
    }
    void attach(Runnable observer){if(observers.add(observer))users++;refresh();}
    void detach(Runnable observer){if(observers.remove(observer))users=Math.max(0,users-1);}
    void keepAlive(boolean value){keepAlive=value;if(value)refresh();}
    boolean enabled(String id){return preferences.getBoolean("host_"+id,false);}
    boolean read(Event e){return preferences.getBoolean("read_"+e.id(),false);}
    void markRead(Event e){if(read(e))return;SharedPreferences.Editor edit=preferences.edit().putBoolean("read_"+e.id(),true);int count=0;for(String key:preferences.getAll().keySet())if(key.startsWith("read_")&&++count>1000)edit.remove(key);edit.apply();changed();}
    void markSessionRead(AgentInbox.Session session){SharedPreferences.Editor edit=preferences.edit();boolean changed=false;for(Event e:session.events)if(!read(e)){edit.putBoolean("read_"+e.id(),true);changed=true;}if(changed){edit.apply();changed();}}
    List<Event> snapshot(){return new ArrayList<>(events);}
    List<AgentInbox.Session> sessions(){return AgentInbox.sessions(snapshot(),preferences,System.currentTimeMillis());}
    int pending(String profile){int count=0;for(Event e:events)if((profile==null||e.profile.equals(profile))&&e.pending())count++;return count;}
    void archive(AgentInbox.Session session,boolean value){if(value&&session.unresolved)return;SharedPreferences.Editor edit=preferences.edit();if(value)edit.putLong("archive_"+session.key,System.currentTimeMillis());else {edit.remove("archive_"+session.key);edit.putLong("restore_"+session.key,System.currentTimeMillis());}edit.commit();changed();persist();}
    void changed(){for(Runnable r:new ArrayList<>(observers))r.run();}
    private void persist(){
        JSONObject value=new JSONObject();JSONArray rows=new JSONArray();try{
            int bytes=0;for(Event e:events){JSONObject data=new JSONObject(e.data.toString());if(data.toString().length()>65536){data.remove("tool_input");data.put("payloadNotCached",true);}int size=data.toString().getBytes(StandardCharsets.UTF_8).length;if(bytes+size>1000000)continue;bytes+=size;rows.put(new JSONObject().put("profile",e.profile).put("host",e.host).put("data",data));}
            value.put("events",rows).put("usages",new JSONObject(usages));
        }catch(JSONException ignored){}worker.execute(()->cache.write(value));
    }
    void refresh(){
        if(busy||users==0&&!keepAlive)return;busy=true;
        worker.execute(()->{List<Event> next=new ArrayList<>();Map<String,Integer> status=new HashMap<>();Map<String,JSONObject> info=new HashMap<>();Set<String> successful=new HashSet<>(),known=new HashSet<>();
            for(SshProfileStore.Entry p:new SshProfileStore(context).list()){known.add(p.id);if(!enabled(p.id))continue;
                try(SshCommand command=new SshCommand()){
                    JSONObject result=new JSONObject(command.run(context,p.id,COMMAND+"poll",null));JSONArray rows=result.getJSONArray("events");long now=(long)(result.getDouble("now")*1000);
                    for(int i=0;i<Math.min(rows.length(),400);i++){JSONObject event=rows.getJSONObject(i);event.put("localCreated",System.currentTimeMillis()/1000d+event.optDouble("created",now/1000d)-now/1000d).put("cached",false).put("offline",false).put("remainingMs",Math.max(0,(long)(event.optDouble("expires")*1000)-now));next.add(new Event(p.id,p.name,event));}
                    status.put(p.id,R.string.connected);successful.add(p.id);if(result.optJSONObject("diagnostics")!=null)info.put(p.id,result.optJSONObject("diagnostics"));
                }catch(Exception error){status.put(p.id,R.string.cannot_read_hook_check_the_connection_or_reinstall);}
            }
            main.post(()->{
                Map<String,Event> merged=new LinkedHashMap<>();Set<String> incoming=new HashSet<>();for(Event e:next)incoming.add(e.id());
                for(Event old:events)if(known.contains(old.profile)&&enabled(old.profile)&&!incoming.contains(old.id())&&System.currentTimeMillis()/1000d-old.data.optDouble("created",0)<604800){
                    try{old.data.put("cached",true).put("offline",!successful.contains(old.profile));}catch(JSONException ignored){}merged.put(old.id(),old);
                }
                for(Event e:next)merged.put(e.id(),e);
                events.clear();events.addAll(merged.values());events.sort(Comparator.comparingDouble((Event e)->e.data.optDouble("created",0)).reversed());
                if(events.size()>400)events.subList(400,events.size()).clear();states.clear();states.putAll(status);diagnostics.putAll(info);diagnostics.keySet().retainAll(known);usages.keySet().retainAll(known);busy=false;
                for(Event e:next){boolean fresh=System.currentTimeMillis()/1000d-e.data.optDouble("created",0)<120;
                    if(notified.add(e.id())&&!read(e)&&preferences.getBoolean("notifications",true)&&(e.pending()||fresh&&"Stop".equals(e.data.optString("hook_event_name"))))notifyEvent(e);}
                while(notified.size()>1000)notified.remove(notified.iterator().next());
                persist();changed();refreshUsage(false);
                if(!scheduled&&(users>0||keepAlive)){scheduled=true;main.postDelayed(()->{scheduled=false;refresh();},5000);}
            });
        });
    }
    void refreshUsage(boolean force){
        if(usageBusy||!preferences.getBoolean("usageCollection",true)||!force&&SystemClock.elapsedRealtime()-lastUsage<60000)return;
        usageBusy=true;lastUsage=SystemClock.elapsedRealtime();
        usageWorker.execute(()->{
            Map<String,JSONObject> next=new LinkedHashMap<>();for(SshProfileStore.Entry p:new SshProfileStore(context).list())if(enabled(p.id))try(SshCommand ssh=new SshCommand()){
                JSONObject result=new JSONObject(ssh.run(context,p.id,COMMAND+"usage"+(force?" --refresh":""),null));next.put(p.id,result);
            }catch(Exception ignored){try{next.put(p.id,new JSONObject().put("status","offline").put("accounts",new JSONArray()));}catch(JSONException impossible){}}
            main.post(()->{for(Map.Entry<String,JSONObject> e:next.entrySet()){
                if("offline".equals(e.getValue().optString("status"))&&usages.containsKey(e.getKey()))try{usages.get(e.getKey()).put("status","offline");}catch(JSONException ignored){}
                else usages.put(e.getKey(),e.getValue());
            }usageBusy=false;persist();changed();});
        });
    }
    private void notifyEvent(Event e){
        if(users>0)return;Intent open=new Intent(context,AgentActivity.class).putExtra("event",e.id()).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);PendingIntent intent=PendingIntent.getActivity(context,e.id().hashCode(),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(context,"agent_events").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(e.agent()+" · "+(e.pending()?tr(R.string.an_agent_needs_your_response):tr(R.string.new_agent_event))).setContentText(e.host).setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(intent).setAutoCancel(true).build();
        try{context.getSystemService(NotificationManager.class).notify(e.id().hashCode(),n);}catch(SecurityException ignored){}
    }
    interface Done {void finish(boolean success,String message);}
    void configure(String profile,boolean install,Done callback){
        worker.execute(()->{boolean success=false;try(SshCommand command=new SshCommand()){
            byte[] script=null;if(install)try(java.io.InputStream in=context.getAssets().open("ghostty_hook.py")){script=PairingClient.read(in,262144);}
            JSONObject result=new JSONObject(command.run(context,profile,install?INSTALL:COMMAND+"uninstall",script));success=result.optBoolean("ok");
            if(success)preferences.edit().putBoolean("host_"+profile,install).commit();
        }catch(Exception ignored){}boolean ok=success;main.post(()->{callback.finish(ok,ok?(install?tr(R.string.hook_installed_start_a_new_claude_code_session_to_use_it):tr(R.string.hook_uninstalled)):tr(R.string.operation_failed_check_ssh_python_3_and_remote_directory_permissi));refresh();});});
    }
    void respond(Event event,String behavior,JSONObject answers,Done callback){
        if(!event.pending()){callback.finish(false,tr(R.string.expired_return_to_the_terminal));return;}
        worker.execute(()->{boolean success=false;try(SshCommand command=new SshCommand()){
            JSONObject body=new JSONObject().put("id",event.data.getString("id")).put("behavior",behavior);if(answers!=null)body.put("answers",answers);
            success=new JSONObject(command.run(context,event.profile,COMMAND+"respond",body.toString().getBytes(StandardCharsets.UTF_8))).optBoolean("ok");
        }catch(Exception ignored){}boolean ok=success;main.post(()->{if(ok){try{event.data.put("status","answered");}catch(JSONException ignored){}markRead(event);persist();}callback.finish(ok,ok?tr(R.string.responded):tr(R.string.not_delivered_or_expired_refresh_to_confirm));refresh();});});
    }
}
