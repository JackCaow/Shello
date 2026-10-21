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

/** Moshi-style local SSH picker. Selection is a draft; it never replaces the active SSH profile. */
public final class SshDiscoveryActivity extends Activity {
    private static final int TEXT=0xffe6e6e8,MUTED=0xff96999f,GREEN=0xff00e529,CARD=0xff111315;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<LanSshDiscovery.Host> hosts=new ArrayList<>();
    private EditText name,host,port;
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
        getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                |WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root=column();root.setBackgroundColor(Color.BLACK);setContentView(root);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars());
            root.setPadding(i.left,i.top,i.right,i.bottom);return WindowInsets.CONSUMED;
        });
        LinearLayout header=row();header.setPadding(dp(16),dp(12),dp(16),dp(12));root.addView(header);
        TextView close=button("×","关闭新建连接",this::finish);header.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView title=text("新建连接",24,TEXT);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
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
        TextView scope=text("选择服务器后填入地址；登录连接暂未接入。",14,MUTED);margin(form,scope,28);
        if(state!=null){name.setText(state.getString("name",""));host.setText(state.getString("host",""));port.setText(state.getString("port","22"));}
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
        heading.addView(button("×","关闭服务器列表",()->picker.dismiss()),new LinearLayout.LayoutParams(dp(44),dp(44)));
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
            LinearLayout card=row();card.setPadding(dp(18),dp(16),dp(18),dp(16));card.setBackground(round(CARD,20));
            TextView icon=text("▤",26,MUTED);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);card.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(48)));
            LinearLayout labels=column();card.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            TextView title=text(item.name,19,TEXT);title.setMaxLines(2);labels.addView(title);
            TextView address=text(item.address+":22",15,MUTED);address.setTypeface(Typeface.MONOSPACE);labels.addView(address);
            TextView arrow=text("›",28,MUTED);arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);card.addView(arrow);
            card.setContentDescription("选择 SSH 服务器 "+item.name+" "+item.address+":22");
            card.setFocusable(true);card.setOnClickListener(v->{name.setText(item.name);host.setText(item.address);port.setText("22");picker.dismiss();});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);list.addView(card,p);
        }
    }
    private void stopScan(){if(scanner!=null){scanner.close();scanner=null;}}
    @Override protected void onStop(){
        visible=false;main.removeCallbacksAndMessages(null);stopScan();
        if(networkCallback!=null){try{cm.unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}networkCallback=null;}
        if(picker!=null)picker.dismiss();super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        state.putString("name",name.getText().toString());state.putString("host",host.getText().toString());state.putString("port",port.getText().toString());super.onSaveInstanceState(state);
    }
    private EditText field(String hint,String description){
        EditText e=new EditText(this);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(17);e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);e.setHint(hint);e.setContentDescription(description);
        e.setPadding(dp(16),0,dp(12),0);e.setBackground(round(CARD,18));return e;
    }
    private TextView label(String value){TextView t=text(value,17,MUTED);t.setPadding(0,0,0,dp(10));return t;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private TextView button(String value,String description,Runnable action){TextView t=text(value,20,TEXT);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(48));t.setContentDescription(description);t.setFocusable(true);t.setOnClickListener(v->action.run());return t;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private void margin(LinearLayout parent,View child,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(child,p);}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
}
