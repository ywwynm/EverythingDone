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

## 2026-07-05 追加计划：Thing Background 字形色与录音计时器

本轮目标是在已有 filled-outline timely 数字基础上，让计时读数承载当前 Thing 的颜色身份，并把录音 dialog 的计时器迁移到同一套方案。

### 范围

- **包含**：DoingActivity 的倒计时读数、无限时长符号、录音 dialog 计时器、冒号分隔符、复用的 `TimelyClockView`。
- **不包含**：设置 chooser 的中性字体预览、其它 DoingActivity 白色 UI、录音波形配色、额外用户设置开关。

### 关键规则

- 主字形色使用原始 Thing Background：纯色直接使用原色，渐变按当前可见读数组连续铺设。
- 主字形不做提亮或压暗，只叠加位置透明度。
- 当前可见读数从左到右使用 90% -> 100% 连续透明度；DoingActivity 低于 1 小时时在可见的 `MM:SS` 范围内重新铺满，录音 dialog 固定 `HH:MM:SS`。无限时长符号 `∞` 不套位置透明度梯度，内部字形透明度为 100%。
- h/m/s 的离散层级继续由合成粗细表达；透明度改为空间连续层级。
- 冒号、无限符号都属于计时读数，纳入同一套字形色、透明度和可读性辅助。
- 可读性辅助层是同一 even-odd 路径上的低透明细 stroke，不用整块 glow，不填数字洞。
- 辅助光颜色 = 64% 宿主反向明暗 + 36% Thing Background；DoingActivity 和暗色 dialog 的宿主反向明暗为白，亮色 dialog 为黑。渐变背景按两端分别混色，并保持连续渐变。
- 第一版辅助参数：stroke 宽度 = 字高 0.018，辅助 alpha = 0.32，再乘当前位置透明度。
- 实心/空心模式使用同一辅助强度；空心线条也完整使用连续渐变，不降级为代表色。

### 实现步骤

1. 在 `timelytextview` 中新增复用的 `TimelyClockView`，统一绘制数字、冒号、无限符号、连续主渐变、连续透明度和细轮廓光。
2. `TimelyClockView` 支持 Doing 自动隐藏小时段模式和 Recording 固定完整 `HH:MM:SS` 模式；内部保留每个数字的 timely morph。
3. DoingActivity 替换现有六个 `TimelyView` 与两个冒号 TextView，读取当前 Thing Background、用户选择的 style/render mode，并把倒计时变化交给 `TimelyClockView`。
4. 录音 dialog 替换 `Chronometer`，复用同一 style/render mode 和当前 DetailActivity 的 Thing Background；用 handler 驱动录音时长显示。
5. 录音 dialog 宽度改为 320dp；录音计时器使用略小高度和更紧的冒号间隔，录音中加入整体透明度呼吸。
6. 保持设置 chooser 预览不变，仍用中性深色背景 + 白色字形，但透明度梯度同步为 90% -> 100%。
7. 编译通过后，按本功能目录生成 debug update 日志并发布到阿里云。
