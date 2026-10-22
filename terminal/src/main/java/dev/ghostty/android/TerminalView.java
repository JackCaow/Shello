package dev.ghostty.android;

import android.content.*;
import android.graphics.*;
import android.os.*;
import android.util.AttributeSet;
import android.view.*;
import android.widget.FrameLayout;
import org.json.*;
import java.util.*;

/** Native Canvas viewport with grapheme-aware touch selection. Contains no transport or WebView. */
public final class TerminalView extends FrameLayout {
    public interface Listener {
        /** Send these ordered bytes to your PTY. No automatic retries are performed. */
        void onInput(byte[] bytes);
        /** Forward the grid size to your PTY. */
        void onResize(int columns, int rows);
        /** Selection changed on the UI thread; empty when cleared. The host owns copy actions. */
        default void onSelectionChanged(String text) {}
    }
    /** Optional host gestures. Return true only when the host handles an assigned action. */
    public enum Gesture { SINGLE_TAP, DOUBLE_TAP, TRIPLE_TAP, PULL_DOWN, SWIPE_LEFT, SWIPE_RIGHT, TWO_LEFT, TWO_RIGHT, TWO_UP, TWO_DOWN, PINCH_IN, PINCH_OUT, DOUBLE_HOLD_START, DOUBLE_HOLD_MOVE, DOUBLE_HOLD_END, DOUBLE_HOLD_CANCEL }
    public interface GestureListener { boolean onGesture(Gesture gesture,float x,float y); }
    private GestureListener gestureListener;
    public void setGestureListener(GestureListener value){checkUi();gestureListener=value;}
    private boolean gesture(String name,float x,float y){return gestureListener!=null&&gestureListener.onGesture(Gesture.valueOf(name.toUpperCase(java.util.Locale.ROOT)),x,y);}
    private TerminalSession session;
    private Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Renderer canvas;
    private final TouchLayer touch;
    private JSONObject frame;
    private int cols=40, rows=20;
    private String selected="";
    private boolean frameScheduled;
    private final Runnable drawTask=()->{frameScheduled=false;refreshFrame();};

