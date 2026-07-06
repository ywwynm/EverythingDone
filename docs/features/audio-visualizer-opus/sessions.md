# 会话记录 — 录音波浪可视化（Opus 版）

## 2026-07-06 回退到 Phase 1 完成阶段（D58）

- **决定**：用户要求回退到 Phase 1 完成阶段，也就是 debug code `202607060908` 的版本（`202607060927` 的上一个版本）。
- **改动**：从 `WaveVisualizerOpus` 移除 D52-D57 的倒置下坠/空中水片路径，包括下坠状态、触发逻辑、快照缓存、触底冲击注入、下坠绘制路径和 `DROP_*` 常量；保留 D51 的 Phase 1 自由液面速度场、1 阶 slosh 回荡、浪包平流、静止门控、6 层错落、D47 峰/谷口径复原和面积守恒。
- **验证与发布**：目标文件关键词扫描确认无下坠相关符号残留；本地 `:app:assembleDebug` 通过。新增发布日志 [update-20260706193325.md](debug-updates/update-20260706193325.md)，执行 `:app:publishDebugUpdate -PdebugUpdateNotesFile=docs/features/audio-visualizer-opus/debug-updates/update-20260706193325.md` 成功，debug update code 为 `202607061133`，远端 `latest.json` 已更新到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。未使用 adb。

## 2026-07-06 修正触底后反馈不足与斜向下坠提前翻面（D57）

- **反馈**：用户认为水坠落到新底部后没有足够物理反应，只像弹簧一样弹一下；期望出现水沿两侧壁爬升、挤压生成大波浪等效果。另一个问题是先倾斜再翻转时，斜向坠落初始高度像按未倾斜设置，快触底时水面瞬间翻转并闪烁。
- **调研**：查阅 sloshing / dam-break / wave impact 实验资料，确认撞底/撞壁后的典型表现是强非线性自由面、冲击压力、沿壁 run-up、回落、反射波和次级波；局部破碎/气泡常出现在冲击后，但对当前 Canvas 方案可用大尺度自由面与浅水速度场近似，不必新增粒子雨。
- **修复**：取消整摊水刚体弹簧反弹，触底后停止全局落体，把冲击速度注入浅水速度场：`DROP_IMPACT_SLOSH_GAIN` 44→82，`DROP_IMPACT_HEIGHT_GAIN` 18→34，形变改为中心凹陷、两侧 wall run-up、肩部大波。斜向坠落时缓存触发前 `mLastDropDepth`，触发后用 `mDropSnapshotDepth` 保持当时姿态厚度；`dropVisual` 在飞行中强制为 1，触底前 `snapshotWeight` 不再按 `fall` 提前淡出，触底后用 `mDropSettleBlend` 短暂恢复到新重力水面。
- **验证与发布**：本地 `:app:assembleDebug` 通过；静态检查确认旧刚体反弹常量已移除；数值检查确认冲击动量/高度注入强度约为上一版 1.9 倍，触底前快照权重保持 1。新增发布日志 [update-20260706183203.md](debug-updates/update-20260706183203.md)，执行 `:app:publishDebugUpdate -PdebugUpdateNotesFile=docs/features/audio-visualizer-opus/debug-updates/update-20260706183203.md` 成功，debug update code 为 `202607061032`，远端 `latest.json` 已更新到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。未使用 adb。

## 2026-07-06 修正倒置下坠后段水位暴涨与侧边斜切（D56）

- **反馈**：用户测试 D55 后认为整体还可以，但下坠接近新底部时，水位会突然升高到几乎占满 dialog，下一帧又恢复正常；轻微倾斜时，水体左右侧可能没有占据完整宽度，露出一道平整斜切面。
- **诊断**：水位暴涨来自空中水片复用了 `closeDistance = lerp(diag*2, dropDepth, dropAir)`。当 `dropAir` 很小但仍大于空中路径阈值时，仍走 `buildFallingWaterPath`，却拿到接近 `diag*2` 的厚度，等于一帧“无限深填充”。侧边斜切来自空中水片没有横向 overscan，路径两端闭合边在轻微倾斜时可能落在 dialog 裁剪区内。
- **修复**：空中水片始终传入有限水厚 `dropDepth`；普通 `buildGravitySurfacePath` 才使用 `diag*2` 的正常闭合深度。`buildFallingWaterPath` 在切向两端增加 `DROP_EDGE_OVERSCAN_DP`，把背面和前沿的闭合边推出裁剪区外。保留 D55 的 6 层快照和层级保持。
- **验证与发布**：本地 `:app:assembleDebug` 通过；静态检查确认空中路径只传 `dropDepth`，普通路径传 `normalCloseDistance`；数值检查确认旧逻辑在 `dropAir=0.03` 时厚度约 888px，新逻辑固定约 118px。新增发布日志 [update-20260706181258.md](debug-updates/update-20260706181258.md)，执行 `:app:publishDebugUpdate -PdebugUpdateNotesFile=docs/features/audio-visualizer-opus/debug-updates/update-20260706181258.md` 成功，debug update code 为 `202607061013`，远端 `latest.json` 已更新到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。未使用 adb。

## 2026-07-06 修正倒置下坠开始瞬间 6 层层级反转（D55）

