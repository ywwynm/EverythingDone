# audio-visualization-fable-sol

2026-10-17 收尾：用户选择保留最新版卓易通方案并提交。已统一隐藏 HDR 长按对照、调试设置组和 HUD，debug 对照 Activity 禁用；旧 HUD 偏好不再显示面板。诊断及日志导出实现保留，后续需专门启用入口。构建通过，1098 项回归通过、1 项默认关闭的 CPU 基准跳过；不改当前渲染与性能策略。debug `202610171315` 已发布并核验远端 APK、原签名和禁用的 Activity，见 [发布说明](debug-updates/update-20261017211508.md)。

2026-10-17 补充：统一复算最近五版后，`1044` 的计时提交 20.82 ms、海浪 53.42 次提交／秒，略优于当前 `1132` 的 21.24 ms／52.22 次；当前版缓冲占用更低。差异较小且非受控测试，不能认定最新版历史最优或回退必然更快；保留 `1044` 作为对照基线。见 [历史比较](composition-performance-2026-10-17.md)。

2026-10-17：第九份日志确认 `202610171132` 持续请求 90 Hz 且无接口失败，但停止触摸后仍回到 60 Hz。匹配纯 60 Hz 窗口后，计时提交 22.18→21.24 ms、海浪 50.05→52.22 次提交／秒，仅小幅变化，形变到提交没有改善。用户表示当前流畅度可以接受；本轮仅分析归档，建议暂缓继续调优，保留现版和日志导出。更大优化仍有空间，但涉及共用计算或合成调度，详见 [最新分析与边界](composition-performance-2026-10-17.md)。

2026-10-17：第八份日志证明纹理复用降低前景替换耗时（0.81→0.22 ms），但计时提交 20.73→21.52 ms、海浪 54.57→50.72 次提交／秒，整体未再改善。新增刷新率证据支持触摸升频结束后回落，省电和窗口节能均关闭。本轮仅 Direct 在同尺寸存在 90 Hz 时请求 90，API 36 采用 AT_LEAST、只允许无缝切换，保留原纹理缓存和日志。构建及 544 项相关回归通过，debug `202610171132` 已发布并核验远端包／原签名，实际复验状态见 [本轮分析](composition-performance-2026-10-17.md)。

2026-10-17：第七份日志确认撤回消费等待后计时捕获／提交改善为约 16.67／20.73 ms，海浪约 54.57 次提交／秒，没有变慢。本轮仅复用卓易通前景的 EGLImage／纹理，保留逐帧重绑和 GPU 所有权；新增缓存指标及 90／60 Hz 降档观察信息，不改变刷新率策略。构建及 531 项相关回归通过，debug `202610171105` 已发布并核验远端包／原签名，实际复验状态见 [本轮分析](composition-performance-2026-10-17.md)。

2026-10-17：第六份日志支持用户反馈，`1018` 的计时提交间隔比 `1002` 从 21.45 增至 23.25 ms，捕获至提交从 18.51 增至 21.11 ms；海浪有所恢复。消费前替换为 0 没有带来控件收益，已撤掉消费等待，恢复 HWUI 捕获与 GL 处理重叠，消费回调只作诊断。517 项相关回归通过；debug `202610171044` 已发布并核验远端包／原签名。普通设备、共用计算及已验证视觉方案不变，实际效果待复验，见 [本轮分析](composition-performance-2026-10-17.md)。

2026-10-17：第五份日志确认补捕获使计时捕获间隔 23.44→16.69 ms、提交间隔 24.18→22.58 ms，GL 排队 6.90→6.52 ms；但海浪耗时升至 20.11 ms，且约四分之一捕获尚未消费即被替换。本轮仅给卓易通前景增加消费反馈，减少无效捕获；共用海浪计算与 shader 未改，用户要求扩大到普通设备前先确认已记录。517 项相关回归通过，debug `202610171018` 已发布并核验远端包／原签名，新候选实际收益待复验。见 [本轮分析](composition-performance-2026-10-17.md)。

2026-10-17：第四份日志确认 `202610170943` 的缓冲回收有效；同为 60 Hz 的稳定片段中，缓冲占用 66.92→45.45 ms，无空闲缓冲 25.46%→0.41%，计时提交间隔 30.01→24.18 ms。剩余损失主要在捕获阶段，本轮增加资源返回后合并补捕获，仍保持串行 HWUI、三缓冲及已验证画面。日志已缩至约 400 KiB，继续保留导出与手动清空。507 项相关回归通过，debug `202610171002` 已发布并核验远端包／原签名，新候选实际收益待复验。见 [分析及候选](composition-performance-2026-10-17.md)。

