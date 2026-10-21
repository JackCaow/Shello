package dev.ghostty.android.sample;

import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class SessionPreviewStoreTest {
    private File directory;
    private SessionPreviewStore store;
    @Before public void setup(){directory=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"preview-test-"+UUID.randomUUID());store=new SessionPreviewStore(directory);}
    private Bitmap image(int color){Bitmap bitmap=Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color);return bitmap;}
    private Bitmap load(SessionPreviewStore source,String id)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Bitmap> result=new AtomicReference<>();
        source.load(id,b->{result.set(b);done.countDown();});assertTrue("Preview IO timed out",done.await(5,TimeUnit.SECONDS));return result.get();
    }
    @Test public void freshStoreRestoresPixelsAfterOriginalIsRecycled()throws Exception{
        Bitmap source=image(Color.GREEN);store.save("host@session-a",source);source.recycle();
        Bitmap restored=load(new SessionPreviewStore(directory),"host@session-a");assertNotNull(restored);assertEquals(480,restored.getWidth());assertEquals(Color.GREEN,restored.getPixel(200,200));restored.recycle();
        store.remove("host@session-a");assertNull(load(store,"host@session-a"));directory.delete();
    }
    @Test public void latestWriteWinsAndCloseCannotResurrectQueuedPreview()throws Exception{
        Bitmap first=image(Color.RED),last=image(Color.BLUE);store.save("tab",first);store.save("tab",last);first.recycle();last.recycle();
        Bitmap restored=load(store,"tab");assertNotNull(restored);assertEquals(Color.BLUE,restored.getPixel(0,0));restored.recycle();
        Bitmap pending=image(Color.GREEN);store.save("tab",pending);store.remove("tab");pending.recycle();
        assertNull(load(new SessionPreviewStore(directory),"tab"));directory.delete();
    }
    @Test public void sessionKeysAreIsolatedAndStaleTabsArePruned()throws Exception{
        String a="../../host@session-a",b="host@session-b";Bitmap source=image(Color.GREEN);
        assertEquals(directory.getCanonicalFile(),store.file(a).getParentFile().getCanonicalFile());
        store.save(a,source);store.save(b,source);source.recycle();store.retain(Collections.singleton(b));
        assertNull(load(store,a));Bitmap restored=load(store,b);assertNotNull(restored);restored.recycle();
        store.remove(b);assertNull(load(store,b));directory.delete();
    }
    @Test public void corruptAndOversizedImagesFallBackWithoutCrashing()throws Exception{
        assertTrue(directory.mkdirs());try(FileOutputStream out=new FileOutputStream(store.file("broken"))){out.write(new byte[]{1,2,3});}
        assertNull(load(store,"broken"));assertFalse(store.file("broken").exists());
        Bitmap huge=Bitmap.createBitmap(481,480,Bitmap.Config.ARGB_8888);
        try(FileOutputStream out=new FileOutputStream(store.file("oversized"))){huge.compress(Bitmap.CompressFormat.PNG,100,out);}huge.recycle();
        assertNull(load(store,"oversized"));assertFalse(store.file("oversized").exists());directory.delete();
    }
}
