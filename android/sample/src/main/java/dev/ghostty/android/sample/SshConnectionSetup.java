package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.os.*;
import android.util.Base64;
import com.jcraft.jsch.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Verifies authentication without opening a shell or sending terminal input. */
final class SshConnectionSetup implements AutoCloseable {
    interface Listener { void trustedHostNeeded(String fingerprint,byte[] knownHosts);void verified();void failed(String message); }
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean closed;private volatile Session session;
    static Session session(JSch jsch,SshProfileStore.Draft d)throws Exception {
        if("key".equals(d.auth))jsch.addIdentity("connection-key",d.privateKey,null,d.passphrase.isEmpty()?null:d.passphrase.getBytes(StandardCharsets.UTF_8));
        Session s=jsch.getSession(d.user,d.host,d.port);s.setConfig("StrictHostKeyChecking","yes");
        s.setConfig("PreferredAuthentications","key".equals(d.auth)?"publickey":"password");
        if("password".equals(d.auth))s.setPassword(d.password);
        s.setServerAliveInterval(10000);s.setServerAliveCountMax(3);return s;
    }
    void start(SshProfileStore.Draft draft,Listener listener){
        new Thread(()->{
            final byte[][] candidate={null};final String[] fingerprint={null};
            try{
                JSch jsch=new JSch();jsch.setKnownHosts(new ByteArrayInputStream(draft.knownHosts));
                HostKeyRepository known=jsch.getHostKeyRepository();
                jsch.setHostKeyRepository(new HostKeyRepository(){
                    public int check(String host,byte[] key){
                        int status=known.check(host,key);
                        // Existing pins never get an automatic replace/accept path.
                        if(status==NOT_INCLUDED&&draft.knownHosts.length==0)try{
                            HostKey hk=new HostKey(host,key);
                            candidate[0]=(host+" "+hk.getType()+" "+Base64.encodeToString(key,Base64.NO_WRAP)+"\n").getBytes(StandardCharsets.UTF_8);
                            fingerprint[0]=hk.getType()+"\nSHA256:"+Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key),Base64.NO_WRAP|Base64.NO_PADDING);
                        }catch(Exception ignored){}
                        return status;
                    }
                    public void add(HostKey key,UserInfo user){}public void remove(String host,String type){}public void remove(String host,String type,byte[] key){}
                    public String getKnownHostsRepositoryID(){return "connection setup";}
                    public HostKey[] getHostKey(){return known.getHostKey();}public HostKey[] getHostKey(String host,String type){return known.getHostKey(host,type);}
                });
                session=session(jsch,draft);if(closed)return;session.connect(10000);
                if(draft.tmux)verifyTmux(session);
                post(listener::verified);
            }catch(Exception error){
                if(candidate[0]!=null)post(()->listener.trustedHostNeeded(fingerprint[0],candidate[0]));
                else post(()->listener.failed(message(error)));
            }finally{if(session!=null)session.disconnect();}
        },"ssh-connection-setup").start();
    }
    // macOS GUI SSH sessions may not inherit Homebrew's interactive PATH.
    static final String TMUX_LOOKUP="if command -v tmux >/dev/null 2>&1; then T=tmux; elif [ -x /opt/homebrew/bin/tmux ]; then T=/opt/homebrew/bin/tmux; elif [ -x /usr/local/bin/tmux ]; then T=/usr/local/bin/tmux; else exit 127; fi; ";
    private void verifyTmux(Session session)throws Exception {
        ChannelExec probe=(ChannelExec)session.openChannel("exec");
        try{
            probe.setCommand(TMUX_LOOKUP+"\"$T\" -V");InputStream in=probe.getInputStream();probe.connect(5000);
            ByteArrayOutputStream out=new ByteArrayOutputStream();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(!closed&&System.nanoTime()<deadline){
                while(in.available()>0&&out.size()<1024)out.write(in.read());
                if(probe.isClosed())break;Thread.sleep(20);
            }
            if(closed)return;
            if(!probe.isClosed()||probe.getExitStatus()!=0||!out.toString("UTF-8").startsWith("tmux "))throw new IOException("tmux unavailable");
        }finally{probe.disconnect();}
    }
    static String message(Exception error){return tr(messageId(error));}
    static int messageId(Exception error){
        if(error instanceof JSchHostKeyException)return R.string.server_fingerprint_mismatch_connection_stopped_verify_the_key_wit;
        String text=error.getMessage()==null?"":error.getMessage().toLowerCase(java.util.Locale.ROOT);
        if(text.equals("tmux unavailable"))return R.string.tmux_is_unavailable_install_it_on_the_server_or_turn_off_keep_rem;
        if(text.contains("auth fail")||text.contains("auth cancel"))return R.string.authentication_failed_check_your_username_password_or_private_key;
        if(text.contains("privatekey")||text.contains("private key"))return R.string.cannot_read_the_private_key_check_its_format_and_passphrase;
        if(text.contains("reject hostkey")||text.contains("hostkey has been changed"))return R.string.server_fingerprint_mismatch_connection_stopped;
        if(SshTransport.isNetworkFailure(error))return R.string.cannot_reach_the_server_check_the_address_port_and_network_then_t;
        return R.string.ssh_connection_failed_check_authentication_and_server_settings;
    }
    private void post(Runnable task){main.post(()->{if(!closed)task.run();});}
    @Override public void close(){closed=true;Session s=session;if(s!=null)new Thread(s::disconnect,"ssh-setup-close").start();}
}
