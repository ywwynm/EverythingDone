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

## 2026-07-05 - Thing Background 字形色与录音计时器

- 经过一轮 `grill-with-docs` 收敛 timely 计时读数上色方案：主字形使用原始 Thing Background；渐变按当前可见读数组连续铺；透明度从左到右 96% -> 64%；h/m/s 粗细层级保留；辅助层使用 64% 宿主反向明暗 + 36% Thing Background 的低透明细轮廓 stroke，并保留数字洞形透明。
- 新增 `TimelyClockView`，统一绘制数字、冒号、无限符号、连续渐变、位置透明度和细轮廓光；DoingActivity 替换原六个 `TimelyView` 与冒号 TextView；录音 dialog 从 `Chronometer` 迁移到同一套 timely 读数，固定 `HH:MM:SS` 并复用用户选择的 style/render mode。
- `:app:assembleDebug` 编译通过；已生成 `docs/features/timely-digit-typography/debug-updates/update-20260705094714.md` 并发布阿里云 debug update `202607050147`。未使用 adb，未提交 git。
- 根据真机视觉反馈继续调整：内部位置透明度改为 90% -> 100%，无限符号内部透明度固定为 100%；录音 dialog 改为 320dp 宽，计时器略缩小并收紧冒号间隔，录音中加入整体 alpha 呼吸。
- `:app:assembleDebug` 再次编译通过；已生成 `docs/features/timely-digit-typography/debug-updates/update-20260705104535.md` 并发布阿里云 debug update `202607050245`。未使用 adb，未提交 git。
- 用户后续将录音 dialog 改回 280dp 并调整 timely 计时器 alpha；本轮保留这些改动，只把准备/录音阶段 top margin 从 18dp 增到 26dp，并把停止阶段的 translationY 改为按 90dp 目标顶部位置动态计算，确保文件名编辑出现时计时器落点不变。`:app:assembleDebug` 通过，已发布阿里云 debug update `202607050321`。未使用 adb，未提交 git。
- 修复 DoingActivity 打开时 `TimelyClockView` 抢先显示的回归：clock 初始 alpha 改为 0，普通倒计时不再在 `updateTimeViews()` 中直接设为可见，`playEnterAnimations()` 统一淡入普通倒计时和无限时长。静态检查确认没有残留的提前 `alpha=1` 路径；`:app:assembleDebug` 通过，已发布阿里云 debug update `202607050334`。未使用 adb，未提交 git。
- 对比改 `timelytextview` 模块前的 `8cad4745` 后，恢复普通倒计时靠自身 morph 出现的行为：新增 `TimelyClockView.animateIn()`，普通倒计时进场直接置 `alpha=1` 并播放内部空形态到当前读数的动画；无限时长目标 alpha 改为 0.96。录音完成态同步上移 8dp：文件名 translation 32dp -> 24dp，计时器停止目标顶部 90dp -> 82dp，保留用户手动调好的准备/录音阶段 36dp top margin。`:app:assembleDebug` 通过，已发布阿里云 debug update `202607050343`。未使用 adb，未提交 git。
- 修正上一轮导致 DoingActivity 出现动画双触发的问题：移除 `playEnterAnimations()` 里主动 `animateIn`，只保留 `DoingService` 首次 tick 从 `[-1…-1]` 到当前读数的后一遍 morph；`playTimelyAnimation()` 首次触发时把 clock alpha 设为 1。录音 dialog 初次准备态和重录回准备态接入 `animateIn(0L)`；录音完成态计时器停止目标顶部改为 80dp。`:app:assembleDebug` 通过，已发布阿里云 debug update `202607050352`。未使用 adb，未提交 git。
- 修复无限时长呼吸 alpha 掉到接近 0 的问题：旧算法 `1 - currentAlpha` 改为 1.0 / 0.75 两端显式切换，进场淡入到 1.0。录音 dialog 首次准备态 `animateIn(0L)` 延迟 160ms 执行，并在用户立即开始录音或关闭 dialog 时取消待执行 intro。`:app:assembleDebug` 通过，已发布阿里云 debug update `202607050359`。未使用 adb，未提交 git。
- 根据反馈调整录音重录路径：`animateIn(0L)` 只保留在 dialog 首次出现时通过延迟 `mClockIntro` 播放；重录回准备态时取消待执行 intro，并直接 `setTimeMillis(0L, false)` 静态复位到 `00:00:00`。`:app:assembleDebug` 通过，已发布阿里云 debug update `202607050413`。未使用 adb，未提交 git。
- 根据反馈处理不同字体字宽差异：`TimelyClockView` 绘制时按可用宽度自动缩小宽字体；DoingActivity 计时器改为 `match_parent` 并左右各留 24dp，动态高度调整保持 `MATCH_PARENT` 宽度；录音 dialog 计时器在 280dp dialog 内左右各留 16dp。`:app:assembleDebug` 通过，已发布阿里云 debug update `202607050425`。未使用 adb，未提交 git。

