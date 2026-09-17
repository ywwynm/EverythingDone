# 粒子动画性能优化

跨三个功能的性能专项：弹窗粒子出现/消失（`dialog-particle-dismiss`）、新建记事入场（`thing-creation-animation`）、左滑完成记事（`swipe-complete-animation`）共用同一套粒子管线，性能问题也是同一组结构性原因，所以放在一个目录里处理。粒子模型、材料、随机、积分与压力迭代次数不在本专项范围内。

## 状态

- 2026-09-17：**逐帧成本专项第三批完成并发布 `202609171237`**，见
  [per-frame-batch3-2026-09-17.md](per-frame-batch3-2026-09-17.md)。任务一全 **G0**，
  只改「这一拍的目标时刻怎么算」。**先取证再改**：新增节拍级取证（`ParticleBeatTrace`）
  一轮否掉四条假设，只剩「节拍与面板相位不对」——此前的节拍是「起点 + k × 16.667 ms」的
  自由计时，而魅族两个 vsync 实测 **16.55 ms**，每拍多 0.117 ms，几十拍后目标滑过一个 vsync，
  同一批帧的呈现时刻散在 **9.4 / 17.7 / 26.0 / 32.3 ms** 四档上（三星面板周期恰好接近 16.667，
  于是几乎不漂，是这条根因最好的旁证）。落地**节拍相位锁定**：魅族提交延迟 p90
  **11.61 → 5.65 ms**、本拍→上屏 p90 **41.63 → 33.96 ms**；华为一轮左滑合成次数
  **478 → 582**、移动期不同画面 11 格里 8 格上升。同批**删掉两项**：「栅栏挪到两拍之间轮询」
  （等待实测 0.02 ms）与「呈现时刻下限」（魅族的 SurfaceFlinger 不按 `desiredPresentTime`
  推迟缓冲，28/69 帧照样早一个周期上屏）。顺带**修正了「左滑远低于 60 fps」这个前提**：
  三格的分母含手指停住的那一段，且出拍没画的 **100% 是「相位没变」**。左滑从本批起在
  **四种复杂度的卡**上各跑一遍（矮卡与清单卡片数差 6.8 倍）。**任务二**给 E12（微片 dp）与
  C7（Jacobi 迭代数）各出一组受控对照图与数字交用户定夺，**默认值一个没改**。
  另补齐前两批欠华为 Maleoon 910 的架构侧回归（着色器零链接失败、`ssbo顶点=0`、
  双上下文读回 HIGH / LOW）。
- 2026-09-17：**逐帧成本专项第二批完成并发布 `202609171032`**，见
  [per-frame-batch2-2026-09-17.md](per-frame-batch2-2026-09-17.md)。全批 **G0**，只改调度与时序。
  落地三项：**F14** 出帧与后台分成两个同 share group 的 EGL 上下文按优先级分档
  （两台真机都真的读回 **HIGH / LOW**，同线程切换只要 10–17 µs）；**F15** 预读改成
  「提交即返回 + 栅栏轮询确认」，CPU 不再每个采样阻塞 4–7 ms，并按实测重定了三个预读档数；
  **预算不许跨过下一拍**。收益最实的是**左滑快拖**：移动期不同画面 7 → 9（魅族）/ 7 → 12（三星），
  魅族滞后 p90 16.2 → 10.3 档；弹窗侧最长呈现间隔与 p90 多格缩短（`attach-back` 消散
  max 228.8 → 87.8 ms、`color-confirm` 出现 p90 96.6 → 24.8 ms），片数最多的
  `color-confirm` 消散 0.725 → 0.842。**「首帧绕开节拍」两台都量不出收益，默认关闭**，
  但它踩出的时序教训写进了 decisions（提前出的一帧必须把要替掉的那一拍真的吃掉，
  否则被 SurfaceFlinger 丢掉的那一帧会卡住整个待确认队列）。新增场景 `swipe-wiggle`
  （按住不放来回拖）与滞后曲线，**查清「来回拖不跟手」不在预读这条链路上**
  （滞后 p90 恒为 0.00 档），剩下的是帧率问题，已记 followups。
