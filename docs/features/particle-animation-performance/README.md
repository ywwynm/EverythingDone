# 粒子动画性能优化

跨三个功能的性能专项：弹窗粒子出现/消失（`dialog-particle-dismiss`）、新建记事入场（`thing-creation-animation`）、左滑完成记事（`swipe-complete-animation`）共用同一套粒子管线，性能问题也是同一组结构性原因，所以放在一个目录里处理。粒子模型、材料、随机、积分与压力迭代次数不在本专项范围内。

## 状态

- 2026-09-15：**弱设备阶段 A 完成并发布 `202609151437`**，见
  [weak-devices-phaseA-2026-09-15.md](weak-devices-phaseA-2026-09-15.md)。用户报告的
  「消散有时不播」定位到**抓图选路**：此前只有「有涟漪」（点按钮关闭）才走离屏硬件抓图，
  返回键与点外部退回窗口 `PixelCopy`，而后者在魅族 20 Infinity 上对「调整颜色」面板
  整个 700 ms 重试窗口都返回 `ERROR_SOURCE_NO_DATA`，修前 2/2 不播。改成「没有可见独立
  Surface 就一律走离屏硬件抓图」并加一次单向兜底之后，**魅族 12/12、华为 16/16 全部开播**。
  同时补齐六类具名失败日志（release 零开销），并纠正「魅族动画缩放 0.75」这条前提（实测 1.0）。
  阶段 B「消散提前预算」按用户裁定暂缓。
- 2026-09-15：**第五批完成并发布 `202609150517`**，见 [batch5-results-2026-09-15.md](batch5-results-2026-09-15.md)。
  第四批那两处「整帧只有 1 个像素不同」定性为**驱动层面的非确定性**：把对照改成「同一条路径
  自己跑多遍」之后，**同一条路径与它自己**就会有约 0.5–0.9% 的运行出现一帧差异；并发负载开关
  两种条件下的事件率分不出差别（开 3/328、关 3/472），逐帧连读两次证明也不是回读不稳。
  定点复现拿到了决定性的一帧：**状态缓冲逐位相同而画出来的像素差了 2 个**——绘制的输入
  一个比特没变、输出却不同。**G0 的判据因此改成两步**：孤立单帧、个位数像素的差异先跑
  自比较，自比较干净才算真回归。共享状态审查另找到一处真实的隔离缺陷（`withBaselineGpu` 改的是
  进程级开关，会被常驻 GL 线程上的提前预算读到），已改成线程局部。第四批跳过的两项回归
  （弹窗四条关闭路径、独立 Surface 交接像素）两台各一轮补齐。**追加项**：创建页预测素材
  持久化——空白创建页首屏无损存进应用私有目录，冷启动后按键读回，**冷启动第一次新建
  OPPO 549.7 → 221.5 ms、三星 971.2 → 324.9 ms**，单张 WEBP 只有 69–143 KB。
- 2026-09-15：**第四批完成并发布 `202609150306`**，见 [batch4-results-2026-09-15.md](batch4-results-2026-09-15.md)。
  创建页轨迹在首页空闲时提前预算（轨迹与贴图分离），命中时点新建按钮到首帧上屏
  OPPO **484 → 171 ms**、三星 **871 → 305 ms**，末帧仍与真实页面逐像素相同；弹窗 / 面板 /
  新卡片的出现也跳过尾段空白（首帧提前 60–100 ms）；三星涟漪的根因查清是「脏区太小 → 可变
  刷新率降档」，改成涟漪期投高帧率票后同段出帧数 33 → 42；材料工作线程跟随调用线程优先级
  （−4 → −4，但三星建材没有可测变化）。顺带修掉 `setLanes(0)` 把并行道数钉死在 1 的缺陷。
