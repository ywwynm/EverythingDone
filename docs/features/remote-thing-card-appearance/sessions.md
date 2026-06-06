# Remote Thing Card Appearance Sessions

Migrated from global `memory/sessions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-06-05 - Remote AppWidget fixed-surface projection refinement

- User reported widget and home-list regressions after the first Remote Thing
  Card Appearance debug build: left/right media did not fill card height,
  top/bottom widget media with tall ratios could consume the whole widget, and
  the single-Thing widget configuration picker did not reflect wide cards,
  placement, media backgrounds, or other Thing Card Appearance choices.
- Resolved that left/right Thing Card Media is a full-height side media panel
  across home-list cards, Things List AppWidget rows, and single-Thing
  AppWidgets.
- Resolved that saved Thing Card Appearance remains unchanged in the database.
  AppWidgets render a Thing Card Surface Projection of the saved appearance, and
  single-Thing widgets may clamp media target height to fit their fixed widget
  surface without writing those clamps back to the Thing.
- Resolved that single-Thing AppWidget top/bottom media projection is
  content-floor first: reserve title/private state, at least one body/checklist
  line when present, required reminder/habit/state/action regions, and bottom
  padding before assigning the remaining height to media.
- Resolved that Things List AppWidget rows do not need a product-level row-height
  clamp for tall top/bottom media ratios because the list widget already scrolls
  through collection rows. Rows may grow to honor the saved desired media ratio,
  subject to hard RemoteViews bitmap/IPC and launcher-compatibility caps.
- Clarified that hard safety caps are platform transport limits, not visual row
  height limits: first try to honor saved row media ratios, then reduce or
  degrade only when bitmap size, RemoteViews IPC, or launcher compatibility would
  make the row unsafe to update.
- Resolved the single-Thing widget configuration split: the candidate list
  should reuse home-list Thing Card rendering for recognition, while the
  post-selection preview should render the actual single-Thing AppWidget
  projection with widget alpha, size/aspect, square widget chrome, and
  RemoteViews-compatible media projection.
- Resolved AppWidget size preset expansion up to 6 cells for tablets and
  large-grid launchers. Single-Thing keeps existing 1x1 through 4x4 square
  entries and adds 4x2, 2x4, 4x3, 3x4, 5x2, 2x5, 5x3, 3x5, 5x4, 4x5, 5x5,
  6x2, 2x6, 6x3, 3x6, 6x4, 4x6, 6x5, 5x6, and 6x6; extra 1xN/Nx1 media presets
  are intentionally not added. Things List keeps the existing 3x3 entry and
  adds 4x4, 5x4, 4x5, 5x5, 6x4, 4x6, 6x5, 5x6, and 6x6. Existing providers
  remain resizable.
- Resolved AppWidget Size Preset labeling: existing provider labels may be
  renamed to include their cell shape because label changes do not affect placed
  widget instance identity. Existing single-Thing entries become 1x1, 2x2, 3x3,
  and 4x4; new single-Thing entries are labeled with their exact shapes up to
  6x6; Things List uses 3x3 for the existing provider and exact-shape labels for
  new entries up to 6x6.
- Resolved AppWidget Size Preset sizing metadata: each provider should declare
  Android 12+ `targetCellWidth` / `targetCellHeight` plus `minWidth` /
  `minHeight` fallbacks for Android 11 and below and launchers that still infer
  picker size from minimum dimensions.
- Implemented new single-Thing provider classes and Things List provider
  classes up to 6x6. New single-Thing presets share
  `BaseThingWidgetConfiguration`, which now resolves the actual provider class
  from `AppWidgetManager` before saving widget size.
- Implemented RemoteViews projection refinements: single-Thing top/bottom media
  now uses a content-floor-first height budget, Things List top/bottom media
  can grow rows up to hard bitmap safety caps, side media slots fill the row or
  widget content height, and media backgrounds render across the single-Thing
  widget surface.
- Updated the single-Thing widget configuration screen so the candidate list
  honors home-list full-span Thing Cards and the post-selection preview applies
  the actual single-Thing RemoteViews projection at the selected alpha and
  provider aspect.
- Verified with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606050916` with
  `.\gradlew.bat :app:publishDebugUpdate
  "-PdebugUpdateNotesFile=memory/debug-update-notes.md"` after rerunning with
  elevated permissions because the sandbox denied `.gradle/configuration-cache.lock`.
- Resolved that arbitrary nested widget scrolling is not the default solution
  for oversized media. Things List rows rely on the parent collection scroller,
  and redesigning the single-Thing AppWidget as a collection widget is deferred.
- Updated `CONTEXT.md`, `memory/decisions.md`,
  `docs/plans/REMOTE_THING_CARD_APPEARANCE_PLAN.md`, and
  `memory/followups.md`.

