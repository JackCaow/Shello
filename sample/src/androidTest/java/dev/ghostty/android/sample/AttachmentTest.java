package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

public class AttachmentTest extends AttachmentTestSupport {
    void assertSourceSheet(){inst.runOnMainSync(()->{
        Dialog dialog=(Dialog)field(activity,"attachmentSheet");assertTrue(dialog.isShowing());View root=dialog.getWindow().getDecorView();
        for(int id:new int[]{R.string.attachment_camera,R.string.attachment_photos,R.string.attachment_files}){View row=find(root,AppLanguage.tr(id));assertNotNull(row);Rect bounds=new Rect();assertTrue(row.getGlobalVisibleRect(bounds));assertTrue(bounds.height()>=SampleUi.dp(activity,64));}
        int[] at=new int[2];root.getLocationOnScreen(at);assertTrue(at[1]>=0);assertTrue(root.getWidth()<=activity.getResources().getDisplayMetrics().widthPixels);
    });}
    void closeSheet(){inst.runOnMainSync(()->{Dialog dialog=(Dialog)field(activity,"attachmentSheet");if(dialog!=null)dialog.dismiss();});}
    @Test public void sourceSheetShowsAllSourcesWithKeyboardOpenAndClosed()throws Exception{
        open();draftMode();try{inst.runOnMainSync(()->call(activity,"hideKeyboard"));SystemClock.sleep(350);inst.runOnMainSync(()->((View)field(activity,"addTextButton")).performClick());SystemClock.sleep(350);assertSourceSheet();capture("sources-portrait");
            inst.runOnMainSync(()->{Dialog dialog=(Dialog)field(activity,"attachmentSheet");View panel=((android.widget.ScrollView)((ViewGroup)dialog.getWindow().getDecorView().findViewById(android.R.id.content)).getChildAt(0)).getChildAt(0);long at=SystemClock.uptimeMillis();for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}){MotionEvent touch=MotionEvent.obtain(at,at+20,action,SampleUi.dp(activity,40),SampleUi.dp(activity,action==MotionEvent.ACTION_DOWN?10:90),0);panel.dispatchTouchEvent(touch);touch.recycle();}assertFalse("Dragging the sheet handle area must dismiss",dialog.isShowing());});closeSheet();
            await(()->activity.hasWindowFocus());inst.runOnMainSync(()->call(activity,"showKeyboard"));await(()->(boolean)field(activity,"imeVisible"));inst.runOnMainSync(()->((View)field(activity,"addTextButton")).performClick());SystemClock.sleep(500);assertSourceSheet();
        }finally{closeSheet();inst.runOnMainSync(()->call(activity,"hideKeyboard"));}
    }
    @Test public void sourceSheetFitsLandscape()throws Exception{
        open();int before=activity.getRequestedOrientation();
        try{inst.runOnMainSync(()->activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));await(()->activity.hasWindowFocus()&&!activity.isDestroyed()&&activity.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE);draftMode();inst.runOnMainSync(()->call(activity,"hideKeyboard"));SystemClock.sleep(350);inst.runOnMainSync(()->((View)field(activity,"addTextButton")).performClick());SystemClock.sleep(350);
            inst.runOnMainSync(()->{Dialog dialog=(Dialog)field(activity,"attachmentSheet");assertTrue(dialog.isShowing());View decor=dialog.getWindow().getDecorView();int[] at=new int[2];decor.getLocationOnScreen(at);assertTrue(at[1]>=0);assertTrue(decor.getHeight()<=activity.getResources().getDisplayMetrics().heightPixels);});capture("sources-landscape");}
        finally{closeSheet();inst.runOnMainSync(()->activity.setRequestedOrientation(before));SystemClock.sleep(500);}
    }
    @Test public void stagedAttachmentsComposeOnlyWhenReadyAndKeepTextIndependent(){
        SshSessionService.Tab tab=new SshSessionService.Tab("local",null);AttachmentTransfer first=new AttachmentTransfer(context,"unused",AttachmentTestProvider.uri("normal"),()->{});tab.attachments.add(first);
        assertNull(SshSessionService.composeDraft(tab,"draft"));first.busy=false;assertNull(SshSessionService.composeDraft(tab,"draft"));
        first.path="/tmp/中文 ' file.png";assertEquals(SshCommand.quote(first.path),SshSessionService.composeDraft(tab,""));assertEquals("draft "+SshCommand.quote(first.path),SshSessionService.composeDraft(tab,"draft"));
        AttachmentTransfer second=new AttachmentTransfer(context,"unused",AttachmentTestProvider.uri("normal"),()->{});second.busy=false;second.path="/tmp/two.mp4";tab.attachments.add(second);assertEquals("draft "+SshCommand.quote(first.path)+" "+SshCommand.quote(second.path),SshSessionService.composeDraft(tab,"draft "));assertEquals(2,tab.attachments.size());
        tab.attachments.remove(first);assertEquals("draft "+SshCommand.quote(second.path),SshSessionService.composeDraft(tab,"draft"));
    }
    @Test public void cameraOutputIsNarrowAndThumbnailIsBounded()throws Exception{
        android.net.Uri uri=AttachmentCameraProvider.create(context);
        try{android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(2048,1024,android.graphics.Bitmap.Config.ARGB_8888);image.eraseColor(android.graphics.Color.GREEN);try(OutputStream out=context.getContentResolver().openOutputStream(uri)){assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,out));}image.recycle();
            android.media.ExifInterface exif=new android.media.ExifInterface(AttachmentCameraProvider.file(context,uri).getAbsolutePath());exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,"6");exif.saveAttributes();
            android.graphics.Bitmap preview=AttachmentTransfer.thumbnail(context,uri,"image/jpeg");assertNotNull(preview);assertTrue(preview.getWidth()<=512&&preview.getHeight()<=512);assertTrue("Photo orientation must follow EXIF",preview.getHeight()>preview.getWidth());preview.recycle();
            for(String path:new String[]{"../shared_prefs/settings.xml","not-a-uuid.jpg","a/b.jpg"})try{AttachmentCameraProvider.file(context,android.net.Uri.parse("content://"+context.getPackageName()+".attachment-camera/"+path));fail("Camera provider accepted unrelated path");}catch(FileNotFoundException expected){}
        }finally{AttachmentCameraProvider.remove(context,uri);assertFalse(AttachmentCameraProvider.file(context,uri).exists());}
    }
    @Test public void photoAndVideoThumbnailsDecodeOnSupportedApi(){
        for(String fixture:new String[]{"photo","video"}){android.graphics.Bitmap preview=AttachmentTransfer.thumbnail(context,AttachmentTestProvider.uri(fixture),fixture.equals("photo")?"image/png":"video/mp4");assertNotNull(fixture+" thumbnail unavailable on API "+Build.VERSION.SDK_INT,preview);assertTrue(preview.getWidth()<=512&&preview.getHeight()<=512);preview.recycle();}
    }
    @Test public void cancelledTransferNeverPublishesAPath()throws Exception{
        java.util.concurrent.atomic.AtomicBoolean published=new java.util.concurrent.atomic.AtomicBoolean();AttachmentTransfer transfer=new AttachmentTransfer(context,"not-a-profile",AttachmentTestProvider.uri("normal"),()->published.set(true));transfer.close();transfer.start();await(()->!transfer.busy);assertNull(transfer.path);assertEquals(0,transfer.bytes);assertFalse(published.get());
    }
    @Test public void boundedUnknownLengthStreamsAndFileNames()throws Exception{
        byte[] bytes=new byte[33];try(InputStream in=new AttachmentTransfer.BoundedInput(new ByteArrayInputStream(bytes),32,()->{})){assertEquals(32,in.read(new byte[32]));try{in.read();fail("Oversize input accepted");}catch(AttachmentTransfer.TooLarge expected){}}
        try(InputStream in=new AttachmentTransfer.BoundedInput(new ByteArrayInputStream(new byte[32]),32,()->{})){assertEquals(32,in.read(new byte[32]));assertEquals(-1,in.read());}
        try(InputStream in=new AttachmentTransfer.BoundedInput(new ByteArrayInputStream(bytes),32,()->{throw new IOException("cancelled");})){try{in.read();fail("Cancellation ignored");}catch(IOException expected){assertEquals("cancelled",expected.getMessage());}}
        String name=AttachmentTransfer.safeName("../a\\b\n[?*]中文👨‍👩‍👧‍👦.png");assertFalse(name.contains("/"));assertFalse(name.contains("\\"));assertFalse(name.contains("\n"));assertFalse(name.contains("["));assertTrue(name.endsWith("中文👨‍👩‍👧‍👦.png"));assertEquals("attachment",AttachmentTransfer.safeName(".."));assertTrue(AttachmentTransfer.safeName(String.join("",Collections.nCopies(100,"中"))).getBytes(StandardCharsets.UTF_8).length<=180);
    }
    @Test public void declaredOversizeAndUnreadableDocumentDoNotUpload()throws Exception{
        AttachmentTransfer large=new AttachmentTransfer(context,"not-a-profile",AttachmentTestProvider.uri("oversize"),()->{});large.start();await(()->!large.busy);assertEquals(R.string.attachment_too_large,large.failure);assertNull(large.path);
        AttachmentTransfer missing=new AttachmentTransfer(context,"not-a-profile",AttachmentTestProvider.uri("missing"),()->{});missing.start();await(()->!missing.busy);assertNull(missing.path);
    }
    void cleanup(String profile,String path)throws Exception{if(path==null)return;String parent=path.substring(0,path.lastIndexOf('/'));assertTrue(parent.matches(".*/shello/attachments/[0-9a-f-]{36}"));run(profile,"rm -f -- "+SshCommand.quote(path)+"; rmdir -- "+SshCommand.quote(parent));}
    void verify(String profile,String path)throws Exception{
        assertNotNull(path);assertTrue(path.endsWith("/"+AttachmentTransfer.safeName(AttachmentTestProvider.NAME)));
        StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(AttachmentTestProvider.DATA))hash.append(String.format("%02x",b&255));
        String actual=run(profile,"shasum -a 256 -- "+SshCommand.quote(path));assertTrue("Remote content differs",actual.startsWith(hash.toString()+" "));
        assertEquals("600",run(profile,"stat -f '%Lp' "+SshCommand.quote(path)).trim());
    }
    @Test public void realPinnedSftpTransfersUnicodeDocumentWithoutChangingShell()throws Exception{
        String profile=profile();AttachmentTransfer transfer=new AttachmentTransfer(context,profile,AttachmentTestProvider.uri("normal"),()->{});
        try{transfer.start();await(()->!transfer.busy);assertNotNull("Upload failure resource "+transfer.failure+" "+transfer.diagnostic,transfer.path);assertEquals(AttachmentTestProvider.DATA.length,transfer.bytes);verify(profile,transfer.path);}
        finally{transfer.close();cleanup(profile,transfer.path);}
    }
    @Test public void unknownHostKeyCannotUpload()throws Exception{
        String real=profile();SshProfileStore store=new SshProfileStore(context);SshProfileStore.Draft d=store.read(store.directory(real));d.knownHosts=new String(d.knownHosts,StandardCharsets.UTF_8).replaceAll("(?m)^\\S+","untrusted.example.invalid").getBytes(StandardCharsets.UTF_8);String wrong=store.save(d);
        try{AttachmentTransfer transfer=new AttachmentTransfer(context,wrong,AttachmentTestProvider.uri("normal"),()->{});transfer.start();await(()->!transfer.busy);assertNull("Untrusted upload accepted",transfer.path);assertTrue(transfer.diagnostic,transfer.failure==R.string.server_fingerprint_mismatch_connection_stopped||transfer.failure==R.string.server_fingerprint_mismatch_connection_stopped_verify_the_key_wit);}
        finally{store.delete(wrong);}
    }
    @Test public void pickerResultSurvivesRecreationAsCardWithoutMutatingDraft()throws Exception{
        String profile=profile();open();SshSessionService initial=(SshSessionService)field(activity,"sshService");String previousTab=initial.selected()==null?null:initial.selected().id;
        String active=new SshProfileStore(context).activeId();boolean hadLocal=initial.find("local")!=null;
        if(temporaryProfile!=null){inst.runOnMainSync(()->SshSessionService.requestConnect(context,profile));await(()->initial.selected()!=null&&profile.equals(initial.selected().profile())&&initial.connected());}else await(initial::connected);
        draftMode();SshSessionService[] service={null};SshSessionService.Tab[] original={null};String[] before={null},path={null};boolean[] mode={false};
        inst.runOnMainSync(()->{service[0]=(SshSessionService)field(activity,"sshService");original[0]=service[0].selected();assertNotNull(original[0]);assertEquals(profile,original[0].profile());assertTrue(original[0].attachments.isEmpty());EditText draft=(EditText)field(activity,"draft");before[0]=draft.getText().toString();mode[0]=(boolean)field(activity,"draftMode");draft.setText("inspect ");draft.setSelection(3);});
        dev.ghostty.android.TerminalView terminal=(dev.ghostty.android.TerminalView)field(activity,"terminal");dev.ghostty.android.TerminalView.Listener prior=(dev.ghostty.android.TerminalView.Listener)field(terminal,"listener");ByteArrayOutputStream submitted=new ByteArrayOutputStream();
        inst.runOnMainSync(()->terminal.setListener(new dev.ghostty.android.TerminalView.Listener(){public void onInput(byte[] bytes){submitted.write(bytes,0,bytes.length);}public void onResize(int c,int r){prior.onResize(c,r);}}));
        IntentFilter filter=new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("*/*");
        Instrumentation.ActivityMonitor picker=inst.addMonitor(filter,new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(AttachmentTestProvider.uri("normal"))),true);
        try{
            inst.runOnMainSync(()->call(activity,"chooseAttachment"));await(()->!original[0].attachments.isEmpty());assertTrue("Expected file picker interception",picker.getHits()>0);MainActivity previous=activity;inst.runOnMainSync(activity::recreate);await(()->activity!=previous&&activity.hasWindowFocus()&&field(activity,"sshService")!=null);
            await(()->!original[0].attachments.get(0).busy);inst.runOnMainSync(()->{AttachmentTransfer upload=original[0].attachments.get(0);assertNotNull(upload.diagnostic,upload.path);path[0]=upload.path;assertEquals("inspect ",((EditText)field(activity,"draft")).getText().toString());assertEquals(3,((EditText)field(activity,"draft")).getSelectionStart());assertEquals(1,((LinearLayout)field(activity,"attachmentCards")).getChildCount());assertTrue(((View)field(activity,"sendButton")).isEnabled());});verify(profile,path[0]);capture("staged-file");
            // Recreation installs a new terminal listener; replace it before the explicit Send check.
            dev.ghostty.android.TerminalView current=(dev.ghostty.android.TerminalView)field(activity,"terminal");dev.ghostty.android.TerminalView.Listener currentPrior=(dev.ghostty.android.TerminalView.Listener)field(current,"listener");
            inst.runOnMainSync(()->{current.setListener(new dev.ghostty.android.TerminalView.Listener(){public void onInput(byte[] bytes){submitted.write(bytes,0,bytes.length);}public void onResize(int c,int r){currentPrior.onResize(c,r);}});assertEquals(0,submitted.size());});
            inst.runOnMainSync(()->{((EditText)field(activity,"draft")).setText("");assertTrue("Attachment-only Send must be enabled",((View)field(activity,"sendButton")).isEnabled());service[0].openLocal();assertEquals(1,original[0].attachments.size());assertEquals(0,((LinearLayout)field(activity,"attachmentCards")).getChildCount());service[0].select(original[0].id);assertEquals(1,((LinearLayout)field(activity,"attachmentCards")).getChildCount());assertEquals(0,submitted.size());
                try{Method send=MainActivity.class.getDeclaredMethod("sendDraft",boolean.class);send.setAccessible(true);send.invoke(activity,true);}catch(Exception error){throw new AssertionError(error);}
                assertEquals(new String(original[0].terminal().encodePaste(SshCommand.quote(path[0])),StandardCharsets.UTF_8)+new String(original[0].terminal().encodeKey(dev.ghostty.android.TerminalSession.Key.ENTER),StandardCharsets.UTF_8),new String(submitted.toByteArray(),StandardCharsets.UTF_8));assertTrue(original[0].attachments.isEmpty());assertFalse(((View)field(activity,"sendButton")).isEnabled());current.setListener(currentPrior);});
        }finally{inst.removeMonitor(picker);inst.runOnMainSync(()->{service[0].select(original[0].id);service[0].cancelAttachment(original[0].id);((EditText)field(activity,"draft")).setText(before[0]);original[0].draft=before[0];original[0].draftMode=mode[0];if(!hadLocal)service[0].close("local");if(temporaryProfile!=null)service[0].closeProfile(temporaryProfile);if(previousTab!=null)service[0].select(previousTab);
                context.getSharedPreferences("ssh_profiles",0).edit().putString("active",active).commit();});cleanup(profile,path[0]);}
    }
    @Test public void mediaAndCameraResultsStageRemovablePreviews()throws Exception{
        String profile=profile();open();SshSessionService service=(SshSessionService)field(activity,"sshService");String previous=service.selected()==null?null:service.selected().id;String active=new SshProfileStore(context).activeId();
        if(temporaryProfile!=null){inst.runOnMainSync(()->SshSessionService.requestConnect(context,profile));await(()->service.selected()!=null&&profile.equals(service.selected().profile())&&service.connected());}else await(service::connected);
        draftMode();SshSessionService.Tab tab=service.selected();assertTrue(tab.attachments.isEmpty());ArrayList<String> paths=new ArrayList<>();
        try{
            for(String fixture:new String[]{"photo","video"}){
                IntentFilter filter=new IntentFilter(Build.VERSION.SDK_INT>=33?android.provider.MediaStore.ACTION_PICK_IMAGES:Intent.ACTION_OPEN_DOCUMENT);
                if(Build.VERSION.SDK_INT<33){filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("*/*");}
                Instrumentation.ActivityMonitor picker=inst.addMonitor(filter,new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(AttachmentTestProvider.uri(fixture))),true);
                try{inst.runOnMainSync(()->{try{Method method=MainActivity.class.getDeclaredMethod("chooseAttachmentSource",int.class);method.setAccessible(true);method.invoke(activity,1);}catch(Exception error){throw new AssertionError(error);}});await(()->!tab.attachments.isEmpty()&&!tab.attachments.get(tab.attachments.size()-1).busy);assertTrue(picker.getHits()>0);
                    inst.runOnMainSync(()->{AttachmentTransfer transfer=tab.attachments.get(tab.attachments.size()-1);assertNotNull(transfer.diagnostic,transfer.path);paths.add(transfer.path);assertNotNull("Media thumbnail missing",transfer.thumbnail);assertTrue(transfer.mime.startsWith(fixture.equals("photo")?"image/":"video/"));assertEquals("",((EditText)field(activity,"draft")).getText().toString());});
                }finally{inst.removeMonitor(picker);}
            }
            capture("staged-media");
            // Camera result uses a private output URI instead of trusting a returned arbitrary file.
            if(context.checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED){
                Instrumentation.ActivityMonitor camera=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){
                    if(!android.provider.MediaStore.ACTION_IMAGE_CAPTURE.equals(intent.getAction()))return null;
                    android.net.Uri output=intent.getParcelableExtra(android.provider.MediaStore.EXTRA_OUTPUT);assertNotNull(output);assertNotNull(intent.getClipData());assertTrue((intent.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0);
                    try(OutputStream out=context.getContentResolver().openOutputStream(output)){android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(120,80,android.graphics.Bitmap.Config.ARGB_8888);image.eraseColor(android.graphics.Color.BLUE);image.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,out);image.recycle();}catch(IOException error){throw new AssertionError(error);}
                    return new Instrumentation.ActivityResult(Activity.RESULT_OK,null);
                }};inst.addMonitor(camera);
                try{inst.runOnMainSync(()->{try{Method method=MainActivity.class.getDeclaredMethod("chooseAttachmentSource",int.class);method.setAccessible(true);method.invoke(activity,0);}catch(Exception error){throw new AssertionError(error);}});
                    await(()->tab.attachments.size()==3&&!tab.attachments.get(2).busy);inst.runOnMainSync(()->{AttachmentTransfer transfer=tab.attachments.get(2);assertNotNull(transfer.diagnostic,transfer.path);paths.add(transfer.path);assertNotNull(transfer.thumbnail);assertFalse(AttachmentCameraProviderExists(transfer.source));});
                }finally{inst.removeMonitor(camera);}
            }
            inst.runOnMainSync(()->{assertEquals(paths.size(),((LinearLayout)field(activity,"attachmentCards")).getChildCount());AttachmentTransfer first=tab.attachments.get(0);View remove=find((View)field(activity,"attachmentCards"),AppLanguage.tr(R.string.attachment_remove,first.name));assertNotNull(remove);remove.performClick();assertFalse(tab.attachments.contains(first));assertEquals(paths.size()-1,tab.attachments.size());});
        }finally{inst.runOnMainSync(()->{service.cancelAttachment(tab.id);if(temporaryProfile!=null)service.closeProfile(temporaryProfile);if(previous!=null)service.select(previous);context.getSharedPreferences("ssh_profiles",0).edit().putString("active",active).commit();});for(String path:paths)cleanup(profile,path);}
    }
    boolean AttachmentCameraProviderExists(android.net.Uri uri){try{return AttachmentCameraProvider.file(context,uri).exists();}catch(IOException error){throw new AssertionError(error);}}

}