- 2026-09-17：**逐帧成本专项第一批完成并发布 `202609170852`**，见
  [per-frame-batch1-2026-09-17.md](per-frame-batch1-2026-09-17.md)。落地两项：**A1** 把
  `material.vert` 的逐顶点数学下沉成 compute 每片每帧一次（弹窗现场消散每帧 −21.4% /
  三星 −19.7%、回放出现 −28.8% / −25.5%，左滑卡片 −11.3% / −10.3% 与 −19.8% / −20.8%，属 G1）；
  **C10** 让压力更新只跑活动区域（左滑卡片现场 −3.7% / −3.9%、预读每采样 −6.4% / −6.2%，
  弹窗侧中性，**两台逐位相同**）。C10 返工两次才转正：慢的不是间接分发，是十万个线程
  对同四个地址做原子操作。同批的 **A2 / A3 因两台都量不出收益整段删除**，
  **B7 因第 0 遍的四边形带抖动、等价性不成立未做**。顺带修掉创建页提前预算「必然未命中」
  （缓存键里的瞬时刷新率）。两台各一轮修前 + 一轮修后的九场景矩阵：最长呈现间隔整体缩短、
  消散层呈现比例普遍上去，唯一没变好的是左滑慢拖的帧数（已记 followups）。
  Adreno 829 与 Maleoon 的回归仍欠着（两台都不在线）。
- 2026-09-17：**逐帧成本专项的基线与分段归因完成（阶段 0'）**，见
  [per-frame-baseline-2026-09-17.md](per-frame-baseline-2026-09-17.md)。魅族 20 Infinity 一台。
  每帧成本的大头随场景换人：**弹窗（10.7 万片）被两遍实例化绘制主导**（现场消散 56.3%、
  回放出现 87.1%），**左滑卡片（2.4 万片）被压力主导**（现场 46.4%、预读 86.9%），而它的
  压力网格（120,096 格）比整屏弹窗（83,520 格）还大 44%。全屏清空在 Adreno 740 上不要钱、
  resolve 只值 0.24–0.33 ms，B6 因此降级；呈现链全部 Device 合成、60 Hz 投票生效，F16 不做。
  最差的一格是「弹窗 A 消散接 B 出现」（间隔最大 227.8 ms，同时 B 在跑 907 ms 预计算）。
  顺带查出创建页的提前预算在这台设备上**必然未命中**（缓存键里放了瞬时刷新率）。
- 2026-09-16：**修掉上一版带出来的两帧闪烁并发布 `202609151640`**。关闭录音弹窗时弹窗一瞬
  变直角、整屏亮度掉一下，根因是「用第二个 `HardwareRenderer` 画活着的视图树」会改掉真实
  `SurfaceView` 的裁剪（Android 15 起由 RenderThread 驱动）。修法：有可见 Surface 的弹窗，
  离屏那一步改用软件绘制；消散一律先离屏（抓图 1.07 s → **59 ms**）；交接图按圆角裁一刀。
  魅族逐帧录屏四次全无闪烁，阶段 A 矩阵 8/8；**OPPO OPD2515 首次跑录音弹窗关闭 4/4 全开播**
  （62 帧、p90 16.7 ms），消散这一项第一次有第二台设备的回归。详见 decisions / sessions 的
  2026-09-16 条。
- 2026-09-15：**录音弹窗那一半补齐并发布 `202609151529`**（与 FableSol 的 HDR 表面修复
  同一版，日志在 `../audio-visualization-fable-sol/debug-updates/update-20260915233000.md`）。
  阶段 A 只修好了「没有可见独立 Surface」那一半；**有**可见 Surface 的弹窗仍然只有窗口
  `PixelCopy` 一条路，录音弹窗（准备态、点外部关闭）在魅族上正是卡在它的
  `ERROR_SOURCE_NO_DATA`，修前 2/2 不播。现在补上另一半回退——窗口复制失败就改走
  「离屏重录 + 逐个 Surface `PixelCopy` 再合成」，修后 **4/4 全开播**。同时撤销阶段 A 的
  一条判断：「离屏重录画不出 SurfaceView 的透明洞」只对软件 `Canvas` 成立。抓图选路单测
  6 → 11 项；阶段 A 那张矩阵重跑 8/8。华为本轮不在线，第二台设备的回归没做。
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

