# Followups / Deferred Items

Things that were technically achievable but deferred to a later iteration
because the work was disproportionate to the visual gain. Each entry
notes the current fallback so you know what the app is doing today.

## Detail colour sampling and information

### Device QA for CameraX colour sampling and colour information (deferred 2026-05-28)

**Scope:** DetailActivity's editable ColorPicker "Pick from world" camera
colour-sampling entry, full-width rounded-square CameraX preview dialog with
an internal live colour preview strip, final Use Color commit semantics, and
the colour-information overflow action across create, underway, habit,
finished, and deleted Detail states.

**Current state:** Implementation is present and `:app:assembleDebug` passed
outside the sandbox after Android Studio sync and after the follow-up UI
refinement. The APK was produced at
`app/build/outputs/apk/debug/app-debug.apk` on 2026-05-28 09:01:42.

**Deferred verification:** Install on a real device or emulator and verify the
camera permission prompt, preview orientation, centre sampling marker, dialog
colour preview strip, Use Color text tint, Cancel/Back/outside dismissal
without Detail-side background changes, single undo entry on Use Color, and
colour-info formatting/scrolling for pure and gradient Thing Backgrounds.

**Risk:** CameraX behaviour is device-sensitive. Compile proves API wiring but
not camera selection, frame sampling correctness, preview rotation, or dialog
layout on varied aspect ratios.

### Native review for machine-translated Simplified Chinese colour names (deferred 2026-05-28)

**Scope:** The bundled `color-name-list` 14.38.0 TSV now has a populated `zh`
column for Simplified Chinese translations of the upstream English colour
names.

**Current state:** All 31,902 colour-name rows were translated with Google
Translate on 2026-05-28. The first successful batches used
`translate.googleapis.com`; after that endpoint returned HTTP 429, the
remaining rows were translated through Google Translate's mobile web endpoint
with stable marker parsing. The runtime can now show Chinese colour names in
Chinese locales.

**Deferred verification:** Native-speaker and colour-domain review of machine
translations. Some upstream names are puns, brands, place names, or invented
labels, so Google Translate can produce literal or mixed English/Chinese names.

## Dialog and popup visual QA

### Device visual review for rounded dialog and popup shells (deferred 2026-05-27)

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

## Localization

### Native-speaker review for new app languages (deferred 2026-05-27)

**Scope:** Japanese, Korean, Italian, Spanish, Russian, French, German, Hindi,
and Portuguese string resources, especially long Help/About copy.

**Current state:** Resources compile, visible Google-translation protection
tokens were removed, and the long Help strings were reworked from the
Simplified Chinese source instead of from the failed Google batch output.

**Deferred verification:** Have native speakers review terminology, tone, and
long-form Help readability. Also smoke-test Settings language switching on
device across a few screens whose Activities do not share the common base class.

**Reason deferred:** The current session could verify build/resource validity,
but not human-level localization quality or on-device locale switching visuals.

## AppWidget verification

### Real launcher widget click smoke test (deferred 2026-05-27)

**Scope:** Single Thing widgets with checklist rows, Things List widget row
clicks, header/settings/create buttons, Create widget, Check Upcoming widget,
and direct reminder/habit widget action buttons.

**Current state:** Source-level guards verify the Android 16-sensitive
contracts: collection templates are mutable, all AppWidget Activity
PendingIntents go through the BAL creator-opt-in helper, create actions resolve
the new-thing background at click time, and widget card icons are explicitly
luminance-adaptive. `:app:assembleDebug` also passes.

**Deferred verification:** Install the APK on an emulator or explicitly
approved test device, place fresh widgets on the launcher, and click each
button path while watching logcat for `ActivityTaskManager` background-activity
launch blocks and app receiver/action logs.
Also visually check light and dark Thing backgrounds for every card subtype
(note, reminder, habit, goal, private, checklist, attachment-only, finished /
deleted) and verify long reminder/habit text is visible or ellipsized next to
its icon.

**Reason deferred:** No emulator was attached in the 2026-05-27 session; only a
physical device was listed by ADB, so the agent did not take over the user's
launcher state for widget placement and manual-click verification.

## Color migration

### Real-gradient ripple waveform (deferred 2026-05-18)

**Scope:** `RecurrencePickerAdapter` NORMAL fake-FAB cells (picked +
unpicked ripple) and any other place the ripple itself should render
gradient instead of a single representative int.

**Current state:** Fake-FAB renders a real OVAL `GradientDrawable` for
picked cells; the ripple **water-pulse** uses representative single int
(`RippleDrawable`'s `ColorStateList` strictly takes one int — Android API
limit). Visually: the press feedback is a flat circular waveform in the
gradient's middle colour, not a true gradient pulse.

