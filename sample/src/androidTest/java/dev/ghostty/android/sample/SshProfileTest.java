package dev.ghostty.android.sample;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class SshProfileTest {
    final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Properties fixture()throws Exception {
        File file=new File(context.getCacheDir(),"ssh-flow/test.properties");assumeTrue("Opt-in local SSH fixture",file.isFile());
        Properties p=new Properties();try(InputStream in=new FileInputStream(file)){p.load(in);}return p;
    }
    private SshProfileStore.Draft draft(boolean password)throws Exception {
        Properties p=fixture();SshProfileStore.Draft d=new SshProfileStore.Draft();d.name="Connection flow test";d.host=p.getProperty("host");
        d.user=p.getProperty("user");d.port=Integer.parseInt(p.getProperty(password?"passwordPort":"port"));d.auth=password?"password":"key";
        if(password)d.password=p.getProperty("password");else d.privateKey=Files.readAllBytes(new File(context.getCacheDir(),"ssh-flow/client_key").toPath());return d;
    }
    private static final class Result implements SshConnectionSetup.Listener {
        final CountDownLatch done=new CountDownLatch(1);byte[] key;String error,fingerprint;boolean verified;
        public void trustedHostNeeded(String value,byte[] bytes){fingerprint=value;key=bytes;done.countDown();}
        public void verified(){verified=true;done.countDown();}public void failed(String value){error=value;done.countDown();}
    }
    private Result verify(SshProfileStore.Draft d)throws Exception {
        Result result=new Result();SshConnectionSetup setup=new SshConnectionSetup();
        try{setup.start(d,result);assertTrue("Setup timed out",result.done.await(20,TimeUnit.SECONDS));return result;}finally{setup.close();}
    }
    @Test public void validatesAddressPortUserAndAuthentication(){
        SshProfileStore.Draft d=new SshProfileStore.Draft();d.host="host.local";d.user="user";d.password="test";assertNull(d.validate());
        d.port=0;assertNotNull(d.validate());d.port=65536;assertNotNull(d.validate());d.port=22;
        d.host="ssh://host/path";assertNotNull(d.validate());d.host="host.local";d.user="";assertNotNull(d.validate());
        d.user="user";d.auth="key";assertNotNull(d.validate());
    }
    @Test public void credentialsEncryptedAndTamperingRejected()throws Exception {
        SshProfileStore store=new SshProfileStore(context);SshProfileStore.Draft d=new SshProfileStore.Draft();
        d.name="Encrypted profile test";d.host="example.invalid";d.user="test";d.password="unpersisted-secret-marker";
        d.tmux=true;d.knownHosts="test pin".getBytes(StandardCharsets.UTF_8);String id=store.save(d);
        try{
            File dir=store.directory(id);SshProfileStore.Draft restored=store.read(dir);assertEquals(d.password,restored.password);assertTrue(restored.tmux);
            assertArrayEquals(d.knownHosts,restored.knownHosts);assertTrue(store.list().stream().anyMatch(e->e.id.equals(id)));
            for(File f:dir.listFiles())assertFalse(new String(Files.readAllBytes(f.toPath()),StandardCharsets.ISO_8859_1).contains(d.password));
            SshResumeState resume=new SshResumeState(dir,context.getSharedPreferences("profile-encryption-test",0));
            resume.remember(resume.identity());assertTrue(resume.pending());
            File secret=new File(dir,"credentials.enc");byte[] bytes=Files.readAllBytes(secret.toPath());bytes[bytes.length-1]^=1;Files.write(secret.toPath(),bytes);
            assertFalse(resume.pending());
            try{store.read(dir);fail("Tampered encrypted credentials must fail");}catch(javax.crypto.AEADBadTagException expected){}
        }finally{store.delete(id);}
    }
    @Test public void firstContactRequiresTrustAndBothAuthenticationMethodsWork()throws Exception {
        for(boolean password:new boolean[]{true,false}){
            SshProfileStore.Draft d=draft(password);Result first=verify(d);assertFalse(first.verified);assertNotNull(first.error,first.key);assertTrue(first.fingerprint.contains("SHA256:"));
            d.knownHosts=first.key;Result accepted=verify(d);assertTrue(accepted.error,accepted.verified);assertNull(accepted.key);
            SshProfileStore store=new SshProfileStore(context);String id=store.save(d);
            try{Result saved=verify(store.read(store.directory(id)));assertTrue(saved.error,saved.verified);}finally{store.delete(id);}
            if(!password){
                d.privateKey=Files.readAllBytes(new File(context.getCacheDir(),"ssh-flow/client_key_encrypted").toPath());
                d.passphrase=fixture().getProperty("passphrase");Result unlocked=verify(d);assertTrue(unlocked.error,unlocked.verified);
                String encryptedId=store.save(d);try{Result roundTrip=verify(store.read(store.directory(encryptedId)));assertTrue(roundTrip.error,roundTrip.verified);}finally{store.delete(encryptedId);}
                d.passphrase="incorrect-passphrase";Result locked=verify(d);assertFalse(locked.verified);assertNull(locked.key);
            }
            if(password){d.password="deliberately-incorrect";Result rejected=verify(d);assertFalse(rejected.verified);assertNull(rejected.key);assertTrue(rejected.error.contains("认证失败"));}
            d=draft(password);d.knownHosts=first.key.clone();
            // A valid but different host key must fail closed, with no new trust prompt.
            String line=new String(d.knownHosts,StandardCharsets.UTF_8);String[] fields=line.trim().split(" ");
            byte[] key=android.util.Base64.decode(fields[2],0);key[key.length-1]^=1;
            d.knownHosts=(fields[0]+" "+fields[1]+" "+android.util.Base64.encodeToString(key,android.util.Base64.NO_WRAP)+"\n").getBytes(StandardCharsets.UTF_8);
            Result changed=verify(d);assertFalse(changed.verified);assertNull(changed.key);assertTrue(changed.error,changed.error.contains("指纹"));
        }
    }
    private static final class Output implements SshTransport.Listener {
        final CountDownLatch connected=new CountDownLatch(1);final StringBuilder text=new StringBuilder();String state;
        public synchronized void onOutput(byte[] bytes){text.append(new String(bytes,StandardCharsets.UTF_8));}
        public void onState(String state,boolean ready){this.state=state;if(ready)connected.countDown();}
        synchronized boolean contains(String s){return text.indexOf(s)>=0;}
        void expect(String marker)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!contains(marker)&&System.nanoTime()<end)Thread.sleep(25);assertTrue(marker,contains(marker));}
    }
    @Test public void savedTmuxProfileUsesExecPtyAndReattachesSameShell()throws Exception {
        SshProfileStore.Draft d=draft(false);d.tmux=true;Result first=verify(d);d.knownHosts=first.key;Result verified=verify(d);assertTrue(verified.error,verified.verified);
        SshProfileStore store=new SshProfileStore(context);String id=store.save(d);SshTransport one=null,two=null;
        try{
            Output initial=new Output();one=new SshTransport(context,store.directory(id),80,24,initial);one.connect();assertTrue(initial.state,initial.connected.await(15,TimeUnit.SECONDS));
            one.send("stty -echo; GHOSTTY_FLOW_TEST=retained; printf '\\nFLOW_SAVED\\n'\r".getBytes(StandardCharsets.UTF_8));initial.expect("\r\nFLOW_SAVED\r\n");
            one.close();one=null;Thread.sleep(200);
            Output restored=new Output();two=new SshTransport(context,store.directory(id),80,24,restored);two.connect();assertTrue(restored.state,restored.connected.await(15,TimeUnit.SECONDS));
            two.send("printf '\\nFLOW_RESTORED=%s\\n' \"$GHOSTTY_FLOW_TEST\"\r".getBytes(StandardCharsets.UTF_8));restored.expect("FLOW_RESTORED=retained");
            two.send("exit\r".getBytes(StandardCharsets.UTF_8));
        }finally{
            if(one!=null)one.close();if(two!=null)two.close();store.delete(id);
            com.jcraft.jsch.JSch jsch=new com.jcraft.jsch.JSch();jsch.setKnownHosts(new ByteArrayInputStream(d.knownHosts));
            com.jcraft.jsch.Session session=SshConnectionSetup.session(jsch,d);
            try{session.connect(10000);com.jcraft.jsch.ChannelExec end=(com.jcraft.jsch.ChannelExec)session.openChannel("exec");
                end.setCommand(SshConnectionSetup.TMUX_LOOKUP+"\"$T\" kill-session -t "+d.tmuxSession);end.connect(5000);
                long until=System.currentTimeMillis()+2000;while(!end.isClosed()&&System.currentTimeMillis()<until)Thread.sleep(20);end.disconnect();
            }finally{session.disconnect();}
        }
    }
}
