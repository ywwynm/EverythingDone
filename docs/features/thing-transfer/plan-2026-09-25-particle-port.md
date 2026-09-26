# 粒子动画第三版移植到 Android：计划（2026-09-25）

用户在模拟器里认可第三版（设计见 [design-2026-09-25-particle-motion.md](design-2026-09-25-particle-motion.md)），
同意握手协议加「每张卡片的高宽比与是否通栏」，要求移植到 Android。本文件是移植的做法与步骤，
进展与结论记在 [sessions.md](sessions.md)。

## 总体做法

模拟器与 Android 共用 `shared/particle-dismiss` 的着色器原文与 `shared/tap-transfer/particles.properties`。
移植的原则是**同一份着色器、同一套 CPU 算法、逐项对照**：

- CPU 端（材料整形）逐行照 `sim_model.py` 写 Kotlin，并用模拟器生成的对照数据做跨语言测试；
- GPU 端直接用 `step.comp` / `material.vert` / `material.frag` 里已经在模拟器验证过的 `SINK_FIELD`、
  `SINK_TARGETS`、`LAYER_ALPHA` 分支（`HAND_UNTIL` 在模拟器第七轮已换成整层透明度 `LAYER_ALPHA`）。

**绘制路径**：Android 其余动画默认走绘制记录（`DRAW_RECORDS`，逐片外观量在 `draw-record.comp` 里算好），
这条路径里没有任何终点相关的处理。碰一碰的会话（带 `SINK_FIELD`）改走 `material.vert` 的默认实例化路径——
模拟器用的就是这条，上面三个分支都已就位，着色器不用另写。代价是逐帧的顶点开销比绘制记录高，要在真机上量；
不够时再把这几段搬进 `draw-record.comp`。其余动画一概不变。

## 步骤

### 一、CPU 端的终点模型（`ParticleSink`）

- `ParticleSink.Field`：第三版的整形参数（抵达时间窗、整团滑行、天线处的散开与隐去、边框带、卷流、涡旋核、
  途中消散的寿命、按卡片分组、光尘接手、来源按远近分配的扰动）；`Geometry` 带上对应的 uniform 值。
- 移植 `field_targets`、`frame_hit`、`assign_sources`（只在看得见的颗粒里排）、`group_of` / `card_groups`、
  `shape_for_field`，随机数与比较顺序与着色器一致。
- 跨语言测试：`make_sink_fixture.py` 增加第三版的对照数据（终点、是否流向边框、是否被吸入、改写后的出生与
  寿命、重排后的随机向量），`ParticleSinkCrossLanguageTest` 逐项核对。

### 二、渲染器与会话

- 渲染器：终点模式分旧版与第三版；第三版的积分着色器打开 `SINK_FIELD`（光尘接手时再加 `SINK_TARGETS`），
  绘制走默认路径并打开 `SINK_FIELD`（按需 `SINK_TARGETS`、`LAYER_ALPHA`）；设置全部新 uniform；逐片终点在
  积分时绑到存储缓冲 2，逐片终点与接手颜色作为实例化顶点属性 6、7。
- 建材：可以另给一张「算运动用」的位图（代表色圆角矩形），材料按它算，前景贴图仍是真实快照。
- 会话：新增「按时间轴取帧」的方式——整段历史先算完、之前不画；之后每帧按外部给的位置（可带小数）取帧，
  不自己结束，由宿主撤。进度期间的代理那层与拼卡那层都用它。

### 三、碰一碰的参数

`TapParticleParams` 读新键并逐项校验（与模拟器 `Params.problems()` 一一对应）。

### 四、握手

`TapHandshake` 在颜色之后追加可选的一段：每张卡片的高宽比（u16，千分之一）与是否通栏（u8）。旧版发送方没有
这一段，接收方读到末尾就当作没有，协议版本不变。`TapIncomingOffer` 带上这两项，模拟发送方同样给出。

### 五、发送

- 流出改用第三版（整团滑行，末段不旋转）；
- 天线处的扩散圈从等待被碰一直播到关闭，流出时随进度收缩，重新抬起时展开；删掉天线处的光点；
- 「已发送」停留之后卡片按打开时的那段抬起重新出现，不再从天线处流回。

### 六、接收

- 舞台：天线处的波纹只在碰上之后显示，流入时收缩淡去；屏幕边框粒子换成光尘带（向内扩散、随进度加深、流入时
  一部分被接手、其余外退淡去）与带底下的柔光。光尘带约两万片，画法（CPU 还是 GPU）在这一步按开销定。
- 预计落点：碰上时按插入之前的列表（各项位置、哪几条置顶）与握手信息，照 `sim_list.py` 的规则排出来。
- 进度期间的代理那层：预计落点上的代表色圆角矩形，按时间轴取帧（`reverse_tau`：传输期间随进度推进，
  到齐之后逼近上限，流入开始后平滑加速到 1）。
- 拼卡那层：列表腾出位置之后按真实位置建（运动按代表色卡片算、颜色取真实快照），就绪时开始流入并与代理那层
  交叉淡化；光尘就近接手流向四边的颗粒（`SINK_TARGETS`）。

### 七、会话记录

`TapSessionRecorder` 记下握手里的通栏与高宽比、置顶的记事，真机新录的会话能直接在模拟器里重放。

## 验证

- 每一步都编译 `:app:assembleDebug`，跑相关单元测试（跨语言对照、着色器编译、参数、握手编解码、列表布局）。
- 真机上的画面、帧耗时与准备时间需要用户同意后再上设备（`CLAUDE.md`：未经要求不连设备），或发 debug 包给
  用户自己看。

## 实施结果（2026-09-25）

七步全部完成，`:app:assembleDebug` 与相关单测通过，没有上真机。与计划不同或计划里没写到的几处：

- 光尘带（约两万片）起初放在 CPU 后台线程，真机上每帧 15–43 ms，改成 GPU 上画（`TapDustLayer`，柔光与波纹一起）；
- 时间轴在慢传输里软着陆（低于上限的 80% 时与模拟器相同）；
- 光尘接手的流入时刻按进度期间那一层实测的准备时长预计，光尘隐去按倒放位置判定；
- 预计落点按设备实际的列数排（模拟器只有两列）；
- 旧的边框粒子、送端光点连同只服务于它们的参数读取、测试一并删除；
- 2026-09-26：拼卡那一层改成插入之后第一次布局就提前建（占位快照），截出真实快照后换贴图；两层共用一个种子。

代码位置：`taptransfer/ui/receive/`（`TapReceiveTimeline`、`TapListPrediction`、`TapDustHandoff`、控制器与舞台）、
`taptransfer/ui/particles/`（`TapDustBand`、`TapDustLayer`）、`taptransfer/ui/flight/TapParticleFlight.ReceiveLayer`。
真机上要看的几项见 [followups.md](followups.md)「第三版动画在真机上看一遍」。

