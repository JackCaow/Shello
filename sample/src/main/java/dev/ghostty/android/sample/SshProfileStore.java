package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.AtomicFile;
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
            p.setProperty("sessionMode",tmux?"tmux":"plain");if(tmux&&tmuxSession!=null)p.setProperty("tmuxSession",tmuxSession);return p;
        }
        String validate(){
            if(host.isEmpty()||host.length()>253||host.matches(".*[\\s/@\\\\].*")||host.startsWith("-")
                    ||host.indexOf(':')>=0&&!host.matches("[0-9a-fA-F:.%]+"))return tr(R.string.enter_a_hostname_or_ip_address_without_a_protocol_or_port);
            if(port<1||port>65535)return tr(R.string.port_must_be_between_1_and_65535);
            if(user.isEmpty()||user.matches(".*[\\s\\p{Cntrl}].*"))return tr(R.string.enter_a_valid_ssh_username);
            if("password".equals(auth)&&password.isEmpty())return tr(R.string.enter_your_login_password);
            if("key".equals(auth)&&privateKey.length==0)return tr(R.string.choose_an_ssh_private_key_file);
            if(!"password".equals(auth)&&!"key".equals(auth))return tr(R.string.choose_an_authentication_method);
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
        if(!exists(directory(id)))throw new IOException("Profile missing");
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
    static boolean snapshotExists(File dir){return new File(dir,"profile.enc").isFile()||new File(dir,"profile.enc.bak").isFile();}
    static boolean exists(File dir){return snapshotExists(dir)||new File(dir,"connection.properties").isFile();}
    String save(Draft draft)throws Exception {
        String id=UUID.randomUUID().toString();File dir=directory(id);
        if(!dir.mkdirs())throw new IOException("Cannot save profile");
        try{write(dir,draft);return id;}catch(Exception error){delete(id);throw error;}
    }
    void update(String id,Draft draft)throws Exception {
        File dir=directory(id);if(!exists(dir))throw new IOException("Profile missing");
        write(dir,draft);
    }
    private static synchronized void write(File dir,Draft draft)throws Exception {
        if(draft.validate()!=null||draft.knownHosts.length==0)throw new IllegalArgumentException("Unverified profile");
        JSONObject metadata=new JSONObject();Properties properties=draft.properties();
        for(String name:properties.stringPropertyNames())metadata.put(name,properties.getProperty(name));
        JSONObject value=new JSONObject().put("properties",metadata)
                .put("knownHosts",Base64.encodeToString(draft.knownHosts,Base64.NO_WRAP))
                .put("password",draft.password).put("passphrase",draft.passphrase)
                .put("privateKey",Base64.encodeToString(draft.privateKey,Base64.NO_WRAP));
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        // Metadata, host trust and credentials switch together, including after a process crash.
        AtomicFile file=new AtomicFile(new File(dir,"profile.enc"));FileOutputStream stream=null;
        try{
            stream=file.startWrite();DataOutputStream out=new DataOutputStream(stream);
            out.writeInt(cipher.getIV().length);out.write(cipher.getIV());out.write(encrypted);out.flush();
            file.finishWrite(stream);
        }catch(Exception error){if(stream!=null)file.failWrite(stream);throw error;}
        // Legacy profiles migrate only after the complete new snapshot is durable.
        for(String old:new String[]{"connection.properties","known_hosts","credentials.enc","client_key"})new File(dir,old).delete();
    }
    private static JSONObject decrypt(InputStream source)throws Exception {
        try(DataInputStream in=new DataInputStream(source)){
            int length=in.readInt();if(length!=12)throw new IOException("Invalid credential IV");byte[] iv=new byte[length];in.readFully(iv);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
            while((count=in.read(buffer))!=-1){if(bytes.size()+count>4*1024*1024)throw new IOException("Profile too large");bytes.write(buffer,0,count);}
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            return new JSONObject(new String(cipher.doFinal(bytes.toByteArray()),StandardCharsets.UTF_8));
        }
    }
    private static Properties metadata(JSONObject snapshot)throws Exception {
        Properties p=new Properties();JSONObject fields=snapshot.getJSONObject("properties");
        for(Iterator<String> names=fields.keys();names.hasNext();){String name=names.next();p.setProperty(name,fields.getString(name));}return p;
    }
    static synchronized Properties properties(File dir)throws IOException {
        if(snapshotExists(dir))try{return metadata(decrypt(new AtomicFile(new File(dir,"profile.enc")).openRead()));}
            catch(Exception error){throw new IOException("Cannot read profile",error);}
        Properties p=new Properties();try(InputStream in=new FileInputStream(new File(dir,"connection.properties"))){p.load(in);}return p;
    }
    Draft read(File dir)throws Exception {synchronized(SshProfileStore.class){
        JSONObject secrets=snapshotExists(dir)?decrypt(new AtomicFile(new File(dir,"profile.enc")).openRead()):null;
        Properties p=secrets==null?properties(dir):metadata(secrets);Draft d=new Draft();d.name=p.getProperty("name","SSH");d.host=p.getProperty("host","");d.user=p.getProperty("user","");
        d.port=Integer.parseInt(p.getProperty("port","22"));d.auth=p.getProperty("auth","key");d.tmux="tmux".equals(p.getProperty("sessionMode"));d.tmuxSession=p.getProperty("tmuxSession");
        d.knownHosts=secrets==null?Files.readAllBytes(new File(dir,"known_hosts").toPath()):Base64.decode(secrets.getString("knownHosts"),Base64.NO_WRAP);
        if(secrets==null){
            File encrypted=new File(dir,"credentials.enc");
            if(!encrypted.isFile()){d.privateKey=Files.readAllBytes(new File(dir,"client_key").toPath());return d;}
            secrets=decrypt(new FileInputStream(encrypted));
        }
        d.password=secrets.getString("password");d.passphrase=secrets.getString("passphrase");d.privateKey=Base64.decode(secrets.getString("privateKey"),Base64.NO_WRAP);return d;
    }}
    private static synchronized javax.crypto.SecretKey key()throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(KEY_ALIAS)){
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();
        }return (javax.crypto.SecretKey)store.getKey(KEY_ALIAS,null);
    }
}
