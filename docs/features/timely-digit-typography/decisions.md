# Decisions — Timely Digit Typography

## 2026-07-05 - Stencil 秒钟字距使用动态 kerning

- Stencil 系列的秒钟字距修正不采用全局压缩 advance，也不改变秒钟字重层级。
- 采用按当前秒钟两位数字轮廓边界计算的动态 kerning：只有当这对数字存在超过目标值的可见空隙时，才将秒钟个位向左收紧。
- 这样可以改善 `36` 等窄数字组合的空隙，同时保留 `04`、`09`、`20` 等宽数字组合的原始安全距离，避免重叠和整组计时器宽度抖动。

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

## 2026-07-05 - 计时数字主字形色使用 Thing Background

用户确认：timely 计时数字的主体颜色应表达当前 Thing 的颜色身份，而不是继续固定为白色。后续实现中，数字主字形色使用当前 Thing Background：纯色直接作为字形色，渐变则作为字形填充或描边渐变。黑色或白色只能作为轮廓、阴影、衬底等可读性辅助层，不能替代主字形色。

该决策适用于 DoingActivity 的倒计时数字，也适用于录音 dialog 计时器迁移到 timely 数字方案后的显示。它区别于普通 **Thing Foreground**：普通 Thing Foreground 是在 Thing Background 上选择黑/白前景；这里是字形本身承载 Thing Background，辅助层再负责在手机桌面壁纸蒙版、亮色 dialog 和暗色 dialog 上保持可读。

主字形色不做亮暗修正：纯色使用原始 Thing Background 色，渐变使用原始两端颜色和方向。主字形只叠加当前可见读数的 90%→100% 位置透明度；可读性完全交给细轮廓光处理。

## 2026-07-05 - 可读性辅助采用轻微轮廓光，不采用宽描边

用户拒绝把可读性辅助层做成明显变宽的外轮廓：timely 字体已经通过合成粗细区分时/分/秒，且部分粗字形本身较宽，宽描边会削弱原有层级并让粗字体显得笨重。默认策略改为轻微轮廓光或柔和外发光，只在字形边缘增加分离度；辅助光整体接近宿主背景所需的偏白或偏黑方向，但应混入 Thing Background 的增强色味，让辅助层也透出记事颜色身份。主字形色仍保持 Thing Background。

辅助光混色比例先设为可调参数：64% 宿主反向明暗色（DoingActivity 和暗色 dialog 取白，亮色 dialog 取黑）+ 36% Thing Background。纯色背景直接混合该色；渐变背景则对两端分别混合，保持与主字形色相同的整组连续渐变方向。该比例后续根据真机视觉效果调整。

该轮廓光始终存在，但必须是轮廓邻近效果，而不是铺在整个字形底部的模糊底影。绘制时需要保留 timely 字形的 even-odd 镂空语义：0、4、6、8、9 的洞不能被 glow 或辅助层填住，洞内应继续透出 DoingActivity 的壁纸蒙版或录音 dialog 的 App Chrome 背景。

实心和空心渲染模式使用相同的轮廓光强度；空心模式不额外增强。两种模式只改变主字形的填充/描边方式，不改变可读性辅助层的视觉权重。

辅助光的几何实现采用同一 even-odd 字形路径上的低透明细 stroke，而不是 `setShadowLayer` 式整块 alpha mask 模糊。实心模式先画贴边辅助 stroke，再画 Thing Background 主字形；空心模式先画同强度辅助 stroke，再画 Thing Background 线条。辅助 stroke 必须足够细，只提供轮廓附近的轻微光线，不形成可感知的新字重。

第一版可调参数：辅助 stroke 宽度为字高的 0.018，辅助光全局 alpha 为 0.32；最终 alpha = 位置透明度（90%→100%）× 0.32。该参数先用于真机验证，后续可按视觉反馈调整。

## 2026-07-05 - 渐变字形色跨整组计时连续铺设

用户确认：当 Thing Background 是渐变时，timely 数字应把渐变作为整组读数的连续色带铺设，而不是让每个数字各自重复同一段渐变。DoingActivity 的 HH:MM:SS 和录音 dialog 迁移后的计时器都应按完整计时组来计算渐变范围，使整个读数表达一个统一的 Thing 色彩身份。纯色背景保持单色字形。

冒号分隔符属于计时读数的一部分，也应纳入同一套 Thing Background 字形色与轻微轮廓光。DoingActivity 的两个冒号继续保留现有层级透明度，但颜色来源和渐变铺设范围与数字一致，避免读数中同时出现“彩色数字 + 白色分隔符”两套视觉逻辑。

