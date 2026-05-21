# Followups / Deferred Items

Things that were technically achievable but deferred to a later iteration
because the work was disproportionate to the visual gain. Each entry
notes the current fallback so you know what the app is doing today.

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