    public TerminalView(Context context) { this(context, null); }
    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        canvas=new Renderer(); touch=new TouchLayer();
        addView(canvas,new LayoutParams(-1,-1)); addView(touch,new LayoutParams(-1,-1));
        setBackgroundColor(0xff151922);
    }

    /** The caller owns the session; detach with null before closing it. UI thread only. */
    public void setSession(TerminalSession value) {
        checkUi();
        if(session==value)return;
        // Attach first so a rejected owner/thread cannot discard the existing view binding.
        if(value!=null)value.attach(this::outputChanged);
        cancelPendingGestures();
        if(session!=null)session.detach();
        session=value;touch.clear();frame=null;canvas.cells=new Renderer.Cell[0][];canvas.resetCursorBlink();
        ui.removeCallbacks(drawTask);removeCallbacks(drawTask);frameScheduled=false;
        if(value!=null){cols=value.getColumns();rows=value.getRows();resizeToView();refreshFrame();}
        canvas.invalidate();
    }
    public void setListener(Listener listener) {
        checkUi();this.listener=listener;
        if(listener!=null&&session!=null)listener.onResize(cols,rows);
    }
    /** Font size in scaled pixels. Resizes the PTY grid and preserves its contents. */
    public void setFontSize(float sp) {
        checkUi();if(!Float.isFinite(sp)||sp<8||sp>32)throw new IllegalArgumentException("Font size must be 8..32 sp");
        float pixels=sp*getResources().getDisplayMetrics().scaledDensity;
        if(canvas.paint.getTextSize()==pixels)return;
        canvas.paint.setTextSize(pixels);canvas.paint.setTypeface(canvas.faces[0]);
        canvas.cellW=canvas.paint.measureText("M");canvas.cellH=Math.max(pixels*1.28f,canvas.paint.getFontSpacing());
        touch.clear();resizeToView();refreshFrame();canvas.invalidate();
    }
    /** Cancel delayed taps, holds and drags before pausing or changing the active PTY. */
    public void cancelPendingGestures(){
        checkUi();ui.removeCallbacks(touch.tapAction);ui.removeCallbacks(touch.holdAction);ui.removeCallbacks(touch.autoScroll);
        if(touch.doubleHeld)gesture("double_hold_cancel",touch.pointerX,touch.pointerY);
        long now=SystemClock.uptimeMillis();MotionEvent cancel=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,touch.pointerX,touch.pointerY,0);
        if(touch.mousePressed){touch.mousePressed=false;touch.mouse(cancel,1);}
        touch.detector.onTouchEvent(cancel);touch.scaleDetector.onTouchEvent(cancel);cancel.recycle();
        touch.tapCount=0;touch.doubleHeld=false;touch.handleDrag=false;touch.multi=false;touch.scaled=false;
    }
    public void setMouseSelectionEnabled(boolean enabled){checkUi();touch.mouseSelection=enabled;touch.clear();}
    public boolean isMouseSelectionEnabled(){return touch.mouseSelection;}
    public void setRightClickEnabled(boolean enabled){checkUi();touch.rightClick=enabled;}
    public boolean isRightClickEnabled(){return touch.rightClick;}
    public void setDragArrowsEnabled(boolean enabled){checkUi();touch.dragArrows=enabled;}
    public boolean isDragArrowsEnabled(){return touch.dragArrows;}
    public void setSelectionEnabled(boolean enabled){checkUi();touch.selectionEnabled=enabled;if(!enabled)touch.clear();}
    public String getSelectedText() { checkUi();return selected; }
    public void clearSelection() { checkUi();touch.clear(); }
    private void selectionChanged(String text) {
        if(selected.equals(text))return;
        selected=text;
        if(listener!=null)listener.onSelectionChanged(text);
    }
    public void scrollToBottom() { checkUi();touch.clear();if(session!=null)session.scroll(Integer.MAX_VALUE); }
    public void sendText(String text) { checkUi();emitInput(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    /** Paste bulk text as one terminal operation; call sendKey(ENTER) separately to submit. */
    public void pasteText(String text) { checkUi();if(session!=null)emitInput(session.encodePaste(text)); }
    public void sendKey(TerminalSession.Key key) { checkUi();if(session!=null)emitInput(session.encodeKey(key)); }
    public void sendKey(TerminalSession.Key key,int modifiers){checkUi();if(session!=null)emitInput(session.encodeKey(key,modifiers));}
    public void sendCharacter(int codePoint,String text,int modifiers){checkUi();if(session!=null)emitInput(session.encodeCharacter(codePoint,text,modifiers));}
    private void sendKey(int index) { sendKey(TerminalSession.Key.values()[index]); }
    private void emitInput(byte[] bytes) {
        if(session==null||listener==null)return;
        canvas.resetCursorBlink();scrollToBottom();listener.onInput(bytes);
    }
    private void outputChanged(boolean clearSelection) { if(clearSelection)touch.clear();drawFrame(); }
    private void drawFrame() { if(!frameScheduled){frameScheduled=true;postOnAnimation(drawTask);} }
    private void refreshFrame() {
        if(session==null)return;
        try { canvas.setRender(session.render()); }
        catch(JSONException e){throw new IllegalStateException("Invalid native frame",e);}
    }
    private float cellWidth(){return canvas.cellW;}
    private float cellHeight(){return canvas.cellH;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void resizeToView(){
        if(session==null||getWidth()==0||getHeight()==0)return;
        int width=Math.max(1,Math.min(1000,(int)(getWidth()/cellWidth())));
        int height=Math.max(1,Math.min(65536/width,(int)(getHeight()/cellHeight())));
        height=Math.min(1000,height);
        if(width==cols&&height==rows)return;
        cols=width;rows=height;session.resize(cols,rows);
        if(listener!=null)listener.onResize(cols,rows);
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);resizeToView();}
    @Override protected void onDetachedFromWindow(){removeCallbacks(drawTask);frameScheduled=false;touch.clear();super.onDetachedFromWindow();}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();drawFrame();}
    private static void checkUi(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("TerminalView requires the UI thread");}
    class Renderer extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint cursorPaint=new Paint();
        final CursorBlink cursorBlink=new CursorBlink();
        boolean blinkingText;
        final Runnable blinkTask=()->{
            if(blinkingText)invalidate();else{
                RectF bounds=cursorBounds();invalidate((int)Math.floor(bounds.left),(int)Math.floor(bounds.top),
                        (int)Math.ceil(bounds.right),(int)Math.ceil(bounds.bottom));
            }
        };
        void resetCursorBlink(){cursorBlink.reset(SystemClock.uptimeMillis());removeCallbacks(blinkTask);invalidate();}
        boolean animationActive(){return session!=null&&frame!=null&&isAttachedToWindow()&&isShown()&&getWindowVisibility()==VISIBLE&&hasWindowFocus();}
        @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);resetCursorBlink();}
        @Override protected void onVisibilityChanged(View changed,int visibility){
            super.onVisibilityChanged(changed,visibility);
            if(cursorBlink!=null)resetCursorBlink();
        }
        @Override protected void onWindowVisibilityChanged(int visibility){
            super.onWindowVisibilityChanged(visibility);if(cursorBlink!=null)resetCursorBlink();
        }
        @Override protected void onDetachedFromWindow(){removeCallbacks(blinkTask);super.onDetachedFromWindow();}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();resetCursorBlink();}
        final Typeface[] faces={Typeface.MONOSPACE,Typeface.create(Typeface.MONOSPACE,Typeface.BOLD),Typeface.create(Typeface.MONOSPACE,Typeface.ITALIC),Typeface.create(Typeface.MONOSPACE,Typeface.BOLD_ITALIC)};
        float cellW,cellH; Cell[][] cells=new Cell[0][];
        final class Cell {
            String text="",displayText="";int fg,bg,wide,flags,underline,underlineColor,displaySpan;boolean skip;
            Cell(){}
            Cell(JSONArray a)throws JSONException{text=a.getString(0);fg=a.getInt(1);bg=a.getInt(2);wide=a.getInt(3);flags=a.optInt(4);underline=a.optInt(5);underlineColor=a.optInt(6,fg);}
        }
        Renderer(){super(TerminalView.this.getContext());paint.setTypeface(faces[0]);paint.setTextSize(dp(14));cellW=paint.measureText("M");cellH=Math.max(dp(18),paint.getFontSpacing());setContentDescription(getContext().getString(R.string.terminal_viewport));}
        final String[] ascii=new String[128];final int[] codepoints=new int[128];
        void setRender(byte[] data)throws JSONException{
            java.nio.IntBuffer buffer=java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            String[] fields={"cols","rows","x","y","alt","mouse","offset","total","cursorVisible","cursorBlink","cursorShape","cursorColor","defaultBg","reserved"};
            JSONObject next=new JSONObject();for(String field:fields){int value=buffer.get();next.put(field,field.equals("cursorVisible")||field.equals("cursorBlink")||field.equals("mouse")?value!=0:value);}
            int height=next.getInt("rows"),width=next.getInt("cols");
            if(cells.length!=height||(height>0&&cells[0].length!=width)){cells=new Cell[height][width];for(int y=0;y<height;y++)for(int x=0;x<width;x++)cells[y][x]=new Cell();}
            for(int y=0;y<height;y++)for(int x=0;x<width;x++){
                Cell v=cells[y][x];v.fg=buffer.get();v.bg=buffer.get();v.wide=buffer.get();v.flags=buffer.get();v.underline=buffer.get();v.underlineColor=buffer.get();int count=buffer.get();
                for(int i=0;i<count;i++)codepoints[i]=buffer.get();
                if(count==0)v.text="";else if(count==1&&codepoints[0]<128){int cp=codepoints[0];if(ascii[cp]==null)ascii[cp]=Character.toString((char)cp);v.text=ascii[cp];}else v.text=new String(codepoints,0,count);
            }
            blinkingText=false;
            for(Cell[] line:cells){shapeRow(line);for(Cell cell:line)blinkingText|=(cell.flags&64)!=0;}
            if(frame==null||frame.optInt("x")!=next.optInt("x")||frame.optInt("y")!=next.optInt("y")
                    ||frame.optInt("cursorShape")!=next.optInt("cursorShape")
                    ||frame.optBoolean("cursorVisible")!=next.optBoolean("cursorVisible")
                    ||frame.optBoolean("cursorBlink")!=next.optBoolean("cursorBlink"))resetCursorBlink();
            frame=next;invalidate();
        }
        boolean sameStyle(Cell a,Cell b){return a.fg==b.fg&&a.bg==b.bg&&a.flags==b.flags&&a.underline==b.underline&&a.underlineColor==b.underlineColor;}
        void shapeRow(Cell[] line){
            StringBuilder text=new StringBuilder();ArrayList<Integer> owners=new ArrayList<>();
            for(int x=0;x<line.length;x++){Cell cell=line[x];cell.displayText=cell.text;cell.displaySpan=cell.wide==1?2:1;cell.skip=false;if(cell.wide>=2)continue;
                String value=cell.text.isEmpty()?" ":cell.text;text.append(value);for(int i=0;i<value.length();i++)owners.add(x);
            }
            String rowText=text.toString();int begin=0;
            // Old Android ICU versions split valid emoji ZWJ sequences. Keep boundaries
            // consistent with the pinned VT engine while preserving its legacy columns.
            for(int end:NativeBridge.graphemeEnds(rowText)){
                int start=begin;begin=end;
                int first=owners.get(start),last=owners.get(end-1);if(first==last)continue;
                // Shape across adjacent legacy cells, but never change VT cursor/selection columns.
                boolean same=true;for(int x=first+1;x<=last;x++)if(line[x].wide<2&&!sameStyle(line[first],line[x]))same=false;
                if(!same)continue;
                line[first].displayText=rowText.substring(start,end);line[first].displaySpan=last-first+(line[last].wide==1?2:1);
                for(int x=first+1;x<=last;x++)line[x].skip=true;
            }
        }
        boolean sameRun(Cell a,Cell b){return !b.skip&&b.displayText.equals(b.text)&&b.wide==0&&b.text.length()==1&&b.text.charAt(0)<128&&a.fg==b.fg&&a.flags==b.flags&&a.underline==b.underline&&a.underlineColor==b.underlineColor;}
        @Override protected void onDraw(Canvas c){
            c.drawColor(0xff000000|(frame==null?0x151922:frame.optInt("defaultBg",0x151922)));
            for(int y=0;y<cells.length;y++)for(int x=0;x<cells[y].length;){
                int end=x+1;while(end<cells[y].length&&cells[y][end].bg==cells[y][x].bg)end++;
                paint.setColor(0xff000000|cells[y][x].bg);c.drawRect(x*cellW,y*cellH,end*cellW,(y+1)*cellH,paint);x=end;
            }
            long now=SystemClock.uptimeMillis();
            boolean active=animationActive(),blink=!active||(now/500)%2==0;
            for(int y=0;y<cells.length;y++)for(int x=0;x<cells[y].length;x++){
                Cell v=cells[y][x];if(v.wide>=2||v.skip||v.text.isEmpty())continue;
                float left=x*cellW,top=y*cellH,width=v.displaySpan*cellW;
                String text=v.displayText;
                if(v.wide==0&&text.length()==1&&text.charAt(0)<128){int end=x+1;StringBuilder run=new StringBuilder(text);while(end<cells[y].length&&sameRun(v,cells[y][end]))run.append(cells[y][end++].text);width=(end-x)*cellW;text=run.toString();x=end-1;}
                if((v.flags&8)!=0||((v.flags&64)!=0&&!blink))continue;
                drawGlyph(c,v,text,left,top,width,null);
            }
            drawCursor(c,now,hasWindowFocus(),blink);
            removeCallbacks(blinkTask);
            if(active){
                long delay=Long.MAX_VALUE;
                if(frame!=null&&frame.optBoolean("cursorVisible")&&frame.optBoolean("cursorBlink"))delay=cursorBlink.nextChange(now);
                if(blinkingText)delay=Math.min(delay,500-now%500);
                if(delay!=Long.MAX_VALUE)postDelayed(blinkTask,delay);
            }
        }
        private void drawGlyph(Canvas c,Cell v,String text,float left,float top,float width,Integer overrideColor){
            paint.setTypeface(faces[v.flags&3]);paint.setColor(0xff000000|(overrideColor==null?v.fg:overrideColor));paint.setAlpha(overrideColor==null&&(v.flags&4)!=0?130:255);
            // Clip to the cell span; each grapheme is passed intact to Android's text shaper.
            c.save();c.clipRect(left,top,left+width,top+cellH);
            float baseline=top+(cellH-paint.getFontSpacing())/2-paint.ascent();
            // Preserve protocol columns even when font fallback has a wider pixel advance.
            float advance=paint.measureText(text);c.save();if(advance>width)c.scale(width/advance,1,left,baseline);
            c.drawText(text,left,baseline,paint);c.restore();
            paint.setStrokeWidth(dp(1));
            if((v.flags&16)!=0)c.drawLine(left,top+cellH*.52f,left+width,top+cellH*.52f,paint);
            if((v.flags&32)!=0)c.drawLine(left,top+dp(1),left+width,top+dp(1),paint);
            paint.setColor(0xff000000|(overrideColor==null?v.underlineColor:overrideColor));
            float bottom=top+cellH-dp(2);
            if(v.underline==1||v.underline==2)c.drawLine(left,bottom,left+width,bottom,paint);
            if(v.underline==2)c.drawLine(left,bottom-dp(3),left+width,bottom-dp(3),paint);
            if(v.underline==3){Path path=new Path();path.moveTo(left,bottom);for(float dx=0;dx<width;dx+=dp(2))path.lineTo(left+dx,bottom+((int)(dx/dp(2))%2==0?-dp(1):dp(1)));paint.setStyle(Paint.Style.STROKE);c.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);}
            if(v.underline==4||v.underline==5){float dash=v.underline==4?dp(1):dp(4);for(float dx=0;dx<width;dx+=dash+dp(2))c.drawLine(left+dx,bottom,Math.min(left+width,left+dx+dash),bottom,paint);}
            paint.setAlpha(255);c.restore();
        }
        RectF cursorBounds(){
            if(frame==null||!frame.optBoolean("cursorVisible"))return new RectF();
            int x=frame.optInt("x"),y=frame.optInt("y");
            if(y<0||y>=cells.length||x<0||x>=cells[y].length)return new RectF();
            // Wide-cell continuation belongs to its leading cell. Legacy ZWJ graphemes
            // keep their VT columns; do not stretch the cursor over the whole visual cluster.
            if(cells[y][x].wide==2&&x>0)x--;
            int width=cells[y][x].wide==1?2:1;
            return new RectF(Math.round(x*cellW),Math.round(y*cellH),
                    Math.round(Math.min(cells[y].length,x+width)*cellW),Math.round((y+1)*cellH));
        }
        void drawCursor(Canvas c,long now,boolean focused,boolean textBlink){
            RectF bounds=cursorBounds();if(bounds.isEmpty())return;
            if(focused&&frame.optBoolean("cursorBlink")&&!cursorBlink.visible(now))return;
            int shape=focused?frame.optInt("cursorShape",1):3;
            int color=0xff000000|frame.optInt("cursorColor",0xdce3ee);
            cursorPaint.setColor(color);cursorPaint.setStyle(Paint.Style.FILL);
            float stroke=Math.max(1,dp(1));
            if(shape==0)c.drawRect(bounds.left,bounds.top,Math.min(bounds.right,bounds.left+dp(2)),bounds.bottom,cursorPaint);
            else if(shape==2)c.drawRect(bounds.left,Math.max(bounds.top,bounds.bottom-dp(2)),bounds.right,bounds.bottom,cursorPaint);
            else if(shape==3){
                cursorPaint.setStrokeWidth(stroke);cursorPaint.setStyle(Paint.Style.STROKE);
                RectF outline=new RectF(bounds);outline.inset(stroke/2,stroke/2);c.drawRect(outline,cursorPaint);
                cursorPaint.setStyle(Paint.Style.FILL);
            }else{
                c.drawRect(bounds,cursorPaint);
                int y=frame.optInt("y"),owner=frame.optInt("x");
                while(owner>0&&(cells[y][owner].skip||cells[y][owner].wide==2))owner--;
                Cell v=cells[y][owner];
                if(!v.text.isEmpty()&&(v.flags&8)==0&&((v.flags&64)==0||textBlink)){
                    int foreground=0xff000000|v.bg;
                    double a=Color.luminance(color)+.05,b=Color.luminance(foreground)+.05;
                    if(Math.max(a,b)/Math.min(a,b)<4.5)foreground=a>.229?Color.BLACK:Color.WHITE;
                    c.save();c.clipRect(bounds);
                    drawGlyph(c,v,v.displayText,owner*cellW,y*cellH,v.displaySpan*cellW,foreground);c.restore();
                }
            }
        }
    }

    class TouchLayer extends View {
        boolean selectionEnabled=true,mouseSelection,rightClick,dragArrows,mousePressed;
        float arrowX,arrowY;
        void mouse(MotionEvent e,int action){if(session==null)return;int cw=Math.max(1,Math.round(cellWidth())),ch=Math.max(1,Math.round(cellHeight()));float x=Math.max(0,Math.min(cols*cellWidth()-1,e.getX()))/cellWidth()*cw,y=Math.max(0,Math.min(rows*cellHeight()-1,e.getY()))/cellHeight()*ch;emitInput(session.encodeMouse(action,rightClick,x,y,cw,ch));}
        final GestureDetector detector;
        final ScaleGestureDetector scaleDetector;
        float downX,downY,multiX,multiY;boolean multi,scaled,swipeHandled,doubleHeld;
        int tapCount;long lastTap;float tapX,tapY,scaleAmount=1;
        final Runnable tapAction=()->{gesture(tapCount>=3?"triple_tap":tapCount==2?"double_tap":"single_tap",tapX,tapY);tapCount=0;};
        final Runnable holdAction=()->{if(tapCount==2){doubleHeld=gesture("double_hold_start",tapX,tapY);}};
        boolean selecting=false,handleDrag=false;int start,end,dragHandle=1;float previousY,pointerX,pointerY;
        final Paint overlay=new Paint(Paint.ANTI_ALIAS_FLAG);
        final Runnable autoScroll=()->autoScrollTick();
        TouchLayer(){super(TerminalView.this.getContext());setContentDescription(getContext().getString(R.string.terminal_gestures));detector=new GestureDetector(getContext(),new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){previousY=e.getY();return true;}
            @Override public void onLongPress(MotionEvent e){if(doubleHeld||tapCount>=2||multi||!selectionEnabled)return;refreshFrame();selecting=true;dragHandle=1;start=normalize(cell(e.getX(),e.getY()),false);end=normalize(start,true);updateSelection();performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);}
            @Override public boolean onSingleTapUp(MotionEvent e){clear();return true;}
            @Override public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy){
                if(selecting||multi||doubleHeld||swipeHandled)return true;
                float totalX=b.getX()-downX,totalY=b.getY()-downY;
                if(Math.abs(totalX)>dp(48)&&Math.abs(totalX)>Math.abs(totalY)*1.5f&&gesture(totalX>0?"swipe_right":"swipe_left",b.getX(),b.getY())){swipeHandled=true;return true;}
                if(totalY>dp(72)&&Math.abs(totalY)>Math.abs(totalX)*2&&gesture("pull_down",b.getX(),b.getY())){swipeHandled=true;return true;}
                int delta=(int)((previousY-b.getY())/cellHeight());
                if(delta!=0){if(frame!=null&&frame.optBoolean("mouse")){
                        // tmux owns its history, even though it uses the alternate screen.
                        // Send negotiated wheel events instead of editing the shell's input history.
                        int cw=Math.max(1,Math.round(cellWidth())),ch=Math.max(1,Math.round(cellHeight()));
                        float x=Math.max(0,Math.min(cols*cellWidth()-1,b.getX()))/cellWidth()*cw;
                        float y=Math.max(0,Math.min(rows*cellHeight()-1,b.getY()))/cellHeight()*ch;
                        byte[] wheel=session.encodeWheel(delta>0,x,y,cw,ch);
                        if(wheel.length>0)for(int i=0;i<Math.min(4,Math.abs(delta));i++)emitInput(wheel);
                    }else if(frame!=null&&frame.optInt("alt")!=0){for(int i=0;i<Math.min(4,Math.abs(delta));i++)sendKey(delta>0?1:0);}
                    else {session.scroll(delta);drawFrame();}previousY=b.getY();}return true;
            }
        });
            detector.setOnDoubleTapListener(null);
            scaleDetector=new ScaleGestureDetector(getContext(),new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                @Override public boolean onScaleBegin(ScaleGestureDetector d){scaleAmount=1;return true;}
                @Override public boolean onScale(ScaleGestureDetector d){scaleAmount*=d.getScaleFactor();if(scaleAmount>1.12f||scaleAmount<.89f){if(gesture(scaleAmount>1?"pinch_out":"pinch_in",d.getFocusX(),d.getFocusY()))scaled=true;scaleAmount=1;}return true;}
            });
            scaleDetector.setQuickScaleEnabled(false);scaleDetector.setStylusScaleEnabled(false);
        }
        int offset(){return frame!=null?frame.optInt("offset"):0;}
        int cell(float x,float y){return (offset()+Math.max(0,Math.min(rows-1,(int)(y/cellHeight()))))*cols+Math.max(0,Math.min(cols-1,(int)(x/cellWidth())));}
        int normalize(int index,boolean last){
            if(frame==null)return index;
            int row=index/cols-offset(),col=index%cols;
            if(row<0||row>=canvas.cells.length||col<0||col>=canvas.cells[row].length)return index;
            int first=col;
            while(first>0&&(canvas.cells[row][first].skip||canvas.cells[row][first].wide==2))first--;
            Renderer.Cell cell=canvas.cells[row][first];
            return index-col+first+(last?Math.max(0,Math.min(cols-first,cell.displaySpan)-1):0);
        }
        void clear(){selecting=false;handleDrag=false;selectionChanged("");ui.removeCallbacks(autoScroll);invalidate();}
        float hx(int index,boolean last){return (index%cols+(last?1:0))*cellWidth();}
        float hy(int index){return (index/cols-offset()+1)*cellHeight()+dp(7);}
        boolean hit(MotionEvent e,int index,boolean last){return Math.hypot(e.getX()-hx(index,last),e.getY()-hy(index))<dp(26);}
        @Override public boolean onTouchEvent(MotionEvent e){
            if(session==null)return false;
            pointerX=e.getX();pointerY=e.getY();
            int action=e.getActionMasked();
            if((mouseSelection||rightClick)&&frame!=null&&frame.optBoolean("mouse")&&e.getPointerCount()==1){if(action==MotionEvent.ACTION_DOWN){mousePressed=true;mouse(e,0);}else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){mousePressed=false;mouse(e,1);}else if(action==MotionEvent.ACTION_MOVE&&mouseSelection)mouse(e,2);return true;}
            if(dragArrows&&e.getPointerCount()==1){if(action==MotionEvent.ACTION_DOWN){arrowX=e.getX();arrowY=e.getY();}else if(action==MotionEvent.ACTION_MOVE){float dx=e.getX()-arrowX,dy=e.getY()-arrowY;if(Math.max(Math.abs(dx),Math.abs(dy))>dp(18)){sendKey(Math.abs(dx)>Math.abs(dy)?dx>0?TerminalSession.Key.RIGHT:TerminalSession.Key.LEFT:dy>0?TerminalSession.Key.DOWN:TerminalSession.Key.UP);arrowX=e.getX();arrowY=e.getY();}}return true;}
            if(action==MotionEvent.ACTION_DOWN){multi=false;scaled=false;swipeHandled=false;doubleHeld=false;downX=e.getX();downY=e.getY();
                long now=e.getEventTime();if(now-lastTap<ViewConfiguration.getDoubleTapTimeout()&&Math.hypot(downX-tapX,downY-tapY)<dp(40))tapCount++;else tapCount=1;
                lastTap=now;tapX=downX;tapY=downY;ui.removeCallbacks(tapAction);ui.removeCallbacks(holdAction);if(tapCount==2)ui.postDelayed(holdAction,ViewConfiguration.getLongPressTimeout());
            }
            scaleDetector.onTouchEvent(e);
            if(action==MotionEvent.ACTION_POINTER_DOWN&&e.getPointerCount()==2){if(doubleHeld){gesture("double_hold_cancel",e.getX(),e.getY());doubleHeld=false;}MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);detector.onTouchEvent(cancel);cancel.recycle();multi=true;multiX=(e.getX(0)+e.getX(1))/2;multiY=(e.getY(0)+e.getY(1))/2;ui.removeCallbacks(holdAction);ui.removeCallbacks(tapAction);}
            if(multi){
                if(action==MotionEvent.ACTION_POINTER_UP&&e.getPointerCount()==2&&!scaled&&!swipeHandled){float dx=(e.getX(0)+e.getX(1))/2-multiX,dy=(e.getY(0)+e.getY(1))/2-multiY;if(Math.max(Math.abs(dx),Math.abs(dy))>dp(48)){gesture(Math.abs(dx)>Math.abs(dy)?dx>0?"two_right":"two_left":dy>0?"two_down":"two_up",e.getX(),e.getY());swipeHandled=true;}}
                if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){multi=false;tapCount=0;}return true;
            }
            if(doubleHeld){if(action==MotionEvent.ACTION_MOVE)gesture("double_hold_move",e.getX(),e.getY());if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){gesture(action==MotionEvent.ACTION_CANCEL?"double_hold_cancel":"double_hold_end",e.getX(),e.getY());ui.removeCallbacks(holdAction);tapCount=0;doubleHeld=false;}return true;}
            if(action==MotionEvent.ACTION_MOVE&&Math.hypot(e.getX()-downX,e.getY()-downY)>ViewConfiguration.get(getContext()).getScaledTouchSlop())ui.removeCallbacks(holdAction);
            if(action==MotionEvent.ACTION_UP){ui.removeCallbacks(holdAction);if(!selecting&&!swipeHandled&&Math.hypot(e.getX()-downX,e.getY()-downY)<dp(12)&&e.getEventTime()-e.getDownTime()<ViewConfiguration.getLongPressTimeout())ui.postDelayed(tapAction,ViewConfiguration.getDoubleTapTimeout());else tapCount=0;}
            if(action==MotionEvent.ACTION_CANCEL){ui.removeCallbacks(holdAction);ui.removeCallbacks(tapAction);tapCount=0;}
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                handleDrag=selecting&&(hit(e,start,false)||hit(e,end,true));
                if(handleDrag){dragHandle=Math.hypot(e.getX()-hx(start,false),e.getY()-hy(start))<=Math.hypot(e.getX()-hx(end,true),e.getY()-hy(end))?0:1;ui.postDelayed(autoScroll,80);return true;}
            }
            if(!handleDrag)detector.onTouchEvent(e);
            if(selecting&&e.getAction()==MotionEvent.ACTION_MOVE){
                int at=cell(pointerX,pointerY);if(dragHandle==0)start=normalize(at,false);else end=normalize(at,true);
                updateSelection();ui.removeCallbacks(autoScroll);ui.postDelayed(autoScroll,80);
            }
            if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){handleDrag=false;ui.removeCallbacks(autoScroll);}
            return true;
        }
        @Override protected void onDetachedFromWindow(){cancelPendingGestures();super.onDetachedFromWindow();}
        void autoScrollTick(){
            if(!selecting||frame==null||frame.optInt("alt")!=0)return;
            int delta=pointerY<cellHeight()?-1:pointerY>getHeight()-cellHeight()?1:0;
            if(delta!=0){session.scroll(delta);refreshFrame();int at=cell(pointerX,pointerY);if(dragHandle==0)start=normalize(at,false);else end=normalize(at,true);updateSelection();ui.postDelayed(autoScroll,80);}
        }
        void updateSelection(){
            try{int lo=normalize(Math.min(start,end),false),hi=normalize(Math.max(start,end),true);
                selectionChanged(session.select(lo,hi));invalidate();
            }catch(Exception e){clear();}
        }
        @Override protected void onDraw(Canvas c){
            if(frame==null)return;
            if(!selecting)return;
            int lo=normalize(Math.min(start,end),false),hi=normalize(Math.max(start,end),true);
            overlay.setColor(0x665b9cff);
            for(int i=Math.max(lo,offset()*cols);i<=Math.min(hi,(offset()+rows)*cols-1);i++)c.drawRect((i%cols)*cellWidth(),(i/cols-offset())*cellHeight(),(i%cols+1)*cellWidth(),(i/cols-offset()+1)*cellHeight(),overlay);
            overlay.setColor(0xff7eb4ff);c.drawCircle(hx(start,false),hy(start),dp(9),overlay);c.drawCircle(hx(end,true),hy(end),dp(9),overlay);
        }
    }
}