## 2026-06-05 - Remote Thing Card Appearance feasibility analysis

- User asked to analyze porting the recently implemented Thing Card Appearance
  features to AppWidgets and notifications, with a preference for complete
  support even if implementation takes longer.
- Read project memory, `.agents/rules/`, `CONTEXT.md`,
  `docs/plans/THING_CARD_APPEARANCE_PLAN.md`,
  `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md`, the unified appearance ADR,
  `AppWidgetHelper`, widget layouts, `SystemNotificationUtil`, and relevant
  home-list card rendering paths.
- Confirmed existing v1 scope deliberately left AppWidget image regions and
  system notification big-picture behavior unchanged, but treated that as future
  scope rather than a hard product rejection.
- Initial conclusion: AppWidget support can likely implement many appearance
  choices through RemoteViews plus app-side bitmap pre-rendering, but exact side
  media width and media-background height are constrained on API 26-30 because
  dynamic RemoteViews layout sizing APIs arrive in API 31. System notifications
  are much more constrained: target SDK 36 prevents fully custom notifications,
  custom content is RemoteViews-based and height-limited, and standard
  BigPictureStyle can realistically support selected media, crop, and video
  frame but not the whole card layout/background model.
- Updated `memory/preferences.md` to record the user's completeness-first
  preference for this remote-surface porting discussion.
- After iterative grilling, resolved scope and wrote
  `docs/plans/REMOTE_THING_CARD_APPEARANCE_PLAN.md`, covering support matrix,
  rendering strategy, degradation rules, implementation phases, and verification
  checklist.

## 2026-06-05 - Single-Thing widget side media width analysis

- User reported that a full-span habit with left-side media and a saved
  `sideMediaWidthPercent` of 42% appears narrower than 42% in a 4x4
  single-Thing AppWidget, even though the right-side content column seems to
  have enough space.
- Performed static diagnosis only; no functional code was changed.
- Reviewed `docs/plans/REMOTE_THING_CARD_APPEARANCE_PLAN.md`,
  `CONTEXT.md`, `docs/adr/0002-unified-thing-card-appearance.md`,
  `AppWidgetHelper.kt`, `app_widget_thing.xml`, `app_widget_item_thing.xml`,
  `RemoteThingCardMediaRenderer.kt`, `ThingsActivity.kt`, and
  `BaseThingsAdapter.kt`.
- Main finding: single-Thing AppWidgets currently let
  `sideMediaDisplayAspectRatioHint` override the saved side-width percent by
  deriving bitmap width from widget height. This can shrink side media below the
  saved percent when the hint captured from the home card is narrower than the
  4x4 widget projection.
- Secondary findings: AppWidget sizing depends on launcher-provided
  `OPTION_APPWIDGET_*` values with provider-cell fallback; right-side content
  padding and habit chrome affect readability but do not currently participate
  in side-media width budgeting; top/bottom and media-background placement have
  separate height clamps and should be audited independently.

## 2026-06-06 - AppWidget resize option change refresh

- Added direct `onAppWidgetOptionsChanged` handling for single-Thing
  AppWidgets and Things List AppWidgets.
- Single-Thing widgets now reuse the normal update path when launcher resize
  options change, so RemoteViews and pre-rendered media bitmaps are regenerated
  with the latest `AppWidgetManager` size options.
- Things List widgets now notify collection row data changed and update the
  outer RemoteViews when launcher resize options change, so visible rows can be
  re-created with the latest widget size options.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606060214` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
  --console=plain --no-configuration-cache`.

## 2026-05-27 - Android 16 AppWidget click action fix

Investigated AppWidget click regressions after targeting Android 16 / SDK 36.
The affected widget surface has two distinct PendingIntent requirements:

- Collection rows (`ThingsListWidget` list rows and single-thing checklist
  rows) use `setPendingIntentTemplate(...)` plus `setOnClickFillInIntent(...)`.
  Their template PendingIntent must be mutable so launcher-provided fill-in
  extras such as thing id and checklist item position reach the app.
- Widget Activity launches are sent by the launcher. Added a shared
  `AppWidgetHelper.getActivityPendingIntentForWidget(...)` helper that attaches
  creator-side background-activity-launch `ActivityOptions` on Android 14+,
  then routed all widget `getActivity` PendingIntents through it.

Changed files:
- `AppWidgetHelper.kt`
- `CreateWidget.kt`
- `CheckUpcomingWidget.kt`
- `memory/decisions.md`

Verification:
- Source guard for AppWidget collection templates passed: both templates now
  use the mutable collection-template flag.
- Source guard for raw widget `PendingIntent.getActivity` usage passed: all
  AppWidget activity launches now go through the BAL helper.
