package dev.ghostty.android;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.inputmethod.*;
import android.widget.EditText;

/** Optional direct-input IME bridge. Unconfirmed composing text stays in the local EditText/IME. */
public final class TerminalInputView extends EditText {
    private TerminalView terminal;
    private int latchedModifiers;private boolean optionAsMeta;
    private Runnable modifiersConsumed;
    public void setModifiers(int value,Runnable consumed){if((value&~15)!=0)throw new IllegalArgumentException("Unknown modifier");latchedModifiers=value;modifiersConsumed=consumed;}
    public void setOptionAsMeta(boolean value){optionAsMeta=value;}
    private void consumed(){if(modifiersConsumed!=null)modifiersConsumed.run();}
    private void commit(String text){
        if(latchedModifiers==0)terminal.sendText(text);
        else text.codePoints().forEach(cp->terminal.sendCharacter(Character.toLowerCase(cp),new String(Character.toChars(cp)),latchedModifiers));
        consumed();
    }
    public TerminalInputView(Context context) { this(context,null); }
    public TerminalInputView(Context context,AttributeSet attrs) {
        super(context,attrs);
        // Raw terminal keys must reach menus/Vim immediately instead of becoming an
        // invisible English word in Latin IMEs. Do not force ASCII: CJK IMEs can
        // still retain Pinyin candidates and commit the selected Unicode text.
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        setHint(R.string.terminal_input_hint);setMinLines(1);setMaxLines(3);
    }
    public void setTerminalView(TerminalView terminal) { this.terminal=terminal;getText().clear(); }
    private boolean composing(){int start=BaseInputConnection.getComposingSpanStart(getText());return start>=0&&BaseInputConnection.getComposingSpanEnd(getText())>start;}
    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        InputConnection base=super.onCreateInputConnection(info);
        if(base==null)return null;
        return new InputConnectionWrapper(base,true){
            @Override public boolean commitText(CharSequence text,int position){
                boolean result=super.commitText(text,position);
                if(terminal!=null)commit(text.toString());
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
        for(int i=0;i<before;i++)terminal.sendKey(TerminalSession.Key.BACKSPACE,latchedModifiers);
        for(int i=0;i<after;i++)terminal.sendKey(TerminalSession.Key.DELETE,latchedModifiers);
        consumed();
    }
    @Override public boolean onKeyDown(int code,KeyEvent event){return handle(event)||super.onKeyDown(code,event);}
    @Override public boolean onKeyUp(int code,KeyEvent event){return handle(event)||super.onKeyUp(code,event);}
    private boolean handle(KeyEvent event){
        if(terminal==null||composing()||event.getAction()==KeyEvent.ACTION_MULTIPLE)return false;
        int mods=latchedModifiers|(event.isShiftPressed()?1:0)|(event.isCtrlPressed()?2:0)|(event.isAltPressed()&&optionAsMeta?4:0)|(event.isMetaPressed()?8:0);
        int code=event.getKeyCode();TerminalSession.Key key=switch(code){
            case KeyEvent.KEYCODE_DPAD_UP->TerminalSession.Key.UP;case KeyEvent.KEYCODE_DPAD_DOWN->TerminalSession.Key.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT->TerminalSession.Key.LEFT;case KeyEvent.KEYCODE_DPAD_RIGHT->TerminalSession.Key.RIGHT;
            case KeyEvent.KEYCODE_PAGE_UP->TerminalSession.Key.PAGE_UP;case KeyEvent.KEYCODE_PAGE_DOWN->TerminalSession.Key.PAGE_DOWN;
            case KeyEvent.KEYCODE_MOVE_HOME->TerminalSession.Key.HOME;case KeyEvent.KEYCODE_MOVE_END->TerminalSession.Key.END;
            case KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER->TerminalSession.Key.ENTER;case KeyEvent.KEYCODE_TAB->TerminalSession.Key.TAB;
            case KeyEvent.KEYCODE_ESCAPE->TerminalSession.Key.ESCAPE;case KeyEvent.KEYCODE_DEL->TerminalSession.Key.BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL->TerminalSession.Key.DELETE;case KeyEvent.KEYCODE_INSERT->TerminalSession.Key.INSERT;
            default->code>=KeyEvent.KEYCODE_F1&&code<=KeyEvent.KEYCODE_F12?TerminalSession.Key.values()[TerminalSession.Key.F1.ordinal()+code-KeyEvent.KEYCODE_F1]:null;
        };
        if(key!=null){if(event.getAction()==KeyEvent.ACTION_DOWN){terminal.sendKey(key,mods);consumed();}return true;}
        int state=event.getMetaState()&~KeyEvent.META_CTRL_MASK&~KeyEvent.META_META_MASK;
        if(optionAsMeta)state&=~KeyEvent.META_ALT_MASK;
        int cp=event.getUnicodeChar(state);
        if(cp>0&&(cp&KeyCharacterMap.COMBINING_ACCENT)==0){
            if(event.getAction()==KeyEvent.ACTION_DOWN){int base=event.getUnicodeChar(state&~KeyEvent.META_SHIFT_MASK);terminal.sendCharacter(base>0?base:cp,new String(Character.toChars(cp)),mods);consumed();}return true;
        }
        return false;
    }
}
