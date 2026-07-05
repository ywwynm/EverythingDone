# Decisions — Timely Digit Typography

## 2026-07-04 - Preserve the continuous skeleton morph (Option A)

**Context.** The current `timelytextview` draws each digit as a single open
13-point cubic path, stroked monoline, and animates digit changes by linearly
interpolating each control point to the next digit's corresponding point. The
digits look crude (4 segments, no counters, uniform stroke). The user wants
prettier, multi-style, font-derived digits but explicitly wants to keep a
similar change animation.

**Decision.** Keep the continuous point-to-point **skeleton morph** as the
transition. Every digit, within a given style, shares one fixed-count,
consistently-ordered anchor skeleton so interpolation stays clean. Rejected:
Option B (switch to filled real-font outlines with a non-morph transition such
as draw-on / cross-fade / odometer) and Option C (filled outlines with a
morph-where-possible, cross-fade-on-topology-mismatch hybrid).

**Rationale.** The continuous morph is the component's distinguishing value; a
merely pretty static digit is already obtainable with a `TextView` and a font.
Any filled-outline approach hits the counter/hole topology mismatch (0,4,6,8,9
have counters; 1,2,3,5,7 do not) and must degrade to cross-fades on those
transitions, weakening the very effect being preserved.

**Consequences.**
- Glyph geometry is skeleton-based: a continuous stroked path whose closed loops
  create counters. Styles derive from skeletons, not arbitrary filled outlines.
- Complex serif / high-contrast (Didone) faces that require multi-contour fills
  cannot be reproduced faithfully.
- Weight/contrast, if wanted, must come from **variable stroke width along the
  skeleton** as a render choice (open question).
- Skeleton source & authoring pipeline is the next open question — real font
  files carry filled outlines, not centrelines.

## 2026-07-04 - Initial style set: three monoline styles

**Decision.** Ship three styles first, all monoline and morph-compatible under
Option A:
- **Geometric** — reference Poppins / Montserrat (round, full, refined).
- **Rounded** — reference Comfortaa / Nunito (soft, round terminals).
- **Technical / segmented** — reference Orbitron / JetBrains Mono (squared,
  timer/tech feel).

No non-monoline (serif / high-contrast Didone / slab) style is in scope. The
user accepted "inspired-by, regularized-for-morph" fidelity rather than a
pixel-faithful reproduction of any face. The exact reference font per style will
be chosen from generated Python previews. The set is extensible later by adding
more skeleton tables.

**Pipeline note (research-confirmed).** A genuinely monoline font is required so
a clean centreline can be recovered; candidates above are all OFL/Apache and
embeddable. Weight/contrast is a render-time stroke-width choice, not taken from
the font. Preview stack: fontTools (outline) + Pillow/matplotlib (render) +
scikit-image (medial-axis skeleton) + numpy (arc-length resampling).

## 2026-07-04 - Raw medial-axis skeleton rejected as the representation

**Context.** The first preview spike extracted centrelines by raster
skeletonization (`skimage.skeletonize`). Reviewing the output, the user rejected
it: the skeletons show broken/discontinuous strokes, spurs (毛刺) at junctions,
and retracted endpoints (strokes stopping short of the true tip). Raw raster
medial axis is inherently lossy this way, and even a cleaned centreline still
has per-digit topology (4 branches, 8 crosses, 0/6/9 loop) that does not
directly satisfy the single shared-order path the morph needs.

**Decision (direction, not final).** Do NOT ship a raw-skeleton representation.
Pursue a complete solution that keeps the continuous morph but avoids extraction
artifacts. Leading candidate: **fixed-N filled-outline morph** — normalize every
digit to `outer contour + up to 2 hole contours`, resample each by arc length to
a fixed point count with a consistent start point, and collapse absent holes to
a point so a counter grows/shrinks smoothly during the morph. This uses the
font's real filled outlines (zero extraction loss) and still morphs, including
clean hole appearance/disappearance. Alternatives under evaluation: hand-authored
high-resolution single-stroke skeletons (monoline only, no auto-extraction), and
MSDF GPU field interpolation (most robust topology handling, highest cost).
Prototype + research in progress before locking this.

## 2026-07-04 - Locked: filled-outline fixed-N morph (Option A) — see ADR-0015

