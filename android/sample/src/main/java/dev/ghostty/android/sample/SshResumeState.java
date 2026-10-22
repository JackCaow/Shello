package dev.ghostty.android.sample;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;

/** Only remembers a successfully attached tmux profile; never stores terminal input or credentials. */
final class SshResumeState {
    private final File directory;
    private Boolean tmuxOverride;
    private final Context context;
    private final SharedPreferences preferences;
    SshResumeState(Context context) {
        this.context=context.getApplicationContext();this.directory=null;this.preferences=context.getSharedPreferences("ssh_resume",Context.MODE_PRIVATE);
    }
    SshResumeState(File directory, SharedPreferences preferences) {
        this.context=null;this.directory=directory;this.preferences=preferences;
    }
    SshResumeState(File directory,SharedPreferences preferences,Boolean tmux){this(directory,preferences);tmuxOverride=tmux;}
    String identity() {
        try {
            File directory=context==null?this.directory:new SshProfileStore(context).activeDirectory();
            Properties profile=SshProfileStore.properties(directory);
            if(!(tmuxOverride==null?"tmux".equals(profile.getProperty("sessionMode")):tmuxOverride))return null;
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            String[] files=SshProfileStore.snapshotExists(directory)?new String[]{"profile.enc"}:new String[]{"connection.properties","known_hosts",new File(directory,"credentials.enc").isFile()?"credentials.enc":"client_key"};
            for(String name:files) {
                byte[] bytes=java.nio.file.Files.readAllBytes(new File(directory,name).toPath());
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return android.util.Base64.encodeToString(digest.digest(),android.util.Base64.NO_WRAP);
        } catch(IOException|IllegalArgumentException|NoSuchAlgorithmException error){return null;}
    }
    void remember(String identity) {
        // Synchronous small write: an immediate process kill must not lose the user's stop/restore intent.
        if(identity==null)clear();else preferences.edit().putString("profile",identity).commit();
    }
    boolean pending() {
        String saved=preferences.getString("profile",null);
        if(saved==null)return false;
        if(saved.equals(identity()))return true;
        clear();return false;
    }
    void clear(){preferences.edit().remove("profile").commit();}
}
