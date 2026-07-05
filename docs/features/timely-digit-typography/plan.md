# Implementation Plan — Timely Digit Typography

Replace the crude 13-point monoline morph digits on the Doing (countdown) screen
with beautiful, user-selectable, font-derived digits across **12 styles**, while
preserving a continuous shape morph. Architecture is fixed by
[ADR-0015](../../adr/0015-timely-countdown-digits-filled-outline-morph.md):
a **filled-outline fixed-N morph**. See [decisions.md](decisions.md) and
[analysis.md](analysis.md) for the full trail and the Python spikes under
[`tools/`](tools/).

## Scope

- **In:** `DoingActivity` HH:MM:SS (the six `TimelyView` instances), a new
  Settings chooser, and an offline glyph-data pipeline.
- **Out (deferred):** the MSDF GPU path (ADR-0015 alternative), other number
  displays, and any colour changes (white + opacity ladder is kept).

## Locked decisions (recap)

- **Option A** filled-outline morph: each digit = outer contour + up to 2 hole
  contours, arc-length resampled to a shared fixed point budget; morph by
  per-point lerp with flubber-style best-rotation-offset correspondence; absent
  counters seeded as zero-area points so holes open/close in place; render filled
  (even-odd) or as a contour outline.
- **12 styles:** Poppins, Comfortaa, Orbitron, Playfair Display, Abril Fatface,
  Cormorant Garamond, Zilla Slab, Lora, DM Serif Display, JetBrains Mono,
  Pacifico, Dancing Script. (Inter excluded.)
- **Both fill and outline** render modes, user-selectable.
- **h/m/s hierarchy:** fixed weight ladder (hour heaviest → second lightest) +
  the existing opacity ladder (86/76/66 %); single-weight fonts fall back to
  opacity only.
- **Consistent optical size & position** across styles; **tabular** advance.
- **Overlap resolution** is mandatory; **lining figures** enabled where needed.

## Part 1 — Offline glyph-data pipeline (Python)

Consolidate the spike scripts into one `tools/generate_glyph_data.py`. For each
(style, digit, weight-level):

1. **Weight source.** Variable fonts → `fontTools.varLib.instancer` at the
   hour/minute/second weights; Poppins → its Bold/Regular/Light statics;
   single-weight fonts (Abril, DM Serif, Pacifico) → one weight.
2. **Resolve overlaps** with **`skia-pathops removeOverlaps`** (authoritative;
   replaces the prototype's area-sign heuristic — fixes Playfair 4/6/8/9,
   Montserrat's open 4, JetBrains 8, etc.).
3. **Lining/tabular figures.** Apply `lnum`/`tnum` where the default is oldstyle
   or proportional (Cormorant) so digits are equal-height and equal-advance.
4. **Contours.** Classify outer + up to 2 holes by containment; arc-length
   resample outer → N (~128) and each hole → M (~48); consistent start point
   (top / rotation-aligned) and winding.
5. **Optical calibration.** Per-font scale + baseline/centre offset to a shared
   target box (uniform figure height, baseline, tabular advance), with small
   manual nudges (e.g. Orbitron a touch smaller). Store per-style metadata.
6. **Correspondence.** Precompute, per needed transition, the best cyclic offset
   for outer and each hole, plus the hole-pairing and zero-area seed positions;
   store so the runtime does no search. Flag topology-jump pairs that should
   cross-fade (e.g. 8↔9, 1↔8).
7. **Emit.** Per (style, weight, digit): normalized `outer[N]` + `hole[2][M]`
   with a per-hole present/absent flag; plus per-style metadata (advance,
   calibration, transition offsets). Format: packed asset under
   `app/src/main/assets/timely/` (JSON to start; compact binary/generated table
   later). **Ship derived data only — the TTFs need not go into the APK.**

## Part 2 — Runtime (rewrite in the `timelytextview` module)

1. **Data model.** Load the asset into per-style `DigitGlyph` objects (contours +
   hole flags + metadata); cache in memory.
2. **Morph.** Keep `ObjectAnimator` + a `TypeEvaluator` over the multi-contour
   point set; apply the precomputed offset/seed for the (from→to) pair; lerp.
3. **Render.** Build an Android `Path` (outer + holes, `FillType.EVEN_ODD`);
   fill, or stroke the contours, per render mode; anti-aliased; white at the
   position opacity.
4. **Weight & opacity per position.** Hour/minute/second pick their weight
   variant + opacity; single-weight fonts use opacity only.
5. **Layout.** Tabular advance from metadata → fixed cell widths in
   `activity_doing.xml`; retire the per-digit negative-margin hacks; no jitter
   when digits change.
6. **Timing.** Reuse the current cadence/easing; optionally cross-fade the flagged
   topology-jump transitions (short alpha blend over the shape morph).
7. **API.** `setStyle(styleId, renderMode)`; `animate(from,to)` unchanged for
   callers.

## Part 3 — Settings & wiring

1. **`Def.kt`:** `KEY_DOING_DIGIT_STYLE` + `KEY_DOING_DIGIT_RENDER`
   (fill/outline). Defaults: Geometric/Poppins, Fill.
2. **`FrequentSettings`:** cache both.
3. **`SettingsActivity` (UI group):** add a "Countdown Digit Style" row next to
   `rl_create_animation_style_as_bt`; open a chooser `DialogFragment` (extends
   `BaseDialogFragment` per project convention) with a `[Fill | Outline]` toggle
   and the 12 styles **one per row**, each previewing **`01:29:36`** (shows the
   h/m/s weight ladder); the selected row may animate a morph demo. Persist.
4. **`DoingActivity`:** read style + mode; apply to the six views.
5. **Strings:** bilingual (zh + en, plus existing locales) for the setting and
   the style names.

## Sequencing (tracer-bullet)

1. Pipeline MVP for **Poppins** (3 weights) → JSON.
2. Runtime MVP: new filled `TimelyView` renders Poppins from data and morphs with
   the precomputed offsets; wire into `DoingActivity` behind a hidden flag.
3. Validate on device (publish debug) against the chooser mock.
4. Add the remaining 11 styles to the pipeline.
5. Settings chooser + persistence.
6. Fill/outline toggle; tabular layout; weight/opacity ladder.
7. Correspondence tuning + cross-fade list for topology jumps.
8. Polish (per-font nudges, lining figures), localize, finalize.

## Risks / open items

- Single-weight fonts have a weaker hierarchy (opacity only) — accepted.
- Script styles (Pacifico, Dancing Script) morph wildly — ship as **experimental**;
  likely need per-pair cross-fade.
- Correspondence precompute keeps the runtime cheap; verify data size (12 styles ×
  ≤3 weights × 10 digits × ~(128 + 96) points) packs small.
- If crisper morph middles are ever wanted, MSDF remains the ADR-0015 alternative.

## Validation

- **Static:** regenerate the contact/chooser mocks; compare per style.
- **Motion:** on-device Doing screen ticking through minutes/hours — check jitter,
  the h/m/s hierarchy, and morph middles (esp. topology jumps).
- **Per-style sign-off** from the chooser preview.
