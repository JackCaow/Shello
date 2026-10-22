package dev.ghostty.android.sample;

import org.junit.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.Assert.*;

public class TmuxSessionsTest {
    @Test public void namesKeepSpacesUnicodeAndUnderscores()throws Exception{
        List<TmuxSessions.Entry> entries=TmuxSessions.parse("2 1 中文 shell_1_0\n1 0  leading space \n");
        assertEquals(2,entries.size());assertEquals("中文 shell_1_0",entries.get(0).name);
        assertEquals(2,entries.get(0).windows);assertEquals(1,entries.get(0).attached);
        assertEquals(" leading space ",entries.get(1).name);assertEquals(0,entries.get(1).attached);
        assertTrue(TmuxSessions.parse("").isEmpty());
    }
    @Test public void malformedOutputIsNotAnEmptyList()throws Exception{
        for(String output:new String[]{"shell_1_0\n","banner\n1 0 shell\n","-1 0 shell","1 x shell","2147483648 0 shell","1 0 "}){
            try{TmuxSessions.parse(output);fail("Malformed output accepted");}catch(IOException expected){}
        }
    }
    @Test public void remoteCommandContainsNoControlCharacters(){
        for(char c:TmuxSessions.COMMAND.toCharArray())assertFalse(Character.isISOControl(c));
        assertTrue(TmuxSessions.COMMAND.contains("-u list-sessions"));
        assertTrue(TmuxSessions.COMMAND.contains("#{session_windows} #{session_attached} #{session_name}"));
    }
}
