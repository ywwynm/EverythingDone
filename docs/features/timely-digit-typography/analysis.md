# Analysis — Timely Digit Typography

## Prototype 1 — raster medial-axis skeleton (REJECTED)

`tools/preview_digits.py`: `skimage.skeletonize` on rasterized glyphs. Output has
broken/discontinuous strokes, spurs at junctions, and retracted endpoints. Raw
raster medial axis is inherently lossy; rejected by the user on 2026-07-04.

## Prototype 2 — fixed-N filled-OUTLINE morph

`tools/morph_outline.py`: each digit normalized to `outer + up to 2 holes`; every
contour arc-length resampled to a fixed count; a missing hole collapses to the
outer centroid; morph = per-point linear interpolation; rendered filled with
even-odd so counters show.

**Findings**

- **Static rendering is pixel-faithful** across Poppins / Comfortaa / Orbitron,
  with correct counters (including 8's two holes). Using the font's real outlines
  fully solves the "ugly digits" problem — zero extraction artifacts.
- **Hole appearance/disappearance works**: `1 -> 0` grows a counter out of a
  point cleanly.
- **Correspondence is the open problem.** With a naive "topmost start point +
  index correspondence", intermediate frames twist and blob for most pairs
  (`2 -> 3`, `5 -> 0`, `8 -> 9`). Endpoints (t=0,1) are always exact; the middle
  is poor.
- **Root cause & fix.** Point correspondence between the A and B contours is
  unoptimized. Known fixes: search the cyclic offset (and direction) that
  minimizes total point travel (flubber-style), consistent parametrization, and
  per-transition tuning; special-case large topology jumps (e.g. `8 -> 9`) with a
  short cross-fade.
- **Inherent trade-off.** Filled-area distortion mid-morph is more visible than
  thin-line distortion; the original component's clean morph owed partly to being
  monoline.

## Prototype 2b — flubber-style correspondence (2026-07-04)

Added best-rotation-offset matching (rotate ring B to the cyclic start that
minimizes summed squared travel vs A) for the outer contour and each hole; holes
paired top-first; a missing counter seeded as a zero-area point at the OTHER
side's counter centroid so it grows/shrinks in place.

**Result.** Previously-broken middles improve markedly. Poppins `8 -> 9` is now
clean (top counter kept, bottom counter shrinks in place). `2 -> 3`, `5 -> 0` are
acceptable but their deep-middle frames are soft/organic. Comfortaa middles are
more organic (round, near-equal counters rotate during matching). This confirms
the filled-outline morph is viable, with one inherent caveat: **filled-shape
morph middles are softer/less crisp than a thin monoline morph** — the original
component's crispness owed partly to being a single thin stroke.

## Prototype 3 — overlapping-contour resolution (2026-07-04)

`tools/overlap_fix.py`. Diagnosed the user-reported Playfair "4" false hole:
Playfair's "4" is **two overlapping same-winding filled contours** (body +
descending stem); the naive "largest = outer, all others = holes" rule subtracts
a fill and opens a false counter at the crossbar. Fix: classify by winding sign
vs the largest contour (same sign = fill → boolean union; opposite = hole →
subtract), keeping only true counters. Before/after confirms the "4" is fixed.

A scan across all fonts shows the naive rule is wrong for many glyphs, in BOTH
directions:
- **Under-counts** (renders solid, no counter): Playfair 6/8/9, Cormorant 4/8 —
  self-overlapping single contours.
- **Over-counts** (spurious holes): Comfortaa 6/8/9, Montserrat 8, and
  Montserrat's OPEN "4" (naive adds a counter where there is none).
- **Heuristic limit**: the shapely area-sign heuristic over-merges JetBrains Mono
  "8" to zero holes — a counterexample proving the sign heuristic is not fully
  robust.

**Requirement.** The offline pipeline must resolve overlaps authoritatively with
**skia-pathops `removeOverlaps`** (the tool fontmake/fontTools use), not the
prototype's area-sign heuristic, then classify true holes by containment. This is
a mandatory extraction step for every font.

## Three viable production paths

| # | Path | Static quality | Morph quality | Impl. cost |
|---|------|----------------|---------------|-----------|
| 1 | Filled-outline morph (CPU/Canvas, even-odd) | Excellent, any style incl. serif | Good for same-topology ±1 with proper correspondence; organic/blobby on big jumps | Medium (correspondence + fill + per-transition tuning) |
| 2 | Hand-authored monoline skeleton (fixed-N single stroke traced from font, no auto-extraction) | Clean but monoline only | Cleanest middles; matches the liked animation | Low-med engineering + manual design of 30 glyphs |
| 3 | MSDF GPU field morph | Faithful filled, any style | Robust topology handling (holes native) | High (AGSL/GL shader, offline msdfgen atlas, integrate into View screen) |

## Open decision (pending user)

Which path to adopt depends on priority: faithful-but-organic-morph (1) vs
monoline-clean-morph that matches the animation they liked (2) vs
expensive-both (3). Research pass (flubber correspondence, MSDF on Android)
running to refine 1 and 3.
