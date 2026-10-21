package dev.ghostty.android.sample;

import android.view.*;

/** Delays a single click only when a double-click action is assigned; never fires both. */
final class ControlGestures implements View.OnTouchListener {
    interface Listener {void single();void twice();void hold();void swipe(boolean up);}
    private Listener listener;private final boolean doubleEnabled,swipes;
    private float x,y;private long lastUp;private boolean second,held,moved;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable click=()->listener.single();private final Runnable hold=()->{held=true;handler.removeCallbacks(click);listener.hold();};
    ControlGestures(Listener listener,boolean doubleEnabled,boolean swipes){this.listener=listener;this.doubleEnabled=doubleEnabled;this.swipes=swipes;}
    @Override public boolean onTouch(View v,MotionEvent e){
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();held=false;moved=false;second=doubleEnabled&&e.getEventTime()-lastUp<ViewConfiguration.getDoubleTapTimeout();if(second)handler.removeCallbacks(click);handler.postDelayed(hold,ViewConfiguration.getLongPressTimeout());return true;}
        if(action==MotionEvent.ACTION_MOVE&&Math.hypot(e.getRawX()-x,e.getRawY()-y)>ViewConfiguration.get(v.getContext()).getScaledTouchSlop()){handler.removeCallbacks(hold);moved=true;}
        if(action==MotionEvent.ACTION_UP){handler.removeCallbacks(hold);float dy=e.getRawY()-y;if(swipes&&Math.abs(dy)>SampleUi.dp(v.getContext(),32)){handler.removeCallbacks(click);listener.swipe(dy<0);}else if(!held&&!moved){if(second){lastUp=0;listener.twice();}else{lastUp=e.getEventTime();if(doubleEnabled)handler.postDelayed(click,ViewConfiguration.getDoubleTapTimeout());else v.performClick();}}return true;}
        if(action==MotionEvent.ACTION_CANCEL){handler.removeCallbacks(hold);handler.removeCallbacks(click);lastUp=0;return true;}return true;
    }
    void cancel(){handler.removeCallbacks(hold);handler.removeCallbacks(click);}
}