**Decision.** After the v2 prototype (flubber-style best-rotation-offset
correspondence) cleaned up the previously-broken transitions, the user chose
**Option A: filled-outline fixed-N morph**. Each digit = outer contour + up to 2
hole contours, arc-length resampled to a shared fixed point budget; morph by
per-point lerp with best-offset matching; absent counters seeded as zero-area
points so holes open/close in place; rendered filled (even-odd) with an option to
stroke the contours. Point arrays generated offline from monoline OFL fonts.
Rejected: Option B (monoline hand-authored skeleton — crisper morph but
monoline-only and manual) and Option C (MSDF GPU — premium, AGSL/API 33+,
deferred). Full rationale and consequences in
[ADR-0015](../../adr/0015-timely-countdown-digits-filled-outline-morph.md).

**Tentative reference fonts (font choice deemed minor by the user, overridable
from previews):** Geometric = Poppins, Rounded = Comfortaa, Technical = Orbitron.

**Still open:** style-selection UX and default; stroke/weight & the
hour/minute/second hierarchy; tabular vs proportional digit advance (clock
jitter); animation timing/easing and which transitions need per-pair tuning; the
offline data format and the runtime `TimelyView` rewrite.

## 2026-07-04 - Support both fill and outline; serif/artistic styles unlocked

**Rendering.** Support BOTH **solid fill** (default) and **contour outline** as a
selectable look. Same contour data; the render mode is a cheap toggle.

**Serif / high-contrast / slab / artistic fonts are now IN SCOPE.** The earlier
"no non-monoline styles" note (above) was conditional on the rejected skeleton
approach. Option A morphs real filled outlines, so any font's topology —
including serifs, which do not change hole counts — is handled by the same
pipeline. **This supersedes that constraint.** The user explicitly asked for more
styles and a more artistic feel.

**Style menu expands** to a larger, user-selectable set spanning sans
(geometric / rounded / technical) and serif/artistic (Didone high-contrast,
elegant serif, slab, display). Final set chosen from previews.

**Trade-off to surface honestly.** Ornate / high-contrast faces look stunning
statically, but their morph middles are more dramatic/organic (serifs and
thick-thin regions appear/disappear as bulges). For this feature that drama may
be desirable ("artistic feel"), but it is shown honestly in previews so the user
picks with eyes open.

## 2026-07-04 - Final style menu (all except Inter) + three review constraints

**Final style menu (user: "all except Inter").** ~11 user-selectable styles:
- Sans: Geometric (Poppins), Rounded (Comfortaa), Technical (Orbitron)
- Serif/Artistic: Playfair Display (Didone), Abril Fatface (display Didone),
  Cormorant Garamond (elegant thin), Zilla Slab (slab), Lora (readable),
  DM Serif Display (high-contrast)
- Mono: JetBrains Mono
- Script (experimental, wild morph): Pacifico and/or Dancing Script
- Excluded: Inter.

**Constraint 1 — resolve overlapping contours (extraction bug).** Playfair "4"
showed a spurious hole at the stem/crossbar overlap. Cause: the naive "largest
contour = outer, all others = holes" classification mislabels an overlapping
FILLED component as a hole. Fix: resolve each glyph with a boolean union by
winding sign (same-winding contours union as fill; opposite-winding subtract as
holes) so only true counters remain. Applies to every font, done offline.

**Constraint 2 — hour/minute/second weight hierarchy.** The current design gives
hour/minute/second different stroke widths plus opacity 86/76/66%. In the filled
model, reproduce the hierarchy with a WEIGHT ladder (hour heavier → second
lighter) for variable/multi-weight fonts, plus the existing opacity ladder.
Single-weight display fonts (Abril Fatface, DM Serif Display) fall back to
size + opacity. The offline pipeline generates the needed weights per style.

**Constraint 3 — consistent optical size & position across styles.** Every style
must render digits at the same optical height and the same vertical/horizontal
position box, so switching styles never shifts or resizes the readout. Add a
per-font calibration (scale + baseline/centre offset to a shared target metric,
with small manual nudges) in the offline pipeline.

## 2026-07-04 - h/m/s hierarchy: fixed weight ladder + opacity (Option A)

