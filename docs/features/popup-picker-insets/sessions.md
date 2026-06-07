# Popup Picker And Insets Sessions

Migrated from global `memory/sessions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-06-07 - Search HUE_BUCKET spacing finalized before commit

User confirmed the previous `HUE_BUCKET` target, asked to soften it, then made
one final local visual adjustment before requesting a Git commit. The committed
spacing follows that final local state.

Change:
- `HUE_BUCKET` first-row top margin changed from 0dp to 8dp, still below the
  original 16dp.
- `HUE_BUCKET` final-row bottom margin changed from 20dp to 12dp after the
  user's final visual tuning.
- `HUE_BUCKET` fixed RecyclerView height remains 256dp.
- Updated feature preferences to record the finalized spacing.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  had previously passed for the pre-final-tuning `HUE_BUCKET` path and
  published debug update `202606070448` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`; no new
  debug update was published after the user's final local adjustment before the
  commit request.

## 2026-06-07 - Search ColorPicker spacing targets HUE_BUCKET

User reported that even changing `COLOR_HAVE_ALL` height to 400dp did not
change the search ColorPicker, and asked for a careful check of whether some
other path rewrites the popup height or margins.

Finding:
- `ThingsActivity` creates the search ColorPicker as
  `ColorPicker(this, window.decorView, Def.PickerType.HUE_BUCKET)`.
- `COLOR_HAVE_ALL` is not used by the current search UI, so changing its fixed
  height or row margins has no effect on the popup being tested.
- The search popup's "all colours" row is the `HUE_BUCKET` all-filter row, and
  the FAB grid contains eight hue-bucket FABs in four rows.

Change:
- Restored `COLOR_HAVE_ALL` height and margins to their baseline values.
- Updated `HUE_BUCKET` margins instead: first bucket row top margin is 0dp, and
  the final bucket row bottom margin is 20dp, slightly larger than the 16dp
  left-edge margin.
- Updated feature preferences to record that search ColorPicker spacing belongs
  to `HUE_BUCKET`.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606070439` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-07 - Search ColorPicker explicit margin correction

User reported that the search ColorPicker spacing still felt wrong and asked
for the direct correction: first-row FAB top margin should be 0dp, and final-row
FAB bottom margin should be larger.

Change:
- `COLOR_HAVE_ALL` first-row FAB top margins changed from 4dp to 0dp.
- `COLOR_HAVE_ALL` final-row FAB bottom margins changed from 16dp to 24dp.
- The search picker fixed RecyclerView height remains 312dp.
- Updated the feature preference to match the direct margin rule.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606070430` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-07 - Search ColorPicker bottom space uses fixed height

User tested the `202606070415` spacing polish and reported little visual
difference. The follow-up pointed out that FAB height/margins were not the main
problem: since the search ColorPicker RecyclerView has a fixed height, bottom
breathing room should come from increasing that fixed height while still
reducing the first FAB row's top margin.

Finding:
- `COLOR_HAVE_ALL` has one 48dp "all colours" row plus ten colour FABs in five
  two-column rows.
- The previous adjustment moved 4dp from first-row top margin to final-row
  bottom margin, keeping the content height effectively unchanged and leaving
  the fixed RecyclerView height at 304dp.

Change:
- `COLOR_HAVE_ALL` RecyclerView height changed from 304dp to 312dp.
- First FAB row top margin remains reduced from 8dp to 4dp.
- Final FAB row bottom margin is restored to the normal 16dp; the extra bottom
  space now comes from the RecyclerView viewport height instead.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606070426` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-07 - Search ColorPicker vertical spacing polish

User reported that in the search-mode ColorPicker, the gap between the "all
colours" row and the FAB grid felt slightly too large, while the gap between
the final FAB row and the popup bottom felt slightly too small.

Change:
- Added a feature preference for search ColorPicker spacing.
- For `Def.PickerType.COLOR_HAVE_ALL`, first-row FAB top margins changed from
  8dp to 4dp, and final-row FAB bottom margins changed from 16dp to 20dp.
- The total popup height remains unchanged because the 4dp removed above the
  grid is moved to the bottom breathing room.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606070415` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-07 - ColorPicker anchors to trigger top-right

User confirmed the latest source picker placement and asked for one final
ColorPicker adjustment: keep the current top-right popup surface animation
direction, but show from the anchor view's top-right corner instead of the
anchor centre.

Change:
- ColorPicker keeps `installContentSurfaceScaleTransition(1, 0)`, so the
  visible popup surface still expands from its top-right corner.
- The valid-anchor `showAsDropDown(...)` call now uses `xoff = 0`,
  `yoff = -anchor.height`, and `Gravity.END`, pinning the popup's top-right
  corner to the trigger view's top-right corner.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606070400` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-07 - Source picker right-bottom anchor uses top-gravity coordinates

User tested debug update `202606070343` and reported that
ThingCardAppearanceSourcePicker still did not appear from the source TextView's
right-bottom corner; it looked lower than the anchor.

