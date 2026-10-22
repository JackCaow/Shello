package dev.ghostty.android.sample;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Private, non-backed-up thumbnails. Serial IO also orders writes/deletes across service recreation. */
final class SessionPreviewStore {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final long MAX_BYTES=24L*1024*1024;
    private final File directory;
    private final Handler main=new Handler(Looper.getMainLooper());
    SessionPreviewStore(Context context){this(new File(context.getNoBackupFilesDir(),"session-previews"));}
    SessionPreviewStore(File directory){this.directory=directory;}
    File file(String id){
        try{return new File(directory,PairingClient.hex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)))+".png");}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    void load(String id,Consumer<Bitmap> result){IO.execute(()->{
        Bitmap bitmap=null;AtomicFile file=new AtomicFile(file(id));
        try(InputStream input=file.openRead()){
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeStream(input,null,bounds);
            if(bounds.outWidth>0&&bounds.outWidth<=480&&bounds.outHeight>0&&bounds.outHeight<=480){
                try(InputStream pixels=file.openRead()){bitmap=BitmapFactory.decodeStream(pixels);}
            }
            if(bitmap==null)file.delete();
        }catch(IOException ignored){}
        Bitmap loaded=bitmap;main.post(()->result.accept(loaded));
    });}
    void save(String id,Bitmap bitmap){
        // The UI owns/recycles the original; the worker must have its own pixels.
        Bitmap copy=bitmap.copy(Bitmap.Config.ARGB_8888,false);
        IO.execute(()->{
            AtomicFile file=new AtomicFile(file(id));FileOutputStream output=null;
            try{
                if(!directory.isDirectory()&&!directory.mkdirs())return;
                output=file.startWrite();if(!copy.compress(Bitmap.CompressFormat.PNG,100,output))throw new IOException("PNG encoding failed");
                file.finishWrite(output);output=null;trim();
            }catch(IOException ignored){if(output!=null)file.failWrite(output);}
            finally{copy.recycle();}
        });
    }
    void remove(String id){IO.execute(()->new AtomicFile(file(id)).delete());}
    void retain(Collection<String> ids){
        Set<String> keep=new HashSet<>();for(String id:ids)keep.add(file(id).getName());
        IO.execute(()->{File[] files=directory.listFiles();if(files!=null)for(File file:files){String name=file.getName().replaceFirst("\\.(bak|new)$","");if(!keep.contains(name))file.delete();}});
    }
    private void trim(){
        File[] files=directory.listFiles((dir,name)->name.endsWith(".png"));if(files==null)return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified));long size=0;for(File file:files)size+=file.length();
        for(File file:files){if(size<=MAX_BYTES)break;long length=file.length();if(file.delete())size-=length;}
    }
}