Reproduce the hour/minute/second prominence with a **fixed weight ladder** (hour
heaviest → second lightest) via the variable fonts' wght axis, plus the existing
**opacity ladder** (86/76/66 %). Single-weight fonts (Abril Fatface, DM Serif
Display, Pacifico) fall back to size + opacity. Values are **fixed, not
user-adjustable**. The offline pipeline generates each style at the needed
hour/minute/second weights. Within a position the weight is constant, so morphs
stay same-weight.

## 2026-07-04 - Style-selection UX (confirmed)

- **Entry.** A new row "Countdown Digit Style" in Settings → UI group, next to the
  existing "create animation style" row (`rl_create_animation_style_as_bt` in
  `SettingsActivity.kt`).
- **Chooser dialog.** A `[Fill | Outline]` toggle at the top, then the styles
  listed **one per row**, each previewing **`01:29:36`** (full HH:MM:SS so the
  hour/minute/second weight ladder is visible in the preview). The selected row
  may animate a morph demo.
- **Storage.** New `Def.kt` keys (`KEY_DOING_DIGIT_STYLE` + a render-mode key),
  cached in `FrequentSettings`; `DoingActivity` reads them at render time.
- **Default.** Geometric (Poppins), Fill.
- **Menu size.** 12 styles (both Pacifico and Dancing Script included as scripts).

## 2026-07-05 - Wider weight ladder; 11 styles shipped; Cormorant deferred

- The hour/minute/second thickness difference read too weakly. Widened the ladder
  via a single unified pipeline knob `WT` (h≈900 / m≈450 / s≈200). Variable fonts
  are instanced at those weights; Poppins uses Black/Regular/ExtraLight statics,
  Zilla Slab uses Bold/Regular/Light; single-weight fonts (Abril, DM Serif,
  Pacifico) keep opacity-only hierarchy. Values remain fixed (not user-adjustable).
- Shipped 11 styles + the settings chooser. **Cormorant Garamond deferred**: its
  default oldstyle figures are uneven-height and unsuitable for a clock; it needs
  lining figures (`lnum`), which the matplotlib extraction path does not apply —
  a follow-up (likely via uharfbuzz shaping or a font swap).
- Preferences stored in the named `EverythingDone_preferences` (keys in
  `Def.Meta`), not `FrequentSettings`.

## 2026-07-05 - Hierarchy = size ladder (primary); tabular-advance spacing

On-device the weight+opacity hierarchy was nearly invisible: weight axes are
limited and, crucially, the units are spatially separated across the screen so a
thickness difference needs side-by-side comparison to read. Research (visual
hierarchy + watch-face practice) says **size is the most perceptible, universal
cue and survives spatial separation**.

- **h/m/s hierarchy is now a SIZE ladder** (hour 1.0, minute 0.84, second 0.68),
  **baseline-aligned** (tops step down, bottoms line up). Weight + opacity remain
  only as light secondary reinforcement. This works for every font, including
  single-weight ones. Ratios are easily tunable in `DoingActivity`/`renderClock`.
- **Digit spacing is now a tabular cell per font**: cell width = the style's
  `advance` (widest 0–9 digit + side-bearing pad) × the size factor, glyph centred;
  the old hardcoded negative margins are removed and `TimelyView`'s square
  `onMeasure` dropped. Wide faces (Orbitron) no longer collide and the row never
  jitters when digits change (standard tabular-figures fix).

## 2026-07-05 - Reverted size ladder → stroke-weight hierarchy (same size)

The user rejected the size ladder: **h/m/s must stay the same size** and be
distinguished by **thickness**. The app's original design already did exactly
this via per-level stroke width (28/16/8) and it read clearly; the filled
redesign lost it by relying on font weight axes (limited range, and none for
single-weight fonts). Restored: **synthesize per-unit boldness on the filled
glyph** via `Paint.FILL_AND_STROKE` with a per-unit stroke width (hour 0.08,
minute 0.035, second 0.0 of glyph height). Digits stay identical size; it works
for every font including single-weight (Abril Fatface, Pacifico); the opacity
ladder stays as secondary reinforcement. Ratios are tunable in
`DoingActivity`/`renderClock`. The advance-based tabular spacing fix is retained.

