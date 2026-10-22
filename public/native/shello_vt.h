#ifndef SHELLO_VT_H
#define SHELLO_VT_H
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
#ifdef __cplusplus
extern "C" {
#endif

/* Platform-neutral wrapper over libghostty-vt. Return 0 on success, a GhosttyResult otherwise.
 * One terminal must be used from one thread. */
typedef struct ShelloTerminal ShelloTerminal;

enum { SHELLO_OK = 0, SHELLO_ERR_ARG = -1, SHELLO_ERR_MEM = -2 };

/* Modifier bits and key indices match the Android TerminalSession API. */
enum { SHELLO_MOD_SHIFT = 1, SHELLO_MOD_CTRL = 2, SHELLO_MOD_ALT = 4, SHELLO_MOD_SUPER = 8 };
/* index -1 selects a character key by code point. */
enum { SHELLO_KEY_COUNT = 26 };

ShelloTerminal *shello_terminal_create(int cols, int rows, int *status);
void shello_terminal_free(ShelloTerminal *t);
int shello_terminal_write(ShelloTerminal *t, const uint8_t *data, size_t len);
/* Copies pending VT replies; returns SHELLO_ERR_ARG when cap is too small, or a
 * nonzero status when replies overflowed the 64 KiB bound (replies are dropped). */
int shello_terminal_take_replies(ShelloTerminal *t, uint8_t *out, size_t cap, size_t *len);
int shello_terminal_resize(ShelloTerminal *t, int cols, int rows);
int shello_terminal_scroll(ShelloTerminal *t, int delta);

/* Render stream: little-endian int32 words. Header of 14 words:
 * cols, rows, cursorX, cursorY, altScreen, mouseTracking, scrollOffset, scrollTotal,
 * cursorVisible, cursorBlink, cursorShape, cursorColor, defaultBg, reserved.
 * Then per cell: fg, bg (0xRRGGBB), wide, flags, underline, underlineColor, n, n codepoints.
 * flags: 1 bold,2 italic,4 faint,8 invisible,16 strike,32 overline,64 blink.
 * Free with shello_free. */
int shello_terminal_render(ShelloTerminal *t, int32_t **words, size_t *count);
/* modes: DEC private modes 1, 1000, 1006, 2004 as a bit mask (bit i = i-th mode). */
int shello_terminal_modes(ShelloTerminal *t, uint32_t *mask);

int shello_terminal_snapshot(ShelloTerminal *t, uint8_t **data, size_t *len);
ShelloTerminal *shello_terminal_restore(const uint8_t *data, size_t len, int *status);

int shello_terminal_paste(ShelloTerminal *t, const uint8_t *text, size_t len, uint8_t **out, size_t *out_len);
int shello_terminal_key(ShelloTerminal *t, int index, int mods, const uint8_t *utf8, size_t len, uint32_t cp, uint8_t *out, size_t cap, size_t *n);
/* action: 0 press, 1 release, 2 motion. */
int shello_terminal_mouse(ShelloTerminal *t, int action, bool right, float x, float y, int cell_w, int cell_h, uint8_t *out, size_t cap, size_t *n);
int shello_terminal_wheel(ShelloTerminal *t, bool down, float x, float y, int cell_w, int cell_h, uint8_t *out, size_t cap, size_t *n);
/* Inclusive row-major indices across the active screen. */
int shello_terminal_selection(ShelloTerminal *t, int start, int end, uint8_t **out, size_t *n);

/* Grapheme boundaries over UTF-32 code points: writes the end index (exclusive) of each cluster. Returns cluster count. */
size_t shello_grapheme_ends(const uint32_t *cps, size_t count, int32_t *ends);
void shello_free(void *p);

#ifdef __cplusplus
}
#endif
#endif
