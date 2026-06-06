# Popup Picker And Insets Sessions

Migrated from global `memory/sessions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-05-27 - Rounded App Chrome dialogs and popup pickers

Updated EverythingDone's custom App Chrome dialog and popup surfaces to render
as rounded rectangles using a dedicated
`@dimen/app_chrome_dialog_popup_corner_radius` token. The token is currently
set to `16dp` for visual review, while home Thing cards keep
`@dimen/thing_card_corner_radius` at `10dp`.

Changes:
- Added `bg_app_chrome_surface_elevated_rounded.xml`, backed by
  `app_chrome_surface_elevated` and
  `app_chrome_dialog_popup_corner_radius`.
- `BaseDialogFragment` now installs that rounded window background and clips
  dialog content to the same rounded outline, covering all custom
  DialogFragment subclasses.
- `PopupPicker` now uses the same rounded elevated surface for picker
  PopupWindows and clips picker content to the rounded outline.
- `NoticeableNotificationActivity`, a dialog-like hybrid chrome surface, now
  uses the same rounded shell background and clipping.
- Kept the legacy `bg_picker` night resource aligned to the dialog/popup-radius
  token for any remaining resource-level references.

Verification:
- `E:\projects\EverythingDone\gradlew.bat :app:assembleDebug --console=plain`
  passed outside the sandbox after sandboxed attempts timed out.
- Fresh APK produced at `app\build\outputs\apk\debug\app-debug.apk`.
- `git diff --check` passed with CRLF conversion warnings only.
- No visual device smoke test was run; `adb devices` showed physical devices
  only and no `emulator-5554`.

## 2026-05-18 — Phase 8 cont'd: dialog downstream gradient propagation

User reported `DateTimeDialogFragment` (and other `mAccentColor`-driven
dialogs: AudioRecord, HabitDetail, HabitRecord) had not adopted the
thing gradient — the top-level dialog chrome was migrated in Phase 8
round 1 but the downstream views (InputLayout, TimeOfDayRecAdapter,
RecurrencePickerAdapter, DateTimePicker callers) still consumed
`mAccentColor` int only.

Done:
- `InputLayout` / `TimeOfDayRecAdapter` / `RecurrencePickerAdapter`
  each gained `setAccentBackground(ThingBackground)`.
- `DateTimeDialogFragment` propagates `mAccentBackground` to all 8
  InputLayout instances, both DateTimePickers, the TimeOfDayRecAdapter
  and the three RecurrencePickerAdapters; `mEtTimeAfter` focus
  listener installs the gradient shader on focus.
- `RecurrencePickerAdapter` NORMAL holder rewritten as fake-FAB
  (`recurrence_picker_normal.xml` + adapter rewrite) so picked cells
  render real gradients.
- `AudioRecordDialogFragment` `mBase` voice-visualizer backplate uses
  `applyBackground`; visualizer waveform + EditText tints stay int.
- `HabitDetailDialogFragment` / `HabitRecordDialogFragment` title and
  button text use `applyTextBackground`.

Commit `a984b91`. 8 files, +305/-40.

Earlier in same session: `Add Claude Code project config and agent skills`
(`9f91693`) — set up CLAUDE.md, .claude/ statusline, Matt Pocock skills
docs, and switched local checkout from `migration/android-16` to `master`
post PR-merge.

## 2026-05-26 - Dialog width and DateTime recurrence colour correction

Fixed the follow-up regressions after searching Android/AppCompat dialog sizing
behaviour:
- Replaced the ineffective generic `WRAP_CONTENT` dialog-window reset with a
  BaseDialogFragment width hook. `ThingDoingDialogFragment` now sets an exact
  `280dp` window width, and `DateTimeDialogFragment` sets an exact `320dp`
  window width, matching the pre-android-16 layout baselines.
- Kept dialog theme min-width overrides for both platform and AppCompat attrs
  (`android:windowMinWidthMajor/Minor` and `windowMinWidthMajor/Minor`) so
  future AppCompat/Material dialog contexts do not reintroduce the wide default.
- Aligned the DateTimeDialog recurrence-tab "new reminder time" text and icon
  with the same `app_chrome_on_surface_secondary` tint used by the first
  reminder-time icons.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.
- No device visual smoke test was run in this step.