## 2026-07-05 - 设置字体选择器重设计

- 重做设置界面的 `DoingDigitStyleDialogFragment`：实心 / 描边切换改成两个等宽 page tab，选中 tab 使用 accent + accent2 渐变文本，未选中 tab 使用提示色，触摸反馈复用 `GradientRippleDrawable`。
- 字体列表行改为“字体名称在上、`01:29:36` 预览在下”的纵向结构；未选中行透明背景 + accent 渐变 ripple + accent 渐变预览字形，选中行整行 accent 渐变背景 + 白色预览字形。
- 扩展 `TimelyView.renderClock`：支持整组连续颜色渐变，并在整组读数上用统一 90% -> 100% alpha mask，实心、描边和冒号预览共享同一位置透明度规则。
- 补充修复 tab 快速切换时的 pending text shader：未选中 tab 走 `ThingBackground.pure(hintColor)`，避免旧选中态的延迟渐变回写。
- `:app:assembleDebug` 编译通过；已生成 `docs/features/timely-digit-typography/debug-updates/update-20260705124953.md` 并发布阿里云 debug update `202607050452`。未使用 adb，未提交 git。
- 后续按反馈微调：dialog 宽度改为 280dp，对齐设置应用语言 dialog；标题改为 accent + accent2 渐变；字体名称统一使用提示性文本颜色。`:app:assembleDebug` 编译通过；已生成 `docs/features/timely-digit-typography/debug-updates/update-20260705125650.md` 并发布阿里云 debug update `202607050457`。未使用 adb，未提交 git。
- 继续按反馈微调：选中字体行的字体名称改为偏白；打开 dialog 与切换实心 / 描边后自动滚动到当前选中字体；在实心 / 描边 tab 下方加入顶部滚动指示线，列表在顶部时隐藏，向下滚动后显示。`:app:assembleDebug` 编译通过；已生成 `docs/features/timely-digit-typography/debug-updates/update-20260705130417.md` 并发布阿里云 debug update `202607050504`。未使用 adb，未提交 git。
- 再次按反馈调整选中字体名亮度：从纯白改为 App Chrome tertiary on-color 层级，保持偏白但更接近提示性文本。`:app:assembleDebug` 编译通过；已生成 `docs/features/timely-digit-typography/debug-updates/update-20260705130800.md` 并发布阿里云 debug update `202607050508`。未使用 adb，未提交 git。
- 按反馈将录音 dialog 中 `clock_record_audio` 的左右边距从 16dp 改为 24dp，dialog 仍保持 280dp 宽度，计时器继续在可用宽度内自适应。`:app:assembleDebug` 编译通过；已生成 `docs/features/timely-digit-typography/debug-updates/update-20260705131724.md` 并发布阿里云 debug update `202607050517`。未使用 adb，未提交 git。

## 2026-07-05 - 追加字体候选调研

