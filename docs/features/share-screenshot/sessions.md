# Share Screenshot Sessions

## 2026-06-15 - Sharesheet preview URI compatibility

- User reported that after sharing a Thing as a long screenshot, the system
  share dialog's image preview looked blank on OPPO ColorOS.
- Checked Android's official Sharesheet and FileProvider documentation. The
  app already shared a `content://` URI through `Intent.EXTRA_STREAM` with
  `image/jpeg` and `FLAG_GRANT_READ_URI_PERMISSION`, but the target intent did
  not explicitly set `ClipData`, `data`, `EXTRA_TITLE`, or read permission on
  the chooser intent itself.
- Hypothesis: the receiving share sheet can send the screenshot correctly, but
  the system preview path is less compatible than target apps because it has to
  open and decode the image before the final share target is chosen. ColorOS may
  be stricter about `ClipData`/chooser grants, or it may fail while decoding a
  very tall JPEG preview.
- Changed `ScreenshotHelper.ShareCallback` to attach the generated screenshot
  URI as `EXTRA_STREAM`, `ClipData`, and intent `data`, keep the concrete
  `image/jpeg` MIME type, set `EXTRA_TITLE` when available, and add
  `FLAG_GRANT_READ_URI_PERMISSION` to both the send intent and chooser intent.
- If ColorOS still shows a blank preview after this compatibility pass, the
  next mitigation is to generate a separate small preview thumbnail URI for the
  sharesheet while continuing to share the full long screenshot through
  `EXTRA_STREAM`.
- Verification: `git diff --check` passed with the repository's existing
  LF/CRLF warnings, and
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606150347`. The remote `latest.json`
  was re-read as a JSON object with `debugUpdateCode=202606150347`.
