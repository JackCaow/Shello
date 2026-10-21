package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;

/** Host hook events travel over pinned SSH. Only foreground UI / an active SSH service polls. */
final class AgentHub {
    static final String COMMAND="python3 \"$HOME/.local/state/ghostty-android/ghostty_hook.py\" ";
    static final String INSTALL="umask 077; mkdir -p \"$HOME/.local/state/ghostty-android\" && cat > \"$HOME/.local/state/ghostty-android/ghostty_hook.py.new\" && mv \"$HOME/.local/state/ghostty-android/ghostty_hook.py.new\" \"$HOME/.local/state/ghostty-android/ghostty_hook.py\" && "+COMMAND+"install";
    static final class Event {final String profile,host;final JSONObject data;Event(String p,String h,JSONObject d){profile=p;host=h;data=d;}String id(){return profile+":"+data.optString("id");}boolean pending(){return "pending".equals(data.optString("status"));}}
    private static AgentHub instance;
    static synchronized AgentHub get(Context c){if(instance==null)instance=new AgentHub(c.getApplicationContext());return instance;}
    final Context context;final android.content.SharedPreferences preferences;
    final Handler main=new Handler(Looper.getMainLooper());
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    final ArrayList<Event> events=new ArrayList<>();final Map<String,Integer> states=new HashMap<>();
    final Set<Runnable> observers=new HashSet<>();final Set<String> notified=new LinkedHashSet<>();
    boolean keepAlive,busy,scheduled;int users;
    private AgentHub(Context c){context=c;preferences=c.getSharedPreferences("agent",0);c.getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("agent_events",tr(R.string.agent_event),NotificationManager.IMPORTANCE_DEFAULT));}
    void attach(Runnable observer){observers.add(observer);users++;refresh();}
    void detach(Runnable observer){if(observers.remove(observer))users=Math.max(0,users-1);}
    void keepAlive(boolean value){keepAlive=value;if(value)refresh();}
    boolean enabled(String id){return preferences.getBoolean("host_"+id,false);}
    boolean read(Event e){return preferences.getBoolean("read_"+e.id(),false);}
    void markRead(Event e){SharedPreferences.Editor edit=preferences.edit().putBoolean("read_"+e.id(),true);int count=0;for(String key:preferences.getAll().keySet())if(key.startsWith("read_")&&++count>500)edit.remove(key);edit.apply();changed();}
    List<Event> snapshot(){return new ArrayList<>(events);}
    int pending(String profile){int count=0;for(Event e:events)if((profile==null||e.profile.equals(profile))&&e.pending())count++;return count;}
    void changed(){for(Runnable r:new ArrayList<>(observers))r.run();}
    void refresh(){
        if(busy||users==0&&!keepAlive)return;busy=true;
        worker.execute(()->{List<Event> next=new ArrayList<>();Map<String,Integer> status=new HashMap<>();Set<String> successful=new HashSet<>();
            for(SshProfileStore.Entry p:new SshProfileStore(context).list())if(enabled(p.id)){
                try(SshCommand command=new SshCommand()){
                    JSONObject result=new JSONObject(command.run(context,p.id,COMMAND+"poll",null));JSONArray rows=result.getJSONArray("events");long now=(long)(result.getDouble("now")*1000);
                    for(int i=0;i<Math.min(rows.length(),100);i++){JSONObject event=rows.getJSONObject(i);event.put("remainingMs",Math.max(0,(long)(event.optDouble("expires")*1000)-now));next.add(new Event(p.id,p.name,event));}
                    status.put(p.id,R.string.connected);successful.add(p.id);
                }catch(Exception error){status.put(p.id,R.string.cannot_read_hook_check_the_connection_or_reinstall);}
            }
            main.post(()->{
                // Preserve visible history on network errors, but never allow a stale approval.
                for(Event old:events)if(enabled(old.profile)&&!successful.contains(old.profile)){try{if(old.pending())old.data.put("status","offline");}catch(JSONException ignored){}next.add(old);}
                events.clear();events.addAll(next);events.sort((a,b)->Double.compare(b.data.optDouble("created"),a.data.optDouble("created")));states.clear();states.putAll(status);busy=false;
                for(Event event:events)if(notified.add(event.id())&&!read(event)&&preferences.getBoolean("notifications",true))notifyEvent(event);
                while(notified.size()>500)notified.remove(notified.iterator().next());
                changed();if(!scheduled&&(users>0||keepAlive)){scheduled=true;main.postDelayed(()->{scheduled=false;refresh();},5000);}
            });
        });
    }
    private void notifyEvent(Event e){
        if(users>0)return;Intent open=new Intent(context,AgentActivity.class);PendingIntent intent=PendingIntent.getActivity(context,80,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(context,"agent_events").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(e.pending()?tr(R.string.an_agent_needs_your_response):tr(R.string.new_agent_event)).setContentText(e.host).setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(intent).setAutoCancel(true).build();
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
        worker.execute(()->{boolean success=false;try(SshCommand command=new SshCommand()){
            JSONObject body=new JSONObject().put("id",event.data.getString("id")).put("behavior",behavior);if(answers!=null)body.put("answers",answers);
            success=new JSONObject(command.run(context,event.profile,COMMAND+"respond",body.toString().getBytes(StandardCharsets.UTF_8))).optBoolean("ok");
        }catch(Exception ignored){}boolean ok=success;main.post(()->{if(ok){markRead(event);try{event.data.put("status","answered");}catch(JSONException ignored){}}callback.finish(ok,ok?tr(R.string.responded):tr(R.string.not_delivered_or_expired_refresh_to_confirm));refresh();});});
    }
}
