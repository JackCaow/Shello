package dev.ghostty.android.sample;

/** Physical keyboard settings, applied on hotplug as well as Activity resume. */
public final class HardwareKeyboardSettingsActivity extends InputSettingsActivity {
    @Override protected void renderSettings(){heading.setText(AppLanguage.tr(R.string.input_physical_keyboard));section(AppLanguage.tr(R.string.input_behavior));inputToggle(AppLanguage.tr(R.string.input_hide_toolbar_automatically),AppLanguage.tr(R.string.input_hide_touch_controls_while_a_physical_keyboard_is_connected),"hardware_hide",true);inputToggle(AppLanguage.tr(R.string.input_option_as_meta),AppLanguage.tr(R.string.input_send_alt_option_as_the_terminal_meta_modifier),"option_meta",false);
        section(AppLanguage.tr(R.string.input_shortcuts_3));for(String[] shortcut:new String[][]{{"Cmd+K",AppLanguage.tr(R.string.input_show_shortcut_panel)},{"Cmd+1–9",AppLanguage.tr(R.string.input_switch_session)},{"Cmd+W",AppLanguage.tr(R.string.input_close_current_session)},{"Cmd+V",AppLanguage.tr(R.string.input_paste_text_or_image)},{"Cmd+J",AppLanguage.tr(R.string.input_toggle_text_input_box)},{"Cmd+Shift+M",AppLanguage.tr(R.string.input_dictation)}})item(shortcut[0],shortcut[1],"keyboard",null);
        copy(AppLanguage.tr(R.string.input_arrow_keys_home_end_page_up_down_f1_f12_and_ctrl_alt_and_shift_combina));}
}
