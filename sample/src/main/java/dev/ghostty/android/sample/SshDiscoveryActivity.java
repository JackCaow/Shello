package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

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
public final class SshDiscoveryActivity extends LocalizedActivity {
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
            catch(Exception error){Toast.makeText(this,tr(R.string.cannot_read_this_connection_go_back_and_try_again),Toast.LENGTH_LONG).show();finish();return;}
        getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                |WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root=column();root.setBackgroundColor(Color.BLACK);setContentView(root);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());
            root.setPadding(i.left,i.top,i.right,Math.max(i.bottom,ime.bottom));return WindowInsets.CONSUMED;
        });
        SampleUi.NavigationBar header=new SampleUi.NavigationBar(this,original==null?tr(R.string.new_connection):tr(R.string.edit_connection),this::cancelEdit);
        root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        if(original!=null){saveTop=SampleUi.header(this,"check",tr(R.string.save_connection_changes),GREEN,this::verifyConnection);header.addAction(saveTop);}
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout form=column();form.setPadding(dp(24),dp(20),dp(24),dp(24));scroll.addView(AdaptiveLayout.centered(this,form,640,header));
        form.addView(label(tr(R.string.name)));
        LinearLayout nameRow=row();nameRow.setPadding(dp(8),0,dp(8),0);nameRow.setBackground(round(CARD,20));
        form.addView(nameRow,new LinearLayout.LayoutParams(-1,dp(68)));
        name=field(tr(R.string.my_mac),tr(R.string.connection_name));name.setBackgroundColor(Color.TRANSPARENT);
        nameRow.addView(name,new LinearLayout.LayoutParams(0,-1,1));
        badge=button(tr(R.string.discovering),tr(R.string.discover_ssh_servers_on_your_network),this::showPicker);badge.setTextSize(13);badge.setTextColor(GREEN);
        badge.setPadding(dp(12),0,dp(12),0);badge.setBackground(round(0xff0d2412,18));
        nameRow.addView(badge,new LinearLayout.LayoutParams(-2,dp(44)));
        LinearLayout addressRow=row();addressRow.setGravity(Gravity.TOP);margin(form,addressRow,24);
        LinearLayout hostColumn=column(),portColumn=column();addressRow.addView(hostColumn,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams portParams=new LinearLayout.LayoutParams(dp(84),-2);portParams.leftMargin=dp(12);addressRow.addView(portColumn,portParams);
        hostColumn.addView(label(tr(R.string.host)));host=field("192.168.1.100",tr(R.string.ssh_host_address));hostColumn.addView(host,new LinearLayout.LayoutParams(-1,dp(60)));
        portColumn.addView(label(tr(R.string.port)));port=field("22",tr(R.string.ssh_port));port.setInputType(InputType.TYPE_CLASS_NUMBER);port.setText("22");portColumn.addView(port,new LinearLayout.LayoutParams(-1,dp(60)));
        status=text("",13,MUTED);margin(form,status,20);
        TextView refresh=button(tr(R.string.scan_again),tr(R.string.scan_the_network_for_ssh_servers_again),()->rescan(true));refresh.setTextColor(GREEN);margin(form,refresh,12);
        margin(form,label(tr(R.string.username)),20);user=field(tr(R.string.username_on_the_server),tr(R.string.ssh_username));form.addView(user,new LinearLayout.LayoutParams(-1,dp(60)));
        margin(form,label(tr(R.string.authentication)),24);
        authChoices=new RadioGroup(this);authChoices.setOrientation(RadioGroup.HORIZONTAL);
        authChoices.setPadding(dp(4),dp(4),dp(4),dp(4));authChoices.setBackground(round(CARD,18));
        RadioButton passwordChoice=SampleUi.segment(this,tr(R.string.password),R.id.auth_password),keyChoice=SampleUi.segment(this,tr(R.string.private_key),R.id.auth_key);
        authChoices.addView(passwordChoice,new RadioGroup.LayoutParams(0,-2,1));authChoices.addView(keyChoice,new RadioGroup.LayoutParams(0,-2,1));
        form.addView(authChoices,new LinearLayout.LayoutParams(-1,-2));authChoices.check(R.id.auth_password);
        passwordFields=column();margin(form,passwordFields,12);password=secretField(tr(R.string.login_password),tr(R.string.ssh_login_password));passwordFields.addView(password,new LinearLayout.LayoutParams(-1,dp(60)));
        keyFields=column();keyFields.setVisibility(View.GONE);margin(form,keyFields,12);
        TextView importKey=button(tr(R.string.choose_private_key),tr(R.string.import_ssh_private_key),this::importKey);importKey.setTextColor(GREEN);importKey.setBackground(SampleUi.touch(this,0xff14271e,18));keyFields.addView(importKey,new LinearLayout.LayoutParams(-1,dp(56)));
        keyStatus=text(tr(R.string.openssh_pem_private_keys_supported),13,MUTED);margin(keyFields,keyStatus,12);
        passphrase=secretField(tr(R.string.key_passphrase_optional),tr(R.string.ssh_key_passphrase));margin(keyFields,passphrase,12);passphrase.setMinHeight(dp(60));
        authChoices.setOnCheckedChangeListener((group,id)->{passwordFields.setVisibility(id==R.id.auth_password?View.VISIBLE:View.GONE);keyFields.setVisibility(id==R.id.auth_key?View.VISIBLE:View.GONE);});
        tmux=new SampleUi.Toggle(this);tmux.setText(tr(R.string.keep_remote_session));tmux.setTextColor(TEXT);tmux.setContentDescription(tr(R.string.keep_remote_sessions_with_tmux));margin(form,tmux,20);
        TextView tmuxHint=text(tr(R.string.install_tmux_on_the_server_to_return_to_your_shell_and_editor_aft),13,MUTED);margin(form,tmuxHint,4);
        connectionStatus=text(tr(R.string.verify_the_server_fingerprint_on_first_connection_connections_are),13,MUTED);margin(form,connectionStatus,24);
        connectButton=button(original==null?tr(R.string.save_and_connect):tr(R.string.save_changes_and_connect),tr(R.string.save_and_connect_over_ssh),this::verifyConnection);connectButton.setTextColor(Color.BLACK);connectButton.setBackground(SampleUi.touch(this,GREEN,20));connectButton.setMinHeight(dp(56));margin(form,connectButton,20);
        if(original!=null){
            name.setText(original.name);host.setText(original.host);port.setText(Integer.toString(original.port));user.setText(original.user);
            authChoices.check("key".equals(original.auth)?R.id.auth_key:R.id.auth_password);tmux.setChecked(original.tmux);
            privateKey=original.privateKey.clone();
            if(privateKey.length>0)keyStatus.setText(tr(R.string.private_key_saved_choose_a_file_to_replace_it));
            if(!original.password.isEmpty())password.setHint(tr(R.string.password_saved_leave_blank_to_keep));
            if(!original.passphrase.isEmpty())passphrase.setHint(tr(R.string.passphrase_saved_leave_blank_to_keep));
            connectionStatus.setText(tr(R.string.changes_are_saved_after_verification_then_the_connection_reopens));
        }
        if(state!=null){name.setText(state.getString("name",""));host.setText(state.getString("host",""));port.setText(state.getString("port","22"));user.setText(state.getString("user",""));authChoices.check(state.getInt("auth",R.id.auth_password));tmux.setChecked(state.getBoolean("tmux"));}
        RetainedInput retained=(RetainedInput)getLastNonConfigurationInstance();
        if(retained!=null){password.setText(retained.password);passphrase.setText(retained.passphrase);
            privateKey=retained.key.clone();java.util.Arrays.fill(retained.key,(byte)0);
            if(privateKey.length>0)keyStatus.setText(tr(R.string.private_key_bytes,privateKey.length));}
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
        try{scope=LanSshDiscovery.wifiScope(cm);}catch(SecurityException e){stopScan();hosts.clear();networkIdentity=null;render(false,tr(R.string.network_access_was_denied_check_permissions));return;}
        String identity=scope==null?null:scope.identity();
        if(!force&&Objects.equals(identity,networkIdentity))return;
        stopScan();networkIdentity=identity;hosts.clear();
        if(scope==null){render(false,tr(R.string.connect_to_wi_fi_and_try_again_ipv4_lan_required));return;}
        scanner=new LanSshDiscovery(this,scope,(results,running,message)->{
            hosts.clear();hosts.addAll(results);render(running,message);
        });
        scanner.start();
    }
    private void render(boolean running,String message){
        scanning=running;scanStatus=message;
        badge.setText(hosts.isEmpty()?(running?tr(R.string.discovering):tr(R.string.discover_servers)):AppLanguage.quantity(R.plurals.servers_found,hosts.size(),hosts.size()));
        status.setText(message+(running?"":"\n"+(hosts.isEmpty()?tr(R.string.no_ssh_servers_found_make_sure_ssh_is_enabled_on_your_computer):tr(R.string.only_hosts_responding_with_an_ssh_banner_on_port_22_are_shown))));
        renderList();
    }
    private void showPicker(){
        if(picker!=null&&picker.isShowing())return;
        picker=new Dialog(this);picker.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content=column();content.setPadding(dp(20),dp(10),dp(20),dp(20));content.setBackground(round(0xff161719,26));
        View grip=new View(this);grip.setBackground(round(0xff575b60,3));
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(dp(44),dp(4));gp.gravity=Gravity.CENTER_HORIZONTAL;gp.bottomMargin=dp(20);content.addView(grip,gp);
        content.setContentDescription(tr(R.string.local_ssh_server_list));
        LinearLayout heading=row();content.addView(heading);
        heading.addView(text(tr(R.string.local_ssh_servers),22,TEXT),new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(SampleUi.header(this,"close",tr(R.string.close_server_list),TEXT,()->picker.dismiss()),new LinearLayout.LayoutParams(dp(44),dp(44)));
        listingStatus=text("",12,MUTED);margin(content,listingStatus,8);
        ScrollView scroll=new ScrollView(this);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,0,1);sp.topMargin=dp(12);content.addView(scroll,sp);
        list=column();scroll.addView(list);
        TextView retry=button(tr(R.string.scan_again),tr(R.string.scan_for_servers_again),()->rescan(true));retry.setTextColor(GREEN);content.addView(retry,new LinearLayout.LayoutParams(-1,dp(48)));
        picker.setContentView(content);Window window=picker.getWindow();
        if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams p=window.getAttributes();p.gravity=Gravity.BOTTOM;p.dimAmount=.35f;window.setAttributes(p);}
        picker.setOnDismissListener(d->{list=null;listingStatus=null;picker=null;});picker.show();
        AdaptiveLayout.sheetWindow(this,picker,600,540);
        renderList();
    }
    private void renderList(){
        if(list==null)return;
        listingStatus.setText(scanStatus);list.removeAllViews();
        if(hosts.isEmpty()){
            TextView empty=text(scanning?tr(R.string.looking_for_ssh_servers_on_your_network):tr(R.string.no_servers_found_scan_again_or_enter_an_address_manually),15,MUTED);
            empty.setPadding(dp(12),dp(24),dp(12),dp(24));list.addView(empty);return;
        }
        for(LanSshDiscovery.Host item:hosts){
            LinearLayout card=row();card.setPadding(dp(18),dp(16),dp(18),dp(16));card.setBackground(SampleUi.touch(this,CARD,20));
            ImageView icon=SampleUi.icon(this,"server",MUTED);icon.setPadding(0,0,dp(14),0);card.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(48)));
            LinearLayout labels=column();card.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            TextView title=text(item.name,19,TEXT);title.setMaxLines(2);labels.addView(title);
            TextView address=text(item.address+":22",15,MUTED);address.setTypeface(Typeface.MONOSPACE);labels.addView(address);
            ImageView arrow=SampleUi.icon(this,"right",MUTED);card.addView(arrow,new LinearLayout.LayoutParams(dp(24),dp(24)));
            card.setContentDescription(tr(R.string.selected_server,item.name,item.address));
            card.setFocusable(true);card.setOnClickListener(v->{name.setText(item.name);host.setText(item.address);port.setText("22");picker.dismiss();});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);list.addView(card,p);
        }
    }
    private void stopScan(){if(scanner!=null){scanner.close();scanner=null;}}
    @Override protected void onStop(){
        visible=false;main.removeCallbacksAndMessages(null);stopScan();
        if(busy){if(setup!=null)setup.close();if(trustDialog!=null)trustDialog.dismiss();setBusy(false);connectionStatus.setText(tr(R.string.verification_paused_connect_again_to_continue));}
        if(networkCallback!=null){try{cm.unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}networkCallback=null;}
        if(picker!=null)picker.dismiss();super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        state.putBoolean("tmux",tmux.isChecked());state.putString("user",user.getText().toString());state.putInt("auth",authChoices.getCheckedRadioButtonId());
        state.putString("name",name.getText().toString());state.putString("host",host.getText().toString());state.putString("port",port.getText().toString());super.onSaveInstanceState(state);
    }
    // Rotation keeps credentials in memory only; never persist secrets to the saved-state Bundle.
    private static final class RetainedInput {
        final String password,passphrase;final byte[] key;
        RetainedInput(String password,String passphrase,byte[] key){this.password=password;this.passphrase=passphrase;this.key=key.clone();}
    }
    @Override public Object onRetainNonConfigurationInstance(){return new RetainedInput(password.getText().toString(),passphrase.getText().toString(),privateKey);}
    private EditText secretField(String hint,String description){
        EditText e=field(hint,description);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);return e;
    }
    private void importKey(){
        if(busy)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try{startActivityForResult(intent,IMPORT_KEY);}catch(ActivityNotFoundException e){connectionStatus.setText(tr(R.string.no_file_picker_is_available_on_this_device));}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=IMPORT_KEY||result!=RESULT_OK||data==null||data.getData()==null)return;
        try(java.io.InputStream in=getContentResolver().openInputStream(data.getData());java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            byte[] bytes=new byte[4096];for(int count;(count=in.read(bytes))!=-1;){if(out.size()+count>1024*1024)throw new java.io.IOException();out.write(bytes,0,count);}
            byte[] loaded=out.toByteArray();String header=new String(loaded,java.nio.charset.StandardCharsets.US_ASCII);
            if(!header.contains("PRIVATE KEY-----"))throw new java.io.IOException();
            java.util.Arrays.fill(privateKey,(byte)0);privateKey=loaded;passphrase.setText("");passphrase.setHint(tr(R.string.key_passphrase_optional));keyStatus.setText(tr(R.string.private_key_bytes,privateKey.length));
        }catch(Exception error){connectionStatus.setText(tr(R.string.cannot_read_this_key_choose_an_openssh_pem_private_key_no_larger));}
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
        connectButton.setEnabled(!value);connectButton.setAlpha(value?.5f:1f);connectButton.setText(value?tr(R.string.verifying_connection):original==null?tr(R.string.save_and_connect):tr(R.string.save_changes_and_connect));}
    private void verify(SshProfileStore.Draft d){
        if(setup!=null)setup.close();setBusy(true);connectionStatus.setText(tr(R.string.connecting_endpoint,d.host+":"+d.port));
        setup=new SshConnectionSetup();setup.start(d,new SshConnectionSetup.Listener(){
            public void trustedHostNeeded(String fingerprint,byte[] knownHosts){
                connectionStatus.setText(tr(R.string.waiting_for_fingerprint_confirmation));
                Runnable cancel=()->{setBusy(false);connectionStatus.setText(tr(R.string.connection_cancelled_server_not_saved));};
                trustDialog=SampleUi.confirm(SshDiscoveryActivity.this,tr(R.string.verify_server_fingerprint),
                        tr(R.string.compare_this_fingerprint_with_your_server_s_fingerprint_login_cre),d.host+":"+d.port+"\n\n"+fingerprint,
                        tr(R.string.cancel),tr(R.string.trust_and_connect),false,cancel,
                        ()->{d.knownHosts=knownHosts;trustedKey=knownHosts;trustedEndpoint=d.host+":"+d.port;verify(d);});
            }
            public void verified(){
                try{
                    SshProfileStore store=new SshProfileStore(SshDiscoveryActivity.this);String id=editingId;
                    if(id==null)id=store.save(d);else store.update(id,d);
                    password.setText("");passphrase.setText("");
                    setResult(RESULT_OK,new Intent().putExtra("profile",id));finish();
                }catch(Exception error){setBusy(false);connectionStatus.setText(tr(R.string.connection_verified_but_saving_failed_check_device_storage_and_tr));}
            }
            public void failed(String message){setBusy(false);connectionStatus.setText(message);}
        });
    }
    private void cancelEdit(){
        if(busy){if(setup!=null)setup.close();if(trustDialog!=null)trustDialog.dismiss();setBusy(false);connectionStatus.setText(tr(R.string.verification_paused_original_connection_unchanged));}
        boolean changed=original!=null&&(!name.getText().toString().equals(original.name)||!host.getText().toString().equals(original.host)
                ||!port.getText().toString().equals(Integer.toString(original.port))||!user.getText().toString().equals(original.user)
                ||tmux.isChecked()!=original.tmux||(!"key".equals(original.auth))!=(authChoices.getCheckedRadioButtonId()==R.id.auth_password)
                ||password.length()>0||passphrase.length()>0||!java.util.Arrays.equals(privateKey,original.privateKey));
        if(changed){if(discardDialog==null||!discardDialog.isShowing())discardDialog=SampleUi.confirm(this,tr(R.string.discard_changes),tr(R.string.unsaved_changes_will_be_lost_your_original_connection_will_be_kep),null,tr(R.string.keep_editing),tr(R.string.discard_changes_71),true,null,this::finish);}
        else finish();
    }
    @Override public void onBackPressed(){cancelEdit();}
    @Override protected void onDestroy(){
        if(discardDialog!=null)discardDialog.dismiss();if(setup!=null)setup.close();if(trustDialog!=null)trustDialog.dismiss();java.util.Arrays.fill(privateKey,(byte)0);if(original!=null)java.util.Arrays.fill(original.privateKey,(byte)0);super.onDestroy();
    }
    private EditText field(String hint,String description){
        EditText e=new EditText(this);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(17);e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);e.setHint(hint);e.setContentDescription(description);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);SampleUi.field(e);return e;
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
