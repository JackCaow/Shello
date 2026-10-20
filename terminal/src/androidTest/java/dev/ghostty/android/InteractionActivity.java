package dev.ghostty.android;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;

/** Test-only real window for gestures and the platform InputConnection. */
public final class InteractionActivity extends Activity {
    TerminalSession session;
    TerminalView terminal;
    TerminalInputView input;
    final List<byte[]> sent = new ArrayList<>();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        terminal = new TerminalView(this);
        input = new TerminalInputView(this);
        root.addView(terminal, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(input, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        session = new TerminalSession(40, 8);
        terminal.setListener(new TerminalView.Listener() {
            public void onInput(byte[] bytes) { sent.add(bytes); }
            public void onResize(int columns, int rows) {}
        });
        terminal.setSession(session);
        input.setTerminalView(terminal);
    }
    @Override protected void onDestroy() {
        input.setTerminalView(null);
        terminal.setSession(null);
        session.close();
        super.onDestroy();
    }
}
