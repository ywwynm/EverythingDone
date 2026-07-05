# Execution — Timely Digit Typography

Tracks implementation against [plan.md](plan.md). Checkboxes reflect actual code
state. Tracer bullet first: **Poppins only, 3 weights, fill mode**, wired into the
Doing screen, then published to Aliyun for on-device review.

## Phase 0 — Tracer bullet (Poppins, fill)

### Offline pipeline
- [x] Install `skia-pathops` in conda env `everythingdone`.
- [x] `tools/generate_glyph_data.py`: Poppins Bold/Regular/Light (h/m/s weights).
- [x] Resolve overlaps (`fontTools removeOverlaps` / skia-pathops).
- [x] Extract outer + up to 2 holes; classify by containment/winding.
- [x] Arc-length resample outer→N(128), holes→M(48); consistent start + winding.
- [x] Optical calibration to a shared box (Regular figure height/baseline; tabular
      advance stored). Per-font size nudges deferred to other styles (Phase 1).
- [x] Emit `app/src/main/assets/timely/poppins.json` (77 KB; per weight × digit).
- [x] Verify: rendered digits back from the JSON — weight ladder + counters OK.

### Runtime (`timelytextview` module)
- [x] Rewrite `TimelyView` to the filled multi-contour model (kept public
      `animate(from,to)` / `animate(end)`).
- [x] Load + cache glyph data from assets; `setStyle(asset, level)` /
      `setWeightLevel(level)`.
- [x] `animate(from,to)`: best cyclic offset for outer + holes, holes top-first,
      absent counters seeded as zero-area points at the counterpart centroid;
      `ShapeEvaluator` lerps; `Null` (−1) collapses to centre.
- [x] `onDraw`: `Path` (outer + holes, `EVEN_ODD`), fill; `textColor` opacity;
      scaled/centred to the view box.

### Wiring
- [x] `DoingActivity`: weight level per view (0,1→hour · 2,3→minute · 4,5→second),
      style = Poppins; existing white opacity ladder kept.
- [~] Tabular advance / layout: existing per-view fixed widths already prevent
      jitter; confirm on device.

### Ship
- [x] `:app:assembleDebug` compiles (BUILD SUCCESSFUL).
- [x] Published debug update `202607041549` to Aliyun
      (`debug-updates/update-20260704234846.md`).
- [ ] On-device review; capture notes/follow-ups.

## Phase 1 — All styles + settings

- [x] Generalize the pipeline to all families via a general
      `generate_glyph_data.py` with single-weight fallbacks.
- [x] **Widen the weight ladder** with one unified `WT` knob (h≈900/m≈450/s≈200);
      Poppins uses Black/Regular/ExtraLight, Zilla uses Bold/Regular/Light.
- [x] Fill/outline render toggle (`TimelyView.setRenderMode` + chooser toggle).
- [x] `Def.kt` keys (`KEY_DOING_DIGIT_STYLE`, `KEY_DOING_DIGIT_RENDER`) read/written
      via the named `EverythingDone_preferences` (no `FrequentSettings` needed).
- [x] Settings "Countdown Digit Style" chooser (`DoingDigitStyleDialogFragment`
      over `BaseDialogFragment`): `[Fill | Outline]` toggle + one-row-per-style
      `01:29:36` preview (`TimelyView.renderClock`); default Poppins/Fill.
- [x] Strings (en + zh-rCN) for the setting and Fill/Outline; style labels are
      language-neutral font names.
- [x] `DoingActivity` reads style + render mode from prefs and applies per view.
- [x] Published debug update `202607041611`.
- [ ] **Cormorant Garamond deferred** — default oldstyle figures are uneven; needs
      lining figures (`lnum`) before shipping (11 of 12 styles live).
- [x] **h/m/s hierarchy → size ladder** (1.0 / 0.84 / 0.68, baseline-aligned);
      weight + opacity kept as secondary. Fixes the near-invisible weight-only
      hierarchy. (`TimelyView.setScale` + `DoingActivity` + `renderClock`.)
- [x] **Digit spacing → tabular advance cells** (width = advance × size, centred);
      removed negative margins and the square `onMeasure`. Fixes wide-font
      (Orbitron) collisions and layout jitter.
- [x] Published debug update `202607041635`.
- [ ] Localize strings for the remaining locales.
- [ ] Cormorant Garamond lining figures (`lnum`) — still deferred.
- [ ] Tune size-ladder ratios / advance side-bearing if on-device feedback asks.

## Phase 2 — Polish

- [ ] Correspondence tuning; cross-fade list for topology-jump transitions.
- [ ] Script styles (Pacifico, Dancing Script) as experimental.
- [ ] Data packing/size review; consider binary/generated table over JSON.
- [ ] Final on-device validation across styles.
