# 会话记录

- 2026-10-05 晚：用户提出移植任务。梳理了现有 `views/particledismiss/` 的结构、模型写死的位置、全部调用点与设置项（README），列出待裁定问题（decisions.md）。尚未改代码。
- 2026-10-05 晚（阶段 A）：实现飘散样式并接入设置页。
  - 新增 `ParticleLayerSession`（`ParticleSession` 与 `ParticleDriftSession` 共用的接口）、`ParticleStyle`、`ParticleDriftModel`、
    `ParticleDriftRenderer`、`ParticleDriftSession`、`ParticleDriftInputs`，着色器 `shared/particle-dismiss/drift-*.{glsl,comp,vert,frag}`。
    与 plan.md 第 1 节的出入：颗粒仍用 compute 按索引闭式算（GLES 3.1，与流体样式同一门槛），顶点阶段经实例化属性读同一缓冲，
    不在顶点着色器里算噪声（Mali / 麒麟顶点阶段读不了 SSBO 的限制同样碰不到）。
  - `ParticleDismissSpec.style` 缺省规则：带汇聚终点、交接、材料共享、延后前景、图层透明度或动力学参数的规格（只有碰一碰会带）固定流体，其余跟设置。
  - `ParticleWindowSurface` 只在有流体会话时才建微片共享资源；`ParticleCreationPrewarm` 在飘散样式下不预算；
    飘散一轮收尾时不把采样交给自动调档（`ParticleFrameStats.roundSkipped`）。
  - 设置页：「粒子样式：流体 / 飘散」+「粒子数量」（0.5×～1.5×，缺省 1.0×），选飘散时隐藏粒子大小、流动精细度、颜色精度、性能自动调节四项；13 种语言文案。
  - R5CW20BLNKL 首轮：`drift-update.comp` 用了 GLSL ES 保留字 `active` 作变量名，编译失败、整段降级成淡出；改名后六个着色器都过了
    glslang（`E:\AndroidSDK\emulator\lib64\vulkan\glslangValidator.exe`，先把 `#include` 展开）。第二轮：示例对话框消散 1.0 秒、出现约 0.58 秒，
    首帧延迟 90–144 ms，37/37 帧上屏；交接处相邻帧差均 < 0.3（无跳变）；示例卡片左滑与回位正常。驱动与分析脚本在 `device/`。
- 2026-10-05 晚（阶段 B）：探针逐个入口验收（全是虚构内容）：真实 AlertDialog / Chooser 的出现、多选移除（含确认弹窗出现与消散）、
  左滑 / 右滑完成与回位、调整外观面板的消散与出现，全部走飘散，无一次回退。`ParticleSwipeShadowProbeActivity` 右滑时回位前整卡静止
  显示约 0.25 秒，流体样式同样如此（探针自己对右滑传 0 位移），设置页的右滑没有这个现象——不是本次引入。
- 2026-10-05 晚（阶段 C）：参考样式库、背景取样、调试键；单测 `ParticleDriftModelTest` 11 条 + 着色器编译测试（飘散着色器按运行时同样的
  方式拼公共段）。设置页示例对话框 9 轮 18 次动画：约一半选到参考样式（thanos、ironman、参考 2/3/6、kobe），背景 L* = 90 → src-over。
- 2026-10-05 晚（阶段 C 续）：两处修正。一、选中参考样式时风向转到本次方向（风力仍取参考的），与第 1 问的记录一致；
  二、时间轴从「第一处开始变淡」的时刻起算（`ParticleDriftModel.startSeconds`）：源码从 0 开始，最低处也要 0.1–0.2 秒（设备时刻）才开始变淡，
  压到 1 秒后开头有一小段卡片纹丝不动，左滑时手指移动的前 5%–10% 也没有反应。单测增至 12 条。
- 2026-10-05 晚（阶段 B 真实首页）：`device/stage_b_home.py` 建了两条纯数字标题的测试记事（905119191、905119192），依次验证 FAB 新建转场
  （全屏快照 5 万颗）、新卡片入场、左滑完成（从按下处开始化开）、多选「删除所选记事」（确认弹窗出现与消散 + 移除），再到「已完成」里删掉
  第一条；两条都在回收站。抽屉里的「已完成」「回收站」是只有图标的分段控件，脚本按结构定位。11 次飘散动画，无回退，旋转设置未变。
  这一轮发现弹窗暗层被读进背景取样（见 decisions.md），已修。
