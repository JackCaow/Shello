package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;


/** Independent gestures settings destination. */
public final class GesturesSettingsActivity extends SettingsPageActivity {
    @Override protected void renderSettings(){
        heading.setText(tr(R.string.gestures));section(tr(R.string.terminal));toggle(tr(R.string.long_press_to_select_text),null,null,"appearance","selection",true,null);
        item(tr(R.string.swipe_up_or_down),tr(R.string.browse_terminal_scrollback),"history",null);item(tr(R.string.drag_selection_handles),tr(R.string.adjust_the_text_selection),"pad",null);
        section(tr(R.string.home));item(tr(R.string.long_press_a_session_card),tr(R.string.reorder_close_or_close_all),"reorder",null);
    }
}
