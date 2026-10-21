package dev.ghostty.android.sample;

import android.content.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** A custom broadcast has no foreground-service exemption. No instrumentation or visible Activity. */
public final class BackgroundStartProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        String rejected=SshSessionService.requestConnect(context);
        try(FileOutputStream stream=new FileOutputStream(new File(context.getFilesDir(),"background-start-probe.txt"))){
            stream.write((rejected==null?"ACCEPTED":rejected).getBytes(StandardCharsets.UTF_8));
        }catch(IOException error){throw new IllegalStateException(error);}
    }
}
