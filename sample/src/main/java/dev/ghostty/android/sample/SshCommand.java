package dev.ghostty.android.sample;

import android.content.Context;
import com.jcraft.jsch.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bounded auxiliary SSH commands. Uses the same pinned, encrypted profile as the PTY. */
final class SshCommand implements AutoCloseable {
    private volatile boolean authenticated;
    boolean authenticated(){return authenticated;}
    private volatile Session session;private volatile ChannelExec channel;private volatile boolean closed;
    String run(Context context,String profile,String command,byte[] input)throws Exception {
        SshProfileStore store=new SshProfileStore(context);
        SshProfileStore.Draft draft=store.read(store.directory(profile));
        JSch jsch=new JSch();jsch.setKnownHosts(new ByteArrayInputStream(draft.knownHosts));
        session=SshConnectionSetup.session(jsch,draft);if(closed)throw new IOException("cancelled");session.connect(10000);authenticated=true;
        channel=(ChannelExec)session.openChannel("exec");channel.setCommand(command);
        channel.setInputStream(new ByteArrayInputStream(input==null?new byte[0]:input));
        InputStream out=channel.getInputStream(),err=channel.getErrStream();channel.connect(5000);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int discarded=0;long until=System.nanoTime()+20_000_000_000L;
        while(!closed&&System.nanoTime()<until){
            while(out.available()>0){int n=out.read();if(n<0)break;if(bytes.size()>=1048576)throw new IOException("response too large");bytes.write(n);}
            while(err.available()>0){if(err.read()<0)break;if(++discarded>1048576)throw new IOException("error response too large");}
            if(channel.isClosed()&&out.available()==0){if(channel.getExitStatus()!=0)throw new IOException("Remote command failed ("+channel.getExitStatus()+")");return bytes.toString("UTF-8");}
            Thread.sleep(15);
        }
        throw new IOException("Remote command timed out");
    }
    static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    @Override public void close(){closed=true;ChannelExec c=channel;Session s=session;if(c!=null)c.disconnect();if(s!=null)s.disconnect();}
}
