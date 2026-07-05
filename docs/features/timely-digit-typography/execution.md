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

## Phase 3 发布结果 - 2026-07-05

- [x] `TimelyClockView`、DoingActivity 接入、录音 dialog 接入均已完成。
- [x] `:app:assembleDebug` 编译通过。
- [x] 已生成发布日志 `docs/features/timely-digit-typography/debug-updates/update-20260705094714.md`。
- [x] 已通过 `:app:publishDebugUpdate` 发布到阿里云 debug 通道，版本号 `202607050147`。

## Phase 3 - Thing Background 字形色与录音计时器

- [x] 与用户确认主线方案：主字形使用原始 Thing Background；渐变跨当前可见读数组连续铺；透明度为 96% -> 64% 连续渐变；h/m/s 粗细层级保留；辅助层为 64/36 混色的低透明细轮廓 stroke；洞形保持透明。
- [x] 更新 `plan.md`、`decisions.md`、`preferences.md` 和 `CONTEXT.md`，记录 Thing Background Glyph Colour、`TimelyClockView` 复用方向、录音 dialog 固定 `HH:MM:SS`、不新增设置开关等决策。
- [ ] 新增 `TimelyClockView`，统一绘制数字、冒号、无限符号、连续渐变、位置透明度和细轮廓光。
- [ ] DoingActivity 接入 `TimelyClockView`，替换六个 `TimelyView` 与冒号 TextView。
- [ ] AudioRecordDialogFragment 接入 `TimelyClockView`，替换 `Chronometer` 并用录音 elapsed time 驱动显示。
- [ ] `:app:assembleDebug` 编译通过。
- [ ] 生成 `docs/features/timely-digit-typography/debug-updates/update-*.md` 并通过 `:app:publishDebugUpdate` 发布到阿里云。

## Phase 3 状态更新 - 2026-07-05

- [x] 与用户确认主线方案：主字形使用原始 Thing Background；渐变跨当前可见读数组连续铺；透明度为 96% -> 64% 连续渐变；h/m/s 粗细层级保留；辅助层为 64/36 混色的低透明细轮廓 stroke；洞形保持透明。
- [x] 更新 `plan.md`、`decisions.md`、`preferences.md` 和 `CONTEXT.md`，记录 Thing Background Glyph Colour、`TimelyClockView` 复用方向、录音 dialog 固定 `HH:MM:SS`、不新增设置开关等决策。
- [x] 新增 `TimelyClockView`，统一绘制数字、冒号、无限符号、连续渐变、位置透明度和细轮廓光。
- [x] DoingActivity 接入 `TimelyClockView`，替换六个 `TimelyView` 与冒号 TextView。
- [x] AudioRecordDialogFragment 接入 `TimelyClockView`，替换 `Chronometer` 并用录音 elapsed time 驱动显示。
- [x] `:app:assembleDebug` 编译通过。
- [ ] 生成 `docs/features/timely-digit-typography/debug-updates/update-*.md` 并通过 `:app:publishDebugUpdate` 发布到阿里云。

## Phase 3 调整 - 2026-07-05

- [x] 将 timely 读数内部位置透明度从 96% -> 64% 改为 90% -> 100%，同步 `TimelyClockView` 和设置 chooser 预览。
- [x] 无限时长符号 `∞` 绕过位置透明度遮罩，内部字形按 100% 绘制。
- [x] 录音 dialog 宽度从 280dp 改为 320dp，录音计时器高度从 44dp 改为 40dp，并将冒号间隔收紧到 0.42 倍 digit advance。
- [x] 录音进行中加入整体 alpha 呼吸，在停止、重录和关闭时取消；`TimelyClockView` 内部的 h/m/s 粗细与位置透明度仍保留。
- [x] `:app:assembleDebug` 编译通过。
- [x] 生成发布日志 `docs/features/timely-digit-typography/debug-updates/update-20260705104535.md`。
- [x] 已通过 `:app:publishDebugUpdate` 发布阿里云 debug update `202607050245`。

## Phase 3 录音顶部留白调整 - 2026-07-05

- [x] 保留用户改回的 280dp 录音 dialog 宽度和已调好的计时器 alpha。
- [x] 将录音 timely 计时器准备/录音阶段的 `layout_marginTop` 从 18dp 增到 26dp。
- [x] 停止录音后的计时器目标顶部位置保持旧值 90dp，`translationY` 改为按当前 top margin 动态计算，避免影响文件名编辑出现时的动画终点。
- [x] `:app:assembleDebug` 编译通过。
- [x] 生成发布日志 `docs/features/timely-digit-typography/debug-updates/update-20260705112137.md`。
- [x] 已通过 `:app:publishDebugUpdate` 发布阿里云 debug update `202607050321`。

