# 会话记录 — 录音波形可视化改造
## 2026-07-03 - 按按钮实际高度重新上调水位
- 用户反馈：D67 发布后水位只升高了一点点，仍然没有怎么盖过录音按钮。
- 诊断结论：底部按钮行高 `96dp`，dialog 高 `360dp`，主录音按钮 `56dp` 且在按钮行内居中，因此按钮 top 约为 `284dp`。D67 的最前景深色层静态水面约为 `288dp`，仍低于按钮 top，视觉上不会明显覆盖按钮区域。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：`baseSurfaceY()` 从 `0.772 - waterLevel * 0.218` 调整为 `0.724 - waterLevel * 0.202`，让最前景深色层静态水面约从 `288dp` 抬到 `271dp`。dialog 尺寸和按钮布局不变。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703145150.md" --console=plain --no-configuration-cache` 已发布到阿里云 debug 渠道，code **202607030652**，APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030652.apk`，SHA-256 `09fe3328660a9e25b6fe5a6301d8ce61648aa0d8aa89771dc3fec610e74bc2df`。服务器 `latest.json` 已确认指向该版本。未使用 adb，未安装到物理设备或模拟器，未 commit。

## 2026-07-03 - 毛刺柔化与初始水位上移
- 用户反馈：波浪偶尔会出现一些毛刺；初始水位稍微低了一些，导致最深色那道浪没能盖过录音按钮的高度。
- 诊断结论：初始水位来自 `baseSurfaceY()`，当前静态基座偏低，最前景深色层还有向下的 `verticalOffset`，因此低能量时不够覆盖按钮高度。毛刺更可能是最终路径中少量孤立采样点、局部物理波场高斜率或细小扰动被路径重建放大，不应通过降低大浪高度或减少层数来解决。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：`baseSurfaceY()` 从 `0.798 - waterLevel * 0.225` 调整为 `0.772 - waterLevel * 0.218`，让整片水体和 6 道浪整体上移。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：新增最终路径的孤立尖点柔化，只处理单点局部极值且超过邻域阈值的突起，保留连续大浪峰、强声高浪和多层丰富度。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703144723.md" --console=plain --no-configuration-cache` 已发布到阿里云 debug 渠道，code **202607030647**，APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030647.apk`，SHA-256 `ee0954b495de69b5596c94c88eb47437b76fbd6edfce9dc2b4fdd0862f38479d`。服务器 `latest.json` 已确认指向该版本。未使用 adb，未安装到物理设备或模拟器，未 commit。

## 2026-07-03 - 残余阶梯状折点合并修正
- 用户反馈：阶梯状情况已经少了非常多，但仍有一些，怀疑波浪合并处还有问题。
- 诊断结论：D64 后生成浪主输出已经是连续 signed sum，但 `crestSupport` 仍通过单个浪包 offset 是否为负来累加；多个浪包互相抵消或穿过时，单个浪包跨零可能让限幅支持量斜率变化，留下轻微下折。另一个次要风险是 cubic 路径控制点在波峰/波谷附近过冲，把小的合成斜率变化放大成折点。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：同层多浪包合并改为使用总 signed sum 的建设性 crest 分量和连续合并能量 `sqrt(sum(offset^2))` 推导 `crestSupport`，不再按单个浪包正负分组。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：路径重建改为局部单调 cubic 切线，相邻斜率异号时切线归零，同号时按较小相邻斜率限制控制点，避免 crest/trough 附近过冲。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：降低生成浪尾部硬截断阈值，让浪包边缘衰减更连续。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703143118.md" --console=plain --no-configuration-cache` 已发布到阿里云 debug 渠道，code **202607030631**，APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030631.apk`，SHA-256 `3f740ce422a1ec9a1df079e64f709685a0274a02498279487497ba28e873289c`。服务器 `latest.json` 已确认指向该版本。未使用 adb，未安装到物理设备或模拟器，未 commit。

## 2026-07-03 - 波峰小卡顿保质优化

- 用户反馈：声音比较大、动画比较丰富时，波峰处会有一些小卡顿，并要求不要降低动画质量。
- 诊断结论：最可能原因是 D64 后主浪路径采样密度较高，且每个采样点会通过三点空间平滑重复计算 `generatedWaveOffsetRaw()`；大声和丰富动画时每层多个 `GeneratedWave` 同时激活，指数/三角函数调用量在波峰丰富场景下达到峰值。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：移除生成浪三点空间平滑的重复 raw 计算。D64 的连续 signed sum 已经承担几何连续性，因此不再需要每个采样点三次计算同一组浪包。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：路径从高密度二次中点连接改为三次连续路径重建；采样点数略降但使用受限切线的 cubic 连接，保持视觉平滑并降低每帧计算量。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：`waveWildness()` 改为每帧计算一次并缓存，避免每个采样点重复计算同一视觉状态。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703142231.md" --console=plain --no-configuration-cache` 已发布到阿里云 debug 渠道，code **202607030622**，APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030622.apk`，SHA-256 `3db46bf9c6132f3aa821e8e048b57b9b3777ba12c92b9e1f3a8485420767b8e7`。未使用 adb，未安装到物理设备或模拟器，未 commit。

## 2026-07-03 - 阶梯式下折锯齿修正

- 用户反馈：仍能看到一种像阶梯一样的锯齿，本来平滑的曲线会突然往下折一下，并询问是否是采样点数不够。
- 调研结论：采样点数不足会造成 aliasing 和阶梯感，但这类“局部突然下折”更像几何函数本身存在斜率突变；`Path` / `Canvas` 只能绘制给定路径，不能自动消除上游合成函数里的折点。
- 诊断结论：D62/D63 的同层多浪包合成把每个浪包按正负拆成 crest / trough 两组再分别做非线性软上限。多个浪包互相抵消时，单个浪包跨过零点会切换组别，可能造成局部斜率突变。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：生成浪合成改为先做连续 signed sum，再只对总和做 crest / trough 软约束；`crestSupport` 只用于估计 trough room，不再改变每个浪包的输出公式。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：提高水体边界和主浪路径采样密度，降低普通采样 aliasing 对阶梯感的放大。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703141417.md" --console=plain --no-configuration-cache` 已发布到阿里云 debug 渠道，code **202607030614**，APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030614.apk`，SHA-256 `a032803dea6407e24563e5f8b734700310840669d2db7363966d031eefa2af2c`。未使用 adb，未安装到物理设备或模拟器，未 commit。

## 2026-07-03 - 潮位流速、强浪幅度与锯齿优化

