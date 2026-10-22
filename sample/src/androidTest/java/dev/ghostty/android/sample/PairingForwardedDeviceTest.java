package dev.ghostty.android.sample;

import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import com.jcraft.jsch.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Opt-in loopback/adb forwarding fixture. Does not certify Windows networking. */
public class PairingForwardedDeviceTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Session connect(SshProfileStore.Draft d)throws Exception{JSch jsch=new JSch();jsch.setKnownHosts(new ByteArrayInputStream(d.knownHosts));Session s=SshConnectionSetup.session(jsch,d);s.connect(10000);return s;}
    private String exec(Session s,String command)throws Exception{
        ChannelExec c=(ChannelExec)s.openChannel("exec");try{c.setCommand(command);InputStream in=c.getInputStream();c.connect(5000);ByteArrayOutputStream out=new ByteArrayOutputStream();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){while(in.available()>0)out.write(in.read());if(c.isClosed())break;Thread.sleep(20);}assertEquals(0,c.getExitStatus());return out.toString("UTF-8").trim();}finally{c.disconnect();}
    }
    @Test public void scannedTlsKeySshEncryptedSaveAndTmuxReattachment()throws Exception{
        assumeTrue("Opt-in dedicated host fixture", "true".equals(InstrumentationRegistry.getArguments().getString("forwardedPairing")));
        File ticketFile=new File(context.getCacheDir(),"forwarded-pair-ticket");assertTrue(ticketFile.isFile());
        String payload=new String(Files.readAllBytes(ticketFile.toPath()),StandardCharsets.UTF_8);ticketFile.delete();
        PairingClient.Ticket ticket=new PairingClient.Ticket(payload);
        String pin=ticket.pin;String bad=payload.replace(pin,"0".repeat(64));
        try{PairingClient.pair(new PairingClient.Ticket(bad));fail("TLS pin mismatch accepted");}catch(javax.net.ssl.SSLException expected){}
        SshProfileStore.Draft draft=PairingClient.pair(ticket);assertEquals(33442,draft.port);assertEquals("Forwarded WSL contract fixture",draft.name);
        assertTrue(new String(draft.knownHosts,StandardCharsets.UTF_8).startsWith("[127.0.0.1]:33442 "));
        CountDownLatch verified=new CountDownLatch(1);String[] error={null};SshConnectionSetup setup=new SshConnectionSetup();
        setup.start(draft,new SshConnectionSetup.Listener(){public void verified(){verified.countDown();}public void failed(String value){error[0]=value;verified.countDown();}public void trustedHostNeeded(String fingerprint,byte[] known){error[0]="Unexpected untrusted host";verified.countDown();}});
        try{assertTrue(verified.await(20,TimeUnit.SECONDS));assertNull(error[0]);}finally{setup.close();}
        SshProfileStore store=new SshProfileStore(context);String id=store.save(draft);String socket="shello_pair_"+UUID.randomUUID().toString().replace("-","");
        String tmux=SshConnectionSetup.TMUX_LOOKUP+"\"$T\" -L "+socket;
        try{
            SshProfileStore.Draft restored=store.read(store.directory(id));assertArrayEquals(draft.privateKey,restored.privateKey);assertEquals(draft.port,restored.port);
            String encrypted=new String(Files.readAllBytes(new File(store.directory(id),"profile.enc").toPath()),StandardCharsets.ISO_8859_1);assertFalse(encrypted.contains("PRIVATE KEY"));
            String before;Session first=connect(restored);try{exec(first,tmux+" new-session -d -s preserved 'sleep 120'");before=exec(first,tmux+" display-message -p -t preserved '#{pane_pid}'");}finally{first.disconnect();}
            Session second=connect(restored);try{assertEquals(before,exec(second,tmux+" display-message -p -t preserved '#{pane_pid}'"));exec(second,tmux+" kill-server");}finally{second.disconnect();}
            // Removing the advertised host's pin must not trigger implicit acceptance.
            restored.knownHosts=new byte[0];try{Session rejected=connect(restored);rejected.disconnect();fail("Unpinned SSH endpoint accepted");}catch(JSchException expected){}
        }finally{store.delete(id);}
    }
}
