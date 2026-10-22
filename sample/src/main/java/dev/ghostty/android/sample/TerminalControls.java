package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.hardware.input.InputManager;
import android.view.*;
import android.widget.*;
import dev.ghostty.android.*;
import java.util.*;

/** Host actions stay in MainActivity; all configurable inputs share this dispatcher. */
final class TerminalControls implements InputManager.InputDeviceListener {
    interface Host {void action(String id);void composer(boolean open);void session(int index,boolean relative);boolean active();boolean keyboardVisible();}
    private final MainActivity activity;private final TerminalView terminal;private final TerminalInputView input;private final LinearLayout toolbar,sheet,composerButtons;private final InputToolbar toolbarContainer;
    private final FrameLayout viewport;private final Host host;
    private final InputManager inputManager;
    private final SharedPreferences inputPreferences,appearancePreferences;
    private boolean dirty=true,hardwareAvailable;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChanged=(p,key)->dirty=true;
    private final View.OnLayoutChangeListener viewportChanged=(v,l,t,r,b,ol,ot,or,ob)->resizeRing();private boolean draft,lockedCtrl,lockedPanel;
    private int modifiers;private LinearLayout panel,groupBar,keys;private String group="Claude";private ZenRing ring;
    private final List<ControlGestures> gestures=new ArrayList<>();
    TerminalControls(MainActivity activity,TerminalView terminal,TerminalInputView input,LinearLayout sheet,InputToolbar toolbarContainer,LinearLayout composerButtons,FrameLayout viewport,Host host){
        this.activity=activity;this.terminal=terminal;this.input=input;this.sheet=sheet;this.toolbarContainer=toolbarContainer;this.toolbar=toolbarContainer.scrollingButtons;this.composerButtons=composerButtons;this.viewport=viewport;this.host=host;
        inputPreferences=activity.getSharedPreferences(InputPreferences.FILE,0);appearancePreferences=activity.getSharedPreferences("appearance",0);inputPreferences.registerOnSharedPreferenceChangeListener(preferencesChanged);appearancePreferences.registerOnSharedPreferenceChangeListener(preferencesChanged);
        inputManager=activity.getSystemService(InputManager.class);inputManager.registerInputDeviceListener(this,null);hardwareAvailable=physicalKeyboard();viewport.addOnLayoutChangeListener(viewportChanged);terminal.setGestureListener(this::gesture);refresh();
    }
    private InputPreferences config(){return new InputPreferences(activity);}
    private int dp(int n){return SampleUi.dp(activity,n);}
    void refresh(){
        InputPreferences c=config();
        if(dirty){dirty=false;for(ControlGestures g:gestures)g.cancel();gestures.clear();renderToolbar();renderComposer();if(panel!=null&&panel.getVisibility()==View.VISIBLE)renderPanel();}
        input.setOptionAsMeta(c.flag("option_meta",false));visibility();updateModifiers();
    }
    private InputButton button(String id,String list){InputButton b=new InputButton(activity,config().action(id),config().flag("modifier_icons",false));b.setOnClickListener(v->{if(id.equals("keyboard"))mapped("keyboard_tap");else perform(id);});String prefix=id.equals("ctrl")?"ctrl":id.equals("keyboard")?"keyboard":id.equals("shortcuts")?"shortcut":null;
        if(prefix!=null){ControlGestures g=new ControlGestures(new ControlGestures.Listener(){public void single(){if(prefix.equals("keyboard"))mapped("keyboard_tap");else perform(id);}public void twice(){mapped(prefix+"_double");}public void hold(){mapped(prefix+"_long");}public void swipe(boolean up){mapped(up?"toolbar_up":"toolbar_down");}},!config().mapping(prefix+"_double").equals("none"),true);gestures.add(g);b.setOnTouchListener(g);b.setOnLongClickListener(v->{mapped(prefix+"_long");return true;});}
        else {b.setOnLongClickListener(v->{if(id.startsWith("preset_")||id.startsWith("custom_"))activity.startActivity(new Intent(activity,ShortcutEditorActivity.class).putExtra("id",id).putExtra("list",list));return true;});}
        return b;
    }
    private void renderToolbar(){List<View> buttons=new ArrayList<>();boolean zen=config().flag("zen_mode",false);List<String> ids=zen?List.of("zen","keyboard"):config().toolbar();for(String id:ids)buttons.add(button(id,"layout_toolbar"));toolbarContainer.setButtons(buttons,zen?0:2);toolbarContainer.setSwipeListener(up->mapped(up?"toolbar_up":"toolbar_down"));}
    private void renderComposer(){composerButtons.removeAllViews();for(String id:config().list("layout_composer")){InputButton b=button(id,"layout_composer");b.toolbarStyle(false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(b.preferredWidth(48,112),dp(48));p.setMargins(dp(2),0,dp(2),0);composerButtons.addView(b,p);}}
    void setActive(String id,boolean active){for(LinearLayout row:List.of(toolbar,toolbarContainer.pinnedButtons,composerButtons))for(int i=0;i<row.getChildCount();i++){View button=row.getChildAt(i);if(id.equals(button.getTag()))button.setActivated(active);}}
    void mode(boolean enabled){draft=enabled;clearModifiers();visibility();}
    private void visibility(){boolean hide=config().flag("hardware_hide",true)&&hardwareAvailable;toolbarContainer.setVisibility(draft||hide?View.GONE:View.VISIBLE);}
    private boolean physicalKeyboard(){for(int id:InputDevice.getDeviceIds()){InputDevice d=InputDevice.getDevice(id);if(d!=null&&!d.isVirtual()&&d.getKeyboardType()==InputDevice.KEYBOARD_TYPE_ALPHABETIC)return true;}return false;}
    void clearModifiers(){modifiers=0;lockedCtrl=false;updateModifiers();}
    private void updateModifiers(){input.setModifiers(modifiers,()->{modifiers=lockedCtrl?TerminalSession.CTRL:0;updateModifiers();});mark(toolbar);mark(toolbarContainer.pinnedButtons);mark(composerButtons);}
    private void mark(LinearLayout container){for(int i=0;i<container.getChildCount();i++){View v=container.getChildAt(i);Object id=v.getTag();int bit=Objects.equals(id,"ctrl")?2:Objects.equals(id,"alt")?4:Objects.equals(id,"shift")?1:Objects.equals(id,"super")?8:0;if(bit!=0)v.setActivated((modifiers&bit)!=0);}}
    void key(TerminalSession.Key key){terminal.sendKey(key,modifiers);modifiers=lockedCtrl?2:0;updateModifiers();}
    void perform(String id){
        if(id==null||id.equals("none"))return;
        switch(id){
            case "ctrl","alt","shift","super"->{int bit=id.equals("ctrl")?2:id.equals("alt")?4:id.equals("shift")?1:8;modifiers^=bit;if(bit==2)lockedCtrl=false;updateModifiers();return;}
            case "lock_ctrl"->{lockedCtrl=!lockedCtrl;modifiers=lockedCtrl?modifiers|2:modifiers&~2;updateModifiers();return;}
            case "shortcuts"->{togglePanel();return;}
            case "lock_shortcuts"->{lockedPanel=!lockedPanel;if(panel==null||panel.getVisibility()!=View.VISIBLE)showPanel();return;}
            case "zen"->{toggleRing();return;}
            case "session_next"->{host.session(1,true);return;}
            case "session_previous"->{host.session(-1,true);return;}
            case "mux_next"->{sequence(config().prefix("Tmux")+", n",false);return;}
            case "mux_previous"->{sequence(config().prefix("Tmux")+", p",false);return;}
            case "pane_next"->{sequence(config().prefix("Tmux")+", RIGHT",false);return;}
            case "pane_previous"->{sequence(config().prefix("Tmux")+", LEFT",false);return;}
        }
        InputActions.Action action=config().action(id);if(action==null)return;
        if(action.binding().startsWith("action:")){String target=action.binding().substring(7);if(target.equals(id))host.action(target);else perform(target);return;}
        sequence(action.binding(),action.enter());
    }
    void sequence(String binding,boolean enter){
        try{List<InputActions.Stroke> strokes=InputActions.parse(binding);
            if(binding.startsWith("action:")){perform(binding.substring(7));return;}
            for(InputActions.Stroke s:strokes){if(s.paste())terminal.pasteText(s.text());else if(s.key()!=null)terminal.sendKey(s.key(),s.modifiers()|modifiers);else terminal.sendCharacter(s.codePoint(),s.text(),s.modifiers()|modifiers);}
            if(enter)terminal.sendKey(TerminalSession.Key.ENTER);modifiers=lockedCtrl?2:0;updateModifiers();
            if(panel!=null&&!lockedPanel)panel.setVisibility(View.GONE);
        }catch(IllegalArgumentException e){Toast.makeText(activity,e.getMessage(),Toast.LENGTH_SHORT).show();}
    }
    private void mapped(String key){perform(config().mapping(key));}
    private boolean gesture(TerminalView.Gesture event,float x,float y){
        String name=event.name().toLowerCase(Locale.ROOT);
        if(!host.active())return false;
        if(name.startsWith("double_hold")){
            if(!config().flag("zen_mode",false))return false;
            if(name.equals("double_hold_start")){showRing();return true;}
            if(ring!=null){ring.track(x-ring.getX(),y-ring.getY());if(name.equals("double_hold_end"))ring.release();if(name.equals("double_hold_end")||name.equals("double_hold_cancel"))hideRing();}return true;
        }
        if(name.startsWith("pinch_")){if(!config().flag("pinch_font",true))return false;android.content.SharedPreferences p=activity.getSharedPreferences("appearance",0);int size=Math.max(10,Math.min(24,p.getInt("fontSize",14)+(name.equals("pinch_out")?1:-1)));p.edit().putInt("fontSize",size).apply();terminal.setFontSize(size);return true;}
        String action=config().mapping(name);if(name.equals("pull_down")&&action.equals("hide_keyboard")&&!host.keyboardVisible())return false;if(action.equals("none"))return false;perform(action);return true;
    }
    void togglePanel(){if(panel!=null&&panel.getVisibility()==View.VISIBLE)panel.setVisibility(View.GONE);else showPanel();}
    private void showPanel(){ensurePanel();renderPanel();panel.setVisibility(View.VISIBLE);}
    private void ensurePanel(){if(panel!=null)return;panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(8),dp(4),dp(8),dp(4));panel.setBackground(SampleUi.surface(activity,0xff101214,16));sheet.addView(panel,sheet.indexOfChild(toolbarContainer),new LinearLayout.LayoutParams(-1,-2));}
    private void renderPanel(){
        panel.removeAllViews();InputPreferences c=config();List<String> groups=c.list("groups");boolean upload=c.flag("shortcut_upload",true);if(!groups.contains(group)&&!(upload&&group.equals("Upload")))group=upload?"Upload":groups.isEmpty()?"":groups.get(0);
        LinearLayout head=new LinearLayout(activity);HorizontalScrollView tabs=new HorizontalScrollView(activity);tabs.setHorizontalScrollBarEnabled(false);groupBar=new LinearLayout(activity);List<String> all=new ArrayList<>();if(upload)all.add("Upload");all.addAll(groups);
        for(String name:all){View tab;
            if(c.flag("shortcut_icons_only",true)&&(name.equals("Claude")||name.equals("Codex"))){ImageView logo=new ImageView(activity);logo.setImageResource(name.equals("Codex")?R.drawable.agent_openai:R.drawable.agent_claude);logo.setImageTintList(android.content.res.ColorStateList.valueOf(name.equals(group)?SampleUi.GREEN:SampleUi.MUTED));logo.setPadding(dp(12),dp(8),dp(12),dp(8));tab=logo;}
            else{TextView b=SampleUi.text(activity,c.flag("shortcut_icons_only",true)?groupIcon(name):InputText.localize(name),14,name.equals(group)?SampleUi.GREEN:SampleUi.MUTED);b.setGravity(Gravity.CENTER);b.setMinWidth(dp(48));tab=b;}
            tab.setSelected(name.equals(group));tab.setContentDescription(name+AppLanguage.tr(R.string.input_shortcuts_2));tab.setOnClickListener(v->{group=name;renderPanel();});groupBar.addView(tab,new LinearLayout.LayoutParams(c.flag("shortcut_icons_only",true)?dp(48):-2,dp(48)));}tabs.addView(groupBar);tabs.post(()->{for(int i=0;i<groupBar.getChildCount();i++){View selected=groupBar.getChildAt(i);if(selected.isSelected()){tabs.scrollTo(Math.max(0,selected.getLeft()-(tabs.getWidth()-selected.getWidth())/2),0);break;}}});head.addView(tabs,new LinearLayout.LayoutParams(0,dp(48),1));
        head.addView(SampleUi.header(activity,"plus",AppLanguage.tr(R.string.input_new_shortcut),SampleUi.TEXT,()->activity.startActivity(new Intent(activity,ShortcutEditorActivity.class).putExtra("list","group_"+(group.equals("Upload")||group.isEmpty()?"Favorites":group)))),new LinearLayout.LayoutParams(dp(48),dp(48)));
        head.addView(SampleUi.header(activity,lockedPanel?"lock":"unlock",AppLanguage.tr(R.string.input_lock_shortcut_panel),SampleUi.TEXT,()->{lockedPanel=!lockedPanel;renderPanel();}),new LinearLayout.LayoutParams(dp(48),dp(48)));
        head.addView(SampleUi.header(activity,"settings",AppLanguage.tr(R.string.input_shortcut_settings),SampleUi.TEXT,()->SettingsNavigation.open(activity,"shortcuts")),new LinearLayout.LayoutParams(dp(48),dp(48)));
        head.addView(SampleUi.header(activity,"close",AppLanguage.tr(R.string.input_close_shortcut_panel),SampleUi.TEXT,()->panel.setVisibility(View.GONE)),new LinearLayout.LayoutParams(dp(48),dp(48)));panel.addView(head);
        HorizontalScrollView sc=new HorizontalScrollView(activity);sc.setHorizontalScrollBarEnabled(false);keys=new LinearLayout(activity);keys.setOrientation(LinearLayout.HORIZONTAL);
        if(group.equals("Upload")){for(String[] source:new String[][]{{"camera",AppLanguage.tr(R.string.input_camera)},{"image",AppLanguage.tr(R.string.input_photos_and_videos)},{"file",AppLanguage.tr(R.string.input_files)}}){TextView b=SampleUi.header(activity,source[0],source[1],SampleUi.TEXT,()->host.action("attachment_"+source[0]));keys.addView(b,new LinearLayout.LayoutParams(dp(92),dp(82)));}}
        else {if(group.equals("Tmux")&&!c.flag("tmux_hide_windows",false)){android.widget.GridLayout numbers=new android.widget.GridLayout(activity);numbers.setColumnCount(5);for(int digit=c.flag("tmux_one_based",true)?1:0;digit<(c.flag("tmux_one_based",true)?10:9);digit++){int number=digit;TextView key=SampleUi.text(activity,Integer.toString(number),14,SampleUi.TEXT);key.setGravity(Gravity.CENTER);key.setContentDescription("Tmux "+number);key.setOnClickListener(v->sequence(config().prefix("Tmux")+", "+number,false));numbers.addView(key,new android.widget.GridLayout.LayoutParams(new android.view.ViewGroup.LayoutParams(dp(48),dp(48))));}keys.addView(numbers);}
        List<String> ids=c.list("group_"+group);for(int i=0;i<ids.size();i+=2){LinearLayout col=new LinearLayout(activity);col.setOrientation(LinearLayout.VERTICAL);for(int j=i;j<Math.min(i+2,ids.size());j++){String id=ids.get(j);InputButton b=button(id,"group_"+group);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(b.preferredWidth(104,160),dp(48));p.setMargins(dp(2),dp(2),dp(2),dp(2));col.addView(b,p);}keys.addView(col);}if(ids.isEmpty())keys.addView(SampleUi.text(activity,AppLanguage.tr(R.string.input_tap_to_add_shortcuts),14,SampleUi.MUTED));}
        sc.addView(keys);panel.addView(sc,new LinearLayout.LayoutParams(-1,dp(104)));
    }
    private String groupIcon(String name){return InputActions.groupSymbol(name);}
    private void toggleRing(){if(ring!=null)hideRing();else showRing();}
    private void showRing(){hideRing();ring=new ZenRing(activity,config(),id->{perform(id);hideRing();});viewport.addView(ring,new FrameLayout.LayoutParams(1,1,Gravity.CENTER));resizeRing();ring.setElevation(dp(20));}
    private void resizeRing(){if(ring==null)return;boolean compact=viewport.getHeight()<dp(180);int count=config().list("layout_zen").size();int width=compact?Math.min(viewport.getWidth(),dp(count*48+16)):Math.min(dp(280),Math.min(viewport.getWidth(),viewport.getHeight()));int columns=Math.max(1,width/dp(48)),rows=(count+columns-1)/columns;int height=compact?Math.min(dp(rows*48+12),viewport.getHeight()):width;FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)ring.getLayoutParams();if(p.width!=width||p.height!=height){p.width=width;p.height=height;ring.setLayoutParams(p);}}
    private void hideRing(){if(ring!=null){viewport.removeView(ring);ring=null;}}
    boolean back(){if(ring!=null){hideRing();return true;}if(panel!=null&&panel.getVisibility()==View.VISIBLE){panel.setVisibility(View.GONE);return true;}return false;}
    boolean hardware(KeyEvent event){
        if(!host.active())return false;int code=event.getKeyCode();
        if(code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN){String id=config().mapping(code==KeyEvent.KEYCODE_VOLUME_UP?"volume_up":"volume_down");if(id.equals("none"))return false;if(event.getAction()==KeyEvent.ACTION_DOWN)perform(id);return true;}
        if(!event.isMetaPressed())return false;boolean known=code==KeyEvent.KEYCODE_K||code==KeyEvent.KEYCODE_J||code==KeyEvent.KEYCODE_W||code==KeyEvent.KEYCODE_V||code==KeyEvent.KEYCODE_M&&event.isShiftPressed()||code>=KeyEvent.KEYCODE_1&&code<=KeyEvent.KEYCODE_9;
        if(!known)return false;if(event.getAction()!=KeyEvent.ACTION_DOWN||event.getRepeatCount()>0)return true;
        if(code==KeyEvent.KEYCODE_K)togglePanel();else if(code==KeyEvent.KEYCODE_J)host.composer(!draft);else if(code==KeyEvent.KEYCODE_W)host.action("close_session");else if(code==KeyEvent.KEYCODE_V)host.action("clipboard");else if(code==KeyEvent.KEYCODE_M)host.action("dictation");else host.session(code-KeyEvent.KEYCODE_1,false);return true;
    }
    void pause(){for(ControlGestures g:gestures)g.cancel();terminal.cancelPendingGestures();hideRing();}
    void close(){viewport.removeOnLayoutChangeListener(viewportChanged);inputManager.unregisterInputDeviceListener(this);inputPreferences.unregisterOnSharedPreferenceChangeListener(preferencesChanged);appearancePreferences.unregisterOnSharedPreferenceChangeListener(preferencesChanged);for(ControlGestures g:gestures)g.cancel();terminal.setGestureListener(null);hideRing();}
    @Override public void onInputDeviceAdded(int id){deviceChanged();}@Override public void onInputDeviceRemoved(int id){deviceChanged();}@Override public void onInputDeviceChanged(int id){deviceChanged();}
    private void deviceChanged(){hardwareAvailable=physicalKeyboard();visibility();}
}
