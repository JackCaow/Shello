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

/** Moshi-inspired terminal controls around a local, offline echo session. */
public final class MainActivity extends Activity {
    private static final int SURFACE=0xff1e1e1e, KEY=0xff111315, TEXT=0xffe6e6e8,
            MUTED=0xff96999f, GREEN=0xff00d86c;
    private TerminalSession session;
    private TerminalSession.Checkpoint checkpoint;
    private TerminalView terminal;
    private TerminalInputView directInput;
    private EditText draft;
    private LinearLayout root, sheet, composer, toolbar, pad, selectionBar;
    private FrameLayout stage, viewport;
    private ScrollView home;
    private ImageView sessionPreview;
    private android.graphics.Bitmap previewBitmap;
    private TextView subtitle, ctrlButton, padButton, lockButton;
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
            @Override public void onInput(byte[] bytes) { echo(bytes); }
            @Override public void onResize(int columns,int rows) {
                subtitle.setText("本地演示 · "+columns+" × "+rows);
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
        demo(); root.requestFocus();
    }

    private void buildHeader() {
        View grip=new View(this); grip.setBackground(round(0xff5a5b60,3));
        LinearLayout.LayoutParams gripParams=new LinearLayout.LayoutParams(dp(44),dp(3));
        gripParams.gravity=Gravity.CENTER_HORIZONTAL;gripParams.topMargin=dp(8);sheet.addView(grip,gripParams);
        LinearLayout header=row(); header.setPadding(dp(8),0,dp(8),0);
        sheet.addView(header,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView minimize=key("−","最小化会话",this::minimize); minimize.setTextColor(Color.BLACK);
        minimize.setTextSize(18);
        minimize.setBackground(new android.graphics.drawable.InsetDrawable(round(0xffffcf24,12),dp(12)));
        header.addView(minimize,new LinearLayout.LayoutParams(dp(44),dp(44)));
        TextView menu=icon("panel","会话工具",()->tools(header));
        menu.setCompoundDrawablesWithIntrinsicBounds(null,new ToolbarIcon("panel",dp(14),Color.BLACK),null,null);
        menu.setPadding(0,dp(15),0,0);
        menu.setBackground(new android.graphics.drawable.InsetDrawable(round(GREEN,12),dp(12)));
        header.addView(menu,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout title=column(); title.setGravity(Gravity.CENTER_VERTICAL);
        TextView name=label("Ghostty",15,TEXT); name.setTypeface(Typeface.MONOSPACE);
        subtitle=label("本地演示",10,MUTED); title.addView(name); title.addView(subtitle);
        header.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        TextView badge=label("LOCAL",10,0xff75b6ff);badge.setPadding(dp(9),dp(5),dp(9),dp(5));
        badge.setBackground(round(0xff173247,12)); header.addView(badge);
    }

    private void buildToolbar() {
        HorizontalScrollView scroll=new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);scroll.setFillViewport(true);
        toolbar=row();toolbar.setPadding(dp(4),dp(6),dp(4),dp(6));
        scroll.setBackground(round(Color.BLACK,32));scroll.setClipToOutline(true);
        scroll.addView(toolbar,new HorizontalScrollView.LayoutParams(-1,dp(56)));
        LinearLayout.LayoutParams outer=new LinearLayout.LayoutParams(-1,dp(56));
        outer.setMargins(dp(8),dp(6),dp(8),dp(8));sheet.addView(scroll,outer);
        scroll.setTag("toolbar");
        ctrlButton=key("Ctrl","Ctrl：点击后组合下一个按键",()->setCtrl(!ctrlArmed));
        addTool(ctrlButton);
        addTool(key("Esc","Escape",()->sendKey(TerminalSession.Key.ESCAPE)));
        addTool(key("Tab","Tab",()->sendKey(TerminalSession.Key.TAB)));
        padButton=icon("pad","方向键浮板",this::togglePad);addTool(padButton);
        addTool(icon("panel","会话工具",()->tools(toolbar)));
        addTool(icon("paste","粘贴到草稿",this::paste));
        addTool(icon("chat","编辑文本",()->setDraftMode(true,true)));
        addTool(icon("keyboard","显示或隐藏键盘",this::toggleKeyboard));
    }
    private void addTool(View view) {
        view.setMinimumWidth(dp(44));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);
        toolbar.addView(view,p);
    }
    private void buildComposer() {
        composer=column();composer.setPadding(dp(10),dp(6),dp(10),dp(6));
        composer.setBackground(round(Color.BLACK,26));composer.setVisibility(View.GONE);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(dp(8),dp(6),dp(8),dp(8));sheet.addView(composer,params);
        draft=new EditText(this);draft.setTextColor(TEXT);draft.setHintTextColor(MUTED);
        draft.setTextSize(15);draft.setHint("输入命令或文本…");draft.setBackgroundColor(Color.TRANSPARENT);
        draft.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        draft.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        draft.setMinLines(1);draft.setMaxLines(4);draft.setPadding(dp(8),dp(10),dp(8),dp(8));
        draft.setContentDescription("本地文本草稿");composer.addView(draft,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout actions=row();composer.addView(actions,new LinearLayout.LayoutParams(-1,dp(44)));
        actions.addView(icon("paste","粘贴到草稿",this::paste),new LinearLayout.LayoutParams(dp(44),dp(44)));
        actions.addView(key("×","关闭文本框，保留草稿",()->setDraftMode(false,true)),new LinearLayout.LayoutParams(dp(44),dp(44)));
        TextView hint=label("长按发送：不回车",10,MUTED);hint.setGravity(Gravity.CENTER);
        actions.addView(hint,new LinearLayout.LayoutParams(0,-1,1));
        actions.addView(icon("keyboard","显示或隐藏键盘",this::toggleKeyboard),new LinearLayout.LayoutParams(dp(44),dp(44)));
        TextView send=icon("send","发送并回车；长按仅发送",()->sendDraft(true));
        send.setBackground(round(0xff123d28,22));send.setOnLongClickListener(v->{sendDraft(false);return true;});
        actions.addView(send,new LinearLayout.LayoutParams(dp(44),dp(44)));
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
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(168),dp(216),Gravity.TOP|Gravity.LEFT);
        p.leftMargin=dp(80);p.topMargin=dp(100);viewport.addView(pad,p);
        LinearLayout handle=row();pad.addView(handle,new LinearLayout.LayoutParams(-1,dp(44)));
        TextView drag=key("━━","拖动方向键浮板",()->{});drag.setTextColor(MUTED);
        handle.addView(drag,new LinearLayout.LayoutParams(0,-1,1));
        lockButton=icon("unlock","锁定方向键位置",()->{
            padLocked=!padLocked;
            lockButton.setCompoundDrawablesWithIntrinsicBounds(null,new ToolbarIcon(padLocked?"lock":"unlock",dp(22),padLocked?GREEN:MUTED),null,null);
            lockButton.setContentDescription(padLocked?"解锁方向键位置":"锁定方向键位置");
        });handle.addView(lockButton,new LinearLayout.LayoutParams(dp(44),dp(44)));
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
        padRow(new String[]{"⌫","↑","×"},new String[]{"退格","上","关闭方向键浮板"},new Runnable[]{()->sendKey(TerminalSession.Key.BACKSPACE),()->sendKey(TerminalSession.Key.UP),this::togglePad});
        padRow(new String[]{"←","↵","→"},new String[]{"左","回车","右"},new Runnable[]{()->sendKey(TerminalSession.Key.LEFT),()->sendKey(TerminalSession.Key.ENTER),()->sendKey(TerminalSession.Key.RIGHT)});
        padRow(new String[]{"","↓",""},new String[]{"","下",""},new Runnable[]{null,()->sendKey(TerminalSession.Key.DOWN),null});
        pad.setVisibility(View.GONE);
    }
    private void padRow(String[] titles,String[] descriptions,Runnable[] actions) {
        LinearLayout row=row();pad.addView(row,new LinearLayout.LayoutParams(-1,dp(56)));
        for(int i=0;i<3;i++){
            View view=actions[i]==null?new View(this):key(titles[i],descriptions[i],actions[i]);
            if(view instanceof TextView)((TextView)view).setTextSize(24);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(52),1);p.setMargins(dp(2),dp(2),dp(2),dp(2));row.addView(view,p);
        }
    }
    private void togglePad() {
        boolean show=pad.getVisibility()!=View.VISIBLE;pad.setVisibility(show?View.VISIBLE:View.GONE);
        padButton.setActivated(show);if(show)pad.post(this::clampPad);
    }
    private void clampPad() {
        if(pad==null||viewport.getWidth()==0||pad.getWidth()==0)return;
        pad.setX(Math.max(0,Math.min(pad.getX(),viewport.getWidth()-pad.getWidth())));
        pad.setY(Math.max(0,Math.min(pad.getY(),viewport.getHeight()-pad.getHeight())));
    }
    private void buildHome() {
        home=new ScrollView(this);LinearLayout content=column();content.setPadding(dp(20),dp(24),dp(20),dp(24));home.addView(content);
        TextView heading=label("会话",24,TEXT);content.addView(heading);
        TextView note=label("本地演示 · 未连接远程主机",13,MUTED);note.setPadding(0,dp(8),0,dp(24));content.addView(note);
        LinearLayout card=column();card.setPadding(dp(18),dp(18),dp(18),dp(18));card.setBackground(round(SURFACE,20));
        card.setContentDescription("恢复 Ghostty 本地会话");card.setOnClickListener(v->resumeSession());
        content.addView(card,new LinearLayout.LayoutParams(-1,dp(220)));
        card.addView(label("Ghostty · LOCAL",14,TEXT));
        sessionPreview=new ImageView(this);sessionPreview.setScaleType(ImageView.ScaleType.FIT_START);
        sessionPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams previewParams=new LinearLayout.LayoutParams(-1,0,1);
        previewParams.topMargin=dp(16);previewParams.bottomMargin=dp(12);card.addView(sessionPreview,previewParams);
        card.addView(label("点击继续会话",12,0xffa8d8b1));
        content.addView(label("最小化保留本次会话；退出应用后重置。",12,MUTED));
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
        menu.getMenu().findItem(4).setEnabled(checkpoint!=null);
        menu.setOnMenuItemClickListener(item->{
            switch(item.getItemId()){
                case 0:terminal.scrollToBottom();break;
                case 1:demo();break;
                case 2:for(int i=1;i<=180;i++)write(String.format(java.util.Locale.ROOT,"L%03d 中文 · 👨‍👩‍👧‍👦 · scrollback\r\n",i));break;
                case 3:checkpoint=session.checkpoint();toast("已保存本地快照");break;
                case 4:TerminalSession restored=TerminalSession.restore(checkpoint);terminal.setSession(null);session.close();session=restored;terminal.setSession(session);toast("已恢复本地快照");break;
                default:return false;
            }return true;
        });menu.show();
    }
    private void echo(byte[] bytes) {
        String text=new String(bytes,StandardCharsets.UTF_8);
        if(ctrlArmed){setCtrl(false);if(text.length()==1&&text.charAt(0)>=64&&text.charAt(0)<128)text=Character.toString((char)(text.charAt(0)&31));}
        if(text.equals("\r")||text.equals("\n")){write("\r\n");return;}
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
        TextView v=label(title,14,TEXT);v.setGravity(Gravity.CENTER);v.setTypeface(Typeface.MONOSPACE);
        v.setSingleLine();v.setEllipsize(TextUtils.TruncateAt.END);v.setContentDescription(description);
        v.setFocusable(true);v.setBackground(new RippleDrawable(ColorStateList.valueOf(0xff35433c),round(KEY,22),null));
        v.setOnClickListener(w->action.run());return v;
    }
    private TextView icon(String name,String description,Runnable action) {
        TextView v=key("",description,action);v.setCompoundDrawablesWithIntrinsicBounds(null,new ToolbarIcon(name,dp(22),TEXT),null,null);
        v.setPadding(0,dp(11),0,0);return v;
    }
    @Override protected void onDestroy(){
        directInput.setTerminalView(null);terminal.setListener(null);terminal.setSession(null);session.close();
        sessionPreview.setImageDrawable(null);if(previewBitmap!=null)previewBitmap.recycle();
        super.onDestroy();
    }
}