- 2026-09-14：完成代码层面的诊断与优化方案草案；用户逐项裁定 D1–D6（见 decisions.md），并授权两台真机 adb。实施交给 Opus 子代理，Fable 负责任务书与关键检查。
- 2026-09-14：**阶段 0（测量与归因）完成**，见 [phase0-baseline-2026-09-14.md](phase0-baseline-2026-09-14.md)。两台真机共 176 轮。屏障不是瓶颈，压力更新占预计算四成；涟漪卡顿主因是 60 Hz 窗口投票把涟漪帧率砍半，GPU 排队只占 1–4 ms。阶段 2 的排期需按该文的建议 1 调整。
- 2026-09-15：**3b 完成并发布**（见 batch3-results 第 12 节）。归因轮否掉了「2b 让每采样贵 21–25%」
  的推断（受控对照下 OPPO +15%、三星 −3%）；出现动画的最终配方定为「方向 + 触点距离」，
  2c 门槛 5% → 10%；「预计算完成 → 首帧上屏」的空档查清是「节拍没提前跑」+「Surface 首次合成」，
  OPPO 108 → **26 ms**，点按钮到首帧上屏 596 → **484 ms**；本地材料库道数 3 → 6（逐位对照守住），
  OPPO 建材 57.7 → 36–43 ms。
- 2026-09-15：**第三批完成并发布**，见 [batch3-results-2026-09-15.md](batch3-results-2026-09-15.md)。
  左滑快拖的回退修好（首个非零画面 OPPO 132.3 → 65.0 ms、三星 201.0 → 122.0 ms）；创建页出现的
  「点下去要等一下」经分段量化**确认是真等待**（首帧上屏 596 / 890 ms，GPU 预计算占 44%–53%），
  起始段看不见只值 16–33 ms。2b（以新建按钮为真实触点）与 2c（跳过倒放尾段空白）已落地，
  各带开关、默认开，G0 新增的「截断预计算逐帧」两台零差异。第三项（为下一次新建预算轨迹）
  评估后不做。**2b 让每采样预计算贵 21–25%，归因实验待裁定。**
- 2026-09-15：**第二批完成并发布**（`202609141724`），见 [batch2-results-2026-09-15.md](batch2-results-2026-09-15.md)。
  一窗一 Surface 的 SurfaceView、常驻 GL 线程与上下文（D4）、去掉每帧 `glFinish`、Surface 无缝帧率投票、
  时间基准统一、快照单拷贝全部落地；G0 除原有两条对照外新增「两个会话在同一上下文交错播放」，
  两台真机三条对照零差异。每次动画的管线准备 8.6–10.9 ms → **0.1–0.4 ms**，连开关 20 层帧距全部 16.7 ms，
  工具栏涟漪期 Activity 窗口 600 ms 出 62–68 帧。**左滑快拖的首个非零画面未达标**，写入 followups。
- 2026-09-14：**第一批完成并发布**，见 [batch1-results-2026-09-14.md](batch1-results-2026-09-14.md) 与 [batch1-regression-2026-09-14.md](batch1-regression-2026-09-14.md)。收益全在 CPU / 时序侧：1.9 的根因是框架时长缩放与 `Settings.Global` 不一致（OPD2515 实测 5.0 对 1.0），修后「录音行 → 添加附件消散首帧」从 1523 ms 降到约 400 ms；GPU 侧四项里 2.1、2.5 破坏逐位一致、2.2 逐位一致但更慢，三项撤回。1.5 整体推迟到第二批。

## 文档

