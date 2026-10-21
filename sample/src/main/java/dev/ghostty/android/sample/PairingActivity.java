package dev.ghostty.android.sample;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.journeyapps.barcodescanner.*;
import java.util.*;

/** Camera-only QR decoding. A scan requires explicit host confirmation before installing a key. */
public final class PairingActivity extends ScreenActivity {
    private DecoratedBarcodeView scanner;private boolean busy,active;private TextView status;
    private SshConnectionSetup verify;private SshProfileStore.Draft paired;
    static final String COMMAND="python3 -m venv ~/.local/share/ghostty-pair\n~/.local/share/ghostty-pair/bin/pip install 'qrcode==8.2'\ncurl -fL https://raw.githubusercontent.com/JackCaow/ghostty-android/main/companion/pair.py -o ~/.local/share/ghostty-pair/pair.py\n~/.local/share/ghostty-pair/bin/python ~/.local/share/ghostty-pair/pair.py";
    @Override public void onCreate(Bundle state){super.onCreate(state);heading.setText("Easy Pair");
        scanner=new DecoratedBarcodeView(this);scanner.setStatusText("");scanner.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(Collections.singletonList(com.google.zxing.BarcodeFormat.QR_CODE)));
        body.addView(scanner,new LinearLayout.LayoutParams(-1,dp(340)));status=copy("在电脑上运行下面的命令，然后扫描二维码。");
        item("macOS / Linux · 复制命令",null,"paste",()->clipboard("Ghostty 配对命令",COMMAND));TextView code=copy(COMMAND);code.setTypeface(android.graphics.Typeface.MONOSPACE);code.setTextSize(12);code.setTextIsSelectable(true);
        item("会发生什么",null,"qr",()->SampleUi.confirm(this,"扫码配对","电脑需要先开启 SSH 并安装 tmux。手机生成私钥并保存在本机加密存储；电脑只安装公钥。二维码固定 HTTPS 证书和一次性令牌，3 分钟后失效。",null,"关闭","知道了",false,null,null));
        item("从图片扫描","可选择电脑上保存的二维码图片","qr",()->{Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,95);});
        scanner.decodeContinuous(new BarcodeCallback(){public void barcodeResult(BarcodeResult result){scanned(result.getText());}});
    }
    @Override protected void onResume(){super.onResume();active=true;if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){if(!busy)scanner.resume();}else requestPermissions(new String[]{Manifest.permission.CAMERA},94);}
    @Override protected void onPause(){active=false;scanner.pause();super.onPause();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==94&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED&&active&&!busy)scanner.resume();else if(code==94)status.setText("相机权限未开启；可从图片扫描，或在系统设置中允许相机权限。");}
    void scanned(String text){if(busy||isFinishing())return;PairingClient.Ticket ticket;try{ticket=new PairingClient.Ticket(text);}catch(Exception invalid){status.setText("这不是有效的 Ghostty 配对码，或二维码已过期。");return;}
        busy=true;scanner.pause();SampleUi.confirm(this,"配对这台电脑？",ticket.host+"\n确认这是你电脑上刚生成的二维码。",null,"取消","配对",false,()->reset("已取消"),()->start(ticket));
    }
    private void start(PairingClient.Ticket ticket){status.setText("正在安全配对…");new Thread(()->{try{SshProfileStore.Draft draft=PairingClient.pair(ticket);runOnUiThread(()->{if(isDestroyed())return;paired=draft;verify();});}catch(Exception e){runOnUiThread(()->reset("配对失败，请检查网络、二维码有效期与电脑时间。"));}},"ssh-pair").start();}
    private void verify(){status.setText("公钥已安装，正在验证 SSH 和 tmux…");verify=new SshConnectionSetup();verify.start(paired,new SshConnectionSetup.Listener(){
        public void trustedHostNeeded(String fingerprint,byte[] known){reset("SSH 指纹与二维码返回的密钥不一致，已停止连接。");}
        public void failed(String message){busy=false;status.setText(message);item("重试验证 SSH",null,"history",()->{if(!busy){busy=true;verify();}});}
        public void verified(){new Thread(()->{try{String id=new SshProfileStore(PairingActivity.this).save(paired);runOnUiThread(()->{setResult(RESULT_OK,new Intent().putExtra("profile",id));finish();});}catch(Exception error){runOnUiThread(()->reset("连接已验证，但保存失败，请重试。"));}},"pair-save").start();}
    });}
    private void reset(String message){if(isDestroyed())return;busy=false;status.setText(message);if(active&&checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)scanner.resume();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=95||result!=RESULT_OK||data==null||busy)return;
        new Thread(()->{try(java.io.InputStream in=getContentResolver().openInputStream(data.getData())){
            byte[] bytes=PairingClient.read(in,8*1024*1024);android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);options.inSampleSize=Math.max(1,Math.max(options.outWidth,options.outHeight)/1600);options.inJustDecodeBounds=false;android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(bitmap==null)throw new java.io.IOException();
            int width=bitmap.getWidth(),height=bitmap.getHeight();int[] pixels=new int[width*height];bitmap.getPixels(pixels,0,width,0,0,width,height);bitmap.recycle();String qr=new com.google.zxing.qrcode.QRCodeReader().decode(new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(width,height,pixels)))).getText();runOnUiThread(()->scanned(qr));
        }catch(Exception error){runOnUiThread(()->reset("图片中未找到有效二维码"));}},"pair-image").start();
    }
    @Override protected void onDestroy(){if(verify!=null)verify.close();super.onDestroy();}
}
