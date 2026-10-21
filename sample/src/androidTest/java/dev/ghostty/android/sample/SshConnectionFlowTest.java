package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in form -> host trust -> auth retry -> saved connection -> foreground terminal flow. */
public class SshConnectionFlowTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    Activity current;
    @Test public void createPasswordConnectionRetryAndReopen()throws Exception {
        Context context=inst.getTargetContext();File fixture=new File(context.getCacheDir(),"ssh-flow/test.properties");assumeTrue(fixture.isFile());
        Properties p=new Properties();try(InputStream in=new FileInputStream(fixture)){p.load(in);}
        SshProfileStore store=new SshProfileStore(context);String original=store.activeId();Set<String> before=new HashSet<>();for(SshProfileStore.Entry e:store.list())before.add(e.id);
        BlockingQueue<Activity> resumed=new LinkedBlockingQueue<>();Application app=(Application)context.getApplicationContext();
        Application.ActivityLifecycleCallbacks callback=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}
            public void onActivityResumed(Activity a){resumed.add(a);}public void onActivityPaused(Activity a){}
            public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
        };
        app.registerActivityLifecycleCallbacks(callback);
        try{
            try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -n dev.ghostty.android.sample/.MainActivity -f 0x10008000");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] b=new byte[512];while(in.read(b)!=-1){}}
            current=resumed.poll(10,TimeUnit.SECONDS);assertTrue(current instanceof MainActivity);
            click("最小化会话");click("新建 SSH 连接");current=resumed.poll(10,TimeUnit.SECONDS);assertTrue(current instanceof SshDiscoveryActivity);
            set("连接名称","SSH Flow Password");set("SSH 主机地址",p.getProperty("host"));set("SSH 端口","0");set("SSH 用户名",p.getProperty("user"));set("SSH 登录密码","incorrect");
            click("保存并连接 SSH");await(()->text(root(),"端口必须"),1000);
            set("SSH 端口",p.getProperty("passwordPort"));click("保存并连接 SSH");
            await(()->value(current,"trustDialog")!=null,15000);
            inst.runOnMainSync(()->((AlertDialog)value(current,"trustDialog")).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            assertEquals(before.size(),store.list().size());click("保存并连接 SSH");
            await(()->{Dialog d=(Dialog)value(current,"trustDialog");return d!=null&&d.isShowing();},15000);
            inst.runOnMainSync(()->((AlertDialog)value(current,"trustDialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            await(()->text(root(),"认证失败"),15000);assertEquals(before.size(),store.list().size());
            set("SSH 登录密码",p.getProperty("password"));click("保存并连接 SSH");
            current=resumed.poll(20,TimeUnit.SECONDS);assertTrue("Form did not return to terminal",current instanceof MainActivity);
            await(()->service()!=null&&service().connected(),15000);
            await(()->{dev.ghostty.android.TerminalSession t=service().terminal();return t!=null&&t.select(0,t.getColumns()*t.getRows()-1).contains("GHOSTTY_PASSWORD_READY");},10000);
            assertEquals(before.size()+1,store.list().size());assertNotNull(store.activeId());
            assertEquals("SSH Flow Password",store.read(store.activeDirectory()).name);
            Activity previous=current;inst.runOnMainSync(previous::recreate);current=resumed.poll(10,TimeUnit.SECONDS);assertNotNull(current);
            await(()->service()!=null&&service().connected(),10000);click("最小化会话");
            click("连接 SSH SSH Flow Password");await(()->service()!=null&&service().connected(),15000);
            String editedId=store.activeId();SshSessionService running=service();
            click("最小化会话");longClick("连接 SSH SSH Flow Password");click("编辑连接 SSH Flow Password");
            current=resumed.poll(10,TimeUnit.SECONDS);assertTrue(current instanceof SshDiscoveryActivity);
            assertEquals("",((EditText)find(root(),"SSH 登录密码")).getText().toString());
            set("连接名称","SSH Flow Edited");set("SSH 登录密码","wrong-edited-password");click("保存并连接 SSH");
            await(()->text(root(),"认证失败"),15000);
            assertEquals("SSH Flow Password",store.read(store.directory(editedId)).name);assertTrue(running.connected());
            assertNull("Same endpoint must retain its pin",value(current,"trustDialog"));
            // A changed endpoint requires trust again; cancelling must not overwrite the profile.
            set("SSH 主机地址",p.getProperty("alternateHost", "10.0.2.2"));set("SSH 端口",p.getProperty("port"));
            set("SSH 登录密码","");click("保存并连接 SSH");await(()->value(current,"trustDialog")!=null,15000);
            inst.runOnMainSync(()->((AlertDialog)value(current,"trustDialog")).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            assertEquals("SSH Flow Password",store.read(store.directory(editedId)).name);
            set("SSH 主机地址",p.getProperty("host"));set("SSH 端口",p.getProperty("passwordPort"));
            // Rotation preserves editable metadata; blank credentials keep the encrypted saved password.
            Activity editing=current;inst.runOnMainSync(editing::recreate);current=resumed.poll(10,TimeUnit.SECONDS);assertNotNull(current);
            assertEquals("SSH Flow Edited",((EditText)find(root(),"连接名称")).getText().toString());
            click("保存并连接 SSH");current=resumed.poll(20,TimeUnit.SECONDS);assertTrue(current instanceof MainActivity);
            await(()->service()!=null&&service().connected()&&"SSH Flow Edited".equals(service().profileName()),15000);
            assertEquals(editedId,store.activeId());assertEquals(before.size()+1,store.list().size());
            dev.ghostty.android.TerminalSession firstTerminal=service().terminal();
            // Creating another profile while the service owns a terminal must switch profiles.
            click("最小化会话");click("新建 SSH 连接");current=resumed.poll(10,TimeUnit.SECONDS);assertTrue(current instanceof SshDiscoveryActivity);
            set("连接名称","SSH Flow Second");set("SSH 主机地址",p.getProperty("host"));set("SSH 端口",p.getProperty("passwordPort"));
            set("SSH 用户名",p.getProperty("user"));set("SSH 登录密码",p.getProperty("password"));click("保存并连接 SSH");
            await(()->value(current,"trustDialog")!=null,15000);
            inst.runOnMainSync(()->((AlertDialog)value(current,"trustDialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            current=resumed.poll(20,TimeUnit.SECONDS);assertTrue(current instanceof MainActivity);
            await(()->service()!=null&&service().connected()&&"SSH Flow Second".equals(service().profileName()),15000);
            assertNotSame(firstTerminal,service().terminal());
            click("最小化会话");click("连接 SSH SSH Flow Edited");
            await(()->service()!=null&&service().connected()&&"SSH Flow Edited".equals(service().profileName()),15000);
            String secondId=store.list().stream().filter(e->e.name.equals("SSH Flow Second")).findFirst().get().id;
            inst.runOnMainSync(()->service().close(secondId));store.delete(secondId);
            click("最小化会话");longClick("连接 SSH SSH Flow Edited");click("删除连接 SSH Flow Edited");
            await(()->{Dialog dialog=(Dialog)value(current,"connectionDeleteDialog");return dialog!=null&&dialog.isShowing();},5000);
            inst.runOnMainSync(()->((AlertDialog)value(current,"connectionDeleteDialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            await(()->store.list().size()==before.size(),5000);
            await(()->service().find(editedId)==null,5000);
        }finally{
            inst.runOnMainSync(()->{if(current instanceof MainActivity&&service()!=null){for(SshSessionService.Tab tab:service().tabs())if(!before.contains(tab.id)&&!tab.id.equals("fixture")&&!tab.id.equals("local"))service().close(tab.id);}if(current!=null)current.finish();});
            app.unregisterActivityLifecycleCallbacks(callback);
            for(SshProfileStore.Entry e:store.list())if(!before.contains(e.id))store.delete(e.id);
            if(original!=null)store.activate(original);else context.getSharedPreferences("ssh_profiles",0).edit().remove("active").commit();
        }
    }
    SshSessionService service(){return (SshSessionService)value(current,"sshService");}
    Object value(Object object,String field){try{Field f=object.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(object);}catch(Exception e){throw new AssertionError(e);}}
    View root(){
        if(current instanceof MainActivity){PopupWindow menu=(PopupWindow)value(current,"connectionMenu");if(menu!=null&&menu.isShowing())return menu.getContentView();}
        return current.getWindow().getDecorView();
    }
    void longClick(String desc){inst.runOnMainSync(()->{View v=find(root(),desc);assertNotNull(desc,v);v.performLongClick();});}
    View find(View v,String desc){
        if(desc.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),desc);if(found!=null)return found;}return null;
    }
    void click(String desc){inst.runOnMainSync(()->{View v=find(root(),desc);assertNotNull(desc,v);v.performClick();});}
    void set(String desc,String value){inst.runOnMainSync(()->((EditText)find(root(),desc)).setText(value));}
    boolean text(View v,String needle){if(v instanceof TextView&&((TextView)v).getText().toString().contains(needle))return true;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(text(((ViewGroup)v).getChildAt(i),needle))return true;return false;}
    void await(BooleanSupplier check,int millis)throws Exception {long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);AtomicBoolean yes=new AtomicBoolean();
        do{inst.runOnMainSync(()->yes.set(check.getAsBoolean()));if(yes.get())return;Thread.sleep(50);}while(System.nanoTime()<end);fail("UI condition timed out");}
}
