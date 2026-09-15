# 粒子动画性能优化

跨三个功能的性能专项：弹窗粒子出现/消失（`dialog-particle-dismiss`）、新建记事入场（`thing-creation-animation`）、左滑完成记事（`swipe-complete-animation`）共用同一套粒子管线，性能问题也是同一组结构性原因，所以放在一个目录里处理。粒子模型、材料、随机、积分与压力迭代次数不在本专项范围内。

## 状态

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
- `decisions.md`、`followups.md`、`sessions.md`：按项目惯例记录。

## 本目录的工具

- `device_particle_perf.py`：阶段 0 的真实系统输入驱动（场景、观察器控制、GPU 探针开关、产物拉取）。
- `analyze_particle_perf.py`：把观察器与探针产物汇总成 `tmp/particle-perf/phase0/summary.json`。
- `device_particle_cleanup.py`：收尾（专用记事移入回收站、档位恢复、删除诊断开关、核对无残留线程）。
- debug 专用计时钩子：`app/src/main/.../ParticlePerfProbe.kt`，默认关闭，由 `files/particle-perf-probe.json` 显式开启；`gpuTiming` 取 `a`（整段）/ `b`（分段计时查询）/ `c`（按段 `glFinish`）。
- 第二批的结构性改动没有留开关（一窗一 Surface、常驻上下文、分步状态机、栅栏节流互为前提）。
- 第一批的逐项开关：`app/src/main/.../ParticlePerfTuning.kt`，debug 包由 `files/particle-perf-tuning.json` 覆盖，缺文件时取默认（= 发布配置）。用来做同一台设备上的 A/B 复测。
- G0 对照：`app/src/debug/.../ParticleTrajectoryCheck.kt`，由观察器配置里的 `gpuCheck` 触发；比对消散 61 帧、出现 37 帧、两会话交错、以及第三批新增的「截断预计算逐帧」（2c 的保留段必须与完整预计算逐位相同），参考路径是「原始逐次 Jacobi + 第一批 GPU 各项全关」。
- 第三批的三组对比帧：`app/src/debug/.../ParticleAppearanceFrames.kt`，由观察器配置里的 `appearanceFrames` 触发（`device_particle_perf.py --appearance-frames`），对同一张快照、同一个种子跑「现状 / 只开 2b / 2b+2c」三种输入，各存 0/50/100/150/250 ms 的整帧缩略图与右下角 3 倍放大块，并记每种输入的 `precomputeMs`。
- 第三批的逐项开关（同样在 `ParticlePerfTuning.kt`）：`gestureCreepToFinger`（左滑追赶）、`creationAppearanceFromFab`（2b）、`creationAppearanceSkipEmptyTail`（2c），默认全开。
- `device_batch3_setup.py`：建专用测试记事（含经真实系统选择器加一张图）、切换新建/左滑档位。

## 相关记录

- 既有性能测量：`../dialog-particle-dismiss/startup-latency-2026-09-12.md`、`appearance-startup-2026-09-13.md`、`ripple-transition-performance-2026-09-13.md`、`concurrent-feedback-and-dismissal-2026-09-13.md`、`repeated-appearance-and-wave-handoff-2026-09-13.md`、`presentation-continuity-2026-09-13.md`；`../thing-creation-animation/performance-validation-2026-09-13.md`、`startup-and-outline-2026-09-14.md`；`../swipe-complete-animation/reverse-and-latency-2026-09-14.md`。
- 测量工具：`../thing-creation-animation/device_performance.py`、`analyze_performance.py`，debug 版只读观察器 `app/src/debug/.../ThingAnimationPerformanceObserver.kt`。
