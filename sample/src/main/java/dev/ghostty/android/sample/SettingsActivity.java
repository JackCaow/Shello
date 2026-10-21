package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** Grouped settings pages. Stored preferences are applied when a terminal resumes. */
public final class SettingsActivity extends ScreenActivity {
    private SharedPreferences prefs;
    private String page="";
    private boolean working;
    private int homeScroll;
    private LinearLayout group,toolbarPreview;
    private static final int CARD=0xff111315, DIVIDER=0xff222426;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences("appearance",0);
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        body.setPadding(dp(16),dp(landscape?12:24),dp(16),dp(32));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setId(android.R.id.list);
        homeScroll=state==null?0:state.getInt("homeScroll");
        show(state==null?getIntent().getStringExtra("page"):state.getString("page"));
        if(state!=null){int y=state.getInt("scroll");scroll.post(()->scroll.scrollTo(0,y));}
    }
    @Override protected void onSaveInstanceState(Bundle out){
        out.putString("page",page);out.putInt("homeScroll",homeScroll);out.putInt("scroll",scroll.getScrollY());
        super.onSaveInstanceState(out);
    }
    private TextView label(String value,int size,int color){
        TextView text=SampleUi.text(this,value,size,color);
        text.setIncludeFontPadding(false);text.setLineSpacing(dp(3),1);return text;
    }
    private void open(String next){if(page.isEmpty())homeScroll=scroll.getScrollY();show(next);}
    private void show(String value){
        page=value==null?"":value;group=null;toolbarPreview=null;body.removeAllViews();
        switch(page){
            case "language":language();break;
            case "hook":hook();break;
            case "notifications":notifications();break;
            case "font":font();break;
            case "theme":theme();break;
            case "advanced":advanced();break;
            case "toolbar":toolbar();break;
            case "gestures":gestures();break;
            case "chat":chat();break;
            case "shortcuts":shortcuts();break;
            default:home();break;
        }
        scroll.scrollTo(0,0);
        scroll.post(()->scroll.scrollTo(0,page.isEmpty()?homeScroll:0));
    }
    @Override void section(String title){
        group=null;
        TextView caption=label(title,13,SampleUi.MUTED);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        p.topMargin=body.getChildCount()==0?0:dp(landscape?16:24);p.bottomMargin=dp(10);
        body.addView(caption,p);
    }
    @Override TextView copy(String value){
        group=null;
        TextView text=label(value,14,SampleUi.MUTED);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);
        body.addView(text,p);return text;
    }
    private void addRow(View row){
        if(group==null){
            group=column();group.setBackground(SampleUi.surface(this,CARD,12));group.setClipToOutline(true);
            body.addView(group,new LinearLayout.LayoutParams(-1,-2));
        }
        if(group.getChildCount()>0){View line=new View(this);line.setBackgroundColor(DIVIDER);group.addView(line,new LinearLayout.LayoutParams(-1,Math.max(1,dp(1))));}
        group.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private LinearLayout settingRow(String title,String subtitle,String icon,View accessory,Runnable action){
        LinearLayout row=row();row.setPadding(dp(16),dp(12),dp(16),dp(12));row.setMinimumHeight(dp(48));
        if(icon!=null){
            LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(20),dp(20));ip.rightMargin=dp(16);
            row.addView(SampleUi.icon(this,icon,SampleUi.MUTED),ip);
        }
        LinearLayout words=column();words.addView(label(title,16,SampleUi.TEXT));
        if(subtitle!=null){TextView sub=label(subtitle,14,SampleUi.MUTED);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(5);words.addView(sub,p);}
        row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        if(accessory!=null){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.leftMargin=dp(12);row.addView(accessory,p);}
        if(action!=null){row.setContentDescription(title);row.setFocusable(true);row.setBackground(SampleUi.touch(this,Color.TRANSPARENT,0));row.setOnClickListener(v->action.run());}
        addRow(row);return row;
    }
    private View trailing(String value,boolean chevron){
        LinearLayout end=row();
        if(value!=null){TextView text=label(value,16,SampleUi.MUTED);text.setSingleLine(true);text.setEllipsize(TextUtils.TruncateAt.END);
            text.setMaxWidth(dp(getResources().getConfiguration().fontScale>1.2f?100:146));end.addView(text);}
        if(chevron){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(18),dp(18));if(value!=null)p.leftMargin=dp(10);end.addView(SampleUi.icon(this,"right",SampleUi.MUTED),p);}
        return end;
    }
    private void navigation(String title,String value,String icon,String target){settingRow(title,null,icon,trailing(value,true),()->open(target));}
    @Override View item(String title,String detail,String icon,Runnable action){return settingRow(title,detail,icon,action==null?null:trailing(null,true),action);}
    private void toggle(String title,String subtitle,String icon,String preferenceGroup,String key,boolean fallback,Runnable changed){
        SharedPreferences source=getSharedPreferences(preferenceGroup,0);
        SettingSwitch toggle=new SettingSwitch(title,subtitle,icon);
        toggle.setChecked(source.getBoolean(key,fallback));
        toggle.setOnCheckedChangeListener((v,checked)->{source.edit().putBoolean(key,checked).apply();if(changed!=null)changed.run();});
        addRow(toggle);
    }
    private void home(){
        heading.setText(tr(R.string.settings));section(tr(R.string.terminal));
        navigation(tr(R.string.theme),ThemePreferences.label(ThemePreferences.current(prefs)),"palette","theme");
        navigation(tr(R.string.font_and_size),prefs.getInt("fontSize",14)+" sp","font","font");navigation(tr(R.string.advanced),null,"sliders","advanced");
        section(tr(R.string.agents));navigation("Hook",null,"hook","hook");navigation(tr(R.string.notifications),null,"bell","notifications");navigation(tr(R.string.text_draft),null,"chat","chat");
        section(tr(R.string.input));navigation(tr(R.string.toolbar),null,"toolbox","toolbar");navigation(tr(R.string.shortcuts),null,"keyboard","shortcuts");navigation(tr(R.string.gestures),null,"pad","gestures");
        section(tr(R.string.app_language));navigation(tr(R.string.app_language),languageName(AppLanguage.choice(this)),"chat","language");
        section(tr(R.string.about));item("Shello",tr(R.string.open_source_android_terminal_library_api_27),"server",()->startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/JackCaow/Shello"))));
    }
    private String languageName(String choice){return choice.equals("en")?"English":choice.equals("zh-CN")?"简体中文":tr(R.string.follow_system);}
    private void language(){
        heading.setText(tr(R.string.app_language));section(tr(R.string.app_language));
        String selected=AppLanguage.choice(this);
        for(String choice:new String[]{"system","en","zh-CN"}){
            String name=languageName(choice);boolean checked=selected.equals(choice);
            ImageView check=SampleUi.icon(this,"check",SampleUi.GREEN);check.setMinimumWidth(dp(20));check.setMinimumHeight(dp(20));check.setVisibility(checked?View.VISIBLE:View.INVISIBLE);
            LinearLayout row=settingRow(name,null,null,check,()->{
                if(checked)return;
                AppLanguage.select(this,choice);
                // Android 13+ recreates via LocaleManager; older versions use our wrapper.
                if(Build.VERSION.SDK_INT<33)recreate();
            });
            row.setSelected(checked);row.setContentDescription(checked?tr(R.string.selected_option,name):name);
        }
        copy(tr(R.string.language_help));
    }
    private void notifications(){
        heading.setText(tr(R.string.notifications));
        toggle(tr(R.string.agent_event_notifications),null,"bell","agent","notifications",true,null);
        copy(tr(R.string.receive_approval_requests_questions_and_completion_notifications));
        section(tr(R.string.permissions));item(tr(R.string.system_notification_settings),tr(R.string.review_notification_permissions_and_channels),"bell",()->startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName())));
        if(Build.VERSION.SDK_INT>=33)item(tr(R.string.request_notification_permission),null,"bell",()->requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},91));
        section(tr(R.string.agent_updates));item(tr(R.string.install_hook),tr(R.string.install_on_an_ssh_host_to_receive_agent_events),"hook",()->open("hook"));
        copy(tr(R.string.active_ssh_sessions_keep_fetching_events_in_the_background_if_no));
    }
    private void font(){
        heading.setText(tr(R.string.font_and_size));section(tr(R.string.terminal_text));
        LinearLayout stepper=row();stepper.setBackground(SampleUi.surface(this,0xff222426,24));
        TextView number=label("",15,SampleUi.TEXT);number.setGravity(Gravity.CENTER);number.setSingleLine(true);number.setMinWidth(dp(48));
        TextView preview=label("",14,SampleUi.TEXT);preview.setTypeface(Typeface.MONOSPACE);
        View minus=SampleUi.header(this,"minus",tr(R.string.decrease_terminal_font_size),SampleUi.TEXT,()->{});
        View plus=SampleUi.header(this,"plus",tr(R.string.increase_terminal_font_size),SampleUi.TEXT,()->{});
        stepper.addView(minus,new LinearLayout.LayoutParams(dp(40),dp(48)));
        stepper.addView(number,new LinearLayout.LayoutParams(-2,dp(48)));
        stepper.addView(plus,new LinearLayout.LayoutParams(dp(40),dp(48)));
        Runnable refresh=()->{int size=prefs.getInt("fontSize",14);number.setText(size+" sp");number.setContentDescription(tr(R.string.terminal_font_size_value,size));preview.setTextSize(size);minus.setEnabled(size>10);minus.setAlpha(size>10?1:.3f);plus.setEnabled(size<24);plus.setAlpha(size<24?1:.3f);};
        minus.setOnClickListener(v->{prefs.edit().putInt("fontSize",Math.max(10,prefs.getInt("fontSize",14)-1)).apply();refresh.run();});
        plus.setOnClickListener(v->{prefs.edit().putInt("fontSize",Math.min(24,prefs.getInt("fontSize",14)+1)).apply();refresh.run();});
        settingRow(tr(R.string.font_size),null,"font",stepper,null);
        settingRow(tr(R.string.font),null,"font",trailing(tr(R.string.system_monospace),false),null);
        copy(tr(R.string.chinese_and_emoji_font_fallback_supported_font_size_10_24_sp));
        section(tr(R.string.preview));preview.setText(tr(R.string.font_preview_sample));preview.setPadding(dp(16),dp(20),dp(16),dp(20));preview.setMinHeight(dp(140));preview.setBackground(SampleUi.surface(this,0xff1e1e1e,12));body.addView(preview,new LinearLayout.LayoutParams(-1,-2));refresh.run();
    }
    private void theme(){
        heading.setText(tr(R.string.theme));section(tr(R.string.terminal_theme));String selected=ThemePreferences.current(prefs);
        for(String name:new String[]{"shello","black","light"}){
            ImageView check=SampleUi.icon(this,"check",SampleUi.GREEN);check.setMinimumWidth(dp(20));check.setMinimumHeight(dp(20));check.setVisibility(selected.equals(name)?View.VISIBLE:View.INVISIBLE);
            LinearLayout row=settingRow(ThemePreferences.label(name),null,"palette",check,()->{prefs.edit().putString("theme",name).apply();show("theme");});row.setSelected(selected.equals(name));row.setContentDescription(selected.equals(name)?tr(R.string.selected_option,ThemePreferences.label(name)):ThemePreferences.label(name));
        }
        copy(tr(R.string.sets_default_terminal_foreground_and_background_colors_colors_set));
        section(tr(R.string.preview));TextView preview=label(tr(R.string.theme_preview_sample),14,selected.equals("light")?0xff202124:0xffd4d4d4);preview.setTypeface(Typeface.MONOSPACE);preview.setPadding(dp(16),dp(20),dp(16),dp(20));preview.setMinHeight(dp(140));preview.setBackground(SampleUi.surface(this,selected.equals("light")?0xfff5f5f5:selected.equals("black")?Color.BLACK:0xff1e1e1e,12));body.addView(preview,new LinearLayout.LayoutParams(-1,-2));
    }
    private void advanced(){
        heading.setText(tr(R.string.advanced));section(tr(R.string.home));
        toggle(tr(R.string.show_connection_hook_status),tr(R.string.show_status_dots_on_saved_connections),null,"appearance","hookStatus",true,null);
        toggle(tr(R.string.show_feature_guide),tr(R.string.show_feature_tips_on_the_home_screen),null,"appearance","guide",true,null);
        section(tr(R.string.background_activity));item(tr(R.string.battery_and_background_activity),tr(R.string.review_device_battery_restrictions),"settings",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName()))));
        copy(tr(R.string.ssh_stays_connected_in_the_background_tmux_sessions_stay_on_the_s));
    }
    private void toolbar(){
        heading.setText(tr(R.string.toolbar));section(tr(R.string.preview));
        LinearLayout canvas=column();canvas.setPadding(dp(8),dp(8),dp(8),dp(8));
        GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff063a18,0xff164d48});gradient.setCornerRadius(dp(12));canvas.setBackground(gradient);
        TextView caption=label(tr(R.string.live_toolbar_preview),16,SampleUi.MUTED);caption.setGravity(Gravity.CENTER);canvas.addView(caption,new LinearLayout.LayoutParams(-1,0,1));
        toolbarPreview=row();toolbarPreview.setPadding(dp(6),dp(6),dp(6),dp(6));toolbarPreview.setBackground(SampleUi.surface(this,Color.BLACK,32));toolbarPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        canvas.addView(toolbarPreview,new LinearLayout.LayoutParams(-1,dp(52)));body.addView(canvas,new LinearLayout.LayoutParams(-1,dp(landscape?132:276)));refreshToolbar();
        section(tr(R.string.visible_buttons));
        for(String[] tool:new String[][]{{"pad",tr(R.string.direction_pad)},{"history",tr(R.string.input_history)},{"paste",tr(R.string.paste)},{"chat",tr(R.string.text_draft)}})
            toggle(tool[1],null,null,"appearance","tool_"+tool[0],true,this::refreshToolbar);
        copy(tr(R.string.ctrl_esc_tab_and_the_keyboard_button_are_always_shown_changes_app));
    }
    private void refreshToolbar(){
        if(toolbarPreview==null)return;toolbarPreview.removeAllViews();
        for(String tool:new String[]{"Ctrl","Esc","Tab","pad","history","paste","chat","keyboard"}){
            boolean fixed=tool.equals("Ctrl")||tool.equals("Esc")||tool.equals("Tab")||tool.equals("keyboard");
            if(!fixed&&!prefs.getBoolean("tool_"+tool,true))continue;
            View view;
            if(tool.length()>1&&Character.isUpperCase(tool.charAt(0))){TextView key=label(tool,13,SampleUi.TEXT);key.setGravity(Gravity.CENTER);key.setTypeface(Typeface.MONOSPACE);key.setSingleLine(true);key.setAutoSizeTextTypeUniformWithConfiguration(9,13,1,android.util.TypedValue.COMPLEX_UNIT_DIP);view=key;}
            else{ImageView icon=SampleUi.icon(this,tool.equals("history")?"panel":tool,SampleUi.TEXT);icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);icon.setPadding(dp(8),dp(8),dp(8),dp(8));view=icon;}
            view.setBackground(SampleUi.surface(this,CARD,24));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(36),1);p.leftMargin=dp(2);p.rightMargin=dp(2);toolbarPreview.addView(view,p);
        }
    }
    private void gestures(){
        heading.setText(tr(R.string.gestures));section(tr(R.string.terminal));toggle(tr(R.string.long_press_to_select_text),null,null,"appearance","selection",true,null);
        item(tr(R.string.swipe_up_or_down),tr(R.string.browse_terminal_scrollback),"history",null);item(tr(R.string.drag_selection_handles),tr(R.string.adjust_the_text_selection),"pad",null);
        section(tr(R.string.home));item(tr(R.string.long_press_a_session_card),tr(R.string.reorder_close_or_close_all),"reorder",null);
    }
    private void chat(){
        heading.setText(tr(R.string.text_draft));section(tr(R.string.input_mode));toggle(tr(R.string.start_with_a_text_draft),tr(R.string.edit_before_sending_when_a_terminal_opens),"chat","appearance","draftDefault",false,null);
        copy(tr(R.string.multiline_input_is_supported_respond_to_agent_questions_and_appro));
        section(tr(R.string.send));item(tr(R.string.tap_send),tr(R.string.send_text_and_press_enter),"send",null);item(tr(R.string.long_press_send),tr(R.string.send_text_without_enter),"edit",null);
    }
    private void shortcuts(){
        heading.setText(tr(R.string.shortcuts));section(tr(R.string.terminal_keys));item("Ctrl",tr(R.string.tap_to_combine_with_the_next_character),"keyboard",null);item("Esc / Tab",tr(R.string.send_the_corresponding_terminal_key),"keyboard",null);
        section(tr(R.string.direction_pad));item(tr(R.string.arrow_keys),tr(R.string.move_up_down_left_or_right),"pad",null);item(tr(R.string.enter_and_backspace),tr(R.string.confirm_input_or_delete_a_character),"return",null);item(tr(R.string.clear_screen),tr(R.string.send_the_clear_screen_shortcut),"clear",null);
        section(tr(R.string.text_draft));item(tr(R.string.tap_send),tr(R.string.send_text_and_press_enter),"send",null);item(tr(R.string.long_press_send),tr(R.string.send_text_only),"edit",null);
    }
    private void hook(){
        heading.setText("Hook");section(tr(R.string.install));
        TextView intro=copy(tr(R.string.install_claude_code_hook_on_an_ssh_host_to_receive_approval_reque));intro.setTextColor(SampleUi.TEXT);intro.setTextSize(16);
        section(tr(R.string.ssh_hosts));AgentHub hub=AgentHub.get(this);java.util.List<SshProfileStore.Entry> profiles=new SshProfileStore(this).list();
        if(profiles.isEmpty())item(tr(R.string.add_an_ssh_connection_first),null,"plus",()->startActivity(new Intent(this,SshDiscoveryActivity.class)));
        for(SshProfileStore.Entry p:profiles){boolean enabled=hub.enabled(p.id);item(p.name,enabled?tr(hub.states.getOrDefault(p.id,R.string.enabled)):tr(R.string.not_installed),"server",()->{
            if(working)return;SampleUi.confirm(this,enabled?tr(R.string.uninstall_hook):tr(R.string.install_hook_387),p.user+"@"+p.host+"\n"+(enabled?tr(R.string.only_the_claude_code_hook_registered_by_this_app_will_be_removed):tr(R.string.existing_settings_are_kept_start_a_new_claude_code_session_after)),null,tr(R.string.cancel),enabled?tr(R.string.uninstall):tr(R.string.install),false,null,()->{
                working=true;toast(tr(R.string.working));hub.configure(p.id,!enabled,(ok,message)->{working=false;if(isDestroyed())return;toast(message);show("hook");});
            });
        });}
        copy(tr(R.string.installation_and_sync_use_ssh_no_extra_ports_are_needed_installat));
        boolean updates=false;for(SshProfileStore.Entry p:profiles)if(hub.enabled(p.id)){
            if(!updates){section(tr(R.string.update));updates=true;}
            item(p.name,tr(R.string.update_host_helper_and_recent_session_support),"hook",()->{if(working)return;working=true;toast(tr(R.string.updating));hub.configure(p.id,true,(ok,message)->{working=false;if(isDestroyed())return;toast(message);show("hook");});});
        }
        section(tr(R.string.hook_status));toggle(tr(R.string.show_hook_status),tr(R.string.show_status_dots_on_saved_connections),null,"appearance","hookStatus",true,null);
    }
    @Override public void onBackPressed(){if(!page.isEmpty())show("");else super.onBackPressed();}

    /** One accessible switch row, with text wrapping clear of the trailing thumb. */
    private final class SettingSwitch extends CheckBox {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        SettingSwitch(String title,String subtitle,String icon){
            super(SettingsActivity.this);setButtonDrawable((android.graphics.drawable.Drawable)null);
            setTextSize(16);setTextColor(SampleUi.TEXT);setIncludeFontPadding(false);setGravity(Gravity.CENTER_VERTICAL);setLineSpacing(dp(3),1);
            setPadding(dp(16),dp(12),dp(80),dp(12));setMinHeight(dp(48));setBackground(SampleUi.touch(SettingsActivity.this,Color.TRANSPARENT,0));
            if(subtitle==null)setText(title);else{SpannableString text=new SpannableString(title+"\n"+subtitle);text.setSpan(new ForegroundColorSpan(SampleUi.MUTED),title.length()+1,text.length(),0);text.setSpan(new AbsoluteSizeSpan(Math.round(14*getResources().getDisplayMetrics().scaledDensity)),title.length()+1,text.length(),0);setText(text);}
            setContentDescription(subtitle==null?title:tr(R.string.accessibility_subtitle,title,subtitle));
            if(icon!=null){ToolbarIcon glyph=new ToolbarIcon(icon,dp(20),SampleUi.MUTED);glyph.setBounds(0,0,dp(20),dp(20));setCompoundDrawables(glyph,null,null,null);setCompoundDrawablePadding(dp(16));}
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float right=getWidth()-dp(16),left=right-dp(48),cy=getHeight()/2f;
            paint.setColor(isChecked()?SampleUi.GREEN:0xff242628);c.drawRoundRect(left,cy-dp(12),right,cy+dp(12),dp(12),dp(12),paint);
            paint.setColor(isChecked()?Color.BLACK:Color.WHITE);float x=isChecked()?right-dp(14):left+dp(14);c.drawRoundRect(x-dp(12),cy-dp(10),x+dp(12),cy+dp(10),dp(10),dp(10),paint);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.Switch");}
    }
}
