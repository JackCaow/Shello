package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.List;

/** Sample-only visual language. Native text editing and accessibility stay intact. */
final class SampleUi {
    static final int TEXT=0xffe6e6e8, MUTED=0xff96999f, GREEN=0xff00d86c,
            PANEL=0xff161719, FIELD=0xff111315, BORDER=0xff303337, DANGER=0xffff7078;
    static int dp(Context c,int n){return (int)(c.getResources().getDisplayMetrics().density*n+.5f);}
    static GradientDrawable surface(Context c,int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(UiTheme.background(c,color));d.setCornerRadius(dp(c,radius));return d;
    }
    static View separator(Context c){View line=new View(c);line.setBackgroundColor(UiTheme.of(c).separator);line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return line;}
    static Drawable touch(Context c,int color,int radius){
        return new RippleDrawable(ColorStateList.valueOf(UiTheme.of(c).ripple),surface(c,color,radius),surface(c,Color.WHITE,radius));
    }
    static TextView text(Context c,String value,int size,int color){
        TextView t=new TextView(c);t.setText(value);UiDesign.type(t,UiDesign.role(size));t.setTextColor(UiTheme.foreground(c,color));
        if(size>24&&value!=null&&value.codePointCount(0,value.length())<=2){t.setTextSize(size);t.setTag(R.id.ui_text_role,null);}return t;
    }
    static TextView sectionTitle(Context c,String value){
        TextView title=text(c,value,13,0xff8e9298);
        UiDesign.heading(title);
        title.setIncludeFontPadding(false);title.setGravity(Gravity.CENTER_VERTICAL);
        if(android.os.Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);
        return title;
    }
    static void field(EditText field){
        Context c=field.getContext();StateListDrawable states=new StateListDrawable();
        GradientDrawable focus=surface(c,FIELD,18);focus.setColor(UiTheme.of(c).input);focus.setStroke(dp(c,1),UiTheme.of(c).accent);
        states.addState(new int[]{android.R.attr.state_focused},focus);
        states.addState(new int[]{},surface(c,UiTheme.of(c).input,18));
        UiDesign.type(field,UiDesign.Type.BODY);field.setBackground(states);field.setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,12));
        field.setTextColor(UiTheme.foreground(c,TEXT));field.setHintTextColor(UiTheme.foreground(c,MUTED));field.setHighlightColor(UiTheme.of(c).light?0x33087442:0x6600d86c);
        field.setMinHeight(dp(c,UiDesign.FIELD));field.setMinimumHeight(dp(c,UiDesign.FIELD));
    }
    static RadioButton segment(Context c,String title,int id){
        RadioButton b=new RadioButton(c);b.setId(id);b.setText(title);UiDesign.type(b,UiDesign.Type.BODY);UiDesign.touch(b);
        b.setButtonDrawable((Drawable)null);b.setGravity(Gravity.CENTER);b.setMinHeight(dp(c,48));
        b.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));
        b.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{android.R.attr.state_checked},{}},new int[]{UiTheme.foreground(c,MUTED),UiTheme.foreground(c,GREEN),UiTheme.foreground(c,MUTED)}));
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_checked},surface(c,0xff20372b,14));
        states.addState(new int[]{},surface(c,Color.TRANSPARENT,14));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(UiTheme.of(c).ripple),states,surface(c,Color.WHITE,14)));return b;
    }
    static final class Toggle extends CheckBox {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Toggle(Context c){super(c);setButtonDrawable((Drawable)null);UiDesign.type(this,UiDesign.Type.BODY);setTextColor(UiTheme.foreground(c,TEXT));
            setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(c,16),dp(c,16),dp(c,78),dp(c,16));
            setMinHeight(dp(c,64));setBackground(touch(c,FIELD,18));}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float right=getWidth()-dp(getContext(),16),top=(getHeight()-dp(getContext(),28))/2f;
            float left=right-dp(getContext(),48),bottom=top+dp(getContext(),28);
            paint.setColor(isChecked()?UiTheme.of(getContext()).accent:UiTheme.of(getContext()).light?UiTheme.of(getContext()).toggleOff:0xff42464b);paint.setAlpha(isEnabled()?255:90);
            canvas.drawRoundRect(left,top,right,bottom,dp(getContext(),14),dp(getContext(),14),paint);
            paint.setColor(UiTheme.of(getContext()).light?Color.WHITE:isChecked()?0xff092618:0xffe6e6e8);paint.setAlpha(isEnabled()?255:90);
            canvas.drawCircle(isChecked()?right-dp(getContext(),14):left+dp(getContext(),14),(top+bottom)/2,dp(getContext(),10),paint);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.Switch");}
    }
    static TextView header(Context c,String symbol,String description,int color,Runnable action){
        TextView v=new TextView(c){
            final ToolbarIcon glyph=new ToolbarIcon(c,symbol,dp(c,24),color);
            @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);int size=dp(c,24);glyph.setBounds((getWidth()-size)/2,(getHeight()-size)/2,(getWidth()+size)/2,(getHeight()+size)/2);glyph.draw(canvas);}
            @Override public CharSequence getAccessibilityClassName(){return "android.widget.Button";}
        };
        UiDesign.touch(v);v.setContentDescription(description);v.setFocusable(true);v.setBackground(touch(c,Color.TRANSPARENT,24));v.setOnClickListener(view->action.run());return v;
    }
    /** Shared navigation for full-screen pages; trailing actions use the same touch target. */
    static final class NavigationBar extends LinearLayout {
        final TextView title;
        private int contentMax;
        void contentWidth(int max){contentMax=max;requestLayout();}
        @Override protected void onMeasure(int ws,int hs){
            int side=contentMax==0?0:Math.max(0,(MeasureSpec.getSize(ws)-dp(getContext(),contentMax))/2);
            // Resolve padding before measuring our children, never from a sibling's measurement.
            if(getPaddingLeft()!=side||getPaddingRight()!=side)setPadding(side,getPaddingTop(),side,getPaddingBottom());
            super.onMeasure(ws,hs);
        }
        NavigationBar(Context context,String text,Runnable back){
            super(context);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setMinimumHeight(dp(context,56));
            addView(header(context,"back",tr(R.string.back),TEXT,back),new LinearLayout.LayoutParams(dp(context,56),dp(context,56)));
            title=SampleUi.text(context,text,20,TEXT);UiDesign.type(title,UiDesign.Type.TITLE);title.setSingleLine();title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=dp(context,16);p.rightMargin=dp(context,16);addView(title,p);
        }
        void addAction(View action){addView(action,new LinearLayout.LayoutParams(dp(getContext(),56),dp(getContext(),56)));}
    }
    static ImageView icon(Context c,String name,int color){
        ImageView v=new ImageView(c);v.setImageDrawable(new ToolbarIcon(c,name,dp(c,22),color));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return v;
    }
    static final class Action {
        final String title,description,icon;final boolean enabled,danger;final Runnable run;
        Action(String title,String icon,boolean enabled,boolean danger,Runnable run){this(title,title,icon,enabled,danger,run);}
        Action(String title,String description,String icon,boolean enabled,boolean danger,Runnable run){
            this.title=title;this.description=description;this.icon=icon;this.enabled=enabled;this.danger=danger;this.run=run;
        }
    }
    static PopupWindow menu(Activity activity,View anchor,List<Action> actions){
        Context c=activity;LinearLayout rows=new LinearLayout(c);rows.setOrientation(LinearLayout.VERTICAL);rows.setPadding(dp(c,6),dp(c,6),dp(c,6),dp(c,6));
        ScrollView scroll=new ScrollView(c);scroll.setFillViewport(false);scroll.setBackground(surface(c,PANEL,22));scroll.addView(rows);
        android.graphics.Rect visible=new android.graphics.Rect();anchor.getWindowVisibleDisplayFrame(visible);
        int width=Math.min(dp(c,320),visible.width()-dp(c,24));
        PopupWindow popup=new PopupWindow(scroll,width,-2,true);popup.setBackgroundDrawable(surface(c,PANEL,22));popup.setElevation(dp(c,18));popup.setOutsideTouchable(true);
        for(Action action:actions){
            int color=action.enabled?(action.danger?DANGER:TEXT):MUTED;
            LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12));row.setMinimumHeight(dp(c,UiDesign.ROW));
            row.setBackground(touch(c,Color.TRANSPARENT,16));row.setContentDescription(action.description);row.setFocusable(action.enabled);row.setEnabled(action.enabled);row.setAlpha(action.enabled?1:.45f);
            TextView title=text(c,action.title,16,color);title.setMaxLines(3);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(c,22),dp(c,22));ip.leftMargin=dp(c,16);row.addView(icon(c,action.icon,color),ip);
            row.setOnClickListener(v->{popup.dismiss();if(action.enabled&&action.run!=null)action.run.run();});rows.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
        rows.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int height=Math.min(rows.getMeasuredHeight(),Math.max(dp(c,52),visible.height()-dp(c,32)));popup.setHeight(height);
        // Keep the IME as it is. A focusable popup must not resize/pan itself away
        // from the button when the editor owns the keyboard.
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        if(android.os.Build.VERSION.SDK_INT>=29)popup.setIsLaidOutInScreen(true);
        int[] previous={Integer.MIN_VALUE,Integer.MIN_VALUE,0};
        Runnable position=()->{
            if(!anchor.isShown()){popup.dismiss();return;}
            android.graphics.Rect frame=new android.graphics.Rect();anchor.getWindowVisibleDisplayFrame(frame);
            int[] location=new int[2];anchor.getLocationOnScreen(location);
            int h=Math.min(rows.getMeasuredHeight(),Math.max(1,frame.height()-dp(c,24)));
            int x=Math.max(frame.left+dp(c,12),Math.min(location[0],frame.right-width-dp(c,12)));
            int below=location[1]+anchor.getHeight()+dp(c,6);
            int y=below+h<=frame.bottom-dp(c,12)?below:location[1]-h-dp(c,6);
            y=Math.max(frame.top+dp(c,12),Math.min(y,frame.bottom-h-dp(c,12)));
            if(android.os.Build.VERSION.SDK_INT<29){int[] screen=new int[2],window=new int[2];anchor.getRootView().getLocationOnScreen(screen);anchor.getRootView().getLocationInWindow(window);x-=screen[0]-window[0];y-=screen[1]-window[1];}
            if(popup.isShowing()){if(previous[0]!=x||previous[1]!=y||previous[2]!=h)popup.update(x,y,width,h);}
            else{popup.setHeight(h);popup.showAtLocation(anchor,Gravity.TOP|Gravity.LEFT,x,y);}
            previous[0]=x;previous[1]=y;previous[2]=h;
        };
        View root=anchor.getRootView();android.view.ViewTreeObserver.OnGlobalLayoutListener follow=position::run;
        popup.setOnDismissListener(()->{if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnGlobalLayoutListener(follow);});
        position.run();if(popup.isShowing())root.getViewTreeObserver().addOnGlobalLayoutListener(follow);return popup;
    }

    static ConfirmDialog confirm(Activity activity,String title,String message,String detail,String cancel,String accept,boolean danger,Runnable cancelled,Runnable accepted){
        ConfirmDialog dialog=new ConfirmDialog(activity,title,message,detail,cancel,accept,danger,cancelled,accepted);dialog.show();return dialog;
    }
    static final class ConfirmDialog extends AlertDialog {
        private final Activity activity;private final Button negative,positive;private final LinearLayout panel;
        ConfirmDialog(Activity activity,String title,String message,String detail,String cancel,String accept,boolean danger,Runnable cancelled,Runnable accepted){
            super(activity);this.activity=activity;Context c=activity;
            panel=new LinearLayout(c);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(c,24),dp(c,12),dp(c,24),dp(c,24));panel.setBackground(surface(c,PANEL,28));
            View grip=new View(c);grip.setBackground(surface(c,0xff54585d,3));LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(dp(c,40),dp(c,4));gp.gravity=Gravity.CENTER_HORIZONTAL;gp.bottomMargin=dp(c,24);panel.addView(grip,gp);
            LinearLayout body=new LinearLayout(c);body.setOrientation(LinearLayout.VERTICAL);panel.addView(body,new LinearLayout.LayoutParams(-1,-2));
            TextView heading=text(c,title,20,TEXT);UiDesign.type(heading,UiDesign.Type.TITLE);body.addView(heading);
            TextView copy=text(c,message,15,MUTED);UiDesign.paragraph(copy);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.topMargin=dp(c,16);body.addView(copy,mp);
            if(detail!=null){TextView code=text(c,detail,14,TEXT);code.setTypeface(Typeface.MONOSPACE);code.setTextIsSelectable(true);code.setPadding(dp(c,16),dp(c,16),dp(c,16),dp(c,16));code.setBackground(surface(c,FIELD,16));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=dp(c,16);body.addView(code,cp);}
            negative=actionButton(c,cancel,false,false);positive=actionButton(c,accept,true,danger);
            LinearLayout actions=new LinearLayout(c);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.topMargin=dp(c,24);panel.addView(actions,ap);
            actions.addView(negative,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,-2,1);pp.leftMargin=dp(c,12);actions.addView(positive,pp);
            negative.setOnClickListener(v->{dismiss();if(cancelled!=null)cancelled.run();});positive.setOnClickListener(v->{dismiss();if(accepted!=null)accepted.run();});
            setOnCancelListener(d->{if(cancelled!=null)cancelled.run();});setView(AdaptiveLayout.sheetScroll(activity,panel),0,0,0,0);

        }
        @Override public Button getButton(int which){return which==BUTTON_POSITIVE?positive:which==BUTTON_NEGATIVE?negative:super.getButton(which);}
        @Override public void show(){
            super.show();AdaptiveLayout.sheetWindow(activity,this,520,0);
            if(getWindow()!=null)UiTheme.window(activity,getWindow());
        }
    }
    private static Button actionButton(Context c,String title,boolean primary,boolean danger){
        Button b=new Button(c);b.setText(title);b.setContentDescription(title);b.setAllCaps(false);UiDesign.type(b,UiDesign.Type.BODY);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setTextColor(UiTheme.foreground(c,primary?(danger?Color.WHITE:UiTheme.of(c).onAccent):TEXT));b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(c,UiDesign.ROW));b.setPadding(dp(c,12),dp(c,12),dp(c,12),dp(c,12));
        b.setStateListAnimator(null);b.setBackground(touch(c,primary?(danger?0xffb83240:GREEN):0xff292c30,16));return b;
    }
}