- [analysis-2026-09-14.md](analysis-2026-09-14.md)：四条用户反馈的逐条诊断、现有管线的时序链与每帧 GPU 工作量。
- [plan.md](plan.md)：分阶段方案、视觉保证级别（G0 / G1）、验收口径、风险与需要用户裁定的问题。
- [research-2026-09-14-platform.md](research-2026-09-14-platform.md)：两项平台调研的摘要与对方案的影响；完整报告见 [research-2026-09-14-platform-full.md](research-2026-09-14-platform-full.md)（上下文优先级、SurfaceView、刷新率、RenderThread 动画、Choreographer）与 [research-2026-09-14-gles-full.md](research-2026-09-14-gles-full.md)（dispatch/屏障开销、数值确定性、零拷贝纹理、多上下文同步、Jacobi 分块）。
- [phase0-baseline-2026-09-14.md](phase0-baseline-2026-09-14.md)：阶段 0 的方法、两台设备的原始数字表、六项各自的结论与三条建议。后续每个阶段对着第 6 节的基线表复测。
- [batch1-results-2026-09-14.md](batch1-results-2026-09-14.md)：第一批逐项的保留/撤回与实测数字、1.9 的根因、对照阶段 0 基线表的复测列、未完成项。
- [batch1-regression-2026-09-14.md](batch1-regression-2026-09-14.md)：第一批的功能回归（G0 散列、单测、动画层覆盖、Surface 交接像素、保存顺序、左滑、四条关闭路径、内存与线程）。
- [batch2-results-2026-09-15.md](batch2-results-2026-09-15.md)：第二批逐项的保留/撤回与实测数字、首帧判定口径、对照阶段 0 基线表的复测列、功能回归、未测到项。
- [batch3-results-2026-09-15.md](batch3-results-2026-09-15.md)：第三批的快拖修复数字、「点新建按钮 → 首个可见粒子帧」的十段时间线、2b/2c 的实测效果与三组对比帧路径、第三项的不做理由、2b 预计算代价的归因提案。
- [batch4-results-2026-09-15.md](batch4-results-2026-09-15.md)：第四批的创建页缓存键清单与「离屏渲染创建页」的不做理由、命中/未命中两条完整时间线、弹窗 2c 的起点与提前量、三星涟漪的 atrace 解剖与刷新率结论、材料线程优先级的读回对照、G0 两条新增对照与 OPPO 的单帧差异说明。
- [batch5-results-2026-09-15.md](batch5-results-2026-09-15.md)：单像素差异的定性（自比较、回读连读两次、并发负载作为可开关的自变量）、两个会话之间全部共享资源的逐项审查表、`withBaselineGpu` 的隔离缺陷与修法、弹窗四条关闭路径与独立 Surface 交接像素两项回归的补跑结果、创建页预测素材持久化的设计与六种场景实测（第 7 节）。
- [weak-devices-2026-09-15.md](weak-devices-2026-09-15.md)：两台新设备上「消散不播 / 更容易掉帧」的分析、候选路径（已按实测标注结论）与阶段 B「消散提前预算」的设计与裁定清单。
- [weak-devices-phaseA-2026-09-15.md](weak-devices-phaseA-2026-09-15.md)：阶段 A 的诊断日志清单、修前/修后两台各一轮的「关闭方式 × 是否开播 × 路径 × 帧数/p90」表、抓图选路的修法与约束、预测性返回的结论。
- `decisions.md`、`followups.md`、`sessions.md`：按项目惯例记录。

## 本目录的工具

- `device_dismiss_paths.py` / `analyze_dismiss_paths.py`：弱设备阶段 A 的「弹窗 × 关闭方式」
  矩阵驱动与日志分析。判据全部来自应用自己的 debug 日志，**必须在跑之前另起一条
  `adb -s <序列号> logcat -v time > 文件`**（魅族主缓冲只有 256 KiB，事后 `-d` 捞不回来）。
  开跑前只读查询 `RECORD_AUDIO`，缺权限就跳过「点录音行」那一格——授权属于设备设置，
  测试不得代为改动。
- `device_particle_perf.py`：阶段 0 的真实系统输入驱动（场景、观察器控制、GPU 探针开关、产物拉取）。
- `analyze_particle_perf.py`：把观察器与探针产物汇总成 `tmp/particle-perf/phase0/summary.json`。
- `device_particle_cleanup.py`：收尾（专用记事移入回收站、档位恢复、删除诊断开关、核对无残留线程）。
- debug 专用计时钩子：`app/src/main/.../ParticlePerfProbe.kt`，默认关闭，由 `files/particle-perf-probe.json` 显式开启；`gpuTiming` 取 `a`（整段）/ `b`（分段计时查询）/ `c`（按段 `glFinish`）。
- 第二批的结构性改动没有留开关（一窗一 Surface、常驻上下文、分步状态机、栅栏节流互为前提）。
- 第一批的逐项开关：`app/src/main/.../ParticlePerfTuning.kt`，debug 包由 `files/particle-perf-tuning.json` 覆盖，缺文件时取默认（= 发布配置）。用来做同一台设备上的 A/B 复测。
- 第五批的 G0 定性模式（同在 `ParticleTrajectoryCheck.kt`）：`device_particle_perf.py` 的
  `--gpu-seed`（固定种子，同时打开 `diagnosticOnly`）、`--gpu-diagnostics`（每条路径自跑多遍、
  逐帧连读两次、定点复现并定位差异像素）、`--gpu-load`（另起一个 EGL 上下文制造与提前预算
  同形态的并发负载）、`--gpu-repeats`、`--gpu-pin-frame`（对着已知的临界帧反复跑）。
  产物由 `pull_parity()` 自动拉回 `tmp/particle-perf/phase0/<serial>/runs/<run>-0/`。
