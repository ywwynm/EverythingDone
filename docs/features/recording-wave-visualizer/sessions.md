# 会话记录 — 录音波形可视化改造

## 2026-07-02 — D21 个性化错峰响应

- 用户反馈 D20 过于平稳、每个浪都差不多，野性不如上一版；明确偏好随机、灵动、每个浪有自己的个性，不要同一时刻一起上升/下降。
- 修复 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：回退 D20 的质量门控、严格谱式权重、主形/细纹强分离、空间平滑与 headroom 前置限幅，恢复更自由的多分量频率 / 权重随机。
- 新增 `incoming → delayed target → current` 响应链：`receive(VoiceAudioFrame)` 只写入一帧音频目标，主线程保存最近若干帧目标；每层 / 每分量按自己的历史延迟取样，并用独立 attack / release 追随。
- 瞬态浪涌改为每层局部触发：不同层拥有独立延迟、强度、尖峰加成和衰减时间，让声音骤起时形成随机先后顺序。
- 保留 D19 的防颤动修复：即时振幅随机仍为连续慢速包络，瞬态仍有门限；但提高频率 / 权重 / 细纹自由度以恢复灵动感。
- 验证与发布：`:app:assembleDebug` 通过（`BUILD SUCCESSFUL in 2s`）；`:app:publishDebugUpdate` 发布到阿里云 debug 通道，code **202607020607**，日志
  [update-20260702140522.md](debug-updates/update-20260702140522.md)。未安装设备，未 commit。

## 2026-07-02 — D20 波形美感收束

- 用户反馈：小颤动修复后，仍偶尔感觉波浪形状不够好看；要求按调研推荐推进。
- 修复 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：把波形生成从“所有分量都参与轮廓”改为“低频 3 个分量负责主形，高频 / air 只做轻量细纹”；分量权重改为更严格的谱式递减。
- 新增实例级质量门控：每次打开录音仍无种子随机，但生成参数后会检查中等音量下的轮廓范围、局部极值数量、细纹能量占比和平均曲率；明显过密 / 过碎的参数会重抽。
- 新增 steepness cap：波峰尖化只对足够大的主浪生效，且实际坡度过高时会压低 crest factor，避免小碎峰也被尖化。
- 绘制层面新增轻量空间平滑与顶部 headroom 前置限制，减少小折皱和顶部 soft limit 事后压扁导致的奇怪形状。
- 验证与发布：`:app:assembleDebug` 通过（`BUILD SUCCESSFUL in 3s`）；`:app:publishDebugUpdate` 发布到阿里云 debug 通道，code **202607020457**，日志
  [update-20260702125617.md](debug-updates/update-20260702125617.md)。未安装设备，未 commit。

## 2026-07-02 — D19 小颤动修复

- 用户反馈：波浪已经上升后，随着音量和频段参数变化，浪体内部偶尔出现小颤动。
- 诊断：不是本地可自动复现的崩溃/错误；结合代码判断，主要由 `receive(VoiceAudioFrame)` 每 100ms 重新应用即时振幅随机、短窗 FFT 高频波动、微小瞬态反复触发叠加造成。
- 修复 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：去掉每次采样即时 `AMP_JITTER`，改为每个波分量连续慢速随机包络；`AMP_JITTER` 收敛为 0.32；高频 / air 驱动权重略降，`BAND_MIX` 0.78→0.70；`transient` 新增 0.20 门限和 180ms 冷却。
- 预期效果：保留“跟声音内容变”和波浪自然不规则感，但减少波浪已经升高后的小幅目标跳变。
- 验证与发布：`:app:assembleDebug` 通过（`BUILD SUCCESSFUL in 3s`）；`:app:publishDebugUpdate` 发布到阿里云 debug 通道，code **202607020439**，日志
  [update-20260702123828.md](debug-updates/update-20260702123828.md)。未安装设备，未 commit。

## 2026-07-02 — D17/D18 实现：VoiceAudioFrame + FFT 频段 + 瞬态浪涌

- 用户确认推进推荐方案：新增 `VoiceAudioFrame`，做 5 个 FFT 频段 + 瞬态浪涌，并同步深化 Gerstner 式视觉形态。
- 新增 [VoiceAudioFrame.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceAudioFrame.kt)：一帧轻量音频特征，包含 `loudness`、`low`、`lowMid`、`mid`、`high`、`air`、`transient`，全部归一化到 0..1。
- 改造 [AudioRecorder.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/AudioRecorder.kt)：录音线程继续用 `AudioRecord` 读 PCM；新增 `VoiceAudioAnalyzer`，把最近 2048 个立体声样本混为单声道，计算 RMS/分贝、5 个 FFT 频段和瞬态；不引入第三方依赖，自写 radix-2 FFT。
- 改造 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：主入口变为 `receive(VoiceAudioFrame)`，保留 `receive(Int)` 兼容；5 个分量分别响应 5 个频段，低频推大浪、高频推细纹；`transient` 触发短时浪幅加成和波峰尖化。
- 视觉深化：在已有波谷压浅基础上增加波峰尖化；分量漂移速度改为与空间频率相关的近似频散关系；分量权重改为谱式递减 + 少量随机。
- 文档更新：新增 D17/D18 决策，README 改为当前数据流，followups 改为“已实现，待真机调参”。
- 验证与发布：`:app:assembleDebug` 通过（`BUILD SUCCESSFUL in 6s`）；`:app:publishDebugUpdate` 发布到阿里云 debug 通道，code **202607020353**，日志
  [update-20260702115257.md](debug-updates/update-20260702115257.md)。未安装设备，未 commit。

