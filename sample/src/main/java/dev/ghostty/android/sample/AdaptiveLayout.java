package dev.ghostty.android.sample;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;

/** Sample window layouts use available space, including split-screen and the IME. */
final class AdaptiveLayout {
    private AdaptiveLayout() {}
    static int dp(Context c,int value){return SampleUi.dp(c,value);}

    static FrameLayout centered(Context c,View content,int maxWidth,View header){
        FrameLayout frame=new FrameLayout(c){
            @Override protected void onMeasure(int ws,int hs){
                int available=MeasureSpec.getSize(ws),width=Math.min(available,dp(c,maxWidth));
                content.getLayoutParams().width=width;
                if(header!=null){int side=(available-width)/2;
                    if(header.getPaddingLeft()!=side)header.setPadding(side,header.getPaddingTop(),side,header.getPaddingBottom());}
                super.onMeasure(ws,hs);
            }
        };
        frame.addView(content,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        return frame;
    }

    /** Switch existing children between columns and a stack without losing input/camera state. */
    static final class Columns extends LinearLayout {
        private final int breakpoint,gap;
        Columns(Context c,int breakpoint,int gap){super(c);this.breakpoint=breakpoint;this.gap=gap;setGravity(Gravity.TOP);setOrientation(VERTICAL);}
        @Override protected void onMeasure(int ws,int hs){
            boolean wide=MeasureSpec.getSize(ws)-getPaddingLeft()-getPaddingRight()>=dp(getContext(),breakpoint);
            setOrientation(wide?HORIZONTAL:VERTICAL);
            int visible=0;for(int i=0;i<getChildCount();i++){
                View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
                LayoutParams p=(LayoutParams)child.getLayoutParams();p.width=wide?0:LayoutParams.MATCH_PARENT;p.height=LayoutParams.WRAP_CONTENT;p.weight=wide?1:0;
                p.leftMargin=wide&&visible>0?dp(getContext(),gap):0;p.topMargin=!wide&&visible>0?dp(getContext(),gap):0;visible++;
            }
            super.onMeasure(ws,hs);
        }
    }

    static Rect available(Activity activity){
        Rect bounds=new Rect();activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(bounds);
        if(bounds.isEmpty())bounds.set(0,0,activity.getResources().getDisplayMetrics().widthPixels,activity.getResources().getDisplayMetrics().heightPixels);
        return bounds;
    }
    /** Call after show. Tall list sheets reserve room for their header and bottom action. */
    static void sheetWindow(Activity activity,Dialog dialog,int maxWidth,int preferredHeight){
        Window window=dialog.getWindow();if(window==null)return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.55f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        Runnable resize=()->{
            Rect bounds=available(activity);
            boolean expanded=activity.getResources().getConfiguration().smallestScreenWidthDp>=600;
            int margin=dp(activity,expanded?24:12);
            int width=Math.min(dp(activity,maxWidth),Math.max(1,bounds.width()-margin*2));
            int height=preferredHeight==0?WindowManager.LayoutParams.WRAP_CONTENT:Math.min(dp(activity,preferredHeight),Math.max(1,bounds.height()-margin*2));
            WindowManager.LayoutParams p=window.getAttributes();int gravity=expanded?Gravity.CENTER:Gravity.BOTTOM,y=expanded?0:margin;
            if(p.width!=width||p.height!=height||p.gravity!=gravity||p.y!=y){p.width=width;p.height=height;p.gravity=gravity;p.y=y;window.setAttributes(p);}
        };
        resize.run();View owner=activity.getWindow().getDecorView();
        View.OnLayoutChangeListener listener=(v,l,t,r,b,ol,ot,or,ob)->resize.run();owner.addOnLayoutChangeListener(listener);
        window.getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){}
            public void onViewDetachedFromWindow(View v){owner.removeOnLayoutChangeListener(listener);v.removeOnAttachStateChangeListener(this);}
        });
    }
    static ScrollView sheetScroll(Activity activity,View content){
        ScrollView scroll=new ScrollView(activity){
            @Override protected void onMeasure(int ws,int hs){
                int max=Math.max(1,available(activity).height()-dp(activity,48));
                if(MeasureSpec.getMode(hs)!=MeasureSpec.UNSPECIFIED)max=Math.min(max,MeasureSpec.getSize(hs));
                super.onMeasure(ws,MeasureSpec.makeMeasureSpec(max,MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);scroll.addView(content);return scroll;
    }
}
