# Shello UI rules

The app UI uses `UiDesign`, `SampleUi`, and `ScreenActivity`. New screens and changes to existing screens must use these roles instead of introducing per-screen font sizes or shrinking text to make a layout fit. Terminal cell fonts and terminal program colors keep their own rendering rules.

## Type and hierarchy

| Role | Size / line height (sp) | Use |
| --- | --- | --- |
| Title | 20 / 28, medium | Activity and sheet title |
| Section | 13 / 20, medium | Section labels, including home |
| Body | 16 / 24 | Row titles, form inputs, primary actions |
| Secondary | 14 / 20 | Explanations, subtitles, form labels |
| Caption | 12 / 16 | Time, counts, supplementary metadata |
| Control | 12 / 18, monospace | Terminal key labels |
| Code | 14 / 20, monospace | Commands and selectable code |

Use one title per page; section labels introduce groups, body text identifies actions, and secondary text explains them. Avoid putting prose into a caption role. A key's visible label may ellipsize, but its full label must remain available to accessibility. Ordinary descriptions wrap. Do not use automatic font shrinking or horizontal text scaling.

## Spacing and geometry

- Use a 4dp spacing grid: 4 between title and subtitle, 8 within groups, 16 for page/card horizontal padding, 24 between sections. A short landscape window may reduce section spacing to 16.
- Use 12dp vertical padding for text rows. Rows have a 56dp minimum, and grow with their text. Form fields have a 56dp minimum; never fix their height to the default-font content height.
- Navigation is at least 56dp high and stays outside scrolling content. Readable single-column content is centered and capped at 640dp; the pairing page may use two columns.
- All independently actionable controls need at least a 48 × 48dp target. A small icon can sit inside that target. Adjacent peer targets must not overlap; a nested corner remove action consumes its own hit area before its clickable preview parent. Disabled previews are not actionable controls.
- Terminal toolbar: 64dp total height, 8dp vertical padding, 48dp button targets. The trailing input/keyboard pair stays fixed; other controls scroll. Shortcut rows use 48dp targets and horizontal overflow. A short Zen menu becomes a scrollable grid instead of shrinking targets. In a landscape IME window shorter than 240dp, the terminal session header hides until the window expands, keeping the terminal/pad and full-size input controls visible.
- Composer: utility actions scroll together; dictation and Send remain fixed on the right. Use 48dp targets with smaller 36dp visual key backgrounds and a separated trailing group. Send uses a 36dp circle inside a 48dp target. Attachment previews use 56dp artwork in a 72dp tile with a corner remove button; no filename or size appears in the tray. Details remain in the preview and accessibility description. Files without thumbnails have category-specific line artwork and tints (Office documents, PDF, audio/video, archives, code, text, images and unknown files); known suffixes take precedence over generic provider MIME types. The nested remove action owns its corner hit area. Draft text and attachments scroll inside a height-limited body on short windows, keeping the trailing actions above the IME.
- Cards use 16dp corners, fields 18dp, sheets 24–28dp, pill controls half their visible height. Hairlines, a sheet grip, and icon artwork are exempt from the spacing grid and target size; their actionable wrappers are not.
- Sheet content scrolls within system bars and IME bounds. Primary actions remain reachable by scrolling. Empty states grow with text and must not pretend to be active sessions.

## Color and accessibility

Use `UiTheme.of(context)` for the Activity palette and `SampleUi` for shared components. The existing **Settings → Theme → Light** choice applies to the entire app and the terminal's default foreground/background. Shello and Black keep dark app chrome. Theme IDs and existing stored preferences remain compatible.

| Light palette role | Color |
| --- | --- |
| Canvas | `#F4F7F5` |
| Surface / sheet | `#FFFFFF` |
| Input / key | `#EDF2EF` / `#E7EEE9` |
| Primary text / secondary text | `#18241E` / `#5C6860` |
| Accent / on accent | `#087442` / `#FFFFFF` |
| Separator | `#D4DED7` |

The palette is immutable for each Activity. Changing between light and dark recreates visible pages and their parents on return, retaining the normal return stack and scroll state. SSH ownership remains in its service; local draft state is saved with the existing tab/recreation mechanism. Native controls, text selection, status/navigation bars and dialogs use the matching Android theme.

Shared text, artwork, fields, switches, menus, empty states, provider meters, attachment chips and terminal controls resolve their existing swatches through `UiTheme`. New components should use named roles. Camera pixels, attachment thumbnails, saved terminal previews and colors explicitly emitted by terminal programs are content, and must never be passed through the UI palette. Keep the camera scan mask and scan lines visible against live camera imagery in either mode.

Muted text still needs to be legible against its actual surface. Normal text must meet 4.5:1 contrast; do not reduce its opacity to establish hierarchy. Use size, weight, spacing and placement first. Color alone must not identify an action or status. Preserve native text editing, font scaling, content descriptions and selection semantics.

Android's [accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/apps) recommends 48dp touch targets and documents text contrast requirements.

## Review and acceptance

The project review matrix is font scales **1.0, 1.3 and 2.0**, narrow portrait, phone landscape with IME, and tablet landscape. This is our regression matrix, not a claim that one screenshot proves all devices.

For each changed screen inspect actual renders and measured views:

1. Titles and left edges align; section/body/metadata hierarchy is consistent.
2. Chinese, English and long labels have adequate line height and padding. No unintended clipping, squeezed text, overlapping controls or tiny fallback text.
3. Targets meet 48dp and stay reachable. Fixed navigation and pinned terminal actions remain stationary while content scrolls.
4. Forms, sheets and keyboard layouts survive rotation, enlarged fonts and Activity recreation.
5. Existing preferences, saved connections and terminal sessions are preserved.

`UiThemeTest` checks palette contrast and light/dark Activity recreation while retaining an isolated service-owned terminal and Chinese/emoji draft. Pass `-e uiTheme light` or `-e uiTheme dark` to `UiDesignAuditTest`, `InputRuntimeTest`, or `AdaptiveLayoutTest` to override and restore the original preference for a run. `UiDesignAuditTest` checks measured text overflow and actionable target sizes on settings, connection/pairing/editor forms, attachments and empty states. `InputRuntimeTest` checks the actual terminal toolbar, gestures, pinned actions, key dispatch and Zen targets with an isolated terminal. `AdaptiveLayoutTest` covers rotation, bounded sheets and fixed navigation. Run the relevant tests and inspect screenshots; passing assertions alone is insufficient visual acceptance. Keep personal screenshots, pairing material and credentials out of version control.
