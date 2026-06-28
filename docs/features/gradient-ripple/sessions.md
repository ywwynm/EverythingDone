# Gradient Ripple — Sessions

## 2026-06-27 — Initial implementation

- Confirmed against AOSP source that the platform `RippleDrawable` cannot render a gradient
  ripple (single-colour paint). The user picked form B (gradient ripple surfacing on press)
  over form A (persistent gradient underlay + plain ripple). See `decisions.md`.
- Added `app/.../views/GradientRippleDrawable.kt` and wired unselected-state gradient
  ripples into three places:
  - drawer `ThingStatusSegmentedView` — status segments (正在进行 / 已完成 / 回收站),
    unselected ROUND_RECT (radius = height/2) ripple from `scopeBackground`.
  - drawer `ThingFilterPanel` — the 5 type icons, unselected OVAL ripple on
    `button.background`.
  - `RecurrencePickerAdapter` — weekly/monthly/yearly circular buttons (OVAL) and the
    month-end pill (ROUND_RECT). Added `onViewRecycled` to stop animations.
- Hardened `DisplayUtil.setRippleColorForCardView` to an `is RippleDrawable` check.
- Builds clean (`:app:assembleDebug`). Published a debug update (code 202606271303) to the
  Aliyun channel with `PEAK_ALPHA = 0.36` for on-device tuning. Not committed — awaiting the
  user's visual review.

## 2026-06-27 — Animation tuning after first on-device test

- Feedback: the ripple followed the finger when sliding and could disappear before filling
  the circle; it also felt slower than the system ripple.
- Reworked `GradientRippleDrawable` animation: lock the origin at press (no finger
  tracking); decouple radius and alpha so the radius always fills even on quick
  release/scroll-cancel (alpha-only fade); speed it up (radius 260ms, alpha enter 60ms,
  alpha exit 300ms). Published debug update 202606271314.

## 2026-06-27 — App-wide ripple expansion (batch 1: home ecosystem + settings)

User requested extending coloured/gradient ripples across the whole app (14 items). Cross-cutting
conventions: unselected → colour ripple via `GradientRippleDrawable` (folder/record/accent); selected
→ adaptive neutral ripple via `BackgroundUtil.adaptiveRippleColor(bg)` (light→dark, dark/accent→white).
Added shared helpers `adaptiveRippleColor(bg)` and `fillDrawable(bg)` to BackgroundUtil; gave
`GradientRippleDrawable` a pill mode (`cornerRadiusPx < 0` → height/2) and rectangle mode (`== 0`).

Batch 1 (items 1–5), all compiling:
- Drawer: status/type selected ripple → adaptive; folder + settings/help/about items unselected →
  colour ripple, selected → filled colour row + adaptive ripple + contrast foreground; folder
  expand button → folder-colour ripple. (DrawerNavigationView + ThingsActivity scope plumbing.)
- Home toolbar icons (hamburger/overflow/search-back/colour) → folder colour, selection mode adaptive,
  via traversing the system Toolbar's child views. Overflow popup items stay system default (user call).
- Big folder thumbnail card → folder colour ripple (ThingsAdapter; shared with single-thing widget config).
- Appearance panel pills (card width / folder size / media position) → colour unselected, adaptive
  selected; colour panel pure/gradient tabs → record/folder colour (ThingBackgroundEditor).
- SettingsActivity: all `_as_bt` items → accent gradient (recursive traversal); Two/ThreeOptionsDialog
  action items → coloured ripple from `mAccentBackground ?: accent` (covers drawer-header dialog).
- Published debug update for on-device review. Remaining items 6–14 (Detail + dialogs + widget + search) pending.

## 2026-06-27 — Batch-1 feedback round 1

- Drawer selected folder's expand button ripple → adaptive (was always folder colour).
- Toolbar icons: removed the system ripple layer (`background = null`) so only ours shows
  (was double-layered).
- Appearance/colour panel: change-colour icon, confirm button, cover-source button, colour-page
  back button, random/pick buttons → record/folder colour.
- Settings checkbox (`CompoundButton`) → accent oval ripple.
- `adaptiveRippleColor` whitish alpha deepened `0x29` → `0x40` (≈25%). Published 202606271540.

## 2026-06-27 — Batch-1 feedback round 2

- Toolbar icon ripple was bigger than the system layer, and nav buttons (hamburger / close /
  back) were even larger because their host views are wider. Gave `GradientRippleDrawable` a
  `fixedRadiusPx` mode and switched all toolbar icons to a fixed 20dp radius
  (`TOOLBAR_ICON_RIPPLE_RADIUS_DP`) — decouples ripple size from view bounds so nav == menu
  items == system size. Contextual (selection-mode) icons use `circularRipple(...).setRadius(...)`.
- Drawer selected folder's expand/collapse icon now drawn at full opacity
  (`onColor(bg, 1f)`); unselected keeps the dimmed foreground colour.
- `adaptiveRippleColor` whitish alpha `0x40` → `0x5C` (≈36%); the single tuning point is
  `BackgroundUtil.adaptiveRippleColor`. Published 202606271558.

## 2026-06-28 — Batch-1 feedback round 3

