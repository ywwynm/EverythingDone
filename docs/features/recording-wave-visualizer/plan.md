# 实现计划 — 录音波形可视化改造

对应决策见 [decisions.md](decisions.md)（D1–D9）。目标：把录音对话框的"底座 + 竖直柱子"
改造为"一片由记事颜色填充、会随音量起伏的多层水体波浪"。

## 一、渲染架构（VoiceVisualizer.kt 重写）

放弃现有的"离屏 `Bitmap` + 每次 `receive` new 一个 `Handler` 重绘"的柱子画法，改为
**自驱动帧循环 + 缓动目标**：

- **帧循环**：`onAttachedToWindow` 启动，`onDetachedFromWindow` 停止。`onDraw` 末尾在仍
  附着窗口时调用 `postInvalidateOnAnimation()` 排下一帧（录音对话框生命周期短、且需要
  静音微动，持续动画可接受；对话框消失即随 view detach 停止）。
- **相位推进**：用 `System.nanoTime()` 求帧间 `dt`，每层相位 `phase += driftSpeed * dt`，
  形成横向流动（D4）。
- **音量→目标**：`receive(volume)`（`AudioRecorder` 后台线程每 100ms 调用）只更新
  `volatile` 的 `targetAmp` / `targetLevel`，不再自己 new Handler / invalidate。映射：
  `norm = clamp01((volume - MIN_DB) / (MAX_DB - MIN_DB))`，`targetAmp = norm`、
  `targetLevel = norm`。`stopListening` 里的 `receive(0)` 自然让目标回落到静息。
- **缓动**：每帧 `current += (target - current) * (1 - exp(-dt/tau))`；振幅 `tau≈90ms`
  更跟手，水位 `tau≈180ms` 更从容（D4 平滑过渡）。
- **静音微动**：实际振幅 `amp = max(currentAmp, IDLE_AMP)`，`IDLE_AMP≈0.05`，配合相位
  持续推进，安静时水面仍极轻荡漾、不僵死（D4）。

## 二、几何（水体从底部向上填充）

- 视图坐标：水从底边向上填充。水面高度 `waterH = height * (restFrac + level*(maxFrac-restFrac))`，
  `restFrac≈0.28`、`maxFrac≈0.62`（`maxFrac` 上限保证水面最高时仍在对话框下部，
  不淹没上方计时文本 —— D3/D8）。基准水面 `surfaceY = height - waterH`。
- 每层是一条正弦水面 + 下方填充：`y(x) = surfaceY - layerAmp*sin(k*x + phase + layerPhaseOffset)`，
  用 `Path`（`moveTo(0,bottom)`→沿 x 采样 `lineTo`→`lineTo(width,bottom)`→`close`）填充到底边。
- 4 层（D5）参数（back→front，初值，**待真机微调**）：
  - 振幅系数 `[0.7, 0.85, 1.0, 1.15]`，`layerAmpPx = ampNorm * MAX_AMP_DP(px) * factor`；
  - 相位偏移 `[0, 1.3, 2.6, 3.9]` rad；
  - 横向波数（跨宽度周期数）`[1.4, 1.8, 2.2, 2.6]`；
  - 漂移速度 `[+0.8, -1.0, +1.25, -1.5]` rad/s（正负交替更灵动）。

## 三、颜色派生（核心）

新增 `fun setThingBackground(bg: ThingBackground)`，替换现有 `setRenderColor`
（保留 `setRenderColor` 作为纯色兜底转 `ThingBackground.pure`）。宽度已知时（`onSizeChanged`）
预构建 4 层 `Paint`：

- **明度 + 透明度阶梯**（back→front，初值，**待真机微调**）：
  - 变亮量 `lighten = [0.55, 0.38, 0.20, 0.06]`（复用 `BackgroundUtil.lighter`；最前层近原色）；
  - 层透明度 `alpha = [0.30, 0.42, 0.56, 0.72]`。
- **D6 纯色**：每层色 = `lighter(base.color, lighten[i])`，`paint.color` 套 `alpha[i]`。
- **D7 渐变**：每层 `paint.shader = LinearGradient(0,0,width,0, [lighter(c1,lighten[i]), lighter(c2,lighten[i])], null, CLAMP)`，
  再用 `paint.alpha = alpha[i]*255`。渐变**统一取横向**（忽略 8 向 orientation，只规整扫向，
  颜色不变）。宽度变化时重建 shader。
- 视图整体明暗（0.16/1.0）仍由 Fragment 的 `view.animate().alpha()` 控制（D9），与层内
  相对透明度相乘。

## 四、布局改造（fragment_record_audio.xml）

现有两段式（上 204dp 含 visualizer+计时+文件名；下 96dp 含 base+按钮）改为**单一根
FrameLayout（280×300）**，水体作为铺满下部的底层：

- `VoiceVisualizer`：`match_parent`（水面被 `maxFrac` 限高，上部自然留白给文字）。
- 计时 `Chronometer`、文件名 `LinearLayout`、按钮 `LinearLayout`（gravity bottom）叠在其上，
  位置与位移动画沿用现值。
- **删除** `view_voice_visualizer_base`（水体取代底座，颜色身份移交水体）。

## 五、Fragment 改造（AudioRecordDialogFragment.kt）

- 删除 `mBase` 字段、`findViewById`、`applyBackground(mBase,…)`、以及三处 `mBase.animate().alpha` 。
- 取色：`val bg = getAccentBackground() ?: ThingBackground.pure(getAccentColor())`；
  `mVisualizer.setThingBackground(bg)`。EditText 高亮/手柄色仍用 `bg.representativeColor()`。
- **侧边图标柔和衬底（D8）**：给"重录 / 取消"两个 `ImageView` 加一层程序化圆形半透明衬底
  ——`GradientDrawable(OVAL)`，色为 `app_chrome_surface_elevated` 套 ~45% alpha，设为
  `background`；点按涟漪改设为 `foreground`（minSdk 26 支持）。主 FAB 已有悬浮面，不动。
- 状态编排（D9）不变：只把原先针对柱子/底座的 alpha 动画收敛为只作用于 `mVisualizer`。

## 六、attrs.xml

`VoiceVisualizer` 的 `numColumns / renderRange / renderType` 变为无用；保留声明避免波及
其它（无引用），XML 里移除 `numColumns/renderRange` 用法。`renderColor` 保留作兜底。

## 七、兼容与性能

- minSdk 26：`postInvalidateOnAnimation`、`View.foreground`、`LinearGradient` 均可用。
- 帧循环仅在对话框显示期间运行；`onDetachedFromWindow` 停止，无泄漏。
- 每帧仅 4 条 `Path` + 4 次 `drawPath`，无离屏 Bitmap，开销低于旧柱子逐格 `drawRect`。

## 八、验证与发布

1. `:app:assembleDebug` 编译通过，产出 `app-debug.apk`。
2. 新建发布日志 `docs/features/recording-wave-visualizer/debug-updates/update-<yyyyMMddHHmmss>.md`
   （首个 `## ` 段为发布说明）。
3. `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-<code>.md" --console=plain --no-configuration-cache`
   发布到阿里云 debug 通道，用户在真机测试后再微调数值。
4. 不自动安装到物理设备（遵守 memory/preferences.md）。

## 九、数值待真机微调清单

`MIN_DB/MAX_DB`、`tau`、`IDLE_AMP`、`restFrac/maxFrac`、`MAX_AMP_DP`、各层
振幅/波数/相位/漂移、`lighten[]/alpha[]` 阶梯、侧边衬底 alpha。全部集中为
`VoiceVisualizer` 顶部常量，便于按用户反馈调整。