- 用户反馈：同层多浪包版本比之前更好，但低水位时波浪流速仍偏快；有时会出现矩形锯齿；声音较大时仍缺少必要的高大浪。
- 诊断结论：相位推进和生成浪速度主要由 presence / pace 驱动，低水位只弱参与；D62 的多浪包软上限为了保护稳定性偏保守，强事件 crest room 不足；窄浪包和指数饱和软限幅可能在局部形成平台或硬折角。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：新增 `tideFlowScale()`，让低水位降低基础波、肩部波、细节波、漂移波、生成浪出生速度和物理波场注入速度。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：生成浪合成增加小尺度空间平滑，浪包最小宽度略加大，并将同层 crest / trough 的软上限改为更柔和的对数型曲线，减少矩形锯齿和硬折角。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：强声、强节拍和高 wake 时提高 crest room、生成浪强度上限和前景层振幅，让必要场景能生成更高、更大的浪；trough 仍由基座水位软限幅保护。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703131323.md" --console=plain --no-configuration-cache` 已发布到阿里云 debug 渠道，code **202607030513**，APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030513.apk`，SHA-256 `7ec1b88b12c6323b7332437534963e8148a46002f4a49521b5c8b85e5683c05d`。未使用 adb，未安装到物理设备或模拟器，未 commit。

## 2026-07-03 - 同层多浪包叠加与后浪软干涉

- 用户反馈：有时会出现一个波峰在瞬间突然下压，希望继续优化海浪模拟，尤其是同一层里后面出现的新浪如何影响已经升起或正在渲染的前浪。
- 调研结论：波浪相遇应以位移叠加和相位干涉为基础；可信的实时水面通常让 active wave 继续按自身参数传播，新参数用于后续出生的波，而不是直接改写已经在运动的波。
- 诊断结论：当前 `RecordingWaveVisualizer` 每层只有一个 `GeneratedWave` 槽位；新浪生成时一旦复用同层槽位，旧浪会瞬间被替换，视觉上就可能表现为波峰突然下压或消失。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：`GeneratedWave` 改为每层 4 个浪包槽位，新波优先进入空槽；只有旧浪可见度很低时才允许复用槽位。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：同层多个浪包在几何层叠加，crest 分量可形成更大的浪，trough 分量经过软上限与 crest 保护，避免新浪肩部瞬间压垮旧波峰。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：新浪出生位置会检查同层可见波峰，避免 leading/trailing shoulder 直接落在旧 crest 上造成突兀抵消。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703130409.md" --console=plain --no-configuration-cache` 已发布到阿里云 debug 渠道，code **202607030504**，APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030504.apk`，SHA-256 `a971d9d4c8d1ea84f491c98768a681ab2b0d30073c8fa213888906e2067d5faf`。未使用 adb，未安装到物理设备或模拟器，未 commit。

## 2026-07-03 - 生成式波浪野性增强

- 用户反馈：生成式生命周期版本减少了当前波浪原地乱颤，但浪不够野性，整体太平淡。
- 诊断结论：生命周期模型方向继续保留，问题在于出生参数偏保守；单个事件通常只生成一道浪，生成门槛和间隔偏高，`GeneratedWave` 的高度、crest、尾浪、速度和内部 ripple 都较克制。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：降低有效事件和持续音乐 drive 的生成门槛，缩短快节奏场景下的新波浪生成间隔。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：提高 `GeneratedWave` 的波峰振幅、crest 权重、传播速度、尾浪权重、leading shoulder 和内部 ripple，让新生成的浪更高、更快、更有冲击感。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：强 beat / 高 pace / 高 strength 时允许一次事件生成主浪和副浪，占用两个不同槽位形成错峰叠加；已出生波浪仍不被后续音频帧改形。
- 更新 [preferences.md](preferences.md) 与 [decisions.md](decisions.md)，记录“生成式波浪也必须足够野性”的偏好与 D61 决策。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703125150.md" --console=plain --no-configuration-cache` 已发布到阿里云 debug 渠道，code **202607030452**，APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030452.apk`，SHA-256 `3d63ab96e0bc61cea4ee0456707156be73a55dc7be236e1a19a4918eeab5007a`。未使用 adb，未安装到物理设备/模拟器，未 commit。

## 2026-07-03 - 生成式波浪生命周期重构

- 用户反馈：当前版本仍会出现波浪原地乱颤；但“音乐变化主要反映在下一个波浪生成上，而不是强行改变当前波浪”的思路是对的。每个波浪应自然完成从上升到下降的生命周期，音乐快慢通过新波浪生成速度体现；6 道波浪可以错峰跟上音乐。
- 诊断结论：上一版虽然加入了行进波前，但基础水面频率、细节、beat breath、pan bend、底层流速和背景水体边界仍有部分当前音频即时改写入口，且 traveling surge 是叠加扰动，不是 6 道主浪自身的生命周期模型。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：新增 6 个 `GeneratedWave` 生命周期槽位；有效声音事件、能量上升、beat pulse 或持续音乐 drive 只触发一个槽位出生新波浪。出生时冻结强度、宽度、速度、方向、rise/fall 比例、crest/trailing/ripple 权重，后续只随 `age` 自然上升、传播、下降。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：`pace`、beat pulse 和整体 music drive 只影响新波浪生成间隔；保留最小间隔和 6 槽复用，避免 20ms 音频帧直接制造几何变化。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：基础水面改成低幅、低频、慢速底座，移除当前音频对基础波频率、细节、beat breath、pan bend、底层流速和 bodySurface 的明显即时改写；慢速 `waterLevel` 继续承担整体潮位。
- 更新 [preferences.md](preferences.md) 与 [decisions.md](decisions.md)，记录“音乐变化主要影响下一道浪的生成”的偏好与 D60 决策。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703120049.md" --console=plain --no-configuration-cache` 已发布到阿里云 debug 渠道，code **202607030401**，APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030401.apk`，SHA-256 `8b5da3973f682e3323b2908f145ca8e6e0c99e39dc023ad958e56359ec6e127f`。未使用 adb，未安装到物理设备/模拟器，未 commit。

## 2026-07-03 - 强声响应改为行进波前

- 用户反馈：当前版本声音变化时会原地把已有波形变成波峰和波谷，看起来像一座座山，而不是自然升起的波浪。
- 诊断结论：上一版虽然加入了死区门控和向下软限幅，但 `RecordingWaveVisualizer.waveY()` 仍用 `level/swell/wake/wildness` 对整条基础正弦波做直接振幅放大，因此声音一变，现有峰谷会在原地长高。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：降低 `level`、`swell`、`wake`、`wildness` 对基础波整条曲线的直接放大和 `shapeAudioWave()` 的原地 crest sharpening，基础水面改回更温和的低频水体底座。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：新增 `TravelingSurge` 生命周期事件；当 `level`、`swell`、`presence`、`wake` 上升或 beat pulse 明显时生成局部行进波前，并在 6 层水面之间加入轻微延迟、宽度和强度差异，让波峰从水面局部升起并横向传播。
- 保留上一轮的目标值死区门控和相对基座水位向下软限幅，避免回到轻微颤动或波谷撞到底部。
- 更新 [preferences.md](preferences.md) 与 [decisions.md](decisions.md)，记录“声音变化应生成行进波峰，而不是原地长成山”的偏好与 D59 决策。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703115201.md" --console=plain --no-configuration-cache` 已发布到阿里云 debug 渠道，code **202607030352**，APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030352.apk`，SHA-256 `99a2e16c9e38b63a1c323c4fb2b696234baf92f5efb4746da2d69c40a9264de7`。未使用 adb，未安装到物理设备/模拟器，未 commit。