实心和空心渲染模式都完整使用 Thing Background 字形色。对于渐变背景，空心模式的线条本身也走整组连续渐变，不降级为代表色；空心只改变字形绘制方式，不能改变颜色来源。

## 2026-07-05 - Doing 和录音计时器都保留 h/m/s 层级

用户确认：DoingActivity 的倒计时读数继续保留当前 h/m/s 的粗细与透明度分级；录音 dialog 计时器迁移到 timely 数字方案后也必须保留同一层级。颜色身份由 Thing Background 字形色提供，层级由时/分/秒的合成粗细和透明度表达，轮廓光强度固定一致，不参与层级区分。

透明度层级改为整组读数上的连续渐变：从当前可见读数左侧 90% 线性过渡到右侧 100%，替代旧 DoingActivity 的 86% / 76% / 66%、chooser 预览里的 90% / 80% / 66%，以及此前讨论中的 84% / 75% / 64%、96% / 64% 方案。完整 `HH:MM:SS` 时从第一个小时数字到最后一个秒数字铺满 90%→100%；DoingActivity 低于 1 小时只显示 `MM:SS` 时，则从第一个分钟数字到最后一个秒数字重新铺满同一透明度范围。冒号按其在当前可见读数中的横向位置自然取透明度，不单独定档。DoingActivity、录音 dialog 和设置预览都应从同一组透明度梯度常量读取，避免不同场景出现层级漂移。h/m/s 的离散层级仍由合成粗细表达，透明度改为连续空间层级。

同一个位置透明度应同时作用于主 Thing Background 字形色和辅助轮廓光；辅助轮廓光再额外乘自己的全局强度系数。这样右侧秒数不会因等强轮廓光而被反向抬高，主字形和辅助层保持一致的空间层级。

录音 dialog 计时器固定显示完整 `HH:MM:SS` 结构，即使录音时长不足一小时也显示小时段。这样 h/m/s 粗细和透明度层级、冒号层级，以及整组渐变铺设范围都保持稳定；录音场景只调整读数整体尺寸，不改变结构。

## 2026-07-05 - 不为计时颜色和轮廓光新增设置开关

用户确认：Thing Background 字形色应自动跟随当前 Thing 生效，可读性辅助层属于实现细节，不新增用户开关。现有用户可选项仍集中在 timely 数字字体风格与实心/空心模式；后续只有在真实反馈表明彩色计时影响专注时，才考虑新增“计时颜色：记事颜色/白色”之类的偏好。

录音 dialog 的 timely 计时器复用同一组用户设置：字体风格与实心/空心模式都从现有 Doing digit setting 读取，不新增录音专属设置。录音场景只覆盖尺寸、显示模式和宿主亮暗策略。

设置里的字体样式 chooser 预览暂时保持中性深色背景 + 白色字形，不引入 Thing Background 字形色。原因是设置页没有具体 Thing 作为颜色来源；预览主要用于比较字体和实心/空心模式，实际颜色效果在 DoingActivity 与录音 dialog 中验证。

## 2026-07-05 - 抽出复用的 TimelyClockView

用户确认：DoingActivity 和录音 dialog 不应各自拼装六个 `TimelyView` 与冒号，而应迁到一个复用的组合读数 View（暂定 `TimelyClockView`）。该 View 统一负责整组计时布局、连续 Thing Background 渐变范围、冒号绘制、轻微轮廓光、h/m/s 粗细与透明度层级，并在内部保持每个数字的 timely morph 动画。这样录音计时器和 DoingActivity 可以共享同一套上色和可读性策略，避免各个数字 View 分别绘制造成渐变不连续和轮廓光不一致。

`TimelyClockView` 需要支持场景化显示模式：DoingActivity 保留现有低于 1 小时时隐藏小时段并放大 `MM:SS` 的行为；录音 dialog 固定显示完整 `HH:MM:SS`。两种模式共享上色、轮廓光、冒号和 h/m/s 层级策略，但由宿主场景决定是否显示小时段。

DoingActivity 的无限时长符号 `∞` 也属于计时读数，应纳入同一套 Thing Background 字形色和轻微轮廓光。它不需要 morph，可作为普通字形或路径绘制；关键是不要继续使用独立的白色文本体系。

## 2026-07-05 - 录音 dialog 计时器独立承载 Thing 身份

用户确认：录音 dialog 的计时器迁移到 timely 数字方案后，应使用同一套 Thing Background 字形色和同强度轮廓光；录音波形继续使用既有水体配色。二者共享被录音 Thing 的颜色身份，但不互相取色、不互相叠加。计时器不是 App Chrome 普通文本，也不是 Voice Waveform 的一部分，而是 Hybrid Chrome Surface 内另一个承载 Thing 身份的读数。