- The first sandboxed Gradle attempts did not produce a fresh APK: one wrapper
  run timed out while trying to download Gradle, and later runs failed writing
  `swirl/build/intermediates/...` with access denied.
- Re-ran the project wrapper outside the sandbox using
  `.\gradlew.bat :app:assembleDebug --console=plain`; it completed with
  `BUILD SUCCESSFUL in 31s` and produced
  `app/build/outputs/apk/debug/app-debug.apk` at `2026-05-27 11:45:51`.
- `git diff --check` passed with only existing LF-to-CRLF warnings.

No real launcher widget click smoke test was run in this session because no
emulator was attached; `adb devices` showed only the physical device.

Follow-up after user reported duplicate home-card update animation:
- Root cause: while `ThingsActivity` was stopped but still alive, its dynamic
  `UPDATE_MAIN_UI` receiver could receive widget checklist-toggle broadcasts.
  If a previous `mRemoteIntent` was already pending, the hidden receiver branch
  immediately called `updateMainUi(mRemoteIntent)`, which could enqueue a
  RecyclerView notify while the adapter was in wait-notify mode, then store the
  newer widget intent for `onResume()`. Returning to home could therefore replay
  a stale queued notify plus the fresh widget update.
- First attempted fix was too broad: it always replaced the pending hidden
  `mRemoteIntent` with the latest intent, which could drop meaningful distinct
  remote events. User correctly flagged this as risky.
- Revised fix: hidden remote updates now go through an explicit coalescing
  helper. The pending intent is replaced only when replacement is safe:
  `App.justNotifyAll()` is already true, the old result is `RESULT_NO_UPDATE`,
  or both intents are the same result for the same Thing. Otherwise the legacy
  behavior is preserved: process the older pending intent, then store the newer
  one after the delay.
- Verification: source guard confirmed the hidden branch uses the coalescing
  helper and that the helper covers the no-update and same-Thing replacement
  cases; `.\gradlew.bat :app:assembleDebug --console=plain` passed outside the
  sandbox (`BUILD SUCCESSFUL in 2s`) and produced
  `app/build/outputs/apk/debug/app-debug.apk` at `2026-05-27 11:57:34`.

Second follow-up after the duplicate animation remained hard to reproduce:
- Found another plausible independent cause: `ThingsAdapter.onBindViewHolder()`
  replayed `things_show` on every bind while
  `mShouldThingsAnimWhenAppearing == true`. A remote widget
  `notifyItemChanged(...)` can therefore combine RecyclerView's change
  animation with the adapter's delayed appear animation. Because
  `playAppearingAnimation()` starts via `Handler.postDelayed(position * 30)`,
  stale delayed appear runnables can also fire after a later content-update
  bind, which explains the intermittent nature.
- Fix: `ThingsAdapter` now tracks which Thing ids have already played the
  appearing animation during the current appear cycle, clears that set when a
  new explicit appear cycle is requested, and uses a per-view keyed tag token
  to cancel stale delayed appear runnables on later non-appearing binds.
- Added `app/src/main/res/values/ids.xml` for the keyed animation token tag.
- Verification: source guard confirmed the appeared-id guard and per-view token
  cancellation are present; `.\gradlew.bat :app:assembleDebug --console=plain`
  passed outside the sandbox (`BUILD SUCCESSFUL in 4s`) and produced
  `app/build/outputs/apk/debug/app-debug.apk` at `2026-05-27 12:57:32`.
- Rolled this second follow-up back after device testing showed severe
  AppWidget data/rendering regressions: tapping checklist item A could update B,
  checklist rows could show checked text with an unchecked icon, and Things-list
  widgets could show wrong thing data. `ThingsAdapter.kt` was restored to its
  pre-follow-up state and `app/src/main/res/values/ids.xml` was deleted.
- Rebuilt the rollback APK with
  `.\gradlew.bat :app:assembleDebug --console=plain`; it passed
  (`BUILD SUCCESSFUL in 1s`) and produced
  `app/build/outputs/apk/debug/app-debug.apk` at `2026-05-27 13:03:57`.

## 2026-05-25 — Homepage thing-card UI refresh, step 1

Updated EverythingDone's homepage thing cards using Everything-Android's
main-list card feel as reference:

- Added shared card tokens: 10dp outer spacing, 10dp corner radius,
  8dp normal elevation, 12dp dragging elevation.
- Applied the spacing to `activity_things.xml`, `ThingsAdapter`
  item margins, and `DisplayUtil.getThingCardWidth` so image cards
  keep the same waterfall grid geometry.
- Updated `card_thing.xml` with the larger radius, stronger shadow,
  max elevation, and outline clipping for image attachments.