## 2026-07-03 - 波谷软限幅与目标值死区门控

- 用户反馈最新发布版在声音变化时，波谷容易直接落到 dialog 底部，上下差异过大；同时部分波浪有轻微颤动，希望变化超过阈值后再发生，并且要以动画形式顺滑变化，而不是瞬时 set。
- 诊断结论：上一版为增强野性提高了主波幅、wake 振幅和物理波场冲击，但 `waveY()` 没有相对平均基座水位的向下软边界；`acceptFrame()` 每帧原样写入所有目标值，也会让细小音频 drive 抖动持续传入视觉层。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：为 `level`、`waterLevel`、`swell`、`wake`、`pace`、频段权重、pitch、pan、beat/tempo 等目标值增加死区门控；超过阈值后仍由 `approach()` 按 attack/release 连续追赶，静音或近零输入可归零目标。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：以每层 `baseSurfaceY + verticalOffset` 作为基座水位，对最终 `waveY` 的向下位移做指数软压缩；同时降低向下 trough 额外放大和物理波场配对 trough 冲击，保留向上波峰和高能量野性。
- 更新 [preferences.md](preferences.md) 与 [decisions.md](decisions.md)，记录“波谷不能明显低于基座水位”和“小幅输入变化需要阈值门控并平滑追赶”的偏好与 D58 决策。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703113923.md" --console=plain --no-configuration-cache` 已发布到阿里云 debug 渠道，code **202607030339**，APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030339.apk`，SHA-256 `9f74815729c41e07b44ea06c4be1db55787b7db5a7d64b14480bd936731023e9`。未使用 adb，未安装到物理设备/模拟器，未 commit。

## 2026-07-03 - 新录音波浪外部调研与首轮问题

- 用户要求重新设计录音 dialog 的波浪动画：完全不参考现有 visualizer 实现，先调研音频可视化、波浪动画和实时音频特征，再通过充分讨论决定方案。
- 调研结论：实时音频可视化不应只看单一音量值；应拆分为响度/动态范围、频段能量、spectral flux / onset、tempo / beat 置信度、spectral centroid 或近似音色亮度等通道，再由视觉层按不同惯性和阈值吸收。
- 调研结论：自然水面不适合逐帧追踪每个音频特征；更适合使用多组连续正弦/Gerstner 式波分量、分层相位、连续噪声和 envelope follower，让音频事件驱动目标值而不是直接改写最终几何。
- 调研结论：安静环境和空调等稳态噪声需要单独门控；低活动态应保留低幅、低速、低细节的水感，只有有效人声、明显 onset、节拍或声强变化进入 alive / surge 类视觉状态。
- 待用户确认的第一个产品问题：这轮新设计的首要取向是“自然水体优先，声学特征作为驱动”，还是“声学仪表优先，尽量直接展示音频特征”。
- 后续讨论中用户不同意复用当前 `VoiceAudioFrame`，要求重新设计音频特征提取层，并再次明确必须上网调研音频可视化和音频特征提取后给出更好的方案。
- 补充调研结论：新特征层应使用多时间尺度特征。短窗约 10-20ms 用于低延迟 RMS、rise、onset 快通道；中窗约 40-60ms 用于 Mel/Bark 频带、spectral centroid/rolloff/flatness 和 spectral flux；长窗约 400ms 以上用于噪声底、响度动态范围、节奏密度和 tempo 置信度。
- 补充调研结论：安静和空调风噪不应只靠响度阈值处理。应维护自适应 noise floor，并结合 spectral flatness、低中频 flux、voicing/pitch 可信度、onset 密度和 hysteresis 得到 `intent` 或 `presence`，再决定视觉层是否进入 alive/surge。
- 补充调研结论： pitch / F0 可以作为“高低感”的辅助输入，但麦克风环境可能混有人声、音乐和噪声；因此不应让 pitch 直接决定几何高度，而应在可信度足够时轻量影响波浪的纵向倾向、流速或表面亮度。

## 2026-07-03 - Fable 版重新设计调研与定向

- 用户反馈近几次提交后的录音 dialog 波浪仍不满意：较安静环境中动画仍明显，声音大小、高低、快慢和节拍差异表达不够；要求完全不查看、不参考现有 visualizer 源码，新建以 `Fable` 为类名后缀的 visualizer。
- 读取了功能文档、录音 dialog 宿主、布局、`VoiceAudioFrame` 和 `AudioRecorder` 的音频特征数据流；未打开现有 visualizer 源码。
- 外部调研参考了实时音频可视化、FFT/频域分析、onset / beat tracking、谱质心 / 音色亮度、水波多正弦 / Gerstner 式渲染等资料。结论是继续采用“音频检测敏感、视觉承接有惯性”的分层策略，而不是把每个短时音频特征直接画进几何轮廓。
- 新增偏好：Fable 版默认约 6 道半透明波浪，每道浪共享同一水体身份但有独立透明度、惯性、延迟、相位、扰动和随机个性；安静态低水位、低浪高、慢流速，活跃态对声强、节奏和速度差异有更显著反馈。
- 新增 D38 决策与 Fable 计划：`AudioRecorder` 后续应依赖轻量接收接口；`AudioVisualizerFable` 内部使用 `quiet / aware / alive / surge` 连续视觉状态机，再映射到 6 层水体绘制。待用户确认是否允许增加不改变主轮廓的“表面生命层”后开始实现。

## 2026-07-02 - D36 实现：恢复适度水位涨落

- 用户反馈：水位高低也应根据音量大小变化，声音小时低位、声音大时高位，并要求回看此前决策。
- 回看结论：D3 原始设计是“浪高 + 水位一起涨落”；D15 因“大声时整片基础水位匀速抬升不好”把水位降级为近固定。D35 已有 `intensity` 声强通道，因此适合恢复有限水位变化。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：新增 `levelDrive`，由 `loudness` 和 `intensity` 混合后经 `LEVEL_GAMMA` 驱动水位。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：水位范围从 `0.36..0.42` 扩为 `0.31..0.49`；水位上升 / 回落使用独立时间常数 `LEVEL_ATTACK_TAU=0.30`、`LEVEL_RELEASE_TAU=0.62`。
- 验证与发布：`:app:assembleDebug` 通过；`:app:publishDebugUpdate` 发布到阿里云 debug 渠道，code **202607021137**，日志 [update-20260702193656.md](debug-updates/update-20260702193656.md)。未使用 adb，未安装设备，未 commit。

## 2026-07-02 - D37 实现：水位范围和潮位时间微调

