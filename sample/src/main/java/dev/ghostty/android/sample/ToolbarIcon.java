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
                for(int y=5;y<=19;y+=7){line(c,9,y,21,y);c.drawPoint(3,y,p);}break;
            case "paste":
                c.drawRoundRect(5,5,19,21,2,2,p);c.drawRoundRect(9,2,15,7,1,1,p);break;
            case "chat":
                Path bubble=new Path();bubble.moveTo(7,3);bubble.lineTo(17,3);
                bubble.quadTo(21,3,21,7);bubble.lineTo(21,14);bubble.quadTo(21,18,17,18);
                bubble.lineTo(9,18);bubble.lineTo(3,21);bubble.lineTo(3,7);bubble.quadTo(3,3,7,3);
                bubble.close();c.drawPath(bubble,p);break;
            case "keyboard":
                c.drawRoundRect(2,4,22,20,2,2,p);for(int y=8;y<=12;y+=4)for(int x=6;x<=18;x+=4)c.drawPoint(x,y,p);line(c,7,16,17,16);break;
            case "send":line(c,12,20,12,4);line(c,5,11,12,4,19,11);break;
            case "edit":line(c,14,4,18,2,22,6,10,18,5,19,6,14,18,2);line(c,14,6,18,10);line(c,12,4,3,4,3,22,21,22,21,13);break;
            case "trash":line(c,3,6,21,6);line(c,9,6,9,3,15,3,15,6);line(c,5,6,6,22,18,22,19,6);line(c,10,10,10,18);line(c,14,10,14,18);break;
            case "plus":line(c,12,4,12,20);line(c,4,12,20,12);break;
            case "close":line(c,5,5,19,19);line(c,19,5,5,19);break;
            case "mic":
                c.drawRoundRect(9,2,15,15,3,3,p);c.drawArc(5,5,19,19,0,180,false,p);line(c,12,19,12,23);break;
            case "toolbox":
                c.drawRoundRect(3,10,21,22,2,2,p);line(c,3,14,21,14);line(c,9,18,15,18);
                line(c,6,10,4,5,9,3,11,7,15,2,20,4,18,10);break;
            case "history":c.drawArc(3,3,21,21,210,300,false,p);line(c,3,3,3,8,8,8);line(c,12,7,12,12,16,14);break;
            case "up":line(c,7,14,12,9,17,14);break;
            case "down":line(c,7,10,12,15,17,10);break;
            case "left":line(c,14,7,9,12,14,17);break;
            case "right":line(c,10,7,15,12,10,17);break;
            case "return":line(c,20,5,20,10,18,14,5,14);line(c,10,9,5,14,10,19);break;
            case "backspace":line(c,9,5,21,5,21,19,9,19,2,12,9,5);line(c,12,9,17,15);line(c,17,9,12,15);break;
            case "clear":
                line(c,10,11,10,3,14,3,14,11,19,13,21,21,3,21,5,13,10,11);
                line(c,8,16,7,21);line(c,12,15,12,21);line(c,16,16,17,21);break;
            case "grip":
                float old=p.getStrokeWidth();p.setStrokeWidth(1.3f);line(c,1,12,23,12);p.setStrokeWidth(old);break;
            case "settings":
                c.drawCircle(12,12,3,p);c.drawCircle(12,12,8,p);
                for(int i=0;i<8;i++){c.save();c.rotate(i*45,12,12);line(c,12,2,12,4);c.restore();}break;
            case "lock":
            case "unlock":
                c.drawRoundRect(5,10,19,21,2,2,p);c.drawPoint(12,15,p);
                c.drawArc(8,2,16,13,180,180,false,p);line(c,8,7,8,10);
                if(name.equals("lock"))line(c,16,7,16,10);
                break;
            case "pad":
                c.drawRoundRect(10,2,14,8,1,1,p);c.drawRoundRect(16,10,22,14,1,1,p);
                c.drawRoundRect(10,16,14,22,1,1,p);c.drawRoundRect(2,10,8,14,1,1,p);break;
            default:break;
        }c.restore();
    }
    @Override public void setAlpha(int alpha){p.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
