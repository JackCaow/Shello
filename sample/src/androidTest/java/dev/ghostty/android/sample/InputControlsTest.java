package dev.ghostty.android.sample;

import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

/** Independent preference and shortcut contracts; restores the user's configuration afterwards. */
public class InputControlsTest {
    final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();InputPreferences config;Map<String,?> saved;
    @Before public void before(){config=new InputPreferences(context);saved=config.prefs.getAll();config.prefs.edit().clear().commit();}
    @After public void restore(){SharedPreferences.Editor e=config.prefs.edit().clear();for(var entry:saved.entrySet()){Object v=entry.getValue();String k=entry.getKey();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Long)e.putLong(k,(Long)v);}assertTrue(e.commit());}
    @Test public void independentLayoutsPersistAndCtrlCannotBeLost(){
        config.save("layout_toolbar",List.of("key_TAB","pad"));config.save("layout_composer",List.of("history","key_ESCAPE"));config.save("layout_zen",List.of("key_ENTER","keyboard"));
        InputPreferences fresh=new InputPreferences(context);assertEquals(List.of("key_TAB","pad","ctrl"),fresh.list("layout_toolbar"));assertEquals(List.of("history","key_ESCAPE"),fresh.list("layout_composer"));assertEquals(List.of("key_ENTER","keyboard"),fresh.list("layout_zen"));
        config.reset("layout_composer");assertTrue(config.list("layout_composer").contains("shortcuts"));assertEquals(List.of("key_TAB","pad","ctrl"),config.list("layout_toolbar"));
    }
    @Test public void customEditingFavoriteAndRemovalAcrossLayouts(){
        String id=config.put(null,"中文输入","text:中文 👨‍👩‍👧‍👦\nhello, world",true);config.save("group_Favorites",List.of(id));config.save("layout_composer",List.of(id));
        InputPreferences fresh=new InputPreferences(context);assertTrue(fresh.action(id).enter());assertEquals("中文输入",fresh.action(id).label());assertEquals("中文 👨‍👩‍👧‍👦\nhello, world",InputActions.parse(fresh.action(id).binding()).get(0).text());
        config.put(id,"Updated","C-b, S-t",false);assertEquals("Updated",fresh.action(id).label());assertEquals(2,InputActions.parse(fresh.action(id).binding()).size());
        String named=config.put(null,"文件","text:value",false);assertEquals("文件",fresh.action(named).label());
        config.removeCustom(id);assertNull(fresh.action(id));assertTrue(fresh.list("group_Favorites").isEmpty());assertTrue(fresh.list("layout_composer").isEmpty());
    }
    @Test public void everyPresetCompilesAndEnterIsExplicit(){
        for(InputActions.Action a:InputActions.CATALOG.values())InputActions.parse(a.binding());
        assertEquals(8,config.list("group_Claude").size());assertEquals(8,config.list("group_Codex").size());
        assertTrue(config.action("preset_Claude_0").enter());assertFalse(config.action("preset_Claude_1").enter());
        assertEquals("/clear",InputActions.parse(config.action("preset_Claude_0").binding()).get(0).text());assertEquals("/new",InputActions.parse(config.action("preset_Codex_0").binding()).get(0).text());
    }
    @Test public void mappingsAndVisibleGroupsRoundTrip(){
        config.mapping("double_tap","key_ESCAPE");config.mapping("volume_up","ctrl_c");config.save("groups",List.of("Codex","Claude","Favorites"));
        InputPreferences fresh=new InputPreferences(context);assertEquals("key_ESCAPE",fresh.mapping("double_tap"));assertEquals("ctrl_c",fresh.mapping("volume_up"));assertEquals(List.of("Codex","Claude","Favorites"),fresh.list("groups"));assertEquals("none",fresh.mapping("volume_down"));
    }
    @Test public void configurablePrefixAndAdvancedAliases(){
        config.prefs.edit().putString("prefix_Tmux","F12").commit();assertEquals("F12, c",config.action("preset_Tmux_0").binding());
        assertEquals(4,config.list("group_Tmux").size());assertEquals(8,config.list("group_Copilot").size());
        assertEquals(2,InputActions.parse("Ctrl+b Shift+Tab").size());assertEquals(2,InputActions.parse("Ctrl+b , Shift+Tab").size());assertEquals(6,InputActions.parse("C-M-b").get(0).modifiers());
        assertEquals("+",InputActions.parse("plus").get(0).text());assertEquals("-",InputActions.parse("dash").get(0).text());
    }
    @Test public void invalidShortcutDoesNotOverwriteExisting(){
        String id=config.put(null,"Original","C-c",false);try{config.put(id,"Broken","C-b, INVALID_KEY",true);fail();}catch(IllegalArgumentException expected){}assertEquals("C-c",config.action(id).binding());
        for(String invalid:new String[]{"","C-b,","unknownkey","action:does-not-exist"})try{InputActions.parse(invalid);fail(invalid);}catch(IllegalArgumentException expected){}
    }
}
