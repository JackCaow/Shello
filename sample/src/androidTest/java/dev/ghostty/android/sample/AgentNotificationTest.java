package dev.ghostty.android.sample;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.service.notification.StatusBarNotification;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Two local notification fixtures must retain independent request destinations. */
public class AgentNotificationTest {
    @Test public void notificationsOpenExactRequestWithoutSendingDecision()throws Exception{
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();Context context=inst.getTargetContext();NotificationManager manager=context.getSystemService(NotificationManager.class);
        assumeTrue(manager.areNotificationsEnabled());if(Build.VERSION.SDK_INT>=33)assumeTrue(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED);
        AgentHub hub=AgentHub.get(context);long until=System.currentTimeMillis()+15000;boolean[] busy={true};do{inst.runOnMainSync(()->busy[0]=hub.busy);if(busy[0])Thread.sleep(50);}while(busy[0]&&System.currentTimeMillis()<until);assertFalse(busy[0]);
        List<AgentHub.Event> old=new ArrayList<>(),fixtures=new ArrayList<>();for(int i=0;i<2;i++)fixtures.add(new AgentHub.Event("notification-fixture","Notification test",new JSONObject().put("id",UUID.randomUUID().toString()).put("provider","codex").put("hook_event_name","PermissionRequest").put("status","pending").put("remainingMs",60000).put("created",System.currentTimeMillis()/1000d).put("tool_name","Bash").put("tool_input",new JSONObject().put("command","printf notification_"+i))));
        Application app=(Application)context.getApplicationContext();BlockingQueue<AgentActivity> opened=new LinkedBlockingQueue<>();Application.ActivityLifecycleCallbacks callbacks=new Application.ActivityLifecycleCallbacks(){public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){if(a instanceof AgentActivity)opened.add((AgentActivity)a);}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}};app.registerActivityLifecycleCallbacks(callbacks);AgentActivity page=null;
        try{
            try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -W -n dev.ghostty.android.sample/.MainActivity");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
            inst.runOnMainSync(()->{old.addAll(hub.events);hub.events.clear();hub.events.addAll(fixtures);hub.busy=true;int users=hub.users;hub.users=0;try{Method notify=AgentHub.class.getDeclaredMethod("notifyEvent",AgentHub.Event.class);notify.setAccessible(true);for(AgentHub.Event e:fixtures)notify.invoke(hub,e);}catch(Exception e){throw new AssertionError(e);}finally{hub.users=users;}});
            PendingIntent[] intents=new PendingIntent[2];for(StatusBarNotification n:manager.getActiveNotifications())for(int i=0;i<2;i++)if(n.getId()==fixtures.get(i).id().hashCode())intents[i]=n.getNotification().contentIntent;
            assertNotNull(intents[0]);assertNotNull(intents[1]);assertNotEquals(intents[0],intents[1]);intents[1].send();page=opened.poll(10,TimeUnit.SECONDS);assertNotNull(page);final AgentActivity actual=page;inst.waitForIdleSync();
            inst.runOnMainSync(()->{assertNotNull(new AgentInboxTest().find(actual.body,"printf notification_1"));assertTrue(fixtures.get(1).pending());assertFalse(hub.read(fixtures.get(0)));assertTrue(hub.read(fixtures.get(1)));});
        }finally{
            final AgentActivity actual=page;inst.runOnMainSync(()->{if(actual!=null)actual.finish();hub.events.clear();hub.events.addAll(old);hub.busy=false;for(AgentHub.Event e:fixtures){hub.preferences.edit().remove("read_"+e.id()).commit();manager.cancel(e.id().hashCode());}});app.unregisterActivityLifecycleCallbacks(callbacks);
        }
    }
}
