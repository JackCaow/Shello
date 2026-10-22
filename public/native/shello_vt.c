#include "shello_vt.h"
#include <ghostty/vt.h>
#include <stdlib.h>
#include <string.h>

struct ShelloTerminal {
    GhosttyTerminal terminal;
    GhosttyRenderState render;
    uint8_t replies[65536]; size_t reply_len; bool reply_overflow;
};
static void reply(GhosttyTerminal t, void *user, const uint8_t *data, size_t len) {
    (void)t; struct ShelloTerminal *l = user;
    if (len > sizeof(l->replies) - l->reply_len) { l->reply_overflow = true; return; }
    memcpy(l->replies + l->reply_len, data, len); l->reply_len += len;
}
static ShelloTerminal *wrap(GhosttyTerminal t, int *status) {
    if (!t) return NULL;
    ShelloTerminal *l = calloc(1, sizeof(*l));
    if (!l) { ghostty_terminal_free(t); if (status) *status = SHELLO_ERR_MEM; return NULL; }
    l->terminal = t;
    GhosttyResult r = ghostty_render_state_new(NULL, &l->render);
    if (r != GHOSTTY_SUCCESS) { ghostty_terminal_free(t); free(l); if (status) *status = r; return NULL; }
    ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_USERDATA, l);
    ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_WRITE_PTY, (const void *)reply);
    return l;
}
ShelloTerminal *shello_terminal_create(int cols, int rows, int *status) {
    if (status) *status = 0;
    GhosttyTerminal t = NULL;
    GhosttyResult r = ghostty_terminal_new(NULL, &t, cols, rows);
    if (r != GHOSTTY_SUCCESS || !t) { if (status) *status = r ? r : SHELLO_ERR_MEM; return NULL; }
    GhosttyColorRgb fg = {.r = 255, .g = 255, .b = 255}, bg = {0};
    ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &fg);
    ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &bg);
    size_t limit = 65536; ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_CONTINUATION_MAX_BYTES, &limit);
    size_t bytes = 4 * 1024 * 1024; ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &bytes);
    bool blink = true; ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_BLINK, &blink);
    const uint8_t cursor_reset[] = "\033[0 q";
    ghostty_terminal_vt_write(t, cursor_reset, sizeof(cursor_reset) - 1);
    return wrap(t, status);
}
void shello_terminal_free(ShelloTerminal *l) {
    if (!l) return;
    ghostty_render_state_free(l->render); ghostty_terminal_free(l->terminal); free(l);
}
int shello_terminal_write(ShelloTerminal *l, const uint8_t *data, size_t len) {
    if (!l || (!data && len)) return SHELLO_ERR_ARG;
    ghostty_terminal_vt_write(l->terminal, data, len); return 0;
}
int shello_terminal_take_replies(ShelloTerminal *l, uint8_t *out, size_t cap, size_t *len) {
    if (!l || !len) return SHELLO_ERR_ARG;
    if (l->reply_overflow) { l->reply_overflow = false; l->reply_len = 0; *len = 0; return GHOSTTY_OUT_OF_SPACE; }
    if (cap < l->reply_len) { *len = l->reply_len; return SHELLO_ERR_ARG; }
    if (l->reply_len) memcpy(out, l->replies, l->reply_len);
    *len = l->reply_len; l->reply_len = 0; return 0;
}
int shello_terminal_resize(ShelloTerminal *l, int cols, int rows) { return l ? ghostty_terminal_resize(l->terminal, cols, rows, 0, 0) : SHELLO_ERR_ARG; }
int shello_terminal_scroll(ShelloTerminal *l, int delta) {
    if (!l) return SHELLO_ERR_ARG;
    GhosttyTerminalScrollViewport v = {.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA, .value.delta = delta};
    ghostty_terminal_scroll_viewport(l->terminal, v); return 0;
}
static int rgb(GhosttyColorRgb c) { return (c.r << 16) | (c.g << 8) | c.b; }
static int color(GhosttyStyleColor c, const GhosttyRenderStateColors *colors, int fallback) {
    if (c.tag == GHOSTTY_STYLE_COLOR_RGB) return rgb(c.value.rgb);
    if (c.tag == GHOSTTY_STYLE_COLOR_PALETTE) return rgb(colors->palette[c.value.palette]);
    return fallback;
}
/* Erase/fill can store background-only cells with no style entry; resolve before SGR inverse. */
static int background(GhosttyCell cell, const GhosttyStyle *style, const GhosttyRenderStateColors *colors) {
    GhosttyCellContentTag tag; ghostty_cell_get(cell, GHOSTTY_CELL_DATA_CONTENT_TAG, &tag);
    if (tag == GHOSTTY_CELL_CONTENT_BG_COLOR_RGB) { GhosttyColorRgb v; ghostty_cell_get(cell, GHOSTTY_CELL_DATA_COLOR_RGB, &v); return rgb(v); }
    if (tag == GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE) { GhosttyColorPaletteIndex i; ghostty_cell_get(cell, GHOSTTY_CELL_DATA_COLOR_PALETTE, &i); return rgb(colors->palette[i]); }
    return color(style->bg_color, colors, rgb(colors->background));
}
int shello_terminal_render(ShelloTerminal *l, int32_t **words, size_t *count) {
    if (!l || !words || !count) return SHELLO_ERR_ARG;
    GhosttyTerminal t = l->terminal; uint16_t cols = 0, rows = 0; int active = 0; bool mouse = false;
    ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_COLS, &cols); ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_ROWS, &rows);
    ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &active); ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &mouse);
    GhosttyTerminalScrollbar bar = {0}; ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &bar);
    ghostty_render_state_update(l->render, t);
    GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
    GhosttyRenderStateCursor cursor = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
    ghostty_render_state_get(l->render, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);
    ghostty_render_state_get(l->render, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cursor);
    size_t cap = 14 + (size_t)cols * rows * (7 + 128);
    int32_t *data = malloc(cap * sizeof(int32_t)); if (!data) return SHELLO_ERR_MEM;
    size_t at = 14;
    int32_t header[] = {cols, rows, cursor.viewport_has_value ? cursor.viewport_x : 0, cursor.viewport_has_value ? cursor.viewport_y : 0,
        active, mouse, (int32_t)bar.offset, (int32_t)bar.total, cursor.visible && cursor.viewport_has_value, cursor.blinking, cursor.visual_style,
        colors.cursor_has_value ? rgb(colors.cursor) : rgb(colors.foreground), rgb(colors.background), 0};
    memcpy(data, header, sizeof(header));
    for (int row = 0; row < rows; row++) for (int col = 0; col < cols; col++) {
        GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
        GhosttyPoint p = {.tag = GHOSTTY_POINT_TAG_VIEWPORT, .value.coordinate = {.x = col, .y = row}};
        if (ghostty_terminal_grid_ref(t, p, &ref) != GHOSTTY_SUCCESS) { for (int i = 0; i < 7; i++) data[at++] = 0; continue; }
        GhosttyCell cell; GhosttyCellWide wide; ghostty_grid_ref_cell(&ref, &cell); ghostty_cell_get(cell, GHOSTTY_CELL_DATA_WIDE, &wide);
        GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle); ghostty_grid_ref_style(&ref, &style);
        uint32_t cps[128]; size_t n = 0;
        if (ghostty_grid_ref_graphemes(&ref, cps, 128, &n) != GHOSTTY_SUCCESS) n = 0;
        int fg = color(style.fg_color, &colors, rgb(colors.foreground)), bg = background(cell, &style, &colors);
        if (style.inverse) { int s = fg; fg = bg; bg = s; }
        data[at++] = fg; data[at++] = bg; data[at++] = wide;
        data[at++] = (style.bold ? 1 : 0) | (style.italic ? 2 : 0) | (style.faint ? 4 : 0) | (style.invisible ? 8 : 0) | (style.strikethrough ? 16 : 0) | (style.overline ? 32 : 0) | (style.blink ? 64 : 0);
        data[at++] = style.underline; data[at++] = color(style.underline_color, &colors, fg); data[at++] = (int32_t)n;
        for (size_t i = 0; i < n; i++) data[at++] = (int32_t)cps[i];
    }
    *words = data; *count = at; return 0;
}
int shello_terminal_modes(ShelloTerminal *l, uint32_t *mask) {
    if (!l || !mask) return SHELLO_ERR_ARG;
    static const int modes[] = {1, 1000, 1006, 2004}; *mask = 0;
    for (int i = 0; i < 4; i++) {
        GhosttyTerminalModeConfig m = {.mode = ghostty_mode_new(modes[i], false)};
        ghostty_terminal_get(l->terminal, GHOSTTY_TERMINAL_DATA_MODE, &m);
        if (m.value) *mask |= 1u << i;
    }
    return 0;
}
int shello_terminal_snapshot(ShelloTerminal *l, uint8_t **data, size_t *len) {
    if (!l || !data || !len) return SHELLO_ERR_ARG;
    uint8_t *p = NULL; size_t n = 0;
    GhosttyResult r = ghostty_snapshot_encode_alloc(l->terminal, NULL, &p, &n);
    if (r != GHOSTTY_SUCCESS) return r;
    uint8_t *copy = malloc(n ? n : 1); if (!copy) { ghostty_free(NULL, p, n); return SHELLO_ERR_MEM; }
    memcpy(copy, p, n); ghostty_free(NULL, p, n); *data = copy; *len = n; return 0;
}
ShelloTerminal *shello_terminal_restore(const uint8_t *data, size_t len, int *status) {
    if (status) *status = 0;
    GhosttySnapshotDecoder d = NULL; GhosttyTerminal t = NULL;
    GhosttyResult r = ghostty_snapshot_decoder_new_buf(NULL, &d, (uint8_t *)data, len);
    if (r == GHOSTTY_SUCCESS) r = ghostty_snapshot_decoder_decode(d, &t);
    if (d) ghostty_snapshot_decoder_free(d);
    if (r != GHOSTTY_SUCCESS) { if (status) *status = r; return NULL; }
    return wrap(t, status);
}
int shello_terminal_paste(ShelloTerminal *l, const uint8_t *text, size_t len, uint8_t **out, size_t *out_len) {
    if (!l || !out || !out_len || (!text && len)) return SHELLO_ERR_ARG;
    size_t cap = len + 12, written = 0; char *buffer = malloc(cap); if (!buffer) return SHELLO_ERR_MEM;
    GhosttyTerminalModeConfig mode = {.mode = GHOSTTY_MODE_BRACKETED_PASTE};
    GhosttyResult r = ghostty_terminal_get(l->terminal, GHOSTTY_TERMINAL_DATA_MODE, &mode);
    if (r == GHOSTTY_SUCCESS) r = ghostty_paste_encode((char *)text, len, mode.value, buffer, cap, &written);
    if (r != GHOSTTY_SUCCESS) { free(buffer); return r; }
    *out = (uint8_t *)buffer; *out_len = written; return 0;
}
static const GhosttyKey keys[SHELLO_KEY_COUNT] = {GHOSTTY_KEY_ARROW_UP, GHOSTTY_KEY_ARROW_DOWN, GHOSTTY_KEY_ARROW_LEFT, GHOSTTY_KEY_ARROW_RIGHT,
    GHOSTTY_KEY_PAGE_UP, GHOSTTY_KEY_PAGE_DOWN, GHOSTTY_KEY_HOME, GHOSTTY_KEY_END, GHOSTTY_KEY_ENTER, GHOSTTY_KEY_TAB, GHOSTTY_KEY_ESCAPE,
    GHOSTTY_KEY_BACKSPACE, GHOSTTY_KEY_DELETE, GHOSTTY_KEY_INSERT, GHOSTTY_KEY_F1, GHOSTTY_KEY_F2, GHOSTTY_KEY_F3, GHOSTTY_KEY_F4, GHOSTTY_KEY_F5,
    GHOSTTY_KEY_F6, GHOSTTY_KEY_F7, GHOSTTY_KEY_F8, GHOSTTY_KEY_F9, GHOSTTY_KEY_F10, GHOSTTY_KEY_F11, GHOSTTY_KEY_F12};
