package dev.ghostty.android.sample;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** UTF-8 output is required even when an SSH exec channel has no UTF-8 locale. */
final class TmuxSessions {
    // -u preserves Unicode names under LANG=C; numeric fields first keep names with spaces intact.
    static final String COMMAND=SshConnectionSetup.TMUX_LOOKUP+
        "\"$T\" -u list-sessions -F '#{session_windows} #{session_attached} #{session_name}' 2>/dev/null || { r=$?; [ $r = 1 ] || exit $r; }";
    static final class Entry {
        final String name;final int windows,attached;
        Entry(String name,int windows,int attached){this.name=name;this.windows=windows;this.attached=attached;}
    }
    static List<Entry> parse(String output)throws IOException {
        List<Entry> entries=new ArrayList<>();
        for(String line:output.split("\n")){
            if(line.isEmpty())continue;
            String[] fields=line.split(" ",3);
            try{
                if(fields.length!=3||!fields[0].matches("[0-9]+")||!fields[1].matches("[0-9]+"))throw new IllegalArgumentException();
                int windows=Integer.parseInt(fields[0]),attached=Integer.parseInt(fields[1]);
                SessionTarget.validate("="+fields[2]);
                entries.add(new Entry(fields[2],windows,attached));
            }catch(IllegalArgumentException invalid){throw new IOException("Invalid tmux session listing",invalid);}
        }
        return entries;
    }
}
