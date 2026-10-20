package dev.ghostty.android.sample;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small original line icons, drawn in a 24-unit coordinate space. */
final class ToolbarIcon extends Drawable {
    private final String name;
    private final int size;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    ToolbarIcon(String name,int size,int color){this.name=name;this.size=size;p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
    @Override public int getIntrinsicWidth(){return size;}
    @Override public int getIntrinsicHeight(){return size;}
    private void line(Canvas c,float... points){Path path=new Path();path.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);c.drawPath(path,p);}
    @Override public void draw(Canvas c){
        c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/24f,getBounds().height()/24f);
        switch(name){
            case "panel":
                line(c,4,4,4,20);for(int y=6;y<=18;y+=6){line(c,9,y,20,y);c.drawPoint(6,y,p);}break;
            case "paste":
                c.drawRoundRect(5,5,19,21,2,2,p);c.drawRoundRect(9,2,15,7,1,1,p);break;
            case "chat":
                c.drawRoundRect(3,3,21,19,7,7,p);line(c,7,19,4,22,5,16);break;
            case "keyboard":
                c.drawRoundRect(2,4,22,20,2,2,p);for(int y=8;y<=12;y+=4)for(int x=6;x<=18;x+=4)c.drawPoint(x,y,p);line(c,7,16,17,16);break;
            case "send":line(c,12,20,12,4);line(c,5,11,12,4,19,11);break;
            case "lock":
            case "unlock":
                c.drawRoundRect(5,10,19,21,2,2,p);c.drawPoint(12,15,p);
                c.drawArc(8,2,16,13,180,180,false,p);line(c,8,7,8,10);
                if(name.equals("lock"))line(c,16,7,16,10);
                break;
            case "pad":
                line(c,9,6,12,3,15,6);line(c,18,9,21,12,18,15);line(c,15,18,12,21,9,18);line(c,6,15,3,12,6,9);break;
            default:break;
        }c.restore();
    }
    @Override public void setAlpha(int alpha){p.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
