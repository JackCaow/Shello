package dev.ghostty.android.sample;

import android.content.Context;
import android.graphics.*;
import android.view.Gravity;
import android.widget.TextView;

/** The same button renderer is used in settings previews and the running terminal. */
final class InputButton extends TextView {
    private final ToolbarIcon icon;
    private boolean toolbarStyle;
    InputButton(Context context,InputActions.Action action,boolean modifierIcons){
        super(context);setGravity(Gravity.CENTER);setTextColor(SampleUi.TEXT);UiDesign.type(this,UiDesign.Type.CONTROL);UiDesign.touch(this);setSingleLine(true);setEllipsize(android.text.TextUtils.TruncateAt.END);setPadding(SampleUi.dp(context,12),0,SampleUi.dp(context,12),0);setFocusable(true);
        String id=action==null?"none":action.id(),label=action==null?"":action.label();setTag(id);setContentDescription(label);
        boolean modifier=java.util.List.of("ctrl","alt","shift","super").contains(id);
        if(modifier){setText(modifierIcons?switch(id){case "ctrl"->"⌃";case "alt"->"⌥";case "shift"->"⇧";default->"⌘";}:label);icon=null;}
        else if(id.startsWith("key_")||id.startsWith("char_")||id.startsWith("preset_")||id.startsWith("custom_")||id.startsWith("ctrl_")){setText(label);icon=null;}
        else icon=new ToolbarIcon(action==null?"close":action.icon(),SampleUi.dp(context,20),SampleUi.TEXT);

        setBackground(SampleUi.touch(context,0xff171a1b,12));
    }
    void toolbarStyle(boolean pinned){
        toolbarStyle=true;setAutoSizeTextTypeWithDefaults(AUTO_SIZE_TEXT_TYPE_NONE);setTextSize(12);setEllipsize(android.text.TextUtils.TruncateAt.END);setPadding(SampleUi.dp(getContext(),8),0,SampleUi.dp(getContext(),8),0);
        setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0xff35433c),pinned?null:circle(0xff15181a),circle(Color.WHITE)));
    }
    int preferredWidth(int minimum,int maximum){return Math.max(SampleUi.dp(getContext(),minimum),Math.min(SampleUi.dp(getContext(),maximum),(int)Math.ceil(getPaint().measureText(getText().toString()))+SampleUi.dp(getContext(),24)));}
    int toolbarWidth(){return preferredWidth(52,112);}
    private android.graphics.drawable.Drawable circle(int color){return new android.graphics.drawable.Drawable(){
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);{paint.setColor(color);}
        @Override public void draw(Canvas canvas){Rect b=getBounds();float height=Math.min(SampleUi.dp(getContext(),36),b.height()),natural=icon==null?getPaint().measureText(getText().toString())+SampleUi.dp(getContext(),16):height,width=Math.min(Math.max(height,natural),b.width()-SampleUi.dp(getContext(),8));canvas.drawRoundRect(b.exactCenterX()-width/2,b.exactCenterY()-height/2,b.exactCenterX()+width/2,b.exactCenterY()+height/2,height/2,height/2,paint);}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    };}
    @Override protected void onDraw(Canvas c){if(icon==null)super.onDraw(c);else{c.save();c.translate(getScrollX(),getScrollY());int s=toolbarStyle?SampleUi.dp(getContext(),18):icon.getIntrinsicWidth();icon.setBounds((getWidth()-s)/2,(getHeight()-s)/2,(getWidth()+s)/2,(getHeight()+s)/2);icon.draw(c);c.restore();}if(isActivated()){Paint p=new Paint(3);p.setColor(SampleUi.GREEN);c.drawCircle(getWidth()/2f,getHeight()-SampleUi.dp(getContext(),toolbarStyle?10:4),SampleUi.dp(getContext(),2),p);}}
}