- 用户指定：水位从 `0.24` 到 `0.49`，上升 / 回落时间为 `0.32s / 0.64s`。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：`REST_FRAC` 调整为 `0.24`，`MAX_FRAC` 保持 `0.49`，`LEVEL_ATTACK_TAU` / `LEVEL_RELEASE_TAU` 调整为 `0.32` / `0.64`。
- 验证与发布：`:app:assembleDebug` 通过；`:app:publishDebugUpdate` 发布到阿里云 debug 渠道，code **202607021154**，日志 [update-20260702195441.md](debug-updates/update-20260702195441.md)。未使用 adb，未安装设备，未 commit。

## 2026-07-02 - D35 实现：声强与节奏快慢区分

- 用户反馈：正常音量说话与大声说话、普通音量音乐与大声音乐在动画上差异不够；正常语速与快速说话、慢节奏音乐与快节奏音乐在横向流速和波形变化速度上也差异不够。
- 调研参考：ITU-R BS.1770 的 loudness 时间窗 / gating 思路；librosa beat tracking 的 onset strength -> tempo correlation -> beat peak 流程；Essentia RhythmExtractor2013 的 BPM / beat / confidence 输出与离线统计限制；aubio 的 onset / tempo / beat 实时音频标注能力。
- 修改 [VoiceAudioFrame.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceAudioFrame.kt)：新增 `intensity` 和 `pace` 字段，把有效活动度、声强、速度感拆成不同语义。
- 修改 [AudioRecorder.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/AudioRecorder.kt)：新增自适应 loudness contrast，结合绝对 loudness、fast loudness 和相对环境底噪动态范围，拉开正常音量和大音量。
- 修改 [AudioRecorder.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/AudioRecorder.kt)：新增事件密度 `pace`，由 onsetScore、fastImpact、body flux 的 leaky density 和高置信 tempo/BPM 共同决定。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：用 `intensity` 放大主体浪高和频段驱动，用 `pace` 放大横向流速与波形相位推进；`activity` 继续只负责从安静态唤醒。
- 验证与发布：`:app:assembleDebug` 通过；`:app:publishDebugUpdate` 发布到阿里云 debug 渠道，code **202607021024**，日志 [update-20260702182353.md](debug-updates/update-20260702182353.md)。未使用 adb，未安装设备，未 commit。

## 2026-07-02 - D34 实现：连续峰高稳定

- 用户反馈：D33 后仍能在动画中看到部分波峰高度出现抖动式上下变化，影响观感。
- 诊断：本轮不恢复 D30 的逐采样最终 y 冻结；剩余抖动更可能来自整层 `ampBoost`、`crestFactor` 和主体分量 `AMP_JITTER` 对小幅输入 / 包络变化的持续响应。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：新增逐层 `ampBoost` 死区平滑，吸收 surge、rhythm 和 rhythmEnergy 的小幅变化，减少已成形波峰上下浮动。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：新增逐层 `crestFactor` 死区平滑，并将主体 / 细节分量的慢速随机包络拆分缩放；低活动状态下进一步压低 jitter。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：高位波峰的 detail 衰减提前并增强，减少峰顶局部扰动；没有恢复逐点最终 y 冻结。
- 验证与发布：`:app:assembleDebug` 通过；`:app:publishDebugUpdate` 发布到阿里云 debug 渠道，code **202607021009**，日志 [update-20260702180804.md](debug-updates/update-20260702180804.md)。未使用 adb，未安装设备，未 commit。

## 2026-07-02 - D33 实现：稳态空调风噪抑制

- 用户反馈：D32 好了一些，但工位上主要是中央空调声和偶尔键盘声，仍能看到偏快动画；怀疑空调高频噪声触发了快速播放。
- 调研参考：HVAC 支持资料提到空调运行可产生 swish / hissing 等持续声音；VAD 资料强调要区分 speech/event 与 background noise；onset/spectral flux 资料也提醒 novelty/flux 需要抑制非事件性的持续波动。
- 修改 [AudioRecorder.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/AudioRecorder.kt)：新增 `midFlux`，把 activity/onset/fastImpact 改为 low/mid/body flux 主导；high/air flux 只保留很小权重，避免稳态 hiss 单独推高活动度。
- 修改 [AudioRecorder.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/AudioRecorder.kt)：`transient` 与 highPulse 降低 high/air 权重，键盘等短促事件仍可响应，但空调风噪不会持续驱动。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：body/detail 活动映射从线性 activity 改为 smooth step 门控；低活动时细节几乎关闭，横向底速、rhythm phase 和 idle 时间继续降低。
- 验证与发布：`:app:assembleDebug` 通过（`BUILD SUCCESSFUL in 852ms`）；`:app:publishDebugUpdate` 发布到阿里云 debug 通道，code **202607020958**，日志 [update-20260702175748.md](debug-updates/update-20260702175748.md)。未使用 adb，未安装设备，未 commit。

## 2026-07-02 - D32 实现：安静工位态更平更慢

- 用户反馈：工位上主观较安静，但仍有明显波浪和偏快的左右移动；希望保留水感，但近安静时更平、更慢。
- 修改 [AudioRecorder.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/AudioRecorder.kt)：提高 `activity` 的响度、事件和 spectral flux 起点/满值阈值；提高事件放行和强唤醒阈值；活动度回落略加快。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：降低安静态主体驱动、细节驱动、idle 幅度、微漂幅度和细节预算底线。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：横向主相位速度从线性 `activity` 响应改成 smooth step 门控；安静底速下调，rhythm phase 也增加低活动门槛。
- 验证与发布：`:app:assembleDebug` 通过（`BUILD SUCCESSFUL in 798ms`）；`:app:publishDebugUpdate` 发布到阿里云 debug 通道，code **202607020942**，日志 [update-20260702174146.md](debug-updates/update-20260702174146.md)。未使用 adb，未安装设备，未 commit。

## 2026-07-02 - D31 修复：移除峰顶硬冻结锯齿

- 用户反馈：D30 后波峰处能看到锯齿。
- 诊断：D30 的 `stabilizePeakY()` 按固定采样点逐点冻结最终 y 值，相邻点可能处在不同帧状态，导致峰顶空间不连续。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：删除 `mPreviousSurfaceY`、`resetSurfaceMemory()`、`stabilizePeakY()` 和 `PEAK_STABILITY_*`，不再对最终 y 做上一帧替换。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：新增 smooth step 的峰顶细节衰减，`bodyS` 到达高位波峰时连续降低 `detailS`，减少峰顶小扰动但保持曲线连续；水面采样数从 120 提到 180。
- 验证与发布：`:app:assembleDebug` 通过（`BUILD SUCCESSFUL in 811ms`）；`:app:publishDebugUpdate` 发布到阿里云 debug 通道，code **202607020921**，日志 [update-20260702172004.md](debug-updates/update-20260702172004.md)。未使用 adb，未安装设备，未 commit。

## 2026-07-02 - D30 实现：高位波峰稳定阈值 + 活动度驱动流速