## 2026-07-02 — 自然波浪与音频驱动方案调研

- 用户在已有 Gerstner 式“平谷”实现基础上，继续要求调研更自然的波浪物理规律、音乐/音频可视化数据，以及二者在当前录音对话框中的结合方式。
- 结合外部资料与当前代码确认：`VoiceVisualizer` 已是 6 层 × 4 分量的随机多分量合成，含音量非线性、顶部软限、波谷压浅；`AudioRecorder` 仍每 100ms 只向可视化推送一个分贝整数。
- 物理侧可继续在不动音频管线的前提下深化：增加 Gerstner 式波峰尖化、把分量速度与频散关系绑定、用更像海浪谱的能量衰减替换部分随机权重，并保留避免折叠/顶到计时的软限。
- 音频侧若要“跟着音色/频率内容变”，需要新增轻量 FFT 与特征帧：RMS/响度控制整体强度，低/中/高频段分别驱动大浪、中浪、细纹，spectral flux 或响度差分驱动一次性浪涌。
- 结论：推荐分阶段推进，先做视觉物理深化作为低风险版本，再决定是否改 `AudioRecorder` 接口加入完整频段与瞬态。

## 2026-07-02 — 设计定案 + 首个可测版本发布

- 通过 grill-with-docs 逐条确认设计（D1–D9，见 [decisions.md](decisions.md)）：多层半透明填充
  波、合并成水体、音量驱动浪高+水位、横向流动+平滑过渡+静音微动、4 层、纯色同色系深浅
  阶梯、渐变整条按层复用、侧边图标柔和衬底、状态沿用现有明暗编排。
- CONTEXT.md 新增词条 **Voice Waveform**，并把录音对话框明确为 **Hybrid Chrome Surface**。
- 实现：
  - 重写 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)
    为自驱动帧循环（`onDraw` 末尾 `postInvalidateOnAnimation`）+ 缓动目标 + 4 层 `Path` 水体；
    `receive` 只写 `volatile` 目标；新增 `setThingBackground`；可调数值集中在 companion 常量。
  - [fragment_record_audio.xml](../../../app/src/main/res/layout/fragment_record_audio.xml)
    扁平化为单一根 FrameLayout，水体铺满下部，删除底座。
  - [AudioRecordDialogFragment.kt](../../../app/src/main/java/com/ywwynm/everythingdone/fragments/AudioRecordDialogFragment.kt)
    移除 `mBase`，改用 `setThingBackground`，侧边图标加程序化圆形半透明衬底。
- 验证与发布：`:app:assembleDebug` 通过；`:app:publishDebugUpdate` 发布到阿里云 debug 通道，
  code **202607011725**（versionCode 43 / 2.0.0）。日志
  [update-20260702012413.md](debug-updates/update-20260702012413.md)。
- 未 git commit，未装物理设备。等用户真机反馈后微调数值。

## 2026-07-02 — 第一轮真机反馈修复（限高 + 随机性）

- 用户反馈：① 对话框被限高，只剩水位高度；② 浪峰高度全一样、声音大时全体同升，缺少
  时间与空间随机性。
- 修复①（D11）：根因是 `BaseDialogFragment` null-parent 充气丢弃根节点尺寸，FrameLayout
  叠层塌到按钮行 96dp；根节点声明 `minWidth/minHeight`（280×320dp，较原 300dp 加高 20dp）。
- 修复②（D10）：每层加次谐波（非整数波长比、反向漂移）；`receive` 每次采样对每层目标
  振幅加 ±35% 抖动（volatile 数组整体换新发布）；各层 TAU 不同（0.16→0.08 前快后慢）；
  另加每层呼吸包络与基线微漂。`MAX_AMP_DP` 12→13。
- `:app:assembleDebug` 通过；发布 code **202607020054**，日志
  [update-20260702085409.md](debug-updates/update-20260702085409.md)。仍未 commit。

## 2026-07-02 — 第二轮真机反馈（水位/振幅再平衡，D12）

