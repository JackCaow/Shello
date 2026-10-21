package dev.ghostty.android.sample;

import android.content.Context;
import android.graphics.*;
import android.view.Gravity;
import android.widget.TextView;

/** The same button renderer is used in settings previews and the running terminal. */
final class InputButton extends TextView {
    private final ToolbarIcon icon;
    InputButton(Context context,InputActions.Action action,boolean modifierIcons){
        super(context);setGravity(Gravity.CENTER);setTextColor(SampleUi.TEXT);setTextSize(13);setTypeface(Typeface.MONOSPACE);setIncludeFontPadding(false);setSingleLine(true);setMinWidth(SampleUi.dp(context,42));setFocusable(true);
        String id=action==null?"none":action.id(),label=action==null?"":action.label();setTag(id);setContentDescription(label);
        boolean modifier=java.util.List.of("ctrl","alt","shift","super").contains(id);
        if(modifier){setText(modifierIcons?switch(id){case "ctrl"->"⌃";case "alt"->"⌥";case "shift"->"⇧";default->"⌘";}:label);icon=null;}
        else if(id.startsWith("key_")||id.startsWith("char_")||id.startsWith("preset_")||id.startsWith("custom_")||id.startsWith("ctrl_")){setText(label);icon=null;}
        else icon=new ToolbarIcon(action==null?"close":action.icon(),SampleUi.dp(context,20),SampleUi.TEXT);
        setAutoSizeTextTypeUniformWithConfiguration(9,13,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        setBackground(SampleUi.touch(context,0xff171a1b,24));
    }
    @Override protected void onDraw(Canvas c){if(icon==null)super.onDraw(c);else{c.save();c.translate(getScrollX(),getScrollY());int s=icon.getIntrinsicWidth();icon.setBounds((getWidth()-s)/2,(getHeight()-s)/2,(getWidth()+s)/2,(getHeight()+s)/2);icon.draw(c);c.restore();}if(isActivated()){Paint p=new Paint(3);p.setColor(SampleUi.GREEN);c.drawCircle(getWidth()/2f,getHeight()-SampleUi.dp(getContext(),4),SampleUi.dp(getContext(),2),p);}}
}
