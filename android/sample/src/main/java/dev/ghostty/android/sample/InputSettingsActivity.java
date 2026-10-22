package dev.ghostty.android.sample;

import android.content.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Shared ordered-list editing; no remote command is executed from a settings page. */
public abstract class InputSettingsActivity extends SettingsPageActivity {
    protected InputPreferences controls(){return new InputPreferences(this);}
    protected void openLayout(String key,String title){startActivity(new Intent(this,InputLayoutActivity.class).putExtra("key",key).putExtra("title",title));}
    protected void editShortcut(String id,String list){startActivity(new Intent(this,ShortcutEditorActivity.class).putExtra("id",id).putExtra("list",list));}
    protected void inputToggle(String title,String detail,String key,boolean fallback){toggle(title,detail,null,InputPreferences.FILE,key,fallback,this::refreshSettings);}
    protected void ordered(String key,List<String> ids,boolean groups){
        for(String id:ids){
            TextView minus=label("−",24,SampleUi.DANGER);minus.setGravity(Gravity.CENTER);minus.setContentDescription(AppLanguage.tr(R.string.input_remove)+(groups?InputText.localize(id):controls().label(id)));minus.setOnClickListener(v->{if(key.equals("layout_toolbar")&&id.equals("ctrl"))return;List<String> next=controls().editable(key);next.remove(id);controls().save(key,next);refreshSettings();});
            TextView handle=label("≡",24,SampleUi.MUTED);handle.setGravity(Gravity.CENTER);handle.setContentDescription(AppLanguage.tr(R.string.input_drag_to_reorder)+(groups?InputText.localize(id):controls().label(id)));
            LinearLayout accessory=row();accessory.addView(handle,new LinearLayout.LayoutParams(dp(48),dp(48)));
            LinearLayout line=settingRow(groups?InputText.localize(id):controls().label(id),groups?controls().list("group_"+id).size()+AppLanguage.tr(R.string.input_shortcuts):null,null,accessory,()->{if(groups)openLayout("group_"+id,id);else editShortcut(id,key);});
            line.addView(minus,0,new LinearLayout.LayoutParams(dp(48),dp(48)));if(key.equals("layout_toolbar")&&id.equals("ctrl")){minus.setEnabled(false);minus.setAlpha(.25f);}
            handle.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN){ClipData clip=ClipData.newPlainText("input-order",id);v.startDragAndDrop(clip,new View.DragShadowBuilder(line),key,0);return true;}return false;});
            line.setOnDragListener((v,e)->{
                if(!key.equals(e.getLocalState()))return false;
                if(e.getAction()==DragEvent.ACTION_DRAG_ENTERED)v.setAlpha(.5f);
                if(e.getAction()==DragEvent.ACTION_DRAG_EXITED||e.getAction()==DragEvent.ACTION_DRAG_ENDED)v.setAlpha(1);
                if(e.getAction()==DragEvent.ACTION_DROP){String from=e.getClipData().getItemAt(0).getText().toString();List<String> next=controls().editable(key);int to=next.indexOf(id);if(to>=0&&next.remove(from)){next.add(Math.min(to,next.size()),from);controls().save(key,next);refreshSettings();}return true;}return true;
            });
            // Accessibility users can reorder without having to perform a drag.
            handle.setOnLongClickListener(v->{List<SampleUi.Action> actions=new ArrayList<>();for(int delta:new int[]{-1,1})actions.add(new SampleUi.Action(delta<0?AppLanguage.tr(R.string.input_move_up):AppLanguage.tr(R.string.input_move_down),"reorder",true,false,()->{List<String> next=controls().editable(key);int from=next.indexOf(id),to=from+delta;if(from>=0&&to>=0&&to<next.size()){Collections.swap(next,from,to);controls().save(key,next);refreshSettings();}}));SampleUi.menu(this,handle,actions);return true;});
        }
        body.setOnDragListener((v,e)->{if(!key.equals(e.getLocalState()))return false;if(e.getAction()==DragEvent.ACTION_DRAG_LOCATION){int edge=dp(64);float y=e.getY()-scroll.getScrollY();if(y<edge)scroll.smoothScrollBy(0,-dp(24));else if(y>scroll.getHeight()-edge)scroll.smoothScrollBy(0,dp(24));}return true;});
    }
    protected void preview(String key){body.addView(layoutPreview(key),new LinearLayout.LayoutParams(-1,-2));}
    protected View layoutPreview(String key){
        if(key.equals("layout_zen")){android.widget.FrameLayout field=new android.widget.FrameLayout(this);field.setBackground(SampleUi.surface(this,0xff12332a,16));ZenRing ring=new ZenRing(this,controls(),id->{});android.widget.FrameLayout.LayoutParams rp=new android.widget.FrameLayout.LayoutParams(dp(240),dp(240),android.view.Gravity.CENTER);field.addView(ring,rp);field.setMinimumHeight(dp(260));return field;}
        if(key.equals("layout_toolbar")){InputToolbar toolbar=new InputToolbar(this);List<View> buttons=new ArrayList<>();for(String id:controls().toolbar()){InputButton button=new InputButton(this,controls().action(id),controls().flag("modifier_icons",false));button.setEnabled(false);buttons.add(button);}toolbar.setButtons(buttons);return toolbar;}
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);LinearLayout buttons=row();buttons.setPadding(dp(6),dp(8),dp(6),dp(8));buttons.setBackground(SampleUi.surface(this,0xff111315,28));
        for(String id:controls().editable(key)){InputButton button=new InputButton(this,controls().action(id),controls().flag("modifier_icons",false));button.setEnabled(false);buttons.addView(button,new LinearLayout.LayoutParams(button.preferredWidth(48,160),dp(48)));}scroll.addView(buttons);return scroll;
    }
}