- 用户反馈：基础水位太高、浪显小、波动不够大、随机性再加强；保留"音量大整体上升"、每道
  浪高度随机。
- 仅调 `VoiceVisualizer` 常量：`REST_FRAC` 0.28→0.16、`MAX_AMP_DP` 13→20、`MAX_FRAC`
  0.62→0.56、`AMP_JITTER` 0.35→0.5、`AMP_FACTOR` [0.6,0.85,1.05,1.25]、`WAVELENGTHS`
  [1.6,2.2,2.8,3.4]、`HARMONIC_WEIGHT` 上调、`ENV_DEPTH` 0.22→0.28、`WANDER_DP` 3→5。
- `:app:assembleDebug` 通过；发布 code **202607020150**，日志
  [update-20260702094958.md](debug-updates/update-20260702094958.md)。未 commit。

## 2026-07-02 — 第三轮真机反馈（幅度 + 节奏再拉大）

- 用户反馈：起伏节奏与浪幅度再多拉大一些。
- 仅调 `VoiceVisualizer` 常量：`MAX_AMP_DP` 20→24、`AMP_FACTOR` [0.55,0.85,1.15,1.35]、
  抖动 `AMP_JITTER` 0.5→0.6 且 receive 上限 1.25→1.3、`DRIFT`/`DRIFT2` 横向流速约 1.6x、
  `ENV_DEPTH` 0.28→0.34、`ENV_SPEED` 约 1.5x、`WANDER_DP` 5→6、`WANDER_SPEED` 加快；
  为容纳更大浪峰把 `MAX_FRAC` 0.56→0.50（上升趋势仍保留）。
- `:app:assembleDebug` 通过；发布 code **202607020216**，日志
  [update-20260702101612.md](debug-updates/update-20260702101612.md)。未 commit。

## 2026-07-02 — 第四轮真机反馈（多分量合成，消除程序化感，D13）

- 用户反馈：一声大声音下水平方向几个波峰几乎同时、等高升起，太程序化、不灵动。
- 根因：每层单正弦×整层统一振幅。改为每层 3 条不同频率/速度/相位、各自独立跟随音量
  （TAU 各异 + 每分量独立抖动）的波分量叠加；静音微动改为叠一条极小持续波；`TAU_LEVEL`
  0.18→0.25、`MAX_AMP_DP` 24→28。详见 decisions D13。
- `:app:assembleDebug` 通过；发布 code **202607020225**，日志
  [update-20260702102516.md](debug-updates/update-20260702102516.md)。未 commit。

## 2026-07-02 — 第五轮真机反馈（扩到 6 层 6 分量 + 去固定种子，D14）

- 用户反馈：效果好不少；层数 4→6、每层分量 3→6、再野一些。
- 先发了固定种子的 6×6 版本（code **202607020240**，过渡版），用户随即要求"不要固定种
  子"；遂改为结构参数在每个实例创建时用无种子 `mRandom` 现场生成——每次打开录音波形都不
  同。`AMP_JITTER` 0.6→0.7。详见 decisions D14。
- 可调项从固定数值矩阵变为 `init` 里的生成区间公式。
- `:app:assembleDebug` 通过；发布 code **202607020244**（取代 202607020240），日志
  [update-20260702104355.md](debug-updates/update-20260702104355.md)。未 commit。

## 2026-07-02 — 第六轮真机反馈（声音由浪振幅体现 + 加大野度，D15）

- 用户反馈：① 声音大时基础水位匀速上升带动浪整体升高，不好——希望水位少变化、由浪体现
  声音、浪变化幅度更大；② 按建议加大野度。
- 改动（修订 D3）：`REST_FRAC`/`MAX_FRAC` 0.16/0.50 → 0.24/0.30（水位近固定）；`MAX_AMP_DP`
  28→48；`mK`/`mDrift`/`mCompWeight` 生成区间加大（频率更宽、流速更大、高频占比更高）。
- `:app:assembleDebug` 通过；发布 code **202607020251**，日志
  [update-20260702105136.md](debug-updates/update-20260702105136.md)。未 commit。

## 2026-07-02 — 第七轮真机反馈（抬水位 + amp60 + 音量非线性曲线）

- 用户：基础水位略高些；`MAX_AMP_DP`=60；音量映射加非线性曲线。
- 改动：`REST_FRAC`/`MAX_FRAC` 0.24/0.30 → 0.30/0.36；`MAX_AMP_DP` 48→60；receive 新增
  `CURVE_GAMMA=1.8` 幂曲线（`norm.pow`），压低中小音量、突出大音量。
- `:app:assembleDebug` 通过；发布 code **202607020257**，日志
  [update-20260702105705.md](debug-updates/update-20260702105705.md)。未 commit。

## 2026-07-02 — 第八轮真机反馈（水位再抬 0.12 + 顶部软限）

