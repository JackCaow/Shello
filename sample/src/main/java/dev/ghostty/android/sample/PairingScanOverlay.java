package dev.ghostty.android.sample;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import com.journeyapps.barcodescanner.BarcodeView;

/** Draws over the actual decoder crop; animation runs only while the camera scans. */
final class PairingScanOverlay extends View {
    private final BarcodeView camera;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path corners=new Path();
    private final ValueAnimator sweep=ValueAnimator.ofFloat(0,1);
    private boolean scanning,recognized;
    private float phase=.5f;

    PairingScanOverlay(Context context,BarcodeView camera){
        super(context);this.camera=camera;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        sweep.setDuration(2400);sweep.setRepeatCount(ValueAnimator.INFINITE);sweep.setRepeatMode(ValueAnimator.REVERSE);
        sweep.setInterpolator(new AccelerateDecelerateInterpolator());
        sweep.addUpdateListener(a->{phase=(float)a.getAnimatedValue();invalidate();});
    }
    void setScanning(boolean value){scanning=value;updateAnimation();}
    void setRecognized(boolean value){recognized=value;invalidate();}
    private void updateAnimation(){
        boolean run=scanning&&isAttachedToWindow()&&getWindowVisibility()==VISIBLE&&isShown()&&ValueAnimator.areAnimatorsEnabled();
        if(run&&!sweep.isStarted())sweep.start();
        else if(!run){sweep.cancel();phase=.5f;invalidate();}
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();updateAnimation();}
    @Override protected void onDetachedFromWindow(){sweep.cancel();super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(sweep!=null)updateAnimation();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        Rect crop=camera.getFramingRect();
        float size=Math.min(SampleUi.dp(getContext(),208),Math.min(getWidth(),getHeight())-SampleUi.dp(getContext(),40));
        float left=crop==null?(getWidth()-size)/2:crop.left,top=crop==null?(getHeight()-size)/2:crop.top;
        float right=crop==null?left+size:crop.right,bottom=crop==null?top+size:crop.bottom;
        paint.setStyle(Paint.Style.FILL);paint.setColor(0x99000000);
        canvas.drawRect(0,0,getWidth(),top,paint);canvas.drawRect(0,bottom,getWidth(),getHeight(),paint);
        canvas.drawRect(0,top,left,bottom,paint);canvas.drawRect(right,top,getWidth(),bottom,paint);
        float length=SampleUi.dp(getContext(),22);
        corners.reset();
        corners.moveTo(left,top+length);corners.lineTo(left,top);corners.lineTo(left+length,top);
        corners.moveTo(right-length,top);corners.lineTo(right,top);corners.lineTo(right,top+length);
        corners.moveTo(right,bottom-length);corners.lineTo(right,bottom);corners.lineTo(right-length,bottom);
        corners.moveTo(left+length,bottom);corners.lineTo(left,bottom);corners.lineTo(left,bottom-length);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(SampleUi.dp(getContext(),3));
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(scanning||recognized?SampleUi.GREEN:SampleUi.MUTED);canvas.drawPath(corners,paint);
        if(scanning){
            float inset=SampleUi.dp(getContext(),8),y=top+inset+(bottom-top-2*inset)*phase;
            paint.setStyle(Paint.Style.FILL);
            for(int i=8;i>0;i--){paint.setColor((3<<24)|(SampleUi.GREEN&0xffffff));float spread=SampleUi.dp(getContext(),i);canvas.drawRect(left+inset,y-spread,right-inset,y+spread,paint);}
            paint.setColor(SampleUi.GREEN);canvas.drawRoundRect(left+inset,y-1,right-inset,y+1,2,2,paint);
        }else if(recognized){
            float x=(left+right)/2,y=(top+bottom)/2;
            paint.setStyle(Paint.Style.FILL);paint.setColor(0xe611251c);canvas.drawCircle(x,y,SampleUi.dp(getContext(),28),paint);
            paint.setColor(SampleUi.GREEN);paint.setStrokeWidth(SampleUi.dp(getContext(),3));
            float unit=getResources().getDisplayMetrics().density;
            canvas.drawLine(x-12*unit,y,x-3*unit,y+9*unit,paint);canvas.drawLine(x-3*unit,y+9*unit,x+16*unit,y-12*unit,paint);
        }
    }
}
