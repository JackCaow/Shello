package dev.ghostty.android.consumer;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.ghostty.android.TerminalSession;
import java.nio.charset.StandardCharsets;

/** Exercises the Maven AAR under R8, without a project/source dependency. */
public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String result;
        try (TerminalSession terminal=new TerminalSession(40,4)) {
            terminal.write("中文👨‍👩‍👧‍👦".getBytes(StandardCharsets.UTF_8));
            if(!terminal.select(0,11).equals("中文👨‍👩‍👧‍👦"))throw new AssertionError("AAR Unicode/JNI mismatch");
            try(TerminalSession restored=TerminalSession.restore(terminal.checkpoint())) {
                if(!restored.select(0,11).equals("中文👨‍👩‍👧‍👦"))throw new AssertionError("AAR checkpoint mismatch");
            }
            result="AAR smoke OK";
        } catch(Throwable failure) { result="AAR smoke FAIL: "+failure; }
        TextView label=new TextView(this);label.setText(result);label.setTextSize(24);label.setPadding(24,200,24,24);setContentView(label);
    }
}
