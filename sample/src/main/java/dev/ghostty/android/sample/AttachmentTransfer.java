package dev.ghostty.android.sample;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import com.jcraft.jsch.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** One bounded streaming SFTP upload. Owned by the SSH service, never by an Activity. */
final class AttachmentTransfer implements AutoCloseable {
    static final long MAX_BYTES=50L*1024*1024;
    private final Context context;
    private final String profile;
    private final Uri source;
    private final Runnable changed;
    private final Handler main=new Handler(Looper.getMainLooper());
    volatile String name="",path;
    volatile long bytes;
    volatile boolean busy=true;
    volatile int failure=R.string.attachment_upload_failed;
    private volatile boolean cancelled;
    private volatile Session session;
    private volatile ChannelSftp channel;
    private volatile InputStream input;
    private long lastProgress;
    volatile String diagnostic="";
    AttachmentTransfer(Context context,String profile,Uri source,Runnable changed){
        this.context=context.getApplicationContext();this.profile=profile;this.source=source;this.changed=changed;
    }
    void start(){new Thread(this::upload,"ssh-attachment").start();}
    private void signal(){main.post(()->{if(!cancelled)changed.run();});}
    private void upload(){
        String folder=null,partial=null;boolean complete=false;
        try{
            if(!"content".equals(source.getScheme()))throw new IOException("Unsupported source");
            String display="attachment";long declared=-1;
            try(Cursor cursor=context.getContentResolver().query(source,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
                if(cursor!=null&&cursor.moveToFirst()){
                    int n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME),s=cursor.getColumnIndex(OpenableColumns.SIZE);
                    if(n>=0&&!cursor.isNull(n))display=cursor.getString(n);if(s>=0&&!cursor.isNull(s))declared=cursor.getLong(s);
                }
            }
            name=safeName(display);if(declared>MAX_BYTES)throw new TooLarge();signal();
            input=context.getContentResolver().openInputStream(source);if(input==null)throw new IOException("Cannot read document");check();
            SshProfileStore store=new SshProfileStore(context);
            SshProfileStore.Draft credentials=store.read("fixture".equals(profile)?new File(context.getFilesDir(),"ssh"):store.directory(profile));
            JSch jsch=new JSch();jsch.setKnownHosts(new ByteArrayInputStream(credentials.knownHosts));
            session=SshConnectionSetup.session(jsch,credentials);check();session.setTimeout(15000);session.connect(10000);check();
            channel=(ChannelSftp)session.openChannel("sftp");channel.connect(10000);check();
            String home=channel.getHome();if(home==null||!home.startsWith("/")||home.codePoints().anyMatch(Character::isISOControl))throw new IOException("Invalid remote home");
            String base=home;
            for(String part:new String[]{".local","share","shello","attachments"}){base+="/"+part;directory(base);}
            folder=base+"/"+UUID.randomUUID();channel.mkdir(folder);channel.chmod(0700,folder);
            String destination=folder+"/"+name;partial=folder+"/.upload-part";
            // The private random directory prevents readers seeing an incomplete file.
            // Rename only after the complete bounded stream has reached the server.
            try(InputStream raw=input){
                check();
                try(InputStream bounded=new BoundedInput(raw,MAX_BYTES,()->{
                    check();long now=SystemClock.uptimeMillis();if(now-lastProgress>150){lastProgress=now;signal();}
                });OutputStream out=channel.put(partial,ChannelSftp.OVERWRITE)){
                    channel.chmod(0600,partial);byte[] buffer=new byte[32768];int count;
                    long deadline=SystemClock.uptimeMillis()+120000;
                    while((count=bounded.read(buffer))!=-1){check();if(SystemClock.uptimeMillis()>deadline)throw new IOException("Upload timed out");out.write(buffer,0,count);bytes+=count;}
                }
            }
            check();channel.rename(partial,destination);complete=true;path=destination;
        }catch(Exception error){
            diagnostic=error.getClass().getSimpleName()+": "+error.getMessage();
            if(error instanceof TooLarge)failure=R.string.attachment_too_large;
            else if(error instanceof JSchException)failure=SshConnectionSetup.messageId(error);
        }finally{
            ChannelSftp sftp=channel;
            if(!complete&&sftp!=null&&sftp.isConnected()){
                if(partial!=null)try{sftp.rm(partial);}catch(Exception ignored){}
                if(folder!=null)try{sftp.rmdir(folder);}catch(Exception ignored){}
            }
            if(sftp!=null)sftp.disconnect();Session ssh=session;if(ssh!=null)ssh.disconnect();
            InputStream raw=input;if(raw!=null)try{raw.close();}catch(IOException ignored){}input=null;busy=false;signal();
        }
    }
    private void directory(String path)throws SftpException{
        SftpATTRS attributes;
        try{attributes=channel.lstat(path);}catch(SftpException missing){if(missing.id!=ChannelSftp.SSH_FX_NO_SUCH_FILE)throw missing;channel.mkdir(path);channel.chmod(0700,path);attributes=channel.lstat(path);}
        if(!attributes.isDir()||attributes.isLink())throw new SftpException(ChannelSftp.SSH_FX_FAILURE,"Upload directory is not a directory");
    }
    private void check()throws IOException{if(cancelled)throw new IOException("Cancelled");}
    @Override public void close(){
        cancelled=true;
        new Thread(()->{InputStream raw=input;if(raw!=null)try{raw.close();}catch(IOException ignored){}},"ssh-attachment-source-close").start();
        // Let the worker remove its partial file first; disconnect a stalled socket after a grace period.
        main.postDelayed(()->{if(busy)new Thread(()->{ChannelSftp sftp=channel;if(sftp!=null)sftp.disconnect();Session ssh=session;if(ssh!=null)ssh.disconnect();},"ssh-attachment-cancel").start();},1000);
    }
    static String safeName(String value){
        String clean=(value==null?"":value).replaceAll("[\\p{Cntrl}/\\\\\\u2028\\u2029]","_");
        clean=clean.replaceAll("[?*\\[\\]]","_");
        if(clean.trim().isEmpty()||clean.equals(".")||clean.equals("..")||clean.equals(".upload-part"))clean="attachment";
        StringBuilder result=new StringBuilder();int length=0;
        for(int cp:clean.codePoints().toArray()){String part=new String(Character.toChars(cp));int n=part.getBytes(StandardCharsets.UTF_8).length;if(length+n>180)break;result.append(part);length+=n;}
        return result.toString();
    }
    static final class TooLarge extends IOException {}
    interface Check {void run()throws IOException;}
    static final class BoundedInput extends FilterInputStream {
        private final long limit;private final Check check;private long count;
        BoundedInput(InputStream input,long limit,Check check){super(input);this.limit=limit;this.check=check;}
        private void added(long n)throws IOException{if(n>0){count+=n;if(count>limit)throw new TooLarge();}}
        @Override public int read()throws IOException{check.run();int n=in.read();added(n<0?0:1);return n;}
        @Override public int read(byte[] b,int off,int len)throws IOException{check.run();int n=in.read(b,off,(int)Math.min(len,limit-count+1));added(n);return n;}
    }
}