- [per-frame-plan-2026-09-17.md](per-frame-plan-2026-09-17.md)：逐帧成本专项（模拟、计算、绘制、呈现）的口径、现状、十个优化方向与阶段划分。
- [research-2026-09-17-per-frame.md](research-2026-09-17-per-frame.md)：上一条方案第 3 节的外部资料调研，十个主题各附来源与「建议 / 收益 / 风险 / 验证」，文末给按预期收益排序的总表与对方案的逐条裁定（B4、PCG、framebuffer fetch 合一、粒子排序四项被否定；B6 与 F14 需修正；A1、A3 的收益被低估）。
- [per-frame-batch3-2026-09-17.md](per-frame-batch3-2026-09-17.md)：第三批的节拍级取证与逐条
  假设裁定、相位锁定的做法与两台数字、两个被证伪并删除的项、四种复杂度测试卡的建法与片数、
  「左滑远低于 60 fps」这个前提的修正、E12 / C7 两组受控对照的图与数字，以及前两批欠华为
  Maleoon 910 的架构侧回归。
- [per-frame-batch2-2026-09-17.md](per-frame-batch2-2026-09-17.md)：第二批（F14 双上下文、F15 异步预读、
  预算让拍）的做法、两台读回的优先级档位与切换开销、预读档数的重定依据、「首帧绕开节拍」的
  缺陷根因与不落地依据、两台各一张矩阵修前修后表，以及左滑三格（慢拖 / 快拖 / 来回拖）的滞后曲线。
- [per-frame-batch1-2026-09-17.md](per-frame-batch1-2026-09-17.md)：第一批逐项的做法、两台设备的
  离屏 A/B 数字、C10 两次返工的归因、A2/A3 的删除依据与 B7 的等价性分析、G0/G1 对照与受控
  并排放大图、两台各一张真机矩阵修前修后表、`hz` 键缺陷的核实与修法。
- [per-frame-baseline-2026-09-17.md](per-frame-baseline-2026-09-17.md)：阶段 0'，魅族 20 Infinity 上的基线矩阵、每帧分段归因（消融 + 分段 `glFinish`，含两条方法边界）、呈现链结论、修前录屏与对照帧路径，以及按实测份额给出的实施批次排序。
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
- `device_per_frame.py` / `analyze_per_frame.py`：阶段 0'（逐帧成本专项）的基线矩阵驱动与汇总。
  第一批新增 `--per-frame-bench`（逐项 A/B，产出 `per-frame-bench.json`）与 `--tuning`
  （整轮的开关覆盖，收尾自动恢复；`create-miss` 那一格会把它并进去而不是擦掉）。
- `analyze_per_frame_ab.py`：逐项 A/B 的汇总（按**配对**取比值，不跨条件比绝对值）。
- `compare_matrix.py`：两轮矩阵（修前 / 修后）的同名场景对照表。
- `compose_before_after.py`：并排图。`--pairs` 是受控 A/B 那一种（同材料同种子只切开关，
  4 倍最近邻放大，裁剪中心是本帧差异最大的像素）；`--before/--after` 是两轮录屏那一种，
  配合 `extract_baseline_frames.py --crops` 沿用修前的裁剪框。
  场景键 `swipe-slow` / `swipe-fast` / **`swipe-wiggle`**（第二批新增：按住不放来回拖，
  剖面在脚本的 `WIGGLE` 常量里，注释写明了三条硬约束）/ `attach-back` / `attach-outside` /
  `color-confirm` / `attach-audio` / `audio-close` / `create-hit` / `create-miss`；`--record` 只录屏不采样、
  `--probe` 开分段 `glFinish`、`--segment-bench` 开离屏消融。同样**必须**先另起一条 logcat 流。