- **反馈**：用户测试后指出，下坠开始时仍能看到瞬间层级反转，需要保留原本 6 层水的层级；只有真正坠落到 dialog 边缘之后，再自然恢复层级。当前水体高度在下坠开始时被缩减，用户判断也与层级反转有关。
- **诊断**：D54 只缓存前景层 `mLastSurfaceX/Y`，`buildFallingWaterPath` 对所有层共用前景快照；同时每层 `equilibriumLayerBaseV`、`layerBaseV`、`hCeil` 仍会在触发后立即按新重力/新层偏移重算。因此空中阶段不是旧 6 层一起坠落，而是“前景快照 + 新重力下的 6 层重排”，会产生层级反转和高度缩减。
- **修复**：把 `mLastSurfaceX/Y`、`mDropSnapshotX/Y` 扩展为 `Array(LAYER_COUNT) { FloatArray(RENDER_N) }`，普通状态缓存 6 层各自自由液面；倒置触发时复制完整 6 层快照。空中阶段每层路径的背面和前沿都优先由该层旧快照生成，保持原层级和可见高度；接近触底时淡出快照权重，首次触底后清除快照，让层级交回新重力下的正常面积守恒/层偏移。
- **验证与发布**：本地 `:app:assembleDebug` 通过；静态检查确认快照读写均带 `layer` 维度；数值检查确认 180° 翻转时快照反向匹配新切向，空中起点快照权重为 1、落地前为 0。新增发布日志 [update-20260706180345.md](debug-updates/update-20260706180345.md)，执行 `:app:publishDebugUpdate -PdebugUpdateNotesFile=docs/features/audio-visualizer-opus/debug-updates/update-20260706180345.md` 成功，debug update code 为 `202607061004`，远端 `latest.json` 已更新到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。未使用 adb。

## 2026-07-06 修正倒置下坠瞬间波浪面重置/换边（D54）

- **反馈**：用户测试后感觉确定下落的一瞬间会重置之前的波浪状态，像是先把水的顶部波浪面和非常平的底面颠倒，再用平底去算下坠；真正应该使用原本的顶部，也就是倒置后的新底部前沿。
- **诊断**：D53 虽然把下坠边缘做成变形水片，但代码没有保存翻转前自由液面。`triggerWaterDrop` 触发后立即切换 `mGravityX/Y`，下一帧 `drawWater` 在新重力坐标系里重算 `mSurfaceX/Y`，`buildFallingWaterPath` 再从这条新面推出下坠前沿，因此会出现用户感知到的重置、换边和平底起点。
- **修复**：普通状态下持续缓存前景层最后一帧自由液面 `mLastSurfaceX/Y`；倒置触发时，在重力切换前复制到 `mDropSnapshotX/Y`，并根据旧/新切向点积记录是否反向采样。空中阶段优先用该快照沿新重力方向推进并叠加 D53 的变形；接近落地时淡出到程序化前沿和新重力面积守恒水面；首次触底冲击后清除快照，让后续反弹交给新水面和 slosh 场。
- **验证与发布**：本地 `:app:assembleDebug` 通过；静态检查确认 `triggerWaterDrop` 会先 `captureDropSnapshot`；数值检查确认 180° 翻转时快照会反向匹配新切向，且下坠起点快照权重为 1、接近落地为 0。新增发布日志 [update-20260706175204.md](debug-updates/update-20260706175204.md)，执行 `:app:publishDebugUpdate -PdebugUpdateNotesFile=docs/features/audio-visualizer-opus/debug-updates/update-20260706175204.md` 成功，debug update code 为 `202607060952`，远端 `latest.json` 已更新到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。未使用 adb。

## 2026-07-06 修正倒置下坠“果冻块”观感（D53）

- **反馈**：用户测试 202607060927 后指出，倒置下坠不像水，而像一块果冻掉下去；当前实现像是直接把整体形状取反并下坠，下坠时底部甚至完全平齐。
- **调研与诊断**：重新查实时水动画资料后确认，height field / shallow water 适合稳定水面和晃动，但 waterfall、breaking wave、splash 这类脱离水面的现象需要粒子或局部网格补充。当前问题正是表示层把水当作刚体水带：上边界是自由液面，下边界只是沿重力方向平行闭合，因此必然出现平底和果冻感。
- **修复**：仍不恢复可见粒子雨，改为连续“变形水片”方案。`WaveVisualizerOpus` 新增下坠进度/年龄/冲击脉冲和下坠前沿采样数组；普通状态仍用 `buildGravitySurfacePath`，空中阶段改用 `buildFallingWaterPath`，让下边界按下落速度、进度、横向位置和层间相位产生不规则凸起、拉伸、边缘收缩和波纹；上边界叠加少量 falling turbulence，避免保持原样刚体平移；触底时除速度场冲击外，再给 `mSloshH` 注入中心凹陷与侧向抬升，形成铺展回荡。
- **验证与发布**：本地 `:app:assembleDebug` 通过；数值抽样确认示例参数下下坠前沿深度范围约 62px，不再是平行直边。新增发布日志 [update-20260706174158.md](debug-updates/update-20260706174158.md)，执行 `:app:publishDebugUpdate -PdebugUpdateNotesFile=docs/features/audio-visualizer-opus/debug-updates/update-20260706174158.md` 成功，debug update code 为 `202607060942`，远端 `latest.json` 已更新到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。未使用 adb。