- 用户反馈：D29 效果继续提升，但高位波峰仍有细小变化；安静时横向流动也应更慢，有声音或音乐节奏时再及时跟随。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：绘制循环改为固定 121 个采样点，并保存上一帧每层采样 y 值；高位波峰变化小于 `PEAK_STABILITY_DP` 阈值时沿用上一帧，减少峰顶细小抖动。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：新增 `flowActivity`、`mFlowTime`、`mIdleTime`；主相位、慢速随机包络、idle 波和基线微漂都随 `activity` 放慢，tempo confidence 足够时再按 BPM 轻量加速或减速。
- 验证与发布：`:app:assembleDebug` 通过（`BUILD SUCCESSFUL in 2s`）；`:app:publishDebugUpdate` 发布到阿里云 debug 通道，code **202607020915**，日志 [update-20260702171423.md](debug-updates/update-20260702171423.md)。未使用 adb，未安装设备，未 commit。

## 2026-07-02 - D29 实现：活动度状态机 + 安静低频水感

- 用户确认推进，并要求“往效果最好的方向实现”。
- 修改 [VoiceAudioFrame.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceAudioFrame.kt)：新增 `activity` 字段，作为音频侧传给视觉侧的有效活动度。
- 修改 [AudioRecorder.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/AudioRecorder.kt)：`VoiceAudioAnalyzer` 根据持续响度、onset/fast impact 与 spectral flux 计算 `activity`；安静时门控 rhythm/beat/onset/pulse 和 tempo 历史，强 onset 仍可快速唤醒。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：输入映射按 `activity` 区分主体浪和细节浪；安静时显著降低 rhythm trigger、tempo confidence、细节预算、漂移 boost 与 rhythm contour；静音微动改为更慢、更低频的独立 idle 波，避免近静音高频翻动。
- 验证与发布：`:app:assembleDebug` 通过（`BUILD SUCCESSFUL in 3s`）；`:app:publishDebugUpdate` 发布到阿里云 debug 通道，code **202607020854**，日志 [update-20260702165316.md](debug-updates/update-20260702165316.md)。未使用 adb，未安装设备，未 commit。

## 2026-07-02 - D29 调研：安静降活跃度与自然水感平衡

- 用户反馈：当前录音 dialog 波浪在相对安静时仍会高频变化，动画不够像自然水波；希望继续调研“及时反映音量、音调、节奏”和“自然、灵性的波浪动画”之间的平衡。
- 代码检查：当前链路已经有 `stableInput()` 死区、20ms 音频帧、512-sample fast RMS/rise、2048 FFT、onset/beat/tempo/rhythmEnergy，以及 `incoming -> delayed target -> current` 的视觉平滑链路。安静时仍显活跃的潜在来源包括 `IDLE_AMP` 持续波、`AMP_JITTER` 连续包络、`rhythmEnergy` 对 loudness/fastLoudness 的持续纳入、onset/fastImpact 的连续缩放输出，以及较低的 `RHYTHM_TRIGGER_THRESHOLD`。
- 调研结论：应继续坚持分层方案。音频层负责检测 RMS、spectral flux、onset、beat/tempo 和频段能量；视觉层不应逐帧拟合这些特征，而应先经过噪声门、活动状态、事件合并、包络追随和水体惯性，再映射到主体低频轮廓、宽节奏脉冲和受限高频细节。
- 推荐下一轮 D29：新增噪声地板 / 安静状态机，安静时冻结或强衰减 rhythm/beat 驱动、降低细节预算和漂移 boost、把 `IDLE_AMP` 改为低频慢漂移；强 onset 或明显节拍再快速唤醒节奏层。暂未改代码，等待用户确认推进。

## 2026-07-02 — D25 几何细纹收束 + 低延迟快通道

- 用户确认实现 D25，并强调只要效果最好的方法，不接受偷懒式参数微调。
- 实现 [AudioRecorder.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/AudioRecorder.kt)：可视化采样间隔从 40ms 降到 25ms；新增 512-sample fast RMS/rise，用于更低延迟的 onset/impact；`rhythmEnergy` 纳入持续 loudness/lowPulse，高潮段不再只依赖 beat/onset 才变强；`highPulse` 增加 onset gate，减少高频常态驱动。
- 实现 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：移除 D24 的高频 rhythm surface displacement；rhythm accent 改为宽轮廓脉冲、振幅增强、轻微漂移加速和 event backfill；主体轮廓与高频细节分离，高频细节进入预算限制，rhythm 越强细节预算越收敛。
- 文档更新：新增 D25 决策；README 当前数据流改为 25ms + fast RMS/rise；followups 标记 D25 已本地实现待发布。
- 验证与发布：`:app:assembleDebug` 通过（`BUILD SUCCESSFUL in 4s`）；`git diff --check` 通过，仅有仓库既有 LF/CRLF 提示；`:app:publishDebugUpdate` 发布到阿里云 debug 通道，code **202607020742**，日志 [update-20260702154133.md](debug-updates/update-20260702154133.md)。未使用 adb，未安装设备，未 commit。

## 2026-07-02 — D24 反馈与 D25 调研方向

- 用户反馈：D24 细纹太多，水面容易全是细纹，观感像锯齿；同时延迟仍高，音乐进入高潮后视觉没有及时反应。
- 初步判断：节奏细节被直接叠进水面几何轮廓，频率和面积偏高；延迟不只来自 40ms 采样，还来自 2048 FFT window、onset/beat 平滑、layer rhythm delay 和视觉 attack/release。
- 调研方向：参考实时 onset / beat tracking 和水面渲染资料，下一版应把高频节奏细节从几何轮廓里拿出来，改为低延迟 onset 快通道 + 更宽的低频/中频浪涌，必要时做事件时间回填补偿视觉滞后。

## 2026-07-02 — D24 歌曲节奏响应优化

- 用户反馈：旁边播放比较激情、节奏较快的歌曲时，录音波形和节拍、演唱速度不够贴合，观感偏慢；用户强调希望采用效果最好的方法，而不是只调快参数。
- 实现 [VoiceAudioFrame.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceAudioFrame.kt)：新增 `onset`、`beatPulse`、`beatPhase`、`tempoBpm`、`tempoConfidence`、`rhythmEnergy`、`lowPulse`、`highPulse`，保持旧入口默认兼容。
- 实现 [AudioRecorder.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/AudioRecorder.kt)：采样间隔从 100ms 降到 40ms；`VoiceAudioAnalyzer` 新增基于 loudness rise、spectral flux、低频/高频 flux 的 onset 检测、短历史 tempo 估计和 beat phase / predicted beat pulse。
- 实现 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：新增 rhythm accent 层，每层独立 rhythm delay、amp、speed 和 phase；节奏只驱动短促细纹、局部浪花感、轻微振幅/尖峰加成和 tempo-based drift boost，主水波继续保持 D21-D23 的随机灵动和个性化错峰。
- 文档更新：新增 D24 决策；README 当前数据流改为 40ms + rhythm fields；followups 将歌曲节奏项标记为已实现、待真机确认。
- 验证与发布：`:app:assembleDebug` 通过（`BUILD SUCCESSFUL in 4s`）；`git diff --check` 通过，仅有仓库既有 LF/CRLF 提示；`:app:publishDebugUpdate` 发布到阿里云 debug 通道，code **202607020717**，日志 [update-20260702151600.md](debug-updates/update-20260702151600.md)。未使用 adb，未安装设备，未 commit。

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
## 2026-07-02 — D26 响应链去跳变、渐变方向与锁屏恢复