- Added normal-mode touch response in `ThingsAdapter`: press scales to
  0.936 and lowers elevation; release bounces to 1.016 then settles.
- Kept moving/selecting mode geometry consistent via `BaseThingsAdapter`
  and `ModeManager`; updated new-item shining border radius to the
  same card-radius dimen.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed
  (`BUILD SUCCESSFUL in 2s` on the final incremental run).
- `git diff --check` passed; only existing CRLF conversion warnings.
- No visual install/screenshot was performed because `adb devices`
  showed only a physical device and no `emulator-5554`.

Follow-up in same session after user reported the radius still looked
unchanged:
- Root cause: `BackgroundUtil.applyCardBackground()` replaced
  CardView's runtime background with a plain `ColorDrawable` or a
  no-radius `GradientDrawable` on every bind. On API 21+, CardView's
  outline follows that runtime background, so the XML
  `cardCornerRadius` was effectively erased.
- Fix: `applyCardBackground()` now always uses a rounded
  `GradientDrawable`, syncing `gd.cornerRadius = cv.radius` and using
  identical color stops for PURE backgrounds so pure/gradient recycling
  keeps the same rounded shape.
- Re-verified `.\gradlew.bat :app:assembleDebug --console=plain`
  (`BUILD SUCCESSFUL in 8s`) and `git diff --check`.

Second follow-up in same UI pass:
- In selecting/moving mode, unselected cards already used
  `lightVariant(background)`, but their content layer kept full alpha.
  On light cards this made black text/icons stand out too much.
- Added `BaseThingsAdapter.shouldDimUnselectedContent()` and enabled it
  only for the homepage `ThingsAdapter`; unselected homepage cards now
  set `llContent` and sticky/ongoing icon alpha to `0.54f`.
- Adjusted `ThingsAdapter.onBindViewHolder()` so its new-item-animation
  cleanup only forces `llContent.alpha = 1f` in NORMAL mode, otherwise
  it would undo the selecting/moving-mode dim.
- Re-verified `.\gradlew.bat :app:assembleDebug --console=plain`
  (`BUILD SUCCESSFUL in 8s`) and `git diff --check`.
- User clarified black and white content should not use the same alpha
  because black reads visually heavier. Split unselected content alpha:
  black/dark foreground on light cards uses `0.38f`; white/light
  foreground on dark cards uses `0.54f`. Re-verified assembleDebug
  (`BUILD SUCCESSFUL in 6s`) and `git diff --check`.
- User clarified the alpha split should only apply to adaptive foreground
  content (text/icons/checklist/reminder/habit), not to image attachments
  or the card colour itself. Replaced the coarse `llContent.alpha` dim with
  per-view alpha assignment: image attachment container/image/cover/loading
  stay at `1.0f`, while adaptive foreground views use the black/white split.
  Re-verified assembleDebug (`BUILD SUCCESSFUL in 7s`) and `git diff --check`.
- User reported the "Blank thing has been abandoned" snackbar no longer
  appears after creating an empty thing and returning home. Root cause:
  `DetailActivity.createFailed()` used `setResult(resultCode)` without an
  `Intent`, while `ThingsActivity.onActivityResult()` now intentionally skips
  null `data` to avoid a migrated Kotlin NPE. The non-zero result code was
  therefore dropped before `RESULT_CREATE_BLANK_THING` could show its snackbar.
  Fixed `createFailed()` to put `KEY_RESULT_CODE` into its result `Intent`
  and call `setResult(resultCode, intent)`. Re-verified assembleDebug
  (`BUILD SUCCESSFUL in 9s`) and `git diff --check`.
- Extended the updated thing-card radius to `DoingActivity`, which reuses
  `card_thing` but had been forcing the CardView radius to `0f`. Kept
  `NoticeableNotificationActivity` square per user direction, and left the
  single-thing widget configuration preview unchanged because the actual
  desktop widget is RemoteViews + bitmap-backed, not a CardView. Re-verified
  assembleDebug (`BUILD SUCCESSFUL in 5s`) and `git diff --check`.

## 2026-05-26 - Detail follow-system dark-mode overlay refresh

Fixed the reported DetailActivity follow-system dark-mode lifecycle issue:
- Detail now records the current night-mode mask and detects real `uiMode`
  appearance changes separately from orientation/layout changes.
- On a night-mode change, Detail applies the AppCompat night mode update,
  dismisses the toolbar overflow popup, dismisses Detail-owned DialogFragments,
  dismisses old picker PopupWindows, recreates `ColorPicker` and
  `quickRemindPicker` with the updated DayNight resources, reattaches their
  listeners, preserves the current colour/quick-remind selection, and rebuilds
  the reusable `DateTimeDialogFragment` instance.