## 2026-07-06 重新引入“整摊水从空中坠落”倒置效果（D52）

- **触发**：用户暂时搁置其他问题，要求实现“直接把手机颠倒时，旧顶部变新底部，水应从空中落下并到达新的底部”的效果。
- **实现**：在 `WaveVisualizerOpus` 中重新加入极简下坠状态，但限定为整摊水体的短暂自由落体：快速接近 180° 重定向时，先把稳定重力方向切到新底部，再用 `mDropOffset` 把 6 层水体沿新重力反方向暂时抬起；每帧按自由落体更新，触底后短暂反弹，并用冲击速度注入 Phase 1 浅水速度场，形成砸底后的回荡。绘制侧改为在空中阶段把水体闭合成有限厚度水带，接近底部后恢复原有整片填充；水量仍由面积守恒和音频水位决定，不新增粒子、水滴、边框或独立装饰层。
- **文档**：记录 D52，并在偏好中明确“倒置时要整摊水从空中落下”，但不恢复此前被撤回的粒子/水团方案。
- **验证与发布**：本地 `:app:assembleDebug` 通过；新增发布日志 [update-20260706172721.md](debug-updates/update-20260706172721.md)，执行 `:app:publishDebugUpdate -PdebugUpdateNotesFile=docs/features/audio-visualizer-opus/debug-updates/update-20260706172721.md` 成功，debug update code 为 `202607060927`，远端 `latest.json` 已更新到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。未使用 adb。

## 2026-07-06 撤回整个 Phase 2，回到 Phase 1 调参后状态（D51）

- **决定**：用户看过水体下坠版后决定撤回整个 Phase 2（下坠 + 粒子都不要），回到 Phase 1 调完参数的状态（202607060818）。
- **改动**：逐处反向撤销 Phase 2（`updateWaterDrop`/`mDropOffset`/`DROP_*`/水带填充/剥离触发等），Grep 确认 `WaveVisualizerOpus.kt` 无 Phase 2 残留符号。保留 Phase 1 全部（速度场晃动/1 阶本征模/平流/门控/6 层错落）+ D47 + 去均值。
- **发布**：`:app:assembleDebug` 通过，发 [update-20260706170749.md](debug-updates/update-20260706170749.md)，`:app:publishDebugUpdate` 成功，code `202607060908`（内容=202607060818）。未连真机。

## 2026-07-06 Phase 2 重做：整摊水体重力下坠（删除错误的粒子方案，D50）

- **现象**：用户测 202607060837（弹道水团粒子），"几个颜色奇怪的圆形粒子在空中乱飞、撞空气墙反弹"，完全不是要的效果。诉求=**6 层水作为整体从空中下坠、反弹**。
- **根因**：粒子方案彻底理解错——做成了离散水滴，而非水体整体下坠。
- **重做（D50）**：删除全部粒子代码（`WaterBlob`/`updateBlobs` 等 + Bitmap/RadialGradient 等导入 + BLOB 常量）。改为**整摊水体重力下坠**：快速大幅重定向把整摊水沿 -重力托到空中（`mDropOffset`），`buildGravitySurfacePath` 填充距离从 `far` 插值到 `columnDepth` → 水体成有限厚水带、下方露空气；停手后自由落体 + 落地反弹（bouncing ball）。6 层共用 `mDropOffset` 一起动；水带厚度=原填充深度→守恒；静止 `mDropOffset=0`→与原填充完全一致。**副产品：不再用粒子 → D48 对 D4 的破例实际未启用**。
- **发布**：`:app:assembleDebug` 通过，发 [update-20260706165643.md](debug-updates/update-20260706165643.md)，`:app:publishDebugUpdate` 成功，code `202607060857`。未连真机。
- **待办**：真机调下坠（`DROP_GRAVITY`/`DROP_HEIGHT_GAIN`/`DROP_RESTITUTION`/`DROP_GATE`）；确认整体下坠+反弹、倒置倒灌、不误触发/不悬停、静止零回归。

## 2026-07-06 倾斜物理 Phase 2（弹道水团 + Worthington 回弹 + 空中水量守恒）

- **实现**（`WaveVisualizerOpus`，建在 Phase 1 速度场上）：新增 `WaterBlob` + `updateBlobs`/`spawnBlobs`/`onBlobLand`/`ensureBlobSprite`/`drawBlobs`。快速大幅重定向（`mPeelStrength=|原始 dθ|`>`PEEL_GATE`，约>180°/s）从"高处空中"（沿重力顶壁一侧）剥离少量柔边水团（径向精灵、染记事本色）；沿真实重力弹道下落；砸底注入 crater 到速度场（复用 Phase 1 回荡）+ Worthington 回弹（恢复系数 0.45、缩小、再弹 1~2 次）后并入；空中水量从 `fillRatio` 扣除（水面下降）、落地加回（⑤，放大让可见）。`updateContainerGravity` 输出未死区的原始旋转幅度供剥离；`drawWater` 存容器几何供 update 物理用、在水体之上画水团；`rebuildPaints` 建水团 `PorterDuffColorFilter`。倒置时重力在锁定 UI 系旋到 (0,-1)，水团落向新底部方向正确。只在快速重定向出现，静止/常态无、不碰音频身份。
- **发布**：`:app:assembleDebug` 通过，发 [update-20260706163605.md](debug-updates/update-20260706163605.md)，`:app:publishDebugUpdate` 成功，code `202607060837`。未连真机。
- **待办**：真机调水团手感（`BLOB_RADIUS_DP`/`BLOB_COUNT_GAIN`/`PEEL_GATE`/`BLOB_GRAVITY`/`BLOB_CONSERVE_AMPLIFY`/回弹参数）；确认"是水不是碎屑"、倒置坠落方向、不误触发；Phase 1 手感一并继续调。