- 2026-10-05 晚（收尾）：
  - 随机生成的动画给湍流取样加了每次不同的偏移（参考样式保持 0）：源码的湍流场只取决于颗粒索引与位置，同一尺寸的卡片每次飘出去的形状都一样。
  - 应用启动时的模型预热按样式区分：飘散只在 GL 线程上先编着色器（首个动画 207 → 189 ms），不再建两次流体材料（之前设置页还会显示流体的 28,324 颗）。
  - 仅调试包的出帧统计（「飘散出帧 上屏=… 应有=…」）：设置页消散 61/63、61/62，出现 30–36 帧对应 0.5–0.6 秒；探针多选移除 4.3 万颗 61/61。
  - 最终版按最接近各参考风向的方向（左上 / 正上 / 右上）给十二段参考各录一轮，出总表；粒子相关 33 个测试类 306 条单测全部通过。
  - 真机收尾：`particle_tuning` 偏好恢复成测试前的空表；两条测试记事（905119191、905119192）在回收站；旋转设置未变。
  交付图：`tmp/harmony-particle-android-port/report/`（由 `device/make_report_images.py` 生成）。
- 2026-10-05 晚：按用户要求发布阿里云调试版 202610051308（APK SHA-256 `6976dddc…4b73`，27,729,440 字节），发布日志
  `debug-updates/update-20261005210757.md`，服务器上的 `latest.json` 读回与本地一致、`releaseNotes` 715 字。代码仍未提交。
- 2026-10-05 晚：按用户要求让左上方向更常出现旧钢铁侠（见 preferences.md / decisions.md 同日条目）。单测增至 310 条（粒子相关）。
  R5CW20BLNKL 上用多选移除探针的确认弹窗按返回键退出 16 次：旧钢铁侠一族 9 次（原样 5、变体 4），其余参考 3 次、随机 4 次。
  钉住变体录了三轮（起点 +2 / -1 / 不变），与原样排成 `tmp/harmony-particle-android-port/ironman-family.png`。未发布、未提交。
- 2026-10-05 晚：按用户要求发布阿里云调试版 202610051408（旧钢铁侠一族；APK SHA-256 `b7ef6407…2646`），发布日志
  `debug-updates/update-20261005220842.md`，服务器读回一致、`releaseNotes` 420 字。代码仍未提交。
- 2026-10-05 夜：用户问旧钢铁侠的卷曲、流动是否完整复刻，并要求有颜色的内容更突出。加了内容强调与参考样式的湍流换算（decisions.md 同日条目），
  单测 314 条。
- 2026-10-05 夜：用户要求「android上的效果要跟桌面模拟器的效果一模一样」。
  - 逐行对照两边的着色器与宿主流程，Android 改了粒子数、扩展画布、设备帧时间与 deltaT、出生判据、点纹理朝向、片元中心换算、内容分析反预乘
    （decisions.md）；桌面补上 Android 口径的可选项（缺省与原来逐位相同）。
  - 逐帧对照（`parity/drift_parity.py`）：R5CW20BLNKL 上的调试 Activity `DriftParityRenderActivity` 用正式的模型、渲染器与着色器离屏出帧，
    桌面用同一份预乘快照字节与同一组输入，在设备帧 6～162（0.05～1.35 秒）共 27 个时刻比较。五组（旧钢铁侠参考输入、参考 5 的 src-over、
    浅色与深色弹窗的随机格点 + 内容强调、旧钢铁侠样式套在弹窗上并转到 150°）每帧 p99.9 ≤ 2/255、最大 5～13/255，差 > 8 的像素至多每帧一两个。
    总览：`tmp/harmony-particle-android-port/parity/p2/overview.png`。
  - 输入规则逐位核对（`parity/check_inputs.py`）：7 种尺寸 × 14 个方向 × 6 个种子共 588 组，设备上的 `ParticleDriftModel.random` 与 Python 移植
    的样式、格点值、风、粒子数、湍流偏移与换算、起止时刻全部逐位相同；内容分析与 Skia 的取整预乘也逐字节相同。期间查出 Python 移植的一处顺序问题：
    `a[i] = v` 在 Kotlin 里先算下标、在 Python 里先算右边，两次随机数的先后颠倒，旧钢铁侠变体的格点值因此不同（已改）。
  - 桌面模拟器加「Android 移植口径」并发布到 8666 固定入口（重启了 8653 后台）；页面上随机播放与点击都能播。
  - 真机复测正式路径：`device/stage_b_probes.py --run b5`（多选移除、左右滑、外观面板）与 `device/style_rounds.py --run s5`（设置页示例弹窗 3 轮），
    11 + 8 次飘散首帧上屏、无回退；旋转设置未变。`stage_b_probes` 的弹窗一项用的出现探针在出现结束后会自己关闭，消散那一步一向找不到按钮（b1 起就如此），
    弹窗消散改由 `style_rounds.py` 覆盖。粒子相关 33 个测试类 314 条单测全部通过（报告文件为本次运行写出）。