- Added Detail App Chrome theme items to the base, `values-v19`, and
  `values-v21` `EverythingDoneTheme.Detail` definitions so overflow menus and
  other floating Detail chrome resolve dark text/control/background resources
  on high-version devices.
- Completed the `values-v29` dialog theme override with the same window
  background / floating background / min-width items as the base dialog theme.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed twice after the
  implementation and versioned-style corrections.
- `git diff --check` passed with CRLF conversion warnings only.
- No device visual smoke test was run from the agent session.

Follow-up investigation and correction:
- Rechecked pre-Kotlin Java history for Detail checklist sizing. The legacy
  implementation also used `findViewHolderForAdapterPosition()` to sum laid-out
  non-finished rows when finished checklist items were collapsed, with fixed
  fallbacks only for separator/count rows (`3`/`4`). It did not estimate
  offscreen normal item heights.
- Android Support Library 25.3.1, AndroidX RecyclerView 1.4.0, and the Kotlin
  migration all keep the same Detail layout shape: a non-scrolling
  `RecyclerView` inside `NestedScrollView`, with `layout_height="wrap_content"`
  and inner nested scrolling disabled.
- Replaced the height-estimation approach. Detail now keeps the checklist
  RecyclerView height at `WRAP_CONTENT`; `CheckListAdapter.getItemCount()`
  exposes only visible rows while finished items are collapsed and exposes the
  complete item list when expanded.
- Checklist structural edits now refresh the adapter data set instead of
  combining granular insert/remove notifications with a changing collapsed item
  count. EditText text changes still update the backing item and only request a
  layout pass through Detail, so ordinary typing should not lose focus.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed twice after the
  adapter-visible-row change.
- `git diff --check` passed with CRLF conversion warnings only.
- No device visual smoke test was run from the agent session.

Follow-up keyboard focus correction:
- Checklist "new item" insertion no longer actively hides the keyboard. The
  add-row icon path returns before the checkbox-toggle `hideKeyboard()` call,
  and `insertItem()` no longer clears focus before `DetailActivity.onInsert()`
  focuses the created EditText.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed after the focus
  correction.
- `git diff --check` passed with CRLF conversion warnings only.

Detail overflow follow-up:
- Fixed `include_actionbar_detail.xml` still using
  `app:popupTheme="@style/Theme.AppCompat.Light"`. Detail toolbar popups now
  use `EverythingDoneTheme.Detail.Popup`, a DayNight overlay with App Chrome
  floating background and foreground colours.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed after the popup
  theme change.
- `git diff --check` passed with CRLF conversion warnings only.

Image viewer attachment-info follow-up:
- Added `uiMode` to `ImageViewerActivity`'s handled config changes so switching
  follow-system dark mode does not destroy the image viewer while a dialog is
  open.
- `ImageViewerActivity` now tracks night-mode changes, dismisses stale image
  viewer dialogs, and reopens the attachment-info dialog for the current image
  if it was visible during the mode switch.
- `AttachmentInfoDialogFragment` no longer depends only on setter-injected
  fields. Accent background/color and attachment info rows are persisted in
  fragment arguments and restored after system recreation; its adapter now
  treats missing rows as an empty list instead of crashing during measure.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed after the image
  viewer/dialog fix.
- `git diff --check` passed with CRLF conversion warnings only.

Follow-up fixes in the same Detail area:
- Preserved quick-remind popup position after follow-system light/dark changes
  when the user left the default "15 minutes later" selection untouched. The
  picker recreation path now infers the selected row from `rhParams` when the
  old picker reports no explicit picked row, and AFTER_TIME pickers initialise
  their adapter selection to row 8.
- Fixed long editable checklists being clipped inside Detail. The checklist
  RecyclerView remains non-scrollable, but Detail now updates its explicit
  layout height from checklist data changes. It uses already-laid-out holder
  heights when available and pure text/layout estimates for offscreen rows, so
  the outer `NestedScrollView` can reveal all checklist rows and the "new item"
  row without creating/binding item views during RecyclerView measurement.
- Reverted the previous `LayoutManager.onMeasure()` row-binding approach after
  it disturbed EditText focus/IME input and could race empty-row deletion.
  Empty-row backspace with no target row now hides the keyboard directly
  instead of asking RecyclerView for adapter position `-1`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.
- No device visual smoke test was run from the agent session.

Noticeable notification ripple and Things return-animation follow-up:
- `NoticeableNotificationActivity` now installs its action-button backgrounds
  programmatically from the current `Appearance Mode`: light mode keeps the
  existing black ripple, while dark mode uses the white ripple resource. It
  also reapplies the DayNight delegate and repaints the shell on `uiMode`
  changes so already-open noticeable notifications do not keep stale ripple
  drawables.