## 2026-07-06 晃动注入 1 阶本征模消颤动 + 加大 6 层错落

- **现象**：用户测 202607060802，倾斜后水面仍长时间低幅颤动（与音频无关）；6 层错落不够。
- **诊断**：上版"均匀"注入方向对但会激发一串奇次谐波（3、5 阶…），主摇摆外这些高频谐波持续颤动很久。
- **修复**：倾斜激励改注入 **1 阶速度本征模 `sin(π·u)`**（两壁 0、中间最大）→ 只激发基础摇摆、几乎不带谐波，摇完就停；阻尼 `SLOSH_DAMP` 0.9965→0.995（约 3~4 次settle）；`TILT_DEADZONE` 0.004→0.006（拒手抖）；`mLayerSloshAmp` 大幅错开振幅 + `mLayerSloshShift` ±3%→±9%。
- **发布**：`:app:assembleDebug` 通过，发 [update-20260706161707.md](debug-updates/update-20260706161707.md)，`:app:publishDebugUpdate` 成功，code `202607060818`。未连真机。

## 2026-07-06 修复晃动模态错误（2 阶驻波→1 阶摇摆）

- **现象**：用户测 202607060737，猛地倾斜时水从两边向中间汇聚撞击又分开、像拉面筋、荡几十次、6 层几乎无差别显机械。
- **诊断**：倾斜激励的空间 profile 用反了——注入的是**反对称**流速（左半向左、右半向右）→ 水向两侧外涌、中间汇聚 = 2 阶驻波。真实倾斜应是整摊水均匀被推向低侧、在反射壁堆成"一侧高一侧低"的 1 阶摇摆。2 阶+更高模态频率高 → 荡几十次；共享场齐动 → 6 层无差别。
- **修复**：倾斜激励改**均匀** profile（去反对称因子）→ 1 阶摇摆、回荡回到 5~6 次；前后倾 z 增益 24→12（z 是对称模态、带汇聚感，压低让摇摆为主）；6 层加水平微错位 `mLayerSloshShift`（±3% 宽）破除齐动。
- **发布**：`:app:assembleDebug` 通过，发 [update-20260706160158.md](debug-updates/update-20260706160158.md)，`:app:publishDebugUpdate` 成功，code `202607060802`。未连真机。

## 2026-07-06 修复 Phase 1 开场狂涌/无数尖锐波峰

- **现象**：用户测 202607060726，打开 dialog 后水面流动极快、满屏尖锐波峰。
- **诊断**：速度场求解稳定（leapfrog G·HH<1），坑在激励注入——① 开场从假设 (0,1) 收敛到真实重力喷出巨大 dθ 冲量（主因）；② 休眠门槛太低，传感器噪声每帧持续注入、永不休眠；③ z 激励用阶跃 profile 注入高频尖峰；外加主增益大约 10 倍。
- **修复**：重力状态锚定首个真实读数（消开场冲量）；激励死区 + clamp（滤噪、保休眠、防灌爆）；z 改平滑 `cos(2π)` 2 阶模；速度场安全 clamp；`SLOSH_TILT_GAIN` 350→40、休眠门槛提高；`onDetachedFromWindow` 复位。
- **发布**：`:app:assembleDebug` 通过，发 [update-20260706153610.md](debug-updates/update-20260706153610.md)，`:app:publishDebugUpdate` 成功，code `202607060737`。未连真机。

## 2026-07-06 倾斜物理升级 grill + Phase 1（自由液面速度场）

