package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import dev.ghostty.android.*;
import java.nio.charset.StandardCharsets;

/** Shello terminal controls around offline echo or a sample SSH shell. */
public final class MainActivity extends LocalizedActivity {
    private static String requestedAgentTab;
    static void openAgent(Activity from,String profile,String target){
        SessionTarget.validate(target);requestedAgentTab=SessionTarget.id(profile,target);
        from.startActivity(new Intent(from,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    private static String takeAgentTab(){String id=requestedAgentTab;requestedAgentTab=null;return id;}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);String id=takeAgentTab();if(id!=null){pendingProfile=id;connectSsh();}}
    private static final int SURFACE=0xff1e1e1e, KEY=0xff111315, TEXT=0xffe6e6e8,
            MUTED=0xff96999f, GREEN=0xff00d86c;
    private TerminalSession session;
    private SshSessionService sshService;
    private boolean sshMode, sshConnected, destroyed, serviceSession, bound, connectOnBind;
    private final SshSessionService.Observer serviceObserver=new SshSessionService.Observer(){
        public void changed(){updateSshView();}
        public void detach(){detachServiceView();}
    };
    private final ServiceConnection serviceConnection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,android.os.IBinder binder){
            if(destroyed||!bound)return;
            sshService=((SshSessionService.LocalBinder)binder).service();
            sshService.observe(serviceObserver);
            if(connectOnBind||sshService.shouldResume()){
                boolean explicitConnect=connectOnBind;connectOnBind=false;
                connectSsh(explicitConnect);
            }
        }
        public void onServiceDisconnected(ComponentName name){
            if(destroyed||!bound)return;
            detachServiceView();sshService=null;sshConnected=false;
            subtitle.setText(tr(R.string.local_service_stopped));
        }
    };
    private TextView protocolBadge;
    private SessionOverview overview;
    private SshSessionService.Tab shownTab;
    private final android.os.Handler previewHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable previewCheckpoint=new Runnable(){public void run(){rememberTab();previewHandler.postDelayed(this,5000);}};
    private TerminalSession.Checkpoint checkpoint;
    private TerminalView terminal;
    private TerminalInputView directInput;
    private EditText draft;
    private LinearLayout root, sheet, composer, toolbar, pad, selectionBar, savedConnections;
    private String pendingProfile,restoredDraftTab,restoredDraft;
    private boolean restoredDraftMode;
    private PopupWindow connectionMenu;
    private android.app.AlertDialog connectionDeleteDialog,infoDialog;
    private FrameLayout stage, viewport;
    private ScrollView home;
    private LinearLayout homePage;
    private View addConnection;
    private android.app.Dialog newConnectionDialog,tmuxDialog;private String connectingProfile,sessionPickerProfile;
    private AgentHub agentHub;
    private final Runnable agentChanged=()->refreshSavedConnections();
    private String appliedTheme;
    private TextView hookAttention;
    private boolean guideShown;
    private TextView subtitle, ctrlButton, padButton, lockButton, sendButton;
    private final java.util.ArrayList<String> draftHistory=new java.util.ArrayList<>();
    private boolean padPositioned;
    private HorizontalScrollView toolScroll;
    private LinearLayout composerActions;
    private boolean ctrlArmed, draftMode, imeVisible, minimized, padLocked;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        root=column(); root.setBackgroundColor(Color.BLACK);
        root.setFocusableInTouchMode(true);
        if(Build.VERSION.SDK_INT>=30) applyModernInsets();
        setContentView(root);
        stage=new FrameLayout(this); root.addView(stage,new LinearLayout.LayoutParams(-1,-1));
        sheet=column(); sheet.setBackground(round(SURFACE,24)); sheet.setClipToOutline(true);
        stage.addView(sheet,new FrameLayout.LayoutParams(-1,-1));
        buildHeader();
        viewport=new FrameLayout(this);
        sheet.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        terminal=new TerminalView(this); terminal.setBackgroundColor(SURFACE);
        FrameLayout.LayoutParams grid=new FrameLayout.LayoutParams(-1,-1);
        grid.setMargins(dp(8),dp(4),dp(8),0); viewport.addView(terminal,grid);
        buildSelectionBar(); buildPad(); buildToolbar(); buildComposer();
        // A real EditText remains the IME target in direct mode; candidates stay in the IME.
        directInput=new TerminalInputView(this); directInput.setTerminalView(terminal);
        directInput.setAlpha(0); directInput.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sheet.addView(directInput,new LinearLayout.LayoutParams(1,1));
        session=new TerminalSession(40,20);
        terminal.setListener(new TerminalView.Listener() {
            @Override public void onInput(byte[] bytes) { sendInput(bytes); }
            @Override public void onResize(int columns,int rows) {
                subtitle.setContentDescription(tr(R.string.terminal_dimensions,sshMode?tr(R.string.ssh_terminal):tr(R.string.local_terminal),columns,rows));
                if(sshMode&&sshService!=null)sshService.resize(columns,rows);
                if(directInput.hasFocus()||draft.hasFocus())terminal.post(terminal::scrollToBottom);
            }
            @Override public void onSelectionChanged(String text) {
                selectionBar.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);
            }
        });
        terminal.setSession(session);
        buildHome();
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            android.graphics.Rect visible=new android.graphics.Rect();
            root.getWindowVisibleDisplayFrame(visible);
            if(Build.VERSION.SDK_INT<30)imeVisible=root.getRootView().getHeight()-visible.bottom>dp(120);
        });
        viewport.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->clampPad());
        sheet.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            int toolWidth=Math.min(dp(600),Math.max(0,r-l-dp(16)));
            LinearLayout.LayoutParams tools=(LinearLayout.LayoutParams)toolScroll.getLayoutParams();
            if(tools.width!=toolWidth){tools.width=toolWidth;tools.gravity=Gravity.CENTER_HORIZONTAL;toolScroll.setLayoutParams(tools);}
            int composerWidth=Math.min(dp(800),Math.max(0,r-l-dp(16)));
            LinearLayout.LayoutParams cp=(LinearLayout.LayoutParams)composer.getLayoutParams();
            if(cp.width!=composerWidth){cp.width=composerWidth;cp.gravity=Gravity.CENTER_HORIZONTAL;composer.setLayoutParams(cp);}
            boolean compact=b-t<dp(400)&&composerWidth>=dp(600);
            if(composer.getOrientation()!=(compact?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL)){
                composer.setOrientation(compact?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);composer.setGravity(Gravity.CENTER_VERTICAL);
                draft.setLayoutParams(new LinearLayout.LayoutParams(compact?0:-1,-2,compact?1:0));
                composerActions.setLayoutParams(new LinearLayout.LayoutParams(compact?dp(220):-1,dp(44)));
            }
            draft.setMaxLines(compact?2:4);
        });
        write(tr(R.string.local_demo_banner)); root.requestFocus();
        pendingProfile=state==null?takeAgentTab():state.getString("pendingProfile");
        if(state!=null){restoredDraftTab=state.getString("draftTab");restoredDraft=state.getString("draft","");restoredDraftMode=state.getBoolean("draftMode");}
        boolean legacyFixture=state==null&&BuildConfig.DEBUG&&getIntent().getBooleanExtra("connectLocalSsh",false);
        // Explicit debug fixture launches select files/ssh without deleting saved UI profiles.
        if(legacyFixture)getSharedPreferences("ssh_profiles",0).edit().remove("active").commit();
        connectOnBind=pendingProfile!=null||legacyFixture;
        if(state==null&&!connectOnBind)minimize();
        if(state!=null){draft.setText(state.getString("draft",""));setDraftMode(state.getBoolean("draftMode"),false);if(state.getBoolean("minimized"))minimize();else if(state.getBoolean("keyboard"))root.post(this::showKeyboard);
            if(state.getBoolean("newConnectionShowing"))root.post(this::chooseConnection);
            String picker=state.getString("sessionPickerProfile");if(picker!=null)root.post(()->chooseSession(picker));}
    }

    private void buildHeader() {
        View grip=new View(this); grip.setBackground(round(0xff5a5b60,3));
        LinearLayout.LayoutParams gripParams=new LinearLayout.LayoutParams(dp(44),dp(3));
        gripParams.gravity=Gravity.CENTER_HORIZONTAL;gripParams.topMargin=dp(8);sheet.addView(grip,gripParams);
        LinearLayout header=row(); header.setPadding(dp(16),0,dp(16),0);
        sheet.addView(header,new LinearLayout.LayoutParams(-1,dp(28)));
        TextView minimize=key("−",tr(R.string.minimize_session),this::minimize); minimize.setTextColor(Color.BLACK);
        minimize.setTextSize(14);
        minimize.setBackground(new android.graphics.drawable.InsetDrawable(round(0xffffcc00,9),dp(4)));
        header.addView(minimize,new LinearLayout.LayoutParams(dp(26),dp(26)));
        TextView menu=icon("panel",tr(R.string.session_tools),()->tools(header));
        ((ControlLabel)menu).setIcon(new ToolbarIcon("panel",dp(11),Color.BLACK));
        menu.setBackground(new android.graphics.drawable.InsetDrawable(round(GREEN,9),dp(4)));
        header.addView(menu,new LinearLayout.LayoutParams(dp(26),dp(26)));
        subtitle=label(tr(R.string.local_terminal),12,MUTED);subtitle.setTypeface(Typeface.MONOSPACE);
        subtitle.setSingleLine();subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setPadding(dp(8),0,dp(8),0);
        header.addView(subtitle,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=label(tr(R.string.local),10,0xff75b6ff);protocolBadge=badge;badge.setPadding(dp(5),dp(3),dp(5),dp(3));
        badge.setBackground(round(0xff173247,10)); header.addView(badge);
    }

    private void buildToolbar() {
        HorizontalScrollView scroll=new HorizontalScrollView(this);toolScroll=scroll;
        scroll.setHorizontalScrollBarEnabled(false);scroll.setFillViewport(true);
        toolbar=row();toolbar.setPadding(dp(5),dp(4),dp(5),dp(4));
        scroll.setBackground(round(Color.BLACK,32));scroll.setClipToOutline(true);
        scroll.addView(toolbar,new HorizontalScrollView.LayoutParams(-1,dp(52)));
        LinearLayout.LayoutParams outer=new LinearLayout.LayoutParams(-1,dp(52));
        outer.setMargins(dp(8),dp(6),dp(8),dp(8));sheet.addView(scroll,outer);
        scroll.setTag("toolbar");
        ctrlButton=key("Ctrl",tr(R.string.ctrl_combine_with_the_next_key),()->setCtrl(!ctrlArmed));
        addTool(ctrlButton);
        addTool(key("Esc","Escape",()->sendKey(TerminalSession.Key.ESCAPE)));
        addTool(key("Tab","Tab",()->sendKey(TerminalSession.Key.TAB)));
        padButton=icon("pad",tr(R.string.direction_pad),this::togglePad);addTool(padButton);
        addTool(icon("panel",tr(R.string.session_tools),()->tools(toolbar)));
        addTool(icon("paste",tr(R.string.paste_into_draft),this::paste));
        addTool(icon("history",tr(R.string.input_history),this::history));
        addTool(icon("chat",tr(R.string.edit_text),()->setDraftMode(true,true)));
        addTool(icon("keyboard",tr(R.string.show_or_hide_keyboard),this::toggleKeyboard));
    }
    private void addTool(View view) {
        view.setMinimumWidth(dp(40));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0xff35433c),toolCircle(KEY),toolCircle(Color.WHITE)));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);
        toolbar.addView(view,p);
    }
    /** Equal circular bases even when the toolbar distributes fractional button widths. */
    private android.graphics.drawable.Drawable toolCircle(int color) {
        return new android.graphics.drawable.Drawable(){
            final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            {paint.setColor(color);}
            @Override public void draw(android.graphics.Canvas canvas){
                android.graphics.Rect b=getBounds();
                float radius=Math.max(0,Math.min(b.width(),b.height())/2f-dp(3));
                canvas.drawCircle(b.exactCenterX(),b.exactCenterY(),radius,paint);
            }
            @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
            @Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
            @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
        };
    }
    private void buildComposer() {
        composer=column();composer.setPadding(dp(12),dp(8),dp(12),dp(8));
        composer.setBackground(round(Color.BLACK,26));composer.setVisibility(View.GONE);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(dp(8),dp(6),dp(8),dp(8));sheet.addView(composer,params);
        draft=new EditText(this);draft.setTextColor(TEXT);draft.setHintTextColor(MUTED);
        draft.setTextSize(14);draft.setTypeface(Typeface.MONOSPACE);draft.setHint(tr(R.string.type_a_message));draft.setBackgroundColor(Color.TRANSPARENT);
        // Command drafts must not autocorrect printf/stty into different commands.
        draft.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        draft.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        draft.setMinLines(1);draft.setMaxLines(4);draft.setPadding(dp(8),dp(10),dp(8),dp(8));
        draft.setContentDescription(tr(R.string.local_text_draft));composer.addView(draft,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout actions=row();composerActions=actions;composer.addView(actions,new LinearLayout.LayoutParams(-1,dp(44)));
        TextView add=icon("plus",tr(R.string.add_text),this::addTextMenu);composerAction(actions,add);
        composerAction(actions,icon("toolbox",tr(R.string.terminal_tools),()->tools(composer)));
        composerAction(actions,icon("close",tr(R.string.close_editor_and_keep_draft),()->setDraftMode(false,true)));
        actions.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        TextView voice=icon("mic",tr(R.string.voice_input_not_available_yet),()->{});voice.setEnabled(false);voice.setAlpha(.4f);composerAction(actions,voice);
        sendButton=icon("send",tr(R.string.send_and_press_enter_long_press_to_send_text_only),()->sendDraft(true));
        sendButton.setBackground(round(0xff191b1e,22));sendButton.setEnabled(false);
        sendButton.setOnLongClickListener(v->{sendDraft(false);return true;});
        actions.addView(sendButton,new LinearLayout.LayoutParams(dp(40),dp(40)));
        draft.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                boolean ready=s.length()>0;sendButton.setEnabled(ready);
                ((ControlLabel)sendButton).setIcon(new ToolbarIcon("send",dp(22),ready?TEXT:MUTED));
            }
            public void afterTextChanged(android.text.Editable e){}
        });
    }
    private void composerAction(LinearLayout actions,TextView button){
        button.setBackgroundColor(Color.TRANSPARENT);actions.addView(button,new LinearLayout.LayoutParams(dp(40),dp(44)));
    }
    private void showMenu(View anchor,java.util.List<SampleUi.Action> actions){
        if(connectionMenu!=null)connectionMenu.dismiss();connectionMenu=SampleUi.menu(this,anchor,actions);
    }
    private void addTextMenu(){
        showMenu(composer,java.util.Arrays.asList(new SampleUi.Action(tr(R.string.paste_text),"paste",true,false,this::paste),
                new SampleUi.Action(tr(R.string.attachments_not_available_yet),"plus",false,false,null)));
    }
    private void history(){
        java.util.List<SampleUi.Action> actions=new java.util.ArrayList<>();
        if(draftHistory.isEmpty())actions.add(new SampleUi.Action(tr(R.string.no_input_history),"history",false,false,null));
        for(String entry:draftHistory)actions.add(new SampleUi.Action(entry,"history",true,false,()->{setDraftMode(true,true);draft.setText(entry);draft.setSelection(draft.length());}));
        showMenu(toolbar,actions);
    }
    private void buildSelectionBar() {
        selectionBar=row();selectionBar.setPadding(dp(4),dp(4),dp(4),dp(4));
        selectionBar.setBackground(round(Color.BLACK,24));selectionBar.setElevation(dp(8));
        selectionBar.addView(key(tr(R.string.copy),tr(R.string.copy_selection),this::copySelection),new LinearLayout.LayoutParams(dp(64),dp(44)));
        selectionBar.addView(key("×",tr(R.string.clear_selection),()->terminal.clearSelection()),new LinearLayout.LayoutParams(dp(44),dp(44)));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);
        p.setMargins(dp(8),dp(8),dp(8),0);viewport.addView(selectionBar,p);selectionBar.setVisibility(View.GONE);
    }
    private void buildPad() {
        pad=column();pad.setElevation(dp(10));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(156),dp(184),Gravity.TOP|Gravity.LEFT);
        viewport.addView(pad,p);
        LinearLayout handle=row();pad.addView(handle,new LinearLayout.LayoutParams(-1,dp(28)));
        TextView drag=icon("grip",tr(R.string.drag_direction_pad),()->{});drag.setTextColor(MUTED);
        ((ControlLabel)drag).setIcon(new ToolbarIcon("grip",dp(44),MUTED));
        handle.addView(new View(this),new LinearLayout.LayoutParams(dp(46),1));
        handle.addView(drag,new LinearLayout.LayoutParams(dp(60),-1));
        lockButton=icon("unlock",tr(R.string.lock_direction_pad_position),()->{
            padLocked=!padLocked;
            ((ControlLabel)lockButton).setIcon(new ToolbarIcon(padLocked?"lock":"unlock",dp(14),padLocked?GREEN:MUTED));
            lockButton.setContentDescription(padLocked?tr(R.string.unlock_direction_pad_position):tr(R.string.lock_direction_pad_position));
        });((ControlLabel)lockButton).setIcon(new ToolbarIcon("unlock",dp(14),MUTED));
        handle.addView(lockButton,new LinearLayout.LayoutParams(dp(28),dp(28)));
        drag.setBackgroundColor(Color.TRANSPARENT);lockButton.setBackgroundColor(Color.TRANSPARENT);
        drag.setOnTouchListener(new View.OnTouchListener() {
            float x,y,startX,startY;
            public boolean onTouch(View v,MotionEvent e) {
                if(padLocked)return false;
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();startX=pad.getX();startY=pad.getY();return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_MOVE){pad.setX(startX+e.getRawX()-x);pad.setY(startY+e.getRawY()-y);clampPad();return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_UP){v.performClick();return true;}
                return true;
            }
        });
        padRow(new String[]{"backspace","up","clear"},new String[]{tr(R.string.backspace),tr(R.string.up),tr(R.string.clear_screen_ctrl_l)},new Runnable[]{()->sendKey(TerminalSession.Key.BACKSPACE),()->sendKey(TerminalSession.Key.UP),()->terminal.sendText("\f")});
        padRow(new String[]{"left","return","right"},new String[]{tr(R.string.left),tr(R.string.enter),tr(R.string.right)},new Runnable[]{()->sendKey(TerminalSession.Key.LEFT),()->sendKey(TerminalSession.Key.ENTER),()->sendKey(TerminalSession.Key.RIGHT)});
        padRow(new String[]{"","down",""},new String[]{"",tr(R.string.down),""},new Runnable[]{null,()->sendKey(TerminalSession.Key.DOWN),null});
        pad.setVisibility(View.GONE);
    }
    private void padRow(String[] titles,String[] descriptions,Runnable[] actions) {
        LinearLayout row=row();pad.addView(row,new LinearLayout.LayoutParams(-1,dp(52)));
        for(int i=0;i<3;i++){
            View view=actions[i]==null?new View(this):icon(titles[i],descriptions[i],actions[i]);
            if(actions[i]!=null)view.setBackground(new RippleDrawable(ColorStateList.valueOf(0xff35433c),round(titles[i].equals("return")||titles[i].equals("clear")||titles[i].equals("backspace")?Color.BLACK:KEY,16),null));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(50),1);p.setMargins(dp(1),dp(1),dp(1),dp(1));row.addView(view,p);
        }
    }
    private void togglePad() {
        boolean show=pad.getVisibility()!=View.VISIBLE;pad.setVisibility(show?View.VISIBLE:View.GONE);
        padButton.setActivated(show);if(show)pad.post(()->{
            if(!padPositioned){pad.setX((viewport.getWidth()-pad.getWidth())/2f);pad.setY(viewport.getHeight()*.42f-pad.getHeight()/2f);padPositioned=true;}
            clampPad();
        });
    }
    private void clampPad() {
        if(pad==null||viewport.getWidth()==0||pad.getWidth()==0)return;
        boolean compact=viewport.getHeight()<dp(184)&&viewport.getWidth()>=dp(468);
        if((pad.getOrientation()==LinearLayout.HORIZONTAL)!=compact){
            pad.setOrientation(compact?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
            pad.getChildAt(0).setVisibility(compact?View.GONE:View.VISIBLE);
            for(int i=1;i<pad.getChildCount();i++)pad.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(dp(156),dp(52)));
            FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)pad.getLayoutParams();p.width=dp(compact?468:156);p.height=dp(compact?52:184);pad.setLayoutParams(p);
            pad.post(this::clampPad);return;
        }
        pad.setX(Math.max(0,Math.min(pad.getX(),viewport.getWidth()-pad.getWidth())));
        pad.setY(Math.max(0,Math.min(pad.getY(),viewport.getHeight()-pad.getHeight())));
    }
    private void buildHome() {
        homePage=column();homePage.setBackground(new HomeBackdrop());
        home=new ScrollView(this);home.setClipToPadding(false);
        LinearLayout content=column();content.setClipChildren(false);int inset=dp(getResources().getConfiguration().screenWidthDp>=600?24:16);content.setPadding(inset,0,inset,dp(110));home.addView(AdaptiveLayout.centered(this,content,1200,null));
        LinearLayout top=row();top.setPadding(inset,0,inset,0);top.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        String[] navIcons={"inbox","settings"};int[] navLabels={R.string.agent_inbox,R.string.settings};
        for(int i=0;i<navIcons.length;i++){final boolean inbox=i==0;View button=SampleUi.header(this,navIcons[i],tr(navLabels[i]),TEXT,()->startActivity(new Intent(this,inbox?AgentActivity.class:SettingsActivity.class)));button.setBackground(SampleUi.touch(this,0xff111713,22));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(44),dp(44));lp.leftMargin=dp(8);top.addView(button,lp);}
        homePage.addView(AdaptiveLayout.centered(this,top,1200,null),new LinearLayout.LayoutParams(-1,dp(56)));
        homePage.addView(home,new LinearLayout.LayoutParams(-1,0,1));
        AdaptiveLayout.Columns homeColumns=new AdaptiveLayout.Columns(this,840,24);content.addView(homeColumns,new LinearLayout.LayoutParams(-1,-2));
        overview=new SessionOverview(this,stage,homePage,new SessionOverview.Actions(){
            public void open(String id){openTab(id);}public void close(String id){closeTab(id);}public void closeAll(){closeAllTabs();}
            public void create(){chooseConnection();}
            public void move(String id,int index){if(sshService!=null)sshService.move(id,index);}
        });homeColumns.addView(overview.view());
        LinearLayout connections=column();homeColumns.addView(connections);
        LinearLayout connectionHeading=row();connectionHeading.setMinimumHeight(dp(36));TextView savedTitle=SampleUi.sectionTitle(this,tr(R.string.connections));connectionHeading.addView(savedTitle,new LinearLayout.LayoutParams(0,-2,1));hookAttention=label("",13,0xffbd9036);hookAttention.setOnClickListener(v->startActivity(SettingsNavigation.intent(this,"hook")));connectionHeading.addView(hookAttention);LinearLayout.LayoutParams connectionHeaderParams=new LinearLayout.LayoutParams(-1,-2);connections.addView(connectionHeading,connectionHeaderParams);
        savedConnections=column();connections.addView(savedConnections);refreshSavedConnections();
        guideShown=getSharedPreferences("appearance",0).getBoolean("guide",true);if(guideShown){
            LinearLayout guideHeader=row();guideHeader.setMinimumHeight(dp(36));TextView heading=SampleUi.sectionTitle(this,tr(R.string.discover_shello));guideHeader.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
            android.content.SharedPreferences visited=getSharedPreferences("guide",0);TextView progress=label("",13,0xff00bc65);progress.setIncludeFontPadding(false);guideHeader.addView(progress);
            LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=dp(16);content.addView(guideHeader,hp);
            String[] names={tr(R.string.qr_pairing),tr(R.string.remote_sessions),tr(R.string.inbox_and_approvals),tr(R.string.terminal_theme),tr(R.string.shortcuts),tr(R.string.text_selection)};String[] icons={"qr","server","inbox","settings","keyboard","pad"};
            Runnable update=()->{int count=0;for(int i=0;i<names.length;i++)if(visited.getBoolean("viewed_"+i,false))count++;progress.setText(count+"/"+names.length);};update.run();
            HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);LinearLayout cards=row();scroll.addView(cards);content.addView(scroll);
            for(int i=0;i<names.length;i++){final int index=i;LinearLayout card=column();card.setGravity(Gravity.CENTER);card.setBackground(SampleUi.touch(this,0xff191b1d,16));card.setContentDescription(names[i]);card.addView(SampleUi.icon(this,icons[i],0xff00ee00),new LinearLayout.LayoutParams(dp(26),dp(26)));TextView text=label(names[i],13,MUTED);text.setGravity(Gravity.CENTER);text.setPadding(0,dp(10),0,0);card.addView(text);
                card.setOnClickListener(v->{visited.edit().putBoolean("viewed_"+index,true).apply();update.run();switch(index){case 0:startActivityForResult(new Intent(this,PairingActivity.class),74);break;case 1:infoDialog=SampleUi.confirm(this,tr(R.string.remote_sessions),tr(R.string.tap_a_saved_connection_to_choose_or_create_a_remote_tmux_session),null,tr(R.string.close),tr(R.string.got_it),false,null,null);break;case 2:startActivity(new Intent(this,AgentActivity.class));break;default:startActivity(SettingsNavigation.intent(this,index==3?"theme":index==4?"shortcuts":"gestures"));}});
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(112),dp(100));cp.rightMargin=dp(8);cards.addView(card,cp);}
        }
        stage.addView(homePage,new FrameLayout.LayoutParams(-1,-1));homePage.setVisibility(View.GONE);if(addConnection!=null)addConnection.setVisibility(View.GONE);
        addConnection=SampleUi.header(this,"plus",tr(R.string.new_ssh_connection),Color.BLACK,this::chooseConnection);addConnection.setBackground(SampleUi.touch(this,0xff00ef00,30));FrameLayout.LayoutParams fab=new FrameLayout.LayoutParams(dp(60),dp(60),Gravity.RIGHT|Gravity.BOTTOM);fab.setMargins(0,0,dp(24),dp(24));stage.addView(addConnection,fab);addConnection.setVisibility(View.GONE);
    }
    private final class HomeBackdrop extends android.graphics.drawable.Drawable {
        final android.graphics.Paint paint=new android.graphics.Paint();
        @Override public void draw(android.graphics.Canvas canvas){android.graphics.Rect b=getBounds();canvas.drawColor(Color.BLACK);paint.setShader(new android.graphics.RadialGradient(b.width()*.5f,0,b.width()*.8f,0xff041104,Color.TRANSPARENT,android.graphics.Shader.TileMode.CLAMP));canvas.drawRect(b,paint);paint.setShader(new android.graphics.RadialGradient(b.right,b.bottom,b.width()*.65f,0xff032003,Color.TRANSPARENT,android.graphics.Shader.TileMode.CLAMP));canvas.drawRect(b,paint);}
        @Override public void setAlpha(int a){}@Override public void setColorFilter(android.graphics.ColorFilter f){}@Override public int getOpacity(){return android.graphics.PixelFormat.OPAQUE;}
    }
    private void chooseConnection(){
        newConnectionDialog=new android.app.Dialog(this);LinearLayout panel=column();panel.setPadding(dp(20),dp(20),dp(20),dp(28));panel.setBackground(round(SampleUi.PANEL,28));
        AdaptiveLayout.Columns choices=new AdaptiveLayout.Columns(this,600,12);panel.addView(choices,new LinearLayout.LayoutParams(-1,-2));
        for(int i=0;i<2;i++){final boolean pair=i==0;LinearLayout card=column();card.setPadding(dp(22),dp(22),dp(22),dp(22));card.setBackground(SampleUi.touch(this,pair?0xff122c14:KEY,20));card.setContentDescription(pair?tr(R.string.pair_with_a_qr_code):tr(R.string.use_your_own_ssh));
            card.addView(label(pair?tr(R.string.quickest):tr(R.string.know_ssh),12,pair?0xff00ef00:MUTED));TextView title=label(pair?tr(R.string.pair_with_a_qr_code):tr(R.string.use_your_own_ssh),22,TEXT);title.setPadding(0,dp(10),0,dp(10));title.setTypeface(null,Typeface.BOLD);card.addView(title);
            card.addView(label(pair?tr(R.string.run_one_command_on_mac_or_linux_and_scan_its_qr_code_shello_insta):tr(R.string.enter_a_host_username_and_key_or_password_keep_remote_sessions_wi),15,MUTED));TextView hint=label(pair?tr(R.string.recommended_about_1_minute):tr(R.string.about_3_minutes_ssh_credentials_needed),12,pair?0xff00ef00:MUTED);hint.setPadding(0,dp(16),0,0);card.addView(hint);
            card.setOnClickListener(v->{newConnectionDialog.dismiss();startActivityForResult(new Intent(this,pair?PairingActivity.class:SshDiscoveryActivity.class),pair?74:73);});LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);choices.addView(card,cp);}
        newConnectionDialog.setContentView(AdaptiveLayout.sheetScroll(this,panel));newConnectionDialog.show();AdaptiveLayout.sheetWindow(this,newConnectionDialog,800,0);
    }
    private void chooseSession(String profile){sessionPickerProfile=profile;rememberTab();if(tmuxDialog!=null)tmuxDialog.dismiss();connectingProfile=profile;refreshSavedConnections();tmuxDialog=TmuxPicker.show(this,profile,id->{pendingProfile=id;connectSsh();},()->{connectingProfile=null;if(!isDestroyed())refreshSavedConnections();});}
    private void refreshSavedConnections(){
        if(savedConnections==null)return;savedConnections.removeAllViews();SshProfileStore store=new SshProfileStore(this);AgentHub hub=AgentHub.get(this);
        for(SshProfileStore.Entry entry:store.list()){
            LinearLayout card=row();card.setPadding(dp(16),dp(12),dp(8),dp(12));card.setBackground(round(KEY,16));
            FrameLayout glyph=new FrameLayout(this);glyph.addView(SampleUi.icon(this,"server",MUTED),new FrameLayout.LayoutParams(dp(26),dp(26),Gravity.CENTER));
            if(getSharedPreferences("appearance",0).getBoolean("hookStatus",true)){View dot=new View(this);dot.setContentDescription(hub.enabled(entry.id)?"Hook "+tr(hub.states.getOrDefault(entry.id,R.string.waiting_to_sync)):tr(R.string.hook_not_installed));dot.setBackground(round(hub.pending(entry.id)>0?0xffffc33d:hub.enabled(entry.id)&&Integer.valueOf(R.string.connected).equals(hub.states.get(entry.id))?GREEN:0xffbd9036,4));FrameLayout.LayoutParams d=new FrameLayout.LayoutParams(dp(8),dp(8),Gravity.TOP|Gravity.RIGHT);d.topMargin=dp(10);glyph.addView(dot,d);}
            LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(dp(32),dp(48));gp.rightMargin=dp(16);card.addView(glyph,gp);
            LinearLayout labels=column();TextView name=label(entry.name,20,TEXT);name.setTypeface(null,Typeface.BOLD);name.setSingleLine();name.setEllipsize(TextUtils.TruncateAt.END);labels.addView(name);TextView endpoint=label(entry.id.equals(connectingProfile)?tr(R.string.connecting):entry.user+"@"+entry.host+":"+entry.port,16,MUTED);endpoint.setTypeface(Typeface.MONOSPACE);endpoint.setSingleLine();endpoint.setEllipsize(TextUtils.TruncateAt.END);labels.addView(endpoint);card.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            card.setContentDescription(tr(R.string.connect_over_ssh_to_name,entry.name));card.setFocusable(true);card.setOnClickListener(v->chooseSession(entry.id));
            card.addView(SampleUi.icon(this,"right",MUTED),new LinearLayout.LayoutParams(dp(24),dp(24)));card.setOnLongClickListener(v->{showConnectionMenu(card,entry);return true;});LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(86));cp.bottomMargin=dp(12);savedConnections.addView(card,cp);
        }
        if(hookAttention!=null){int needs=0;for(SshProfileStore.Entry e:store.list())if(!hub.enabled(e.id)||hub.pending(e.id)>0||!Integer.valueOf(R.string.connected).equals(hub.states.get(e.id)))needs++;boolean show=getSharedPreferences("appearance",0).getBoolean("hookStatus",true);hookAttention.setText(needs>0&&show?"● "+needs:"");hookAttention.setContentDescription(AppLanguage.quantity(R.plurals.hook_attention_count,needs,needs));}
        if(savedConnections.getChildCount()==0)savedConnections.addView(HomeEmptyState.card(this,false,this::chooseConnection),new LinearLayout.LayoutParams(-1,-2));
    }
    private void showConnectionMenu(View anchor,SshProfileStore.Entry entry){
        showMenu(anchor,java.util.Arrays.asList(
                new SampleUi.Action(tr(R.string.edit),tr(R.string.edit_connection_179_name,entry.name),"edit",true,false,()->startActivityForResult(new Intent(this,SshDiscoveryActivity.class).putExtra("editProfile",entry.id),73)),
                new SampleUi.Action(tr(R.string.delete),tr(R.string.delete_connection_name,entry.name),"trash",true,true,()->deleteConnection(entry))));
    }
    private void deleteConnection(SshProfileStore.Entry entry){
        SshProfileStore store=new SshProfileStore(this);
        connectionDeleteDialog=SampleUi.confirm(this,tr(R.string.delete_connection_182),tr(R.string.delete_connection_body,entry.name),null,tr(R.string.cancel),tr(R.string.delete),true,null,()->{
                    try{if(sshService!=null)sshService.closeProfile(entry.id);
                        store.delete(entry.id);refreshSavedConnections();}catch(Exception error){toast(tr(R.string.deletion_failed_try_again));}
                });
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if((request==73||request==74)&&result==RESULT_OK&&data!=null){String profile=data.getStringExtra("profile");if(profile!=null){pendingProfile=null;chooseSession(profile);}}
    }
    private void rememberTab(){
        if(shownTab==null)return;
        shownTab.draft=draft.getText().toString();shownTab.draftMode=draftMode;
        if(!minimized&&sheet.isShown()&&terminal.getWidth()>0&&terminal.getHeight()>0&&session!=null&&sshService!=null){
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(480,480,android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);canvas.drawColor(SURFACE);
            float scale=480f/terminal.getWidth();canvas.scale(scale,scale);terminal.draw(canvas);
            // Existing ImageViews can still reference the previous preview until the next layout.
            sshService.rememberPreview(shownTab,bitmap);
        }
    }
    private void minimize() {
        rememberTab();hideKeyboard();setCtrl(false);minimized=true;sheet.setVisibility(View.INVISIBLE);homePage.setVisibility(View.VISIBLE);if(addConnection!=null)addConnection.setVisibility(View.VISIBLE);
        if(overview!=null&&sshService!=null)overview.refresh(sshService.tabs());
    }
    private void openTab(String id){
        if(sshService==null)return;rememberTab();sshService.select(id);
        SshSessionService.Tab tab=sshService.selected();
        if(tab!=null&&tab.remote()&&!tab.connection.foreground){pendingProfile="fixture".equals(id)?null:id;connectSsh();}
        else resumeSession();
    }
    private void closeTab(String id){
        if(sshService==null)return;
        if(shownTab!=null&&shownTab.id.equals(id)){shownTab=null;draft.setText("");}
        sshService.close(id);minimize();
    }
    private void closeAllTabs(){
        if(sshService==null)return;shownTab=null;draft.setText("");sshService.closeAll();minimize();
    }
    private void resumeSession(){minimized=false;homePage.setVisibility(View.GONE);if(addConnection!=null)addConnection.setVisibility(View.GONE);sheet.setVisibility(View.VISIBLE);root.requestFocus();}
    @Override public void onBackPressed() {
        if(overview!=null&&overview.back())return;
        if(minimized){super.onBackPressed();return;}
        if(pad.getVisibility()==View.VISIBLE){togglePad();return;}
        if(!terminal.getSelectedText().isEmpty()){terminal.clearSelection();return;}
        if(draftMode){setDraftMode(false,false);hideKeyboard();return;}
        minimize();
    }
    private void setDraftMode(boolean enabled,boolean keyboard) {
        setCtrl(false);draftMode=enabled;
        ((View)toolbar.getParent()).setVisibility(enabled?View.GONE:View.VISIBLE);
        composer.setVisibility(enabled?View.VISIBLE:View.GONE);
        pad.setVisibility(View.GONE);padButton.setActivated(false);
        if(keyboard)showKeyboard();
    }
    private void showKeyboard() {
        View target=draftMode?draft:directInput;target.requestFocus();
        target.post(()->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(target,InputMethodManager.SHOW_IMPLICIT));
    }
    private void hideKeyboard() {
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);root.requestFocus();
    }
    private void toggleKeyboard(){if(imeVisible)hideKeyboard();else showKeyboard();}
    private void setCtrl(boolean enabled){ctrlArmed=enabled;ctrlButton.setActivated(enabled);ctrlButton.setTextColor(enabled?GREEN:TEXT);}
    private void sendKey(TerminalSession.Key key){terminal.sendKey(key);}
    private void sendDraft(boolean enter) {
        String text=draft.getText().toString();if(text.isEmpty())return;
        if(sshMode&&!sshConnected){toast(tr(R.string.ssh_is_disconnected_draft_kept));return;}
        draftHistory.remove(text);draftHistory.add(0,text);if(draftHistory.size()>20)draftHistory.remove(20);
        // Explicit submission finishes this local draft; it is never sent during composition.
        terminal.sendText(text);if(enter)terminal.sendKey(TerminalSession.Key.ENTER);
        draft.setText("");
    }
    private void paste() {
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(!clipboard.hasPrimaryClip()||clipboard.getPrimaryClip()==null)return;
        CharSequence text=clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        setDraftMode(true,true);int start=Math.max(0,draft.getSelectionStart()),end=Math.max(0,draft.getSelectionEnd());
        draft.getText().replace(Math.min(start,end),Math.max(start,end),text);
    }
    private void copySelection() {
        String text=terminal.getSelectedText();if(text.isEmpty())return;
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(tr(R.string.terminal),text));
        terminal.clearSelection();toast(tr(R.string.copied));
    }
    private void tools(View anchor) {
        String[] titles={tr(R.string.scroll_to_bottom),tr(R.string.unicode_demo),tr(R.string.scrolling_demo),tr(R.string.save_local_snapshot),tr(R.string.restore_local_snapshot),sshMode?tr(R.string.reconnect_ssh):tr(R.string.connect_ssh),tr(R.string.disconnect_ssh),tr(R.string.return_to_offline_demo),tr(R.string.notification_settings),tr(R.string.background_settings),tr(R.string.new_connection_lan_discovery)};
        String[] symbols={"down","chat","panel","paste","history","server","close","return","settings","settings","plus"};
        java.util.List<SampleUi.Action> actions=new java.util.ArrayList<>();
        for(int i=0;i<titles.length;i++){
            final int id=i;boolean enabled=true;
            if(i>=1&&i<=3)enabled=!sshMode&&session!=null;
            if(i==4)enabled=checkpoint!=null&&!sshMode&&session!=null;
            if(i==6)enabled=sshService!=null&&sshService.active();
            if(i==7)enabled=sshMode||session==null;
            actions.add(new SampleUi.Action(titles[i],symbols[i],enabled,i==6,()->runTool(id)));
        }
        showMenu(anchor,actions);
    }
    private void runTool(int id){
            switch(id){
                case 5:connectSsh();break;
                case 10:startActivityForResult(new Intent(this,SshDiscoveryActivity.class),73);break;
                case 8:startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()));break;
                case 9:infoDialog=SampleUi.confirm(this,tr(R.string.background_sessions_and_battery_limits),
                        tr(R.string.system_battery_policies_may_pause_networking_or_close_the_app_a_f)
                        +tr(R.string.for_background_connectivity_review_battery_policies_in_app_info_s)
                        +tr(R.string.when_networking_returns_or_the_app_reopens_shello_reconnects_to_t),null,
                        tr(R.string.close),tr(R.string.open_app_info),false,null,()->startActivity(new Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:"+getPackageName()))));break;
                case 6:disconnectSsh();break;
                case 7:returnOffline();sshMode=false;subtitle.setText(tr(R.string.local_terminal));protocolBadge.setText(tr(R.string.local));demo();break;
                case 0:terminal.scrollToBottom();break;
                case 1:demo();break;
                case 2:for(int i=1;i<=180;i++)write(String.format(java.util.Locale.ROOT,tr(R.string.unicode_scrollback_line),i));break;
                case 3:checkpoint=session.checkpoint();toast(tr(R.string.local_snapshot_saved));break;
                case 4:TerminalSession restored=TerminalSession.restore(checkpoint);terminal.setSession(null);session.close();session=restored;if(shownTab!=null&&!shownTab.remote())shownTab.local=restored;terminal.setSession(session);toast(tr(R.string.local_snapshot_restored));break;
                default:break;
            }
    }
    @Override protected void onStart(){
        super.onStart();
        agentHub=AgentHub.get(this);agentHub.attach(agentChanged);
        bound=bindService(new Intent(this,SshSessionService.class),serviceConnection,BIND_AUTO_CREATE);
    }
    @Override protected void onResume(){
        super.onResume();previewHandler.removeCallbacks(previewCheckpoint);previewHandler.postDelayed(previewCheckpoint,5000);if(home!=null&&guideShown!=getSharedPreferences("appearance",0).getBoolean("guide",true)){overview.dismiss();stage.removeView(homePage);stage.removeView(addConnection);buildHome();if(minimized)minimize();}refreshSavedConnections();applyAppearance();
        if(sshService!=null){sshService.refreshNotification();updateSshView();}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==41&&sshService!=null){sshService.refreshNotification();updateSshView();}
    }
    @Override protected void onPause(){
        previewHandler.removeCallbacks(previewCheckpoint);rememberTab();super.onPause();
    }
    @Override protected void onStop(){
        if(tmuxDialog!=null&&!tmuxDialog.isShowing())tmuxDialog.dismiss();
        if(agentHub!=null)agentHub.detach(agentChanged);
        if(connectionMenu!=null)connectionMenu.dismiss();
        if(overview!=null)overview.dismiss();rememberTab();
        if(sshService!=null)sshService.removeObserver(serviceObserver);
        detachServiceView();sshService=null;sshConnected=false;
        if(bound){unbindService(serviceConnection);bound=false;}
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        state.putBoolean("newConnectionShowing",newConnectionDialog!=null&&newConnectionDialog.isShowing());
        if(tmuxDialog!=null&&tmuxDialog.isShowing())state.putString("sessionPickerProfile",sessionPickerProfile);
        state.putString("draftTab",shownTab==null?null:shownTab.id);state.putBoolean("keyboard",imeVisible&&(draft.hasFocus()||directInput.hasFocus()));state.putBoolean("minimized",minimized);state.putString("pendingProfile",pendingProfile);state.putString("draft",draft.getText().toString());state.putBoolean("draftMode",draftMode);
        super.onSaveInstanceState(state);
    }
    private void updateSshView(){
        if(destroyed||sshService==null)return;
        SshSessionService.Tab next=sshService.selected();
        TerminalSession remote=sshService.terminal();
        if(restoredDraftTab!=null){
            if(next!=null&&restoredDraftTab.equals(next.id)){next.draft=restoredDraft;next.draftMode=restoredDraftMode;}
            restoredDraftTab=null;restoredDraft=null;
        }
        if(next!=shownTab){
            if(shownTab!=null){shownTab.draft=draft.getText().toString();shownTab.draftMode=draftMode;}
            shownTab=next;draft.setText(next==null?"":next.draft);setDraftMode(next!=null&&(next.draftMode||getSharedPreferences("appearance",0).getBoolean("draftDefault",false)),false);
        }
        if(session!=remote){
            terminal.setSession(null);if(session!=null&&!serviceSession)session.close();
            session=remote;serviceSession=remote!=null;terminal.setSession(session);appliedTheme=null;
        }
        sshMode=next!=null&&next.remote();sshConnected=sshService.connected();
        protocolBadge.setText(sshMode?"SSH":tr(R.string.local));
        subtitle.setText(sshMode?sshService.profileName()+" · "+sshService.status()
                +(sshService.active()&&!notificationsVisible()?tr(R.string.notifications_off):""):tr(R.string.local_terminal));
        applyAppearance();
        if(overview!=null)overview.refresh(sshService.tabs());
        if(next==null&&!minimized)minimize();
    }
    private void applyAppearance(){
        if(terminal==null)return;android.content.SharedPreferences prefs=getSharedPreferences("appearance",0);terminal.setFontSize(prefs.getInt("fontSize",14));terminal.setSelectionEnabled(prefs.getBoolean("selection",true));
        for(int i=0;i<toolbar.getChildCount();i++){View tool=toolbar.getChildAt(i);Object tag=tool.getTag();if(tag instanceof String&&java.util.Arrays.asList("history","paste","chat","pad").contains(tag))tool.setVisibility(prefs.getBoolean("tool_"+tag,true)?View.VISIBLE:View.GONE);}
        String theme=ThemePreferences.current(prefs);if(session!=null&&!theme.equals(appliedTheme)){appliedTheme=theme;String fg=theme.equals("light")?"#202124":"#d4d4d4",bg=theme.equals("light")?"#f5f5f5":theme.equals("black")?"#000000":"#1e1e1e";session.write(("\033]10;"+fg+"\007\033]11;"+bg+"\007").getBytes(StandardCharsets.UTF_8));}
    }
    private void detachServiceView(){
        if(serviceSession){terminal.setSession(null);session=null;serviceSession=false;}
    }
    private boolean notificationsVisible(){
        android.app.NotificationManager manager=getSystemService(android.app.NotificationManager.class);
        android.app.NotificationChannel channel=manager.getNotificationChannel("ssh_session");
        return manager.areNotificationsEnabled()&&(channel==null||channel.getImportance()!=android.app.NotificationManager.IMPORTANCE_NONE);
    }
    private void connectSsh(){connectSsh(true);}
    private void connectSsh(boolean requestNotification){
        if(sshService==null){connectOnBind=true;return;}
        if(pendingProfile==null&&!SshProfileStore.exists(new SshProfileStore(this).activeDirectory())){
            startActivityForResult(new Intent(this,SshDiscoveryActivity.class),73);return;
        }
        if(requestNotification&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},41);
        // Permission denial does not prevent an SSH foreground service; automatic recovery never re-prompts.
        String rejected=SshSessionService.requestConnect(this,pendingProfile,!requestNotification);
        if(rejected==null){pendingProfile=null;if(requestNotification||!minimized)resumeSession();}
        if(rejected!=null){subtitle.setText(tr(R.string.local_cannot_start_a_session_right_now));toast(tr(R.string.return_to_the_app_and_reconnect_ssh));}
    }
    private void disconnectSsh(){if(sshService!=null)sshService.disconnect();}
    private void returnOffline(){
        if(sshService!=null){rememberTab();sshService.openLocal();resumeSession();}
    }
    private void sendInput(byte[] bytes) {
        String text=new String(bytes,StandardCharsets.UTF_8);
        if(ctrlArmed){setCtrl(false);if(text.length()==1&&text.charAt(0)>=64&&text.charAt(0)<128)text=Character.toString((char)(text.charAt(0)&31));}
        if(sshMode){if(sshConnected&&sshService!=null)sshService.send(text.getBytes(StandardCharsets.UTF_8));else toast(tr(R.string.ssh_disconnected));return;}
        if(text.equals("\r")||text.equals("\n")){write("\r\n");return;}
        if(text.equals("\f")){write("\033[2J\033[H");return;}
        if(text.equals("\u007f")||text.equals("\b")){toast(tr(R.string.backspace));return;}
        if(text.codePoints().anyMatch(cp->cp<32&&cp!='\n'&&cp!='\t')){
            String display=text.replace("\033","Esc ");
            if(text.length()==1&&text.charAt(0)<27)display="Ctrl+"+(char)(text.charAt(0)+64);
            toast(tr(R.string.key_feedback,display));return;
        }
        write(text.replace("\n","\r\n"));
    }
    private void demo() {
        write("\033c\033]10;#d4d4d4\007\033]11;#1e1e1e\007\033]12;#00d86c\007"
                +"\033[1;32mShello\033[0m\r\n\r\n"
                +tr(R.string.local_demo_intro)
                +tr(R.string.local_demo_gestures)
                +"\033[?2027h👨‍👩‍👧‍👦  👍🏽  🇨🇳  1️⃣  👩‍❤️‍👩\r\n"
                +"🧑🏽‍🚀  👩🏻‍🤝‍👨🏿  é\r\n\r\n"
                +tr(R.string.local_demo_keys)
                +tr(R.string.local_demo_help));
    }
    @android.annotation.TargetApi(30) private void applyModernInsets() {
        root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));
            imeVisible=insets.isVisible(WindowInsets.Type.ime());return insets;
        });
    }
    private void write(String text){if(session!=null)session.write(text.getBytes(StandardCharsets.UTF_8));}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView label(String text,int size,int color){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);return v;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView key(String title,String description,Runnable action) {
        TextView v=new ControlLabel();v.setText(title);v.setTextSize(14);v.setTextColor(TEXT);v.setGravity(Gravity.CENTER);v.setTypeface(Typeface.MONOSPACE);
        v.setSingleLine();v.setEllipsize(TextUtils.TruncateAt.END);v.setContentDescription(description);
        v.setFocusable(true);v.setBackground(new RippleDrawable(ColorStateList.valueOf(0xff35433c),round(KEY,22),null));
        v.setOnClickListener(w->action.run());return v;
    }
    private TextView icon(String name,String description,Runnable action) {
        TextView v=key("",description,action);v.setTag(name);((ControlLabel)v).setIcon(new ToolbarIcon(name,dp(20),TEXT));
        return v;
    }
    /** Center icons independently of font ascent/descent, including the small header buttons. */
    private final class ControlLabel extends TextView {
        private ToolbarIcon symbol;
        ControlLabel(){super(MainActivity.this);setIncludeFontPadding(false);}
        void setIcon(ToolbarIcon icon){symbol=icon;invalidate();}
        @Override protected void onDraw(android.graphics.Canvas canvas){
            android.graphics.drawable.Drawable icon=symbol;
            if(icon==null){super.onDraw(canvas);return;}
            int w=icon.getIntrinsicWidth(),h=icon.getIntrinsicHeight();
            canvas.save();canvas.translate(getScrollX(),getScrollY());
            icon.setBounds((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);icon.draw(canvas);canvas.restore();
        }
    }
    @Override protected void onDestroy(){
        if(newConnectionDialog!=null)newConnectionDialog.dismiss();if(tmuxDialog!=null)tmuxDialog.dismiss();
        if(connectionDeleteDialog!=null)connectionDeleteDialog.dismiss();if(infoDialog!=null)infoDialog.dismiss();
        destroyed=true;
        directInput.setTerminalView(null);terminal.setListener(null);terminal.setSession(null);
        if(session!=null&&!serviceSession)session.close();
        if(overview!=null)overview.dismiss();
        super.onDestroy();
    }
}
