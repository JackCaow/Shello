package dev.ghostty.android.sample;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.UUID;

/** Only explicitly granted, random camera outputs are shared with the camera app. */
public final class AttachmentCameraProvider extends ContentProvider {
    static Uri create(Context context)throws IOException{
        File folder=new File(context.getCacheDir(),"attachment-camera");
        if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Camera cache unavailable");
        File file=new File(folder,UUID.randomUUID()+".jpg");if(!file.createNewFile())throw new IOException("Camera output exists");
        return new Uri.Builder().scheme("content").authority(context.getPackageName()+".attachment-camera").appendPath(file.getName()).build();
    }
    static File file(Context context,Uri uri)throws FileNotFoundException{
        if(uri==null||!"content".equals(uri.getScheme())||!(context.getPackageName()+".attachment-camera").equals(uri.getAuthority())||uri.getPathSegments().size()!=1||!uri.getLastPathSegment().matches("[0-9a-f-]{36}\\.jpg"))throw new FileNotFoundException("Invalid camera output");
        return new File(new File(context.getCacheDir(),"attachment-camera"),uri.getLastPathSegment());
    }
    static void remove(Context context,Uri uri){try{file(context,uri).delete();context.revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignored){}}
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){return "image/jpeg";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        try{File file=file(getContext(),uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] values=new Object[columns.length];for(int i=0;i<columns.length;i++){if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))values[i]=file.getName();if(OpenableColumns.SIZE.equals(columns[i]))values[i]=file.length();}cursor.addRow(values);return cursor;}
        catch(FileNotFoundException invalid){return null;}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{return ParcelFileDescriptor.open(file(getContext(),uri),ParcelFileDescriptor.parseMode(mode));}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
}
