# Sessions — Timely Digit Typography

## 2026-07-04 — Design interview (grill-with-docs) + Python spikes

Redesigned the Doing-screen countdown digits (the `timelytextview` module).
Established the current mechanism (13-point open cubic path, monoline stroke,
per-control-point linear-interpolation morph) and its flaws (crude shapes, no
real counters, uniform stroke).

Decisions reached (all 2026-07-04, see [decisions.md](decisions.md) +
[ADR-0015](../../adr/0015-timely-countdown-digits-filled-outline-morph.md)):
- Keep the continuous morph (Option A) over a monoline skeleton (B) or MSDF (C).
- Represent digits as **filled outlines** (outer + up to 2 holes), morph by
  per-point lerp with flubber-style best-offset correspondence + hole-from-point.
- 12 user-selectable styles (sans + serif/artistic + mono + script; Inter out);
  serif unlocked because Option A morphs real filled outlines.
- Both fill and outline render modes; h/m/s weight + opacity hierarchy (fixed);
  consistent optical size/position; mandatory overlap resolution; lining figures.
- Settings chooser next to "create animation style"; preview `01:29:36`, one row
  per style; default Geometric/Poppins/Fill.

Python spikes (conda env `everythingdone`, `tools/`): rejected raster
skeletonization; proved filled-outline morph incl. hole open/close; fixed the
Playfair "4" false hole via winding-correct overlap union (found the naive
classification is wrong for many glyphs across fonts); produced the 12-style
chooser mock validating weight ladder + consistent sizing + overlap fix.

Outcome: [plan.md](plan.md) written. Next: build the offline pipeline
(`generate_glyph_data.py` with skia-pathops) and the runtime rewrite, starting
with a Poppins tracer bullet. No app code changed yet.

## 2026-07-04/05 — Implementation: Poppins tracer → all styles + chooser

- Offline pipeline `tools/generate_glyph_data.py`: `removeOverlaps` + arc-length
  resample + shared optical box + a unified weight-ladder knob `WT`
  (h≈900/m≈450/s≈200). Emits `app/src/main/assets/timely/<style>.json` for 11
  styles (Cormorant deferred — oldstyle figures need `lnum`).
- Rewrote `TimelyView` to the filled multi-contour morph (best-offset
  correspondence, hole-from-point seed, `EVEN_ODD` fill), added fill/outline
  render mode + `renderClock` (whole "01:29:36" bitmap for previews).
- `DoingActivity` reads style + render mode from `EverythingDone_preferences` and
  applies the h/m/s weight ladder per view.
- Settings chooser `DoingDigitStyleDialogFragment` (one row per style, `01:29:36`
  preview, Fill/Outline toggle); new `Def.Meta` keys; row in Settings → UI group.
- Published debug `202607041549` (Poppins tracer) then `202607041611` (11 styles +
  wider weights + chooser). No git commit yet.