- `ThingsActivity` records whether it was restored from saved state, which is
  the path hit when it is recreated in the background after a system light/dark
  change. Restored lists no longer play the initial "appearing" card animation.
- Reverted the experimental Detail-return payload refresh after device testing
  showed it made the list jump back to the restored position without the normal
  item update affordance. Same-type Detail returns again use ordinary
  `notifyItemChanged(position)`; only the restored list-wide appearing animation
  remains disabled.
- Restored ThingsActivity instances now run the RecyclerView init runnable
  synchronously instead of waiting 240 ms. Normal cold/open animations keep the
  historical delay, but system-recreated home screens should not briefly expose
  an empty list before RecyclerView restores its scroll state.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.
- No device visual smoke test was run from the agent session.

Audio attachment info icon dark-mode correction:
- Repainted the audio attachment info icon during every
  `AudioAttachmentAdapter.onBindViewHolder()` call. The play/delete/stop icons
  were already rebound with the current App Chrome control colour, but the info
  icon was only tinted in `ViewHolder` init, so existing holders could keep a
  stale light-mode tint after a system dark-mode change and look too dim.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.

Dark-mode ripple correction:
- Replaced the API 21+ shared `selectable_item_background` and
  `selectable_item_background_light` resources with direct `RippleDrawable`
  XMLs using transparent content and an explicit mask. This covers the hardcoded
  selectable backgrounds used by Settings, Help, App Chrome dialogs, popup
  picker rows/buttons, attachment dialogs, chooser dialogs, DateTime controls,
  and related button-like rows.
- Added `app:rippleColor="@color/app_chrome_ripple"` to the audio-record dialog
  Material FAB so it participates in the same App Chrome ripple policy as the
  surrounding dialog controls.
- Follow-up after device testing showed no visual change: the dark build was
  still packaging `drawable-night/selectable_item_background.xml` as
  `drawable-night-v8`, so dark mode bypassed the new `drawable-v21` ripple.
  Added `drawable-night-v21/selectable_item_background.xml` with the same direct
  masked ripple. Rebuilt debug resources confirmed only `drawable-night-v21` and
  `drawable-v21` selectable backgrounds remain in packaged resources.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.

Launcher widget Android 16 follow-up:
- User clarified that the repeat animation after toggling a checklist item from
  a widget looked like the whole Things list reloading from the bottom, not a
  RecyclerView `notifyItemChanged()` change animation.
- Traced that visual path to `ThingsActivity.justNotifyAll()`, which reloads
  the Things data and always enabled `things_show` before
  `notifyDataSetChanged()`.
- Kept the full reload behavior for correctness, but parameterized
  `justNotifyAll()` and made the `onResume()` branch that consumes background
  `App.justNotifyAll()` call `justNotifyAll(false)`, so widget/background
  catch-up refreshes do not replay the list appearing animation.
- Confirmed the previous `ThingsAdapter.kt` / `ids.xml` experiment remains
  rolled back; this change does not touch widget RemoteViews data generation or
  adapter resource ids.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed and produced
  `app\build\outputs\apk\debug\app-debug.apk` at 2026-05-27 13:11:49.
- `git diff --check` passed with CRLF conversion warnings only.

Launcher widget rendering follow-up:
- Fixed Create widget and Things List widget create-button colour staleness by
  routing create clicks through `ShortcutActivity`. The RemoteViews PendingIntent
  now carries only the create action (and the list limit when applicable), so
  each click resolves the current `App.newThingBackground` immediately before
  opening `DetailActivity`.
- Added explicit luminance-adaptive icon rendering in `AppWidgetHelper` for
  widget card icons that sit on Thing backgrounds: checklist state icons,
  private lock, sticky/ongoing markers, reminder/goal icons, habit icon, habit
  record dots, audio attachment icon, and finished/deleted state icons.
- Added ids for the widget audio attachment ImageViews so RemoteViews can swap
  the black/white audio assets just like the normal card adapter does.
- Tightened widget reminder/habit/state text layout constraints by giving text
  next to icons the remaining row width plus single-line ellipsizing. This
  targets the intermittent launcher rendering case where a reminder icon showed
  but the adjacent reminder time text was missing.
- Did not modify `ThingsAdapter.kt` and did not recreate `values/ids.xml`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed and produced
  `app\build\outputs\apk\debug\app-debug.apk` at 2026-05-27 14:36:32.
- `git diff --check` passed with CRLF conversion warnings only.

