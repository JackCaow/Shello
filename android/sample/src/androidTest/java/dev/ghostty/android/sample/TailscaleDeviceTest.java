package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in real-device acceptance. Existing profiles are never edited or deleted. */
public class TailscaleDeviceTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Context context=inst.getTargetContext();
    String expected(){String host=InstrumentationRegistry.getArguments().getString("tailscaleHost");assumeTrue(host!=null);assertTrue(NetworkAddress.tailscale(host));return host;}
    @Test public void discoverActualPeerAndCancelWithoutLateUpdates()throws Exception{
        String host=expected();ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        assertFalse("Tailscale VPN must be connected",TailscaleSshDiscovery.vpnIdentity(cm).isEmpty());
        List<LanSshDiscovery.Host> routes=TailscaleSshDiscovery.routedPeers(cm);
        assertTrue("This device should expose the host route",routes.stream().anyMatch(h->h.address.equals(host)));
        AtomicReference<TailscaleSshDiscovery> scan=new AtomicReference<>();AtomicReference<List<LanSshDiscovery.Host>> result=new AtomicReference<>();
        AtomicInteger callbacks=new AtomicInteger();CountDownLatch done=new CountDownLatch(1);
        inst.runOnMainSync(()->{scan.set(new TailscaleSshDiscovery(context,(hosts,running,message)->{callbacks.incrementAndGet();if(!running){result.set(hosts);done.countDown();}}));scan.get().start();});
        try{assertTrue(done.await(30,TimeUnit.SECONDS));assertTrue("Phone must reach real SSH over Tailscale",result.get().stream().anyMatch(h->h.address.equals(host)));}
        finally{inst.runOnMainSync(()->scan.get().close());}
        int count=callbacks.get();Thread.sleep(700);assertEquals(count,callbacks.get());
        inst.runOnMainSync(()->{scan.set(new TailscaleSshDiscovery(context,(hosts,running,message)->callbacks.incrementAndGet()));scan.get().start();scan.get().close();});
        count=callbacks.get();Thread.sleep(700);assertEquals(count,callbacks.get());
    }
    @Test public void savedTailnetConnectionReadsPeersOverPinnedSsh()throws Exception{
        String host=expected();SshProfileStore store=new SshProfileStore(context);SshProfileStore.Entry match=null;
        for(SshProfileStore.Entry e:store.list())if(host.equals(e.host))match=e;
        assumeTrue("Run after QR pairing",match!=null);
        try(SshCommand cmd=new SshCommand()){
            List<LanSshDiscovery.Host> peers=TailscaleSshDiscovery.parse(cmd.run(context,match.id,TailscaleSshDiscovery.COMMAND,null));
            assertTrue("Trusted source must supply its own tailnet address",peers.stream().anyMatch(h->host.equals(h.address)));
        }
    }
    @Test public void pairFromQrImageAndVerifyRealSshWithoutReplacingProfiles()throws Exception{
        String host=expected();assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("tailscalePair")));
        File qr=new File(context.getCacheDir(),"tailscale-pair.png");assertTrue("Copy the private, short-lived QR image to app cache",qr.isFile());
        SshProfileStore store=new SshProfileStore(context);Set<String> before=new HashSet<>();for(SshProfileStore.Entry e:store.list())before.add(e.id);
        try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -n dev.ghostty.android.sample/.MainActivity");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
        SystemClock.sleep(500);inst.waitForIdleSync();
        PairingActivity activity=(PairingActivity)inst.startActivitySync(new Intent(context,PairingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            inst.runOnMainSync(()->activity.onActivityResult(95,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(qr))));
            long until=SystemClock.uptimeMillis()+12000;boolean clicked=false;
            while(SystemClock.uptimeMillis()<until&&!clicked){AccessibilityNodeInfo root=inst.getUiAutomation().getRootInActiveWindow();
                if(root!=null){for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(AppLanguage.tr(R.string.pair))){
                    if(AppLanguage.tr(R.string.pair).contentEquals(n.getText()==null?"":n.getText())&&n.isClickable())clicked|=n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }}if(!clicked)Thread.sleep(100);
            }
            assertTrue("QR image must reach the explicit confirmation",clicked);
            SshProfileStore.Entry added=null;until=SystemClock.uptimeMillis()+35000;
            while(SystemClock.uptimeMillis()<until&&added==null){for(SshProfileStore.Entry e:store.list())if(!before.contains(e.id)&&host.equals(e.host))added=e;if(added==null)Thread.sleep(200);}
            assertNotNull("Pairing must verify SSH/tmux and save a new profile",added);
            for(String id:before)assertTrue("Original profile lost",SshProfileStore.exists(store.directory(id)));
            try(SshCommand cmd=new SshCommand()){assertEquals("SHELLO_TAILSCALE_OK",cmd.run(context,added.id,"printf SHELLO_TAILSCALE_OK",null));}
            try(SshCommand cmd=new SshCommand()){assertTrue(cmd.run(context,added.id,SshConnectionSetup.TMUX_LOOKUP+"\"$T\" -V",null).startsWith("tmux "));}
        }finally{qr.delete();inst.runOnMainSync(()->{if(!activity.isDestroyed())activity.finish();});}
    }
}
