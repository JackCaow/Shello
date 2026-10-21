package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.os.Handler;
import android.os.Looper;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.io.*;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sample-only SSH shell. All callbacks run on the main thread; VT state stays there. */
final class SshTransport implements AutoCloseable {
    interface Listener {
        void onOutput(byte[] bytes);
        default void onState(String state, boolean connected) {}
        default void onStateResource(int id, boolean connected) { onState(tr(id), connected); }
        default void onClosedResource(int id, boolean retryable) { onClosed(tr(id), retryable); }
        default void onClosed(String state, boolean retryable) { onState(state, false); }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final File directory;
    private String target;
    private final android.content.Context context;
    private final AtomicBoolean closed = new AtomicBoolean();
    // Bound both directions: a fast PTY must not enqueue unlimited Android UI work.
    private final Semaphore outputSlots = new Semaphore(4);
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(128));
    private volatile Session connection;
    private volatile Channel channel;
    private volatile OutputStream input;
    private volatile int columns, rows;
    private Thread reader;

    SshTransport(android.content.Context context,File directory, int columns, int rows, Listener listener) {
        this.context=context.getApplicationContext();
        this.directory = directory; this.columns = columns; this.rows = rows; this.listener = listener;
    }
    SshTransport target(String value){if(value!=null)SessionTarget.validate(value);target=value;return this;}
    void connect() {
        state(R.string.connecting_297, false);
        reader = new Thread(this::read, "sample-ssh-reader"); reader.start();
    }
    private void read() {
        int end = R.string.disconnected;
        boolean retryable = false, profileLoaded = false;
        try {
            JSch jsch = new JSch();
            SshProfileStore.Draft profile=new SshProfileStore(context).read(directory);
            if(target!=null){profile.tmux=!target.startsWith("plain_");profile.tmuxSession=profile.tmux?target:null;}
            Properties config=profile.properties();
            jsch.setKnownHosts(new ByteArrayInputStream(profile.knownHosts));
            profileLoaded = true;
            Session ssh = SshConnectionSetup.session(jsch,profile);
            connection = ssh;
            if (closed.get()) return;
            ssh.connect(10000);
            if (closed.get()) return;
            Channel shell;
            String tmuxSession=profile.tmuxSession;
            if("tmux".equals(config.getProperty("sessionMode"))&&tmuxSession!=null){
                SessionTarget.validate(tmuxSession);
                ChannelExec exec=(ChannelExec)ssh.openChannel("exec");exec.setPty(true);
                exec.setPtyType("xterm-256color",columns,rows,0,0);
                exec.setCommand(SessionTarget.command(tmuxSession));shell=exec;
            }else{
                ChannelShell login=(ChannelShell)ssh.openChannel("shell");login.setPtyType("xterm-256color",columns,rows,0,0);shell=login;
            }
            channel=shell;
            InputStream output = shell.getInputStream(); input = shell.getOutputStream();
            shell.connect(10000);
            if (closed.get()) return;
            resize(columns, rows);
            state("tmux".equals(config.getProperty("sessionMode")) ? R.string.connected_tmux : R.string.connected, true);
            byte[] buffer = new byte[16384];
            for (int count; !closed.get() && (count = output.read(buffer)) != -1;) {
                outputSlots.acquire();
                byte[] bytes = Arrays.copyOf(buffer, count);
                main.post(() -> {
                    try { if (!closed.get()) listener.onOutput(bytes); }
                    finally { outputSlots.release(); }
                });
            }
            // EOF can arrive before exit-status. Give a normal detach/exit time to finish.
            long exitDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(!closed.get()&&!shell.isClosed()&&ssh.isConnected()&&shell.getExitStatus()<0
                    &&shell.getExitSignal()==null&&System.nanoTime()<exitDeadline)Thread.sleep(10);
            // JSch closes channel streams BEFORE clearing Session.isConnected on transport loss.
            // That flag can still be true here: do not mistake the race for a deliberate remote exit.
            retryable = shell.getExitStatus()<0&&shell.getExitSignal()==null;
            if(!retryable&&shell.getExitStatus()!=0&&target!=null&&target.startsWith("="))end=R.string.remote_tmux_session_ended_choose_another_session_or_create_one;
            if(BuildConfig.DEBUG)android.util.Log.i("GhosttySsh","Transport EOF: exit="+shell.getExitStatus()
                    +" sessionConnected="+ssh.isConnected()+" channelClosed="+shell.isClosed()+" retry="+retryable);
        } catch (Exception error) {
            retryable = profileLoaded && isNetworkFailure(error);
            if(BuildConfig.DEBUG)android.util.Log.i("GhosttySsh","Transport failure: "+error.getClass().getSimpleName()
                    +" cause="+(error.getCause()==null?"none":error.getCause().getClass().getSimpleName())+" retry="+retryable);
            // No credentials, hostnames, or raw server errors in the terminal/logcat.
            end = SshConnectionSetup.messageId(error);
        } finally {
            if (channel != null) channel.disconnect();
            if (connection != null) connection.disconnect();
            int status = end;
            boolean canRetry = retryable;
            // FIFO with already-posted output, so EOF does not discard the final screen.
            main.post(() -> { if (!closed.get()) { close(); listener.onClosedResource(status, canRetry); } });
        }
    }
    void send(byte[] bytes) {
        if (closed.get() || input == null || channel == null || !channel.isConnected()) return;
        byte[] owned = bytes.clone();
        enqueue(() -> { try { input.write(owned); input.flush(); }
            catch (IOException error) { fail(R.string.send_failed_connection_lost, true); } });
    }
    void resize(int columns, int rows) {
        this.columns = columns; this.rows = rows;
        if (closed.get() || channel == null || !channel.isConnected()) return;
        enqueue(() -> {
            if(channel instanceof ChannelShell)((ChannelShell)channel).setPtySize(this.columns,this.rows,0,0);
            else if(channel instanceof ChannelExec)((ChannelExec)channel).setPtySize(this.columns,this.rows,0,0);
        });
    }
    private void enqueue(Runnable operation) {
        try { writer.execute(operation); }
        catch (RejectedExecutionException error) { if (!closed.get()) fail(R.string.send_queue_full_reconnect_to_continue, false); }
    }
    // Retry transport I/O only. Key/config/authentication/protocol failures need user action.
    static boolean isNetworkFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.jcraft.jsch.JSchHostKeyException) return false;
            if (cause instanceof java.net.SocketException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.UnknownHostException || cause instanceof EOFException) return true;
        }
        return false;
    }
    private void fail(int message, boolean retryable) {
        main.post(() -> { if (!closed.get()) { close(); listener.onClosedResource(message, retryable); } });
    }
    private void state(int message, boolean connected) {
        main.post(() -> { if (!closed.get()) listener.onStateResource(message, connected); });
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        writer.shutdownNow();
        if (reader != null) reader.interrupt();
        // disconnect may write to the socket; never block Android's UI thread.
        new Thread(() -> {
            if (channel != null) channel.disconnect();
            if (connection != null) connection.disconnect();
        }, "sample-ssh-close").start();
    }
}
