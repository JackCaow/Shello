package dev.ghostty.android.sample;

import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.List;
import java.util.function.Consumer;

/** A separate trailing pair stays reachable while the remaining controls scroll. */
final class InputToolbar extends LinearLayout {
    final InputToolScroll scroll;
    final LinearLayout scrollingButtons,pinnedButtons;
    private float downX,downY;
    private boolean vertical;
    private Consumer<Boolean> swipe;

    InputToolbar(Context context){
        super(context);setOrientation(HORIZONTAL);setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        setPadding(dp(5),dp(4),dp(5),dp(4));setBackground(SampleUi.surface(context,0xff000000,32));setClipToOutline(true);
        scroll=new InputToolScroll(context);scroll.setHorizontalScrollBarEnabled(false);scroll.setFillViewport(true);
        scrollingButtons=new LinearLayout(context);scrollingButtons.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(scrollingButtons,new HorizontalScrollView.LayoutParams(-2,-1));
        addView(scroll,new LinearLayout.LayoutParams(0,dp(44),1));
        pinnedButtons=new LinearLayout(context);pinnedButtons.setGravity(Gravity.CENTER_VERTICAL);
        addView(pinnedButtons,new LinearLayout.LayoutParams(-2,dp(44)));
    }
    private int dp(int n){return SampleUi.dp(getContext(),n);}
    void setButtons(List<? extends View> buttons){setButtons(buttons,2);}
    void setButtons(List<? extends View> buttons,int pinnedCount){
        scrollingButtons.removeAllViews();pinnedButtons.removeAllViews();
        int split=Math.max(0,buttons.size()-pinnedCount);
        pinnedButtons.setVisibility(pinnedCount==0?GONE:VISIBLE);
        scroll.setVisibility(split==0?GONE:VISIBLE);
        for(int i=0;i<buttons.size();i++){
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(44),dp(44),i<split?1:0);
            params.setMargins(dp(2),0,dp(2),0);
            (i<split?scrollingButtons:pinnedButtons).addView(buttons.get(i),params);
        }
        scroll.scrollTo(0,0);
    }
    void setSwipeListener(Consumer<Boolean> listener){swipe=listener;scroll.setSwipeListener(listener);}
    @Override public boolean onInterceptTouchEvent(MotionEvent e){
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();vertical=false;}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&Math.abs(e.getY()-downY)>dp(20)&&Math.abs(e.getY()-downY)>Math.abs(e.getX()-downX)*1.5f){vertical=true;return true;}
        return super.onInterceptTouchEvent(e);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();vertical=false;}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&Math.abs(e.getY()-downY)>dp(20)&&Math.abs(e.getY()-downY)>Math.abs(e.getX()-downX)*1.5f)vertical=true;
        if(e.getActionMasked()==MotionEvent.ACTION_UP){if(vertical&&swipe!=null)swipe.accept(e.getY()<downY);else performClick();vertical=false;}
        if(e.getActionMasked()==MotionEvent.ACTION_CANCEL)vertical=false;
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
