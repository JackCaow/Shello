package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.os.*;
import dev.ghostty.android.TerminalSession;
import java.io.*;
import java.util.*;
import org.json.*;

/** Owns the ordered open tabs and independent SSH connections; Activities only attach views. */
public final class SshSessionService extends Service {
    static final String CONNECT="dev.ghostty.android.sample.CONNECT", DISCONNECT="dev.ghostty.android.sample.DISCONNECT";
    private static final String CHANNEL="ssh_session";
    private static final int NOTIFICATION=41;
    interface Observer { void changed(); void detach(); }
    final class LocalBinder extends Binder { SshSessionService service(){return SshSessionService.this;} }
    static final class Tab {
        final String id;final SshSessionConnection connection;
        TerminalSession local;Bitmap preview;String caption="";String draft="";boolean draftMode;
        Tab(String id,SshSessionConnection connection){this.id=id;this.connection=connection;}
        boolean remote(){return connection!=null;}
        String name(){return remote()?connection.profileName:"本地终端";}
        String sessionName(){if(!caption.isEmpty())return caption;if(!remote())return "local";String target=connection.target;return target==null?"":target.startsWith("=")?target.substring(1):target.startsWith("plain_")?"shell":target.startsWith("ghostty_")?"shell":target;}
        String profile(){return SessionTarget.profile(id);}
        TerminalSession terminal(){return remote()?connection.terminal:local;}
    }
    private final LocalBinder binder=new LocalBinder();
    private final ArrayList<Tab> tabs=new ArrayList<>();
    private Observer observer;
    private Tab selected;
    private PowerManager.WakeLock wakeLock;
    private boolean foreground,destroyed,mutating;
    private SharedPreferences state;
    private SessionPreviewStore previews;

