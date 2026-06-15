# Share Screenshot Sessions

## 2026-06-15 - Re-share screenshot into EverythingDone

- User reported that after sharing a Thing as a long screenshot and choosing
  EverythingDone's create-Thing target from the system share dialog,
  EverythingDone could not read the generated long screenshot.
- Diagnosis narrowed the issue to the receiving side rather than screenshot
  generation. `ScreenshotHelper.ShareCallback` now exposes the screenshot as a
  FileProvider `content://` URI, but `DetailActivity.setupThingFromIntent()`
  only tried `UriPathConverter.getLocalPathName(...)` before adding the shared
  media as an attachment.
- `UriPathConverter` depends on filesystem paths or `_data` columns. A
  FileProvider URI is stream-oriented and does not expose `_data`, so the old
  receive path could drop the attachment even though the screenshot file and
  URI grant were valid.
- Changed `DetailActivity` to read incoming share URIs from `EXTRA_STREAM`,
  `Intent.data`, or `ClipData`, deduplicate multiple sources, and convert each
  URI through a helper shared by single and multiple media shares.
- The helper now keeps the old direct-local-path path when available. When the
  path cannot be resolved, it infers a media postfix from
  `ContentResolver.getType(uri)` or the share intent MIME type, copies the
  shared URI stream into the app's temp media file area with
  `FileUtil.copyUriToFile(...)`, and then creates the existing type/path/name
  attachment token.
- Verification: `git diff --check` passed with the repository's existing
  LF/CRLF warning for `DetailActivity.kt`, and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed before publishing. `.\gradlew.bat :app:publishDebugUpdate
  "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain
  --no-configuration-cache` then passed and published debug update
  `202606150622`; both the local and remote `latest.json` parsed as JSON
  objects with `debugUpdateCode=202606150622`. No device-side chooser loop was
  available in this agent environment, so the app-published build should be
  used to verify the exact OEM/system share dialog path.

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