Launcher widget create/configuration correction:
- User found the standalone Create widget could fail on the second click in this
  sequence: keep ThingsActivity alive by pressing Home, click Create widget,
  press Back to abandon the empty created Thing and return to the Things home
  screen with the abandon snackbar, press Home again, then click Create widget.
  The second click resumed the Things home screen instead of opening
  `DetailActivity` in CREATE mode.
- First tried restoring the standalone Create widget to a direct
  `DetailActivity` PendingIntent plus refreshing standalone Create widgets
  after `App.newThingBackground` rolls. Device testing showed that approach did
  not fix the repeated task/colour staleness.
- Replaced the direct path by copying the Things List widget create action:
  standalone Create now opens `ShortcutActivity` with
  `SHORTCUT_ACTION_CREATE`, carries `KEY_LIMIT = ALL_UNDERWAY`, and uses the
  shared widget BAL PendingIntent helper. The Things List widget keeps the same
  `ShortcutActivity` create path with its selected list limit.
- Removed the temporary `AppWidgetHelper.updateCreateAppWidgets()` helper and
  the `DetailActivity` call site added by the failed direct-path attempt.
- Fixed `ThingsListWidgetConfiguration` so reopening settings reads the stored
  negative `ThingWidgetInfo.thingId` and preselects the saved limit instead of
  always showing "All".

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed after the final
  create-action correction and produced `app\build\outputs\apk\debug\app-debug.apk`
  at 2026-05-27 15:35:00.
- `git diff --check` passed with CRLF conversion warnings only.

Button-like control ripple shaping:
- Resolved the scope through a grill-with-docs pass: shaped ripple work targets
  local command controls only. Full-row, full-card, and full-width dialog
  action-row surfaces stay rectangular and are excluded. Dialog "Got it" buttons
  that were accidentally implemented as full-row rows are layout debt and should
  become compact bottom-end text buttons.
- Added shared `BackgroundUtil` helpers for App Chrome and Thing-owned pill or
  circular ripple drawables. Pill outlines use the laid-out height divided by
  two; icon-only controls use oval/circular masks. App Chrome controls resolve
  `app_chrome_ripple`; Thing-owned controls derive black/white translucent
  ripple from the Thing representative colour.
- Applied the helpers to compact BaseDialogFragment text buttons, DateTimeDialog
  tabs and dropdown entry controls, DateTime recurrence add/delete/select-all
  controls, NoticeableNotification action icons, Detail quick-remind and
  checklist controls, Settings help icons, AudioRecord side icons, and the
  two-option dialog's icon+text actions. `HabitDetailDialog`'s full-width
  "Got it" action was converted to the compact bottom-end dialog-button shape.
- Kept popup picker internal selection rows and other full-row/full-card
  surfaces on their existing full-row feedback.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.

Gradle debug update notes rule update:
- User asked to update the Gradle operational rules so debug app changes default
  to `:app:publishDebugUpdate` and every publish task run uses
  `memory/debug-update-notes.md` as a conversation-summary notes file.
- Updated `.agents/rules/gradle.md`: `:app:publishDebugUpdate` is now the
  default debug Gradle task, while `:app:assembleDebug` is documented as a
  local compile/diagnostic task only. The rules now require updating
  `memory/debug-update-notes.md` before each publish with the user's request,
  the analysis, important file/code changes, user follow-up corrections, the
  agent response, and verification/publish status.
- Updated `memory/preferences.md` with the same publishing preference.
- Rewrote `memory/debug-update-notes.md` from a one-line NoticeableNotification
  fix note into the new concise conversation-summary format for the most recent
  debug update.

Verification:
- `git diff --check` passed with CRLF conversion warnings only.

## 2026-06-05 - Remote Thing Card Appearance implementation pass

- Implemented `RemoteThingCardMediaRenderer` for remote surfaces. It resolves
  the effective Thing Card media source, checks image/video permissions,
  decodes selected images or exact video frames with
  `MediaMetadataRetriever.OPTION_CLOSEST`, applies saved thumbnail/background
  crop values, and bakes media-background mask strength into the output bitmap.
- Updated standard Thing notifications to use the renderer for
  `BigPictureStyle`, so selected source, thumbnail crop, and saved video frame
  are respected. Full custom notification card layout remains out of scope.
- Reworked single-Thing and Things-list AppWidget layouts with dedicated
  top/bottom/left/right media slots and a media-background count overlay.
- Updated `AppWidgetHelper` so AppWidgets follow Thing Card Appearance for
  selected media source, top/bottom/left/right placement, side width, thumbnail
  crop ratio/focus, media backgrounds, background mask, and video frame. The
  structured RemoteViews text/checklist/state/action regions remain
  interactive.
- AppWidget side media width is expressed through the pre-rendered bitmap's
  intrinsic width plus `wrap_content` side slots, avoiding a dependency on
  newer RemoteViews layout-sizing APIs.