- 2026-10-05 夜：按用户要求发布阿里云调试版 202610051555（逐帧一致与内容强调；APK SHA-256 `c3ea4953…db98`，27,729,616 字节），
  发布日志 `debug-updates/update-20261005235515.md`，服务器上的 `latest.json` 读回与本地一致、`releaseNotes` 598 字。代码仍未提交。
- 2026-10-06：按用户要求提交，分两次：`3a4dc2c9` 桌面复刻（GPT-6 从鸿蒙 7 模拟器镜像提取的着色器与宿主代码、核心公式验证、
  逐项等价实现与十二段输入估计、实时预览，以及 9 月 26 日至 10 月 5 日的候选与分析脚本）；`35fd5464` Android 飘散移植（含逐帧对照、
  模拟器的 Android 移植口径、三次发布的日志）。图片、视频、缓存、差异截图、两份约 2 MB 的拟合权重与探针参考帧都没有提交
  （后两者已写进忽略规则）。`.claude/rules/adb.md` 里首页滚动性能那一节、空间照片与首页滚动性能的文档按原样留在工作区。
- 2026-10-06：用户要求「朝上的参考及其变体更常出现」「粒子数量 0.2–1.6 倍、加粒子大小」「触点远近要影响动画」「性能至少与流体持平」，
  做完发布阿里云（决定与原话见 decisions.md、preferences.md 同日条目）。
  - 朝上一组、全部参考可出变体、数量与大小两档设置、触点远近（风力倍数与朝终点偏转）：Kotlin 与 Python 移植两边同改；`parity/check_inputs.py --run i9`
    1404 组输入（加了全屏 1440×3088 与左滑 1300×300 两种尺寸）逐位相同；七组离屏出帧对照 p99.9 ≤ 2/255（p2–p4）。
  - 真机触点远近：`device/touch_distance_demo.py --run t2` 钉住参考 7，在弹窗正上方近 / 中 / 远各点一次，触点距离 0.22 / 0.94 / 1.66、
    风力 13073 / 19680 / 26272，0.9 秒时颗粒最高处离弹窗上沿 434 / 614 / 816 px；对照表 `tmp/harmony-particle-android-port/t2/touch-distance-zoom.png`。
  - 朝上的抽样：6 次点在弹窗正上方关闭，5 次是朝上一组（参考 4 原样 2、参考 1 变体 2、参考 7 变体 1），1 次随机。
  - 性能：两种会话加了同口径的出帧统计（`ParticleFramePacing`），脚本 `device/perf_compare.py` + `device/perf_table.py`。修了三处：噪声值域只算角点
    （主线程建输入 23–67 → 0.2–2.7 ms）、确认上屏不再被丢帧压到 250 ms 超时（弹窗出现交接提前约 180 ms，`device/appear_tail_check.py` 的录屏里
    末帧原来停了约 230 ms）、准备阶段重排（先上传、分析挪到后台线程）；预热加了样式表、输入与内容分析。最终一轮（流体 q10、飘散 q14，
    首帧为热态中位，单位 ms）：

    | 场景 | 流体首帧 | 飘散首帧 | 流体上屏 / 应有 | 飘散上屏 / 应有 |
    |---|---|---|---|---|
    | 弹窗出现 | 370 | 85 | 198/197 | 209/216 |
    | 点弹窗外消散 | 113 | 97 | 368/368 | 366/358 |
    | 左滑跟手 | 205 | 127 | 192/228 | 216/250 |
    | 卡片回位 | 375 | 91 | 132/132 | 141/141 |
    | 多选移除：确认弹窗出现 | 465 | 95 | 97/94 | 105/111 |
    | 多选移除：卡片 | 192 | 81 | 120/180 | 183/181 |
    | 多选移除：确认弹窗消散 | 419 | 427 | 238/306 | 193/193 |
    | 新建页 | 165 | 209 | 98/124 | 137/137 |

    左滑跟手的「应有」按 60 Hz 节拍算，手指停顿时本来就不出帧，两种样式比例相同（84–86%）。只有新建页起播仍比流体慢，原因与后续做法见 followups.md。
  - 粒子相关 33 个测试类 324 条单测全部通过（报告文件为本次运行写出）。每轮真机测试后 `particle_tuning.xml` 恢复为原文（`<map>\n</map>`），
    权限改回 660（`cat > tmp && mv` 写回会变成 666，`perf_compare.write_prefs` 已补 chmod）；旋转设置前后都是 1 / 0，未变。
  - 桌面模拟器：重启 8653 后台并重新发布 8666 固定入口（新规则），内置浏览器里随机播放与点击都能播。
  - 发布阿里云调试版 202610051901（APK SHA-256 `b8087d95…fa40`，27,733,220 字节），发布日志 `debug-updates/update-20261006030135.md`；
    服务器上的 `latest.json` 读回与本地一致、`releaseNotes` 979 字，服务器上的 APK 大小一致。代码未提交。
