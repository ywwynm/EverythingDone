# Dark Mode Followups

Migrated from global `memory/followups.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Dialog and popup visual QA - Device visual review for rounded dialog and popup shells (deferred 2026-05-27)

**Scope:** Custom `BaseDialogFragment` dialogs, `PopupPicker` surfaces
(`ColorPicker`, `DateTimePicker`, quick-remind/time-type pickers), and
`NoticeableNotificationActivity`'s dialog-like shell after the rounded
App Chrome surface change.

**Current state:** Shared code paths now use
`bg_app_chrome_surface_elevated_rounded.xml` and clip to
`@dimen/app_chrome_dialog_popup_corner_radius`, currently `16dp`.
`:app:assembleDebug` and `git diff --check` pass.

**Deferred verification:** Install on an emulator or explicitly approved test
device and visually open representative dialogs/popups in both light and dark
Appearance Mode. Check corner clipping, ripple bounds, picker content, and
NoticeableNotificationActivity shell edges.

**Reason deferred:** `adb devices` showed physical devices only and no
`emulator-5554`, so the agent did not take over the user's device UI for
manual visual navigation.
