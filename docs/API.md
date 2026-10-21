# API and ownership contract

## Session

`TerminalSession` is `AutoCloseable` and confined to its creating thread. A view requires a session created on the Android main thread. There is no finalizer: detach and explicitly close. Closing twice is safe; operations after close or from another thread throw `IllegalStateException`. A session can bind to one view at a time; closing an attached session is rejected before freeing native state.

`write(byte[])` accepts arbitrary ordered chunks, including partial UTF-8 or escape sequences. It synchronously returns query replies to send back to the PTY. Replies are bounded to 64 KiB per write; overflow throws instead of returning truncated protocol data. Keep transport chunks bounded and handle failures at the integration layer.

`resize(columns, rows)` changes the parser grid; the view derives its grid from actual pixels. `TerminalView.Listener.onResize` forwards view-driven changes to the PTY. Applications that resize a headless session directly must also resize their PTY. `encodeKey(Key)` honors application cursor mode.

`select(start, end)` uses inclusive zero-based row-major indices across the active screen/history. Invalid/expired native coordinates fail. The view normalizes touches to whole graphemes; a direct caller of `select` is responsible for boundaries.

`checkpoint()` produces an opaque immutable RAM checkpoint. `restore(checkpoint)` creates a separately owned session. The original must still be detached/closed. This preserves parser state only: it does not recreate a PTY or confirm which output/input was delivered. Fence output and sequence replay at the transport layer. There is intentionally no public byte-array snapshot importer in this alpha.

## View and input

`setSession(null)` detaches without freeing. Activity destruction must detach then close, or explicitly transfer ownership to an appropriate application lifecycle component. In SSH mode the sample foreground service owns the main-thread VT and transport; Activities attach/detach their view and observer without closing that shared session. Output and terminal replies continue while no view is bound. The service detaches any remaining view before closing its VT. This ownership belongs to the sample, not the library. tmux separately retains remote processes across actual SSH loss; that recovery is independent of `checkpoint()`. After process death the sample can start a new service on normal Activity open, validate its persisted profile fingerprint and reattach tmux. The new VT is redrawn by tmux; local selection, full scrollback and parser checkpoints are not persisted to disk.

`setListener` supplies synchronous input and resize callbacks. A missing listener means read-only, not queued input. `sendText` emits literal UTF-8, not automatic bracketed paste. The optional `TerminalInputView` sends committed IME text and translates deletion to terminal key input. It does not interpret remote shell commands or implement a full editor. In draft/paste-sensitive workflows, use your own input UI.

The view clears selection on output and resize, keeps selection while scrolling during a handle drag, and exposes `getSelectedText()`. The optional `Listener.onSelectionChanged(String)` callback runs synchronously on the UI thread when selection text changes, including an empty string on clear; use it to show or dismiss copy controls. The application owns clipboard access and copy menus.

New sessions and default cursor resets (`CSI 0 SP q`, omitted-parameter DECSCUSR, and RIS) use a blinking block cursor. Explicit remote steady styles or DEC mode 12 reset still disable blinking; checkpoints retain both the current style and the default policy. The cursor follows VT visibility, color and shape (block/bar/underline/hollow); block cursors are opaque and redraw the underlying glyph with contrast. A wide cell occupies both columns; legacy emoji protocol columns remain unchanged. Input, cursor movement, shape/visibility changes and window focus restore the visible phase for 750 ms, followed by 500 ms off/on intervals when the VT requests blinking. Title-only updates do not restart blinking. An inactive window shows a steady outline; hidden, detached or session-less views do not schedule blink redraws. Focus here means window focus, so a sibling IME input view does not incorrectly deactivate the terminal cursor. Alternate-screen swipe is translated into cursor keys; terminal mouse reporting is not implemented.

Rendering and parsing are on the UI thread in this alpha. The renderer batches updates on animation frames, but has no dirty-row atlas or GPU renderer. It uses the system monospace/emoji fonts. Do not infer production performance from a prior prototype benchmark.

On Android 15+ edge-to-edge windows, apply IME insets to the host layout (as in the sample). `adjustResize` alone is insufficient for an edge-to-edge content view; the terminal resizes when its actual view bounds change.

`TerminalView.setFontSize(float sp)` updates cell metrics and notifies the resize listener when the grid changes (8–32 sp). `setSelectionEnabled(boolean)` controls long-press text selection. Both must be called on the UI thread. Existing IME/transport ownership stays with the caller.
