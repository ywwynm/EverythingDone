# Popup Picker And Insets Decisions

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-06-07 - Popup picker placement aligns surface pivots to intended anchor points

PopupPicker subclasses should position from an explicit, picker-specific anchor
point in window coordinates, then align the visible popup surface's transition
pivot to that point. This preserves the visual "emerges from the tapped
control" relationship without falling back to display-global coordinates.

`PopupPicker` owns shared helpers for measuring the current popup content and
installing content-surface scale transitions. Subclasses own their anchor point
and transition pivot because those values mirror the intended visual origin:
- `ColorPicker`: top-right corner pinned to the anchor view's top-right corner,
  with its visible rounded content surface scaling from `pivotX = width`,
  `pivotY = 0`.
- `DateTimePicker` quick-remind (`AFTER_TIME`): left-bottom corner pinned to
  the anchor TextView's left edge and vertical centre, with its visible rounded
  content surface scaling from `pivotX = 0`, `pivotY = height`.
- `DateTimePicker` time-type popups: left-top corner pinned to the anchor
  TextView's left-top corner, with its visible rounded content surface scaling
  from `pivotX = 0`, `pivotY = 0`.
- `ThingCardAppearanceSourcePicker`: right-bottom corner pinned to the anchor
  TextView's right-bottom corner, with its visible rounded content surface
  scaling from `pivotX = width`, `pivotY = height`.

This supersedes the earlier quick-remind rule that the popup bottom should land
on the anchor vertical centre as a DateTimePicker-only special case. That
relationship is now part of the common DateTimePicker visual origin: popup
bottom = anchor vertical centre, popup left = anchor left. Bottom-gravity
DateTimePicker popups still compensate for `navBottom`, but the compensated
bottom coordinate is the last step after first computing the desired popup top.

Follow-up visual testing found ColorPicker's perceived opening point still sat
slightly up-left of the trigger icon. The likely cause is that toolbar colour
actions usually sit near the window's top-right corner; positioning by an
interior pivot (`86%, 10%`) can compute a popup region that wants to exceed the
window bounds, after which clamping separates the perceived animation origin
from the tapped icon. ColorPicker therefore uses an explicit top-right-corner
model instead of a view-size-relative visual correction.

The top-right-corner model should use `PopupWindow.showAsDropDown(anchor, ...)`
for valid anchors, not a manual `showAtLocation(...)` offset. Android's
`showAsDropDown` path converts `anchor.getLocationOnScreen()` back into
app-window coordinates by subtracting the app root's screen location before it
sets `WindowManager.LayoutParams.x/y`. Reimplementing that conversion with
`getLocationInWindow()` plus `showAtLocation()` can drift in edge-to-edge or
multi-window cases. For ColorPicker's desired geometry, pass explicit measured
popup width/height to `PopupWindow`, then use `xoff = 0`,
`yoff = -anchor.height`, and `Gravity.END`; that makes the popup's top-right
corner land on the anchor view's top-right corner using the platform's
coordinate conversion.

Do not use ColorPicker's `PopupWindow` window animation for anchor-origin
surface motion. Android applies `PopupWindow.setAnimationStyle(...)` as window
enter/exit animation through `WindowManager.LayoutParams.windowAnimations`, and
the popup may be wrapped in `PopupDecorView` / `PopupBackgroundView` with
background/elevation surface insets. That makes percentage pivots describe the
window/decor surface rather than the visible rounded picker surface, so visual
origin can drift from the intended anchor corner.

ColorPicker should instead disable window animation (`popupAnimStyle = 0`),
clear the PopupWindow background, keep the rounded elevated background on
`mContentView`, and install `enterTransition` / `exitTransition` on the
PopupWindow. The transition targets the visible content surface, so the popup
surface itself animates on both show and dismiss while `showAsDropDown(...)`
continues to own final geometry and anchor tracking. The old
`R.style.ColorPickerAnimation` and `color_picker_show` / `color_picker_hide`
resources were removed to avoid reintroducing window-animation pivot drift.

DateTimePicker follows the same content-surface transition rule. It no longer
uses `QuickRemindPickerAnimation` or `TimeTypePickerAnimation`; both animation
styles and their resource files were removed once all local users moved to
content-surface transitions. Quick-remind still unfolds from the left side of
the label and grows rightward/upward from its left-bottom surface corner.
Time-type popups unfold from the label's left-top corner and grow
rightward/downward from the surface's left-top corner.