Finding:
- The source picker had moved to content-surface transitions with
  `PopupWindow.isClippingEnabled = false`, but still used
  `Gravity.BOTTOM | Gravity.START` and subtracted `navBottom` from the bottom
  reference.
- With clipping disabled, bottom gravity can resolve against the full
  parent/window bottom. The extra `navBottom` subtraction therefore makes the
  popup bottom land below the desired anchor by about one system-bar inset.

Change:
- ThingCardAppearanceSourcePicker now positions with `Gravity.TOP |
  Gravity.START` and computes `x = anchorRight - popupWidth`,
  `y = anchorBottom - popupHeight`, so the popup right-bottom corner maps
  directly to the anchor TextView's right-bottom corner.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606070356` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-07 - Exact content-surface popup anchors

User observed that ThingCardAppearanceSourcePicker's right-bottom origin did
not match the imagined pill touch-area corner, and that DateTimePicker's
time-type popup in the "after" tab appeared above the pill's left-top corner,
while the recurrence tab did not show the same issue.

Finding:
- Pill ripples are shaped by `clipToOutline`, but that does not change the
  TextView's measured bounds; the pill touch area remains the whole TextView.
- The DateTimePicker after-tab issue came from clamping popup top to the short
  dialog content height. The recurrence tab is taller, so the clamp was not
  visible there.

Change:
- `installContentSurfaceScaleTransition(...)` now disables PopupWindow
  clipping for content-surface transition popups.
- DateTimePicker now uses exact anchor offsets: quick-remind bottom aligns to
  anchor vertical centre, and time-type top aligns to anchor top, without
  clamping to parent height.
- ThingCardAppearanceSourcePicker now aligns popup right/bottom directly to
  the anchor TextView right/bottom, without the previous 8dp screen-margin
  clamp.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606070343` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-07 - Remaining popup pickers use content-surface transitions

User pointed out that more popups still used the original PopupWindow animation
styles and requested explicit placement/pivot rules for each.

Change:
- Quick-remind DateTimePicker keeps the left-side / vertical-centre placement
  and left-bottom content-surface transition.
- Time-type DateTimePicker now places the popup at the anchor TextView's
  left-top corner and uses a left-top content-surface transition.
- ThingCardAppearanceSourcePicker now places the popup at the anchor TextView's
  right-bottom corner and uses a right-bottom content-surface transition.
- ThingCardAppearanceSourcePicker rows now add an 8dp top margin to the first
  item and an 8dp bottom margin to the last item, matching quick-remind row
  spacing.
- Removed the obsolete quick-remind PopupWindow animation style/resources after
  moving ThingCardAppearanceSourcePicker off them.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606070330` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-07 - DateTimePicker uses left-bottom content-surface transition

User confirmed the ColorPicker content-surface transition is acceptable and
asked to apply the same idea to DateTimePicker. DateTimePicker should expand
from bottom-left toward top-right, but its origin should be the left side of the
anchor TextView and the TextView's vertical centre, not the horizontal centre.

Change:
- Moved the reusable scale transition into `PopupPicker` as
  `installContentSurfaceScaleTransition(...)`.
- ColorPicker now uses the shared helper with top-right pivot `(1, 0)`.
- DateTimePicker now disables PopupWindow window animation and uses the shared
  content-surface transition with left-bottom pivot `(0, 1)`.
- DateTimePicker now positions both quick-remind and time-type popups from the
  anchor TextView's left edge and vertical centre. The AFTER_TIME branch still
  compensates for bottom system-bar insets before converting to
  `Gravity.BOTTOM`.
- Removed the obsolete `TimeTypePickerAnimation` style and
  `time_type_picker_show` / `time_type_picker_hide` resources. Kept
  `QuickRemindPickerAnimation` because `ThingCardAppearanceSourcePicker` still
  uses it.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606070321` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-07 - ColorPicker uses content-surface transition instead of window animation

User reported that restoring `PopupWindow` window animation fixed show/hide
animation layering but again made the popup appear not to originate from the
anchor centre. Reviewed Android official docs and AOSP source: animation style
is applied as `WindowManager.LayoutParams.windowAnimations`, while popup content
is hosted in `PopupDecorView` / optional background wrappers. That makes the
window animation pivot too coarse for ColorPicker's precise anchor-origin
surface motion.

Change:
- ColorPicker now passes `0` for the PopupWindow animation style.
- ColorPicker clears the PopupWindow background and keeps the visible rounded
  elevated surface on `mContentView`.
- Added `ColorPickerSurfaceTransition`, installed through PopupWindow
  `enterTransition` / `exitTransition`, scaling the visible content surface
  from its top-right corner on show and dismiss.
- Removed the obsolete `ColorPickerAnimation` style and
  `color_picker_show` / `color_picker_hide` animation resources.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606070312` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-07 - Restore ColorPicker popup-window animation

User confirmed the content-level animation fixed the placement but produced the
wrong feel: the popup surface appeared instantly, only the content scaled from
top-right, and dismissal had no popup animation. The desired behaviour is the
popup surface itself animating in and out.

Change:
- Restored `R.style.ColorPickerAnimation` as ColorPicker's PopupWindow
  animation style.