- 用户反馈 D25 整体效果不错、响应更快，但仍有四个问题：个别波浪偶尔上下颤动；记事渐变色方向没有被录音波浪继承；打开录音 dialog 后锁屏再回来动画停止；希望评估 25ms 采样和动画帧率是否还能更顺滑。
- 诊断结论：剩余颤动主要来自低延迟 rhythm backfill 直接抬高 `mLayerRhythmCurrent`，以及 beat phase 收到新相位后直接改 `mVisualBeatPhase`；渐变方向来自固定横向 `LinearGradient`；锁屏恢复来自 View 自驱帧循环缺少可见性恢复入口；25ms 是音频特征采样，不是动画帧率。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：rhythm backfill 不再写当前值，改为 `mergedPulseTarget()` 目标合并；surge/rhythm 延迟触发共用“已接近峰值则保持、不重新抬峰”的规则；beat phase 改为按帧限速拉近；渐变 shader 按 `ThingBackground.Orientation` 生成 8 向起止点；新增 window 可见性、聚合可见性、焦点恢复时的帧循环重启。
- 修改 [AudioRecorder.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/AudioRecorder.kt)：视觉采样从约 25ms 调到 20ms，同时把录音线程每次读取块缩到约 512 个 stereo frame，避免采样间隔被过大的 `AudioRecord.read()` 阻塞粒度抵消。
- 验证：`:app:assembleDebug` 通过（`BUILD SUCCESSFUL in 3s`）；`git diff --check` 通过，仅有既有 LF/CRLF 提示；`:app:publishDebugUpdate` 已发布到阿里云 debug 通道，code **202607020804**。未使用 adb，未安装设备，未 commit。
## 2026-07-02 - D27 果冻感与重新开始失效修复

- 用户反馈：当前版本在小幅音频变化下仍有果冻感，希望忽略不重要的小变化；同时录音结束后点击重新开始，重复几次“开始 -> 结束 -> 重新开始”后，可能出现只有水流动、没有波峰波谷响应的问题。
- 诊断结论：果冻感来自 20ms 音频特征更新后，小幅 loudness / band / rhythm 变化持续进入视觉目标链路；重新开始失效的核心风险在 `AudioRecorder` 旧监听线程没有被明确 stop/join，且线程使用全局 `mIsListening` 与可变 `mAudioRecord`，快速重启后旧线程可能与新线程争用同一录音源或让 analyzer 不再收到有效 PCM。
- 修改 [AudioRecorder.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/AudioRecorder.kt)：新增 `mRecordingThread`、线程私有 stop 标记、`restartListening()`、`ensureAudioRecord()` 和 `stopListeningThread()`；停止时请求旧线程退出并停止 `AudioRecord`，启动时为新线程捕获当前 raw 文件和 `AudioRecord`；raw 写入和 wav 转存都按真实读取长度写入；`release()` 与保存路径改为可重复调用的空值安全逻辑。
- 修改 [AudioRecordDialogFragment.kt](../../../app/src/main/java/com/ywwynm/everythingdone/fragments/AudioRecordDialogFragment.kt)：重新开始按钮改为清理当前保存文件后调用 `mRecorder.restartListening()`，不再分散手写 stop/start。
- 修改 [VoiceVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/VoiceVisualizer.kt)：在 `receive(VoiceAudioFrame)` 入口为分量目标、水位、rhythm energy、low/high pulse 增加 `stableInput()` 死区与零值门槛，忽略不构成有效视觉反馈的小幅变化，减少果冻感。
- 验证与发布：`:app:assembleDebug` 通过；`:app:publishDebugUpdate` 已发布到阿里云 debug 通道，code **202607020823**。未使用 adb，未安装设备，未 commit。
## 2026-07-02 - D28 停止和重新开始按钮卡顿修复

- 用户反馈：按下停止按钮、重新开始按钮都会出现 UI 卡死约一秒。
- 诊断结论：D27 为修复重启后动画失效，引入了 `AudioRecord.stop()`、`RecordingThread.join(600ms)` 和 raw -> wav 转存的严格收束；这些操作仍在 `AudioRecordDialogFragment` 的点击回调里同步执行，所以停止和重新开始都会阻塞主线程。
- 修改 [AudioRecordDialogFragment.kt](../../../app/src/main/java/com/ywwynm/everythingdone/fragments/AudioRecordDialogFragment.kt)：新增 `mRecorderTransitionInProgress` 和后台收束流程；停止按钮立即切到 STOPPED UI，后台执行 `stopListening(true)`、wav 转存和 `startListening()`；重新开始按钮立即切到 PREPARED UI，后台删除旧文件并执行 `restartListening()`；后台完成后再恢复按钮点击。
- 同步调整 dismiss：资源释放和 `audio_raw` 临时目录清理改到后台线程顺序执行，避免关闭 dialog 时也被录音线程收束卡住。
- 验证与发布：`:app:assembleDebug` 通过；`:app:publishDebugUpdate` 已发布到阿里云 debug 通道，code **202607020832**。未使用 adb，未安装设备，未 commit。

## 2026-07-02 - 确认 FAB 跟随记事颜色

- 用户反馈：录音完成后的确认/保存 FAB 应使用当前记事颜色，纯色显示纯色，渐变显示渐变；图标颜色根据记事色自适应；普通状态仍保持白色。
- 修改 [fragment_record_audio.xml](../../../app/src/main/res/layout/fragment_record_audio.xml)：在主 FAB 后增加同尺寸圆形色层，用于确认态承载纯色或渐变背景，FAB 本体继续负责点击、阴影、涟漪和图标。
- 修改 [AudioRecordDialogFragment.kt](../../../app/src/main/java/com/ywwynm/everythingdone/fragments/AudioRecordDialogFragment.kt)：保存态移除固定绿色 `#4CAF50`，改为当前 `ThingBackground`；确认态让 FAB 本体透明并显示后方色层；开始录音和重新录音恢复白色；确认图标通过 `BackgroundUtil.onColor(...)` 自动取偏白或偏黑。
- 验证与发布：`:app:assembleDebug` 通过；`:app:publishDebugUpdate` 已发布到阿里云 debug 通道，code **202607021207**。远端 `latest.json` 已确认 APK URL 和 SHA-256。未使用 adb，未安装设备，未 commit。

## 2026-07-02 - 确认按钮去除 FAB 叠层

