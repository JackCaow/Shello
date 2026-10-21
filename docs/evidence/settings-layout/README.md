# Settings layout refinement — 2026-10-21

Compared the installed Moshi settings pages on a 1080 × 2400 Android phone
(density 450, font scale 1.0) with the sample's settings UI.

## Changes

- Grouped surfaces: 16 dp page margins, 12 dp corner radius, 1 dp dividers,
  48 dp minimum rows, 16 sp titles and 14 sp descriptions.
- Settings header: 20 sp heading, 56 dp toolbar, back arrow and consistent
  left alignment. Sections use 13 sp captions and 24 dp inter-group spacing.
- Font size: compact 10–24 sp stepper, bounds, immediate text preview and
  persistence across Activity recreation. System monospace remains the supported font.
- Theme: selected checkmark and an immediate color preview.
- Toolbar: live button visibility preview, grouped switches and smaller switch
  tracks. Preview key labels scale to fit even with large system fonts.
- Hook, notifications, advanced, draft, shortcuts and gestures: grouped actions,
  shorter inline descriptions, wrapping text and consistent dimensions.
- Home: shared 13 sp regular-weight section headings in #8e9298, no font padding,
  36 dp minimum header rows. Connection/discovery spacing is consistent; the
  discovery label uses “发现 Ghostty”. Hints and progress are visually secondary.

Only supported settings are shown. Moshi-specific cloud/token controls,
font downloads, chat rendering and additional gesture bindings are not added by
this visual refinement. No Maven package is published.

## Validation

- Debug and Release APK builds and release lint passed.
- API 27: 3 instrumentation tests passed, including font bounds/recreation,
  switch/theme persistence and text wrapping across 10 settings pages.
- API 35 with system font scale 1.5: the same 3 tests passed. This caught a
  clipping issue in the toolbar preview's Ctrl label; fixed before the final run.
- System font scale was restored after the large-font run.
- Phone: inspected the real settings/toolbar/notification/gesture pages. Phone
  instrumentation was interrupted by lock/focus/background-launch restrictions;
  those interrupted runs are not counted as passing tests.

Attached images come from the emulator. Moshi reference screenshots, phone
screenshots and device-specific data remain in ignored local build output.