- **触发**：用户要继续提升倾斜动画的物理真实感，指两处——① 安静时晃动只弹两三次就平，应多次衰减回荡；② 倒置/倾斜时水应从空中坠落到新底部、砸底还会回弹，当前完全没有。
- **调研 + grill**：充分 web 调研（阻尼谐振子晃动 `ω=√(g·k·tanh(kH))`、弹簧/浅水速度场天然回荡、height field 剥离粒子做倾倒；prime31/CGI Coffee/Game2DWaterKit/Müller）。根因=自由液面无动力学。grill 逐层收敛：否 full-C、定 **C-可见**（速度场 + 受限弹道水团）；细化 **D4 边界**（物理驱动的真实水元素不算装饰、允许）；回荡选甲、坠落选 A + Worthington 回弹；音频永远运动学不进流体；静止门控；分两 Phase。记 D48/D49，更新 preferences。
- **Phase 1 实现**（`WaveVisualizerOpus`，仅渲染层）：以 1D 交错网格浅水速度场（η+切向 u，48 点，6 层共享）取代旧 `mSloshEnergy` 能量池；倾斜/前后倾注入流速→自发晃动/爬墙/反射/多次衰减回荡（甲）；频率绑容器宽；音频浪包被流速平流（③，`WavePacket.drift`）；无激励且能量衰竭时清零休眠→走静态路径（④，静止时对音频零影响）。`updateSloshField`/`stepSloshField`/`sampleSloshArray` 新增，`updateContainerGravity` 改为输出倾斜/z 激励，`drawWater` 每点叠加零均值晃动形变。`spawnSloshWave` 及旧 SLOSH 能量常量删除。
- **验证 + 发布**：`:app:assembleDebug` 通过。发 debug [update-20260706152510.md](debug-updates/update-20260706152510.md)，`:app:publishDebugUpdate` 成功，code `202607060726`。未连真机。
- **待办**：真机调 Phase 1 手感（`SLOSH_DAMP` 回荡次数、`SLOSH_TILT_GAIN`/`SLOSH_RENDER_GAIN` 幅度、`SLOSH_ADVECT_GAIN` 平流、倾斜方向符号）；确认静止零回归。之后做 **Phase 2**（水团空中坠落 + Worthington 回弹 + 空中水量守恒）。

## 2026-07-06 通读实现 + 竖直静止复原原音频波浪峰高/谷深（D47）

- **背景**：用户要求先彻底读懂整个 Opus 音频可视化实现（分析器链路、渲染、各参数作用、倾斜容器），不急于发表意见，作为后续讨论/优化的第一步。通读 `WaveAudioAnalyzerOpus`（623 行）、`WaveVisualizerOpus`（833 行）、驱动帧、`AudioRecorder` 采集循环与全部 feature 文档后，整理出实现全景与参数字典。
- **确认问题1（静止是否=加特性前）**：基准水面位置/水位响应/层偏移在竖直静止时精确退化到原版；但波形整形仍有三处不同——去均值、波谷 18→32dp、波峰净空约 198→155·frac，后两处是上次"保持静止尺度"修正（202607060336）未覆盖的。
- **决策 D47 + 实现**：去均值保留（面积守恒结构性前提）；波峰净空/波谷限深复原到原口径，参照从固定 `diag` 改为容器沿重力轴的实际跨度 `vSpan` + 计时保护线，竖直时精确等于旧值、倾斜时按真实深度自适应。仅改 `WaveVisualizerOpus.drawWater`（补算 `minV/maxV/vSpan/topLimitV`，改 `hCeil`/`troughMaxPx`），清理死常量 `CREST_CONTAINER_FRAC`/`TROUGH_CONTAINER_FRAC`/`TROUGH_SOFT_DP`，复用 `TOP_LIMIT_FRAC`/`TROUGH_MAX_FRAC`。`:app:assembleDebug` 通过。
- **发布**：新增 [debug-updates/update-20260706141426.md](debug-updates/update-20260706141426.md)，执行 `:app:publishDebugUpdate` 成功，debug update code 为 `202607060615`，远端 `latest.json` 已更新到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。未使用 adb。
- **文档**：新增 [implementation-reference.md](implementation-reference.md)（实现全景 + 参数字典）；decisions.md 记 D47。

## 2026-07-06 修正倾斜容器初始方向反转

- **现象**：用户测试 debug 版本时发现，录音 dialog 刚打开后水体方向判断反了，直接翻转 180 度跑到顶部。
- **诊断**：检查 `AudioRecordDialogFragment.dispatchGravityToVisualizer()` 发现，在 `Surface.ROTATION_0` 下，如果传感器竖直正常姿态给到 `(x=0, y≈+9.8)`，旧实现会再对 y 分量取反，传给 `WaveVisualizerOpus` 的结果变成 `(0, -9.8)`，正好把默认底部解释成顶部。根因不是传感器数据本身读反，而是 UI 坐标映射里多做了一次 y 轴反转。
- **修正**：移除 `setContainerGravity(screenX, -screenYUp, gz)` 中的额外取反，改为传入 `setContainerGravity(screenX, screenY, gz)`；同时保留 0/90/180/270 度锁定方向下的轴映射。
- **验证与发布**：本地 `:app:assembleDebug` 通过，随后按用户偏好直接执行 `:app:publishDebugUpdate`。新增发布日志 [debug-updates/update-20260706111041.md](debug-updates/update-20260706111041.md)，发布成功，debug update code 为 `202607060311`，远端 `latest.json` 已更新到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。未使用 adb。

## 2026-07-06 修正倾斜容器左右方向反转

- **现象**：用户继续测试 debug 版本时发现，设备往左侧倾斜时，水体应该堆积到左侧，但实际堆到了右侧。
- **诊断**：上下初始方向修正后仍只剩左右反转，说明水体半平面和 y 方向已经基本成立，问题集中在 `dispatchGravityToVisualizer()` 的 UI 水平轴映射。当前锁定 UI 坐标下，Android gravity/accelerometer 的 x 分量与容器希望的左右物理方向相反。
- **修正**：在传入 `WaveVisualizerOpus` 前只翻转 `screenX`，保持 `screenY` 不变，避免回退初始顶部反转修复。
- **验证与发布**：本地 `:app:assembleDebug` 通过。新增发布日志 [debug-updates/update-20260706111428.md](debug-updates/update-20260706111428.md)，执行 `:app:publishDebugUpdate` 成功，debug update code 为 `202607060314`。未使用 adb。

