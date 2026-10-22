package dev.ghostty.android.sample;

import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.List;
import java.util.function.Consumer;

/** A separate trailing pair stays reachable while the remaining controls scroll. */
final class InputToolbar extends LinearLayout {
    static final int HEIGHT_DP=64;
    final InputToolScroll scroll;
    final LinearLayout scrollingButtons,pinnedButtons;
    private float downX,downY;
    private boolean vertical;
    private Consumer<Boolean> swipe;
    private final View divider;

    InputToolbar(Context context){
        super(context);setOrientation(HORIZONTAL);setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        setMinimumHeight(dp(HEIGHT_DP));setPadding(dp(8),dp(8),dp(8),dp(8));setBackground(SampleUi.surface(context,0xff000000,32));setClipToOutline(true);
        scroll=new InputToolScroll(context);scroll.setHorizontalScrollBarEnabled(false);scroll.setFillViewport(true);scroll.setHorizontalFadingEdgeEnabled(true);scroll.setFadingEdgeLength(dp(12));scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scrollingButtons=new LinearLayout(context);scrollingButtons.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(scrollingButtons,new HorizontalScrollView.LayoutParams(-2,-1));
        addView(scroll,new LinearLayout.LayoutParams(0,dp(48),1));
        divider=SampleUi.separator(context);LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(1,dp(18));line.setMargins(dp(8),0,dp(8),0);addView(divider,line);
        pinnedButtons=new LinearLayout(context);pinnedButtons.setGravity(Gravity.CENTER_VERTICAL);
        addView(pinnedButtons,new LinearLayout.LayoutParams(-2,dp(48)));
    }
    private int dp(int n){return SampleUi.dp(getContext(),n);}
    void setButtons(List<? extends View> buttons){setButtons(buttons,2);}
    void setButtons(List<? extends View> buttons,int pinnedCount){
        scrollingButtons.removeAllViews();pinnedButtons.removeAllViews();
        int split=Math.max(0,buttons.size()-pinnedCount);
        pinnedButtons.setVisibility(pinnedCount==0?GONE:VISIBLE);
        divider.setVisibility(pinnedCount>0&&split>0?VISIBLE:GONE);
        scroll.setVisibility(split==0?GONE:VISIBLE);
        for(int i=0;i<buttons.size();i++){
            View view=buttons.get(i);int width=dp(i<split?52:48);
            if(view instanceof InputButton button){button.toolbarStyle(i>=split);if(i<split)width=button.toolbarWidth();}
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(width,dp(48),i<split?1:0);
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
