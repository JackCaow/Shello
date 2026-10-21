package dev.ghostty.android.sample;

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
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;
    }
    static Drawable touch(Context c,int color,int radius){
        return new RippleDrawable(ColorStateList.valueOf(0x22ffffff),surface(c,color,radius),surface(c,Color.WHITE,radius));
    }
    static TextView text(Context c,String value,int size,int color){
        TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;
    }
    static void field(EditText field){
        Context c=field.getContext();StateListDrawable states=new StateListDrawable();
        GradientDrawable focus=surface(c,FIELD,18);focus.setStroke(dp(c,1),GREEN);
        states.addState(new int[]{android.R.attr.state_focused},focus);
        states.addState(new int[]{},surface(c,FIELD,18));
        field.setBackground(states);field.setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,12));
        field.setTextColor(TEXT);field.setHintTextColor(MUTED);field.setHighlightColor(0x6600d86c);
        field.setMinHeight(dp(c,56));
    }
    static RadioButton segment(Context c,String title,int id){
        RadioButton b=new RadioButton(c);b.setId(id);b.setText(title);b.setTextSize(16);
        b.setButtonDrawable((Drawable)null);b.setGravity(Gravity.CENTER);b.setMinHeight(dp(c,48));
        b.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));
        b.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{android.R.attr.state_checked},{}},new int[]{MUTED,GREEN,MUTED}));
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_checked},surface(c,0xff20372b,14));
        states.addState(new int[]{},surface(c,Color.TRANSPARENT,14));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),states,surface(c,Color.WHITE,14)));return b;
    }
    static final class Toggle extends CheckBox {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Toggle(Context c){super(c);setButtonDrawable((Drawable)null);setTextSize(16);setTextColor(TEXT);
            setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(c,16),dp(c,16),dp(c,78),dp(c,16));
            setMinHeight(dp(c,64));setBackground(touch(c,FIELD,18));}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float right=getWidth()-dp(getContext(),16),top=(getHeight()-dp(getContext(),28))/2f;
            float left=right-dp(getContext(),48),bottom=top+dp(getContext(),28);
            paint.setColor(isChecked()?GREEN:0xff42464b);paint.setAlpha(isEnabled()?255:90);
            canvas.drawRoundRect(left,top,right,bottom,dp(getContext(),14),dp(getContext(),14),paint);
            paint.setColor(isChecked()?0xff092618:0xffe6e6e8);paint.setAlpha(isEnabled()?255:90);
            canvas.drawCircle(isChecked()?right-dp(getContext(),14):left+dp(getContext(),14),(top+bottom)/2,dp(getContext(),10),paint);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.Switch");}
    }
    static TextView header(Context c,String symbol,String description,int color,Runnable action){
        TextView v=new TextView(c){
            final ToolbarIcon glyph=new ToolbarIcon(symbol,dp(c,24),color);
            @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);int size=dp(c,24);glyph.setBounds((getWidth()-size)/2,(getHeight()-size)/2,(getWidth()+size)/2,(getHeight()+size)/2);glyph.draw(canvas);}
            @Override public CharSequence getAccessibilityClassName(){return "android.widget.Button";}
        };
        v.setContentDescription(description);v.setFocusable(true);v.setBackground(touch(c,Color.TRANSPARENT,24));v.setOnClickListener(view->action.run());return v;
    }
    static ImageView icon(Context c,String name,int color){
        ImageView v=new ImageView(c);v.setImageDrawable(new ToolbarIcon(name,dp(c,22),color));
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
            LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12));row.setMinimumHeight(dp(c,52));
            row.setBackground(touch(c,Color.TRANSPARENT,16));row.setContentDescription(action.description);row.setFocusable(action.enabled);row.setEnabled(action.enabled);row.setAlpha(action.enabled?1:.45f);
            TextView title=text(c,action.title,16,color);title.setMaxLines(3);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(c,22),dp(c,22));ip.leftMargin=dp(c,16);row.addView(icon(c,action.icon,color),ip);
            row.setOnClickListener(v->{popup.dismiss();if(action.enabled&&action.run!=null)action.run.run();});rows.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
        rows.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int height=Math.min(rows.getMeasuredHeight(),Math.max(dp(c,52),visible.height()-dp(c,32)));popup.setHeight(height);
        int[] location=new int[2];anchor.getLocationOnScreen(location);
        int x=Math.max(visible.left+dp(c,12),Math.min(location[0],visible.right-width-dp(c,12)));
        int y=location[1]+anchor.getHeight()+dp(c,6);
        if(y+height>visible.bottom-dp(c,12))y=location[1]-height-dp(c,6);
        y=Math.max(visible.top+dp(c,12),Math.min(y,visible.bottom-height-dp(c,12)));
        popup.showAtLocation(anchor,Gravity.TOP|Gravity.LEFT,x,y);return popup;
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
            ScrollView scroll=new ScrollView(c);LinearLayout body=new LinearLayout(c);body.setOrientation(LinearLayout.VERTICAL);scroll.addView(body);panel.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
            TextView heading=text(c,title,22,TEXT);heading.setTypeface(null,Typeface.BOLD);body.addView(heading);
            TextView copy=text(c,message,15,MUTED);copy.setLineSpacing(dp(c,4),1);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.topMargin=dp(c,16);body.addView(copy,mp);
            if(detail!=null){TextView code=text(c,detail,14,TEXT);code.setTypeface(Typeface.MONOSPACE);code.setTextIsSelectable(true);code.setPadding(dp(c,16),dp(c,16),dp(c,16),dp(c,16));code.setBackground(surface(c,FIELD,16));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=dp(c,16);body.addView(code,cp);}
            negative=actionButton(c,cancel,false,false);positive=actionButton(c,accept,true,danger);
            LinearLayout actions=new LinearLayout(c);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.topMargin=dp(c,24);panel.addView(actions,ap);
            actions.addView(negative,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,-2,1);pp.leftMargin=dp(c,12);actions.addView(positive,pp);
            negative.setOnClickListener(v->{dismiss();if(cancelled!=null)cancelled.run();});positive.setOnClickListener(v->{dismiss();if(accepted!=null)accepted.run();});
            setOnCancelListener(d->{if(cancelled!=null)cancelled.run();});setView(panel,0,0,0,0);
            // Scroll long messages/fingerprints on compact screens and with large fonts.
            panel.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
                int max=activity.getResources().getDisplayMetrics().heightPixels*3/4;
                int available=Math.max(dp(c,80),max-(panel.getHeight()-scroll.getHeight()));
                if(scroll.getHeight()>available){scroll.getLayoutParams().height=available;scroll.requestLayout();}
            });
        }
        @Override public Button getButton(int which){return which==BUTTON_POSITIVE?positive:which==BUTTON_NEGATIVE?negative:super.getButton(which);}
        @Override public void show(){
            super.show();Window w=getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setDimAmount(.55f);w.setGravity(Gravity.BOTTOM);
                w.setLayout(Math.min(dp(activity,520),activity.getResources().getDisplayMetrics().widthPixels-dp(activity,24)),-2);
                WindowManager.LayoutParams p=w.getAttributes();p.y=dp(activity,12);w.setAttributes(p);w.setNavigationBarColor(Color.BLACK);}
        }
    }
    private static Button actionButton(Context c,String title,boolean primary,boolean danger){
        Button b=new Button(c);b.setText(title);b.setContentDescription(title);b.setAllCaps(false);b.setTextSize(16);b.setTypeface(null,Typeface.BOLD);
        b.setTextColor(primary?(danger?Color.WHITE:0xff062519):TEXT);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(c,52));b.setPadding(dp(c,12),dp(c,12),dp(c,12),dp(c,12));
        b.setStateListAnimator(null);b.setBackground(touch(c,primary?(danger?0xffb83240:GREEN):0xff292c30,16));return b;
    }
}
