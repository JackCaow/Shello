package dev.ghostty.android.sample;

import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import dev.ghostty.android.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.*;
import static org.junit.Assert.*;

/** Operates the real Activity controls with a disposable local VT; never sends to a user's SSH tab. */
public class InputRuntimeTest extends AttachmentTestSupport {
    InputPreferences config;Map<String,?> before;SshSessionService owner;SshSessionService.Tab fixture;
    String previous;TerminalControls controls;TerminalView terminal;TerminalView.Listener previousListener;
    final List<String> bytes=new ArrayList<>();
    @Before public void fixture()throws Exception{
        config=new InputPreferences(context);before=config.prefs.getAll();config.prefs.edit().clear().commit();open();
        inst.runOnMainSync(()->{owner=(SshSessionService)field(activity,"sshService");previous=owner.selected()==null?null:owner.selected().id;fixture=new SshSessionService.Tab("input-audit-"+UUID.randomUUID(),null);fixture.local=new TerminalSession(40,10);
            @SuppressWarnings("unchecked") List<SshSessionService.Tab> tabs=(List<SshSessionService.Tab>)field(owner,"tabs");tabs.add(fixture);owner.select(fixture.id);call(activity,"resumeSession");
            controls=(TerminalControls)field(activity,"inputControls");terminal=(TerminalView)field(activity,"terminal");previousListener=(TerminalView.Listener)field(terminal,"listener");terminal.setListener(new TerminalView.Listener(){public void onInput(byte[] b){bytes.add(new String(b,StandardCharsets.UTF_8));}public void onResize(int c,int r){}});
        });SystemClock.sleep(100);
    }
    @After public void cleanup(){inst.runOnMainSync(()->{if(terminal!=null)terminal.setListener(previousListener);if(owner!=null&&fixture!=null){owner.close(fixture.id);if(previous!=null)owner.select(previous);}if(activity!=null)call(activity,"minimize");SharedPreferences.Editor e=config.prefs.edit().clear();for(var v:before.entrySet()){Object value=v.getValue();if(value instanceof String)e.putString(v.getKey(),(String)value);else if(value instanceof Boolean)e.putBoolean(v.getKey(),(Boolean)value);else if(value instanceof Float)e.putFloat(v.getKey(),(Float)value);else if(value instanceof Integer)e.putInt(v.getKey(),(Integer)value);else if(value instanceof Long)e.putLong(v.getKey(),(Long)value);}e.commit();if(controls!=null)controls.refresh();});}
    @Override void capture(String name)throws Exception{SystemClock.sleep(650);inst.runOnMainSync(()->assertTrue(activity.hasWindowFocus()));super.capture(name);}
    @Test public void liveToolbarComposerAndCustomSequenceShareConfiguration()throws Exception{
        inst.runOnMainSync(()->{String id=config.put(null,"中文快捷键","text:中文 👨‍👩‍👧‍👦",true);config.save("layout_toolbar",List.of(id,"key_TAB","ctrl"));config.save("layout_composer",List.of("key_ESCAPE",id));controls.refresh();LinearLayout bar=(LinearLayout)field(activity,"toolbar");assertEquals(3,bar.getChildCount());InputToolbar container=(InputToolbar)field(activity,"toolbarContainer");assertEquals(2,container.pinnedButtons.getChildCount());assertEquals("chat",container.pinnedButtons.getChildAt(0).getTag());assertEquals("keyboard",container.pinnedButtons.getChildAt(1).getTag());assertEquals(id,bar.getChildAt(0).getTag());bar.getChildAt(0).performClick();assertEquals(List.of("中文 👨‍👩‍👧‍👦","\r"),bytes);bytes.clear();controls.sequence("C-b, S-TAB",false);assertEquals(List.of("\002","\033[Z"),bytes);String alias=config.put(null,"Panel alias","action:shortcuts",false);controls.perform(alias);assertEquals(View.VISIBLE,((View)field(controls,"panel")).getVisibility());assertTrue(controls.back());
            LinearLayout composer=(LinearLayout)field(activity,"configuredComposer");assertEquals(2,composer.getChildCount());assertEquals(id,composer.getChildAt(1).getTag());});capture("input-toolbar");
    }
    @Test public void attachmentPreviewAndRemoveHaveSeparateUsableTargets()throws Exception{
        AttachmentTransfer upload=new AttachmentTransfer(context,"unused",AttachmentTestProvider.uri("normal"),()->{});upload.name="设计说明 · 中文 long filename.pdf";upload.bytes=4096;upload.busy=false;upload.path="/tmp/ui-fixture.pdf";
        inst.runOnMainSync(()->{fixture.attachments.add(upload);List<String> actions=new ArrayList<>(config.list("layout_composer"));for(int i=1;i<=12;i++)actions.add("key_F"+i);config.save("layout_composer",actions);controls.refresh();});draftMode();inst.runOnMainSync(()->{call(activity,"hideKeyboard");call(activity,"updateAttachmentStatus");((EditText)field(activity,"draft")).setText("检查附件与输入布局");});inst.waitForIdleSync();
        capture("input-composer-attachments");inst.runOnMainSync(()->call(activity,"showKeyboard"));await(()->(Boolean)field(activity,"imeVisible"));capture("input-composer-keyboard");
        HorizontalScrollView[] scroll={null};View[] pinned=new View[2];int[][] positions={new int[2],new int[2]};
        inst.runOnMainSync(()->{
            View keys=(View)field(activity,"configuredComposer");scroll[0]=(HorizontalScrollView)keys.getParent().getParent();
            pinned[0]=find((View)field(activity,"composer"),AppLanguage.tr(R.string.input_dictation));pinned[1]=(View)field(activity,"sendButton");
            assertNotNull(pinned[0]);for(int i=0;i<2;i++){pinned[i].getLocationOnScreen(positions[i]);android.graphics.Rect visible=new android.graphics.Rect();assertTrue("Pinned composer action must stay fully visible above the IME",pinned[i].getGlobalVisibleRect(visible)&&visible.height()>=pinned[i].getHeight()-1);}assertTrue(scroll[0].getChildAt(0).getWidth()>scroll[0].getWidth());
        });
        int[] origin=new int[2];inst.runOnMainSync(()->scroll[0].getLocationOnScreen(origin));long down=SystemClock.uptimeMillis();
        for(int i=0;i<=12;i++){MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),i==0?MotionEvent.ACTION_DOWN:i==12?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,origin[0]+scroll[0].getWidth()*(.85f-.7f*i/12),origin[1]+scroll[0].getHeight()/2f,0);inst.sendPointerSync(event);event.recycle();SystemClock.sleep(15);}SystemClock.sleep(250);
        inst.runOnMainSync(()->{
            assertTrue("Composer utility actions did not scroll",scroll[0].getScrollX()>0);for(int i=0;i<2;i++){int[] after=new int[2];pinned[i].getLocationOnScreen(after);assertArrayEquals("A pinned composer action moved",positions[i],after);}
            scroll[0].scrollTo(scroll[0].getChildAt(0).getWidth(),0);View close=find((View)field(activity,"composer"),AppLanguage.tr(R.string.close_editor_and_keep_draft));android.graphics.Rect visible=new android.graphics.Rect();assertTrue(close.getGlobalVisibleRect(visible));assertTrue("Close editor cannot be reached by scrolling",visible.width()>=close.getWidth()-1);assertTrue(bytes.isEmpty());
            ((ComposerBody)field(activity,"composerBody")).scrollTo(0,0);ViewGroup tray=(ViewGroup)field(activity,"attachmentCards");assertEquals(1,tray.getChildCount());ViewGroup card=(ViewGroup)tray.getChildAt(0);View remove=find(card,AppLanguage.tr(R.string.attachment_remove,upload.name));assertNotNull(remove);
            assertTrue(remove.getWidth()>=SampleUi.dp(activity,48)&&remove.getHeight()>=SampleUi.dp(activity,48));
            assertTrue(card.isClickable());assertEquals(SampleUi.dp(activity,72),card.getWidth());assertEquals(SampleUi.dp(activity,72),card.getHeight());
            assertEquals("Remove must stay in the upper-right corner",card.getWidth(),remove.getRight());assertEquals(0,remove.getTop());
            for(int i=0;i<card.getChildCount();i++)assertFalse("Attachment tray must not show filename or size labels",card.getChildAt(i) instanceof TextView);
            long tap=SystemClock.uptimeMillis();for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){MotionEvent e=MotionEvent.obtain(tap,tap+20,action,SampleUi.dp(activity,12),SampleUi.dp(activity,60),0);card.dispatchTouchEvent(e);e.recycle();}
        });await(()->field(activity,"attachmentPreview")!=null&&((android.app.Dialog)field(activity,"attachmentPreview")).isShowing());
        inst.runOnMainSync(()->{
            android.app.Dialog detail=(android.app.Dialog)field(activity,"attachmentPreview");assertEquals("Preview must not remove the attachment",1,fixture.attachments.size());detail.dismiss();
            ViewGroup card=(ViewGroup)((ViewGroup)field(activity,"attachmentCards")).getChildAt(0);View remove=find(card,AppLanguage.tr(R.string.attachment_remove,upload.name));
            long tap=SystemClock.uptimeMillis();for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){MotionEvent e=MotionEvent.obtain(tap,tap+20,action,remove.getLeft()+remove.getWidth()/2f,remove.getTop()+remove.getHeight()/2f,0);card.dispatchTouchEvent(e);e.recycle();}
        });await(()->fixture.attachments.isEmpty());inst.runOnMainSync(()->{assertFalse("Corner tap must not reopen preview",((android.app.Dialog)field(activity,"attachmentPreview")).isShowing());assertTrue("Removal must not submit terminal input",bytes.isEmpty());});
    }
    @Test public void controlLongPressOpensCorrectAgentGroupsAndLockRetainsPanel()throws Exception{
        inst.runOnMainSync(()->{View ctrl=((LinearLayout)field(activity,"toolbar")).getChildAt(0);long t=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(t,t,0,12,12,0);ctrl.dispatchTouchEvent(down);down.recycle();});SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+100);
        inst.runOnMainSync(()->{View ctrl=((LinearLayout)field(activity,"toolbar")).getChildAt(0);long t=SystemClock.uptimeMillis();MotionEvent up=MotionEvent.obtain(t,t,1,12,12,0);ctrl.dispatchTouchEvent(up);up.recycle();LinearLayout panel=(LinearLayout)field(controls,"panel");assertEquals(View.VISIBLE,panel.getVisibility());View codex=find(panel,"Codex"+AppLanguage.tr(R.string.input_shortcuts_2));assertNotNull(codex);codex.performClick();LinearLayout keys=(LinearLayout)field(controls,"keys");InputButton first=(InputButton)((LinearLayout)keys.getChildAt(0)).getChildAt(0);assertEquals("/new",first.getText().toString());first.performClick();assertEquals(List.of("/new","\r"),bytes);assertEquals(View.GONE,panel.getVisibility());bytes.clear();controls.perform("lock_shortcuts");controls.perform("preset_Claude_2");assertEquals(View.VISIBLE,panel.getVisibility());assertEquals(List.of("/compact","\r"),bytes);});capture("input-shortcuts");
    }
    @Test public void toolbarSwipeWorksOverButtonsAndBackground(){
        inst.runOnMainSync(()->{config.mapping("toolbar_up","ctrl_c");controls.refresh();View scroll=(View)field(activity,"toolScroll");for(float x:new float[]{1,scroll.getWidth()/2f}){long t=SystemClock.uptimeMillis();float y=scroll.getHeight()*.8f,end=y-activity.getResources().getDisplayMetrics().density*45;for(int action:new int[]{0,2,1}){MotionEvent event=MotionEvent.obtain(t,t+(action==0?0:action==2?40:80),action,x,action==0?y:end,0);scroll.dispatchTouchEvent(event);event.recycle();}}assertEquals(List.of("\003","\003"),bytes);});
    }
    @Test public void pinnedInputAndKeyboardStayPutDuringHorizontalAndVerticalSwipes()throws Exception{
        inst.runOnMainSync(()->{List<String> ids=new ArrayList<>(List.of("chat","ctrl","key_ESCAPE","key_TAB","keyboard"));for(int i=1;i<=12;i++)ids.add("key_F"+i);config.save("layout_toolbar",ids);config.mapping("toolbar_up","ctrl_c");controls.refresh();});
        inst.waitForIdleSync();SystemClock.sleep(100);
        InputToolbar bar=(InputToolbar)field(activity,"toolbarContainer");int[][] pins={new int[2],new int[2]};
        inst.runOnMainSync(()->{assertEquals(2,bar.pinnedButtons.getChildCount());assertEquals("chat",bar.pinnedButtons.getChildAt(0).getTag());assertEquals("keyboard",bar.pinnedButtons.getChildAt(1).getTag());for(int i=0;i<2;i++)bar.pinnedButtons.getChildAt(i).getLocationOnScreen(pins[i]);assertTrue(bar.scrollingButtons.getWidth()>bar.scroll.getWidth());assertTrue(bar.pinnedButtons.getRight()<=bar.getWidth());});
        for(boolean left:new boolean[]{true,false}){
            int beforeScroll=bar.scroll.getScrollX();int[] xy=new int[2];inst.runOnMainSync(()->bar.scroll.getLocationOnScreen(xy));long t=SystemClock.uptimeMillis();float start=xy[0]+bar.scroll.getWidth()*(left?.85f:.15f),end=xy[0]+bar.scroll.getWidth()*(left?.15f:.85f),y=xy[1]+bar.scroll.getHeight()/2f;
            for(int i=0;i<=12;i++){MotionEvent event=MotionEvent.obtain(t,SystemClock.uptimeMillis(),i==0?MotionEvent.ACTION_DOWN:i==12?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,start+(end-start)*i/12,y,0);inst.sendPointerSync(event);event.recycle();SystemClock.sleep(15);}SystemClock.sleep(250);
            inst.runOnMainSync(()->{assertTrue("Horizontal scrolling did not move in the requested direction",left?bar.scroll.getScrollX()>beforeScroll:bar.scroll.getScrollX()<beforeScroll);for(int i=0;i<2;i++){int[] after=new int[2];bar.pinnedButtons.getChildAt(i).getLocationOnScreen(after);assertArrayEquals("A pinned action moved with the scroll content",pins[i],after);}assertTrue("Scrolling accidentally sent terminal input",bytes.isEmpty());});
        }
        inst.runOnMainSync(()->{
            float x=bar.pinnedButtons.getX()+bar.pinnedButtons.getChildAt(0).getX()+bar.pinnedButtons.getChildAt(0).getWidth()/2f,from=bar.getHeight()*.9f,to=from-activity.getResources().getDisplayMetrics().density*40;long now=SystemClock.uptimeMillis();for(int action:new int[]{0,2,1}){MotionEvent e=MotionEvent.obtain(now,now+(action==0?0:action==2?40:80),action,x,action==0?from:to,0);bar.dispatchTouchEvent(e);e.recycle();}assertEquals(List.of("\003"),bytes);assertFalse((Boolean)field(activity,"draftMode"));
            bar.pinnedButtons.getChildAt(0).performClick();assertTrue((Boolean)field(activity,"draftMode"));assertEquals(View.GONE,bar.getVisibility());controls.hardware(new KeyEvent(0,0,0,KeyEvent.KEYCODE_J,0,KeyEvent.META_META_ON));assertEquals(View.VISIBLE,bar.getVisibility());});capture("input-pinned-toolbar");
    }
    @Test public void singleAndTripleTapDoNotLeakDoubleActions()throws Exception{
        inst.runOnMainSync(()->{config.mapping("single_tap","key_TAB");config.mapping("double_tap","ctrl_c");config.mapping("triple_tap","key_ESCAPE");View touch=terminal.getChildAt(1);long t=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(t,t,0,40,40,0),up=MotionEvent.obtain(t,t+20,1,40,40,0);touch.dispatchTouchEvent(down);touch.dispatchTouchEvent(up);down.recycle();up.recycle();});SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout()+100);
        inst.runOnMainSync(()->{assertEquals(List.of("\t"),bytes);bytes.clear();View touch=terminal.getChildAt(1);long t=SystemClock.uptimeMillis();for(int i=0;i<3;i++){MotionEvent down=MotionEvent.obtain(t+i*80,t+i*80,0,40,40,0),up=MotionEvent.obtain(t+i*80,t+i*80+20,1,40,40,0);touch.dispatchTouchEvent(down);touch.dispatchTouchEvent(up);down.recycle();up.recycle();}});SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout()+100);inst.runOnMainSync(()->assertEquals(List.of("\033"),bytes));
    }
    @Test public void doubleCtrlLocksModifierUntilDisabled(){
        inst.runOnMainSync(()->{View ctrl=((LinearLayout)field(activity,"toolbar")).getChildAt(0);long t=SystemClock.uptimeMillis();for(int i=0;i<2;i++){MotionEvent down=MotionEvent.obtain(t+i*80,t+i*80,0,12,12,0),up=MotionEvent.obtain(t+i*80,t+i*80+20,1,12,12,0);ctrl.dispatchTouchEvent(down);ctrl.dispatchTouchEvent(up);down.recycle();up.recycle();}assertTrue((Boolean)field(controls,"lockedCtrl"));TerminalInputView input=(TerminalInputView)field(activity,"directInput");input.onKeyDown(KeyEvent.KEYCODE_C,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_C));input.onKeyDown(KeyEvent.KEYCODE_D,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_D));assertEquals(List.of("\003","\004"),bytes);assertTrue((Boolean)field(controls,"lockedCtrl"));ctrl.performClick();assertFalse((Boolean)field(controls,"lockedCtrl"));});
    }
    @Test public void configurableDoubleTapAndVolumeEmitExactlyOnce()throws Exception{
        inst.runOnMainSync(()->{config.mapping("double_tap","key_ESCAPE");config.mapping("volume_up","ctrl_c");View touch=terminal.getChildAt(1);long t=SystemClock.uptimeMillis();for(int i=0;i<2;i++){MotionEvent down=MotionEvent.obtain(t+i*80,t+i*80,0,40,40,0),up=MotionEvent.obtain(t+i*80,t+i*80+20,1,40,40,0);touch.dispatchTouchEvent(down);touch.dispatchTouchEvent(up);down.recycle();up.recycle();}});SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout()+80);
        inst.runOnMainSync(()->{assertEquals(List.of("\033"),bytes);bytes.clear();assertTrue(controls.hardware(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_UP)));assertTrue(controls.hardware(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_VOLUME_UP)));assertEquals(List.of("\003"),bytes);assertFalse(controls.hardware(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_DOWN)));});
    }
    void pointer(View touch,long down,long time,int action,float x1,float y1,float x2,float y2){
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[2];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[2];for(int i=0;i<2;i++){properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i]=new MotionEvent.PointerCoords();coords[i].pressure=1;coords[i].size=1;coords[i].x=i==0?x1:x2;coords[i].y=i==0?y1:y2;}
        MotionEvent event=MotionEvent.obtain(down,time,action,2,properties,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);touch.dispatchTouchEvent(event);event.recycle();
    }
    @Test public void twoFingerSwipeFiresOnceAndZenHoldCancelsCleanly()throws Exception{
        inst.runOnMainSync(()->{config.mapping("two_left","ctrl_c");View touch=terminal.getChildAt(1);long t=SystemClock.uptimeMillis();MotionEvent start=MotionEvent.obtain(t,t,0,280,200,0);touch.dispatchTouchEvent(start);start.recycle();pointer(touch,t,t+20,MotionEvent.ACTION_POINTER_DOWN|(1<<8),280,200,480,200);pointer(touch,t,t+50,MotionEvent.ACTION_MOVE,100,200,300,200);pointer(touch,t,t+80,MotionEvent.ACTION_POINTER_UP|(1<<8),100,200,300,200);MotionEvent end=MotionEvent.obtain(t,t+100,1,100,200,0);touch.dispatchTouchEvent(end);end.recycle();assertEquals(List.of("\003"),bytes);bytes.clear();config.setFlag("zen_mode",true);controls.refresh();InputToolbar bar=(InputToolbar)field(activity,"toolbarContainer");assertEquals(2,bar.scrollingButtons.getChildCount());assertEquals(View.GONE,bar.pinnedButtons.getVisibility());});
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+100);
        inst.runOnMainSync(()->{assertEquals("Multitouch left a pending long-press selection", "",terminal.getSelectedText());View touch=terminal.getChildAt(1);long t=SystemClock.uptimeMillis();for(int i=0;i<2;i++){MotionEvent e=MotionEvent.obtain(t+i*80,t+i*80,0,60,60,0);touch.dispatchTouchEvent(e);e.recycle();if(i==0){e=MotionEvent.obtain(t,t+20,1,60,60,0);touch.dispatchTouchEvent(e);e.recycle();}}});
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+150);inst.runOnMainSync(()->assertNotNull("Zen ring did not open; taps="+field(terminal.getChildAt(1),"tapCount")+", held="+field(terminal.getChildAt(1),"doubleHeld")+", zen="+config.flag("zen_mode",false)+", minimized="+field(activity,"minimized")+", focus="+activity.hasWindowFocus(),field(controls,"ring")));capture("input-zen");
        inst.runOnMainSync(()->{View touch=terminal.getChildAt(1);long t=SystemClock.uptimeMillis();pointer(touch,t,t,MotionEvent.ACTION_POINTER_DOWN|(1<<8),60,60,200,60);assertNull("Adding a second finger did not cancel Zen hold",field(controls,"ring"));MotionEvent e=MotionEvent.obtain(t,t,MotionEvent.ACTION_CANCEL,60,60,0);touch.dispatchTouchEvent(e);e.recycle();assertNull(field(controls,"ring"));assertTrue(bytes.isEmpty());});
    }
    @Test public void configuredPullDownWorksWithScrollback()throws Exception{
        inst.runOnMainSync(()->{for(int i=0;i<120;i++)fixture.local.write(("history "+i+"\r\n").getBytes(StandardCharsets.UTF_8));config.mapping("pull_down","ctrl_c");});SystemClock.sleep(100);
        inst.runOnMainSync(()->{View touch=terminal.getChildAt(1);long t=SystemClock.uptimeMillis();float start=50,end=start+activity.getResources().getDisplayMetrics().density*90;for(int action:new int[]{0,2,1}){MotionEvent event=MotionEvent.obtain(t,t+(action==0?0:action==2?40:80),action,60,action==0?start:end,0);touch.dispatchTouchEvent(event);event.recycle();}assertEquals(List.of("\003"),bytes);});
    }
    @Test public void pinchChangesFontWithoutSelectingOrSendingInput(){
        android.content.SharedPreferences appearance=context.getSharedPreferences("appearance",0);Object original=appearance.getAll().get("fontSize");
        try{inst.runOnMainSync(()->{appearance.edit().putInt("fontSize",14).commit();terminal.setFontSize(14);View touch=terminal.getChildAt(1);float width=terminal.getWidth(),y=terminal.getHeight()*.4f;long t=SystemClock.uptimeMillis();MotionEvent e=MotionEvent.obtain(t,t,0,width*.25f,y,0);touch.dispatchTouchEvent(e);e.recycle();pointer(touch,t,t+20,MotionEvent.ACTION_POINTER_DOWN|(1<<8),width*.25f,y,width*.75f,y);pointer(touch,t,t+60,MotionEvent.ACTION_MOVE,width*.2f,y,width*.8f,y);pointer(touch,t,t+100,MotionEvent.ACTION_MOVE,width*.1f,y,width*.9f,y);pointer(touch,t,t+140,MotionEvent.ACTION_POINTER_UP|(1<<8),width*.1f,y,width*.9f,y);e=MotionEvent.obtain(t,t+180,1,width*.1f,y,0);touch.dispatchTouchEvent(e);e.recycle();assertTrue("Pinch did not update font size",appearance.getInt("fontSize",0)>14);assertTrue(bytes.isEmpty());assertEquals("",terminal.getSelectedText());});}
        finally{inst.runOnMainSync(()->{android.content.SharedPreferences.Editor edit=appearance.edit();if(original==null)edit.remove("fontSize");else edit.putInt("fontSize",(Integer)original);edit.commit();terminal.setFontSize(original==null?14:(Integer)original);});}
    }
    @Test public void commandKeyboardNavigationTargetsOnlyDisposableTabs(){
        inst.runOnMainSync(()->{
            java.util.Set<String> originalIds=new java.util.HashSet<>();for(SshSessionService.Tab tab:owner.tabs())if(tab!=fixture)originalIds.add(tab.id);
            SshSessionService.Tab second=new SshSessionService.Tab("input-audit-"+UUID.randomUUID(),null);second.local=new TerminalSession(40,10);
            @SuppressWarnings("unchecked") List<SshSessionService.Tab> tabs=(List<SshSessionService.Tab>)field(owner,"tabs");tabs.add(second);
            try{owner.move(fixture.id,0);owner.move(second.id,1);
                activity.dispatchKeyEvent(new KeyEvent(0,0,0,KeyEvent.KEYCODE_J,0,KeyEvent.META_META_ON));assertTrue((Boolean)field(activity,"draftMode"));
                activity.dispatchKeyEvent(new KeyEvent(0,0,0,KeyEvent.KEYCODE_J,0,KeyEvent.META_META_ON));assertFalse((Boolean)field(activity,"draftMode"));
                activity.dispatchKeyEvent(new KeyEvent(0,0,0,KeyEvent.KEYCODE_2,0,KeyEvent.META_META_ON));assertSame(second,owner.selected());
                activity.dispatchKeyEvent(new KeyEvent(0,0,0,KeyEvent.KEYCODE_1,0,KeyEvent.META_META_ON));assertSame(fixture,owner.selected());
                activity.dispatchKeyEvent(new KeyEvent(0,0,0,KeyEvent.KEYCODE_2,0,KeyEvent.META_META_ON));assertSame(second,owner.selected());
                activity.dispatchKeyEvent(new KeyEvent(0,0,0,KeyEvent.KEYCODE_W,0,KeyEvent.META_META_ON));assertNull(owner.find(second.id));
                for(String id:originalIds)assertNotNull("An existing user tab was closed",owner.find(id));
            }finally{owner.close(second.id);owner.select(fixture.id);}
        });
    }
    @Test public void compactZenControlsFitWithoutOverlapping(){
        inst.runOnMainSync(()->{
            for(boolean overflow:new boolean[]{false,true}){
                if(overflow){List<String> ids=new ArrayList<>(config.defaults("layout_zen"));for(int i=1;i<=12;i++)ids.add("key_F"+i);config.save("layout_zen",ids);}
                ZenRing ring=new ZenRing(activity,config,id->{});int width=SampleUi.dp(activity,400),height=SampleUi.dp(activity,60);
                ring.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));ring.layout(0,0,width,height);
                List<android.graphics.Rect> rectangles=new ArrayList<>();
                for(View button:ring.buttons()){
                    android.graphics.Rect bounds=new android.graphics.Rect(button.getLeft(),button.getTop(),button.getRight(),button.getBottom());
                    assertTrue(bounds.left>=0&&bounds.top>=0&&bounds.bottom<=height);
                    assertTrue("Zen target is too small",button.getWidth()>=SampleUi.dp(activity,48)&&button.getHeight()>=SampleUi.dp(activity,48));
                    for(android.graphics.Rect prior:rectangles)assertFalse("Compact Zen controls overlap",android.graphics.Rect.intersects(prior,bounds));rectangles.add(bounds);
                }
                View last=ring.buttons().get(ring.buttons().size()-1);ring.scrollTo(last.getRight()-width,0);
                assertTrue("Trailing Zen action is unreachable",last.getLeft()-ring.getScrollX()>=0&&last.getRight()-ring.getScrollX()<=width);
                if(overflow)assertTrue("Overflow must scroll instead of shrinking controls",ring.getScrollX()>0);
            }
        });
    }
    @Test public void toolbarIconsActuallyRenderVisibleStrokes(){
        inst.runOnMainSync(()->{LinearLayout toolbar=(LinearLayout)field(activity,"toolbar");View icon=null;for(int i=0;i<toolbar.getChildCount();i++)if("pad".equals(toolbar.getChildAt(i).getTag()))icon=toolbar.getChildAt(i);assertNotNull(icon);assertTrue(icon.getWidth()>0);android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(toolbar.getWidth(),toolbar.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);toolbar.draw(new android.graphics.Canvas(bitmap));int bright=0;for(int y=icon.getTop();y<icon.getBottom();y++)for(int x=icon.getLeft();x<icon.getRight();x++){int color=bitmap.getPixel(x,y);if(android.graphics.Color.red(color)>170&&android.graphics.Color.green(color)>170&&android.graphics.Color.blue(color)>170)bright++;}bitmap.recycle();assertTrue("D-Pad icon was invisible",bright>20);});
    }
    private void dragPad(View handle){long t=SystemClock.uptimeMillis();for(int action:new int[]{0,2,1}){MotionEvent event=MotionEvent.obtain(t,t+(action==0?0:action==2?40:80),action,action==0?10:50,action==0?10:90,0);handle.dispatchTouchEvent(event);event.recycle();}}
    @Test public void padFoldLockAndKeyboardAppShortcut()throws Exception{
        inst.runOnMainSync(()->controls.perform("pad"));SystemClock.sleep(150);
        inst.runOnMainSync(()->{View pad=(View)field(activity,"pad");assertEquals(View.VISIBLE,pad.getVisibility());View handle=find(pad,AppLanguage.tr(R.string.input_toggle_dpad));assertNotNull(handle);float beforeX=pad.getX();dragPad(handle);assertTrue("Dragging did not move the D-Pad",pad.getX()>beforeX);assertTrue(config.prefs.contains("pad_x"));assertEquals(pad.getX()/((View)field(activity,"viewport")).getWidth(),new InputPreferences(context).prefs.getFloat("pad_x",-1),.001f);handle.performClick();assertTrue(config.flag("pad_folded",false));View lock=(View)field(activity,"lockButton");lock.performClick();assertTrue(config.flag("pad_locked",false));float lockedX=pad.getX(),lockedY=pad.getY();dragPad(handle);assertEquals(lockedX,pad.getX(),.01f);assertEquals(lockedY,pad.getY(),.01f);long t=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(t,t,0,12,12,0);handle.dispatchTouchEvent(down);down.recycle();});
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+100);
        inst.runOnMainSync(()->{assertFalse("Holding the D-Pad handle did not unlock it",config.flag("pad_locked",true));assertTrue(config.flag("pad_folded",false));View handle=find((View)field(activity,"pad"),AppLanguage.tr(R.string.input_toggle_dpad));long t=SystemClock.uptimeMillis();MotionEvent up=MotionEvent.obtain(t,t,1,12,12,0);handle.dispatchTouchEvent(up);up.recycle();assertTrue(controls.hardware(new KeyEvent(0,0,0,KeyEvent.KEYCODE_K,0,KeyEvent.META_META_ON)));assertEquals(View.VISIBLE,((View)field(controls,"panel")).getVisibility());});capture("input-pad-folded");
    }
}