**Path to real gradient:** Drop `RippleDrawable` for these cells.
Implement an `onTouchListener` (or `View.PRESSED` state listener) that
runs a scale + alpha `ValueAnimator` over a circular `GradientDrawable`
to mimic the ripple effect. Hand-roll cancel/recycle so RecyclerView
scrolling doesn't leave half-finished animations behind.

**Risk:** RecyclerView scroll-perf, animation cancel/cleanup on view
recycle, multi-touch, accessibility (ripple announces "selected"
implicitly via state — custom animation needs explicit
`onTouchExplorationStateChanged` handling).

### Gradient text-selection highlight (deferred 2026-05-18)

**Scope:** Every `EditText` that takes the thing accent —
`InputLayout`, `TimeOfDayRecAdapter`'s edit fields,
`DateTimeDialogFragment.mEtTimeAfter`,
`AudioRecordDialogFragment.mEtFileName`, and anything added later.

**Current state:** `TextView.setHighlightColor(int)` strictly takes a
single int; selection highlight uses `DisplayUtil.getLightColor(representative)`.
On a focused EditText with GRADIENT accent, the select-all background is
a flat lightened representative — not a gradient.

**Path to real gradient:** Subclass `EditText` → `GradientEditText`.
Override `onDraw(Canvas)`: before `super.onDraw`, when
`getSelectionStart() != getSelectionEnd()`, paint the selection path
manually with a `Paint` whose `Shader` is a `LinearGradient` matching
the thing background. Suppress the default highlight via
`setHighlightColor(Color.TRANSPARENT)`. Apply via XML class swap on all
referencing layouts.

**Risk:** Touch hit-testing, IME compatibility, RTL text, multi-line
selection layout maths.

## Kotlin migration

### Global N1 sweep on `set*Listener` / `set*Callback` params (deferred 2026-05-20)

**Scope:** Every Kotlin file translated in Groups 1–12.

**Background:** Plan §3.1 N1 says every Java reference parameter →
Kotlin `T?`. In practice this was missed on listener / callback
setters where the param "looks" non-null but Java callers legitimately
pass `null` to deregister. One concrete crash hit production on
2026-05-20: `ThingsActivity.playNewItemShiningBorder` calls
`mShiningBorder.setOnProgressUpdateListener(null)` to clear the
progress listener before the end-listener runs — Kotlin's intrinsic
non-null check threw `NullPointerException` and crashed the new-thing
animation flow on every newly-created note.

**Fixed in this incident (2026-05-20):** `ShiningBorder.kt` 3 setters,
`PatternLockView.setOnPatternListener`, `Snackbar.setUndoListener` —
all changed to `param: T?`. Scan was scoped to `views/` only.

**Path to global sweep:**

```bash
grep -nE "fun\s+set\w+(Listener|Callback)\s*\(.*:\s*[A-Z]\w*\)" \
     app/src/main/java/com/ywwynm/everythingdone/**/*.kt
```

Any match with a non-`?` parameter is a candidate. For each, check
whether any Java caller passes `null` (deregistration); if yes (or
the original Java had no `@NonNull`), append `?`. Don't widen the
signature blindly — some callbacks are required at construction and
Java callers don't pass null. Inspect the original Java in
`git show <pre-group-commit>:<file>.java` to confirm.

**Risk if left undone:** more silent NPE landmines waiting for the
right Java call site to hit `null`. The original cross-language
N1 audit trail principle holds; widening these is behavior-preserving
(Java already accepts null at runtime — Kotlin's intrinsic check is
the regression, not the param itself).

## UI visual QA

### Button-like shaped ripple device pass (deferred 2026-05-27)

**Scope:** Shaped ripple controls added in the button-like control pass:
compact dialog text buttons, DateTimeDialog tabs and dropdown entry controls,
DateTime recurrence icon/text actions, NoticeableNotification action icons,
Detail quick-remind/checklist controls, Settings help icons, AudioRecord side
icons, and the converted HabitDetail "Got it" button.

**Path:** Install the debug APK and smoke-test light App Chrome, dark App
Chrome, light Thing Background, dark Thing Background, and a gradient Thing
Background. Verify that press feedback is pill/circular, that text/icon visual
positions did not shift, and that full-row/full-card surfaces still use their
original full-row feedback.

**Risk if left undone:** compile verifies the helper wiring, but it does not
prove the ripple mask is visually correct on Material TabLayout internals or on
all shaped foreground hosts.