2026-10-17：第三份日志确认控件提交延迟继续改善；持续 60 Hz 时计时更新／绘制约 16／17 ms，提交仍约 30 ms。新候选以最后一次 GPU 采样标记回收旧前景，减少无关海浪任务造成的缓冲等待；增加计时捕获指标。日志按版本清理、支持手动重录，独立导出避免重复累积。构建及 497 项相关回归通过，debug `202610170943` 已发布并核验远端包／原签名，实际性能仍待复验。见 [本轮分析](composition-performance-2026-10-17.md)。

2026-10-17：第二份性能日志确认上一轮有效，HDR／SDR 海浪约 58／59 次提交每秒，控件约 34／36 次新帧每秒，计时形变仍不顺畅。新候选在最终合成前读取最新控件帧，并允许复用已有海浪单独更新控件；补齐计时更新／绘制／提交指标，保留日志导出。构建、485 项相关回归和 18 项 GPU 画面检查通过；debug `202610170849` 已发布，远端包／原签名及源码范围核验通过，实际收益待复验。见 [第二轮分析](composition-performance-2026-10-17.md)。

2026-10-17：已分析用户性能导出，稳定录音时 HDR／SDR 的控件新帧约 17／20 次每秒，海浪约 43／48 次提交每秒；前景逐次 GPU 等待和双缓冲占用是明确的优化点。候选改为安全的异步 fence 回收及三缓冲，保留现有画面与日志；构建及 474 项回归通过，debug `202610170821` 已发布并核验远端包／原签名，实际收益待用户性能对照。见 [定位与候选](composition-performance-2026-10-17.md)。

2026-10-17：用户确认视觉修复后反馈海浪、ripple 和控件移动仍不够流畅。已发布仅 debug／卓易通的性能取证版 `202610170749`，覆盖三个实际弹窗的前景捕获、GPU 同步等待、GL 排队／渲染、控件新帧与输入／窗口指标，自动记录并接入原文件导出。462 项回归及远端包／签名核验通过；保持已验证显示方案，尚待日志确定瓶颈。见 [指标与复验步骤](composition-performance-2026-10-17.md)。

2026-10-17 最新实测：用户确认 debug `202610170712`“终于 OK 了”。当前设备上的卓易通音频弹窗兼容问题通过用户复验；最终为 C 系统暗层＋N 自管 PQ 输出＋原生前景同缓冲合成，保留日志导出。具体方案与验证边界见 [最终结果](direct-surface-integration-2026-10-17.md)。

2026-10-17：同缓冲前景候选 debug `202610170712` 已发布。仅卓易通三个弹窗将真实控件的硬件绘制结果与海浪统一呈现，保留圆角、C 暗层和日志导出。构建、456 项相关回归、18 项真实 shader GPU 检查及远端包／签名均通过；发布后已收到用户整体确认，更多机型与长期边界仍需回归。见 [发布与验证边界](direct-surface-integration-2026-10-17.md)。

2026-10-17 最新复验：用户确认 `202610170554` 仍在 HDR 出现后丢失计时、按钮、进度条，圆角正常。移除 CLEAR 的候选未通过；本轮没有新的稳定前景日志，不能把窗口清除视为已证实原因。后续候选将真实原生控件离屏绘制后与海浪合成到同一输出缓冲，避免依赖兼容器的跨图层前景顺序；继续保留 C 暗层、圆角和日志导出，普通设备路径不启用。

2026-10-17：已发布背景裁出候选 debug `202610170554`，保持无黑角的媒体子窗口，移除原窗口 CLEAR；稳定前景采样与呈现 fence 解耦，导出等待落盘并保留更长日志。构建及 456 项相关回归、远端包／说明／签名核验通过。实际前景与圆角联合验收仍待用户复验，见 [本轮证据与发布](direct-surface-integration-2026-10-17.md)。

2026-10-17 最新取证：已读取 `202610170528` 导出，PQ／交接成功，窗口透明度正常恢复；已有控件采样都在粒子隐藏阶段，恢复后的采样又因无效呈现回调而缺失。候选改为背景绘制时留出海浪区域、移除原窗口 CLEAR，并补充独立的稳定可见采样与完整落盘导出；圆角、前景和闪烁仍需联合复验。见 [反馈与取证](direct-surface-integration-2026-10-17.md)。