- G0 对照：`app/src/debug/.../ParticleTrajectoryCheck.kt`，由观察器配置里的 `gpuCheck` 触发；比对消散 61 帧、出现 37 帧、两会话交错、以及第三批新增的「截断预计算逐帧」（2c 的保留段必须与完整预计算逐位相同），参考路径是「原始逐次 Jacobi + 第一批 GPU 各项全关」。
- 第三批的三组对比帧：`app/src/debug/.../ParticleAppearanceFrames.kt`，由观察器配置里的 `appearanceFrames` 触发（`device_particle_perf.py --appearance-frames`），对同一张快照、同一个种子跑「现状 / 只开 2b / 2b+2c」三种输入，各存 0/50/100/150/250 ms 的整帧缩略图与右下角 3 倍放大块，并记每种输入的 `precomputeMs`。
- 第三批的逐项开关（同样在 `ParticlePerfTuning.kt`）：`gestureCreepToFinger`（左滑追赶）、`creationAppearanceFromFab`（2b）、`creationAppearanceSkipEmptyTail`（2c），默认全开。
- 第四批的逐项开关（同上）：`dialogAppearanceSkipEmptyTail`（弹窗/面板/新卡片的 2c，默认开）、
  `creationPrewarm` / `creationPrewarmIdleMs`（创建页轨迹提前预算，默认开 / 500 ms）、
  `rippleHighFrameRate`（涟漪期投高帧率票，默认开）、`atrace`（自定义 trace 分段，默认关）、
  `yieldPrecomputeDuringFeedback`（涟漪期给预计算让路，默认关，采集证明没必要）。
  门槛常量由 `creationAppearanceAliveRatio` 改名为 `appearanceAliveRatio`（JSON 旧键仍可用）。
- 第四批的 G0 新增对照（同在 `ParticleTrajectoryCheck.kt`）：`prewarmSameSnapshotPassed`
  （预测快照 == 真实快照时命中路径逐位相同）与 `prewarmFinalFrameEqual`（用 R/B 对调的假预测图
  建材，末帧散列仍等于真实页面那一帧）。
- 第五批的追加项：`ParticlePrewarmStore`（磁盘缓存，写 `filesDir/particle-prewarm/`）与
  `ParticlePrewarmRetention`（纯逻辑的淘汰规则，JVM 单测覆盖）。
  `device_particle_perf.py --trim-between` 在创建页每一轮之前退后台 + 发一次
  `RUNNING_CRITICAL` 再回来，验证内存态清掉之后磁盘缓存仍然可用。
  `summarise_prewarm.py <run 目录>…` 把「抬手 → 首帧上屏」与命中来源（内存 / 磁盘 / 无）打出来。
- `device_batch3_setup.py`：建专用测试记事（含经真实系统选择器加一张图）、切换新建/左滑档位。
  第四批起标题前缀放宽到 `PerfB<数字>-`，清理脚本同步放宽。

## 相关记录

- 既有性能测量：`../dialog-particle-dismiss/startup-latency-2026-09-12.md`、`appearance-startup-2026-09-13.md`、`ripple-transition-performance-2026-09-13.md`、`concurrent-feedback-and-dismissal-2026-09-13.md`、`repeated-appearance-and-wave-handoff-2026-09-13.md`、`presentation-continuity-2026-09-13.md`；`../thing-creation-animation/performance-validation-2026-09-13.md`、`startup-and-outline-2026-09-14.md`；`../swipe-complete-animation/reverse-and-latency-2026-09-14.md`。
- 测量工具：`../thing-creation-animation/device_performance.py`、`analyze_performance.py`，debug 版只读观察器 `app/src/debug/.../ThingAnimationPerformanceObserver.kt`。
