package dev.ghostty.android.sample;

import java.util.*;

/** All configurable gestures resolve through the shared action catalog. */
public final class GesturesSettingsActivity extends InputSettingsActivity {
    @Override protected void renderSettings(){heading.setText(AppLanguage.tr(R.string.input_gestures));section(AppLanguage.tr(R.string.input_terminal));toggle(AppLanguage.tr(R.string.long_press_to_select_text),null,null,"appearance","selection",true,null);
        for(String[] g:new String[][]{{"single_tap",AppLanguage.tr(R.string.input_single_tap)},{"double_tap",AppLanguage.tr(R.string.input_double_tap)},{"triple_tap",AppLanguage.tr(R.string.input_triple_tap)},{"pull_down",AppLanguage.tr(R.string.input_pull_down)},{"swipe_left",AppLanguage.tr(R.string.input_swipe_left)},{"swipe_right",AppLanguage.tr(R.string.input_swipe_right)},{"two_left",AppLanguage.tr(R.string.input_two_finger_swipe_left)},{"two_right",AppLanguage.tr(R.string.input_two_finger_swipe_right)},{"two_up",AppLanguage.tr(R.string.input_two_finger_swipe_up)},{"two_down",AppLanguage.tr(R.string.input_two_finger_swipe_down)}})mapping(g);
        inputToggle(AppLanguage.tr(R.string.input_pinch_to_change_font_size),null,"pinch_font",true);section(AppLanguage.tr(R.string.input_toolbar));mapping(new String[]{"toolbar_up",AppLanguage.tr(R.string.input_swipe_toolbar_up)});mapping(new String[]{"toolbar_down",AppLanguage.tr(R.string.input_swipe_toolbar_down)});
        section(AppLanguage.tr(R.string.input_buttons));for(String[] g:new String[][]{{"keyboard_tap",AppLanguage.tr(R.string.input_keyboard_single_tap)},{"keyboard_double",AppLanguage.tr(R.string.input_keyboard_double_tap)},{"keyboard_long",AppLanguage.tr(R.string.input_keyboard_long_press)},{"ctrl_double",AppLanguage.tr(R.string.input_ctrl_double_tap)},{"ctrl_long",AppLanguage.tr(R.string.input_ctrl_long_press)},{"shortcut_double",AppLanguage.tr(R.string.input_shortcuts_double_tap)}})mapping(g);
        section(AppLanguage.tr(R.string.input_hardware_buttons));mapping(new String[]{"volume_up",AppLanguage.tr(R.string.input_volume_up)});mapping(new String[]{"volume_down",AppLanguage.tr(R.string.input_volume_down)});copy(AppLanguage.tr(R.string.input_volume_keys_are_intercepted_only_when_an_action_is_assigned));
        section(AppLanguage.tr(R.string.input_d_pad_corners));for(String[] g:new String[][]{{"corner_ul",AppLanguage.tr(R.string.input_upper_left)},{"corner_ur",AppLanguage.tr(R.string.input_upper_right)},{"corner_ll",AppLanguage.tr(R.string.input_lower_left)},{"corner_lr",AppLanguage.tr(R.string.input_lower_right)}})mapping(g);copy(AppLanguage.tr(R.string.input_tap_the_handle_to_collapse_or_expand_drag_to_move_and_hold_to_lock_or_));
    }
    private void mapping(String[] g){String selected=controls().mapping(g[0]);item(g[1],controls().label(selected),"pad",()->startActivity(new android.content.Intent(this,GestureActionActivity.class).putExtra("gesture",g[0]).putExtra("title",g[1])));}
}
