package dev.ghostty.android.sample;

import android.content.Context;
import android.os.*;
import android.net.*;
import dev.ghostty.android.TerminalSession;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** One independent SSH/VT/retry state machine, owned by SshSessionService. Main thread only. */
final class SshSessionConnection {
    interface Listener { void changed(); void detach(); }
    private final Context context;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final SshResumeState resumeState;
    private final File profileDirectory;
    final String target;
    TerminalSession terminal;
    private SshTransport ssh;
    private String profileIdentity;
    String profileName="本机",status="已断开";
    boolean connected,foreground;
    private boolean autoReconnect,destroyed;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network activeNetwork;
    private Runnable retryTask,stableTask;
    private Properties profileConfig;
    private int generation,retryAttempt;
    SshSessionConnection(Context context,File directory,String id,Listener listener){
        this.context=context;this.profileDirectory=directory;this.listener=listener;target=SessionTarget.target(id);
        resumeState=new SshResumeState(directory,context.getSharedPreferences("session_resume_"+SessionTarget.storageKey(id),0),target==null?null:!target.startsWith("plain_"));
        try{profileName=SshProfileStore.properties(directory).getProperty("name","本机");}catch(IOException ignored){}
    }
    boolean shouldResume(){return resumeState.pending();}
    void visible(){if(foreground&&autoReconnect&&retryTask!=null){closeAttempt();retryAttempt=0;startAttempt();}}
    private void changed(){listener.changed();}
    void destroy(boolean explicit){
        destroyed=true;if(explicit)resumeState.clear();cancelConnection();
        listener.detach();if(terminal!=null){terminal.close();terminal=null;}
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
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
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
            try{context.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(callback);}
            catch(IllegalArgumentException ignored){}
        }
    }
    void connect(){
        foreground=true;
        cancelConnection();
        File nextDirectory=profileDirectory;
        Properties config;
        try{config=SshProfileStore.properties(nextDirectory);}
        catch(IOException|IllegalArgumentException error){finish("配置无效");return;}
        if(profileConfig!=null&&!config.equals(profileConfig)&&terminal!=null){
            listener.detach();terminal.close();terminal=null;
        }
        profileConfig=config;profileIdentity=resumeState.identity();
        profileName=config.getProperty("name","本机");autoReconnect=target==null?"tmux".equals(config.getProperty("sessionMode")):!target.startsWith("plain_");
        if(terminal==null)terminal=new TerminalSession(40,20);
        if(autoReconnect)startNetworkMonitoring();
        changed();startAttempt();
    }
    private void startAttempt(){
        if(destroyed||!foreground)return;
        retryTask=null;
        if(networkCallback!=null&&activeNetwork==null){status="网络不可用，等待恢复";changed();return;}
        final int attempt=++generation;
        final boolean[] firstOutput={true};
        ssh=new SshTransport(context,profileDirectory,terminal.getColumns(),terminal.getRows(),new SshTransport.Listener(){
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
        ssh.target(target).connect();
    }
    void send(byte[] bytes){if(connected&&ssh!=null)ssh.send(bytes);}
    void resize(int columns,int rows){if(ssh!=null)ssh.resize(columns,rows);}
    void disconnect(){finish("已断开");}
    private void finish(String value){finish(value,true);}
    private void finish(String value,boolean clearResume){
        if(clearResume)resumeState.clear();
        cancelConnection();status=value;
        foreground=false;
        changed();
    }
    void clearSession(){
        disconnect();
        listener.detach();
        if(terminal!=null){terminal.close();terminal=null;}
    }
}