- Toolbar ripple wasn't centred (origin = touch point) and felt small vs the action item's
  touch area. Added `centered` (origin = view centre) and `peakAlphaOverride` params to
  `GradientRippleDrawable`; toolbar icons now use centred fixed-radius ripples, radius
  20dp → 22dp. Selection-mode (contextual) toolbar switched from system `circularRipple` to a
  centred `GradientRippleDrawable` over a PURE adaptive colour with `peakAlphaOverride` = the
  adaptive colour's own alpha — same feel as normal mode, correct per-tone alpha (white 36% /
  black 16%).
- Drawer selected folder's expand chevron still looked translucent because `ic_dropdown.png`
  is only ~54% alpha (max 138/255) and `ImageView.setColorFilter` (SRC_ATOP) preserves that
  cap. Switched the selected branch to `DisplayUtil.opaqueTintDrawable`, which remaps the
  glyph alpha so the max becomes fully opaque; unselected keeps the source PNG's dimming.
  Published 202606271611.
- Follow-up tweak: `centered` should not snap the origin to centre on press. Reworked it to
  start the ripple at the touch point and migrate the origin toward the view centre as the
  radius fills (origin convergence like the native ripple), so it still spreads from the
  finger but ends centred. Toolbar radius 22dp → 21dp. Published 202606271617.

## 2026-06-28 — App-wide ripple expansion (batch 2: items 6–14)

Re-scoped item 6 per the user: it's the **share** TwoOptionsDialog, not the Detail toolbar
overflow (overflow-menu items explicitly deferred). Added a shared
`GradientRippleDrawable.applyAccentRipple(view, bg, fallbackColor)` for the many flat
confirm/option buttons (keeps utils free of view deps — the factory lives in the views layer).

- 条6 DetailActivity.chooseHowToShareThing → pass `getAccentBackground()` to the share dialog.
- 条7 Reminder suite: `DateTimeDialogFragment` tabs / after-unit button / rec-unit button /
  pick-all → note colour; `DateTimePicker` popup rows (shared by quick-remind popup + both unit
  popups) → note colour; `TimeOfDayRecAdapter` new-reminder + delete-x → note colour;
  `RecurrencePickerAdapter` selected circle + month-end pill → adaptive; confirm button.
- 条8 `AddAttachmentDialogFragment` 4 items → note colour (reads `getAccentBackground()`).
- 条9 `AudioAttachmentAdapter` card (rounded) + 3 icon buttons (oval) → note colour, recycle stop.
- 条10 `ImageAttachmentAdapter` container (rect) + edit/delete (oval) → note colour (new
  `setAccentBackground`, fed from DetailActivity); crop dialog width pills (DetailActivity
  `bindDetailAttachmentAppearanceChoice` / `applyDetailAttachmentAppearanceSelectedPill`):
  unselected note colour, selected adaptive over the filled pill.
- 条11 confirm-button ripple = confirm text colour across Alert / ThreeActionsAlert / Chooser /
  ColorInfo / AttachmentInfo / HabitRecord / HabitDetail / LongText / ThingFolderName /
  ThingBackgroundEditorBottomSheet / PatternLock / DebugUpdate; radio options
  (`RadioChooserAdapter`) ripple = confirm colour. ThingDoing CTA + AudioRecord FAB left as-is
  (not flat confirm buttons).
