package dev.ghostty.android.sample;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.*;

/** Sample connection profiles. Secrets are encrypted with a non-exportable Android Keystore key. */
final class SshProfileStore {
    private static final String KEY_ALIAS="ghostty.sample.ssh.credentials.v1";
    private final Context context;
    SshProfileStore(Context context){this.context=context.getApplicationContext();}
    static final class Draft {
        String name="",host="",user="",auth="password",password="",passphrase="";
        int port=22;boolean tmux;
        String tmuxSession="ghostty_"+UUID.randomUUID().toString().replace("-","");
        byte[] privateKey=new byte[0],knownHosts=new byte[0];
        Properties properties(){
            Properties p=new Properties();p.setProperty("name",name);p.setProperty("host",host);
            p.setProperty("port",Integer.toString(port));p.setProperty("user",user);p.setProperty("auth",auth);
            p.setProperty("sessionMode",tmux?"tmux":"plain");if(tmux)p.setProperty("tmuxSession",tmuxSession);return p;
        }
        String validate(){
            if(host.isEmpty()||host.length()>253||host.matches(".*[\\s/@\\\\].*")||host.startsWith("-")
                    ||host.indexOf(':')>=0&&!host.matches("[0-9a-fA-F:.%]+"))return "请填写主机名或 IP 地址，不要包含协议和端口";
            if(port<1||port>65535)return "端口必须为 1–65535";
            if(user.isEmpty()||user.matches(".*[\\s\\p{Cntrl}].*"))return "请填写有效的 SSH 用户名";
            if("password".equals(auth)&&password.isEmpty())return "请输入登录密码";
            if("key".equals(auth)&&privateKey.length==0)return "请选择 SSH 私钥文件";
            if(!"password".equals(auth)&&!"key".equals(auth))return "请选择认证方式";
            return null;
        }
    }
    static final class Entry {
        final String id,name,host,user;final int port;
        Entry(String id,Properties p){this.id=id;name=p.getProperty("name",p.getProperty("host","SSH"));host=p.getProperty("host","");user=p.getProperty("user","");port=Integer.parseInt(p.getProperty("port","22"));}
    }
    File directory(String id){
        if(id==null||!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IllegalArgumentException("Invalid profile ID");
        return new File(context.getFilesDir(),"ssh-profiles/"+id);
    }
    File activeDirectory(){
        String id=activeId();return id==null?new File(context.getFilesDir(),"ssh"):directory(id);
    }
    String activeId(){return context.getSharedPreferences("ssh_profiles",0).getString("active",null);}
    void activate(String id)throws IOException {
        if(!new File(directory(id),"connection.properties").isFile())throw new IOException("Profile missing");
        if(!context.getSharedPreferences("ssh_profiles",0).edit().putString("active",id).commit())throw new IOException("Profile selection failed");
    }
    List<Entry> list(){
        List<Entry> entries=new ArrayList<>();File[] dirs=new File(context.getFilesDir(),"ssh-profiles").listFiles();
        if(dirs!=null)for(File dir:dirs)try{entries.add(new Entry(dir.getName(),properties(dir)));}catch(Exception ignored){}
        entries.sort(Comparator.comparing(e->e.name));return entries;
    }
    void delete(String id)throws IOException {
        File dir=directory(id);
        if(id.equals(activeId()))context.getSharedPreferences("ssh_profiles",0).edit().remove("active").commit();
        File[] files=dir.listFiles();if(files!=null)for(File f:files)if(!f.delete())throw new IOException("Delete failed");
        if(dir.exists()&&!dir.delete())throw new IOException("Delete failed");
    }
    String save(Draft draft)throws Exception {
        String error=draft.validate();if(error!=null||draft.knownHosts.length==0)throw new IllegalArgumentException("Unverified profile");
        String id=UUID.randomUUID().toString();File dir=directory(id);
        if(!dir.mkdirs())throw new IOException("Cannot save profile");
        try{
            JSONObject secrets=new JSONObject().put("password",draft.password).put("passphrase",draft.passphrase)
                    .put("privateKey",Base64.encodeToString(draft.privateKey,Base64.NO_WRAP));
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
            byte[] encrypted=cipher.doFinal(secrets.toString().getBytes(StandardCharsets.UTF_8));
            try(DataOutputStream out=new DataOutputStream(new FileOutputStream(new File(dir,"credentials.enc")))){
                out.writeInt(cipher.getIV().length);out.write(cipher.getIV());out.write(encrypted);
            }
            Files.write(new File(dir,"known_hosts").toPath(),draft.knownHosts);
            // Publish metadata last: incomplete profiles never appear in the connection list.
            try(OutputStream out=new FileOutputStream(new File(dir,"connection.properties"))){draft.properties().store(out,null);}
            return id;
        }catch(Exception errorSaving){delete(id);throw errorSaving;}
    }
    static Properties properties(File dir)throws IOException {
        Properties p=new Properties();try(InputStream in=new FileInputStream(new File(dir,"connection.properties"))){p.load(in);}return p;
    }
    Draft read(File dir)throws Exception {
        Properties p=properties(dir);Draft d=new Draft();d.name=p.getProperty("name","SSH");d.host=p.getProperty("host","");d.user=p.getProperty("user","");
        d.port=Integer.parseInt(p.getProperty("port","22"));d.auth=p.getProperty("auth","key");d.tmux="tmux".equals(p.getProperty("sessionMode"));d.tmuxSession=p.getProperty("tmuxSession",d.tmuxSession);d.knownHosts=Files.readAllBytes(new File(dir,"known_hosts").toPath());
        File encrypted=new File(dir,"credentials.enc");
        if(!encrypted.isFile()){d.privateKey=Files.readAllBytes(new File(dir,"client_key").toPath());return d;}
        try(DataInputStream in=new DataInputStream(new FileInputStream(encrypted))){
            int length=in.readInt();if(length!=12)throw new IOException("Invalid credential IV");byte[] iv=new byte[length];in.readFully(iv);
            byte[] bytes=new byte[(int)encrypted.length()-4-length];in.readFully(bytes);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            JSONObject secrets=new JSONObject(new String(cipher.doFinal(bytes),StandardCharsets.UTF_8));
            d.password=secrets.getString("password");d.passphrase=secrets.getString("passphrase");d.privateKey=Base64.decode(secrets.getString("privateKey"),Base64.NO_WRAP);
        }return d;
    }
    private static synchronized javax.crypto.SecretKey key()throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(KEY_ALIAS)){
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();
        }return (javax.crypto.SecretKey)store.getKey(KEY_ALIAS,null);
    }
}