## Phase 3 Doing 计时器进场修复 - 2026-07-05

- [x] 复现代码路径：`TimelyClockView` 迁移后在布局中没有初始 `alpha=0`，非无限倒计时又在 `updateTimeViews()` 中直接设为 `alpha=1`，导致打开 DoingActivity 时读数抢先显示。
- [x] `activity_doing.xml` 中将 `clock_time_doing` 初始 alpha 改为 0。
- [x] 移除非无限倒计时在 `updateTimeViews()` 里的立即可见逻辑。
- [x] `playEnterAnimations()` 对普通倒计时和无限时长都执行 clock 淡入；普通倒计时淡入到 1.0，无限时长淡入到 0.76。
- [x] 静态检查确认没有残留的提前 `alpha=1` 路径。
- [x] `:app:assembleDebug` 编译通过。
- [x] 生成发布日志 `docs/features/timely-digit-typography/debug-updates/update-20260705113413.md`。
- [x] 已通过 `:app:publishDebugUpdate` 发布阿里云 debug update `202607050334`。

## Phase 3 进场动画与录音完成态调整 - 2026-07-05

- [x] 对比改 `timelytextview` 模块前的 `8cad4745`：旧 `TimelyView` 初始 `controlPoints=null`，普通倒计时不做整体 alpha 淡入，而是第一次计时更新时由自身 morph 出现。
- [x] `TimelyClockView` 增加 `animateIn(visibleMillis)`，从 `-1` 空形态展开到当前读数，并在动画启动前先放到起始形态，避免最终数字闪现。
- [x] DoingActivity 普通倒计时进场改为调用 `animateIn` 并直接置 `alpha=1`；无限时长仍用 alpha 出现，目标 alpha 改为 0.96。
- [x] 录音完成态略向上移动：文件名区域停止 translation 从 32dp 改为 24dp，计时器停止目标顶部从 90dp 改为 82dp；保留用户手动调整后的准备/录音阶段 top margin。
- [x] `:app:assembleDebug` 编译通过。
- [x] 生成发布日志 `docs/features/timely-digit-typography/debug-updates/update-20260705114337.md`。
- [x] 已通过 `:app:publishDebugUpdate` 发布阿里云 debug update `202607050343`。

## Phase 3 animateIn 双触发修复与录音接入 - 2026-07-05

- [x] 静态调用链确认双触发：`playEnterAnimations()` 主动 `animateIn(leftTime)` 是第一遍，`DoingService` 首次 tick 从 `[-1…-1]` 到当前读数的 `playTimelyAnimation()` 是第二遍。
- [x] 移除 DoingActivity 普通倒计时进场阶段的主动 `animateIn`；普通倒计时在 `playEnterAnimations()` 中保持 alpha 0。
- [x] 在 `playTimelyAnimation()` 中先把 `TimelyClockView` alpha 设为 1，再播放服务首次 tick 触发的 `animateDigits`，只保留后一遍可见动画。
- [x] 录音 dialog 初次配置和重录回准备态改用 `animateIn(0L)`，让 `00:00:00` 也从空形态出现；开始录音时仍用 `setTimeMillis(0L, false)` 固定初值，不重复出现动画。
- [x] 录音完成态计时器停止目标顶部从 82dp 改为 80dp，文件名区域保持 24dp 停止 translation。
- [x] `:app:assembleDebug` 编译通过。
- [x] 生成发布日志 `docs/features/timely-digit-typography/debug-updates/update-20260705115145.md`。
- [x] 已通过 `:app:publishDebugUpdate` 发布阿里云 debug update `202607050352`。

## Phase 3 无限呼吸与录音首次 animateIn 调整 - 2026-07-05

- [x] 定位无限时长 alpha 掉到 0 的原因：旧代码使用 `1 - currentAlpha` 作为下一次呼吸目标，当前 alpha 接近 1 时下一次目标接近 0。
- [x] 将无限时长呼吸改为显式 1.0 / 0.75 两端切换，进场淡入到 1.0。
- [x] 录音 dialog 首次 `animateIn(0L)` 延迟 160ms 执行，让它更接近下方录音波浪动画启动；如果用户在延迟期间开始录音，则取消这次准备态出现动画。
- [x] `:app:assembleDebug` 编译通过。
- [x] 生成发布日志 `docs/features/timely-digit-typography/debug-updates/update-20260705115919.md`。
- [x] 已通过 `:app:publishDebugUpdate` 发布阿里云 debug update `202607050359`。

## Phase 3 录音 animateIn 仅首次出现 - 2026-07-05

