package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.ghostty.android.TerminalSession;
import org.junit.Test;
import java.io.*;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in real fixture check: recreate() must not recreate the service-owned VT or stop SSH. */
public class SshSessionServiceTest {
    @Test public void recreationKeepsServiceAndTerminalUntilExplicitDisconnect() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        File profile=new File(context.getFilesDir(),"ssh/connection.properties");
        assumeTrue(profile.isFile());
        Properties config=new Properties();
        try(InputStream in=new FileInputStream(profile)){config.load(in);}
        assumeTrue("tmux".equals(config.getProperty("sessionMode")));
        Application app=(Application)context.getApplicationContext();
        BlockingQueue<Activity> resumed=new LinkedBlockingQueue<>();
        Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){}
            public void onActivityStarted(Activity a){}
            public void onActivityResumed(Activity a){if(a instanceof MainActivity)resumed.add(a);}
            public void onActivityPaused(Activity a){}
            public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a,Bundle b){}
            public void onActivityDestroyed(Activity a){}
        };
        AtomicReference<SshSessionService> owner=new AtomicReference<>();
        CountDownLatch bound=new CountDownLatch(1);
        ServiceConnection connection=new ServiceConnection(){
            public void onServiceConnected(ComponentName n,IBinder b){owner.set(((SshSessionService.LocalBinder)b).service());bound.countDown();}
            public void onServiceDisconnected(ComponentName n){}
        };
        Activity first=null,second=null;
        boolean binding=false;
        app.registerActivityLifecycleCallbacks(lifecycle);
        try{
            // A live terminal may keep drawing on high-refresh OEM devices. Wait for its lifecycle,
            // not startActivitySync's global UI-idle condition, which can time out despite a visible Activity.
            instrumentation.runOnMainSync(()->context.startActivity(new Intent(context,MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("connectLocalSsh",true)));
            first=resumed.poll(10,TimeUnit.SECONDS);assertNotNull("Activity did not resume",first);
            binding=app.bindService(new Intent(context,SshSessionService.class),connection,Context.BIND_AUTO_CREATE);
            assertTrue(binding);assertTrue(bound.await(5,TimeUnit.SECONDS));
            AtomicReference<TerminalSession> terminal=new AtomicReference<>();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(terminal.get()==null&&System.nanoTime()<deadline){
                instrumentation.runOnMainSync(()->{if(owner.get().connected())terminal.set(owner.get().terminal());});
                Thread.sleep(25);
            }
            assertNotNull("SSH did not connect",terminal.get());
            assertTrue("Successful tmux attach must permit reopen recovery",new SshResumeState(context).pending());
            Activity original=first;
            instrumentation.runOnMainSync(original::recreate);
            second=resumed.poll(5,TimeUnit.SECONDS);
            assertNotNull(second);assertNotSame(first,second);
            instrumentation.runOnMainSync(()->{
                assertTrue(original.isDestroyed());
                assertTrue(owner.get().active());assertTrue(owner.get().connected());
                assertSame(terminal.get(),owner.get().terminal());
                owner.get().disconnect();
                assertFalse(owner.get().active());assertFalse(owner.get().connected());
                assertFalse("Explicit stop must cancel process-death recovery",new SshResumeState(context).pending());
            });
        }finally{
            Activity last=second!=null?second:first;
            instrumentation.runOnMainSync(()->{if(owner.get()!=null)owner.get().disconnect();if(last!=null)last.finish();});
            if(binding)app.unbindService(connection);
            app.unregisterActivityLifecycleCallbacks(lifecycle);
        }
    }
}