- 用户：基础水位再抬高 0.12。
- 改动：`REST_FRAC`/`MAX_FRAC` 0.30/0.36 → 0.42/0.48；因水位高 + 大浪会顶到计时，新增顶部软
  限（softplus 平滑上界，`TOP_LIMIT_DP=64` / `SOFT_DP=14`，浪尖最高约 74dp，计时底 60dp 之
  上留约 14dp）。正常浪不受影响，只有很高的浪尖被柔和压圆。
- `:app:assembleDebug` 通过；发布 code **202607020305**，日志
  [update-20260702110518.md](debug-updates/update-20260702110518.md)。未 commit。

## 2026-07-02 — 第九轮（水位微调 0.36/0.42）

- 用户：水位改到 0.36/0.42。`REST_FRAC`/`MAX_FRAC` 0.42/0.48 → 0.36/0.42，其余不变。
- 发布 code **202607020308**，日志
  [update-20260702110803.md](debug-updates/update-20260702110803.md)。未 commit。

## 2026-07-02 — 第十轮（层数改回 4）

- 用户：改回 4 层看观感。`LAYERS` 6→4（`COMPONENTS` 仍 6）。只改一个常量，其余矩阵 / 阶梯 /
  绘制循环按 LAYERS×COMPONENTS 自动适配。
- 发布 code **202607020311**，日志
  [update-20260702111137.md](debug-updates/update-20260702111137.md)。未 commit。

## 2026-07-02 — 第十一轮（波谷不对称 + 调研）

- 用户自调为 6 层 × 4 分量；反馈波谷下沉过深，要谷浅峰不变、更合物理；并请调研波浪物理 /
  音频可视化数据 / 二者结合。
- 调研结论：真实水波 Gerstner/摆线波＝峰尖谷平不对称；音频可视化＝时域 RMS/响度（平滑、
  当前所用）vs 频域 FFT 分频段（低/中/高，指数带宽），最佳实践两者结合。来源见对话（Wikipedia
  Trochoidal wave；dlbeer FFT vis；ourcodeworld；medium web-audio）。
- 落地 D16：波谷不对称压浅（`TROUGH_FACTOR`/`TROUGH_SOFT`）。FFT 频段驱动列为可选深化，待
  用户定夺（需改 AudioRecorder 加 FFT，接口从分贝 Int 变频段数组）。
- 发布 code **202607020327**，日志
  [update-20260702112643.md](debug-updates/update-20260702112643.md)。未 commit。

## 2026-07-02 — D22 二次音节颤动修复

- 用户反馈：连续发出两个“啦”时，部分已上升到峰顶的波浪会突然再次改变峰高，出现“动画消失”、生硬、僵硬的观感。
- 诊断：D21 的 layer-local surge 保留了随机错峰，但在倒计时结束时直接写入 `mLayerCurrentSurge`，会瞬间改变该层的振幅放大和波峰尖化；同时 100ms 音频目标刷新下，个别分量在短 attack 中可能单帧变化过大。
- 修复 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：新增 `mLayerSurgeTarget`，延迟结束只写目标，当前浪涌按 `SURGE_ATTACK_TAU` / `SURGE_RELEASE_TAU` 平滑追随；新增 `TARGET_RISE_SLEW` / `TARGET_FALL_SLEW` 限制分量目标单帧变化。
- 保留上一版的随机错峰和每层个性化响应，不回退到 D20 的统一收敛风格。
- 验证与发布：`:app:assembleDebug` 通过（`BUILD SUCCESSFUL in 2s`）；`:app:publishDebugUpdate` 发布到阿里云 debug 通道，code **202607020624**，日志 [update-20260702142338.md](debug-updates/update-20260702142338.md)。未使用 adb，未安装设备，未 commit。

## 2026-07-02 — D23 波峰圆润化

- 用户反馈：D22 效果不错，但有些波峰宽度过窄、峰高过高，显得太尖锐；希望先优化但不要削弱声音大小和音频内容表达。
- 判断：尖锐感主要来自正峰尖化、多分量相位叠加和较大的 `MAX_AMP_DP`。直接降低整体振幅或频段权重会让可视化反馈变钝，因此本轮只处理正峰曲率。
- 修复 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：给 `crestFactor` 增加上限；`shapeWave()` 正峰分支新增高峰 taper 和圆角软限制，降低极端窄峰的针尖感。
- 未改 `MAX_AMP_DP`、`BAND_MIX`、`SURGE_AMP_BOOST`、RMS/FFT 输入映射，声音大小和频段驱动仍按原链路表达。
- 验证与发布：`:app:assembleDebug` 通过（`BUILD SUCCESSFUL in 3s`）；`:app:publishDebugUpdate` 发布到阿里云 debug 通道，code **202607020636**，日志 [update-20260702143559.md](debug-updates/update-20260702143559.md)。未使用 adb，未安装设备，未 commit。