- Media-background rendering now degrades back to normal foreground media if
  the background bitmap cannot be produced, rather than hiding media on a plain
  Thing background.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed after implementation and again after the side-width layout adjustment.
- No launcher/widget or notification visual smoke test was run on a device in
  this session.

Publish:
- Updated `memory/debug-update-notes.md` with Chinese debug update notes for the
  remote Thing Card Appearance widget/notification port.
- The first sandboxed
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  attempt reached `:app:publishDebugUpdate` but timed out during the upload
  phase.
- Re-ran the same command with elevated permissions so `ssh`/`scp` could access
  the configured update server. The task passed and published debug update
  `202606050729` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-05 - Remote widget appearance follow-up fixes

- User reported three follow-up problems after testing the remote Thing Card
  Appearance widget build:
  - the single-Thing widget preview showed only a gray background;
  - left/right media layouts in single-Thing widgets and Things-list widget rows
    still left reminder, habit, state, and action sections full width instead
    of keeping them in the non-media content column;
  - custom Thing Card Appearance operations needed a check for widget refresh.
- Diagnosed the preview issue as a `RemoteViews.apply()` failure path in
  `BaseThingWidgetConfiguration`: exceptions were printed but the preview
  container stayed empty.
- Updated `app_widget_item_thing.xml` so `ll_reminder_habit_state` and
  `view_thing_padding_bottom` live inside `ll_thing_text_content_widget`. This
  makes Things-list widget rows use the same media-column/content-column shape
  as home cards for left/right media.
- Updated `app_widget_thing.xml` so `ll_reminder_habit_state`,
  `ll_thing_action`, and `view_thing_padding_bottom` live inside the single
  widget text content column, and removed the old root-level bottom anchor.
- Updated `BaseThingWidgetConfiguration.kt` so the single-widget preview still
  tries the actual widget `RemoteViews` first, but falls back to a single local
  `card_thing` preview if applying the `RemoteViews` fails. The fallback keeps
  provider-sized preview geometry, alpha background tinting, full-span card
  support, media placement, and media backgrounds visible instead of leaving a
  gray blank screen.
- Updated `ThingsActivity.confirmThingCardAppearancePanel()` to refresh the
  edited single-Thing widgets, all Things-list widgets, and the ongoing Thing
  notification immediately after persisting the confirmed appearance draft.
- Recorded the refresh decision in `memory/decisions.md`.

Verification:
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- The first sandboxed
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  attempt timed out during `:app:publishDebugUpdate`.
- Re-ran the same publish command with elevated permissions. It passed and
  published debug update `202606051046` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-05 - Widget preview rounding and side media aspect hint

- User agreed to two follow-up implementation directions:
  - the single-Thing widget configuration preview should prioritise the actual
    provider-sized RemoteViews/widget surface rather than expanding or scrolling
    to show all Thing content;
  - side media in widgets should use a derived final display aspect ratio hint
    where possible, with the saved side-width percent kept as a clamp/fallback.
- Updated `BaseThingWidgetConfiguration.kt`:
  - installs a rounded outline on `fl_app_widget_preview` and enables
    `clipToOutline`, so both actual RemoteViews previews and fallback previews
    are clipped to the Thing Card corner radius;
  - disables vertical scrolling in the fallback single-card RecyclerView, so
    fallback preview also behaves like a fixed widget frame.
- Updated `ThingCardAppearance.SourceAppearance` with optional
  `sideMediaDisplayAspectRatioHint`, including JSON read/write support with
  old-data compatibility.
- Updated `ThingsActivity.confirmThingCardAppearancePanel()`:
  - before persisting the confirmed draft, it captures the currently visible
    home-card side-media width/height ratio when the draft uses left/right media
    and the side media view has been measured;
  - clears an old hint for the current source when the confirmed draft no
    longer uses left/right foreground media;
  - leaves the hint absent when the view cannot be measured, preserving the old
    percentage-based widget behaviour.
- Updated `AppWidgetHelper` side-media sizing:
  - single-Thing widgets use fixed widget height plus the hint to infer side
    media width, clamped by the configured min/max side-width percent;
  - Things-list widget rows use side width plus the hint to infer media height;
  - widgets fall back to the previous side-width percent logic when no hint is
    available.
- Recorded the design rule in `memory/decisions.md` and the user's
  discuss-before-implementation preference in `memory/preferences.md`.

Verification:
- `git diff --check` passed with only the repository's existing LF/CRLF
  warnings.
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed.
- The first sandboxed
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  attempt timed out during `:app:publishDebugUpdate`.
- Re-ran the same publish command with elevated permissions. It passed and
  published debug update `202606051117` to
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.