- `analyze_beats.py`：第三批的节拍级取证汇总（`result.json` 里的 `beats` 与 `clock`）。
  回答四个分支：节拍跳没跳、出拍了却没画（原因分布）、画了但提交晚了、提交了却没按目标上屏。
  用法 `python analyze_beats.py <序列号> <run 前缀> [场景键…]`。
- `device_batch3_setup.py` 的 `cards` / `make-list`：建一组不同复杂度的测试卡
  （高文本卡、清单卡、带图卡）。**华为上 `input text` 的拉丁字母会被中文输入法改写**，
  那台一律用纯数字标题；清单要「先写好行再点启用清单」，勾选框是 `iv_check_list_state`。
- `device_per_frame.py --swipe-titles 类型=标题,…`：左滑三格在每张卡上各跑一遍；
  `--option-frames`：第三批任务二的 E12 / C7 受控对照（离屏，只出图与数字）。
- `device_composition.py`：`dumpsys SurfaceFlinger` 采集（`--mode swipe|dialog`），核对粒子层的
  合成方式、像素格式与刷新率投票。输出很长，整份落到设备上再 `adb pull`。
- `extract_baseline_frames.py`：从录屏里按「相邻帧差分」定位动画窗口，取 10% / 50% / 90% 三个
  进度点的整帧与 4 倍放大裁剪，作为修前修后并排对照的基线。
- `device_particle_perf.py`：阶段 0 的真实系统输入驱动（场景、观察器控制、GPU 探针开关、产物拉取）。
- `analyze_particle_perf.py`：把观察器与探针产物汇总成 `tmp/particle-perf/phase0/summary.json`。
- `device_particle_cleanup.py`：收尾（专用记事移入回收站、档位恢复、删除诊断开关、核对无残留线程）。
- debug 专用计时钩子：`app/src/main/.../ParticlePerfProbe.kt`，默认关闭，由 `files/particle-perf-probe.json` 显式开启；`gpuTiming` 取 `a`（整段）/ `b`（分段计时查询）/ `c`（按段 `glFinish`）。阶段 0' 起分段细到 integrate / splat / blur / tiles / jacobi / project / history / restore / clear / pass0 / pass1 / resolve，并附每帧的 dispatch 计数（记录里的 `count:<段名>` 行，计时关掉也有）。
- 阶段 0' 的消融与离屏基准：`app/src/main/.../ParticleAblation.kt`（**线程局部**的跳段掩码，release 恒为 0）与 `app/src/debug/.../ParticleSegmentBench.kt`（成对 A/B、先热身、整段末尾只 `glFinish` 一次），由观察器配置 `segmentBench` 触发（`device_per_frame.py --segment-bench`）。**消融只对不回灌模拟的段成立**——跳掉压力里的任何一级都会改变活动 tile 列表，详见 per-frame-baseline 第 3.1 节。
- 第二批的结构性改动没有留开关（一窗一 Surface、常驻上下文、分步状态机、栅栏节流互为前提）。
- 第一批的逐项开关：`app/src/main/.../ParticlePerfTuning.kt`，debug 包由 `files/particle-perf-tuning.json` 覆盖，缺文件时取默认（= 发布配置）。用来做同一台设备上的 A/B 复测。
- 第二批的逐项开关（同一个文件）：`backgroundContext`（双上下文，默认开）、`asyncPrecompute`
  （预读提交即返回，默认开）、`reverseInFlight`（在途采样上限，0 = 两批）、`yieldWorkBeforeBeat`
  （预算不跨过下一拍，默认开）、`immediateFirstFrame`（首帧绕开节拍，**默认关**，两台都量不出收益）。
  观察器产物新增 `glContext`（两个上下文实际读回的优先级档位、切换次数与累计墙钟）与每个手势层的
  `gesture` 滞后曲线；`analyze_per_frame.py` 把后者汇总成 `gestureLag`（p50/p90/max、被卡住的帧
  占比、每次反向的峰值与恢复帧数），`compare_matrix.py` 为左滑场景单出一张表。
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