## 2026-07-06 保持静止状态下的原音频波浪尺度

- **问题**：用户询问当前倾斜容器方案在陀螺仪/重力数据相对静止时，是否会影响原本的音频波浪动画效果。
- **确认**：静止传感器不会持续注入 slosh；`mSloshEnergy` 只来自重力方向变化量和 z 方向变化量，设备静止后会衰减。
- **发现**：上一版为了旋转时覆盖容器边角，将自由液面采样范围加了 margin。这样竖直静止时 `uSpan` 大于屏幕宽度，会让基础波和音频浪包横向变宽，确实会轻微改变原本动画尺度。
- **修正**：自由液面采样范围改回矩形在自由液面切线方向上的真实投影跨度；竖直静止时 `uSpan == width`，基础波频率、音频浪包宽度和速度恢复到以屏幕宽度为尺度的原始口径。
- **验证与发布**：本地 `:app:assembleDebug` 通过。新增发布日志 [debug-updates/update-20260706113607.md](debug-updates/update-20260706113607.md)，执行 `:app:publishDebugUpdate` 成功，debug update code 为 `202607060336`。未使用 adb。

## 2026-07-06 录音 dialog 360 度倾斜容器物理

- **触发**：用户希望录音 dialog 现有音频水波动画更像一个可玩的容器：手机/平板倾斜、前后扣动、横放、倒置时，水体和波浪按物理规律倾斜、倒灌、撞壁、爬墙和反弹；dialog 可见时就能玩；音频仍是 Opus 的核心驱动。
- **grill-with-docs 决策**：记录 D30-D44。关键口径包括：偏夸张的玩具水槽强度；左右主导堆积，前后辅助涌动；dialog 可见即启用；倾斜和音频双驱动叠加但音频仍是核心；隐形容器撞壁不新增边框；完整 360 度连续重力方向；完全倒置时原顶部成为新底部；水量守恒；音频波浪跟随自由液面坐标系；UI 不为倒置做特殊处理；dialog 期间临时锁定 Activity 方向；平放退化区保留最近稳定方向并缓慢回默认底部。
- **调研修正**：用户指出首次调研偏向传感器接入，真正需要调研动画实现。补充调研后记录 D46：采用重力相对的 1D height-field / shallow-water 表面 + 面积守恒几何裁剪；不采用 VOF/Navier-Stokes 或 SPH/PBF 粒子流体，因为当前 Android `Canvas` 自定义 View 中成本和视觉取向不合适。
- **实现**：`AudioRecordDialogFragment` 新增方向锁定、`TYPE_GRAVITY`/`TYPE_ACCELEROMETER` 姿态监听、固定 UI 坐标映射和生命周期注销；`WaveVisualizerOpus` 重构为重力相对容器：按连续重力向量求自由液面切线/法线，用矩形半平面裁剪二分求守恒面积基准线，音频基础波和浪包改在自由液面坐标系传播，绘制时用沿重力方向闭合的 `Path` 填充；新增平放退化区、倾斜能量、边界 slosh 浪包和夸张玩具水槽参数。
- **验证**：本地 `:app:assembleDebug` 通过，完整输出写入 `memory/compile.txt`。未连接真机或模拟器；未发布 debug。

## 2026-07-06 发布 360 度倾斜容器 debug 版本

- **发布日志**：新增 [debug-updates/update-20260706110416.md](debug-updates/update-20260706110416.md)，记录本轮 360 度重力容器、倒置倒灌、音频波浪跟随自由液面、调研取舍和真机观察重点。
- **发布命令**：执行 `:app:publishDebugUpdate`，传入 `-PdebugUpdateNotesFile=docs/features/audio-visualizer-opus/debug-updates/update-20260706110416.md`。
- **结果**：发布成功，debug update code 为 `202607060304`，远端 `latest.json` 已更新到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。完整输出写入 `memory/publish.txt`。

## 2026-07-04 撤销竖直明暗变化

- **触发**：用户确认需要去掉此前的竖直方向明暗变化，因为它会让水体最下方显得脏。
- **修改**：`WaveVisualizerOpus` 删除渐变记事的第二遍竖向中性光照覆盖，删除纯色记事的竖向同色系明暗 `LinearGradient`，并移除对应画笔、常量与 helper。现在颜色只保留记事底色、完整 8 向渐变方向和层间远近提亮；水体底部不再被额外压暗。
- **文档**：更新 [preferences.md](preferences.md) 和 [decisions.md](decisions.md) D29，记录 D24/D25 的竖直明暗着色已撤销。

## 2026-07-04 渐变记事水体方向修正

