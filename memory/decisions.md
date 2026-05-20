# Decisions

## 2026-05-18

### Recurrence picker NORMAL cells: Material FAB → fake-FAB
`RecurrencePickerAdapter`'s `NormalViewHolder` swapped Material FAB
for a fake-FAB (FrameLayout + bg View + RippleDrawable foreground,
mirroring `color_picker_fab.xml`). This lets picked cells carry a real
OVAL `GradientDrawable` instead of being flattened to representative
via `setBackgroundTintList`. The ripple waveform itself remains
single-int representative (Android `RippleDrawable` `ColorStateList`
limit) — assessed acceptable; "real-gradient ripple via custom touch
animation" is a follow-up, not Phase 8 scope.

### Gradient signal propagation into all DateTime/Habit/AudioRecord dialogs
Phase 8 extended into `InputLayout`, `TimeOfDayRecAdapter`,
`RecurrencePickerAdapter` — each grew a `setAccentBackground(ThingBackground)`
entry, with text colours migrated to `BackgroundUtil.applyTextBackground`
and other paths kept on representative int.

### Plan §4.7.4 "FAB tint must be single int" is overruled where fake-FAB is feasible
The COLOR_MIGRATION_PLAN.md classification of FAB as "single-int only"
was too conservative — the fake-FAB pattern bypasses the API
restriction by replacing the widget. Plan §4.7.4 still applies to
genuine Android-API single-int seams (Notification.setColor,
PorterDuff tints, EdgeEffect.setColor, RippleDrawable ColorStateList).

## 2026-05-20

### Kotlin migration: branch `kotlin`, frozen master, behavior-snapshot semantics
After a grilling session, all strategy + rule decisions for the
Java→Kotlin migration of `app/` are captured in
`docs/plans/KOTLIN_MIGRATION_PLAN.md`. Highlights:

- Goal **A** — behavior snapshot, not modernisation. Later refactor
  phase removes `!!`, `@JvmStatic`, etc.
- **Long-lived `kotlin` branch** off master. **Master frozen** for
  the migration's duration.
- **17 groups**, bottom-up dependency order, one commit per group.
- Translation rules: **N1** (every Java reference → `T?`, every
  deref → `!!`, audit trail preserved), **E1** (`===` for reference
  equality, explicit numeric widening), **S-1..S-4** (`@JvmStatic`,
  `const val`/`@JvmField`, `object` for pure-static singletons,
  `companion object { init }` for `static {}`), **A-class
  modernisations** adopted (Elvis, `is`/`as`, `for in`, void
  omission), **B-class** SAM lambdas with guard rule (10 sites
  identified that must keep `object : Listener` form), **C-class
  deferred**, **D-class rejected**.
- **V1+V2+V3** required on every group; **V4** required on groups 1,
  4, 5, 14, 15, 16, 17.
- **V3 closed-loop**: Claude installs APK on emulator-5554, takes
  PNG screenshots, diffs against `memory/screenshots/baseline/`.
  Approach validated on 2026-05-20 — baseline home screenshot
  captured successfully via the PowerShell-safe `screencap` →
  `pull` workflow (see [preferences.md](preferences.md)).

**Don't reintroduce** anything from the C/D-class modernisation
list during the migration phase — those are explicitly deferred or
rejected. They are revisit candidates for the post-migration
refactor only.

### Group 0 surprise: AGP 9.2.1 ships a built-in Kotlin compiler
Trying to apply `org.jetbrains.kotlin.android` (tested 2.1.21 and
2.2.0, both classpath and `plugins {}` forms) fails on this
codebase with `Cannot add extension with name 'kotlin', as there is
an extension already registered with that name`. AGP 9 owns the
`kotlin` DSL extension and ships its own compiler
(`built_in_kotlinc`) that picks up `.kt` files dropped into the
java source set with **zero build.gradle changes**.

Verified by `:app:compileDebugKotlin` task running successfully on
just the `Dummy.kt` file, with `Dummy.class` ending up in the dex
output. Group 0 commit therefore contains a single new file — no
`build.gradle` edits — and the APK installs and cold-starts
identically to baseline `01_home_underway.png`.

**Don't try** `apply plugin: 'kotlin-android'` or
`id 'org.jetbrains.kotlin.android'` while AGP 9.x is in use — it
will fail. kapt and any plugin that requires the standalone Kotlin
Gradle Plugin are also unavailable. See
[KOTLIN_MIGRATION_PLAN.md §7.5](../docs/plans/KOTLIN_MIGRATION_PLAN.md)
for the kapt-replacement decision tree (KSP / defer / downgrade).

### Kotlin migration Group 3 (utils/) — deprecation suppression as file-level

14 utility classes translated cleanly; the only mechanical
challenge was the wave of Java-API deprecation warnings the
Kotlin compiler emits where the original Java already used the
deprecated API (Display.getDefaultDisplay/getRealSize/getSize,
Drawable.setColorFilter(Int, Mode), Notification.PRIORITY_*,
Locale(String, String), Resources.updateConfiguration,
InputMethodManager.SHOW_FORCED, etc.). Group 2's model/ classes
had none of these because Cursor / Parcel are still un-deprecated.

Decision: `@file:Suppress("DEPRECATION")` at the top of any
`.kt` file whose Java original called a since-deprecated API.
This preserves V1's "0 warnings" bar without changing which
API is called (behaviour snapshot), and the post-migration
refactor pass can revisit those call sites with intent.

Documented as new rule in [KOTLIN_MIGRATION_PLAN.md §3.11](../docs/plans/KOTLIN_MIGRATION_PLAN.md).

### Kotlin migration: header date stamp convention (added mid-migration)

Each `.java` → `.kt` translation now stamps
`Translated to Kotlin on YYYY-MM-DD.` immediately after the
existing `Created by … on YYYY/M/D.` Javadoc line. Files without
a `Created by` comment skip the stamp (e.g. ThingBackground was
born post-convention). Group 1+2 backfilled. Plan §3.10.5
captures the rule.

## 2026-05-19

### PopupPicker keeps IME visible — `INPUT_METHOD_NOT_NEEDED`
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

### DetailActivity bottom-bar padding is owned solely by the inset chain
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

### `chainDecorInsetsCallback` skips apply listener during IME animation
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

### `chainDecorInsetsCallback.onEnd` does a stable re-read for IME animations
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

### PopupPicker positioning is always anchor-driven, window-relative
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

### PopupWindow Y offset must compensate for navbar inset
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

### Anchor View lookups must happen at show time, not in `onCreateOptionsMenu`
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
