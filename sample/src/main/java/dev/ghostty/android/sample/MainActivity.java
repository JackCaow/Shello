package dev.ghostty.android.sample;

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

/** Moshi-inspired terminal controls around offline echo or a sample SSH shell. */
public final class MainActivity extends Activity {
    private static final int SURFACE=0xff1e1e1e, KEY=0xff111315, TEXT=0xffe6e6e8,
            MUTED=0xff96999f, GREEN=0xff00d86c;
    private TerminalSession session;
    private SshTransport ssh;
    private boolean sshMode, sshConnected, destroyed;
    private int sshGeneration;
    private TextView protocolBadge, homeBadge;
    private TerminalSession.Checkpoint checkpoint;
    private TerminalView terminal;
    private TerminalInputView directInput;
    private EditText draft;
    private LinearLayout root, sheet, composer, toolbar, pad, selectionBar;
    private FrameLayout stage, viewport;
    private ScrollView home;
    private ImageView sessionPreview;
    private android.graphics.Bitmap previewBitmap;
    private TextView subtitle, ctrlButton, padButton, lockButton, sendButton;
    private final java.util.ArrayList<String> draftHistory=new java.util.ArrayList<>();
    private boolean padPositioned;
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
                subtitle.setContentDescription((sshMode?"SSH 终端":"本地终端")+"，"+columns+" 列，"+rows+" 行");
                if(ssh!=null)ssh.resize(columns,rows);
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
            imeVisible=root.getRootView().getHeight()-visible.bottom>dp(120);
        });
        viewport.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->clampPad());
        write("\033]10;#d4d4d4\007\033]11;#1e1e1e\007\033]12;#00d86c\007本地演示 · Offline echo\r\n\r\n› "); root.requestFocus();
        if(BuildConfig.DEBUG && getIntent().getBooleanExtra("connectLocalSsh",false))
            terminal.post(this::connectSsh);
    }

    private void buildHeader() {
        View grip=new View(this); grip.setBackground(round(0xff5a5b60,3));
        LinearLayout.LayoutParams gripParams=new LinearLayout.LayoutParams(dp(44),dp(3));
        gripParams.gravity=Gravity.CENTER_HORIZONTAL;gripParams.topMargin=dp(8);sheet.addView(grip,gripParams);
        LinearLayout header=row(); header.setPadding(dp(16),0,dp(16),0);
        sheet.addView(header,new LinearLayout.LayoutParams(-1,dp(28)));
        TextView minimize=key("−","最小化会话",this::minimize); minimize.setTextColor(Color.BLACK);
        minimize.setTextSize(14);
        minimize.setBackground(new android.graphics.drawable.InsetDrawable(round(0xffffcc00,9),dp(4)));
        header.addView(minimize,new LinearLayout.LayoutParams(dp(26),dp(26)));
        TextView menu=icon("panel","会话工具",()->tools(header));
        ((ControlLabel)menu).setIcon(new ToolbarIcon("panel",dp(11),Color.BLACK));
        menu.setBackground(new android.graphics.drawable.InsetDrawable(round(GREEN,9),dp(4)));
        header.addView(menu,new LinearLayout.LayoutParams(dp(26),dp(26)));
        subtitle=label("本地终端",12,MUTED);subtitle.setTypeface(Typeface.MONOSPACE);
        subtitle.setSingleLine();subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setPadding(dp(8),0,dp(8),0);
        header.addView(subtitle,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=label("LOCAL",10,0xff75b6ff);protocolBadge=badge;badge.setPadding(dp(5),dp(3),dp(5),dp(3));
        badge.setBackground(round(0xff173247,10)); header.addView(badge);
    }

    private void buildToolbar() {
        HorizontalScrollView scroll=new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);scroll.setFillViewport(true);
        toolbar=row();toolbar.setPadding(dp(5),dp(4),dp(5),dp(4));
        scroll.setBackground(round(Color.BLACK,32));scroll.setClipToOutline(true);
        scroll.addView(toolbar,new HorizontalScrollView.LayoutParams(-1,dp(52)));
        LinearLayout.LayoutParams outer=new LinearLayout.LayoutParams(-1,dp(52));
        outer.setMargins(dp(8),dp(6),dp(8),dp(8));sheet.addView(scroll,outer);
        scroll.setTag("toolbar");
        ctrlButton=key("Ctrl","Ctrl：点击后组合下一个按键",()->setCtrl(!ctrlArmed));
        addTool(ctrlButton);
        addTool(key("Esc","Escape",()->sendKey(TerminalSession.Key.ESCAPE)));
        addTool(key("Tab","Tab",()->sendKey(TerminalSession.Key.TAB)));
        padButton=icon("pad","方向键浮板",this::togglePad);addTool(padButton);
        addTool(icon("panel","会话工具",()->tools(toolbar)));
        addTool(icon("paste","粘贴到草稿",this::paste));
        TextView history=icon("history","输入历史",this::history);
        ((ControlLabel)history).setIcon(new ToolbarIcon("history",dp(13),MUTED));
        history.setBackgroundColor(Color.TRANSPARENT);toolbar.addView(history,new LinearLayout.LayoutParams(dp(16),dp(44)));
        TextView chat=icon("chat","编辑文本",()->setDraftMode(true,true));addTool(chat);chat.setBackgroundColor(Color.TRANSPARENT);
        TextView keyboard=icon("keyboard","显示或隐藏键盘",this::toggleKeyboard);addTool(keyboard);keyboard.setBackgroundColor(Color.TRANSPARENT);
    }
    private void addTool(View view) {
        view.setMinimumWidth(dp(40));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0xff35433c),new android.graphics.drawable.InsetDrawable(round(KEY,20),dp(3),dp(4),dp(3),dp(4)),null));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);
        toolbar.addView(view,p);
    }
    private void buildComposer() {
        composer=column();composer.setPadding(dp(12),dp(8),dp(12),dp(8));
        composer.setBackground(round(Color.BLACK,26));composer.setVisibility(View.GONE);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(dp(8),dp(6),dp(8),dp(8));sheet.addView(composer,params);
        draft=new EditText(this);draft.setTextColor(TEXT);draft.setHintTextColor(MUTED);
        draft.setTextSize(14);draft.setTypeface(Typeface.MONOSPACE);draft.setHint("对终端说…");draft.setBackgroundColor(Color.TRANSPARENT);
        // Command drafts must not autocorrect printf/stty into different commands.
        draft.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        draft.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        draft.setMinLines(1);draft.setMaxLines(4);draft.setPadding(dp(8),dp(10),dp(8),dp(8));
        draft.setContentDescription("本地文本草稿");composer.addView(draft,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout actions=row();composer.addView(actions,new LinearLayout.LayoutParams(-1,dp(44)));
        TextView add=icon("plus","添加文本",this::addTextMenu);composerAction(actions,add);
        composerAction(actions,icon("toolbox","终端工具",()->tools(composer)));
        composerAction(actions,icon("close","关闭文本框，保留草稿",()->setDraftMode(false,true)));
        actions.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        TextView voice=icon("mic","语音输入（暂未接入）",()->{});voice.setEnabled(false);voice.setAlpha(.4f);composerAction(actions,voice);
        sendButton=icon("send","发送并回车；长按仅发送",()->sendDraft(true));
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
    private void addTextMenu(){
        PopupMenu menu=new PopupMenu(this,composer);menu.getMenu().add("粘贴文本").setOnMenuItemClickListener(item->{paste();return true;});
        menu.getMenu().add("附件（暂未接入）").setEnabled(false);menu.show();
    }
    private void history(){
        PopupMenu menu=new PopupMenu(this,toolbar);
        if(draftHistory.isEmpty())menu.getMenu().add("暂无输入历史").setEnabled(false);
        for(String entry:draftHistory)menu.getMenu().add(entry).setOnMenuItemClickListener(item->{setDraftMode(true,true);draft.setText(entry);draft.setSelection(draft.length());return true;});
        menu.show();
    }
    private void buildSelectionBar() {
        selectionBar=row();selectionBar.setPadding(dp(4),dp(4),dp(4),dp(4));
        selectionBar.setBackground(round(Color.BLACK,24));selectionBar.setElevation(dp(8));
        selectionBar.addView(key("复制","复制选中内容",this::copySelection),new LinearLayout.LayoutParams(dp(64),dp(44)));
        selectionBar.addView(key("×","取消选择",()->terminal.clearSelection()),new LinearLayout.LayoutParams(dp(44),dp(44)));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);
        p.setMargins(dp(8),dp(8),dp(8),0);viewport.addView(selectionBar,p);selectionBar.setVisibility(View.GONE);
    }
    private void buildPad() {
        pad=column();pad.setElevation(dp(10));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(156),dp(184),Gravity.TOP|Gravity.LEFT);
        viewport.addView(pad,p);
        LinearLayout handle=row();pad.addView(handle,new LinearLayout.LayoutParams(-1,dp(28)));
        TextView drag=icon("grip","拖动方向键浮板",()->{});drag.setTextColor(MUTED);
        ((ControlLabel)drag).setIcon(new ToolbarIcon("grip",dp(44),MUTED));
        handle.addView(new View(this),new LinearLayout.LayoutParams(dp(46),1));
        handle.addView(drag,new LinearLayout.LayoutParams(dp(60),-1));
        lockButton=icon("unlock","锁定方向键位置",()->{
            padLocked=!padLocked;
            ((ControlLabel)lockButton).setIcon(new ToolbarIcon(padLocked?"lock":"unlock",dp(14),padLocked?GREEN:MUTED));
            lockButton.setContentDescription(padLocked?"解锁方向键位置":"锁定方向键位置");
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
        padRow(new String[]{"backspace","up","clear"},new String[]{"退格","上","清屏 Ctrl+L"},new Runnable[]{()->sendKey(TerminalSession.Key.BACKSPACE),()->sendKey(TerminalSession.Key.UP),()->terminal.sendText("\f")});
        padRow(new String[]{"left","return","right"},new String[]{"左","回车","右"},new Runnable[]{()->sendKey(TerminalSession.Key.LEFT),()->sendKey(TerminalSession.Key.ENTER),()->sendKey(TerminalSession.Key.RIGHT)});
        padRow(new String[]{"","down",""},new String[]{"","下",""},new Runnable[]{null,()->sendKey(TerminalSession.Key.DOWN),null});
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
        pad.setX(Math.max(0,Math.min(pad.getX(),viewport.getWidth()-pad.getWidth())));
        pad.setY(Math.max(0,Math.min(pad.getY(),viewport.getHeight()-pad.getHeight())));
    }
    private void buildHome() {
        home=new ScrollView(this);LinearLayout content=column();content.setPadding(dp(16),dp(12),dp(16),dp(24));home.addView(content);
        LinearLayout top=row();top.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        TextView settings=icon("settings","会话工具",()->tools(top));settings.setBackgroundColor(Color.TRANSPARENT);
        top.addView(settings,new LinearLayout.LayoutParams(dp(44),dp(44)));content.addView(top,new LinearLayout.LayoutParams(-1,dp(60)));
        TextView heading=label("会话",13,MUTED);heading.setPadding(0,dp(8),0,dp(12));content.addView(heading);
        LinearLayout cards=row();cards.setGravity(Gravity.TOP);content.addView(cards);
        LinearLayout card=column();card.setContentDescription("恢复 Ghostty 本地会话");card.setOnClickListener(v->resumeSession());
        int width=(getResources().getDisplayMetrics().widthPixels-dp(44))/2;
        cards.addView(card,new LinearLayout.LayoutParams(width,-2));
        FrameLayout thumbnail=new FrameLayout(this);thumbnail.setBackground(round(SURFACE,14));thumbnail.setClipToOutline(true);
        card.addView(thumbnail,new LinearLayout.LayoutParams(-1,width));
        sessionPreview=new ImageView(this);sessionPreview.setScaleType(ImageView.ScaleType.FIT_START);
        sessionPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        thumbnail.addView(sessionPreview,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout caption=row();caption.setPadding(dp(8),dp(6),dp(8),dp(6));caption.setBackgroundColor(0xcc1e1e1e);
        TextView title=label("本地终端",12,TEXT);caption.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=label("LOCAL",10,0xff75b6ff);homeBadge=badge;badge.setBackground(round(0xff173247,9));badge.setPadding(dp(4),dp(2),dp(4),dp(2));caption.addView(badge);
        thumbnail.addView(caption,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        TextView name=label("Ghostty",15,TEXT);name.setPadding(0,dp(6),0,0);card.addView(name);
        TextView prompt=label("›_ local",13,GREEN);prompt.setTypeface(Typeface.MONOSPACE);card.addView(prompt);
        stage.addView(home,new FrameLayout.LayoutParams(-1,-1));home.setVisibility(View.GONE);
    }
    private void minimize() {
        if(terminal.getWidth()>0&&terminal.getHeight()>0){
            sessionPreview.setImageDrawable(null);
            if(previewBitmap!=null)previewBitmap.recycle();
            previewBitmap=android.graphics.Bitmap.createBitmap(640,320,android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas=new android.graphics.Canvas(previewBitmap);
            float scale=640f/terminal.getWidth();canvas.scale(scale,scale);terminal.draw(canvas);
            sessionPreview.setImageBitmap(previewBitmap);
        }
        hideKeyboard();setCtrl(false);minimized=true;sheet.setVisibility(View.INVISIBLE);home.setVisibility(View.VISIBLE);
    }
    private void resumeSession(){minimized=false;home.setVisibility(View.GONE);sheet.setVisibility(View.VISIBLE);root.requestFocus();}
    @Override public void onBackPressed() {
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
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Terminal",text));
        terminal.clearSelection();toast("已复制");
    }
    private void tools(View anchor) {
        PopupMenu menu=new PopupMenu(this,anchor);
        String[] titles={"回到底部","Unicode 示例","滚动示例","保存本地快照","恢复本地快照"};
        for(int i=0;i<titles.length;i++)menu.getMenu().add(0,i,i,titles[i]);
        menu.getMenu().findItem(4).setEnabled(checkpoint!=null && !sshMode);
        for(int id=1;id<=3;id++)menu.getMenu().findItem(id).setEnabled(!sshMode);
        menu.getMenu().add(0,5,5,sshMode?"重新连接本机 SSH":"连接本机 SSH");
        menu.getMenu().add(0,6,6,"断开 SSH").setEnabled(ssh!=null);
        menu.getMenu().add(0,7,7,"返回离线演示").setEnabled(sshMode);
        menu.setOnMenuItemClickListener(item->{
            switch(item.getItemId()){
                case 5:connectSsh();break;
                case 6:disconnectSsh();break;
                case 7:disconnectSsh();sshMode=false;subtitle.setText("本地终端");protocolBadge.setText("LOCAL");homeBadge.setText("LOCAL");demo();break;
                case 0:terminal.scrollToBottom();break;
                case 1:demo();break;
                case 2:for(int i=1;i<=180;i++)write(String.format(java.util.Locale.ROOT,"L%03d 中文 · 👨‍👩‍👧‍👦 · scrollback\r\n",i));break;
                case 3:checkpoint=session.checkpoint();toast("已保存本地快照");break;
                case 4:TerminalSession restored=TerminalSession.restore(checkpoint);terminal.setSession(null);session.close();session=restored;terminal.setSession(session);toast("已恢复本地快照");break;
                default:return false;
            }return true;
        });menu.show();
    }
    private void connectSsh() {
        java.io.File directory=new java.io.File(getFilesDir(),"ssh");
        if(!new java.io.File(directory,"connection.properties").isFile()){
            toast("请先通过 scripts/local-ssh.py 配置本机 SSH");return;
        }
        disconnectSsh();sshMode=true;sshConnected=false;
        write("\033c\033]10;#d4d4d4\007\033]11;#1e1e1e\007\033]12;#00d86c\007");
        protocolBadge.setText("SSH");homeBadge.setText("SSH");
        final int generation=sshGeneration;
        ssh=new SshTransport(directory,session.getColumns(),session.getRows(),new SshTransport.Listener(){
            private boolean current(){return !destroyed && generation==sshGeneration;}
            public void onState(String state,boolean connected){
                if(!current())return;
                sshConnected=connected;subtitle.setText("本机 · "+state);
                if(!connected&&!state.equals("连接中"))toast(state);
            }
            public void onOutput(byte[] bytes){
                if(!current())return;
                byte[] replies=session.write(bytes);if(replies.length>0)ssh.send(replies);
            }
        });
        ssh.connect();
    }
    private void disconnectSsh(){
        sshGeneration++;
        if(ssh!=null){ssh.close();ssh=null;}sshConnected=false;
        if(sshMode)subtitle.setText("本机 · 已断开");
    }
    private void sendInput(byte[] bytes) {
        String text=new String(bytes,StandardCharsets.UTF_8);
        if(ctrlArmed){setCtrl(false);if(text.length()==1&&text.charAt(0)>=64&&text.charAt(0)<128)text=Character.toString((char)(text.charAt(0)&31));}
        if(sshMode){if(sshConnected&&ssh!=null)ssh.send(text.getBytes(StandardCharsets.UTF_8));else toast("SSH 未连接");return;}
        if(text.equals("\r")||text.equals("\n")){write("\r\n");return;}
        if(text.equals("\f")){write("\033[2J\033[H");return;}
        if(text.equals("\u007f")||text.equals("\b")){toast("退格");return;}
        if(text.codePoints().anyMatch(cp->cp<32&&cp!='\n'&&cp!='\t')){
            String display=text.replace("\033","Esc ");
            if(text.length()==1&&text.charAt(0)<27)display="Ctrl+"+(char)(text.charAt(0)+64);
            toast("按键："+display);return;
        }
        write(text.replace("\n","\r\n"));
    }
    private void demo() {
        write("\033c\033]10;#d4d4d4\007\033]11;#1e1e1e\007\033]12;#00d86c\007"
                +"\033[1;32mGhostty Android\033[0m\r\n\r\n"
                +"本地交互演示\r\n输入仅在此回显，不执行命令。\r\n\r\n"
                +"\033[32m›\033[0m 中文输入 · 滚动 · 长按选择\r\n\r\n"
                +"\033[?2027h👨‍👩‍👧‍👦  👍🏽  🇨🇳  1️⃣  👩‍❤️‍👩\r\n"
                +"🧑🏽‍🚀  👩🏻‍🤝‍👨🏿  é\r\n\r\n"
                +"Ctrl / Esc / Tab   方向键浮板\r\n\r\n"
                +"点击气泡编辑文本，点击键盘直接输入。\r\n长按发送仅输入文字，不附加回车。\r\n\r\n\033[32m›\033[0m ");
    }
    @android.annotation.TargetApi(30) private void applyModernInsets() {
        root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
            android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));
            imeVisible=insets.isVisible(WindowInsets.Type.ime());return insets;
        });
    }
    private void write(String text){session.write(text.getBytes(StandardCharsets.UTF_8));}
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
        TextView v=key("",description,action);((ControlLabel)v).setIcon(new ToolbarIcon(name,dp(20),TEXT));
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
        destroyed=true;disconnectSsh();
        directInput.setTerminalView(null);terminal.setListener(null);terminal.setSession(null);session.close();
        sessionPreview.setImageDrawable(null);if(previewBitmap!=null)previewBitmap.recycle();
        super.onDestroy();
    }
}