## 2026-07-05 - 透明度梯度改为 90%→100%，录音计时器保留整体呼吸

用户确认：此前 96%→64% 的位置透明度太弱化右侧秒数，改为从当前可见读数左侧 90% 线性过渡到右侧 100%，用更高透明度强调秒钟变化。DoingActivity、录音 dialog 和设置预览都应同步使用这组常量；h/m/s 的离散层级仍由合成粗细表达，透明度只作为整组空间方向的轻微变化。

无限时长符号 `∞` 不需要套位置透明度梯度，内部字形透明度直接按 100% 绘制；如果宿主场景另有整体 view alpha 动画，则仍属于场景动画，不属于 timely 读数内部的 h/m/s 位置层级。

录音 dialog 固定显示完整 `HH:MM:SS` 后，小时段会让 280dp 宽度过紧。dialog 宽度改为 320dp；录音计时器在这个场景使用略小字号与更紧的冒号间隔，但仍要避免数字相连。录音进行中还要加入与 DoingActivity 同类的整体透明度呼吸，同时保留 `TimelyClockView` 内部的 h/m/s 粗细和位置透明度分级。

## 2026-07-05 - 录音 dialog 回到 280dp，顶部间距与停止落点分离

用户后续将录音 dialog 宽度改回 280dp，并调整了录音计时器 alpha。该选择保留：计时器适配优先靠较小高度、收紧冒号间隔和透明度处理，而不是扩大 dialog。

准备和录音阶段的 timely 计时器需要更大的顶部留白，因此上边距从 18dp 增到 26dp。停止录音后计时器仍应移动到调整前相同的绝对位置：旧布局中 18dp top margin + 72dp translation = 90dp，因此停止阶段以 90dp 作为目标顶部位置，并动态扣除当前 top margin 计算 `translationY`。这样准备/录音阶段更下移，但停止后出现文件名编辑时的动画终点不变。

## 2026-07-05 - Doing 普通倒计时使用自身出现动画

对比改 `timelytextview` 模块前的版本（`8cad4745`）后确认：旧 `TimelyView` 的 `controlPoints` 初始为 `null`，因此打开 DoingActivity 时数字自身不绘制；第一次倒计时更新才通过 `animate(from,to)` 把字形画出来。旧版本没有给六个 timely 数字做整体 alpha 淡入，只有无限时长文本和冒号分隔符使用 alpha 动画。

迁移到 `TimelyClockView` 后，普通倒计时应保留这类自身出现动画，而不是先把整个 view alpha 淡入到 1，再播放字形 morph。`TimelyClockView` 增加从 `-1` 空形态展开到当前读数的 `animateIn` 路径；DoingActivity 普通倒计时进场时直接让 view 可见并触发该内部 morph。无限时长符号没有数字 morph，仍使用 alpha 出现，目标 alpha 调为 0.96。

录音完成态的位置在后续真机反馈中略向上调整：文件名区域停止 translation 从 32dp 改为 24dp，计时器停止目标顶部从 90dp 改为 82dp。该调整只影响 STOPPED 状态，不改变用户手动调好的准备/录音阶段 top margin（当前布局为 36dp）。

## 2026-07-05 - Doing 只保留服务首次 tick 的出现动画，录音也使用 animateIn

用户反馈 DoingActivity 的 `TimelyClockView.animateIn` 出现动画会播放两遍，并明确只保留第二遍。代码路径确认：第一遍来自 `playEnterAnimations()` 中主动调用 `animateIn(leftTime)`；第二遍来自 `DoingService` 首次 tick，服务的 `mTimeNumbers` 初始为 `[-1,-1,-1,-1,-1,-1]`，第一次 `onLeftTimeChanged` 会把数字从空形态 morph 到当前读数。保留后者，移除 DoingActivity 进场阶段的主动 `animateIn`；在 `playTimelyAnimation` 里先把 clock alpha 设为 1，再播放服务触发的 `animateDigits`。

录音 dialog 也复用 `TimelyClockView.animateIn(0)`：初次配置和重录回准备态时，从空形态展开到 `00:00:00`；按下开始录音时只固定到 0 并进入计时，不额外重复出现动画。

录音完成态继续上移：计时器停止目标顶部从 82dp 改为 80dp。文件名区域保持上一轮的 24dp 停止 translation。

## 2026-07-05 - 无限时长呼吸范围与录音首次出现延迟