- 条12 `MoveToThingFolderDialogFragment`: row fill/ripple now use **each row's own folder
  colour** (was the moved item's), selected → adaptive, expand icon → folder colour / adaptive,
  confirm → accent.
- 条13 `ThingsListWidgetConfiguration`: scope rows per-folder colour (Drawer/条12 style); type
  icons (条1 style); status/display pills unselected scope-folder colour, selected adaptive,
  root→white; the two checkbox rows → accent. Added `currentScopeBackground()` +
  `refreshScopeDependentChrome()` so type/status/display ripples follow the selected scope.
- 条14 `ColorPicker.bindAllColor`: "all colours" item unselected → accent gradient ripple,
  selected unchanged (no ripple).

All compile clean; published 202606271711. Not committed.

## 2026-06-28 — Batch-2 feedback round 1 (7 items)

- **Item 7 (core, most important)**: ripples vanished instantly on tap instead of playing out.
  Root causes: a quick release cut the alpha fade-in before it peaked, and the framework's
  `jumpToCurrentState` reset an in-flight fade. Reworked `GradientRippleDrawable`: split alpha
  into in/out animators with a `pendingExit` flag — releasing mid-fade-in defers the fade-out
  until the ripple has reached full alpha, so a single tap always plays fill+fade to
  completion; `jumpToCurrentState` no longer interrupts a running/ pending fade-out; and
  `applyAccentRipple` now reuses an existing instance (updateBackground) so list rebinds
  (notifyDataSetChanged) don't discard a running ripple.
- Item 1: DetailActivity actionbar icons (ib_back + menu items + overflow) → adaptive ripple
  (`adaptiveRippleColor(getAccentBackground())`, centred, fixed 21dp) via new
  `applyDetailToolbarIconRipples()`, called after both `tintMenuIcons` sites. (Overflow popup
  rows stay system default.)
- Item 2: `AudioAttachmentAdapter` 3 buttons → rectangular (fill) ripple, not oval.
- Items 3 & 4: `createDetailAttachmentAppearanceButton` confirm now uses note colour text
  (`applyTextBackground`, gradient-capable) + note ripple; `createDetailAttachmentAppearanceIconButton`
  (Detail video play/pause/stop) and `ThingsActivity.createThingCardCropEditorIconButton` (home
  cover video crop play/pause/stop) → note-colour rectangular ripple.
- Item 5: `MoveToThingFolderDialogFragment` selected-row chevron → `opaqueTintDrawable`
  (ic_dropdown PNG is ~54% alpha; SRC_IN/ATOP can't make it opaque). Icon image/tint moved
  into `bindExpand` (selected = opaque, unselected = dimmed).
- Item 6: search "all colours" ripple was being created but cut instantly (dismiss-on-click +
  item 7); resolved by the core fix. Picked state keeps no ripple per spec.

Published 202606271751. Not committed.

## 2026-06-28 — Batch-2 feedback round 2 (8 items)

- Item 2/3 shape correction: crop-dialog video play/pause/stop reverted to **circular**
  (last round made them rect for audio-parity, but the user wants media-crop controls circular;
  audio buttons stay rect).
- Item 1: `ThingCardAppearanceSourcePicker` option rows → note-colour row ripple
  (`applyAccentRowRipple`).
- Item 2: appearance-panel `mBtThingCardAppearancePreciseCrop` (crop cover) → note-colour pill
  foreground ripple; home crop dialog confirm (`createThingCardCropEditorButton`) → note-colour
  pill foreground ripple.
- Item 3: confirm-button ripple shape — `applyAccentRipple` changed from rect to **pill**
  (cornerRadiusPx -1) so all dialog confirm/action buttons match the cancel pill; added
  `applyAccentRowRipple` (rect) for list rows (`RadioChooserAdapter`). Detail crop dialog
  confirm (`createDetailAttachmentAppearanceButton`) given the standard padding + text size so
  its spacing/width/edge-margin match other dialogs.
- Item 4: share dialog (`TwoOptionsDialogFragment`) option icons → `tintDrawableOpaque`
  (the gradient-but-opaque variant) instead of `tintDrawable` (which preserved the PNG's ~54%
  alpha → faded).
- Item 5: `AboutActivity` toolbar nav/menu/overflow icons → accent gradient (opaque via
  `tintDrawableOpaque`) + accent gradient ripple (`applyAboutToolbarChrome`); version text →
  gradient.
- Item 6: `ThingsActivity.getHomeActionbarIconTintBackground` returns accent at root in **both**
  light and dark mode (was gray in light mode).
- Item 7: Settings/Help toolbar title + nav/overflow icons → whitish
  (`onColor(accent, ON_ALPHA_PRIMARY)`) on the accent-gradient bar.
- Item 8: FAB icons white on accent — `FloatingActionButton.setThingBackgroundWithAdaptiveIcon`
  now judges light/dark by the full `ThingBackground` (accent gradient → dark → white icon/ripple),
  covering the stats share + about support FABs; home root create FAB icon set to `white_86p`;
  Detail start-doing button icon → `white_86p` + light ripple.

Published 202606280207. Not committed.

## 2026-06-28 — Batch-2 feedback round 3 (4 items)

- Re-added `BackgroundUtil.applyToolbarIconRipples(toolbar, radiusDp, factory)` (factory-based,
  no utils→views dep) — now shared by Settings/Help/About.
- Item 1: Help feedback menu icon tinted whitish in `onCreateOptionsMenu`; About toolbar title
  → accent gradient (About's actionbar is on a light `app_chrome_surface`, so gradient — matching
  its icons — not literal white; noted to the user).
- Item 2: Settings + Help toolbar icons → whitish ripple (accent-adaptive GradientRippleDrawable,
  centred, fixed radius) via the shared helper.
- Item 3: `MoveToThingFolderDialogFragment` — title + confirm text colour and confirm ripple now
  follow the **selected target folder** (new `selectedFolderBackground()` + `updateAccentChrome()`
  called from `onCreateView` and `selectRow`). Selected row reworked to mirror Drawer: solid fill
  (`fillDrawable`, was translucent 0x22) + adaptive ripple + contrast (onColor) folder icon / name /
  expand chevron. `bindIcon`/`bindTitle` now take (rowBg, selected).
- Item 4: drawer header image foreground ripple → accent gradient (`GradientRippleDrawable` on the
  inflated `drawer_header` FrameLayout).

Published 202606280425. Not committed.

## 2026-06-28 — Move dialog root colouring + drop passed-in accent

- `MoveToThingFolderDialogFragment.selectedFolderBackground()`: root ("all") → `App.defaultAccentBackground`
  (accent gradient) for title / confirm text / confirm ripple; folder → its own colour. The dialog no
  longer uses the host-supplied `mAccentBackground` at all — `setAccentBackground` kept as a no-op for
  caller compatibility, field removed, overscroll edge colour switched to accent. Published 202606280434.