2026-10-17：已发布媒体子窗口候选 debug `202610170528`。仅卓易通生产弹窗移除透明 SDR SurfaceView 容器，以公开的下层媒体子窗口承载既有 PQ 海浪；原 Dialog 保留控件、输入、C 系统暗层及粒子流程。日志增加子窗口全生命周期，原导出入口保持。构建及 451 项相关回归、远端 APK／说明／签名均核验通过，包内确认新容器继承普通 View。圆角和窗口合成仍待用户实测，见 [本轮记录](direct-surface-integration-2026-10-17.md)。

2026-10-17 最新复验：`202610170436` 的计时、按钮、进度条已正常；完整弹窗在 SDR／HDR 下都重新出现黑角，粒子动画图像正常。透明 SDR 容器通过前景项但未通过圆角项；继续调查 SurfaceView 容器及兼容窗口合成，保留日志导出，见 [排查记录](direct-surface-integration-2026-10-17.md)。

2026-10-17：已发布新的层级候选 debug `202610170436`。仅卓易通将自管 PQ 海浪挂到全透明 SDR 容器下，继承系统窗口下层顺序；透明容器排除粒子图像／恢复，保留 C 暗层、现有圆角和日志导出。构建及 451 项相关回归通过，远端包、中文说明、签名与新增 DEX 标记核验通过。前景恢复、圆角及闪烁仍待用户实测，见 [排查与发布记录](direct-surface-integration-2026-10-17.md)。

2026-10-17 复验：用户确认 `202610170418` 仍丢失计时、按钮和进度条，圆角正常，关闭粒子无改善。透明提示候选未通过；继续核对直接图层相对原生窗口的顺序及窗口缓冲，见 [排查记录](direct-surface-integration-2026-10-17.md)。

2026-10-17 新反馈：`202610170404` 的实际弹窗圆角正常，但 HDR 图层出现后计时／图标／进度条消失；关闭粒子仍复现。已发布候选修正版 `202610170418`，保守保留海浪区域内的窗口像素，并新增交接阶段的原生控件窗口采样；保持 N／C 暗层与圆角。构建及 451 项回归、远端包与签名核验通过，前景恢复与闪烁仍待实测。见 [实际接入反馈](direct-surface-integration-2026-10-17.md)。

2026-10-17：按用户授权，N 直接 SurfaceControl 已接入三个卓易通音频弹窗，保留 C 系统暗层和原圆角；普通设备仍用原 SurfaceView。HDR 切换保留旧图层至候选帧就绪，粒子捕获／恢复和关闭隐藏识别新生产者；新增图层日志并纳入原文件导出。最终构建与 451 项相关回归通过，debug `202610170404` 已发布阿里云，远端包、更新说明、签名及新增 DEX 标记核验通过。真实海浪与偶发闪烁仍需用户复验。见 [实际接入](direct-surface-integration-2026-10-17.md)。

2026-10-17 固定图实测：debug `202610170332` 的 C／M 仍有黑角，L（Canvas SDR）及 N（直接 SurfaceControl＋PQ）圆角正常。N 的 PQ、首帧和可见事务均已验证，用户另确认左侧原生白圈完整、颜色正常；固定场景已有有效证据。该版仅诊断页实现 N，后续实际接入见上段。真实海浪、粒子交接、HDR 切换和关闭释放仍待复验，普通设备路径保持。详见 [实测与边界](corner-composition-research-2026-10-17.md)。

2026-10-17 已发布 debug `202610170332`：测试页新增 L／M／N 三组黑角对照。L 为无独立 Surface 的 Canvas，M 为 C 的 PQ 去阴影，N 为直接 SurfaceControl＋同一 PQ 固定图；默认 C，相关切换重建窗口，保留观感及文件导出。生产代码不变；构建与 50 项相关回归通过，远端 APK／元数据／签名已核验。本轮屏幕复验结果见上段。详见 [诊断实现](corner-composition-research-2026-10-17.md)。

2026-10-17 新实机反馈：`202610170304` 的 C／J／K 均仍有黑角。新日志确认 K 的四角窗口缓冲 alpha 已为 0，生产设置弹窗也一样；显式清角候选未通过屏幕验收。原生圆角探测的旧日志明确为 API 不可用。排查转向窗口下层、SurfaceView 背景与兼容宿主合成，保持已正常的粒子动画。详见 [黑角复验与调查](corner-composition-research-2026-10-17.md)。