- **触发**：用户追问当前录音 dialog 中，渐变记事下水体、波浪的渐变方向是否与记事一致。检查发现 `WaveVisualizerOpus` 已接入当前录音 dialog，但渐变底色仍固定为横向 `LinearGradient(0,0,w,0)`，只根据左右语义交换起止色；因此上下与对角方向并不与记事背景一致。
- **修改**：`WaveVisualizerOpus.rebuildPaints()` 中，渐变记事的每层底色改为构造对应层的 `ThingBackground.gradient(...)`，再交给 `BackgroundUtil.createLinearGradient(...)` 生成 shader，从而完整沿用项目统一的 8 向 `ThingBackground.orientation` 映射。第二遍竖向中性光照覆盖 `mLayerLightPaints` 保留，用于继续提供上亮下暗的体积明暗。
- **文档**：更新 [preferences.md](preferences.md) 和 [decisions.md](decisions.md) D28，记录"底色完整跟随记事渐变方向，竖向明暗仅作为覆盖层"。

## 2026-07-03 设计访谈（grill-with-docs）+ 首轮实现

- **调研**：七轮联网调研 + v1/v2 精读，汇总于 [research.md](research.md)。方向包括音频可视化设计
  范式（siriwave 多层正弦 + 中心钟形、lookas 弹簧-阻尼 + 非对称平滑）、水面/波浪模拟（弹簧质点
  水线、Gerstner、centripetal Catmull-Rom）、音频特征→视觉映射、法拉第波/cymatics/频谱→空间、
  "声音即水面"液化技法、Android 采集优化（UNPROCESSED + 关 AGC/NS）、稳健特征工程（PCEN/白化）。
- **v1/v2 总结**：v1 `VoiceVisualizer` = 多分量正弦合成 + 事后整形（原地放大、反复打补丁）；
  v2 `RecordingWaveVisualizer`（用户最爱）= 生成式浪包 + 196 点物理场 + 持久波三合一（方向对、
  但重且补丁多、有帧峰）。偏好排序 v2 > v1 > Fable。
- **访谈定案 D1–D13**（见 [decisions.md](decisions.md)）：核心=离散艺术化波浪群 + 轻弹簧底
  （收窄 D1）；共享场 + 前景层内扰动；全新 Opus 分析器；融合而非装饰；声音驱动浪的数量/大小/
  分层；采集改 UNPROCESSED + 关音效 + mono；层级=深度层 + 尺度倾向 + 音色分配；数量明显但克制；
  出生=离散事件 + 持续驱动 + 主副浪；每浪有方向整体滚动横移；主体水体纯本色不透明；Opus 直接生效。
  过程中用户两次重要澄清：① 主要通过"波浪"（离散、分层、可数）而非水面纹理反映声音，不过度模拟
  现实；② 主体水体明度/透明度皆为 1，避免完全激活时偏暗偏脏。均已落入 decisions/preferences。
- **实现 E1–E7**（见 [execution.md](execution.md)）：新增 5 个 Kotlin 类（`WaveAudioFrameOpus`、
  `WaveDriveFrameOpus`、`WaveFrameReceiverOpus`、`WaveAudioAnalyzerOpus`、`WaveVisualizerOpus`），
  改造 `AudioRecorder`（采集源/单声道/关音效/`linkOpus`）、`AudioRecordDialogFragment`、
  `fragment_record_audio.xml`。v1/v2/Fable 全部保留不删。`:app:assembleDebug` 通过，APK 20.8MB。
- **文档**：新建 `docs/features/audio-visualizer-opus/`（decisions/preferences/research/plan/
  execution/sessions）；更新 `CONTEXT.md` 的 **Voice Waveform** 词条（主体纯本色 + 上层明度/透明度
  阶梯，纠正 Fable 那轮改的"仅透明度"）；更新 `recording-wave-visualizer/preferences.md`（版本排序）。
- **待办**：E9 用户真机目视验证 + 可调参迭代（plan.md 第十节；execution.md 列出首轮简化点）。

## 2026-07-03 晚–07-04 真机调优批次（视觉层）

多轮真机反馈迭代，逐条落在 [debug-updates](debug-updates/)（含 debug code）与 decisions D17–D20：
层次均衡与"浅色后层窜高/深色前景遮死"的反复（D17 解耦"丰富度⟂主次"）→ 整体单向流动 + 浪包横穿
移出（D18 消驻波干涉）→ 六层角色分工"近层平静·偶爆发、远层细密·频繁"（D19）→ 大胆加大层间基线差
（相邻 9.6dp，平静时也能看清 6 层）→ 稳态空调底噪抑制（D20，音调性缩放 absoluteLevel）。

## 2026-07-04 响度"半绝对"重构（grill-me + 6 份调研 + 实现 + 发布）

- **触发**：用户提"把声音特征记录下来做相对比较"的想法，因为正常/小声说话跟大声放歌动画差别不大、
  甚至前者更显著。
- **grill-me 收敛**：诊断出根因不是"缺历史"，而是**自适应归一化（floor+peak 都自适应）抹平大小声** +
  **MIC 保留 AGC 压动态**（D20 削 absoluteLevel 又加剧）。共识=**半绝对**：自适应零点 + 固定尺子。逐个
  钉定：目标（半绝对）、骨架（最小机制、不建历史）、零点（信号门控自适应底）、量程/死区（45/5dB）、
  曲线（S 曲线强区分）、录音（分步暂不动）。见 [decisions.md](decisions.md) D21。
- **调研**：6 份 web 调研（声学 dBFS/SPL、心理声学、自适应 metering/gate/VAD、Android MIC/AGC），存
  [research.md](research.md) 第 5 节，全面印证 45dB 量程、fast-down/slow-up 零点、AGC 是元凶、未校准
  拿不到绝对 SPL（半绝对是唯一正解）。
