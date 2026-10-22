package dev.ghostty.android.sample;

import android.content.Context;
import android.view.View;
import android.widget.ScrollView;

/** Keeps staged content scrollable without pushing the composer actions behind the IME. */
final class ComposerBody extends ScrollView {
    private int maximumHeight=Integer.MAX_VALUE;
    ComposerBody(Context context){super(context);setVerticalScrollBarEnabled(false);setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);}
    void maximumHeight(int pixels){pixels=Math.max(1,pixels);if(maximumHeight!=pixels){maximumHeight=pixels;requestLayout();}}
    @Override protected void onMeasure(int width,int height){
        int mode=MeasureSpec.getMode(height),limit=mode==MeasureSpec.UNSPECIFIED?maximumHeight:Math.min(maximumHeight,MeasureSpec.getSize(height));
        super.onMeasure(width,MeasureSpec.makeMeasureSpec(limit,MeasureSpec.AT_MOST));
    }
}