- 用户反馈：上一版效果很差，普通状态下出现正方形背景，确认状态下图标附近出现多边形/八边形残留；要求不要用 FAB，统一改成圆形按钮。
- 诊断结论：上一版 `FloatingActionButton` 透明化后，Material FAB 的内部 shape/ripple 仍参与绘制；背后色层又没有裁剪成圆形，因此同时出现方形底和多边形残留。
- 修改 [fragment_record_audio.xml](../../../app/src/main/res/layout/fragment_record_audio.xml)：删除 `FloatingActionButton` 和背后色层，主按钮改为单个 56dp `ImageView`，16dp padding 保持 24dp 图标尺寸。
- 修改 [AudioRecordDialogFragment.kt](../../../app/src/main/java/com/ywwynm/everythingdone/fragments/AudioRecordDialogFragment.kt)：主按钮类型改为 `ImageView`；普通态直接设置圆形高架面背景；确认态直接设置当前 `ThingBackground` 的圆形纯色/渐变背景；图标和 ripple 按背景明暗自适应。
- 验证与发布：`:app:assembleDebug` 通过；`:app:publishDebugUpdate` 已发布到阿里云 debug 通道，code **202607021213**。远端 `latest.json` 已确认 APK URL 和 SHA-256。未使用 adb，未安装设备，未 commit。

## 2026-07-02 - 录音 dialog 高度改为宽度四比三

- 用户询问当前高度并要求调高为宽度的 `4/3`。
- 当前 [fragment_record_audio.xml](../../../app/src/main/res/layout/fragment_record_audio.xml) 根布局宽度为 `280dp`，此前高度为 `320dp`；按 `280 × 4 / 3` 计算，新高度设置为 `373.33dp`。
- 修改根布局 `android:layout_height` 和 `android:minHeight` 为 `373.33dp`。保留 `minHeight` 是因为 `BaseDialogFragment` 以 `null parent` 充气，根节点 `layout_height` 可能不作为实际固有尺寸。
- 验证与发布：`:app:assembleDebug` 通过；`:app:publishDebugUpdate` 已发布到阿里云 debug 通道，code **202607021217**。远端 `latest.json` 已确认 APK URL 和 SHA-256。未使用 adb，未安装设备，未 commit。

## 2026-07-02 - 录音 dialog 高度改为 360dp

- 用户要求将上一版 `373.33dp` 的录音 dialog 高度改为 `360dp`。
- 修改 [fragment_record_audio.xml](../../../app/src/main/res/layout/fragment_record_audio.xml)：根布局 `android:layout_height` 和 `android:minHeight` 同步改为 `360dp`。
- 验证与发布：`:app:assembleDebug` 通过；`:app:publishDebugUpdate` 已发布到阿里云 debug 通道，code **202607021219**。远端 `latest.json` 已确认 APK URL 和 SHA-256。未使用 adb，未安装设备，未 commit。

## 2026-07-03 - 新录音波浪实现与入口切换

- 用户在调研和方案确认后要求进入实现并发布阿里云，同时强调 dialog 尺寸必须保持 `280dp * 360dp`，自定义 View 不得改变对话框尺寸。
- 新增 [RecordingAudioFeatureFrame.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingAudioFeatureFrame.kt)、[RecordingWaveDriveFrame.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveDriveFrame.kt)、[RecordingWaveFrameReceiver.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveFrameReceiver.kt)、[RecordingAudioAnalyzer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingAudioAnalyzer.kt)、[RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)。
- 新特征层使用 RMS / dBFS、adaptive floor、fast level、8 段 FFT、spectral centroid / rolloff / flatness、spectral flux、onset、弱 tempo / beat、轻量 YIN pitch、stereo balance；再映射成 `level`、`presence`、`swell`、`wake`、`pace`、`surfaceLife`、`pitchLift` 等水体语义驱动。
- 新动画 View 使用底部水体、6 道实例随机主波、局部浪涌事件和表面高光；安静态保留轻微自然流动，presence gate 与 noise-like 判定会抑制环境噪声导致的剧烈动画；有效声音优先推动浪高和局部浪涌，水位只慢速少量变化。
- 修改 [AudioRecorder.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/AudioRecorder.kt)：运行入口改为 `RecordingAudioAnalyzer`，接收者改为 `RecordingWaveFrameReceiver`，旧 `VoiceAudioAnalyzer` / `VoiceAudioFrame` 留在文件中但不再被当前录音 dialog 使用。
- 修改 [AudioRecordDialogFragment.kt](../../../app/src/main/java/com/ywwynm/everythingdone/fragments/AudioRecordDialogFragment.kt) 和 [fragment_record_audio.xml](../../../app/src/main/res/layout/fragment_record_audio.xml)：`voice_visualizer` 切到 `RecordingWaveVisualizer`；根布局仍保持 `layout_width="280dp"`、`layout_height="360dp"`、`minWidth="280dp"`、`minHeight="360dp"`。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过（`BUILD SUCCESSFUL in 7s`）；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703011723.md" --console=plain --no-configuration-cache` 发布到阿里云 debug 通道，code **202607021718**，远端 APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607021718.apk`，SHA-256 `fd87f3fdf406c53101717d7722c7e82b305321daae5655cc0bd19820b96d65da`。未使用 adb，未安装设备，未 commit。

## 2026-07-03 - 尺寸回归与波峰过尖修复

- 用户反馈：上一版新 visualizer 导致录音 dialog 宽高又变了，高度非常高，顶部计时器文本不居中；同时波峰太尖锐，跳动太快。
- 诊断结论：`BaseDialogFragment` 的 window 高度是 `WRAP_CONTENT`，根布局以 `null parent` 充气后依赖 `minWidth/minHeight` 提供固有尺寸；新 `RecordingWaveVisualizer` 是 `match_parent` 子项，在 `AT_MOST` 测量下默认接受父容器可用高度，导致根 `FrameLayout` 被撑高。计时器偏移是该尺寸回归的连带表现。波峰过尖主要来自窄局部浪涌、高频环纹、较短 attack 和较高横向流速。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：新增 `onMeasure()`，非精确测量下声明 `280dp * 360dp` 固有尺寸；放慢 `level/presence/swell/turbulence/wake/pace` 等视觉状态追随；降低横向流速、高频交叉分量、crest 线条强度与 turbulence/impulse 权重；局部浪涌改为更宽、更慢、更低强度的波包。
- 修改 [fragment_record_audio.xml](../../../app/src/main/res/layout/fragment_record_audio.xml)：根布局尺寸仍保持 `280dp * 360dp`，计时器 `layout_gravity` 改为 `top|center_horizontal`。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过（`BUILD SUCCESSFUL in 1s`）；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703095917.md" --console=plain --no-configuration-cache` 发布到阿里云 debug 通道，code **202607030159**，远端 APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030159.apk`，SHA-256 `d4a417608b4914482491878cfd0d0d7d909a24ca746f3e631f1399ac82b4f75e`。未使用 adb，未安装设备，未 commit。