- 2026-10-06（发布之后）：用户问「飘散是否也可以提前计算」。飘散没有轨迹要预算；试了预先分配 GPU 资源（复用快照纹理、工作面、颗粒缓冲，
  `perf_compare.py --run q15`，新建页 6 轮），GL 侧中位仍约 78 ms，无收益，已撤回。会话日志加了「提交到上屏」一项（第一帧 39–51 ms）。
- 2026-10-06（上午）：用户「做吧，做完在手机上测试后发布阿里云」——新建页零拷贝快照（决定见 decisions.md 同日条目）。
  - 新增 `ParticleDriftHardwareImport`、`ParticleHardwareTexture` + `app/src/main/cpp/particle_hardware_texture.cpp`（CMake 加链 `android`）、
    `shared/particle-dismiss/drift-gather.comp`；`ParticleDriftContent` 拆成 `samplePositions` / `samplesFrom` / `analyseSamples`；
    `ParticleHardwareSnapshot.captureKeepingHardware`；`ParticleMicroflakePreparation.startLazily`；调试键 `debug_drift_verify_zero_copy`。
  - 单测：新增 `ParticleDriftContentSamplesTest`（拆分前后逐位相同、取样位置），粒子相关 34 个测试类 326 条全部通过（报告文件为本次运行写出）。
  - 真机：`device/zero_copy_check.py --run z2` 逐字节核对三轮全部 0 差异；`perf_compare.py --run q16`（新建页 6 轮）首帧中位 152 ms；
    `q17` 飘散全场景回归（弹窗 86 / 99、左滑 110、回位 92、多选移除卡片 79、新建页 156 ms，帧数完整，4 次新建页都走零拷贝、无回退）；
    `parity/check_inputs.py --run i10` 1404 组输入 0 条不一致、内容强调逐位相同；`device/creation_record.py --run c1` 录屏两轮，联系表在
    `tmp/harmony-particle-android-port/c1/creation-*-appear-sheet.png`。
  - 流体抽查（q18、q19）两次中止在新建页：点新建那一下偶尔被「首页截图完成时没有窗口焦点」挡掉（`ThingCreationTransition` 会静默放弃），
    驱动脚本没察觉、接着按返回把应用退到了桌面。装回上一版 APK（202610051901）与新版各跑 3 轮流体新建页都全部成功，确认不是回归；
    `perf_compare.creation_round` 与 `creation_record.py` 已改成点新建后等 `DetailActivity` 到前台、返回按前台 Activity 判断。
    改过之后流体全场景抽查（q22）正常。每轮之后 `particle_tuning.xml` 恢复为原文、权限 660，旋转设置未变。
  - 发布阿里云调试版 202610060158（APK SHA-256 `6275b653…8ec7`，27,733,307 字节），发布日志 `debug-updates/update-20261006095743.md`；
    服务器上的 `latest.json` 读回与本地一致、`releaseNotes` 665 字，服务器上的 APK 大小一致。代码未提交。
