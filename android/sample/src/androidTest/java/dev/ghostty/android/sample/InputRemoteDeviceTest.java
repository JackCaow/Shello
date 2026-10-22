package dev.ghostty.android.sample;

import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Opt-in SSH + tmux + vim acceptance; restricted to a caller-created private socket/file. */
public class InputRemoteDeviceTest extends AttachmentTestSupport {
    @Test public void configuredKeysOperateRealShellAndVim()throws Exception{
        String socket=InstrumentationRegistry.getArguments().getString("controlsSocket"),file=InstrumentationRegistry.getArguments().getString("controlsFile");assumeNotNull(socket,file);
        assertTrue(socket.matches("/tmp/shello-controls-[0-9a-f]{8}\\.sock"));assertTrue(file.matches("/tmp/shello-controls-[0-9a-f]{8}\\.txt"));String profile=profile();assertNotNull(temporaryProfile);open();
        SshSessionService owner=(SshSessionService)field(activity,"sshService");InputPreferences preferences=new InputPreferences(context);String savedPrefix=preferences.prefs.getString("prefix_Tmux",null);java.util.Map<String,String> mappings=new java.util.HashMap<>();for(String key:new String[]{"swipe_left","two_left","two_right"})mappings.put(key,preferences.prefs.getString("gesture_"+key,null));String previous=owner.selected()==null?null:owner.selected().id,active=new SshProfileStore(context).activeId();String tmux="/opt/homebrew/bin/tmux -S "+SshCommand.quote(socket);TerminalControls[] controls={null};
        try{
            String prefix=run(profile,tmux+" show-option -gv prefix").trim();InputActions.parse(prefix);preferences.prefs.edit().putString("prefix_Tmux",prefix).commit();preferences.mapping("swipe_left","mux_next");preferences.mapping("two_left","pane_next");preferences.mapping("two_right","pane_previous");
            inst.runOnMainSync(()->SshSessionService.requestConnect(context,profile));await(()->owner.selected()!=null&&profile.equals(owner.selected().profile())&&owner.connected());
            inst.runOnMainSync(()->{call(activity,"resumeSession");controls[0]=(TerminalControls)field(activity,"inputControls");owner.send(("exec "+tmux+" attach-session -t probe\r").getBytes(StandardCharsets.UTF_8));});
            long ready=SystemClock.uptimeMillis()+10000;while(!run(profile,tmux+" list-clients -F '#{client_session}'").contains("probe")&&SystemClock.uptimeMillis()<ready)SystemClock.sleep(100);
            assertTrue(run(profile,tmux+" list-clients -F '#{client_session}'").contains("probe"));
            inst.runOnMainSync(()->controls[0].sequence("text:printf 'SHELLO_CONTROL_REMOTE_OK\\n'",true));
            waitRemote(profile,tmux+" capture-pane -p -t probe:shell","(?s).*\\nSHELLO_CONTROL_REMOTE_OK\\r?\\n.*");
            inst.runOnMainSync(()->controls[0].perform("preset_Tmux_1"));waitRemote(profile,tmux+" display-message -p '#{window_name}'","(?s).*vim.*");
            inst.runOnMainSync(()->{controls[0].sequence("i",false);controls[0].sequence("text:中文 👨‍👩‍👧‍👦 👍🏽",false);controls[0].sequence("ESCAPE",false);controls[0].sequence("text::w",true);});
            waitRemote(profile,"cat "+SshCommand.quote(file),"(?s).*中文 👨‍👩‍👧‍👦 👍🏽.*");capture("controls-ssh-vim");
            inst.runOnMainSync(()->controls[0].perform("preset_Tmux_2"));waitRemote(profile,tmux+" display-message -p '#{window_name}'","(?s).*shell.*");
            swipe(false,true);waitRemote(profile,tmux+" display-message -p '#{window_name}'","(?s).*vim.*");
            String[] panes=run(profile,tmux+" list-panes -t probe:vim -F '#{pane_id}'").trim().split("\\s+");assertEquals("Create three disposable vim-window panes for navigation acceptance",3,panes.length);
            swipe(true,true);waitRemote(profile,tmux+" display-message -p -t probe:vim '#{pane_id}'",java.util.regex.Pattern.quote(panes[1])+"\\s*");
            swipe(true,true);waitRemote(profile,tmux+" display-message -p -t probe:vim '#{pane_id}'",java.util.regex.Pattern.quote(panes[2])+"\\s*");
            swipe(true,false);waitRemote(profile,tmux+" display-message -p -t probe:vim '#{pane_id}'",java.util.regex.Pattern.quote(panes[1])+"\\s*");
            swipe(true,false);waitRemote(profile,tmux+" display-message -p -t probe:vim '#{pane_id}'",java.util.regex.Pattern.quote(panes[0])+"\\s*");
            waitRemote(profile,tmux+" capture-pane -p -t probe:vim","(?s).*中文.*");
        }finally{android.content.SharedPreferences.Editor restore=preferences.prefs.edit();for(var mapping:mappings.entrySet()){if(mapping.getValue()==null)restore.remove("gesture_"+mapping.getKey());else restore.putString("gesture_"+mapping.getKey(),mapping.getValue());}restore.commit();if(savedPrefix==null)preferences.prefs.edit().remove("prefix_Tmux").commit();else preferences.prefs.edit().putString("prefix_Tmux",savedPrefix).commit();inst.runOnMainSync(()->{owner.closeProfile(profile);if(previous!=null)owner.select(previous);android.content.SharedPreferences.Editor e=context.getSharedPreferences("ssh_profiles",0).edit();if(active==null)e.remove("active");else e.putString("active",active);e.commit();});}
    }
    private void swipe(boolean two,boolean left){inst.runOnMainSync(()->{
        dev.ghostty.android.TerminalView terminal=(dev.ghostty.android.TerminalView)field(activity,"terminal");android.view.View touch=terminal.getChildAt(1);float start=terminal.getWidth()*(left?.8f:.2f),end=terminal.getWidth()*(left?.2f:.8f),y=terminal.getHeight()*.5f,spread=activity.getResources().getDisplayMetrics().density*30;long t=SystemClock.uptimeMillis();
        android.view.MotionEvent event=android.view.MotionEvent.obtain(t,t,0,start,y,0);touch.dispatchTouchEvent(event);event.recycle();
        if(two){android.view.MotionEvent.PointerProperties[] properties=new android.view.MotionEvent.PointerProperties[2];android.view.MotionEvent.PointerCoords[] points=new android.view.MotionEvent.PointerCoords[2];for(int i=0;i<2;i++){properties[i]=new android.view.MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=android.view.MotionEvent.TOOL_TYPE_FINGER;points[i]=new android.view.MotionEvent.PointerCoords();points[i].pressure=1;points[i].size=1;points[i].y=y;}
            for(int action:new int[]{android.view.MotionEvent.ACTION_POINTER_DOWN|(1<<8),android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_POINTER_UP|(1<<8)}){float x=action==(android.view.MotionEvent.ACTION_POINTER_DOWN|(1<<8))?start:end;points[0].x=x-spread;points[1].x=x+spread;event=android.view.MotionEvent.obtain(t,t+40,action,2,properties,points,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);touch.dispatchTouchEvent(event);event.recycle();}}
        else{event=android.view.MotionEvent.obtain(t,t+40,2,end,y,0);touch.dispatchTouchEvent(event);event.recycle();}
        event=android.view.MotionEvent.obtain(t,t+80,1,end,y,0);touch.dispatchTouchEvent(event);event.recycle();
    });}
    private void waitRemote(String profile,String command,String pattern)throws Exception{long until=SystemClock.uptimeMillis()+15000;String text;do{text=run(profile,command);if(text.matches(pattern))return;SystemClock.sleep(120);}while(SystemClock.uptimeMillis()<until);capture("controls-failure");throw new AssertionError("Remote TUI did not reach the expected state");}
}