- **实现**：重构 `WaveAudioAnalyzerOpus` 响度链（新 `semiAbsLevel`、信号门控 `mFloorDb`、删 `mPeakDb`/
  `relativeLevel`/`absoluteLevel`/`absAssist`、`fastLevel→fastDbFs`、常量 `RANGE_DB=45`/`DEADZONE_DB=5`）；
  `AudioRecorder` MIC 也 `disablePreprocessing` + `getEnabled` 复核 + DEBUG log（修 `:141` 漏洞）。编译通过，
  发布 debug code **202607031730**。
- **提交**：首轮 Opus 功能整体提交 `85f1aa28`（31 文件；排除 Everything-Android 与临时日志）。
- **待验证**：真机看大声 vs 小声是否明显拉开、空调是否不激活、各机型 AGC 能否关（`logcat` 看 `preproc=`）；
  录音文件音量分步处理（暂不动，真机若变小再单独加保存前增益归一）。

## 2026-07-04 v5 之后第二轮调研优化（D23，五条落地）

- **触发**：用户在 v5（`78f9f9fe`）稳定后要求"分析当前实现 + 联网充分调研还能怎么优化"，先只出分析与
  建议（不改文件），再要求"改一下 1-5"。
- **调研**：新一轮 web 调研（避开前 7 轮已覆盖的弹簧水线/Gerstner/Catmull-Rom/cymatics/PCEN/半绝对响度），
  聚焦 AGSL/RuntimeShader、Gerstner 着色（fresnel/specular/深浅色）、SuperFlux（Böck DAFx-13）、LUFS/
  K 加权（BS.1770）、相量递推、Canvas drawPath 性能、domain warping（iq）。
- **落地五条**（见 [decisions.md](decisions.md) D23）：
  1. 建议1 竖直深度渐变着色（纯色记事，只提亮不压暗，守 D12；渐变记事保横向）。
  2. 建议2 K 加权响度（BS.1770 two-stage biquad，`ingest` 连续滤波到 `mKRing`，`dbFs`/`fastDbFs` 改用）。
  3. 建议3 浪包按层分桶 + 基础波场相量递推（性能，像素不变；drawVertices 不做）。
  4. 建议4 SuperFlux 频域最大值滤波（抑制颤音虚假 onset）。
  5. 建议5 分量权重缓慢时变起伏去机械感（与相量递推兼容、不碰流向，取代会冲突的空间 domain warping）。
- **未采纳**：建议6 AGSL（minSdk 26 vs API 33，单独立项）；建议7 tempo 锁（抖动回退风险）。
- **构建**：`WaveAudioAnalyzerOpus` + `WaveVisualizerOpus` 改动，`:app:assembleDebug` 两次分批通过。未发布
  debug（用户未要求）。
- **真机复校清单**（重点看 K 加权是否改变标定）：
  1. **大小声戏剧性**是否保持/更好（K 加权后 dbFs 刻度可能微移；若变弱调 `RANGE_DB`，若空调又激活调 `DEADZONE_DB`/floor 门控）；
  2. **纯色记事**是否有了竖直体积感、主体是否仍纯净不脏（`CREST_LIGHTEN_*` 可调；渐变记事应保持横向方向）；
  3. **唱歌/弦乐**的 onset 是否更干净、不再一串虚假浪（`SUPERFLUX_MAXFILTER_BINS` 可调）；
  4. **去机械感**是否自然、有没有引入不想要的形状抖动或"晃动"（`WOBBLE_AMP`/`WOBBLE_K_*` 可调）；
  5. 高能量场景**帧稳定性**（卡顿）是否改善（分桶 + 相量递推的目的）。

## 2026-07-04 D24 竖直明暗着色扩展到渐变记事 + 深水压暗

- **触发**：用户反馈 D23 的竖直渐变只影响纯色记事，要求渐变记事也有 y 方向明暗（主体保留颜色/渐变方向，
  波峰稍提亮、最下方深水区稍压暗），更好模拟自然水。
- **调研**：UE4 stylized water（深处更暗、越浅越亮、波峰高光）、水彩海景（深色打底 + 渐浅到波峰 + 波峰留白）；
  确认 ComposeShader 不能组合两个 LinearGradient（须两遍绘制）；确认 `BackgroundUtil` 有 `lighter`/`darker`。
- **实现**（见 [decisions.md](decisions.md) D24，修订 D12 放宽"主体不压暗"）：纯色记事单遍竖直同色系
  `提亮→本色→压暗`；渐变记事底色横向不变 + 第二遍中性竖直光照覆盖（白/黑，透明端用同侧透明白/黑避免灰边）。
  `:app:assembleDebug` 通过；同步更新 `CONTEXT.md` 的 Voice Waveform 词条。未发布 debug、未提交（待用户指示）。
- **真机调**：`CREST_LIGHTEN_*`/`DEEP_DARKEN_*`（纯色）、`OVERLAY_LIGHT_ALPHA`/`OVERLAY_DARK_ALPHA`（渐变）、
  三段位置 `SHADE_*`。重点看渐变记事是否既保留渐变方向又有了体积感、深水压暗是否自然不过头。