2026-10-17 最新：用户确认 debug `202610170247` 的粒子动画已正常，圆角黑块仍在。当前继续以 C 系统暗层为基础，新增整个窗口的显式透明清角候选，保留原轮廓供阴影／粒子使用；仅三个卓易通音频弹窗启用。对照页新增 C→J→K（原裁剪／仅关闭窗口裁剪／显式清角），导出附带窗口四角 alpha 与裁剪路径。50 项既有回归通过，debug `202610170304` 已发布并核对远端 APK／说明／签名；最终屏幕效果待实机确认。

2026-10-17 后续：用户录屏确认实际 C 接入版出现背景提前露出、关闭无可见粒子和圆角黑块。本轮恢复首次显示前的窗口／内容共同隐藏，关闭不再改变焦点属性，失败回退不再伪报粒子就绪；新增原生 Surface 圆角候选及详细交接导出日志。debug `202610170247` 已发布阿里云，50 项相关测试与远端包／元数据／签名核验通过。候选尚需卓易通实机复验，尤其原生圆角能力可能被系统拒绝。详见 [回归与候选修正](system-dim-integration-2026-10-17.md)。

2026-10-17：C 系统暗层已接入实际录音、音频附件和海浪设置弹窗，仅 API 34+ 且命中既有卓易通判据时启用。
同一弹窗始终使用系统 dim=0.6，不另挂应用暗层；HDR 关闭仍切回真正的 SDR 表面。粒子使用独立透明承载窗口，
关闭在快照和首帧接管后淡出暗层，归零提交后移除窗口；普通设备继续原路径。
保留长按“HDR 高光增强”的 A～I 对照与文件导出，新日志包含实际弹窗的暗层阶段。
debug `202610170210` 已发布阿里云，45 项测试、远端 APK／元数据与签名核对通过。
真实弹窗的穿透、颜色、圆角及切换连续性仍需用户复验；C 的旧对照结果不等于生产场景验收。
详见 [接入与验收](system-dim-integration-2026-10-17.md)。

此前对照版 `202610161648` 的 C 在固定 PQ 和真实海浪 PQ 下均不再报告穿透，仍有轻微偏色疑虑，固定图黑角仍在。
A、B、D～I 均报告偏色和穿透，未证明兼容合成器内部根因。

2026-10-17：debug `202610161619` 在实际弹窗结构中已复现固定 PQ、海浪 PQ 的偏色与穿透，
海浪 SDR 正常，冻结 PQ 无改善。新版对照默认进入实际弹窗／模式 2，提供 A～I 九项条件：
暗层、窗口／面板背景、两处 View 裁剪及固定图 shader 圆角分别排查；每项从 A 基准恢复。
窗口条件切换不主动重建 Surface／EGL，观感与文件导出按钮常驻底部；日志记录实际配置与
异步采样归属。正式海浪配置保持原状；上述结果来自诊断页，实际音频弹窗接入后仍需验证。
入口仍为长按海浪设置的“HDR 高光增强”。详见 [对照结果与操作](hdr-screenshots-2026-10-16.md)。

2026-10-16：仅在卓易通环境启用 PQ 屏幕输出。FP16 PQ 出现弹窗局部偏青，RGB10_A2 又出现
HDR 区域穿透；当前代码的 RGBA8 PQ 候选也被用户确认仍穿透、偏色，**尚未解决**。系统截图
没有穿透；原始 HDR 截图的按钮底图／增益均为中性白，但用户在图库重显第 1 张时仍略偏青，
第 4 张较正常。新增仅 debug／卓易通可用的固定画面合成对照与文件日志导出，入口为长按
海浪设置的“HDR 高光增强”，发布版本 `202610161547`。见 [截图与诊断说明](hdr-screenshots-2026-10-16.md)。
当前正式海浪实现保留完整 8 位 alpha，
并在 PQ 编码域抖动以抑制色带。
内部仍为 FP16；强度 0 真正重建 SDR 色彩空间，切换等遮盖及目标会话首帧交接。粒子及遮盖
快照直接生成 sRGB。普通 Android 的 SDR／scRGB 与图片、离线导出保持原路径。
修正与验证边界见 [偏色排查](pq-dialog-colors-2026-10-16.md)，首版 D222 的关闭档例外已撤回。

