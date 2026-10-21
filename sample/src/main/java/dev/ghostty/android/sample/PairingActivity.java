package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import com.journeyapps.barcodescanner.*;
import java.util.*;

/** Camera-only QR decoding. A scan requires explicit host confirmation before installing a key. */
public final class PairingActivity extends ScreenActivity {
    private int networkChoice;
    private BarcodeView scanner;private PairingScanOverlay overlay;
    private boolean busy,active;private TextView status;private ProgressBar progress;private long lastInvalid;
    private SshConnectionSetup verify;private SshProfileStore.Draft paired;
    static final String COMMAND="curl -fsSL https://raw.githubusercontent.com/JackCaow/Shello/main/companion/pair.sh | bash";
    @Override int contentWidthDp(){return 1040;}
    @Override public void onCreate(Bundle state){super.onCreate(state);heading.setText(tr(R.string.qr_pairing));scroll.setVerticalScrollBarEnabled(false);
        TextView title=SampleUi.text(this,tr(R.string.connect_your_computer),24,SampleUi.TEXT);body.addView(title);
        TextView hint=SampleUi.text(this,tr(R.string.run_the_pairing_command_then_place_the_qr_code_in_the_frame),14,SampleUi.MUTED);hint.setLineSpacing(dp(3),1);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=dp(8);hp.bottomMargin=dp(20);body.addView(hint,hp);networkChoice=state==null?0:state.getInt("pairNetwork",0);
        AdaptiveLayout.Columns columns=new AdaptiveLayout.Columns(this,600,24);body.addView(columns,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout scanColumn=column(),instructions=column();columns.addView(scanColumn);columns.addView(instructions);
        scanner=new BarcodeView(this);scanner.setUseTextureView(true);scanner.setFramingRectSize(new Size(dp(208),dp(208)));
        scanner.setDecoderFactory(new DefaultDecoderFactory(Collections.singletonList(com.google.zxing.BarcodeFormat.QR_CODE)));
        FrameLayout camera=new FrameLayout(this);camera.setBackground(SampleUi.surface(this,SampleUi.FIELD,24));camera.setClipToOutline(true);
        camera.addView(scanner,new FrameLayout.LayoutParams(-1,-1));overlay=new PairingScanOverlay(this,scanner);camera.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        scanColumn.addView(camera,new LinearLayout.LayoutParams(-1,dp(256)));
        LinearLayout feedback=row();feedback.setGravity(Gravity.CENTER);feedback.setPadding(dp(8),dp(14),dp(8),dp(14));feedback.setMinimumHeight(dp(56));
        progress=new ProgressBar(this);progress.setIndeterminateTintList(ColorStateList.valueOf(SampleUi.GREEN));progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(dp(18),dp(18));pp.rightMargin=dp(10);feedback.addView(progress,pp);
        status=SampleUi.text(this,tr(R.string.opening_camera),13,SampleUi.MUTED);status.setGravity(Gravity.CENTER);status.setLineSpacing(dp(3),1);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);feedback.addView(status,new LinearLayout.LayoutParams(0,-2,1));scanColumn.addView(feedback);
        LinearLayout command=column();command.setPadding(dp(16),dp(16),dp(16),dp(16));command.setBackground(SampleUi.surface(this,SampleUi.FIELD,18));
        LinearLayout commandHeader=row();commandHeader.addView(SampleUi.text(this,"macOS / Linux",15,SampleUi.TEXT),new LinearLayout.LayoutParams(0,-2,1));
        TextView copyButton=SampleUi.text(this,tr(R.string.copy_command),14,SampleUi.GREEN);copyButton.setGravity(Gravity.CENTER);copyButton.setPadding(dp(12),0,dp(12),0);copyButton.setMinHeight(dp(48));copyButton.setBackground(SampleUi.touch(this,0x1200d86c,12));copyButton.setFocusable(true);copyButton.setContentDescription(tr(R.string.copy_pairing_command));
        copyButton.setOnClickListener(v->{clipboard(tr(R.string.shello_pairing_command),pairCommand());copyButton.setText(tr(R.string.copied));if(restoreCopy!=null)copyButton.removeCallbacks(restoreCopy);restoreCopy=()->copyButton.setText(tr(R.string.copy_command));copyButton.postDelayed(restoreCopy,1800);});commandHeader.addView(copyButton);command.addView(commandHeader);
        TextView code=SampleUi.text(this,pairCommand(),12,SampleUi.MUTED);code.setTypeface(Typeface.MONOSPACE);code.setLineSpacing(dp(4),1);code.setTextIsSelectable(true);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=dp(12);command.addView(code,cp);
        RadioGroup networks=new RadioGroup(this);networks.setOrientation(RadioGroup.HORIZONTAL);networks.setBackground(SampleUi.surface(this,SampleUi.PANEL,16));
        String[] choices={tr(R.string.pair_network_auto),tr(R.string.pair_network_lan),"Tailscale"};
        for(int i=0;i<choices.length;i++){RadioButton option=SampleUi.segment(this,choices[i],View.generateViewId());networks.addView(option,new RadioGroup.LayoutParams(0,-2,1));if(i==networkChoice)networks.check(option.getId());}
        networks.setOnCheckedChangeListener((group,id)->{networkChoice=group.indexOfChild(group.findViewById(id));code.setText(pairCommand());});
        command.addView(networks,new LinearLayout.LayoutParams(-1,-2));
        TextView networkHint=SampleUi.text(this,tr(R.string.pair_network_hint),13,SampleUi.MUTED);networkHint.setPadding(0,dp(12),0,0);command.addView(networkHint);
        LinearLayout.LayoutParams commandParams=new LinearLayout.LayoutParams(-1,-2);commandParams.topMargin=dp(4);commandParams.bottomMargin=dp(16);instructions.addView(command,commandParams);
        spacedItem(tr(R.string.scan_from_image),tr(R.string.choose_a_saved_pairing_qr_code),"qr",()->{if(busy)return;Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,95);});
        spacedItem(tr(R.string.about_pairing),null,"qr",()->SampleUi.confirm(this,tr(R.string.qr_pairing),tr(R.string.enable_ssh_and_install_tmux_on_your_computer_first_your_private_k),null,tr(R.string.close),tr(R.string.got_it),false,null,null));
        // Keep secondary actions with the instructions when the scanner occupies the other column.
        while(body.getChildCount()>3){View action=body.getChildAt(3);body.removeView(action);instructions.addView(action);}
        scanner.decodeContinuous(new BarcodeCallback(){public void barcodeResult(BarcodeResult result){scanned(result.getText());}});
        scanner.addStateListener(new CameraPreview.StateListener(){
            public void previewSized(){overlay.invalidate();}
            public void previewStarted(){if(active&&!busy){overlay.setScanning(true);if(status.getText().toString().equals(tr(R.string.opening_camera)))feedback(tr(R.string.point_at_a_qr_code_to_scan),false,false);}}
            public void previewStopped(){overlay.setScanning(false);}
            public void cameraClosed(){}
            public void cameraError(Exception error){overlay.setScanning(false);feedback(tr(R.string.camera_unavailable_reopen_this_page_or_scan_from_an_image),false,true);}
        });
    }
    private String pairCommand(){return networkChoice==0?COMMAND:COMMAND.replace("| bash","| bash -s -- --network "+(networkChoice==1?"lan":"tailscale"));}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("pairNetwork",networkChoice);super.onSaveInstanceState(state);}
    private Runnable restoreCopy;
    private void spacedItem(String title,String detail,String icon,Runnable action){View v=item(title,detail,icon,action);LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.bottomMargin=dp(12);v.setLayoutParams(p);}
    private void feedback(String message,boolean loading,boolean error){status.setText(message);status.setTextColor(error?SampleUi.DANGER:SampleUi.MUTED);progress.setVisibility(loading?View.VISIBLE:View.GONE);}
    @Override protected void onResume(){super.onResume();active=true;if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){if(!busy){feedback(tr(R.string.opening_camera),false,false);scanner.resume();}}else requestPermissions(new String[]{Manifest.permission.CAMERA},94);}
    @Override protected void onPause(){active=false;overlay.setScanning(false);scanner.pause();super.onPause();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==94&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED&&active&&!busy)scanner.resume();else if(code==94)feedback(tr(R.string.camera_access_is_off_scan_from_an_image_or_allow_camera_access_in),false,true);}
    void scanned(String text){if(busy||isFinishing()||isDestroyed())return;PairingClient.Ticket ticket;try{ticket=new PairingClient.Ticket(text);}catch(Exception invalid){long now=SystemClock.elapsedRealtime();if(now-lastInvalid>2000){lastInvalid=now;feedback(tr(R.string.invalid_or_expired_qr_code_generate_a_new_pairing_code),false,true);}return;}
        busy=true;overlay.setScanning(false);overlay.setRecognized(true);scanner.pause();feedback(tr(R.string.qr_code_recognized_confirm_the_computer),false,false);overlay.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        SampleUi.confirm(this,tr(R.string.pair_with_this_computer),tr(R.string.pair_confirmation_body,ticket.host),null,tr(R.string.cancel),tr(R.string.pair),false,()->reset(tr(R.string.cancelled_ready_to_scan_again)),()->start(ticket));
    }
    private void start(PairingClient.Ticket ticket){feedback(tr(R.string.pairing_securely),true,false);new Thread(()->{try{SshProfileStore.Draft draft=PairingClient.pair(ticket);runOnUiThread(()->{if(isDestroyed())return;paired=draft;verify();});}catch(Exception e){runOnUiThread(()->reset(tr(R.string.pairing_failed_check_your_network_qr_code_expiry_and_computer_clo)));}},"ssh-pair").start();}
    private void verify(){feedback(tr(R.string.public_key_installed_verifying_ssh_and_tmux),true,false);verify=new SshConnectionSetup();verify.start(paired,new SshConnectionSetup.Listener(){
        public void trustedHostNeeded(String fingerprint,byte[] known){reset(tr(R.string.ssh_fingerprint_differs_from_the_pairing_key_connection_stopped));}
        public void failed(String message){busy=false;feedback(message,false,true);item(tr(R.string.retry_ssh_verification),null,"history",()->{if(!busy){busy=true;verify();}});}
        public void verified(){new Thread(()->{try{String id=new SshProfileStore(PairingActivity.this).save(paired);runOnUiThread(()->{setResult(RESULT_OK,new Intent().putExtra("profile",id));finish();});}catch(Exception error){runOnUiThread(()->reset(tr(R.string.connection_verified_but_saving_failed_try_again)));}},"pair-save").start();}
    });}
    private void reset(String message){if(isDestroyed())return;busy=false;overlay.setRecognized(false);feedback(message,false,false);if(active&&checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)scanner.resume();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=95||result!=RESULT_OK||data==null||busy)return;
        new Thread(()->{try(java.io.InputStream in=getContentResolver().openInputStream(data.getData())){
            byte[] bytes=PairingClient.read(in,8*1024*1024);android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);options.inSampleSize=Math.max(1,Math.max(options.outWidth,options.outHeight)/1600);options.inJustDecodeBounds=false;android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(bitmap==null)throw new java.io.IOException();
            int width=bitmap.getWidth(),height=bitmap.getHeight();int[] pixels=new int[width*height];bitmap.getPixels(pixels,0,width,0,0,width,height);bitmap.recycle();String qr=new com.google.zxing.qrcode.QRCodeReader().decode(new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(width,height,pixels)))).getText();runOnUiThread(()->scanned(qr));
        }catch(Exception error){runOnUiThread(()->reset(tr(R.string.no_valid_qr_code_found_in_the_image)));}},"pair-image").start();
    }
    @Override protected void onDestroy(){if(verify!=null)verify.close();super.onDestroy();}
}
