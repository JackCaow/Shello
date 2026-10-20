package dev.ghostty.android.sample;

import android.os.Handler;
import android.os.Looper;
import com.jcraft.jsch.ChannelShell;
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
        void onState(String state, boolean connected);
        default void onClosed(String state, boolean retryable) { onState(state, false); }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final File directory;
    private final AtomicBoolean closed = new AtomicBoolean();
    // Bound both directions: a fast PTY must not enqueue unlimited Android UI work.
    private final Semaphore outputSlots = new Semaphore(4);
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(128));
    private volatile Session connection;
    private volatile ChannelShell channel;
    private volatile OutputStream input;
    private volatile int columns, rows;
    private Thread reader;

    SshTransport(File directory, int columns, int rows, Listener listener) {
        this.directory = directory; this.columns = columns; this.rows = rows; this.listener = listener;
    }
    void connect() {
        state("连接中", false);
        reader = new Thread(this::read, "sample-ssh-reader"); reader.start();
    }
    private void read() {
        String end = "已断开";
        boolean retryable = false, profileLoaded = false;
        try {
            Properties config = new Properties();
            try (InputStream source = new FileInputStream(new File(directory, "connection.properties"))) {
                config.load(source);
            }
            JSch jsch = new JSch();
            jsch.setKnownHosts(new File(directory, "known_hosts").getPath());
            jsch.addIdentity(new File(directory, "client_key").getPath());
            profileLoaded = true;
            Session ssh = jsch.getSession(config.getProperty("user"), config.getProperty("host"),
                    Integer.parseInt(config.getProperty("port", "22")));
            connection = ssh;
            ssh.setConfig("StrictHostKeyChecking", "yes");
            ssh.setConfig("PreferredAuthentications", "publickey");
            ssh.setServerAliveInterval(10000); ssh.setServerAliveCountMax(3);
            if (closed.get()) return;
            ssh.connect(10000);
            if (closed.get()) return;
            ChannelShell shell = (ChannelShell) ssh.openChannel("shell"); channel = shell;
            shell.setPtyType("xterm-256color", columns, rows, 0, 0);
            InputStream output = shell.getInputStream(); input = shell.getOutputStream();
            shell.connect(10000);
            if (closed.get()) return;
            resize(columns, rows);
            state("tmux".equals(config.getProperty("sessionMode")) ? "已连接 · tmux" : "已连接", true);
            byte[] buffer = new byte[16384];
            for (int count; !closed.get() && (count = output.read(buffer)) != -1;) {
                outputSlots.acquire();
                byte[] bytes = Arrays.copyOf(buffer, count);
                main.post(() -> {
                    try { if (!closed.get()) listener.onOutput(bytes); }
                    finally { outputSlots.release(); }
                });
            }
            // A normal remote exit/detach must not restart a shell or fight another client.
            retryable = shell.getExitStatus() < 0 && !ssh.isConnected();
        } catch (Exception error) {
            retryable = profileLoaded && isNetworkFailure(error);
            // No credentials, hostnames, or raw server errors in the terminal/logcat.
            end = "连接失败：" + error.getClass().getSimpleName();
        } finally {
            if (channel != null) channel.disconnect();
            if (connection != null) connection.disconnect();
            String status = end;
            boolean canRetry = retryable;
            // FIFO with already-posted output, so EOF does not discard the final screen.
            main.post(() -> { if (!closed.get()) { close(); listener.onClosed(status, canRetry); } });
        }
    }
    void send(byte[] bytes) {
        if (closed.get() || input == null || channel == null || !channel.isConnected()) return;
        byte[] owned = bytes.clone();
        enqueue(() -> { try { input.write(owned); input.flush(); }
            catch (IOException error) { fail("发送失败，连接已断开", true); } });
    }
    void resize(int columns, int rows) {
        this.columns = columns; this.rows = rows;
        if (closed.get() || channel == null || !channel.isConnected()) return;
        enqueue(() -> channel.setPtySize(this.columns, this.rows, 0, 0));
    }
    private void enqueue(Runnable operation) {
        try { writer.execute(operation); }
        catch (RejectedExecutionException error) { if (!closed.get()) fail("发送队列已满，请重新连接", false); }
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
    private void fail(String message, boolean retryable) {
        main.post(() -> { if (!closed.get()) { close(); listener.onClosed(message, retryable); } });
    }
    private void state(String message, boolean connected) {
        main.post(() -> { if (!closed.get()) listener.onState(message, connected); });
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