- [x] 保留 `configureClockView` 阶段的延迟 `mClockIntro`，用于 dialog 首次出现时播放 `animateIn(0L)`。
- [x] `stoppedToPrepared()` 不再调用 `animateIn(0L)`；重录回准备态时直接 `setTimeMillis(0L, false)` 静态复位到 `00:00:00`。
- [x] 重录回准备态时取消任何待执行的 `mClockIntro` callback，避免重复出现动画。
- [x] `:app:assembleDebug` 编译通过。
- [x] 生成发布日志 `docs/features/timely-digit-typography/debug-updates/update-20260705121246.md`。
- [x] 已通过 `:app:publishDebugUpdate` 发布阿里云 debug update `202607050413`。

## Phase 3 字体宽度自适应 - 2026-07-05

- [x] `TimelyClockView.onDraw` 按 `min(内容高度, 可用宽度 / widthUnits)` 计算实际绘制尺度，宽字体整体缩小到 view 内，避免贴边或溢出。
- [x] DoingActivity 的 `clock_time_doing` 改为 `match_parent`，左右各留 24dp；动态 `sizeClockView` 保持 `MATCH_PARENT` 宽度，只调整高度。
- [x] 录音 dialog 的 `clock_record_audio` 改为 `match_parent`，在 280dp dialog 内左右各留 16dp。
- [x] `:app:assembleDebug` 编译通过。
- [x] 生成发布日志 `docs/features/timely-digit-typography/debug-updates/update-20260705122446.md`。
- [x] 已通过 `:app:publishDebugUpdate` 发布阿里云 debug update `202607050425`。

## Phase 4 设置字体选择器重设计 - 2026-07-05

- [x] 记录用户确认的 chooser 视觉规则：page tab、字体名称在上 / 预览在下、未选中渐变 ripple、选中整行 accent 渐变、预览字形按选中态在 accent 渐变与白色之间切换。
- [x] 扩展 `TimelyView.renderClock` 支持整组连续渐变字形和统一 90% -> 100% alpha mask。
- [x] 重做 `DoingDigitStyleDialogFragment` 的 tab 样式、行布局、选中态和触摸反馈。
- [x] 未选中 tab 通过纯色 `ThingBackground` 清理 pending text shader，避免快速切换后旧渐变文本状态回写。
- [x] 按反馈将 dialog 宽度改为 280dp，标题改为 accent 渐变，字体名称统一改为提示性文本颜色。
- [x] `:app:assembleDebug` 编译通过。
- [x] 生成发布日志 `docs/features/timely-digit-typography/debug-updates/update-20260705124953.md`。
- [x] 已通过 `:app:publishDebugUpdate` 发布阿里云 debug update `202607050452`。

## Phase 4 设置字体选择器微调 - 2026-07-05

- [x] 将字体选择 dialog 宽度从屏幕 92% 改为 280dp，对齐设置应用语言的 `ChooserDialogFragment`。
- [x] 标题改为 App 默认 accent + accent2 渐变文字。
- [x] 字体名称文字统一改为 `app_chrome_on_surface_hint`。
- [x] `:app:assembleDebug` 编译通过。
- [x] 生成发布日志 `docs/features/timely-digit-typography/debug-updates/update-20260705125650.md`。
- [x] 已通过 `:app:publishDebugUpdate` 发布阿里云 debug update `202607050457`。

## Phase 4 设置字体选择器滚动与选中态微调 - 2026-07-05

- [x] 选中字体行的字体名称改为偏白，未选中字体名保持提示性文本颜色。
- [x] 打开 dialog 或切换实心 / 描边后自动滚动到当前选中的字体行。
- [x] 在实心 / 描边 tab 下方加入顶部滚动指示线，列表顶部时隐藏，向下滚动后显示。
- [x] `:app:assembleDebug` 编译通过。
- [x] 生成发布日志 `docs/features/timely-digit-typography/debug-updates/update-20260705130417.md`。
- [x] 已通过 `:app:publishDebugUpdate` 发布阿里云 debug update `202607050504`。

## Phase 4 设置字体选择器选中字体名亮度微调 - 2026-07-05

- [x] 选中字体行的字体名称从纯白改为 App Chrome tertiary on-color 层级，让它保持偏白但更接近提示性文本。
- [x] `:app:assembleDebug` 编译通过。
- [x] 生成发布日志 `docs/features/timely-digit-typography/debug-updates/update-20260705130800.md`。
- [x] 已通过 `:app:publishDebugUpdate` 发布阿里云 debug update `202607050508`。

## Phase 4 录音计时器左右边距微调 - 2026-07-05

- [x] 将录音 dialog 中 `clock_record_audio` 的左右边距从 16dp 改为 24dp。
- [x] `:app:assembleDebug` 编译通过。
- [x] 生成发布日志 `docs/features/timely-digit-typography/debug-updates/update-20260705131724.md`。
- [x] 已通过 `:app:publishDebugUpdate` 发布阿里云 debug update `202607050517`。