ThingCardAppearanceSourcePicker also follows the content-surface transition
rule. It unfolds from the source TextView's right-bottom corner and grows
leftward/upward from the popup surface's right-bottom corner. Its first and last
rows carry the same 8dp outer top/bottom margins used by quick-remind-style
picker rows, so the list content breathes inside the rounded popup shell.

Content-surface popups must not clamp their origin to the parent view's current
height when the product requirement is an exact anchor point. DateTimeDialog's
"after" tab can be shorter than the time-type popup; if DateTimePicker clamps
the popup top to `mParent.height - popupHeight`, the popup origin moves above
the tapped pill. Use exact anchor offsets and set
`PopupWindow.isClippingEnabled = false` for content-surface transitions so
short dialog tabs can let the popup extend beyond their local content height
without changing the dialog size.

`clipToOutline` does not shrink an anchor view's geometry. For pill-ripple
TextViews, the pill touch area is still the TextView's measured rectangle; the
outline only shapes drawing and ripple clipping. Therefore source-picker
right-bottom anchoring should use the TextView's measured right/bottom directly
and avoid extra screen-margin clamps unless a separate product rule explicitly
prefers edge padding over exact anchoring.

When a content-surface popup has `PopupWindow.isClippingEnabled = false`, avoid
using `Gravity.BOTTOM` plus nav-bar compensation for exact right/bottom source
picker anchoring. The unclipped popup window can resolve bottom gravity against
the full parent/window bottom, so subtracting `navBottom` can leave the popup
surface visually below the anchor by roughly one system-bar inset. For
ThingCardAppearanceSourcePicker, use top-gravity coordinates instead:
`x = anchorRight - popupWidth`, `y = anchorBottom - popupHeight`. This makes the
right-bottom relationship direct and independent of bottom-gravity inset
semantics.

## 2026-05-29 - Thing-owned local controls use contrast foregrounds

When a local control paints its own background with a Thing Background or Thing
accent, any text or icon drawn directly on that control must use the same
lightness-based foreground rule as Thing cards. Use `BackgroundUtil.onColor(...)`
for text on the painted surface, and use Thing-owned ripple colours for press
feedback. This applies to compact dialog action cards such as ThingDoingDialog's
"Start doing" button and to picked recurrence cells in DateTimeDialog.

## 2026-05-27 - Button-like control ripple work excludes full-row and full-card surfaces

Button-like control ripple shaping should target local command controls: compact
text actions, icon+text actions, and icon-only actions that behave like buttons
even when they are built from plain views. Full-row and full-card clickable
surfaces are not part of this change and should keep their current interaction
surface unless handled by a separate design pass. Full-width dialog action rows
count as full-row surfaces and are excluded too.

Compact text buttons on dialogs and dialog-like surfaces, including affirmative
buttons such as "Got it" as well as cancel/confirm buttons, are included in the
button-like control ripple shaping pass.

Full-row affirmative "Got it" buttons in dialogs are treated as layout debt, not
as intentionally full-row action surfaces. Convert them to the same bottom-end
compact text-button form used by most dialogs, then apply the pill ripple.

DateTimeDialog's TabLayout tabs are included only for touch-feedback shaping.
Keep their existing selected-state semantics: accent or gradient tab text and
the bottom indicator stay unchanged; only the pressed ripple should become a
pill-shaped rounded rectangle.

DateTimePicker popup entry controls, such as the visible time-unit TextView with
its dropdown icon, are included. The full-row selection items inside the popup
are excluded and keep their current full-row feedback.

## 2026-05-27 - Dialog and popup corner radius has its own App Chrome token

Custom App Chrome dialogs and popup pickers should use a dedicated corner-radius
token, `@dimen/app_chrome_dialog_popup_corner_radius`, currently set to `16dp`
for visual review. This keeps dialog and popup shape adjustable without
changing the home Thing card radius.

## 2026-05-18 - Gradient signal propagation into all DateTime/Habit/AudioRecord dialogs
Phase 8 extended into `InputLayout`, `TimeOfDayRecAdapter`,
`RecurrencePickerAdapter` — each grew a `setAccentBackground(ThingBackground)`
entry, with text colours migrated to `BackgroundUtil.applyTextBackground`
and other paths kept on representative int.

