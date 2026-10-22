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
    private TerminalControls inputControls;
    private LinearLayout configuredComposer;
    private boolean padFolded;
    private final java.util.List<TextView> padCorners=new java.util.ArrayList<>();
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
            uploadPickedAttachment();
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
    private LinearLayout terminalHeader;
    private EditText draft;
    private LinearLayout root, sheet, composer, pad, selectionBar, savedConnections;
    private String pendingProfile,restoredDraftTab,restoredDraft;
    private boolean restoredDraftMode;
    private int restoredDraftSelectionStart=-1,restoredDraftSelectionEnd=-1;
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
    private TextView subtitle, lockButton, sendButton;
    private final java.util.ArrayList<String> draftHistory=new java.util.ArrayList<>();
    private boolean padPositioned;
    private HorizontalScrollView toolScroll;
    private InputToolbar toolbarContainer;
    private LinearLayout toolbar;
    private LinearLayout composerActions,draftPanel;
    private ComposerBody composerBody;
    private TextView addTextButton,attachmentStatus;
    private String attachmentTab;
    private android.app.Dialog attachmentSheet,attachmentPreview;
    private LinearLayout attachmentCards;
    private HorizontalScrollView attachmentScroll;
    private android.net.Uri cameraUri;
    private static final int TAKE_ATTACHMENT=76,CAMERA_PERMISSION=77;
    private android.net.Uri attachmentUri;
    private static final int PICK_ATTACHMENT=75;
    private boolean draftMode, imeVisible, minimized, padLocked;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        UiTheme.window(this,getWindow());

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        root=column(); root.setBackgroundColor(UiTheme.of(this).canvas);
        root.setFocusableInTouchMode(true);
        configureInsets();
        setContentView(root);
        stage=new FrameLayout(this); root.addView(stage,new LinearLayout.LayoutParams(-1,-1));
        sheet=column(); sheet.setBackground(round(SURFACE,24)); sheet.setClipToOutline(true);
        stage.addView(sheet,new FrameLayout.LayoutParams(-1,-1));
        buildHeader();
        viewport=new FrameLayout(this);
        sheet.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        terminal=new TerminalView(this); terminal.setBackgroundColor(uiTheme().light?0xfff5f5f5:SURFACE);
        FrameLayout.LayoutParams grid=new FrameLayout.LayoutParams(-1,-1);
        grid.setMargins(dp(8),dp(4),dp(8),0); viewport.addView(terminal,grid);
        buildSelectionBar(); buildPad(); buildToolbar(); buildComposer();
        // A real EditText remains the IME target in direct mode; candidates stay in the IME.
        directInput=new TerminalInputView(this); directInput.setTerminalView(terminal);
        directInput.setAlpha(0); directInput.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sheet.addView(directInput,new LinearLayout.LayoutParams(1,1));
        inputControls=new TerminalControls(this,terminal,directInput,sheet,toolbarContainer,configuredComposer,viewport,new TerminalControls.Host(){
            public void action(String id){controlAction(id);}
            public void composer(boolean open){setDraftMode(open,true);}
            public void session(int index,boolean relative){switchSession(index,relative);}
            public boolean active(){return !destroyed&&!minimized;}
            public boolean keyboardVisible(){return imeVisible;}
        });
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
            // A landscape IME can leave less than 52dp for the terminal/pad.
            // Keep full-size controls and recover the decorative/session header space.
            boolean shortLandscape=r-l>b-t&&b-t<dp(240);
            int headerVisibility=shortLandscape?View.GONE:View.VISIBLE;
            if(terminalHeader.getVisibility()!=headerVisibility)terminalHeader.setVisibility(headerVisibility);
            int toolWidth=Math.min(dp(600),Math.max(0,r-l-dp(16)));
            LinearLayout.LayoutParams tools=(LinearLayout.LayoutParams)toolbarContainer.getLayoutParams();
            if(tools.width!=toolWidth){tools.width=toolWidth;tools.gravity=Gravity.CENTER_HORIZONTAL;toolbarContainer.setLayoutParams(tools);}
            int composerWidth=Math.min(dp(800),Math.max(0,r-l-dp(16)));
            LinearLayout.LayoutParams cp=(LinearLayout.LayoutParams)composer.getLayoutParams();
            if(cp.width!=composerWidth){cp.width=composerWidth;cp.gravity=Gravity.CENTER_HORIZONTAL;composer.setLayoutParams(cp);}
            boolean compact=b-t<dp(400)&&composerWidth>=dp(600);
            if(composer.getOrientation()!=(compact?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL)){
                composer.setOrientation(compact?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);composer.setGravity(Gravity.CENTER_VERTICAL);
                composerBody.setLayoutParams(new LinearLayout.LayoutParams(compact?0:-1,-2,compact?1:0));
                composerActions.setLayoutParams(new LinearLayout.LayoutParams(compact?dp(272):-1,dp(64)));
            }
            int maxLines=compact?2:4;if(draft.getMaxLines()!=maxLines)draft.setMaxLines(maxLines);
            int bodyBudget=b-t-dp(compact?90:154);
            composerBody.maximumHeight(bodyBudget-(bodyBudget>=dp(128)?dp(48):0));
        });
        write(tr(R.string.local_demo_banner)); root.requestFocus();
        pendingProfile=state==null?takeAgentTab():state.getString("pendingProfile");
        if(state!=null){String camera=state.getString("cameraUri");if(camera!=null)cameraUri=android.net.Uri.parse(camera);attachmentTab=state.getString("attachmentTab");String uri=state.getString("attachmentUri");if(uri!=null)attachmentUri=android.net.Uri.parse(uri);}
        if(state!=null){restoredDraftTab=state.getString("draftTab");restoredDraft=state.getString("draft","");restoredDraftMode=state.getBoolean("draftMode");restoredDraftSelectionStart=state.getInt("draftSelectionStart",-1);restoredDraftSelectionEnd=state.getInt("draftSelectionEnd",-1);}
        boolean legacyFixture=state==null&&BuildConfig.DEBUG&&getIntent().getBooleanExtra("connectLocalSsh",false);
        // Explicit debug fixture launches select files/ssh without deleting saved UI profiles.
        if(legacyFixture)getSharedPreferences("ssh_profiles",0).edit().remove("active").commit();
        connectOnBind=pendingProfile!=null||legacyFixture;
        if(state==null&&!connectOnBind)minimize();
        if(state!=null){restoreDraft(state.getString("draft",""),restoredDraftSelectionStart,restoredDraftSelectionEnd);setDraftMode(state.getBoolean("draftMode"),false);if(state.getBoolean("minimized"))minimize();else if(state.getBoolean("keyboard"))root.post(this::showKeyboard);
            if(state.getBoolean("newConnectionShowing"))root.post(this::chooseConnection);
            String picker=state.getString("sessionPickerProfile");if(picker!=null)root.post(()->chooseSession(picker));}
    }

    private void buildHeader() {
        terminalHeader=column();sheet.addView(terminalHeader,new LinearLayout.LayoutParams(-1,-2));
        View grip=new View(this); grip.setBackground(round(0xff5a5b60,3));
        LinearLayout.LayoutParams gripParams=new LinearLayout.LayoutParams(dp(44),dp(3));
        gripParams.gravity=Gravity.CENTER_HORIZONTAL;gripParams.topMargin=dp(8);terminalHeader.addView(grip,gripParams);
        LinearLayout header=row(); header.setPadding(dp(16),0,dp(16),0);
        terminalHeader.addView(header,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView minimize=key("−",tr(R.string.minimize_session),this::minimize); minimize.setTextColor(UiTheme.foreground(MainActivity.this,Color.BLACK));
        minimize.setTextSize(14);
        minimize.setBackground(new android.graphics.drawable.InsetDrawable(round(0xffffcc00,9),dp(14)));
        header.addView(minimize,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView menu=icon("panel",tr(R.string.session_tools),()->tools(header));
        ((ControlLabel)menu).setIcon(new ToolbarIcon(MainActivity.this,"panel",dp(11),uiTheme().onAccent));
        menu.setBackground(new android.graphics.drawable.InsetDrawable(round(GREEN,9),dp(14)));
        header.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48)));
        subtitle=label(tr(R.string.local_terminal),12,MUTED);subtitle.setTypeface(Typeface.MONOSPACE);
        subtitle.setSingleLine();subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setPadding(dp(8),0,dp(8),0);
        header.addView(subtitle,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=label(tr(R.string.local),10,0xff75b6ff);protocolBadge=badge;badge.setPadding(dp(5),dp(3),dp(5),dp(3));
        badge.setBackground(round(0xff173247,10)); header.addView(badge);
    }

    private void buildToolbar() {
        toolbarContainer=new InputToolbar(this);toolScroll=toolbarContainer.scroll;toolbar=toolbarContainer.scrollingButtons;
        LinearLayout.LayoutParams outer=new LinearLayout.LayoutParams(-1,dp(InputToolbar.HEIGHT_DP));
        outer.setMargins(dp(8),dp(10),dp(8),dp(12));sheet.addView(toolbarContainer,outer);
        toolbarContainer.setTag("toolbar");
    }
    private void buildComposer() {
        composer=column();composer.setPadding(dp(12),dp(8),dp(12),dp(8));
        composer.setBackground(round(Color.BLACK,26));composer.setVisibility(View.GONE);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(dp(8),dp(6),dp(8),dp(8));sheet.addView(composer,params);
        draft=new EditText(this);draft.setTextColor(UiTheme.foreground(MainActivity.this,TEXT));draft.setHintTextColor(UiTheme.foreground(MainActivity.this,MUTED));
        UiDesign.type(draft,UiDesign.Type.BODY);draft.setHint(tr(R.string.type_a_message));draft.setBackgroundColor(UiTheme.background(MainActivity.this,Color.TRANSPARENT));
        // Command drafts must not autocorrect printf/stty into different commands.
        draft.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        draft.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        draft.setMinLines(1);draft.setMaxLines(4);draft.setPadding(dp(8),dp(10),dp(8),dp(8));
        draft.setContentDescription(tr(R.string.local_text_draft));draftPanel=column();composerBody=new ComposerBody(this);composerBody.addView(draftPanel);composer.addView(composerBody,new LinearLayout.LayoutParams(-1,-2));attachmentScroll=new HorizontalScrollView(this);attachmentScroll.setHorizontalScrollBarEnabled(false);attachmentScroll.setVisibility(View.GONE);
        attachmentCards=row();attachmentCards.setPadding(dp(4),dp(4),dp(4),dp(4));attachmentScroll.addView(attachmentCards);draftPanel.addView(attachmentScroll,new LinearLayout.LayoutParams(-1,-2));draftPanel.addView(draft,new LinearLayout.LayoutParams(-1,-2));
        attachmentStatus=label("",12,MUTED);attachmentStatus.setPadding(dp(8),0,dp(8),dp(6));attachmentStatus.setVisibility(View.GONE);draftPanel.addView(attachmentStatus,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout actions=row();composerActions=actions;composer.addView(actions,new LinearLayout.LayoutParams(-1,dp(64)));
        HorizontalScrollView configured=new HorizontalScrollView(this);configured.setHorizontalScrollBarEnabled(false);configured.setHorizontalFadingEdgeEnabled(true);configured.setFadingEdgeLength(dp(12));configured.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout scrollActions=row();configured.addView(scrollActions);
        addTextButton=icon("plus",tr(R.string.add_attachment),this::addTextMenu);composerAction(scrollActions,addTextButton);
        configuredComposer=row();configuredComposer.setPadding(dp(4),0,dp(4),0);scrollActions.addView(configuredComposer,new LinearLayout.LayoutParams(-2,dp(48)));
        composerAction(scrollActions,icon("close",tr(R.string.close_editor_and_keep_draft),()->setDraftMode(false,true)));
        actions.addView(configured,new LinearLayout.LayoutParams(0,dp(48),1));
        View spacer=new View(this);actions.addView(spacer,new LinearLayout.LayoutParams(dp(8),1));
        TextView voice=icon("mic",AppLanguage.tr(R.string.input_dictation),()->controlAction("dictation"));composerAction(actions,voice);
        sendButton=icon("send",tr(R.string.send_and_press_enter_long_press_to_send_text_only),()->sendDraft(true));
        sendButton.setBackground(new android.graphics.drawable.InsetDrawable(round(0xff191b1e,18),dp(6)));sendButton.setEnabled(false);
        sendButton.setOnLongClickListener(v->{sendDraft(false);return true;});
        actions.addView(sendButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        draft.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                updateSendButton();
            }
            public void afterTextChanged(android.text.Editable e){}
        });
    }
    private void composerAction(LinearLayout actions,TextView button){
        button.setBackgroundColor(UiTheme.background(MainActivity.this,Color.TRANSPARENT));actions.addView(button,new LinearLayout.LayoutParams(dp(48),dp(48)));
    }
    private void showMenu(View anchor,java.util.List<SampleUi.Action> actions){
        if(connectionMenu!=null)connectionMenu.dismiss();connectionMenu=SampleUi.menu(this,anchor,actions);
    }
    private void addTextMenu(){
        if(attachmentSheet!=null)attachmentSheet.dismiss();hideKeyboard();
        attachmentSheet=AttachmentSourceSheet.show(this,this::chooseAttachmentSource);
    }
    private void chooseAttachment(){chooseAttachmentSource(2);}
    private void chooseAttachmentSource(int source){
        if(sshService==null||shownTab==null||!shownTab.remote()||!sshConnected){toast(tr(R.string.attachment_requires_ssh));return;}
        if(shownTab.attachments.size()>=SshSessionService.MAX_ATTACHMENTS){toast(tr(R.string.attachment_limit));return;}
        attachmentTab=shownTab.id;
        if(source==0){
            if(checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.CAMERA},CAMERA_PERMISSION);return;}
            launchAttachmentCamera();return;
        }
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if(source==1){
            if(Build.VERSION.SDK_INT>=33)pick=new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            else pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"});
        }
        try{startActivityForResult(pick,PICK_ATTACHMENT);}
        catch(ActivityNotFoundException|SecurityException error){
            if(source==1&&Build.VERSION.SDK_INT>=33){try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"}).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),PICK_ATTACHMENT);return;}catch(ActivityNotFoundException|SecurityException ignored){}}
            attachmentTab=null;toast(tr(R.string.file_picker_unavailable));
        }
    }
    private void launchAttachmentCamera(){
        try{
            cameraUri=AttachmentCameraProvider.create(this);
            Intent camera=new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT,cameraUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            camera.setClipData(ClipData.newRawUri("Camera output",cameraUri));
            startActivityForResult(camera,TAKE_ATTACHMENT);
        }catch(java.io.IOException|ActivityNotFoundException|SecurityException error){AttachmentCameraProvider.remove(this,cameraUri);cameraUri=null;attachmentTab=null;toast(tr(R.string.attachment_camera_unavailable));}
    }
    private void uploadPickedAttachment(){
        if(attachmentUri==null||sshService==null)return;
        android.net.Uri uri=attachmentUri;String tab=attachmentTab;attachmentUri=null;attachmentTab=null;
        if(tab==null||!sshService.startAttachment(tab,uri)){AttachmentCameraProvider.remove(this,uri);toast(tr(R.string.attachment_requires_ssh));}
    }
    private void updateSendButton(){
        if(sendButton==null)return;
        boolean ready=(draft.length()>0||shownTab!=null&&!shownTab.attachments.isEmpty())&&SshSessionService.attachmentsReady(shownTab)&&(!sshMode||sshConnected);
        sendButton.setEnabled(ready);sendButton.setBackground(new android.graphics.drawable.InsetDrawable(round(ready?GREEN:0xff191b1e,18),dp(6)));
        ((ControlLabel)sendButton).setIcon(new ToolbarIcon(MainActivity.this,"send",dp(20),ready?uiTheme().onAccent:MUTED));
    }
    private void updateAttachmentStatus(){
        attachmentCards.removeAllViews();attachmentStatus.setVisibility(View.GONE);
        java.util.List<AttachmentTransfer> uploads=shownTab==null?java.util.Collections.emptyList():new java.util.ArrayList<>(shownTab.attachments);
        attachmentScroll.setVisibility(uploads.isEmpty()?View.GONE:View.VISIBLE);
        StringBuilder status=new StringBuilder();
        for(AttachmentTransfer upload:uploads){
            boolean failed=!upload.busy&&upload.path==null;
            String description=upload.busy?tr(R.string.attachment_pending):failed?tr(upload.failure):"";
            AttachmentChip card=new AttachmentChip(this,upload,()->previewAttachment(upload),()->{if(sshService!=null&&shownTab!=null)sshService.removeAttachment(shownTab.id,upload);});
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(72),dp(72));params.rightMargin=dp(4);attachmentCards.addView(card,params);
            if(upload.busy||failed){if(status.length()>0)status.append("\n");status.append(description);}
        }
        if(status.length()>0){attachmentStatus.setText(status);attachmentStatus.setVisibility(View.VISIBLE);}
        updateSendButton();
    }
    private void previewAttachment(AttachmentTransfer upload){
        if(upload.mime.startsWith("video/"))try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(upload.source,upload.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));return;}catch(ActivityNotFoundException|SecurityException ignored){}
        if(attachmentPreview!=null)attachmentPreview.dismiss();
        attachmentPreview=new android.app.Dialog(this);LinearLayout panel=column();panel.setPadding(dp(20),dp(20),dp(20),dp(20));panel.setBackground(round(0xff161719,24));
        TextView heading=label(upload.name.isEmpty()?tr(R.string.attachment_preview):upload.name,18,TEXT);panel.addView(heading);
        if(upload.thumbnail!=null){ImageView image=new ImageView(this);image.setImageBitmap(upload.thumbnail);image.setAdjustViewBounds(true);image.setMaxHeight(Math.min(dp(240),AdaptiveLayout.available(this).height()/3));panel.addView(image,new LinearLayout.LayoutParams(-1,-2));}
        TextView info=label(upload.busy?tr(R.string.attachment_uploading,upload.name,upload.bytes/1024):upload.path==null?tr(upload.failure):tr(R.string.attachment_ready,upload.name),14,MUTED);info.setPadding(0,dp(16),0,dp(16));panel.addView(info);
        TextView close=label(tr(R.string.dismiss),16,GREEN);close.setPadding(0,dp(12),0,dp(12));close.setOnClickListener(v->attachmentPreview.dismiss());panel.addView(close);
        attachmentPreview.setContentView(AdaptiveLayout.sheetScroll(this,panel));attachmentPreview.show();AdaptiveLayout.sheetWindow(this,attachmentPreview,560,0);
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
        selectionBar.addView(key(tr(R.string.copy),tr(R.string.copy_selection),this::copySelection),new LinearLayout.LayoutParams(dp(64),dp(48)));
        selectionBar.addView(key("×",tr(R.string.clear_selection),()->terminal.clearSelection()),new LinearLayout.LayoutParams(dp(48),dp(48)));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);
        p.setMargins(dp(8),dp(8),dp(8),0);viewport.addView(selectionBar,p);selectionBar.setVisibility(View.GONE);
    }
    private void buildPad() {
        pad=column();pad.setElevation(dp(10));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(156),dp(204),Gravity.TOP|Gravity.LEFT);
        viewport.addView(pad,p);
        LinearLayout handle=row();pad.addView(handle,new LinearLayout.LayoutParams(-1,dp(48)));
        padLocked=new InputPreferences(this).flag("pad_locked",false);padFolded=new InputPreferences(this).flag("pad_folded",false);
        TextView drag=icon("grip",AppLanguage.tr(R.string.input_toggle_dpad),()->{padFolded=!padFolded;new InputPreferences(this).setFlag("pad_folded",padFolded);layoutPad();});drag.setTextColor(UiTheme.foreground(MainActivity.this,MUTED));
        ((ControlLabel)drag).setIcon(new ToolbarIcon(MainActivity.this,"grip",dp(44),MUTED));
        handle.addView(new View(this),new LinearLayout.LayoutParams(dp(32),1));
        handle.addView(drag,new LinearLayout.LayoutParams(dp(64),-1));
        lockButton=icon("unlock",tr(R.string.lock_direction_pad_position),()->{
            padLocked=!padLocked;new InputPreferences(this).setFlag("pad_locked",padLocked);
            ((ControlLabel)lockButton).setIcon(new ToolbarIcon(MainActivity.this,padLocked?"lock":"unlock",dp(14),padLocked?GREEN:MUTED));
            lockButton.setContentDescription(padLocked?tr(R.string.unlock_direction_pad_position):tr(R.string.lock_direction_pad_position));
        });((ControlLabel)lockButton).setIcon(new ToolbarIcon(MainActivity.this,padLocked?"lock":"unlock",dp(14),padLocked?GREEN:MUTED));lockButton.setContentDescription(padLocked?tr(R.string.unlock_direction_pad_position):tr(R.string.lock_direction_pad_position));
        handle.addView(lockButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        drag.setBackgroundColor(UiTheme.background(MainActivity.this,Color.TRANSPARENT));lockButton.setBackgroundColor(UiTheme.background(MainActivity.this,Color.TRANSPARENT));
        drag.setOnTouchListener(new View.OnTouchListener(){float x,y,sx,sy;boolean moved,held;final android.os.Handler h=new android.os.Handler(android.os.Looper.getMainLooper());final Runnable lock=()->{held=true;lockButton.performClick();};
            public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:x=e.getRawX();y=e.getRawY();sx=pad.getX();sy=pad.getY();moved=false;held=false;h.postDelayed(lock,ViewConfiguration.getLongPressTimeout());return true;
                case MotionEvent.ACTION_MOVE:if(Math.hypot(e.getRawX()-x,e.getRawY()-y)>dp(8)){moved=true;h.removeCallbacks(lock);}if(moved&&!padLocked){pad.setX(sx+e.getRawX()-x);pad.setY(sy+e.getRawY()-y);clampPad();}return true;
                case MotionEvent.ACTION_UP:h.removeCallbacks(lock);if(!moved&&!held)v.performClick();if(moved)savePadPosition();return true;
                case MotionEvent.ACTION_CANCEL:h.removeCallbacks(lock);return true;
            }return true;}
        });
        padCornerRow(true);
        padRow(new String[]{"left","return","right"},new String[]{tr(R.string.left),tr(R.string.enter),tr(R.string.right)},new Runnable[]{()->sendKey(TerminalSession.Key.LEFT),()->sendKey(TerminalSession.Key.ENTER),()->sendKey(TerminalSession.Key.RIGHT)});
        padCornerRow(false);layoutPad();
        pad.setVisibility(View.GONE);
    }
    private void padCornerRow(boolean upper){
        LinearLayout row=row();pad.addView(row,new LinearLayout.LayoutParams(-1,dp(52)));
        for(int i=0;i<3;i++){View view;if(i==1)view=icon(upper?"up":"down",upper?tr(R.string.up):tr(R.string.down),()->sendKey(upper?TerminalSession.Key.UP:TerminalSession.Key.DOWN));
            else {String corner=upper?i==0?"corner_ul":"corner_ur":i==0?"corner_ll":"corner_lr";TextView button=key("",corner,()->{if(inputControls!=null)inputControls.perform(new InputPreferences(this).mapping(corner));});button.setTag(corner);padCorners.add(button);view=button;}
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(50),1);p.setMargins(dp(1),dp(1),dp(1),dp(1));row.addView(view,p);
        }refreshPadCorners();
    }
    private void refreshPadCorners(){InputPreferences config=new InputPreferences(this);for(TextView b:padCorners){String action=config.mapping((String)b.getTag());b.setText(config.label(action));UiDesign.type(b,UiDesign.Type.CONTROL);b.setContentDescription(config.label(action));b.setVisibility(action.equals("none")?View.INVISIBLE:View.VISIBLE);}}
    private void layoutPad(){if(pad==null)return;for(int i=1;i<pad.getChildCount();i++)pad.getChildAt(i).setVisibility(padFolded?View.GONE:View.VISIBLE);FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)pad.getLayoutParams();p.height=dp(padFolded?48:204);pad.setLayoutParams(p);pad.post(this::clampPad);}
    private void savePadPosition(){if(viewport.getWidth()==0||viewport.getHeight()==0)return;new InputPreferences(this).prefs.edit().putFloat("pad_x",pad.getX()/viewport.getWidth()).putFloat("pad_y",pad.getY()/viewport.getHeight()).apply();}
    private void padRow(String[] titles,String[] descriptions,Runnable[] actions) {
        LinearLayout row=row();pad.addView(row,new LinearLayout.LayoutParams(-1,dp(52)));
        for(int i=0;i<3;i++){
            View view=actions[i]==null?new View(this):icon(titles[i],descriptions[i],actions[i]);
            if(actions[i]!=null)view.setBackground(new RippleDrawable(ColorStateList.valueOf(uiTheme().ripple),round(titles[i].equals("return")||titles[i].equals("clear")||titles[i].equals("backspace")?Color.BLACK:KEY,16),null));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(50),1);p.setMargins(dp(1),dp(1),dp(1),dp(1));row.addView(view,p);
        }
    }
    private void togglePad() {
        boolean show=pad.getVisibility()!=View.VISIBLE;pad.setVisibility(show?View.VISIBLE:View.GONE);
        if(inputControls!=null)inputControls.setActive("pad",show);if(show)pad.post(()->{
            if(!padPositioned){android.content.SharedPreferences saved=new InputPreferences(this).prefs;pad.setX(saved.contains("pad_x")?saved.getFloat("pad_x",.5f)*viewport.getWidth():(viewport.getWidth()-pad.getWidth())/2f);pad.setY(saved.contains("pad_y")?saved.getFloat("pad_y",.4f)*viewport.getHeight():viewport.getHeight()*.42f-pad.getHeight()/2f);padPositioned=true;}
            clampPad();
        });
    }
    private void clampPad() {
        if(pad==null||viewport.getWidth()==0||pad.getWidth()==0)return;
        boolean compact=!padFolded&&viewport.getHeight()<dp(204)&&viewport.getWidth()>=dp(468);
        if((pad.getOrientation()==LinearLayout.HORIZONTAL)!=compact){
            pad.setOrientation(compact?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
            pad.getChildAt(0).setVisibility(compact?View.GONE:View.VISIBLE);
            for(int i=1;i<pad.getChildCount();i++)pad.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(compact?dp(156):-1,dp(52)));
            FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)pad.getLayoutParams();p.width=dp(compact?468:156);p.height=dp(padFolded?48:compact?52:204);pad.setLayoutParams(p);
            pad.post(this::clampPad);return;
        }
        pad.setX(Math.max(0,Math.min(pad.getX(),viewport.getWidth()-pad.getWidth())));
        pad.setY(Math.max(0,Math.min(pad.getY(),viewport.getHeight()-pad.getHeight())));
    }
    private void buildHome() {
        homePage=column();homePage.setBackground(new HomeBackdrop(homePage));
        home=new ScrollView(this);home.setClipToPadding(false);
        LinearLayout content=column();content.setClipChildren(false);int inset=dp(getResources().getConfiguration().screenWidthDp>=600?24:16);content.setPadding(inset,0,inset,dp(110));home.addView(AdaptiveLayout.centered(this,content,1200,null));
        LinearLayout top=row();top.setPadding(inset,0,inset,0);top.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        String[] navIcons={"inbox","settings"};int[] navLabels={R.string.agent_inbox,R.string.settings};
        for(int i=0;i<navIcons.length;i++){final boolean inbox=i==0;View button=SampleUi.header(this,navIcons[i],tr(navLabels[i]),TEXT,()->startActivity(new Intent(this,inbox?AgentActivity.class:SettingsActivity.class)));button.setBackground(SampleUi.touch(this,uiTheme().light?uiTheme().surface:0xff111713,24));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(48),dp(48));lp.leftMargin=dp(8);top.addView(button,lp);}
        homePage.addView(AdaptiveLayout.centered(this,top,1200,null),new LinearLayout.LayoutParams(-1,dp(56)));
        homePage.addView(home,new LinearLayout.LayoutParams(-1,0,1));
        AdaptiveLayout.Columns homeColumns=new AdaptiveLayout.Columns(this,840,24);content.addView(homeColumns,new LinearLayout.LayoutParams(-1,-2));
        overview=new SessionOverview(this,stage,homePage,new SessionOverview.Actions(){
            public void open(String id){openTab(id);}public void close(String id){closeTab(id);}public void closeAll(){closeAllTabs();}
            public void create(){chooseConnection();}
            public void move(String id,int index){if(sshService!=null)sshService.move(id,index);}
        });homeColumns.addView(overview.view());
        LinearLayout connections=column();homeColumns.addView(connections);
        LinearLayout connectionHeading=row();connectionHeading.setMinimumHeight(dp(48));TextView savedTitle=SampleUi.sectionTitle(this,tr(R.string.connections));connectionHeading.addView(savedTitle,new LinearLayout.LayoutParams(0,-2,1));hookAttention=label("",13,0xffbd9036);UiDesign.touch(hookAttention);hookAttention.setGravity(Gravity.CENTER);hookAttention.setOnClickListener(v->startActivity(SettingsNavigation.intent(this,"hook")));connectionHeading.addView(hookAttention);LinearLayout.LayoutParams connectionHeaderParams=new LinearLayout.LayoutParams(-1,-2);connections.addView(connectionHeading,connectionHeaderParams);
        savedConnections=column();connections.addView(savedConnections);refreshSavedConnections();
        guideShown=getSharedPreferences("appearance",0).getBoolean("guide",true);if(guideShown){
            LinearLayout guideHeader=row();guideHeader.setMinimumHeight(dp(48));TextView heading=SampleUi.sectionTitle(this,tr(R.string.discover_shello));guideHeader.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
            android.content.SharedPreferences visited=getSharedPreferences("guide",0);TextView progress=label("",13,0xff00bc65);progress.setIncludeFontPadding(false);guideHeader.addView(progress);
            LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=dp(UiDesign.SECTION);content.addView(guideHeader,hp);
            String[] names={tr(R.string.qr_pairing),tr(R.string.remote_sessions),tr(R.string.inbox_and_approvals),tr(R.string.appearance_and_language),tr(R.string.shortcuts),tr(R.string.text_selection)};String[] icons={"qr","server","inbox","settings","keyboard","pad"};
            Runnable update=()->{int count=0;for(int i=0;i<names.length;i++)if(visited.getBoolean("viewed_"+i,false))count++;progress.setText(count+"/"+names.length);};update.run();
            HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);LinearLayout cards=row();scroll.addView(cards);content.addView(scroll);
            for(int i=0;i<names.length;i++){final int index=i;LinearLayout card=column();card.setGravity(Gravity.CENTER);card.setMinimumHeight(dp(100));card.setPadding(0,dp(12),0,dp(12));card.setBackground(SampleUi.touch(this,uiTheme().light?uiTheme().surface:0xff191b1d,16));card.setContentDescription(names[i]);card.addView(SampleUi.icon(this,icons[i],0xff00ee00),new LinearLayout.LayoutParams(dp(24),dp(24)));TextView text=label(names[i],13,MUTED);text.setGravity(Gravity.CENTER);text.setPadding(dp(8),dp(8),dp(8),0);card.addView(text);
                card.setOnClickListener(v->{visited.edit().putBoolean("viewed_"+index,true).apply();update.run();switch(index){case 0:startActivityForResult(new Intent(this,PairingActivity.class),74);break;case 1:infoDialog=SampleUi.confirm(this,tr(R.string.remote_sessions),tr(R.string.tap_a_saved_connection_to_choose_or_create_a_remote_tmux_session),null,tr(R.string.close),tr(R.string.got_it),false,null,null);break;case 2:startActivity(new Intent(this,AgentActivity.class));break;default:startActivity(SettingsNavigation.intent(this,index==3?"appearance":index==4?"shortcuts":"gestures"));}});
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(112),-2);cp.rightMargin=dp(8);cards.addView(card,cp);}
        }
        stage.addView(homePage,new FrameLayout.LayoutParams(-1,-1));homePage.setVisibility(View.GONE);if(addConnection!=null)addConnection.setVisibility(View.GONE);
        addConnection=SampleUi.header(this,"plus",tr(R.string.new_ssh_connection),uiTheme().onAccent,this::chooseConnection);addConnection.setBackground(SampleUi.touch(this,0xff00ef00,30));FrameLayout.LayoutParams fab=new FrameLayout.LayoutParams(dp(60),dp(60),Gravity.RIGHT|Gravity.BOTTOM);fab.setMargins(0,0,dp(24),dp(24));stage.addView(addConnection,fab);addConnection.setVisibility(View.GONE);
    }
    private final class HomeBackdrop extends android.graphics.drawable.Drawable {
        final android.graphics.Paint paint=new android.graphics.Paint();final View owner;
        HomeBackdrop(View owner){this.owner=owner;}
        @Override public void draw(android.graphics.Canvas canvas){
            android.graphics.Rect bounds=getBounds();float width=root.getWidth(),height=root.getHeight();
            float x=owner==root?0:root.getPaddingLeft(),y=owner==root?0:root.getPaddingTop();
            canvas.drawColor(uiTheme().canvas);
            paint.setShader(new android.graphics.RadialGradient(width*.5f-x,-y,Math.max(1,width*.8f),UiTheme.background(MainActivity.this,0xff041104),Color.TRANSPARENT,android.graphics.Shader.TileMode.CLAMP));canvas.drawRect(bounds,paint);
            paint.setShader(new android.graphics.RadialGradient(width-x,height-y,Math.max(1,width*.65f),UiTheme.background(MainActivity.this,0xff032003),Color.TRANSPARENT,android.graphics.Shader.TileMode.CLAMP));canvas.drawRect(bounds,paint);
        }
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
        if(request==78){if(result==RESULT_OK&&data!=null){java.util.ArrayList<String> words=data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);if(words!=null&&!words.isEmpty()){setDraftMode(true,true);draft.append(words.get(0));}}return;}

        super.onActivityResult(request,result,data);
        if(request==TAKE_ATTACHMENT){
            android.net.Uri output=cameraUri;cameraUri=null;
            boolean valid=false;try{valid=output!=null&&AttachmentCameraProvider.file(this,output).length()>0;}catch(java.io.IOException ignored){}
            if(result==RESULT_OK&&valid){attachmentUri=output;uploadPickedAttachment();}else{AttachmentCameraProvider.remove(this,output);attachmentTab=null;}return;
        }
        if(request==PICK_ATTACHMENT){if(result==RESULT_OK&&data!=null&&data.getData()!=null){attachmentUri=data.getData();uploadPickedAttachment();}else{attachmentUri=null;attachmentTab=null;}return;}
        if((request==73||request==74)&&result==RESULT_OK&&data!=null){String profile=data.getStringExtra("profile");if(profile!=null){pendingProfile=null;chooseSession(profile);}}
    }
    private void restoreDraft(String text,int start,int end){
        draft.setText(text);int length=draft.length();draft.setSelection(start<0?length:Math.min(start,length),end<0?length:Math.min(end,length));
    }
    private void rememberTab(){
        if(shownTab==null)return;
        shownTab.draft=draft.getText().toString();shownTab.draftMode=draftMode;shownTab.draftSelectionStart=draft.getSelectionStart();shownTab.draftSelectionEnd=draft.getSelectionEnd();
        if(!minimized&&sheet.isShown()&&terminal.getWidth()>0&&terminal.getHeight()>0&&session!=null&&sshService!=null){
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(480,480,android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);canvas.drawColor(SURFACE);
            float scale=480f/terminal.getWidth();canvas.scale(scale,scale);terminal.draw(canvas);
            // Existing ImageViews can still reference the previous preview until the next layout.
            sshService.rememberPreview(shownTab,bitmap);
        }
    }
    private void minimize() {
        rememberTab();hideKeyboard();if(inputControls!=null)inputControls.clearModifiers();minimized=true;root.setBackground(new HomeBackdrop(root));sheet.setVisibility(View.INVISIBLE);homePage.setVisibility(View.VISIBLE);if(addConnection!=null)addConnection.setVisibility(View.VISIBLE);
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
    private void resumeSession(){minimized=false;root.setBackgroundColor(uiTheme().canvas);homePage.setVisibility(View.GONE);if(addConnection!=null)addConnection.setVisibility(View.GONE);sheet.setVisibility(View.VISIBLE);root.requestFocus();}
    @Override public void onBackPressed() {
        if(overview!=null&&overview.back())return;
        if(minimized){super.onBackPressed();return;}
        if(inputControls!=null&&inputControls.back())return;
        if(pad.getVisibility()==View.VISIBLE){togglePad();return;}
        if(!terminal.getSelectedText().isEmpty()){terminal.clearSelection();return;}
        if(draftMode){setDraftMode(false,false);hideKeyboard();return;}
        minimize();
    }
    private void setDraftMode(boolean enabled,boolean keyboard) {
        if(inputControls!=null)inputControls.clearModifiers();draftMode=enabled;
        toolbarContainer.setVisibility(enabled?View.GONE:View.VISIBLE);
        composer.setVisibility(enabled?View.VISIBLE:View.GONE);
        pad.setVisibility(View.GONE);
        if(inputControls!=null){inputControls.mode(enabled);inputControls.setActive("pad",false);}
        if(keyboard)showKeyboard();
    }
    private void showKeyboard() {
        View target=draftMode?draft:directInput;target.requestFocus();
        target.post(new Runnable(){int attempts;
            public void run(){
                if(destroyed||minimized||!target.isShown()||!target.hasFocus())return;
                // Popup dismissal and Activity transitions can return focus a frame later.
                if(!target.hasWindowFocus()){if(++attempts<12)target.postDelayed(this,50);return;}
                ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(target,InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }
    private void hideKeyboard() {
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);root.requestFocus();
    }
    private void toggleKeyboard(){if(imeVisible)hideKeyboard();else showKeyboard();}
    private void sendKey(TerminalSession.Key key){if(inputControls!=null)inputControls.key(key);else terminal.sendKey(key);}
    private void sendDraft(boolean enter) {
        String original=draft.getText().toString();String text=SshSessionService.composeDraft(shownTab,original);if(text==null||text.isEmpty())return;
        if(sshMode&&!sshConnected){toast(tr(R.string.ssh_is_disconnected_draft_kept));return;}
        if(!original.isEmpty()){draftHistory.remove(original);draftHistory.add(0,original);if(draftHistory.size()>20)draftHistory.remove(20);}
        // Explicit submission finishes this local draft; it is never sent during composition.
        terminal.pasteText(text);if(enter)terminal.sendKey(TerminalSession.Key.ENTER);
        if(sshService!=null&&shownTab!=null)sshService.cancelAttachment(shownTab.id);draft.setText("");
    }
    private void paste() {
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(!clipboard.hasPrimaryClip()||clipboard.getPrimaryClip()==null)return;
        CharSequence text=clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);if(text==null)return;
        if(!draftMode){terminal.pasteText(text.toString());return;}
        int start=Math.max(0,draft.getSelectionStart()),end=Math.max(0,draft.getSelectionEnd());
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
        if(request==CAMERA_PERMISSION){if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)launchAttachmentCamera();else{attachmentTab=null;toast(tr(R.string.attachment_camera_unavailable));}return;}
        if(request==41&&sshService!=null){sshService.refreshNotification();updateSshView();}
    }
    @Override protected void onPause(){
        previewHandler.removeCallbacks(previewCheckpoint);if(inputControls!=null)inputControls.pause();rememberTab();super.onPause();
    }
    @Override protected void onStop(){
        if(tmuxDialog!=null&&!tmuxDialog.isShowing())tmuxDialog.dismiss();
        if(agentHub!=null)agentHub.detach(agentChanged);
        if(connectionMenu!=null)connectionMenu.dismiss();
        if(attachmentSheet!=null)attachmentSheet.dismiss();if(attachmentPreview!=null)attachmentPreview.dismiss();
        if(overview!=null)overview.dismiss();rememberTab();
        if(sshService!=null)sshService.removeObserver(serviceObserver);
        detachServiceView();sshService=null;sshConnected=false;
        if(bound){unbindService(serviceConnection);bound=false;}
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        state.putInt("draftSelectionStart",draft.getSelectionStart());state.putInt("draftSelectionEnd",draft.getSelectionEnd());
        state.putString("cameraUri",cameraUri==null?null:cameraUri.toString());state.putString("attachmentTab",attachmentTab);state.putString("attachmentUri",attachmentUri==null?null:attachmentUri.toString());
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
            if(next!=null&&restoredDraftTab.equals(next.id)){next.draft=restoredDraft;next.draftMode=restoredDraftMode;next.draftSelectionStart=restoredDraftSelectionStart;next.draftSelectionEnd=restoredDraftSelectionEnd;}
            restoredDraftTab=null;restoredDraft=null;
        }
        if(next!=shownTab){
            if(shownTab!=null){shownTab.draft=draft.getText().toString();shownTab.draftMode=draftMode;shownTab.draftSelectionStart=draft.getSelectionStart();shownTab.draftSelectionEnd=draft.getSelectionEnd();}
            shownTab=next;restoreDraft(next==null?"":next.draft,next==null?-1:next.draftSelectionStart,next==null?-1:next.draftSelectionEnd);setDraftMode(next!=null&&(next.draftMode||getSharedPreferences("appearance",0).getBoolean("draftDefault",false)),false);
        }
        if(session!=remote){
            terminal.setSession(null);if(session!=null&&!serviceSession)session.close();
            session=remote;serviceSession=remote!=null;terminal.setSession(session);appliedTheme=null;
        }
        sshMode=next!=null&&next.remote();sshConnected=sshService.connected();
        protocolBadge.setText(sshMode?"SSH":tr(R.string.local));
        subtitle.setText(sshMode?sshService.profileName()+" · "+sshService.status()
                +(sshService.active()&&!notificationsVisible()?tr(R.string.notifications_off):""):tr(R.string.local_terminal));
        updateAttachmentStatus();applyAppearance();
        if(overview!=null)overview.refresh(sshService.tabs());
        if(next==null&&!minimized)minimize();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(inputControls!=null&&inputControls.hardware(event))return true;return super.dispatchKeyEvent(event);}
    private void switchSession(int index,boolean relative){if(sshService==null)return;java.util.List<SshSessionService.Tab> tabs=sshService.tabs();if(tabs.isEmpty())return;int current=tabs.indexOf(sshService.selected());int selected=relative?Math.floorMod(current+index,tabs.size()):index;if(selected<tabs.size())openTab(tabs.get(selected).id);}
    private void controlAction(String id){switch(id){
        case "pad":togglePad();break;case "panel":tools(toolbar);break;case "paste":paste();break;case "history":history();break;case "chat":setDraftMode(true,true);break;case "keyboard":toggleKeyboard();break;case "hide_keyboard":hideKeyboard();break;case "minimize":minimize();break;
        case "select":terminal.setSelectionEnabled(true);toast(AppLanguage.tr(R.string.input_hold_to_select));break;
        case "drag_arrows":terminal.setDragArrowsEnabled(!terminal.isDragArrowsEnabled());break;
        case "mouse":terminal.setMouseSelectionEnabled(!terminal.isMouseSelectionEnabled());break;
        case "right_click":terminal.setRightClickEnabled(!terminal.isRightClickEnabled());break;
        case "changes":if(inputControls!=null)inputControls.sequence("text:/diff",true);break;
        case "preview":openWebPreview();break;
        case "close_session":if(shownTab!=null)closeTab(shownTab.id);break;
        case "attachment_camera":setDraftMode(true,false);chooseAttachmentSource(0);break;case "attachment_image":setDraftMode(true,false);chooseAttachmentSource(1);break;case "attachment_file":setDraftMode(true,false);chooseAttachmentSource(2);break;
        case "dictation":try{Intent speech=new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);startActivityForResult(speech,78);}catch(ActivityNotFoundException error){toast(AppLanguage.tr(R.string.input_no_dictation));}break;
        case "clipboard":android.content.ClipboardManager clipboard=getSystemService(android.content.ClipboardManager.class);ClipData clip=clipboard.getPrimaryClip();if(clip!=null&&clip.getItemCount()>0&&clip.getItemAt(0).getUri()!=null){attachmentUri=clip.getItemAt(0).getUri();attachmentTab=shownTab==null?null:shownTab.id;uploadPickedAttachment();setDraftMode(true,true);}else paste();break;
    }}
    private void openWebPreview(){EditText address=new EditText(this);SampleUi.field(address);address.setHint(AppLanguage.tr(R.string.input_web_address_hint));android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle(AppLanguage.tr(R.string.input_web_preview)).setView(address).setNegativeButton(AppLanguage.tr(R.string.input_cancel),null).setPositiveButton(AppLanguage.tr(R.string.input_open),null).create();if(infoDialog!=null)infoDialog.dismiss();infoDialog=dialog;dialog.setOnShowListener(ignored->dialog.getButton(-1).setOnClickListener(v->{try{android.net.Uri uri=android.net.Uri.parse(address.getText().toString());if(!java.util.List.of("http","https").contains(uri.getScheme())){address.setError(AppLanguage.tr(R.string.input_http_required));return;}startActivity(new Intent(Intent.ACTION_VIEW,uri));dialog.dismiss();}catch(ActivityNotFoundException error){address.setError(AppLanguage.tr(R.string.input_no_browser));}}));dialog.show();}
    private void applyAppearance(){
        if(terminal==null)return;android.content.SharedPreferences prefs=getSharedPreferences("appearance",0);terminal.setFontSize(prefs.getInt("fontSize",14));terminal.setSelectionEnabled(prefs.getBoolean("selection",true));
        if(inputControls!=null){inputControls.refresh();inputControls.setActive("pad",pad.getVisibility()==View.VISIBLE);}refreshPadCorners();
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
    private void configureInsets(){
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        if(Build.VERSION.SDK_INT>=29)getWindow().setStatusBarContrastEnforced(false);
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarContrastEnforced(false);applyModernInsets();
        }else{
            View decor=getWindow().getDecorView();decor.setSystemUiVisibility(decor.getSystemUiVisibility()|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            root.setOnApplyWindowInsetsListener((view,insets)->{
                view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
                return insets.consumeSystemWindowInsets();
            });
        }
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
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);l.setBaselineAligned(false);return l;}
    private TextView label(String text,int size,int color){return SampleUi.text(this,text,size,color);}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(UiTheme.background(this,color));d.setCornerRadius(dp(radius));return d;}
    private TextView key(String title,String description,Runnable action) {
        TextView v=new ControlLabel();v.setText(title);UiDesign.type(v,UiDesign.Type.CONTROL);UiDesign.touch(v);v.setTextColor(UiTheme.foreground(MainActivity.this,TEXT));v.setGravity(Gravity.CENTER);v.setTypeface(Typeface.MONOSPACE);
        v.setSingleLine();v.setEllipsize(TextUtils.TruncateAt.END);v.setContentDescription(description);
        v.setFocusable(true);v.setBackground(new RippleDrawable(ColorStateList.valueOf(uiTheme().ripple),round(KEY,22),null));
        v.setOnClickListener(w->action.run());return v;
    }
    private TextView icon(String name,String description,Runnable action) {
        TextView v=key("",description,action);v.setTag(name);((ControlLabel)v).setIcon(new ToolbarIcon(MainActivity.this,name,dp(20),TEXT));
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
        destroyed=true;if(inputControls!=null)inputControls.close();
        directInput.setTerminalView(null);terminal.setListener(null);terminal.setSession(null);
        if(session!=null&&!serviceSession)session.close();
        if(overview!=null)overview.dismiss();
        super.onDestroy();
    }
}