- 2026-10-06（上午，续）：用户「可以继续优化下性能，不只是从交互到动画开始播放的延迟，还有本身的渲染性能」（决定见 decisions.md 同日条目）。
  - GPU 基准 `device/gpu_bench.py --run g1`：每帧 0.87–1.53 ms，渲染开销不是瓶颈。
  - 飘散 120 Hz（`ParticleClock.setHighRate`、`ParticleLayerSession.prefersHighFrameRate`、`ParticleWindowSurface.voteFrameRate`）；粒子 Surface
    空闲不投、流体投 60、只有飘散投 120（之前常驻 60 Hz 把整个应用压在 60 fps）。左滑卡片 / 列表的 HIGH 投票经对照无效，已删除。
  - 测试工具：`ParticleGestureInput` 加异步 240 Hz 模式（环境变量 `PARTICLE_INPUT_ASYNC=1`，jar 重打，旧的留作 `input-sync-only.jar`）；
    `perf_compare.py --async-input`；`cadence_record.py` 录屏看屏幕上的实际帧间隔。
  - 真机（R5CW20BLNKL）：飘散全场景 q30、流体抽查 q29（与改动前一致）；录屏 r1 / r2；源码契约测试 `ParticlePerFrameBatchTwoTest` 改为断言
    「吃掉当前节拍的一拍」（`ParticleClock.intervalNanos`）；粒子相关 34 个测试类 326 条全部通过（报告文件为本次运行写出）。每轮之后偏好恢复原文、
    权限 660，旋转设置未变。未发布、未提交。
- 2026-10-06（上午，续 2）：左滑补帧（决定见 decisions.md 同日「左滑补帧」）。调试包「飘散跟手」日志加了位置更新与读取的相位取证；
  `ParticlePerfTuning.lateGestureFrames`、`ParticleGl.lateFrames` / `gestureMoved`、`ParticleWindowSurface.compositedTarget` / `wantsLateFrame`、
  `ParticleLayerSession.wantsLateFrame`、`ParticleSwipeAnimator.Session.track` 唤醒。新增 `ParticleDriftFrameRateTest`（高帧率分频、刷新率投票、
  补帧的结构约束，7 条）；粒子相关 35 个测试类 333 条全部通过（报告文件为本次运行写出）。真机 q32（取证）、q33（补帧后左滑三轮）、
  q34（飘散 + 流体全场景）。每轮之后偏好恢复原文、权限 660，旋转设置未变。未发布、未提交。
- 2026-10-06：按用户要求跟进 Claude 的性能优化进度，只检查会话、工作区差异和已有测试产物。确认新建页零拷贝已发布，120 Hz 调度与左滑补帧已实现、
  35 个测试类 333 条通过、`q34` 全场景回归完成；但 `s2` 左滑录屏的内容变化频率仍待核对，Claude 在北京时间 11:10 的认证失败前尚未完成这项分析。
  核对结果、改动范围、分场景首帧数据与未完成事项见 [进度核对](progress-review-2026-10-06.md)。仅更新分析文档，未改应用代码、运行 adb、构建或发布。
## 2026-10-06：接续优化并发布 202610060447

- 用户要求继续 Claude 的工作，重点优化从交互开始的起播延迟和渲染耗时，完成后发布阿里云、详细写日志。先保留接手时 APK 与源码建立对照，再分别实施、验证。
- 新增 RGBA 权重精确缓存与整字取样、相同模拟帧工作面复用、飘散新建首页截图保留硬件位图；同步发布此前尚未上线的高帧率调度、空闲刷新率投票修正及左滑补帧。
- 三星完整新建点击到首帧热运行中位 237.3 → 187.9 ms；按下到首帧 274.4 → 213.3 ms；首页截图 42.7 → 9.4 ms。弹窗外点击到首帧约 55.9 → 47.2 ms，重复模拟状态 GPU 完整开销下降 19%–32%。
- 核实左滑历史首帧日志不含按下、识别和截图；完整按下到交接约 100 ms，未测到稳定下降。多选确认弹窗自身约 398 ms 的既有等待未改。
- 35 类 335 条粒子单测通过；5 类输入 40 帧逐字节无差异；双样式全场景回归及 2 轮新建录屏完成，偏好、诊断文件和旋转设置已核对。详见 [专项记录](performance-continuation-2026-10-06.md)。
- 调试版 `202610060447` 已发布阿里云，远端元数据、完整 2194 字日志、APK 大小及 SHA-256 均校验一致。发布日志见 [本次更新](debug-updates/update-20261006124424.md)。未创建 Git 提交。
## 2026-10-06：整理 Android 后续改动提交

- 按用户要求，将接续性能优化与 Claude Opus 5.5 此前完成的 Android 改动合并为一次提交：朝上参考及变体的抽取、粒子数量范围与大小设置、触点远近响应、零拷贝快照、高帧率调度、左滑补帧和重复模拟帧复用；包含对应测试、桌面对照与发布记录。
- 提交说明使用中英文，英文在前；共同作者按 Claude Opus 5.5、GPT-6 的顺序排列。正在进行的桌面视觉外观实验及其它功能改动保留在工作区，不纳入本次提交。
