# 0015 - Timely countdown digits use a filled-outline fixed-N morph

- Status: Accepted
- Date: 2026-07-04
- Feature: `docs/features/timely-digit-typography/`

## Context

The Doing (countdown) screen renders HH:MM:SS with the `timelytextview` module.
Each digit is a single **open** cubic path of exactly 13 control points drawn as a
uniform monoline **stroke**; a digit change morphs by linearly interpolating each
control point to the next digit's corresponding point. The morph is the
component's distinguishing value, but the digits are crude: only 4 cubic
segments, no real counters (0/6/8/9 are self-overlapping wireframes), uniform
stroke, and per-digit overshoot hacks. The user wants beautiful, user-selectable,
multi-style, font-derived digits **while keeping a similar morph animation**.

Two hard constraints shape the solution:
1. The continuous morph requires every digit to expose the **same number of
   interpolation anchors in a consistent order**.
2. Real fonts carry **filled outlines with per-digit topology** — 0/4/6/9 have one
   counter, 8 has two, 1/2/3/5/7 have none — so outlines have different contour
   counts and cannot be naively interpolated.

Spikes (Python): a raster medial-axis **skeleton** produced broken strokes, spurs,
and retracted endpoints and was rejected. A **filled-outline** morph rendered the
real font outlines pixel-faithfully; with flubber-style correspondence its
transitions are good-to-acceptable, with only soft/organic deep-middle frames.

## Decision

Represent each digit as a **fixed-N resampled filled outline**: one outer contour
plus up to two hole contours, every contour arc-length resampled to a fixed point
count, normalized to one shared point budget across all ten digits. Morph by
per-point linear interpolation, with:

- **flubber-style correspondence** — rotate each ring to the cyclic start offset
  that minimizes summed squared travel between the two digits;
- **holes paired top-first**; an absent counter seeded as a **zero-area point** at
  the counterpart's counter centroid, so counters open/close in place;
- rendering as a filled `Path` with the **even-odd** rule (with the option to
  stroke the contours instead of filling).

Glyph point arrays are generated **offline** (Python: fontTools + matplotlib
`TextPath`) from embeddable OFL/Apache monoline fonts and shipped as data;
the Android runtime keeps the existing linear-interpolation architecture,
extended to multiple contours.

## Alternatives considered

- **Monoline hand-authored skeleton (Option B).** Crispest morph, closest to the
  old animation, but monoline-only (cannot deliver "beautiful, varied fonts"),
  requires hand-drawing every glyph, and is least faithful. Rejected as the
  primary path; remains the only way to reproduce the exact old single-thin-line
  look if ever wanted.
- **MSDF GPU field morph (Option C).** Faithful filled glyphs with topology-free
  morphing (holes native), but needs AGSL/`RuntimeShader` (Android 13+/API 33),
  has corner artifacts, colour-unsafe field blending for a true morph, and must be
  embedded in an otherwise View/Canvas screen. Deferred as a premium option.
- **Raw raster medial-axis skeleton.** Rejected — inherently lossy (spurs, broken
  strokes, retracted endpoints).

## Consequences

- Digits are faithful to real fonts across styles, with correct counters; the
  "ugly digits" problem is fully solved with zero extraction artifacts.
- Hole appearance/disappearance (e.g. 1↔0, 8↔9) is handled by the zero-area seed.
- Works on all API levels and reuses the current point-array + linear-interpolation
  design.
- Filled-shape morph middles are **softer/less crisp** than the old monoline
  morph; a few worst-case transitions may need per-transition offset tuning or a
  subtle cross-fade blended with the shape morph.
- Introduces an **offline glyph-data pipeline** (font → normalized contour arrays).
- The exact original single-thin-line monoline look is not reproduced (that is
  Option B's territory); Option A can render solid-filled or contour-outlined.