用户反馈无限时长符号的呼吸 alpha 会一直到 0。代码路径确认原因是 `mInfinityHandler` 使用 `1 - mClockView.alpha` 计算下一次目标 alpha：当当前 alpha 接近 0.96 或 1.0 时，下一次目标就会接近 0。修复为显式两端切换：高点 1.0，低点 0.75，中点阈值 0.875；无限时长进场淡入到 1.0，后续只在 1.0 和 0.75 间呼吸。

录音 dialog 的首次 `animateIn(0L)` 从配置阶段立即执行改为延迟 160ms 执行，使 `00:00:00` 从空形态出现的动画与下方录音波浪启动更接近。如果用户在延迟期间立即开始录音，则取消这次待执行的准备态出现动画，避免进入录音态后重复播放。

## 2026-07-05 - 录音 animateIn 仅用于首次出现

用户确认：录音 dialog 的 timely 计时器重新开始录音时，不需要再播放一次 `animateIn`；该动画只用于 `TimelyClockView` 第一次出现。实现上保留 `configureClockView` 阶段的延迟 `mClockIntro`，但 `stoppedToPrepared()` 不再调用 `animateIn(0L)`，而是直接 `setTimeMillis(0L, false)` 静态回到 `00:00:00`，并取消任何待执行的 intro callback。

## 2026-07-05 - TimelyClockView 在宿主可用宽度内自适应

用户指出不同 timely 字体的字宽不同，DoingActivity 和录音 dialog 中的 `TimelyClockView` 都需要与屏幕或 dialog 边缘保持一定距离。实现策略是把宿主可用宽度作为硬边界：DoingActivity 的计时器占满父行宽但左右各留 24dp，录音 dialog 内左右各留 16dp；`TimelyClockView` 内部按 `min(高度, 可用宽度 / widthUnits)` 计算实际绘制尺度，宽字体会整体缩小到可用宽度内，窄字体则保持原高度并居中。

这个策略优先保证不贴边和不裁切，代价是极宽字体在完整 `HH:MM:SS` 或大号无限符号场景下可能略小；这比不同字体在宿主边缘产生不可控距离更稳定。

## 2026-07-05 - 设置字体选择器改为 accent 渐变预览

用户要求重做设置界面的字体选择 dialog。决策：该 chooser 不再使用中性深色预览底 + 白字，而是用 App 默认 accent + accent2 表示“设置页中无具体 Thing”的中性强调色。未选中行通过渐变 ripple 和渐变预览字形表达可点状态；选中行整行铺满同一渐变，预览字形改为白色，保证选中状态一眼可见。

实心 / 描边模式选择改成 page tab 语义：两个等宽 tab、选中项用 accent 渐变文本强调，未选中项用提示色，并继续使用渐变触摸 ripple。字体选项行改为“字体名称在上、预览在下”的纵向结构，预览仍固定 `01:29:36`，保留 h/m/s 合成粗细差异和 90% -> 100% 的左到右位置透明度。该规则同时适用于实心和描边渲染。

后续细化：该 dialog 宽度对齐设置应用语言 dialog 的 280dp，不再使用屏幕百分比宽度。标题也使用 App 默认 accent + accent2 渐变；字体名称不参与选中态白色前景，统一使用 App Chrome 的提示性文本颜色，把强选中反馈留给整行渐变背景和白色预览数字。

再次细化：选中字体行的字体名称仍需要偏白，以便在整行 accent 渐变背景上保持可读；提示性文本颜色只用于未选中字体名称。打开 dialog 后自动滚动到当前字体行。滚动指示只保留 tab 下方一条顶部指示线，显示 / 隐藏逻辑参考应用语言 `ChooserDialogFragment` 的顶部分隔线：列表在顶部时隐藏，向下滚动后显示。

## 2026-07-05 - 多外轮廓字体沿用兼容 JSON 字段

为接入 stencil、inline 与多填充分片字体，离线管线不再把次级轮廓限制为最多两个。历史字段 `holes` 继续保留，但语义扩展为“除最大外轮廓之外的所有次级轮廓”：真实 counters、stencil 缺口、inline 环和分离填充分片都放入该数组。

这样做可以复用现有运行时：`TimelyView` 和 `TimelyClockView` 已经对 `holes.length` 使用动态数组，并通过 `EVEN_ODD` 填充绘制整组轮廓；morph 时也按当前与目标字形的最大次级轮廓数量补零面积 seed。此轮不引入新的 JSON 顶层 schema，避免同时迁移旧资产和运行时读取器。

代价是字段名 `holes` 已不再精确描述所有内容，因此在生成器注释和 runtime 注释中明确说明它是历史字段名。未来若需要更强的轮廓匹配质量，可再新增显式 `secondaryContours` / component metadata schema。
