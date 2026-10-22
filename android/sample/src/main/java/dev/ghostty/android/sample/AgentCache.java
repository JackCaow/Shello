package dev.ghostty.android.sample;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Private bounded snapshots; credentials and approval grants are never stored here. */
final class AgentCache {
    private final AtomicFile file;
    AgentCache(Context context){this(context.getNoBackupFilesDir());}
    AgentCache(File directory){file=new AtomicFile(new File(directory,"agent-inbox-v2.json"));}
    JSONObject read(){try(FileInputStream in=file.openRead()){
        byte[] bytes=PairingClient.read(in,2*1024*1024);return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
    }catch(Exception ignored){return new JSONObject();}}
    void write(JSONObject value){FileOutputStream out=null;try{byte[] data=value.toString().getBytes(StandardCharsets.UTF_8);if(data.length>2*1024*1024)return;out=file.startWrite();out.write(data);file.finishWrite(out);}catch(Exception ignored){if(out!=null)file.failWrite(out);}}
}
