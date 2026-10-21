package dev.ghostty.android;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.*;
import android.widget.EditText;

/** Optional direct-input IME bridge. Unconfirmed composing text stays in the local EditText/IME. */
public final class TerminalInputView extends EditText {
    private TerminalView terminal;
    public TerminalInputView(Context context) { this(context,null); }
    public TerminalInputView(Context context,AttributeSet attrs) {
        super(context,attrs);
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        setHint(R.string.terminal_input_hint);setMinLines(1);setMaxLines(3);
    }
    public void setTerminalView(TerminalView terminal) { this.terminal=terminal;getText().clear(); }
    private boolean composing(){return BaseInputConnection.getComposingSpanStart(getText())>=0;}
    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        InputConnection base=super.onCreateInputConnection(info);
        if(base==null)return null;
        return new InputConnectionWrapper(base,true){
            @Override public boolean commitText(CharSequence text,int position){
                boolean result=super.commitText(text,position);
                if(terminal!=null)terminal.sendText(text.toString());
                getText().clear();return result;
            }
            @Override public boolean deleteSurroundingText(int before,int after){
                if(!composing()&&getText().length()==0&&terminal!=null){delete(before,after);return true;}
                return super.deleteSurroundingText(before,after);
            }
            @Override public boolean deleteSurroundingTextInCodePoints(int before,int after){
                if(!composing()&&getText().length()==0&&terminal!=null){delete(before,after);return true;}
                return super.deleteSurroundingTextInCodePoints(before,after);
            }
            @Override public boolean sendKeyEvent(KeyEvent event){return handle(event)||super.sendKeyEvent(event);}
        };
    }
    private void delete(int before,int after){
        // Deletion is interpreted by the remote application; never split local UTF-8 bytes.
        for(int i=0;i<before;i++)terminal.sendKey(TerminalSession.Key.BACKSPACE);
        for(int i=0;i<after;i++)terminal.sendText("\033[3~");
    }
    @Override public boolean onKeyDown(int code,KeyEvent event){return handle(event)||super.onKeyDown(code,event);}
    @Override public boolean onKeyUp(int code,KeyEvent event){return handle(event)||super.onKeyUp(code,event);}
    private boolean handle(KeyEvent event){
        if(terminal==null||composing())return false;
        int[] codes={KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_PAGE_UP,KeyEvent.KEYCODE_PAGE_DOWN,KeyEvent.KEYCODE_MOVE_HOME,KeyEvent.KEYCODE_MOVE_END,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_TAB,KeyEvent.KEYCODE_ESCAPE,KeyEvent.KEYCODE_DEL};
        for(int i=0;i<codes.length;i++)if(event.getKeyCode()==codes[i]){
            if(event.getAction()==KeyEvent.ACTION_DOWN)terminal.sendKey(TerminalSession.Key.values()[i]);return true;
        }
        int cp=event.getUnicodeChar(event.getMetaState()&~KeyEvent.META_CTRL_MASK);
        if(cp>0){
            if(event.getAction()==KeyEvent.ACTION_DOWN){
                if(event.isCtrlPressed()&&cp<128)terminal.sendText(Character.toString((char)(cp&31)));
                else terminal.sendText(new String(Character.toChars(cp)));
            }
            return true;
        }
        return false;
    }
}
