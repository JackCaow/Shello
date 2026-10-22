package dev.ghostty.android.sample;

import android.content.Context;
import android.view.*;
import android.widget.HorizontalScrollView;
import java.util.function.Consumer;

/** Horizontal scrolling yields to configured vertical swipes across every toolbar button. */
final class InputToolScroll extends HorizontalScrollView {
    private float x,y;private boolean vertical;private Consumer<Boolean> listener;
    InputToolScroll(Context c){super(c);}
    void setSwipeListener(Consumer<Boolean> listener){this.listener=listener;}
    @Override public boolean onInterceptTouchEvent(MotionEvent e){if(e.getActionMasked()==0){x=e.getX();y=e.getY();vertical=false;}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&Math.abs(e.getY()-y)>SampleUi.dp(getContext(),20)&&Math.abs(e.getY()-y)>Math.abs(e.getX()-x)*1.5f){vertical=true;return true;}return super.onInterceptTouchEvent(e);}
    @Override public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getX();y=e.getY();vertical=false;}if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&Math.abs(e.getY()-y)>SampleUi.dp(getContext(),20)&&Math.abs(e.getY()-y)>Math.abs(e.getX()-x)*1.5f)vertical=true;if(vertical){if(e.getActionMasked()==MotionEvent.ACTION_UP){if(listener!=null)listener.accept(e.getY()<y);vertical=false;}else if(e.getActionMasked()==MotionEvent.ACTION_CANCEL)vertical=false;return true;}return super.onTouchEvent(e);}
}
