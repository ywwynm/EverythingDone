# Timely Digit Typography

Redesign the animated countdown digits shown on the Doing screen (`DoingActivity`,
HH:MM:SS) — currently provided by the `timelytextview` module — so they look like
beautiful, user-selectable font styles while preserving the signature continuous
morph animation.

## Current implementation (baseline)

- `timelytextview/` — each digit is a single **open** cubic Bézier path of exactly
  **13 control points** (1 `moveTo` + 4 `cubicTo`), drawn with `Paint.Style.STROKE`
  at uniform width (`TimelyView.java`).
- A digit change morphs by **per-control-point linear interpolation** from the old
  digit to the new digit's corresponding point (`TimelyEvaluator.java`); the `Null`
  (-1) state collapses all points to the centre (0.5, 0.5).
- Sole consumer: `DoingActivity` (`playTimelyAnimation`), 6 instances forming
  HH:MM:SS, white at 86/76/66 % opacity over a blurred background.

## Known flaws in the current digits

1. Only 4 cubic segments — shapes are coarse approximations (`1` is a line with 8
   coincident points; `4` fakes corners with duplicated points).
2. Single open stroke, no real counters — `0/6/8/9` read as self-overlapping
   wireframe rather than typeforms; `8` is a crossing line, not two counters.
3. Uniform stroke width — no weight/contrast, no typographic personality.
4. Overshoot hacks (y up to 1.02–1.14) patch baseline/descender inconsistently.

## Status

**Design complete; implementation plan ready** (2026-07-04). Architecture in
[ADR-0015](../../adr/0015-timely-countdown-digits-filled-outline-morph.md);
full trail in [decisions.md](decisions.md) and [analysis.md](analysis.md);
build steps in [plan.md](plan.md). Python spikes under [`tools/`](tools/)
(preview, filled-outline morph, overlap fix, chooser mock).

Chosen approach: filled-outline fixed-N morph (Option A), 12 user-selectable
styles, fill or outline render, hour/minute/second weight+opacity hierarchy.
