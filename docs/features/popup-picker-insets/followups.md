# Popup Picker And Insets Followups

Migrated from global `memory/followups.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Color migration - Gradient text-selection highlight (deferred 2026-05-18)

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
