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
