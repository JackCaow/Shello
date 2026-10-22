package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import dev.ghostty.android.TerminalSession;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** App chrome has its own palette; switching it must not replace the SSH-owned terminal session. */
public class UiThemeTest extends AttachmentTestSupport {
    private double contrast(int a,int b){double x=Color.luminance(a),y=Color.luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
    @Test public void textAndFileArtworkHaveContrastInBothPalettes(){
        for(boolean light:new boolean[]{false,true}){
            UiTheme theme=UiTheme.forMode(light);
            for(int text:new int[]{SampleUi.TEXT,SampleUi.MUTED,SampleUi.GREEN,SampleUi.DANGER,0xffffbd4a,0xff00b99a,0xffd98b68})
                for(int bg:new int[]{theme.canvas,theme.surface,theme.input,theme.background(0xff15181a)})
                    assertTrue("Low text contrast: light="+light+" color="+Integer.toHexString(text),contrast(theme.foreground(text),bg)>=4.5);
            for(AttachmentFileType type:AttachmentFileType.values())assertTrue("Low file artwork contrast: "+type,contrast(theme.foreground(type.foreground),theme.background(type.background))>=3);
            assertTrue("Selected segment contrast",contrast(theme.accent,theme.background(0xff20372b))>=4.5);
            if(light)assertTrue("Switch thumb contrast",contrast(Color.WHITE,theme.toggleOff)>=3);
            assertTrue("Primary action label contrast",contrast(theme.onAccent,theme.accent)>=4.5);
        }
    }
    @Test public void unknownAndContentColorsAreNotConverted(){
        UiTheme light=UiTheme.forMode(true);int arbitrary=0xff193b8d;
        assertEquals(arbitrary,light.foreground(arbitrary));assertEquals(arbitrary,light.background(arbitrary));
        assertEquals(Color.TRANSPARENT,light.background(Color.TRANSPARENT));assertEquals(0x66000000,light.background(0x66000000));
        for(int color:new int[]{SampleUi.TEXT,SampleUi.MUTED,SampleUi.GREEN,Color.BLACK,0xff1e1e1e})assertEquals(color,UiTheme.forMode(false).background(color));
    }
    @Test public void switchingThemeRecreatesPagesAndRetainsSessionAndDraft()throws Exception{
        SharedPreferences prefs=context.getSharedPreferences("appearance",0);String original=prefs.getString("theme",null);
        SshSessionService[] owner={null};SshSessionService.Tab[] tab={null};String[] previous={null};List<String> before=new ArrayList<>();
        // A local VT fixture has no started foreground SSH service. Keep one test binding
        // while the Activity is stopped, just as a running SSH connection retains its owner.
        ServiceConnection keepAlive=new ServiceConnection(){public void onServiceConnected(ComponentName name,android.os.IBinder binder){}public void onServiceDisconnected(ComponentName name){}};
        boolean[] retained={false};
        AtomicReference<Activity> foreground=new AtomicReference<>();
        Application.ActivityLifecycleCallbacks watcher=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){foreground.set(a);}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
        };
        Application app=(Application)context.getApplicationContext();app.registerActivityLifecycleCallbacks(watcher);
        try{
            prefs.edit().putString("theme","shello").commit();open();await(()->!activity.uiTheme().light&&activity.hasWindowFocus());
            inst.runOnMainSync(()->{
                retained[0]=context.bindService(new Intent(context,SshSessionService.class),keepAlive,Context.BIND_AUTO_CREATE);assertTrue(retained[0]);
                owner[0]=(SshSessionService)field(activity,"sshService");previous[0]=owner[0].selected()==null?null:owner[0].selected().id;
                @SuppressWarnings("unchecked") List<SshSessionService.Tab> tabs=(List<SshSessionService.Tab>)field(owner[0],"tabs");for(var t:tabs)before.add(t.id);
                tab[0]=new SshSessionService.Tab("light-ui-"+UUID.randomUUID(),null);tab[0].local=new TerminalSession(40,10);tabs.add(tab[0]);owner[0].select(tab[0].id);call(activity,"resumeSession");
            });draftMode();inst.runOnMainSync(()->{((EditText)field(activity,"draft")).setText("保留草稿 · 中文 👩🏽‍💻");call(activity,"hideKeyboard");});
            for(boolean light:new boolean[]{true,false}){
                MainActivity old=activity;
                inst.runOnMainSync(()->activity.startActivity(SettingsNavigation.intent(activity,"")));
                await(()->foreground.get() instanceof SettingsActivity&&foreground.get().hasWindowFocus());
                SettingsActivity index=(SettingsActivity)foreground.get();int[] scroll={0};
                inst.runOnMainSync(()->{index.scroll.scrollTo(0,index.dp(120));scroll[0]=index.scroll.getScrollY();index.startActivity(SettingsNavigation.intent(index,"theme"));});
                await(()->foreground.get() instanceof ThemeSettingsActivity&&foreground.get().hasWindowFocus());
                inst.runOnMainSync(()->{View choice=find(foreground.get().getWindow().getDecorView(),light?AppLanguage.tr(R.string.light):"Shello");assertNotNull(choice);choice.performClick();});
                await(()->foreground.get() instanceof ThemeSettingsActivity&&((ThemeSettingsActivity)foreground.get()).uiTheme().light==light&&foreground.get().hasWindowFocus());
                inst.runOnMainSync(()->{
                    Activity page=foreground.get();TypedArray a=page.obtainStyledAttributes(new int[]{android.R.attr.windowLightStatusBar,android.R.attr.windowLightNavigationBar});try{assertEquals(light,a.getBoolean(0,false));assertEquals(light,a.getBoolean(1,false));}finally{a.recycle();}
                    page.finish();
                });
                await(()->foreground.get() instanceof SettingsActivity&&foreground.get()!=index&&foreground.get().hasWindowFocus()&&((SettingsActivity)foreground.get()).uiTheme().light==light);
                inst.runOnMainSync(()->{SettingsActivity restored=(SettingsActivity)foreground.get();assertEquals("Settings index scroll",scroll[0],restored.scroll.getScrollY());restored.finish();});
                await(()->activity!=old&&activity.hasWindowFocus()&&activity.uiTheme().light==light&&field(activity,"sshService")!=null);
                inst.runOnMainSync(()->{assertSame(owner[0],field(activity,"sshService"));assertSame(tab[0].local,field(activity,"session"));assertEquals(tab[0].id,((SshSessionService.Tab)field(activity,"shownTab")).id);assertEquals("保留草稿 · 中文 👩🏽‍💻",((EditText)field(activity,"draft")).getText().toString());});
                capture(light?"light-theme-restored-draft":"dark-theme-restored-draft");
            }
        }finally{
            inst.runOnMainSync(()->{
                Activity page=foreground.get();if(page!=null&&!(page instanceof MainActivity))page.finish();
                if(owner[0]!=null&&tab[0]!=null){owner[0].close(tab[0].id);if(previous[0]!=null)owner[0].select(previous[0]);@SuppressWarnings("unchecked") List<SshSessionService.Tab> tabs=(List<SshSessionService.Tab>)field(owner[0],"tabs");assertEquals(before,tabs.stream().map(t->t.id).collect(java.util.stream.Collectors.toList()));}
                if(activity!=null&&!activity.isDestroyed())call(activity,"minimize");SharedPreferences.Editor e=prefs.edit();if(original==null)e.remove("theme");else e.putString("theme",original);e.commit();
            });if(retained[0])context.unbindService(keepAlive);app.unregisterActivityLifecycleCallbacks(watcher);
        }
    }
}