把桌面模拟器 `audioVisualizerSimulatorFable`（PySide6 + numpy）的**实时分析 → 可视化**
链路移植进 EverythingDone，作为录音对话框的水波可视化。目标：视觉效果、物理效果
一比一复刻原版；命名统一用 `FableSol` 后缀（对照现有 `Opus` 后缀）；现有 Opus 代码保留。

## 本目录文档

- `decisions.md` / `sessions.md` / `followups.md` / `preferences.md` — 决策、会话、遗留项与偏好
- `on-device-perf-testing.md` — 实机性能测量方法
- `rim-edge-testing.md` — 银丝沿轮廓连续性的测量方法（栅格相位相关判据、D219 基线数字、暗带排除性检查）

## 范围

**移植**（原版纯数学核心 + 渲染，作者已注明是"Android 移植蓝本"）：

- 实时音频特征链：`features.py` / `speed.py`
- 音频→驱动映射：`mapping.py`
- 九层水体物理：`simulation.py` / `waves.py` / `ambient.py`
- 渲染：`canvas.py`（环境天空、九层水面、C2 B-spline fairing + Hermite 重建、镜面/菲涅尔高光、波峰透光、波冠轻纱、珍珠色）
- 常量/参数：`spec.py` / `params.py`（参数取默认值硬编码）

**不移植**：桌面文件读取/播放 UI（`decode.py`/`offline.py`/`engine.py`、`panel.py`/
`meters.py`/`main_window.py`）、可选神经人声模型、离线前瞻导演和进程 IPC。Android 使用
自己的实时 PCM 输入、原生调参 Dialog 与线程模型；实时 Foote 段落、因果 Drop 和 DSP 人声
运动证据属于实时分析链，已随 FableSol 移植。

## 关键决策

- **配色**：物理/高光一比一；配色不用原版独立 palette，改接记事的 `ThingBackground`
  （支持纯色 + 渐变，渐变方向沿用记事的 8 向 `Orientation`，复用 `BackgroundUtil.createLinearGradient`）。
  原版"远层混白空气透视 / 色彩呼吸 / OKLab 混色 / 珍珠偏色"逻辑保留，但混白量随层深
  `depth01` 增长：第 0 层恒不混白，直接使用记事本色。纯色的 c1/c2 都取 `bg.color`；渐变的
  c1/c2 取原始 `color/endColor` 并保留方向。见 decisions.md D1、D6。
- **接入**：直接把录音对话框可视化换成 `WaveVisualizerFableSol`，Opus 的 .kt 全部保留、不删。
- **坐标/尺寸**：波长、浪高、速度与网格间距继续使用 Fable 的 dp 标尺；`320dp` 只作为
  网格采样间距和 View 尚未完成测量时的回退基准。运行时物理容器宽度来自
  `WaveVisualizerFableSol.onSizeChanged()` 的最终实测 `w / density`，不读取 XML 的 `280dp`，
  也不从 TimelyClockView 的声明尺寸推算。容器高度保持 420dp。体积守恒、墙面、倾斜水位、
  可见跨度和按宽度定义的事件全部使用这项实测宽度。见 decisions.md D2、D8。
- **采样率**：复用现有采集 44100Hz（原版 48000Hz）。`FRAME_RATE=SR/HOP` 自适应，
  算法结构不变，仅频率分辨率有极小差异（bin 21.5Hz vs 23.4Hz），视觉无感。见 decisions.md D3。
- **可信声音门**：A 加权感知总能量限制在 16kHz 以下，flux 的 32 个对数频带严格止于 12kHz；
  Analyzer 从静音启动，并以 −66~−54dBFS smoothstep 置信度缩放响度、频段和 onset，避免手机
  近 Nyquist 电子干扰及 AGC 泵动被相对归一放大。见 decisions.md D9。
- **采集启动预热**：Android 首次启动 AudioRecord 时，连续的低频暂态在最多 4.5 秒的自适应预热内
  不驱动水位或 onset；稳定静音或可信中高频内容持续 0.3 秒即可提前放行。只保护可视化，不裁剪 WAV。
- **浪形连续性**：onset 不再直接修改程序化主浪；快速能量只进入 DynamicWave 物理注入。
  HeroWave 只按慢包络改变，几何粗糙度与快速光学材质分离。见 decisions.md D12。