## 2026-05-19 - PopupPicker keeps IME visible — `INPUT_METHOD_NOT_NEEDED`
Pre-edge-to-edge, opening a `ColorPicker` / `DateTimePicker` while an
EditText was focused left the IME up and the popup floated above it.
On the edge-to-edge build that legacy behaviour broke: `setFocusable(true)`
made the popup steal window focus, IME was forced to hide, and the
`applyBottomInsetAsPadding(mFlRoot)` chain re-fired mid-show — the
bottom bar dropped, the popup's anchor shifted, and the popup got
auto-dismissed while the bottom bar stayed stuck at the pre-hide
`ime.bottom` value (no inset re-dispatch reached the activity because
the popup's own window owned the IME focus on its way down).

Restored the legacy UX by setting
`mPopupWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED)`
in the `PopupPicker` constructor. That flag tells the framework
"popup doesn't participate in IME, so don't change IME visibility
because of it" — IME stays open, no inset chain perturbation, no
flicker, no auto-dismiss. `setFocusable(true)` remains so BACK still
dismisses the popup.

**Don't reintroduce** `hideKeyboardBeforeShow()` / pre-emptive IME hide
in pickers — coexistence is the desired behaviour, not a workaround.

## 2026-05-19 - DetailActivity bottom-bar padding is owned solely by the inset chain
`DetailActivity#setEvents` had a legacy `KeyboardUtil.addKeyboardCallback`
that did `mFlRoot.setPadding(0,0,0,keyboardHeight)` on IME show and
`mFlRoot.setPadding(0,0,0,0)` on IME hide. With edge-to-edge +
`applyBottomInsetAsPadding(mFlRoot)`, those calls now collide with the
chain: any IME-up → DialogFragment-show transition triggers
`onKeyboardHide`, which writes `padding=0` on top of the chain's
`bars.bottom` value — and the bottom bar ends up under the gesture /
3-button nav bar.

Stripped both `setPadding` calls. The callback now only does its
scroll-to-cursor work on `onKeyboardShow`; padding is entirely
chain-managed.

**Don't reintroduce** any direct `mFlRoot.setPadding(...)` from
keyboard callbacks — IME ↔ navbar geometry must stay in
`applyBottomInsetAsPadding`'s hands so the two cases compose.

## 2026-05-19 - `chainDecorInsetsCallback` skips apply listener during IME animation
The decor-view dispatch order for an animated IME show is:
1. `onPrepare(animation)`
2. `onApplyWindowInsetsListener` fires with the **target** insets
   (IME at full height) — _before_ the animation starts
3. `onStart`
4. `onProgress` every frame with interpolated insets
5. `onEnd`

Applying the chain in step 2 snapped padding to the final IME-up
value; then `onProgress` re-applied the interpolated value starting
near the IME-down state → visible "flash to final, jump back,
animate up" flicker every time the keyboard opened.

Fix: shared `imeAnimating` flag set in `onPrepare` and cleared in
`onEnd` (gated on `WindowInsetsCompat.Type.ime()`). While true, the
`setOnApplyWindowInsetsListener` skips the chain, leaving the
animation purely driven by `onProgress`. Non-IME insets (rotation,
multi-window, gesture-nav entering / leaving) still flow through
the apply listener as before.

**Don't bypass** the flag in the apply listener for "always reset
on a stable dispatch" — that brings the flicker back.

## 2026-05-19 - `chainDecorInsetsCallback.onEnd` does a stable re-read for IME animations
Once `imeAnimating[0]` is cleared in `onEnd`, the callback explicitly
re-fires the chain with `ViewCompat.getRootWindowInsets(decor)`.

Why: in multi-window mode, the platform temporarily folds the navbar
inset into the IME envelope while the focused half resizes — so
`onProgress`'s last frame reports `bars.bottom = 0`. After the
animation settles, the post-animation stable insets carry the
correct `bars.bottom` (multi-window nav handle), but the platform
does not reliably re-dispatch them; and even if it did, the apply
listener still skips them while `imeAnimating[0]` is true. The
result without this fix: bottom bar loses its multi-window nav
handle accommodation forever after the first IME open / close.

Implementation note: uses `getRootWindowInsets` (local read) rather
than `decor.requestApplyInsets()` so the recovery doesn't trigger a
layout pass that would collide with concurrent DialogFragment show
timing (the original reason `requestApplyInsets` was removed from
`onEnd`).

## 2026-05-19 - PopupPicker positioning is always anchor-driven, window-relative
`PopupPicker.mAnchor` is a `View` (was `Object`). Subclasses compute
popup x/y from `mAnchor.getLocationInWindow()` and `mParent.getWidth()`
/ `mParent.getHeight()` — never from `getLocationOnScreen()` +
`getDisplaySize()`. Reason: `showAtLocation`'s gravity reference is
the popup's parent window (= the activity window). In multi-window,
that window is one half of the display, but `getLocationOnScreen`
and `getDisplaySize` return display-global coordinates — mixing the
two computes an xOffset that throws the popup into the other split's
region (search popup drifts left in left-half multi-window; Detail
ColorPicker drifts right in right-half).

ColorPicker's tint behaviour (recolour an icon Drawable on each pick)
moved off `mAnchor` and onto a separate `setTintTarget(Drawable)`
slot. `mAnchor` is strictly the View we position against.

**Y placement rule for bottom-bar pickers** (DateTimePicker AFTER_TIME):
popup bottom lands at `anchor vertical centre`, i.e.
`Y_offset = mParent.getHeight() - anchorTopInWindow - anchor.height / 2`.
That matches the legacy non-edge-to-edge visual (where the old formula
`displayHeight - pos[1] - anchor.height` accidentally landed there
because `displayHeight - mParent.getHeight() ≈ navbar`); encoding it
explicitly makes the position hold across edge-to-edge, multi-window,
and gesture-nav layouts where that accidental relationship no longer
holds.

**Don't** reintroduce `displayHeight - …` based offsets — they only
match the legacy visual by coincidence and break the moment the
window starts spanning the navbar / cutout.

## 2026-05-19 - PopupWindow Y offset must compensate for navbar inset
`PopupWindow` is constructed without `FLAG_LAYOUT_IN_SCREEN`, so
`WindowManager` insets the popup's own window by the bottom system
bars. This means `Gravity.BOTTOM`'s reference "popup-window-bottom"
sits at `mParent.height - navBottom` (in screen coords), **not** at
`mParent.height`.

For `Gravity.BOTTOM` placements that want a specific anchor
relationship (e.g. popup bottom = anchor centre), the Y offset is:

```
Y = mParent.getHeight() - navBottom - anchorTopInWindow - <anchor adjustment>
```

where `navBottom = ViewCompat.getRootWindowInsets(mParent).getInsets(
systemBars() | displayCutout()).bottom`.

Legacy non-edge-to-edge windows had `mParent.getHeight() ==
display.height - navbar` already, so the navbar fell out of the math
by accident and the old `displayHeight - pos[1] - anchor.height`
formula landed in the right place. Edge-to-edge `mParent.getHeight()
== display.height` exposes the gap — we have to compensate
explicitly.

This applies only to bottom-gravity popups. Top-gravity popups
(`Gravity.TOP`, e.g. ColorPicker) reference window top, which is the
same regardless of bottom-bar insets, so no compensation needed.

## 2026-05-19 - Anchor View lookups must happen at show time, not in `onCreateOptionsMenu`
Looking up a toolbar action menu item via `findViewById(id)` inside
`onCreateOptionsMenu` returned a View whose location wasn't yet
final under multi-window — the menu inflate completed but the
ActionMenuItemView hadn't been measured / laid out yet. Caching that
reference and reading `getLocationInWindow` later still returned
stale (0,0) coordinates because the View was never properly attached
in that path.

Fix: look up `findViewById(R.id.act_…)` inside
`onOptionsItemSelected`, right before `mColorPicker.show()`. The
menu item is fully attached by then (we're literally handling a tap
on it). DetailActivity already worked correctly because it followed
this pattern from day one.

## 2026-05-26 - Detail quick-remind and checklist measurement corrections

Quick-remind picker recreation must preserve a valid picked index even when
the old PopupWindow was never opened after the default "15 minutes later"
selection was installed. Detail now infers the picked index from `rhParams`
when the old picker reports `-1`, and `DateTimePicker` gives AFTER_TIME
pickers the same default selected row internally.

Detail checklist RecyclerView stays non-scrollable; the outer
`NestedScrollView` owns the scrolling. Do not measure checklist height by
creating/binding RecyclerView item views inside `LayoutManager.onMeasure()`;
that steals focus during editing and can race item removal.

Also do not estimate offscreen checklist row heights. The legacy Java code only
used laid-out holder heights plus fixed fallbacks for separator/count rows, and
that relied on unstable RecyclerView layout state. Collapsed finished checklist
items should instead be represented by adapter visibility: while collapsed, the
adapter exposes only unfinished rows plus the add/separator/finished-count rows;
while expanded, it exposes the complete item list. The RecyclerView height stays
`WRAP_CONTENT` and relies on RecyclerView AutoMeasure.