- 复核当前实现：`TimelyClockView` 从 `app/src/main/assets/timely/*.json` 读取真实字体轮廓，按 h/m/s 三组字形数据 + 运行时合成粗细呈现同尺寸层级，小时 `0.080`、分钟 `0.035`、秒 `0.000`，并支持实心 / 描边、整组 90% -> 100% 位置透明度、Thing Background 连续渐变和轻微轮廓光。
- 当前已接入 11 种 style：Poppins、Comfortaa、Orbitron、Playfair Display、Abril Fatface、Zilla Slab、Lora、DM Serif Display、JetBrains Mono、Pacifico、Dancing Script；Cormorant Garamond 仍因默认 oldstyle figures 延后。
- 联网查看 Google Fonts 与 Google Fonts 仓库后，临时下载候选字体并用现有管线同类的 `fontTools` + `matplotlib.textpath` 方式生成数字预览和指标，输出在 `tmp/timely-font-research/`。初筛最适合追加的是 Bodoni Moda、Libre Bodoni、Prata、Fraunces、Spectral、Libre Baskerville、Quattrocento、Tenor Sans、Josefin Sans、Rajdhani、Oxanium、Chakra Petch、Quicksand；Unbounded 偏宽，Marcellus 和 Belleza 默认数字高度 / 落点差异较大，暂不优先。
- 用户确认保留 Fraunces、Bodoni Moda、Libre Bodoni、Cinzel、Libre Baskerville、Josefin Sans、Exo 2，并希望继续扩展其它风格。第二轮补看 Art Deco、窄体、现代 grotesk、typewriter、pixel、stencil、inline/shade 等方向，输出在 `tmp/timely-font-research-2/` 与 `tmp/timely-font-research-3/`。结论：Righteous、Poiret One、Limelight、Antonio、Teko、Saira Condensed、Oswald、Space Grotesk、Sora、Urbanist、Outfit、Nixie One、Courier Prime、Gilda Display、Forum、Gemunu Libre、Kanit、Major Mono Display、Jersey 10 可先按现有管线试；Handjet、Silkscreen、Tiny5、Stencil 系列、Monoton、Bungee Shade 等需要先支持多外轮廓 / 多填充分片。
- 用户进一步点名看中 Space Grotesk、Limelight、Righteous、Poiret One、Major Mono Display、Genos、Italiana、Nixie One、Big Shoulders Stencil、Sirin Stencil、Allerta Stencil、Saira Stencil、Stardos Stencil、Outfit、Monoton。已记录到偏好与 follow-up；其中 stencil / Monoton 类需要先扩展离线管线。
- 已先接入当前管线可直接支持的 9 个字体：Space Grotesk、Limelight、Righteous、Poiret One、Major Mono Display、Genos、Italiana、Nixie One、Outfit。新增 9 个 `app/src/main/assets/timely/*.json`，扩展 `generate_glyph_data.py` 支持指定 style 子集生成，并更新 `DoingDigitStyleDialogFragment.STYLES`。`:app:assembleDebug` 编译通过；已生成 `docs/features/timely-digit-typography/debug-updates/update-20260705141512.md` 并发布阿里云 debug update `202607050615`。未使用 adb，未提交 git。
- 按用户要求提交上一轮改动：commit `7c983258 Add direct Timely digit font styles / 添加可直接接入的 Timely 数字字体`。
- 继续扩展多外轮廓 / 多填充分片管线：`generate_glyph_data.py` 中历史 `holes` 字段不再截断为 2 个，改为保存所有次级轮廓，运行时沿用 `EVEN_ODD` 与动态次级轮廓数组。新增 Big Shoulders Stencil、Sirin Stencil、Allerta Stencil、Saira Stencil、Stardos Stencil、Monoton 的 timely JSON，并注册到设置选择器。`:app:assembleDebug` 编译通过；已生成 `docs/features/timely-digit-typography/debug-updates/update-20260705142400.md` 并发布阿里云 debug update `202607050624`。未使用 adb，未提交第二轮改动。
