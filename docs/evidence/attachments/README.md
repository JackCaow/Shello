# Composer menu and SSH attachment acceptance

2026-10-21. This feature belongs to the sample app; the transport-independent terminal AAR is unchanged.

- [Physical Xiaomi / API 36](phone-api36-tests.txt): all eight AttachmentTest checks passed. Three opt-in checks used the existing pinned SSH profile to the local Mac over Tailscale: streamed document upload with matching remote SHA-256 and mode 0600, rejection after removing every matching host pin, and a file-picker result surviving Activity recreation with the path inserted exactly once at the saved draft cursor. These tests do not submit terminal input; disposable upload files and temporary untrusted profiles are removed.
- [API 27](api27-tests.txt) and [API 35](api35-tests.txt): five local checks each passed; the three saved-host-dependent checks were skipped. Local coverage includes keyboard-open/closed/reflow positioning, landscape edge clamping, Unicode/path-safe names, unknown-length stream bounds, invalid sources, declared oversize and cancellation without publishing a path.
- The automated picker test intercepts ACTION_OPEN_DOCUMENT with a synthetic content URI. Separately, the user selected and explicitly sent an image through the actual physical picker; its remote file path appeared in Claude Code. This was confirmed as manual sending, not an automatic upload action. The user's image is retained. No private image or QR content is included in this evidence.
- Replacement installation and final acceptance preserved all fifteen original preference/encrypted-profile files byte-for-byte. [Sanitized comparison](data-preservation.json). Debug, test APK, R8 release APK and debug lint builds passed. The final debug APK is installed on the phone. No tag, GitHub release or Maven publication.

The test-only document provider lives in the instrumentation APK. Debug builds declare its visibility; release builds contain neither the provider nor that query. Xiaomi autostart restrictions required the runner to start this synthetic provider through a shell query before the tests, without changing device autostart settings. The existing debug restriction-probe receiver remains intact.

Private screenshots stay under build/attachments/. Initial runs found and fixed lost draft selection during recreation and delayed IME focus after popup dismissal. A host-key test initially changed only one of several trusted keys; its corrected fixture removes all matching pins. Landscape assertions allow the bubble to move inward at the window edge rather than requiring it to extend offscreen.

## Reference interaction observed on Moshi Android 4.1.0

A fresh pairing to the current LAN Mac succeeded after an earlier cloud status request returned HTTP 500. The reference app was previously in Zen mode; standard toolbar mode was temporarily selected for inspection, then Zen mode was restored and its selected state verified.

The actual Add attachment panel is a dimmed bottom sheet with a drag handle, a title and three spaced rounded rows: Camera (capture a new photo), Photos or videos (select media), and File (upload to the host). An attached video appears as an approximately 64 dp square preview above the composer text, with a play affordance and a separate remove button. The send button is enabled with the attachment alone; the path is not displayed as editable composer text. This confirms an interaction gap in this first upload implementation: source selection and staged preview cards have not yet been implemented in Shello. These observations do not claim parity or prove every reference source's end-to-end transport.

Reference documentation: https://getmoshi.app/docs/image-paste and https://getmoshi.app/docs/files . The documents also describe iOS behavior; the three source rows and staged video preview above were observed separately on the Android phone. Reference screenshots remain private under build/attachments/.
