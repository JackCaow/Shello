package dev.ghostty.android.sample;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Tab identity includes its remote target; multiple shells on one host remain independent. */
final class SessionTarget {
    static String profile(String id){int i=id.indexOf('@');return i<0?id:id.substring(0,i);}
    static String target(String id){int i=id.indexOf('@');if(i<0)return null;return new String(Base64.decode(id.substring(i+1),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING),StandardCharsets.UTF_8);}
    static String id(String profile,String target){validate(target);return profile+"@"+Base64.encodeToString(target.getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    static void validate(String target){if(target==null||target.length()>180||target.matches(".*[\\p{Cntrl}].*")||!(target.startsWith("=")&&target.length()>1||target.matches("ghostty_[0-9a-f]{32}")||target.matches("plain_[0-9a-f]{32}")||agent(target)))throw new IllegalArgumentException("Invalid session target");}
    static String fresh(boolean tmux){return (tmux?"ghostty_":"plain_")+UUID.randomUUID().toString().replace("-","");}
    static boolean agent(String target){return target!=null&&target.matches("agent:(claude|codex):[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");}
    static String storageKey(String id){if(!id.contains("@"))return id;try{return "target_"+PairingClient.hex(java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
    static String command(String target){validate(target);if(agent(target)){String[] parts=target.split(":");return AgentHub.COMMAND+"resume "+SshCommand.quote(parts[1])+" "+SshCommand.quote(parts[2]);}return SshConnectionSetup.TMUX_LOOKUP+"exec \"$T\" "+(target.startsWith("=")?"attach-session -t "+SshCommand.quote(target):"new-session -A -s "+SshCommand.quote(target));}
}
