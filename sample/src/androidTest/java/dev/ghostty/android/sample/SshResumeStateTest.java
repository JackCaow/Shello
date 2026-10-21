package dev.ghostty.android.sample;

import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class SshResumeStateTest {
    @Test public void onlyMatchingTmuxProfileResumesAndExplicitStopClearsIt() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory=Files.createTempDirectory(context.getCacheDir().toPath(),"resume-test").toFile();
        SharedPreferences prefs=context.getSharedPreferences("resume-test",Context.MODE_PRIVATE);
        SshResumeState state=new SshResumeState(directory,prefs);
        try {
            state.clear();
            write(directory,"connection.properties","sessionMode=tmux\nhost=127.0.0.1\n");
            write(directory,"known_hosts","test-host-key");write(directory,"client_key","test-client-key");
            assertFalse(state.pending());
            state.remember(state.identity());
            assertTrue(new SshResumeState(directory,prefs).pending());
            state.clear();assertFalse(state.pending());
            for(String changed:new String[]{"connection.properties","known_hosts","client_key"}) {
                state.remember(state.identity());assertTrue(state.pending());
                String previous=new String(Files.readAllBytes(new File(directory,changed).toPath()),StandardCharsets.UTF_8);
                write(directory,changed,previous+"\n# changed\n");
                assertFalse(state.pending());
                write(directory,changed,previous);
                assertFalse("Invalidated intent must not reappear",state.pending());
            }
            state.remember(state.identity());
            write(directory,"connection.properties","sessionMode=plain\n");
            assertNull(state.identity());assertFalse(state.pending());
            assertTrue(new File(directory,"known_hosts").delete());
            assertFalse(state.pending());
        } finally {
            state.clear();for(File file:directory.listFiles())file.delete();directory.delete();
        }
    }
    private static void write(File directory,String name,String text) throws IOException {
        Files.write(new File(directory,name).toPath(),text.getBytes(StandardCharsets.UTF_8));
    }
}
