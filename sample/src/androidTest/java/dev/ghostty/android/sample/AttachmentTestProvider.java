package dev.ghostty.android.sample;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Synthetic test documents only; packaged in the instrumentation APK, never the app. */
public final class AttachmentTestProvider extends ContentProvider {
    static final String AUTHORITY="dev.ghostty.android.sample.test.attachments";
    static final String NAME="中文 file ' $() [1] 👨‍👩‍👧‍👦.txt";
    static final byte[] DATA=(String.join("",java.util.Collections.nCopies(16384,"Shello 附件 中文\n' $(printf should-not-run) 👨‍👩‍👧‍👦\n"))).getBytes(StandardCharsets.UTF_8);
    static Uri uri(String document){return Uri.parse("content://"+AUTHORITY+"/"+document);}
    @Override public boolean onCreate(){return true;}
    private void valid(Uri uri){if(!uri.equals(uri("normal"))&&!uri.equals(uri("oversize")))throw new IllegalArgumentException("Unknown fixture");}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){valid(uri);MatrixCursor rows=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});rows.addRow(new Object[]{NAME,uri.equals(uri("oversize"))?AttachmentTransfer.MAX_BYTES+1:DATA.length});return rows;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        valid(uri);if(!"r".equals(mode))throw new FileNotFoundException();
        File file=new File(getContext().getCacheDir(),"synthetic-attachment.txt");try(FileOutputStream out=new FileOutputStream(file)){out.write(DATA);}catch(IOException e){throw new FileNotFoundException(e.toString());}
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri){return "text/plain";}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException();}
}