    static String requestConnect(Context context){return requestConnect(context,null);}
    static String requestConnect(Context context,String profile){return requestConnect(context,profile,false);}
    static String requestConnect(Context context,String profile,boolean restore){
        try{context.startForegroundService(new Intent(context,SshSessionService.class).setAction(CONNECT).putExtra("profile",profile).putExtra("restore",restore));return null;}
        catch(IllegalStateException|SecurityException rejected){
            android.util.Log.i("GhosttySsh","Foreground service request rejected: "+rejected.getClass().getSimpleName());
            return rejected.getClass().getSimpleName();
        }
    }
    @Override public void onCreate(){
        super.onCreate();previews=new SessionPreviewStore(this);state=getSharedPreferences("terminal_tabs",0);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"终端会话",NotificationManager.IMPORTANCE_LOW));
        wakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ghostty:SSHSession");wakeLock.setReferenceCounted(false);
        try{JSONArray ids=new JSONArray(state.getString("order","[]"));for(int i=0;i<ids.length();i++)add(ids.getString(i));}catch(JSONException ignored){}
        if(!state.getBoolean("initialized",false)){
            if(new SshResumeState(this).pending())add(profileId());else add("local");
        }
        selected=find(state.getString("selected",null));if(selected==null&&!tabs.isEmpty())selected=tabs.get(0);persist();
        ArrayList<String> retained=new ArrayList<>();for(Tab tab:tabs)retained.add(tab.id);previews.retain(retained);
    }
    private String profileId(){String id=new SshProfileStore(this).activeId();return id==null?"fixture":id;}
    private Tab add(String id){
        Tab existing=find(id);if(existing!=null)return existing;
        Tab tab;
        if("local".equals(id)){
            tab=new Tab(id,null);tab.local=new TerminalSession(40,20);
            tab.local.write("\033]10;#d4d4d4\007\033]11;#1e1e1e\007\033]12;#00d86c\007本地演示 · Offline echo\r\n\r\n› ".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }else{
            File directory;
            try{String target=SessionTarget.target(id);if(target!=null)SessionTarget.validate(target);directory="fixture".equals(id)?new File(getFilesDir(),"ssh"):new SshProfileStore(this).directory(SessionTarget.profile(id));}
            catch(IllegalArgumentException invalid){return null;}
            if(!SshProfileStore.exists(directory))return null;
            tab=new Tab(id,new SshSessionConnection(this,directory,id,new SshSessionConnection.Listener(){
                public void changed(){SshSessionService.this.changed();}
                public void detach(){if(selected!=null&&selected.id.equals(id)&&observer!=null)observer.detach();}
            }));
        }
        tab.caption=getSharedPreferences("terminal_titles",0).getString(id,"");tabs.add(tab);
        previews.load(id,bitmap->{if(bitmap==null)return;if(destroyed||find(id)!=tab||tab.preview!=null){bitmap.recycle();return;}tab.preview=bitmap;if(observer!=null)observer.changed();});
        return tab;
    }
    void rememberPreview(Tab tab,Bitmap bitmap){
        if(destroyed||find(tab.id)!=tab){bitmap.recycle();return;}
        tab.preview=bitmap;previews.save(tab.id,bitmap);
    }
    List<Tab> tabs(){return new ArrayList<>(tabs);}
    Tab selected(){return selected;}
    Tab find(String id){for(Tab tab:tabs)if(tab.id.equals(id))return tab;return null;}
    void select(String id){
        Tab next=find(id);if(next==null)return;
        if(selected!=next&&observer!=null)observer.detach();selected=next;
        if(next.remote())try{if(!"fixture".equals(id))new SshProfileStore(this).activate(SessionTarget.profile(id));else getSharedPreferences("ssh_profiles",0).edit().remove("active").commit();}catch(IOException ignored){}
        persist();changed();
    }
    void openLocal(){Tab tab=add("local");select(tab.id);}
    void move(String id,int index){Tab tab=find(id);if(tab==null)return;tabs.remove(tab);tabs.add(Math.max(0,Math.min(index,tabs.size())),tab);persist();changed();}
    void close(String id){
        Tab tab=find(id);if(tab==null)return;
        mutating=true;
        if(selected==tab){if(observer!=null)observer.detach();selected=null;}
        tabs.remove(tab);previews.remove(id);getSharedPreferences("terminal_titles",0).edit().remove(id).apply();persist(); // Remove restore intent before closing sockets.
        if(tab.remote())tab.connection.destroy(true);else if(tab.local!=null)tab.local.close();
        if(tab.preview!=null){tab.preview.recycle();tab.preview=null;}
        if(id.equals(profileId()))new SshResumeState(this).clear();
        if(selected==null&&!tabs.isEmpty())select(tabs.get(0).id);
        mutating=false;persist();changed();
    }
    void closeProfile(String id){for(Tab tab:new ArrayList<>(tabs))if(tab.profile().equals(id))close(tab.id);}
    void closeAll(){for(Tab tab:new ArrayList<>(tabs))close(tab.id);new SshResumeState(this).clear();}
    private void persist(){JSONArray ids=new JSONArray();for(Tab tab:tabs)ids.put(tab.id);state.edit().putBoolean("initialized",true).putString("order",ids.toString()).putString("selected",selected==null?null:selected.id).commit();}
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&CONNECT.equals(intent.getAction())){
            try{
                if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                else startForeground(NOTIFICATION,notification());
            }catch(IllegalStateException|SecurityException rejected){
                android.util.Log.i("GhosttySsh","Foreground promotion rejected: "+rejected.getClass().getSimpleName());if(!foreground)stopSelf();return START_NOT_STICKY;
            }
            foreground=true;mutating=true;
            String id=intent.getStringExtra("profile");if(id==null)id=profileId();
            boolean restore=intent.getBooleanExtra("restore",false);
            Tab tab=restore?selected:add(id);
            if(restore&&(tab==null||!tab.remote()||!tab.connection.shouldResume())){
                Tab eligible=null;for(Tab candidate:tabs)if(candidate.remote()&&candidate.connection.shouldResume()){eligible=candidate;break;}
                // Migration from the single-session release uses its legacy resume marker once.
                if(eligible!=null)tab=eligible;else tab=new SshResumeState(this).pending()?add(profileId()):null;
            }
            if(tab!=null&&tab.remote()){select(tab.id);tab.connection.connect();}
            // Reopen eligible tmux tabs only after a visible Activity starts the service.
            for(Tab other:tabs)if(other!=tab&&other.remote()&&!other.connection.foreground&&other.connection.shouldResume())other.connection.connect();
            mutating=false;persist();changed();
        }else if(intent!=null&&DISCONNECT.equals(intent.getAction()))disconnectAll();
        return START_NOT_STICKY;
    }
    TerminalSession terminal(){return selected==null?null:selected.terminal();}
    boolean connected(){return selected!=null&&selected.remote()&&selected.connection.connected;}
    boolean active(){return foreground;}
    boolean shouldResume(){
        if(foreground)return false;
        for(Tab tab:tabs)if(tab.remote()&&tab.connection.shouldResume())return true;
        return new SshResumeState(this).pending();
    }
    String status(){return selected==null?"已断开":selected.remote()?selected.connection.status:"本地终端";}
    String profileName(){return selected==null?"本机":selected.name();}
    void observe(Observer value){
        if(observer!=null&&observer!=value)observer.detach();observer=value;
        for(Tab tab:tabs)if(tab.remote())tab.connection.visible();
        if(observer!=null)observer.changed();refreshNotification();
    }
    void removeObserver(Observer value){if(observer==value)observer=null;}
    private void changed(){
        if(mutating||destroyed)return;
        boolean running=false;for(Tab tab:tabs)if(tab.remote()&&tab.connection.foreground)running=true;
        AgentHub.get(this).keepAlive(running);
        if(running){if(!wakeLock.isHeld())wakeLock.acquire();}
        else {if(wakeLock.isHeld())wakeLock.release();if(foreground){foreground=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}}
        if(connected()&&selected.connection.target==null)new SshResumeState(this).remember(new SshResumeState(this).identity());
        if(observer!=null)observer.changed();refreshNotification();
    }
    void refreshNotification(){if(foreground)getSystemService(NotificationManager.class).notify(NOTIFICATION,notification());}
    private Notification notification(){
        Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,SshSessionService.class).setAction(DISCONNECT),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        int count=0;for(Tab tab:tabs)if(tab.remote()&&tab.connection.foreground)count++;
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(count>1?count+" 个 SSH 终端会话":"SSH 终端会话").setContentText(status()).setContentIntent(content)
                .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null,"全部断开",stop).build());
        if(Build.VERSION.SDK_INT>=31)builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);return builder.build();
    }
    void send(byte[] bytes){if(selected!=null&&selected.remote())selected.connection.send(bytes);}
    void resize(int columns,int rows){if(selected!=null&&selected.remote())selected.connection.resize(columns,rows);}
    void disconnect(){if(selected!=null&&selected.remote())selected.connection.disconnect();new SshResumeState(this).clear();}
    private void disconnectAll(){for(Tab tab:tabs)if(tab.remote())tab.connection.disconnect();new SshResumeState(this).clear();}
    void clearSession(){if(selected!=null)close(selected.id);}
    @Override public void onDestroy(){
        AgentHub.get(this).keepAlive(false);
        destroyed=true;if(observer!=null){observer.detach();observer=null;}
        for(Tab tab:tabs){if(tab.remote())tab.connection.destroy(false);else if(tab.local!=null)tab.local.close();if(tab.preview!=null)tab.preview.recycle();}
        if(wakeLock.isHeld())wakeLock.release();super.onDestroy();
    }
}
