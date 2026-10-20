package dev.ghostty.android.sample;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in integration against scripts/local-ssh.py; never contacts a public host. */
public class SshTransportTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private File profile() { return new File(context.getFilesDir(), "ssh"); }
    private static final class Probe implements SshTransport.Listener {
        final CountDownLatch connected = new CountDownLatch(1), ended = new CountDownLatch(1);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        volatile String state;
        @Override public synchronized void onOutput(byte[] bytes) { output.write(bytes, 0, bytes.length); }
        @Override public void onState(String value, boolean ready) {
            state = value;
            if (ready) connected.countDown();
            else if (!value.equals("连接中")) ended.countDown();
        }
        synchronized String text() { return new String(output.toByteArray(), StandardCharsets.UTF_8); }
        void expect(String marker) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!text().contains(marker) && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue("Missing expected PTY marker: " + marker, text().contains(marker));
        }
    }
    @Test public void realPtyResizeUnicodeTuiInterruptAndReconnect() throws Exception {
        assumeTrue("Run scripts/local-ssh.py first", new File(profile(), "connection.properties").isFile());
        java.util.Properties config = new java.util.Properties();
        try (InputStream in = new FileInputStream(new File(profile(), "connection.properties"))) { config.load(in); }
        assumeTrue("Plain-shell test must not exit the tmux fixture", !"tmux".equals(config.getProperty("sessionMode")));
        Probe probe = new Probe();
        SshTransport ssh = new SshTransport(profile(), 80, 24, probe);
        try {
            ssh.connect();assertTrue(probe.state, probe.connected.await(15, TimeUnit.SECONDS));
            send(ssh, "stty -echo; printf '\\nPTY_SIZE='; stty size; printf 'TERM_VALUE=%s\\n' \"$TERM\"\r");
            probe.expect("PTY_SIZE=24 80");probe.expect("TERM_VALUE=xterm-256color");
            ssh.resize(50,12);
            send(ssh,"printf '\\nRESIZED='; stty size; printf '\\n中文👨‍👩‍👧‍👦👍🏽🇨🇳1️⃣\\n'\r");
            probe.expect("RESIZED=12 50");probe.expect("中文👨‍👩‍👧‍👦👍🏽🇨🇳1️⃣");
            send(ssh,"vim -Nu NONE -n unicode.txt\r");probe.expect("\033[?1049h");
            send(ssh,"\033:q!\r");probe.expect("\033[?1049l");
            send(ssh,"less -R unicode.txt\r");probe.expect("001 中文输入");
            send(ssh,"G");probe.expect("240 中文输入");send(ssh,"q");
            send(ssh,"sleep 60\r");Thread.sleep(250);send(ssh,"\003");
            send(ssh,"printf '\\nINTERRUPT_OK\\n'\r");probe.expect("\r\nINTERRUPT_OK\r\n");
            send(ssh,"exit\r");assertTrue(probe.ended.await(10,TimeUnit.SECONDS));
        } finally { ssh.close(); }
        Probe reconnect = new Probe();
        SshTransport second = new SshTransport(profile(),80,24,reconnect);
        try {
            second.connect();assertTrue(reconnect.connected.await(15,TimeUnit.SECONDS));
            send(second,"printf '\\nRECONNECTED_OK\\n'\r");reconnect.expect("\r\nRECONNECTED_OK\r\n");
        } finally { second.close(); }
    }
    @Test public void unknownHostKeyIsRejected() throws Exception {
        assumeTrue(new File(profile(),"connection.properties").isFile());
        File invalid = new File(context.getCacheDir(), "ssh-rejected");invalid.mkdirs();
        for(String name:new String[]{"connection.properties","client_key"})
            Files.copy(new File(profile(),name).toPath(),new File(invalid,name).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.write(new File(invalid,"known_hosts").toPath(),new byte[0]);
        Probe probe = new Probe();SshTransport ssh = new SshTransport(invalid,80,24,probe);
        try {
            ssh.connect();assertTrue(probe.ended.await(15,TimeUnit.SECONDS));
            assertEquals(1L,probe.connected.getCount());assertTrue(probe.state.startsWith("连接失败"));
        } finally {
            ssh.close();for(File file:invalid.listFiles())file.delete();invalid.delete();
        }
    }
    private static void send(SshTransport ssh,String text) { ssh.send(text.getBytes(StandardCharsets.UTF_8)); }
}
