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
