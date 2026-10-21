package dev.ghostty.android.sample;

import android.app.*;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.net.ConnectivityManager;
import android.net.Network;
import dev.ghostty.android.TerminalSession;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Sample-only owner of the SSH connection and main-thread VT, independent of any Activity. */
public final class SshSessionService extends Service {
    static final String CONNECT="dev.ghostty.android.sample.CONNECT", DISCONNECT="dev.ghostty.android.sample.DISCONNECT";
    private static final String CHANNEL="ssh_session";
    private static final int NOTIFICATION=41;
    interface Observer { void changed(); void detach(); }
    final class LocalBinder extends Binder { SshSessionService service(){return SshSessionService.this;} }
    private final LocalBinder binder=new LocalBinder();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Observer observer;
    private TerminalSession terminal;
    private SshTransport ssh;
    private SshResumeState resumeState;
    private String profileIdentity;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network activeNetwork;
    private Runnable retryTask,stableTask;
    private boolean connected,autoReconnect,foreground,destroyed;
    private int generation,retryAttempt;
    private String status="已断开";

    /** A visibility change can race the request; let the user retry rather than crash the app. */
    static String requestConnect(Context context){
        try{context.startForegroundService(new Intent(context,SshSessionService.class).setAction(CONNECT));return null;}
        catch(IllegalStateException|SecurityException rejected){
            String reason=rejected.getClass().getSimpleName();
            android.util.Log.i("GhosttySsh","Foreground service request rejected: "+reason);
            return reason;
        }
    }
    @Override public void onCreate(){
        super.onCreate();
        resumeState=new SshResumeState(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL,"终端会话",NotificationManager.IMPORTANCE_LOW));
        wakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ghostty:SSHSession");
        wakeLock.setReferenceCounted(false);
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&CONNECT.equals(intent.getAction())){
            // Called by a visible Activity. Publish the notification before any connection work.
            try{
                if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                else startForeground(NOTIFICATION,notification());
            }catch(IllegalStateException|SecurityException rejected){
                // Promotion is checked separately by Android; retain tmux resume intent for a visible retry.
                android.util.Log.i("GhosttySsh","Foreground promotion rejected: "+rejected.getClass().getSimpleName());
                finish("系统暂不允许启动会话",false);return START_NOT_STICKY;
            }
            foreground=true;
            connect();
        }else if(intent!=null&&DISCONNECT.equals(intent.getAction()))disconnect();
        // Do not silently recreate a terminated app/session after process death or force-stop.
        return START_NOT_STICKY;
    }
    TerminalSession terminal(){return terminal;}
    boolean connected(){return connected;}
    boolean active(){return foreground;}
    boolean shouldResume(){return terminal==null&&!foreground&&resumeState.pending();}
    String status(){return status;}
    void observe(Observer value){
        if(observer!=null&&observer!=value)observer.detach();
        // A vendor may suspend retries while locked. Do not make a visible user wait for backoff.
        if(value!=null&&foreground&&autoReconnect&&retryTask!=null){
            closeAttempt();retryAttempt=0;startAttempt();
        }
        observer=value;if(observer!=null)observer.changed();refreshNotification();
    }
    void removeObserver(Observer value){if(observer==value)observer=null;}
    private void changed(){
        if(observer!=null)observer.changed();
        refreshNotification();
    }
    void refreshNotification(){
        if(foreground)getSystemService(NotificationManager.class).notify(NOTIFICATION,notification());
    }
    private Notification notification(){
        Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,SshSessionService.class).setAction(DISCONNECT),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("SSH 终端会话").setContentText(status).setContentIntent(content)
                .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null,"断开",stop).build());
        if(Build.VERSION.SDK_INT>=31)builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }
    private void cancelConnection(){
        stopNetworkMonitoring();closeAttempt();autoReconnect=false;retryAttempt=0;
    }
    private void closeAttempt(){
        generation++;connected=false;
        if(retryTask!=null){handler.removeCallbacks(retryTask);retryTask=null;}
        if(stableTask!=null){handler.removeCallbacks(stableTask);stableTask=null;}
        if(ssh!=null){ssh.close();ssh=null;}
    }
    private void startNetworkMonitoring(){
        ConnectivityManager manager=getSystemService(ConnectivityManager.class);
        activeNetwork=manager.getActiveNetwork();
        networkCallback=new ConnectivityManager.NetworkCallback(){
            private boolean current(){return networkCallback==this&&!destroyed&&foreground&&autoReconnect;}
            @Override public void onAvailable(Network network){
                if(!current()||network.equals(activeNetwork))return;
                activeNetwork=network;
                closeAttempt();retryAttempt=0;startAttempt();
            }
            @Override public void onLost(Network network){
                if(!current()||!network.equals(activeNetwork))return;
                activeNetwork=null;closeAttempt();status="网络不可用，等待恢复";changed();
            }
        };
        try{manager.registerDefaultNetworkCallback(networkCallback,handler);}
        catch(SecurityException|IllegalArgumentException unavailable){
            // Keep the existing keepalive/backoff path if a vendor rejects monitoring.
            networkCallback=null;activeNetwork=null;
        }
    }
    private void stopNetworkMonitoring(){
        ConnectivityManager.NetworkCallback callback=networkCallback;
        networkCallback=null;activeNetwork=null;
        if(callback!=null){
            try{getSystemService(ConnectivityManager.class).unregisterNetworkCallback(callback);}
            catch(IllegalArgumentException ignored){}
        }
    }
    private void connect(){
        cancelConnection();
        profileIdentity=resumeState.identity();
        Properties config=new Properties();
        try(InputStream in=new FileInputStream(new File(getFilesDir(),"ssh/connection.properties"))){
            config.load(in);autoReconnect="tmux".equals(config.getProperty("sessionMode"));
        }catch(IOException|IllegalArgumentException error){finish("配置无效");return;}
        if(terminal==null)terminal=new TerminalSession(40,20);
        // Deliberately held only for a user-started, notified session; released on every terminal stop.
        if(!wakeLock.isHeld())wakeLock.acquire();
        if(autoReconnect)startNetworkMonitoring();
        changed();startAttempt();
    }
    private void startAttempt(){
        if(destroyed||!foreground)return;
        retryTask=null;
        if(networkCallback!=null&&activeNetwork==null){status="网络不可用，等待恢复";changed();return;}
        final int attempt=++generation;
        final boolean[] firstOutput={true};
        ssh=new SshTransport(new File(getFilesDir(),"ssh"),terminal.getColumns(),terminal.getRows(),new SshTransport.Listener(){
            private boolean current(){return !destroyed&&attempt==generation;}
            @Override public void onState(String value,boolean ready){
                if(!current())return;
                connected=ready;status=value;changed();
                if(ready){resumeState.remember(profileIdentity);stableTask=()->{if(current()&&connected)retryAttempt=0;};handler.postDelayed(stableTask,10000);}
            }
            @Override public void onClosed(String value,boolean retryable){
                if(!current())return;
                ssh=null;connected=false;
                if(stableTask!=null){handler.removeCallbacks(stableTask);stableTask=null;}
                if(autoReconnect&&retryable){
                    long delay=Math.min(15000,1000L<<Math.min(retryAttempt++,4));
                    status="已断开，"+(delay/1000)+" 秒后重连";changed();
                    retryTask=()->{if(current()&&autoReconnect)startAttempt();};handler.postDelayed(retryTask,delay);
                }else finish(value);
            }
            @Override public void onOutput(byte[] bytes){
                if(!current())return;
                if(firstOutput[0]){
                    firstOutput[0]=false;
                    terminal.write("\033c\033]10;#d4d4d4\007\033]11;#1e1e1e\007\033]12;#00d86c\007".getBytes(StandardCharsets.UTF_8));
                }
                byte[] replies=terminal.write(bytes);
                if(replies.length>0&&ssh!=null)ssh.send(replies);
            }
        });
        ssh.connect();
    }
    void send(byte[] bytes){if(connected&&ssh!=null)ssh.send(bytes);}
    void resize(int columns,int rows){if(ssh!=null)ssh.resize(columns,rows);}
    void disconnect(){finish("已断开");}
    private void finish(String value){finish(value,true);}
    private void finish(String value,boolean clearResume){
        if(clearResume)resumeState.clear();
        cancelConnection();status=value;
        foreground=false;
        if(wakeLock.isHeld())wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();changed();
    }
    void clearSession(){
        disconnect();
        if(observer!=null)observer.detach();
        if(terminal!=null){terminal.close();terminal=null;}
    }
    @Override public void onDestroy(){
        destroyed=true;cancelConnection();
        if(wakeLock.isHeld())wakeLock.release();
        if(observer!=null){observer.detach();observer=null;}
        if(terminal!=null){terminal.close();terminal=null;}
        super.onDestroy();
    }
}