- Removed the temporary `mContentView` scale/alpha animation helper.
- Kept the validated `showAsDropDown(...)` positioning and explicit
  PopupWindow width/height.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606070304` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-07 - ColorPicker animates picker content instead of popup window

User clarified that the ColorPicker trigger is the toolbar menu item icon view,
not a text layout concern. Treated the remaining offset as a mismatch between
`PopupWindow` window animation bounds and the actual picker content surface,
rather than as an anchor-view centre problem.

Change:
- ColorPicker now passes `0` as its `PopupPicker` animation style, disabling
  the `PopupWindow` window enter/exit animation for this picker.
- `ColorPicker.show()` still positions the popup through
  `showAsDropDown(anchor, -anchor.width / 2, -anchor.height / 2, Gravity.END)`
  when a valid anchor exists.
- After the popup is placed, ColorPicker runs a content-level scale/alpha
  animation on `mContentView`, with `pivotX = mContentView.width` and
  `pivotY = 0`, so the actual picker surface grows from its top-right corner.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606070258` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-07 - ColorPicker uses platform anchor positioning

User asked why ColorPicker still did not appear from the anchor view centre and
whether the anchor calculation itself was wrong. Reviewed Android 36
`PopupWindow` source: `showAsDropDown` converts `anchor.getLocationOnScreen()`
into app-window coordinates by subtracting the app root's screen location,
whereas the current ColorPicker path manually combined
`getLocationInWindow()` with `showAtLocation(...)`.

Change:
- `ColorPicker.show()` now writes the measured popup width/height to
  `mPopupWindow` before showing so right-gravity dropdown alignment has
  absolute dimensions.
- With a valid anchor, ColorPicker now calls
  `showAsDropDown(anchor, -anchor.width / 2, -anchor.height / 2, Gravity.END)`.
  This pins the popup's top-right corner to the anchor centre using
  PopupWindow's platform coordinate conversion.
- The manual `showAtLocation(...)` top-right fallback remains only for the
  no-anchor case.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606070247` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-07 - ColorPicker top-right corner animation model

User proposed that ColorPicker's toolbar anchor usually lives near the
top-right corner, so an interior popup pivot may first compute a placement that
extends past the right edge and then gets adjusted by clamping or platform
window bounds. That matches the observed "opening point still biased up-left"
behaviour better than a simple visual nudge.

Changes:
- Updated `color_picker_show.xml` and `color_picker_hide.xml` to scale from
  `pivotX="100%"` and `pivotY="0%"`.
- Updated `ColorPicker.show()` so the popup's top-right corner is pinned to the
  anchor view's centre, with x/y offsets clamped to the parent window.
- Removed the previous `0.25 * anchor size` visual correction constants from
  `ColorPicker`.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606070239` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-07 - ColorPicker anchor visual correction follow-up

User reported that ColorPicker's popup opening anchor still appeared biased
up-left after the anchor-centre/pivot pass. Treated this as a visual calibration
issue specific to toolbar colour actions rather than a reason to change the
shared popup positioning rule.

Change:
- `ColorPicker.show()` now shifts the target point by
  `+0.25 * anchor.width` and `+0.25 * anchor.height` before converting the
  target into popup offsets. This moves the perceived opening point down-right
  while keeping the placement window-relative and anchor-driven.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606070232` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-07 - Anchor-centred ColorPicker and DateTimePicker placement

User reported that ColorPicker and DateTimePicker popups appeared to be
positioned from the trigger view's top-left rather than its centre. Reviewed the
existing popup positioning rule and corrected both pickers to use the anchor
view centre in window coordinates.

Changes:
- Added shared `PopupPicker` helpers for measuring the current popup content,
  reading the anchor centre, and computing clamped anchor-pivot offsets.
- Updated `ColorPicker.show()` to remove the remaining display-global fallback
  math and align its animation pivot to the anchor centre while keeping a
  right-edge fallback when no valid anchor is available.
- Updated `DateTimePicker.show()` so both quick-remind and time-type pickers
  align their animation pivots to the anchor centre; quick-remind keeps the
  bottom-inset compensation before converting the computed popup top back to a
  bottom-gravity offset.

Verification:
- `git diff --check` passed with LF/CRLF conversion warnings only.
- `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606070222` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-06 - DateTime dialog tab strip start alignment

User reported that the DateTime dialog tab row ("某个时刻" / "一段时间之后" /
"重复") looked centered as a group. Updated `fragment_date_time.xml` so the
`TabLayout` keeps `tabMode="scrollable"` but uses `tabGravity="start"`, aligning
the tab strip itself to the left while preserving each tab's internal label
alignment and existing selected-state/ripple styling.

Follow-up feedback noted that aligning the first tab pill's left edge with the
content column still made the tab label itself look indented. Adjusted the
`TabLayout` start margin from `20dp` to `4dp`, allowing the pill/ripple to extend
left while the first tab label visually aligns with the dialog content below.
After visual testing, `4dp` was too aggressive, so the start margin was relaxed
to `12dp`.

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
