package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;


/** Independent shortcuts settings destination. */
public final class ShortcutsSettingsActivity extends SettingsPageActivity {
    @Override protected void renderSettings(){
        heading.setText(tr(R.string.shortcuts));section(tr(R.string.terminal_keys));item("Ctrl",tr(R.string.tap_to_combine_with_the_next_character),"keyboard",null);item("Esc / Tab",tr(R.string.send_the_corresponding_terminal_key),"keyboard",null);
        section(tr(R.string.direction_pad));item(tr(R.string.arrow_keys),tr(R.string.move_up_down_left_or_right),"pad",null);item(tr(R.string.enter_and_backspace),tr(R.string.confirm_input_or_delete_a_character),"return",null);item(tr(R.string.clear_screen),tr(R.string.send_the_clear_screen_shortcut),"clear",null);
        section(tr(R.string.text_draft));item(tr(R.string.tap_send),tr(R.string.send_text_and_press_enter),"send",null);item(tr(R.string.long_press_send),tr(R.string.send_text_only),"edit",null);
    }
}
