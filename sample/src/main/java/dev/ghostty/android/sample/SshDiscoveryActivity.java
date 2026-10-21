package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Local discovery and user-driven SSH setup; no credentials are passed through Intents. */
public final class SshDiscoveryActivity extends Activity {
    private static final int TEXT=0xffe6e6e8,MUTED=0xff96999f,GREEN=SampleUi.GREEN,CARD=0xff111315;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<LanSshDiscovery.Host> hosts=new ArrayList<>();
    private EditText name,host,port,user,password,passphrase;
    private TextView keyStatus,connectButton,connectionStatus,saveTop;
    private LinearLayout passwordFields,keyFields;
    private android.widget.RadioGroup authChoices;
    private byte[] privateKey=new byte[0],trustedKey=new byte[0];
    private String trustedEndpoint;
    private SshConnectionSetup setup;
    private Dialog trustDialog,discardDialog;
    private boolean busy;
    private String editingId;
    private SshProfileStore.Draft original;
    private CheckBox tmux;
    private static final int IMPORT_KEY=72;
    private TextView badge,status,listingStatus;
    private LinearLayout list;
    private Dialog picker;
    private LanSshDiscovery scanner;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback networkCallback;
    private String networkIdentity,scanStatus="";
    private boolean scanning,visible;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        editingId=getIntent().getStringExtra("editProfile");
        if(editingId!=null)try{original=new SshProfileStore(this).read(new SshProfileStore(this).directory(editingId));}
            catch(Exception error){Toast.makeText(this,"无法读取连接，请返回重试",Toast.LENGTH_LONG).show();finish();return;}
        getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                |WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root=column();root.setBackgroundColor(Color.BLACK);setContentView(root);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars());
            android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());
            root.setPadding(i.left,i.top,i.right,Math.max(i.bottom,ime.bottom));return WindowInsets.CONSUMED;
        });
        LinearLayout header=row();header.setPadding(dp(16),dp(12),dp(16),dp(12));root.addView(header);
        TextView close=SampleUi.header(this,"close",original==null?"关闭新建连接":"关闭编辑连接",TEXT,this::cancelEdit);header.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView title=text(original==null?"新建连接":"编辑连接",24,TEXT);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        if(original!=null){saveTop=SampleUi.header(this,"check","确认修改连接",GREEN,this::verifyConnection);header.addView(saveTop,new LinearLayout.LayoutParams(dp(48),dp(48)));}
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout form=column();form.setPadding(dp(24),dp(20),dp(24),dp(24));scroll.addView(form);
        form.addView(label("名称"));
        LinearLayout nameRow=row();nameRow.setPadding(dp(8),0,dp(8),0);nameRow.setBackground(round(CARD,20));
        form.addView(nameRow,new LinearLayout.LayoutParams(-1,dp(68)));
        name=field("我的 Mac","连接名称");name.setBackgroundColor(Color.TRANSPARENT);
        nameRow.addView(name,new LinearLayout.LayoutParams(0,-1,1));
        badge=button("正在发现…","局域网 SSH 自动发现",this::showPicker);badge.setTextSize(13);badge.setTextColor(GREEN);
        badge.setPadding(dp(12),0,dp(12),0);badge.setBackground(round(0xff0d2412,18));
        nameRow.addView(badge,new LinearLayout.LayoutParams(-2,dp(44)));
        LinearLayout addressRow=row();addressRow.setGravity(Gravity.TOP);margin(form,addressRow,24);
        LinearLayout hostColumn=column(),portColumn=column();addressRow.addView(hostColumn,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams portParams=new LinearLayout.LayoutParams(dp(84),-2);portParams.leftMargin=dp(12);addressRow.addView(portColumn,portParams);
        hostColumn.addView(label("主机"));host=field("192.168.1.100","SSH 主机地址");hostColumn.addView(host,new LinearLayout.LayoutParams(-1,dp(60)));
        portColumn.addView(label("端口"));port=field("22","SSH 端口");port.setInputType(InputType.TYPE_CLASS_NUMBER);port.setText("22");portColumn.addView(port,new LinearLayout.LayoutParams(-1,dp(60)));
        status=text("",13,MUTED);margin(form,status,20);
        TextView refresh=button("重新扫描","重新扫描局域网 SSH",()->rescan(true));refresh.setTextColor(GREEN);margin(form,refresh,12);
        margin(form,label("用户名"),20);user=field("服务器上的用户名","SSH 用户名");form.addView(user,new LinearLayout.LayoutParams(-1,dp(60)));
        margin(form,label("认证方式"),24);
        authChoices=new RadioGroup(this);authChoices.setOrientation(RadioGroup.HORIZONTAL);
        authChoices.setPadding(dp(4),dp(4),dp(4),dp(4));authChoices.setBackground(round(CARD,18));
        RadioButton passwordChoice=SampleUi.segment(this,"密码",R.id.auth_password),keyChoice=SampleUi.segment(this,"私钥",R.id.auth_key);
        authChoices.addView(passwordChoice,new RadioGroup.LayoutParams(0,-2,1));authChoices.addView(keyChoice,new RadioGroup.LayoutParams(0,-2,1));
        form.addView(authChoices,new LinearLayout.LayoutParams(-1,-2));authChoices.check(R.id.auth_password);
        passwordFields=column();margin(form,passwordFields,12);password=secretField("登录密码","SSH 登录密码");passwordFields.addView(password,new LinearLayout.LayoutParams(-1,dp(60)));
        keyFields=column();keyFields.setVisibility(View.GONE);margin(form,keyFields,12);
        TextView importKey=button("选择私钥文件","导入 SSH 私钥",this::importKey);importKey.setTextColor(GREEN);importKey.setBackground(SampleUi.touch(this,0xff14271e,18));keyFields.addView(importKey,new LinearLayout.LayoutParams(-1,dp(56)));
        keyStatus=text("支持 OpenSSH / PEM 私钥",13,MUTED);margin(keyFields,keyStatus,12);
        passphrase=secretField("私钥口令（没有可留空）","SSH 私钥口令");margin(keyFields,passphrase,12);passphrase.setMinHeight(dp(60));
        authChoices.setOnCheckedChangeListener((group,id)->{passwordFields.setVisibility(id==R.id.auth_password?View.VISIBLE:View.GONE);keyFields.setVisibility(id==R.id.auth_key?View.VISIBLE:View.GONE);});
        tmux=new SampleUi.Toggle(this);tmux.setText("保持远端会话");tmux.setTextColor(TEXT);tmux.setContentDescription("使用 tmux 保持远端会话");margin(form,tmux,20);
        TextView tmuxHint=text("服务器需安装 tmux；开启后可在断线重连时回到原来的 shell 和编辑器。",13,MUTED);margin(form,tmuxHint,4);
        connectionStatus=text("首次连接需核对服务器指纹。认证成功后保存连接，登录凭据在本机加密保存。",13,MUTED);margin(form,connectionStatus,24);
        connectButton=button(original==null?"保存并连接":"保存修改并连接","保存并连接 SSH",this::verifyConnection);connectButton.setTextColor(Color.BLACK);connectButton.setBackground(SampleUi.touch(this,GREEN,20));connectButton.setMinHeight(dp(56));margin(form,connectButton,20);
        if(original!=null){
            name.setText(original.name);host.setText(original.host);port.setText(Integer.toString(original.port));user.setText(original.user);
            authChoices.check("key".equals(original.auth)?R.id.auth_key:R.id.auth_password);tmux.setChecked(original.tmux);
            privateKey=original.privateKey.clone();
            if(privateKey.length>0)keyStatus.setText("已保存私钥 · 选择文件可替换");
            if(!original.password.isEmpty())password.setHint("已保存密码（留空保留）");
            if(!original.passphrase.isEmpty())passphrase.setHint("已保存口令（留空保留）");
            connectionStatus.setText("验证成功后保存修改并重新连接；取消或验证失败会保留原配置。");
        }
        if(state!=null){name.setText(state.getString("name",""));host.setText(state.getString("host",""));port.setText(state.getString("port","22"));user.setText(state.getString("user",""));authChoices.check(state.getInt("auth",R.id.auth_password));tmux.setChecked(state.getBoolean("tmux"));}
        root.setFocusableInTouchMode(true);root.requestFocus();
        cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
    }
    @Override protected void onStart(){
        super.onStart();visible=true;
        networkCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){scheduleNetworkCheck();}
            @Override public void onLost(Network network){scheduleNetworkCheck();}
            @Override public void onLinkPropertiesChanged(Network network,LinkProperties properties){scheduleNetworkCheck();}
        };
        try{cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),networkCallback,main);}
        catch(RuntimeException ignored){networkCallback=null;}
        rescan(true);
    }
    private final Runnable networkCheck=()->rescan(false);
    private void scheduleNetworkCheck(){main.removeCallbacks(networkCheck);main.postDelayed(networkCheck,250);}
    private void rescan(boolean force){
        if(!visible)return;
        LanSshDiscovery.Scope scope;
        try{scope=LanSshDiscovery.wifiScope(cm);}catch(SecurityException e){stopScan();hosts.clear();networkIdentity=null;render(false,"系统不允许访问局域网，请检查权限");return;}
        String identity=scope==null?null:scope.identity();
        if(!force&&Objects.equals(identity,networkIdentity))return;
        stopScan();networkIdentity=identity;hosts.clear();
        if(scope==null){render(false,"请连接 Wi-Fi 后重试（需要 IPv4 局域网）");return;}
        scanner=new LanSshDiscovery(this,scope,(results,running,message)->{
            hosts.clear();hosts.addAll(results);render(running,message);
        });
        scanner.start();
    }
    private void render(boolean running,String message){
        scanning=running;scanStatus=message;
        badge.setText(hosts.isEmpty()?(running?"正在发现…":"发现服务器"):"找到 "+hosts.size()+" 台服务器");
        status.setText(message+(running?"":"\n"+(hosts.isEmpty()?"未发现可用的 SSH 服务器。请确认电脑已开启 SSH。":"仅显示已返回 SSH 协议标识的 22 端口。")));
        renderList();
    }
    private void showPicker(){
        if(picker!=null&&picker.isShowing())return;
        picker=new Dialog(this);picker.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content=column();content.setPadding(dp(20),dp(10),dp(20),dp(20));content.setBackground(round(0xff161719,26));
        View grip=new View(this);grip.setBackground(round(0xff575b60,3));
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(dp(44),dp(4));gp.gravity=Gravity.CENTER_HORIZONTAL;gp.bottomMargin=dp(20);content.addView(grip,gp);
        content.setContentDescription("本地 SSH 服务器列表");
        LinearLayout heading=row();content.addView(heading);
        heading.addView(text("本地 SSH 服务器",22,TEXT),new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(SampleUi.header(this,"close","关闭服务器列表",TEXT,()->picker.dismiss()),new LinearLayout.LayoutParams(dp(44),dp(44)));
        listingStatus=text("",12,MUTED);margin(content,listingStatus,8);
        ScrollView scroll=new ScrollView(this);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,0,1);sp.topMargin=dp(12);content.addView(scroll,sp);
        list=column();scroll.addView(list);
        TextView retry=button("重新扫描","重新扫描服务器列表",()->rescan(true));retry.setTextColor(GREEN);content.addView(retry,new LinearLayout.LayoutParams(-1,dp(48)));
        picker.setContentView(content);Window window=picker.getWindow();
        if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams p=window.getAttributes();p.gravity=Gravity.BOTTOM;p.dimAmount=.35f;window.setAttributes(p);}
        picker.setOnDismissListener(d->{list=null;listingStatus=null;picker=null;});picker.show();
        if(window!=null)window.setLayout(-1,(int)(getResources().getDisplayMetrics().heightPixels*.58f));
        renderList();
    }
    private void renderList(){
        if(list==null)return;
        listingStatus.setText(scanStatus);list.removeAllViews();
        if(hosts.isEmpty()){
            TextView empty=text(scanning?"正在查找局域网中的 SSH 服务器…":"未找到服务器，可重新扫描或手动填写地址",15,MUTED);
            empty.setPadding(dp(12),dp(24),dp(12),dp(24));list.addView(empty);return;
        }
        for(LanSshDiscovery.Host item:hosts){
            LinearLayout card=row();card.setPadding(dp(18),dp(16),dp(18),dp(16));card.setBackground(SampleUi.touch(this,CARD,20));
            ImageView icon=SampleUi.icon(this,"server",MUTED);icon.setPadding(0,0,dp(14),0);card.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(48)));
            LinearLayout labels=column();card.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            TextView title=text(item.name,19,TEXT);title.setMaxLines(2);labels.addView(title);
            TextView address=text(item.address+":22",15,MUTED);address.setTypeface(Typeface.MONOSPACE);labels.addView(address);
            ImageView arrow=SampleUi.icon(this,"right",MUTED);card.addView(arrow,new LinearLayout.LayoutParams(dp(24),dp(24)));
            card.setContentDescription("选择 SSH 服务器 "+item.name+" "+item.address+":22");
            card.setFocusable(true);card.setOnClickListener(v->{name.setText(item.name);host.setText(item.address);port.setText("22");picker.dismiss();});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);list.addView(card,p);
        }
    }
    private void stopScan(){if(scanner!=null){scanner.close();scanner=null;}}
    @Override protected void onStop(){
        visible=false;main.removeCallbacksAndMessages(null);stopScan();
        if(busy){if(setup!=null)setup.close();if(trustDialog!=null)trustDialog.dismiss();setBusy(false);connectionStatus.setText("连接验证已暂停，请重新连接");}
        if(networkCallback!=null){try{cm.unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}networkCallback=null;}
        if(picker!=null)picker.dismiss();super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        state.putBoolean("tmux",tmux.isChecked());state.putString("user",user.getText().toString());state.putInt("auth",authChoices.getCheckedRadioButtonId());
        state.putString("name",name.getText().toString());state.putString("host",host.getText().toString());state.putString("port",port.getText().toString());super.onSaveInstanceState(state);
    }
    private EditText secretField(String hint,String description){
        EditText e=field(hint,description);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);return e;
    }
    private void importKey(){
        if(busy)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try{startActivityForResult(intent,IMPORT_KEY);}catch(ActivityNotFoundException e){connectionStatus.setText("系统未提供文件选择器");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=IMPORT_KEY||result!=RESULT_OK||data==null||data.getData()==null)return;
        try(java.io.InputStream in=getContentResolver().openInputStream(data.getData());java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            byte[] bytes=new byte[4096];for(int count;(count=in.read(bytes))!=-1;){if(out.size()+count>1024*1024)throw new java.io.IOException();out.write(bytes,0,count);}
            byte[] loaded=out.toByteArray();String header=new String(loaded,java.nio.charset.StandardCharsets.US_ASCII);
            if(!header.contains("PRIVATE KEY-----"))throw new java.io.IOException();
            java.util.Arrays.fill(privateKey,(byte)0);privateKey=loaded;passphrase.setText("");passphrase.setHint("私钥口令（没有可留空）");keyStatus.setText("已载入私钥 · "+privateKey.length+" 字节");
        }catch(Exception error){connectionStatus.setText("无法读取私钥，请选择不超过 1 MB 的 OpenSSH / PEM 私钥文件");}
    }
    private void verifyConnection(){
        if(busy)return;
        SshProfileStore.Draft d=new SshProfileStore.Draft();d.host=host.getText().toString().trim();d.name=name.getText().toString().trim();
        d.tmux=tmux.isChecked();d.user=user.getText().toString().trim();d.auth=authChoices.getCheckedRadioButtonId()==R.id.auth_password?"password":"key";
        d.password=d.auth.equals("password")?password.getText().toString():"";d.passphrase=d.auth.equals("key")?passphrase.getText().toString():"";
        d.privateKey=d.auth.equals("key")?privateKey.clone():new byte[0];
        try{d.port=Integer.parseInt(port.getText().toString());}catch(NumberFormatException e){d.port=0;}
        if(original!=null){
            if(d.auth.equals("password")&&original.auth.equals("password")&&d.password.isEmpty())d.password=original.password;
            if(d.auth.equals("key")&&d.passphrase.isEmpty()&&java.util.Arrays.equals(d.privateKey,original.privateKey))d.passphrase=original.passphrase;
            if(d.tmux&&original.tmux&&original.tmuxSession!=null&&d.host.equals(original.host)&&d.port==original.port&&d.user.equals(original.user))d.tmuxSession=original.tmuxSession;
        }
        String error=d.validate();if(error!=null){connectionStatus.setText(error);return;}
        if(d.name.isEmpty())d.name=d.host;
        if(original!=null&&d.host.equals(original.host)&&d.port==original.port)d.knownHosts=original.knownHosts.clone();
        else if((d.host+":"+d.port).equals(trustedEndpoint))d.knownHosts=trustedKey.clone();
        verify(d);
    }
    private void setBusy(boolean value){busy=value;
        if(saveTop!=null){saveTop.setEnabled(!value);saveTop.setAlpha(value?.5f:1f);}
        for(View field:new View[]{name,host,port,user,password,passphrase,tmux,badge})field.setEnabled(!value);
        for(int i=0;i<authChoices.getChildCount();i++)authChoices.getChildAt(i).setEnabled(!value);
        connectButton.setEnabled(!value);connectButton.setAlpha(value?.5f:1f);connectButton.setText(value?"正在验证连接…":original==null?"保存并连接":"保存修改并连接");}
    private void verify(SshProfileStore.Draft d){
        if(setup!=null)setup.close();setBusy(true);connectionStatus.setText("正在连接 "+d.host+":"+d.port);
        setup=new SshConnectionSetup();setup.start(d,new SshConnectionSetup.Listener(){
            public void trustedHostNeeded(String fingerprint,byte[] knownHosts){
                connectionStatus.setText("等待确认服务器指纹");
                Runnable cancel=()->{setBusy(false);connectionStatus.setText("已取消连接，未保存服务器");};
                trustDialog=SampleUi.confirm(SshDiscoveryActivity.this,"确认服务器指纹",
                        "请与服务器提供的指纹核对。确认后才会发送登录凭据。",d.host+":"+d.port+"\n\n"+fingerprint,
                        "取消","信任并连接",false,cancel,
                        ()->{d.knownHosts=knownHosts;trustedKey=knownHosts;trustedEndpoint=d.host+":"+d.port;verify(d);});
            }
            public void verified(){
                try{
                    SshProfileStore store=new SshProfileStore(SshDiscoveryActivity.this);String id=editingId;
                    if(id==null)id=store.save(d);else store.update(id,d);
                    password.setText("");passphrase.setText("");
                    setResult(RESULT_OK,new Intent().putExtra("profile",id));finish();
                }catch(Exception error){setBusy(false);connectionStatus.setText("连接验证成功，但保存失败，请检查本机存储后重试");}
            }
            public void failed(String message){setBusy(false);connectionStatus.setText(message);}
        });
    }
    private void cancelEdit(){
        if(busy){if(setup!=null)setup.close();if(trustDialog!=null)trustDialog.dismiss();setBusy(false);connectionStatus.setText("已暂停验证，原连接保持不变");}
        boolean changed=original!=null&&(!name.getText().toString().equals(original.name)||!host.getText().toString().equals(original.host)
                ||!port.getText().toString().equals(Integer.toString(original.port))||!user.getText().toString().equals(original.user)
                ||tmux.isChecked()!=original.tmux||(!"key".equals(original.auth))!=(authChoices.getCheckedRadioButtonId()==R.id.auth_password)
                ||password.length()>0||passphrase.length()>0||!java.util.Arrays.equals(privateKey,original.privateKey));
        if(changed){if(discardDialog==null||!discardDialog.isShowing())discardDialog=SampleUi.confirm(this,"放弃修改？","尚未保存的修改将丢失，原连接保持不变。",null,"继续编辑","放弃修改",true,null,this::finish);}
        else finish();
    }
    @Override public void onBackPressed(){cancelEdit();}
    @Override protected void onDestroy(){
        if(discardDialog!=null)discardDialog.dismiss();if(setup!=null)setup.close();if(trustDialog!=null)trustDialog.dismiss();java.util.Arrays.fill(privateKey,(byte)0);if(original!=null)java.util.Arrays.fill(original.privateKey,(byte)0);super.onDestroy();
    }
    private EditText field(String hint,String description){
        EditText e=new EditText(this);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(17);e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);e.setHint(hint);e.setContentDescription(description);
        SampleUi.field(e);return e;
    }
    private TextView label(String value){TextView t=text(value,17,MUTED);t.setPadding(0,0,0,dp(10));return t;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private TextView button(String value,String description,Runnable action){TextView t=text(value,17,TEXT);t.setBackground(SampleUi.touch(this,Color.TRANSPARENT,16));t.setGravity(Gravity.CENTER);t.setMinHeight(dp(48));t.setContentDescription(description);t.setFocusable(true);t.setOnClickListener(v->action.run());return t;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private void margin(LinearLayout parent,View child,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(child,p);}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
}
