# Debug Update Channel Sessions

Migrated from global `memory/sessions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-06-15 - Publish only current debug notes in update metadata

- User reported that the in-app update check showed
  `Expected BEGIN_OBJECT but was STRING at line 1 column 1 path`, suggesting a
  problem with the published update JSON.
- Re-read the remote `latest.json` and confirmed the current server response
  had status 200, `Content-Type: application/json`, and started with `{`.
  `BuildConfig.DEBUG_UPDATE_METADATA_URL` also pointed at the expected debug
  metadata URL.
- Found that `:app:publishDebugUpdate` embedded the entire long-term
  `memory/debug-update-notes.md` history into `releaseNotes` whenever
  `-PdebugUpdateNotesFile` was used. The metadata was syntactically an object,
  but it had grown to roughly 90KB and included many stale historical entries.
- Changed `app/build.gradle` so file and inline notes are normalized through
  `currentDebugUpdateNotes(...)`; when the text contains multiple `##` entries,
  only the first/top entry is published into `latest.json`.
- Added a local generated-metadata parse step with `JsonSlurper` so the Gradle
  task fails before upload if the generated metadata is not a JSON object.
- Updated `.agents/rules/gradle.md` and this feature's preferences to document
  that the notes file may keep history, but only the top current entry is
  embedded in app-visible update metadata.

Verification:
- `git diff --check` passed with the repository's existing LF/CRLF warnings.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606150245` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- Re-read both local and remote `latest.json`: both were 3313 characters,
  started with `{` (ASCII 123), parsed as JSON objects, had
  `debugUpdateCode=202606150245`, and had `releaseNotesLength=1247`.

## 2026-05-27 - DetailActivity catches widget updates after Home

Fixed the remote-widget path where a user could open a Thing in
`DetailActivity`, press Home, change that same Thing from a desktop widget, and
return through Recents to a stale Detail screen.

Changes:
- `App` now tracks Detail screens that are actually foreground-visible, not
  only alive in the task.
- Reminder/goal and habit widget finish actions are blocked only when the
  matching Detail screen is visible. A stopped Detail left by pressing Home no
  longer prevents the receiver from writing the database.
- `DetailActivity` records a rendered Thing snapshot, marks matching remote UI
  broadcasts while stopped, and on resume compares the snapshot with the latest
  Thing from the manager/DAO. If the Thing changed externally, Detail disables
  its pause-time autosave for that recreation and calls `recreate()` rather
  than treating `initUI()` as a standalone refresh API.
- Added a short bounded retry for the async state-update path so returning very
  quickly after a widget finish does not miss the database write.

Verification:
- `git diff --check` passed with CRLF warnings only.
- `.\gradlew.bat :app:assembleDebug --console=plain` passed twice after the
  implementation and retry correction.
- The debug APK was produced at
  `app/build/outputs/apk/debug/app-debug.apk` at `2026-05-27 20:01:14`.
- No device Recents/widget smoke test was run from the agent session.

## 2026-06-04 - Wide hidden-media count row spacing follow-up

- User reported that the hidden image/video count row still had extra bottom
  space, apparently only for wide Thing Cards, and that the `fitCenter` icon
  looked undersized and vertically off relative to the text.
- Confirmed `thing_card_full_span_sparse_min_height` is 120dp and
  `updateFullSpanSparseMinHeight()` had allowed `llInlineMediaAttachment` to
  trigger that minimum height, producing the apparent bottom margin in wide
  hidden-media cards.
- Changed full-span sparse minimum-height handling to skip while the hidden
  media inline count row is visible. Title/text/audio-only wide cards keep the
  existing sparse minimum-height behavior.
- Kept the shared fixed icon view dimensions for text-start alignment, but
  changed the count icon scale type back to `centerCrop` so the image/video icon
  fills the shared bounds instead of looking smaller under `fitCenter`.

Verification:
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings before publish.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606041510` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-05 - Image/video count icon nudge and overlay size unification

- User reported that image/video count icons still looked visually misaligned
  from audio count icons and asked to try 1dp left/top padding while keeping
  `fitCenter`, nudging the fitted drawable slightly right/down.
- User also reported that media-background overlay image/video count icons
  were larger than other image/video count rows.
- Added a media-count-icon helper that reuses the fixed count-icon dimensions
  and applies `1dp` left/top padding only to image/video count icons.
- Applied the same helper to both hidden-media inline count icons and
  media-background overlay count icons, so overlay no longer uses PNG intrinsic
  size.
- Audio count icons keep the shared dimensions but do not receive the media
  icon nudge.

Verification:
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings before publish.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606050013` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- No Git commit was created.

## 2026-06-05 - Image/video count icon width and text-start alignment

- User decided that the image/video count icon should get 2dp more horizontal
  view width, while its right-side text margin should shrink by 2dp so the
  count text still aligns with the audio count text.
- Kept audio count rows unchanged: `14x14dp` normal icons with an `8dp` text
  start margin, and `16x16dp` large icons with a `12dp` text start margin.
- Added media-specific count-row dimensions: image/video count icons now use
  `16x14dp` normal and `18x16dp` large view bounds, with `6dp` and `10dp` text
  start margins respectively.
- Applied the same media-specific dimensions and margins to hidden-media inline
  count rows and media-background overlay count rows.
- Preserved the image/video icon's `fitCenter` behavior and 1dp left/top
  padding nudge so the wider PNG is not clipped.

Verification:
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings before publish.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606050023` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
- Committed the hidden-media and count-icon follow-up changes immediately
  after publish.
