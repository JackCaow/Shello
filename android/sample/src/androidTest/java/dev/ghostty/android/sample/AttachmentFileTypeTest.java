package dev.ghostty.android.sample;

import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class AttachmentFileTypeTest {
    @Test public void genericProvidersAndOfficeZipMimeKeepUsefulTypes(){
        assertEquals(AttachmentFileType.PRESENTATION,AttachmentFileType.resolve("季度汇报.PPTX","application/zip"));
        assertEquals(AttachmentFileType.WORD,AttachmentFileType.resolve("合同.docx","application/octet-stream"));
        assertEquals(AttachmentFileType.SPREADSHEET,AttachmentFileType.resolve("统计.xlsx",null));
        assertEquals(AttachmentFileType.AUDIO,AttachmentFileType.resolve("recording.M4A","application/octet-stream"));
        assertEquals(AttachmentFileType.ARCHIVE,AttachmentFileType.resolve("backup.tar.gz","application/octet-stream"));
        assertEquals(AttachmentFileType.CODE,AttachmentFileType.resolve(".env","text/plain"));
        assertEquals(AttachmentFileType.PDF,AttachmentFileType.resolve("document"," APPLICATION/PDF; charset=binary"));
        assertEquals(AttachmentFileType.WORD,AttachmentFileType.resolve("export","application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertEquals(AttachmentFileType.AUDIO,AttachmentFileType.resolve("recording","audio/x-wav"));
        assertEquals(AttachmentFileType.TEXT,AttachmentFileType.resolve("notes","text/plain"));
        assertEquals(AttachmentFileType.OTHER,AttachmentFileType.resolve(null,null));
        assertEquals(AttachmentFileType.OTHER,AttachmentFileType.resolve("download.unfamiliar","application/octet-stream"));
    }
    @Test public void categoriesHaveDistinctArtworkBeyondTheirColors(){
        Set<Integer> shapes=new HashSet<>();
        for(AttachmentFileType type:AttachmentFileType.values()){
            Bitmap bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);ToolbarIcon icon=new ToolbarIcon(type.icon,96,Color.WHITE);icon.setBounds(0,0,96,96);icon.draw(new Canvas(bitmap));
            int[] pixels=new int[96*96];bitmap.getPixels(pixels,0,96,0,0,96,96);int count=0;for(int pixel:pixels)if(Color.alpha(pixel)>0)count++;
            assertTrue("Empty icon: "+type,count>80);assertTrue("Categories must differ by shape, not just tint: "+type,shapes.add(Arrays.hashCode(pixels)));bitmap.recycle();
        }
    }
    @Test public void actualThumbnailsTakePrecedenceOverFallbackArtwork(){
        var inst=InstrumentationRegistry.getInstrumentation();var context=inst.getTargetContext();Bitmap bitmap=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.CYAN);
        try{inst.runOnMainSync(()->{
            AttachmentTransfer upload=new AttachmentTransfer(context,"unused",AttachmentTestProvider.uri("normal"),()->{});upload.name="photo.png";upload.mime="image/png";upload.thumbnail=bitmap;
            AttachmentChip chip=new AttachmentChip(context,upload,()->{},()->{});ImageView image=(ImageView)chip.getChildAt(0);
            assertTrue(image.getDrawable() instanceof BitmapDrawable);assertSame(bitmap,((BitmapDrawable)image.getDrawable()).getBitmap());assertEquals(ImageView.ScaleType.CENTER_CROP,image.getScaleType());
        });}finally{bitmap.recycle();}
    }
}