- **段落检测**：移植实时 Foote 新奇度 `_NoveltyDetector`（驱动性格档切换；段涌 surge_gain 默认 0）。
- **因果感知标定**：动画主链消费固定声学域的 `W/S/K/I`、四类音乐运动、能量上升与
  grade 证据；旧 `loudness01` 只保留诊断兼容，不再驱动水位或浪形。麦克风采集显式套用
  手机录音补偿，原始 A 计权静音门始终读取未补偿 PCM。
- **七境与巨浪**：七境以软时长、迟滞和证据释放实时解码；`LIFT` / `CLIMAX` 可持续。
  巨浪只在 `PEAK` / `CLIMAX` 的有效音乐运动到达时触发，全局冷却 14 秒、无每段配额；
  物理实体固定为第 0 层 840dp × 144dp C2 宽峰，按真实流速从画外自然通过。
- **录音状态**：PREPARED/STOPPED 继续监听并驱动低透明度水面，开始录音不重置 Analyzer；
  这是 Android 录音预览设计，不要求跟随 Python 播放器状态机。见 decisions.md D7。

## 线程模型（对照原版进程/UI 定时器解耦）

- **音频线程**（`AudioRecorder.RecordingThread`）：PCM(16bit)→float → `FableSolRealtimeAnalyzer.feed()`
  → `(frames, events)` → 经 `FableSolFrameReceiver` 塞进 View 的并发队列。
- **渲染线程 / Canvas 回退 UI 线程**：drain 队列 → 按音频时间逐个消费所有 authoritative
  frame，并把稀疏 event 稳定交织回相邻 hop → `Simulation.update(dt)` → 渲染。不能只取最新
  frame，否则会漏掉 0.2 秒证据窗并让 60/120Hz 屏幕得到不同状态。
- `Simulation` 只被当前渲染所有者单线程访问：GLES 主路径为 GL 线程，Canvas 回退为 UI
  线程，因此无需加锁；音频线程只生产 frames/events。

## 命名映射（Python → Kotlin，包 `views.recording.fablesol`）

| 原版 | Kotlin |
|------|--------|
| `spec.py` | `FableSolSpec.kt` |
| `params.py` | `FableSolParams.kt` |
| `core/waves.py` `DynamicWave` | `FableSolDynamicWave.kt` |
| `core/ambient.py` Ambient/Hero/Optical | `FableSolWaveSets.kt` |
| `core/simulation.py` Simulation/LayerSim | `FableSolSimulation.kt` / `FableSolLayerSim` |
| `core/mapping.py` FeatureMapper | `FableSolFeatureMapper.kt` |
| `audio/state_evidence.py` | `FableSolCausalStateEvidence.kt` |
| `core/state_decoder.py` / `core/states.py` | `FableSolSoftDurationGradeDecoder.kt` / `FableSolSevenStateMachine.kt` / `FableSolContinuousStateChannels.kt` |
| `core/grand_wave_gate.py` / `core/grand_wave.py` | `FableSolGrandWaveEventGate.kt` / `FableSolGrandWave.kt` |
| `audio/speed.py` | `FableSolSpeed.kt` |
| `audio/features.py` RealtimeAnalyzer/RingStat/BeatTracker/NoveltyDetector | `FableSolRealtimeAnalyzer.kt` / `FableSolRingStat.kt` / `FableSolBeatTracker.kt` / `FableSolNoveltyDetector.kt` |
| feature dict / event dict | `FableSolFeatureFrame.kt` / `FableSolEvent.kt` |
| `ui/canvas.py` VisCanvas 渲染 | `WaveVisualizerFableSol.kt` |
| `canvas.py` OKLab/hue 工具 | `FableSolColor.kt` |
| — | `FableSolFrameReceiver.kt`（接收器接口） |

## 重力→倾斜

原版 `set_tilt(deg)`；Fragment 已把重力投影到屏幕平面 `(screenX, screenY)`。
`WaveVisualizerFableSol.setContainerGravity(x,y,z)` 内部换算
`deg = toDegrees(atan2(x, y))` 传给 `Simulation.setTilt(deg)`。Android 实现接受完整 360° 重力方向，
完全倒置时保持 ±180°，并在 179°↔−179° 边界选择最短连续旋转，因此水体可与旧 Opus 一样转到
Dialog 顶部。录音 Dialog 打开时锁定宿主 Activity 当前方向，销毁或关闭时恢复原方向；该生命周期与
切换 FableSol 前的 Opus 实现相同。原版无 z 通道，z 暂忽略。