## 2026-07-03 - 增强起伏与颜色深度

- 用户反馈：浪的上下起伏可以再大、更显著，水位起伏也可以再大一点点；当前颜色太淡，需要更深。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：提高主波 `baseAmplitude`、`energyAmplitude`、`wakeAmplitude`，增强 `swell` 对层透明度和水体顶部的影响；水位映射从 `0.170` 提高到 `0.215`，并略微加快慢速潮位追随。
- 颜色调整：水体和主波 alpha 提高；`tone()` 改为在浅色背景上更明显压暗、在暗色背景上减少提亮，避免整体发白、过淡。
- 保留上一轮约束：未加快横向主相位速度，未恢复窄而快的局部尖峰；dialog 根布局仍保持 `280dp * 360dp`。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过（`BUILD SUCCESSFUL in 4s`）；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703100751.md" --console=plain --no-configuration-cache` 发布到阿里云 debug 通道，code **202607030208**，远端 APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030208.apk`，SHA-256 `6c20500d08970a578c877641f034c0be4d3ca2764bacccd5c3ac000f257cc62a`。未使用 adb，未安装设备，未 commit。

## 2026-07-03 - 顶部平浪、起伏幅度与竖条闪烁优化

- 用户反馈：最上方总有一道几乎一直平着的浅浪，希望它也起伏；波峰和波谷的高度差异还可以再大；当前实现里有从左往右滑动闪烁的竖条，渲染质量不高。
- 诊断结论：顶部平浪来自 `drawWaterBody()` 用矩形绘制，水体上边界天然是水平线；竖条感主要来自半透明填充路径的粗折线采样和表面高光短亮条；起伏差异不足则需要继续提高主体波幅，而不是恢复窄尖瞬态。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：新增 `bodySurfaceY()`，水体上边界改为低频波形路径；水体和主波填充路径从折线改为更密的二次曲线采样；提高 `baseAmplitude / energyAmplitude / wakeAmplitude` 并增加最终波高放大系数；收敛表面高光的触发门槛、概率、alpha、移动速度和形状。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过（`BUILD SUCCESSFUL in 3s`）；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703101617.md" --console=plain --no-configuration-cache` 发布到阿里云 debug 通道，code **202607030216**，远端 APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030216.apk`，SHA-256 `d30a1a7539754869d42ff5851de94b173d47fff163a7b16bcde004d4eafd3c1e`。未使用 adb，未安装设备，未 commit。
## 2026-07-03 - Fable 自然波场内核补强与边界随机

- 读取交接文件后继续当前录音波浪任务，确认本轮只借 Claude Fable 的自然波浪动力学内核，不整体替换 EverythingDone 的视觉风格，也不修改 `bigA`。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：在 6 层水面上加入小幅实例级随机，让相位、采样偏移、振幅、速度和细节比例每次 dialog 生命周期略有不同，但不逐帧跳变，不改变层数和核心稳定常量。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：物理波场脉冲改为 crest/trough 成对速度扰动，并轻量消除整体速度均值；同时略微放慢波速、提高黏性、加宽脉冲核，减少尖锐跳动和整体机械抬升。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：加密水体和主波填充曲线采样，继续压低半透明填充路径产生的竖向分段和闪烁感。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703110927.md" --console=plain --no-configuration-cache` 已发布到阿里云 debug 通道，code **202607030311**，APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030311.apk`，SHA-256 `6143a65ccaeef6c82ce7f17a096ce3254da8aeb438ab9039ab46a6fa287fb142`。未使用 adb，未安装设备，未 commit。
## 2026-07-03 - Fable 内核剩余项补全

- 用户追问是否还有 Fable 相关内容未实现；重新对照 `E:\tmp\audio-visualizer-fable` 参考实现后，确认继续只借自然动力学内核，不搬粒子水花、径向光斑、三层视差等 Fable 视觉身份。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：新增冲击事件队列，`receive()` 只入队，动画帧 `advance()` 再统一注入物理波场，使音频回调和水波积分解耦。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：补回 `onWindowVisibilityChanged()`、`onVisibilityAggregated()` 和 `onWindowFocusChanged()` 的帧循环恢复/停止逻辑，降低锁屏返回或窗口状态变化后动画停止的风险。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：加入轻量 `beatBreath`，在 tempo 置信度足够时只调制宽浪呼吸；横向流速改为活动度门控，安静时更慢，有效声音和快节奏出现后再明显活跃。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703111609.md" --console=plain --no-configuration-cache` 已发布到阿里云 debug 通道，code **202607030316**，APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030316.apk`，SHA-256 `c9dcc4970ff0fd247fe755f5ba63db6d71f7bd02998361a7a161799af7c8f5d1`。未使用 adb，未安装设备，未 commit。
## 2026-07-03 - 去白边、增强野性和修正高潮能量

- 用户反馈三点：每一道浪顶部像有白边；波峰/波谷高度差太小，声音丰富场景缺少“野性”和相对尖锐的大浪；一首歌前奏水位高、高潮反而下降，怀疑当前实现过度跟随音调/频段亮度而不是音量和整体能量。
- 诊断结论：白边来自 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt) 中每层 `crestPath` 的高亮描边；野性不足来自主波振幅、物理冲击和波形整形仍偏保守；高潮下降风险来自 [RecordingAudioAnalyzer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingAudioAnalyzer.kt) 中 `brightness` / `noiseLike` / 相对响度对水位和浪高的权重不平衡。
- 修改 [RecordingWaveVisualizer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingWaveVisualizer.kt)：删除每层 crest 描边，只保留填充水面；新增 `waveWildness()` 和 `shapeAudioWave()`，高 presence / level / swell / turbulence / wake / pace 时对主波做非线性放大和 crest sharpening；提高主波能量振幅、wake 振幅和物理波场冲击力度，同时降低 pitch / brightness 对主波频率的影响。
- 修改 [RecordingAudioAnalyzer.kt](../../../app/src/main/java/com/ywwynm/everythingdone/views/recording/RecordingAudioAnalyzer.kt)：提高 `absoluteLevel`、`fastLevel` 和整体频带能量对 `level`、`waterLevel`、`swell` 的权重；降低 `noiseLike` 对高能量人声/高潮段的压制，让高潮段即使频段不如前奏乐器明亮，也更可靠地表现为更高水位和更剧烈波浪。
- 验证与发布：`:app:assembleDebug --console=plain --no-configuration-cache` 通过；`:app:publishDebugUpdate "-PdebugUpdateNotesFile=docs/features/recording-wave-visualizer/debug-updates/update-20260703112811.md" --console=plain --no-configuration-cache` 已发布到阿里云 debug 通道，code **202607030328**，APK `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607030328.apk`，SHA-256 `9a1ea2136d277a5e48a7500016c5ef93bcac9da75774bb18898510e360abaa68`。未使用 adb，未安装设备，未 commit。