int shello_terminal_key(ShelloTerminal *l, int index, int mods, const uint8_t *utf8, size_t len, uint32_t cp, uint8_t *out, size_t cap, size_t *n) {
    if (!l || !n || index < -1 || index >= SHELLO_KEY_COUNT) return SHELLO_ERR_ARG;
    GhosttyKeyEncoder encoder = NULL; GhosttyKeyEvent event = NULL;
    GhosttyResult r = ghostty_key_encoder_new(NULL, &encoder);
    if (r == GHOSTTY_SUCCESS) r = ghostty_key_event_new(NULL, &event);
    if (r == GHOSTTY_SUCCESS) {
        GhosttyKey key = index >= 0 ? keys[index] : GHOSTTY_KEY_UNIDENTIFIED;
        if (index < 0) {
            if (cp >= 'a' && cp <= 'z') key = GHOSTTY_KEY_A + cp - 'a';
            else if (cp >= 'A' && cp <= 'Z') key = GHOSTTY_KEY_A + cp - 'A';
            else if (cp >= '0' && cp <= '9') key = GHOSTTY_KEY_DIGIT_0 + cp - '0';
            else switch (cp) {
                case ' ': key = GHOSTTY_KEY_SPACE; break; case '[': key = GHOSTTY_KEY_BRACKET_LEFT; break;
                case ']': key = GHOSTTY_KEY_BRACKET_RIGHT; break; case '\\': key = GHOSTTY_KEY_BACKSLASH; break;
                case '/': key = GHOSTTY_KEY_SLASH; break; case '-': key = GHOSTTY_KEY_MINUS; break;
                case '=': key = GHOSTTY_KEY_EQUAL; break; case '`': key = GHOSTTY_KEY_BACKQUOTE; break;
                case ',': key = GHOSTTY_KEY_COMMA; break; case '.': key = GHOSTTY_KEY_PERIOD; break;
                case ';': key = GHOSTTY_KEY_SEMICOLON; break; case 39: key = GHOSTTY_KEY_QUOTE; break;
            }
        }
        ghostty_key_encoder_setopt_from_terminal(encoder, l->terminal);
        ghostty_key_event_set_action(event, GHOSTTY_KEY_ACTION_PRESS);
        ghostty_key_event_set_key(event, key); ghostty_key_event_set_mods(event, (GhosttyMods)mods);
        if (len) ghostty_key_event_set_utf8(event, (const char *)utf8, len);
        ghostty_key_event_set_unshifted_codepoint(event, cp);
        r = ghostty_key_encoder_encode(encoder, event, (char *)out, cap, n);
    }
    if (event) ghostty_key_event_free(event);
    if (encoder) ghostty_key_encoder_free(encoder);
    return r;
}
static int mouse_common(ShelloTerminal *l, int action, int button, float x, float y, int cw, int ch, uint8_t *out, size_t cap, size_t *n) {
    if (!l || !n) return SHELLO_ERR_ARG;
    GhosttyMouseEncoder encoder = NULL; GhosttyMouseEvent event = NULL;
    GhosttyResult r = ghostty_mouse_encoder_new(NULL, &encoder);
    if (r == GHOSTTY_SUCCESS) r = ghostty_mouse_event_new(NULL, &event);
    if (r == GHOSTTY_SUCCESS) {
        uint16_t cols = 0, rows = 0;
        ghostty_terminal_get(l->terminal, GHOSTTY_TERMINAL_DATA_COLS, &cols); ghostty_terminal_get(l->terminal, GHOSTTY_TERMINAL_DATA_ROWS, &rows);
        GhosttyMouseEncoderSize size = GHOSTTY_INIT_SIZED(GhosttyMouseEncoderSize);
        size.cell_width = cw; size.cell_height = ch; size.screen_width = cols * cw; size.screen_height = rows * ch;
        ghostty_mouse_encoder_setopt_from_terminal(encoder, l->terminal);
        ghostty_mouse_encoder_setopt(encoder, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &size);
        ghostty_mouse_event_set_action(event, action == 0 ? GHOSTTY_MOUSE_ACTION_PRESS : action == 1 ? GHOSTTY_MOUSE_ACTION_RELEASE : GHOSTTY_MOUSE_ACTION_MOTION);
        ghostty_mouse_event_set_button(event, button);
        ghostty_mouse_event_set_position(event, (GhosttyMousePosition){.x = x, .y = y});
        r = ghostty_mouse_encoder_encode(encoder, event, (char *)out, cap, n);
    }
    if (event) ghostty_mouse_event_free(event);
    if (encoder) ghostty_mouse_encoder_free(encoder);
    return r;
}
int shello_terminal_mouse(ShelloTerminal *l, int action, bool right, float x, float y, int cw, int ch, uint8_t *out, size_t cap, size_t *n) {
    return mouse_common(l, action, right ? GHOSTTY_MOUSE_BUTTON_RIGHT : GHOSTTY_MOUSE_BUTTON_LEFT, x, y, cw, ch, out, cap, n);
}
int shello_terminal_wheel(ShelloTerminal *l, bool down, float x, float y, int cw, int ch, uint8_t *out, size_t cap, size_t *n) {
    return mouse_common(l, 0, down ? GHOSTTY_MOUSE_BUTTON_FIVE : GHOSTTY_MOUSE_BUTTON_FOUR, x, y, cw, ch, out, cap, n);
}
int shello_terminal_selection(ShelloTerminal *l, int start, int end, uint8_t **out, size_t *n) {
    if (!l || !out || !n) return SHELLO_ERR_ARG;
    uint16_t cols = 0; ghostty_terminal_get(l->terminal, GHOSTTY_TERMINAL_DATA_COLS, &cols);
    if (!cols || start < 0 || end < 0) return SHELLO_ERR_ARG;
    GhosttySelection selection = GHOSTTY_INIT_SIZED(GhosttySelection);
    selection.start = (GhosttyGridRef)GHOSTTY_INIT_SIZED(GhosttyGridRef);
    selection.end = (GhosttyGridRef)GHOSTTY_INIT_SIZED(GhosttyGridRef);
    GhosttyPoint a = {.tag = GHOSTTY_POINT_TAG_SCREEN, .value.coordinate = {.x = start % cols, .y = start / cols}};
    GhosttyPoint b = {.tag = GHOSTTY_POINT_TAG_SCREEN, .value.coordinate = {.x = end % cols, .y = end / cols}};
    GhosttyResult r = ghostty_terminal_grid_ref(l->terminal, a, &selection.start);
    if (r == GHOSTTY_SUCCESS) r = ghostty_terminal_grid_ref(l->terminal, b, &selection.end);
    if (r != GHOSTTY_SUCCESS) return r;
    GhosttyFormatterTerminalOptions options = GHOSTTY_INIT_SIZED(GhosttyFormatterTerminalOptions);
    options.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN; options.unwrap = true; options.trim = true; options.selection = &selection;
    GhosttyFormatter formatter = NULL; uint8_t *data = NULL; size_t len = 0;
    r = ghostty_formatter_terminal_new(NULL, &formatter, l->terminal, options);
    if (r == GHOSTTY_SUCCESS) r = ghostty_formatter_format_alloc(formatter, NULL, &data, &len);
    if (formatter) ghostty_formatter_free(formatter);
    if (r != GHOSTTY_SUCCESS) return r;
    uint8_t *copy = malloc(len ? len : 1); if (!copy) { ghostty_free(NULL, data, len); return SHELLO_ERR_MEM; }
    memcpy(copy, data, len); ghostty_free(NULL, data, len); *out = copy; *n = len; return 0;
}
size_t shello_grapheme_ends(const uint32_t *cps, size_t count, int32_t *ends) {
    size_t groups = 0;
    for (size_t i = 0; i < count;) {
        size_t step = ghostty_unicode_grapheme_width(cps + i, count - i, NULL);
        if (!step) step = 1;
        i += step; ends[groups++] = (int32_t)i;
    }
    return groups;
}
void shello_free(void *p) { free(p); }
