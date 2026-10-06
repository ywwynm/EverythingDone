# 华为源码版粒子动画移植到 Android

开始日期：2026-10-05。

把桌面上按华为 `GEParticleAblationFilter` 源码逐项实现的粒子消散（`docs/features/dialog-particle-dismiss/tuning-0909-gpt6/harmony_native.py`，
复刻记录见 `docs/features/dialog-particle-dismiss/native-replica-2026-10-04.md`）移植到 Android，作为与现有实现并存、可在设置里切换的第二种实现。

## 文档

- [需求与偏好](preferences.md)
- [阶段决策](decisions.md)
- [会话记录](sessions.md)
- [后续工作](followups.md)
- [2026-10-06 性能优化接续与实测](performance-continuation-2026-10-06.md)：完整交互计时、内容分析、重复帧复用、首页硬件截图及验证范围

## 现有实现的结构与切入点（2026-10-05 梳理，只读）

路径前缀 `PD` = `app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/`。

- 编排 `ParticleDismissController`（消散 `start` / `startContent`，出现 `startCondense` / `appearContent`，抓图）→ 动画层 `ParticleDismissOverlay`
  → 会话 `ParticleSession`（GL 线程上的状态机）→ 模型 / 渲染 `ParticleMicroflakeRenderer`（GLES 3.1 compute，微片 + 压力求解）。
- 与模型无关、可直接复用：抓图（离屏 HardwareRenderer / PixelCopy）、覆盖层、常驻 GL 线程 `ParticleGl`、节拍 `ParticleClock`、
  窗口表面 `ParticleWindowSurface`（SurfaceView，卡片在 API 34+ 用 SurfaceControl 交接）、启动门闩、弹窗叠层抬升、前景重画。
- 模型目前写死在 Session（构造、输入、历史预计算、绘制调用）、Preparation、Gl / WindowSurface 的共享对象类型、CreationPrewarm 里，没有接口。
  计划在 Session 与渲染器之间抽一层：准备（快照 → 纹理）、按时刻绘制、可选的历史能力；`ParticleDismissSpec` 携带所选实现。
- 现有出现动画都是「先录正向轨迹、再倒放」（`ParticleReverseHistory`），左滑跟手也用这份历史。华为模型的颗粒位置是出生后时长的闭式函数，
  出生时刻可由遮罩解析算出，任意时刻都能直接求，倒放与跟手不需要历史缓冲与预计算。
- 渲染约定：输出预乘 alpha、叠画到当前默认帧缓冲、不清屏、ONE / ONE_MINUS_SRC_ALPHA；共享 VAO 用完关掉实例属性；Mali / Maleoon 顶点阶段不读 SSBO。

## 覆盖范围（调用点）

弹窗出现 / 消散（29 个 `BaseDialogFragment` 子类、透明宿主 Activity）；首页「调整外观」面板 `ParticlePanelAnimator`；首页与「正在做」页左滑完成
`ParticleSwipeAnimator`；习惯打卡后卡片回位 `ThingSwipeReturn`；多选菜单的完成 / 删除 / 恢复 / 彻底删除 `ThingListRemovalAnimator`；
新建页从 FAB 出现 `ThingCreationTransition`；保存后新卡片入场；粒子动画设置页的预览。碰一碰（`TapParticleFlight`）用现有引擎的专有部分，是否覆盖待定。

## 设置

主设置的 `dialog_particle_animation`、`thing_creation_animation`、`swipe_complete_animation` 决定各处是否用粒子；「粒子动画设置」页
（`ParticleTuningActivity`，偏好文件 `particle_tuning`）现有四项都是微片模型专属。目前没有实现选择器，新增在这一页。

## 飘散样式的实现（2026-10-05）

路径前缀同上，着色器在 `shared/particle-dismiss/drift-*`。

| 文件 | 作用 |
|---|---|
| `ParticleLayerSession` | 两种会话共用的接口；`ParticleWindowSurface`、`ParticleDismissOverlay`、`ParticleCardSurfaceHandoff` 只认它 |
| `ParticleStyle` | 设置值（流体 / 飘散，缺省飘散）与「粒子数量」「粒子大小」倍率；`ParticleDismissSpec.style` 按它取，碰一碰固定流体 |
| `ParticleDriftModel` | CPU 侧输入：格点值、风、粒子数、出生 / 寿命 / 时长的闭式公式、种子打散 |
| `ParticleDriftStyles` | 参考样式库（`drift-styles.json`，由 `export_styles.py` 生成）：按方向与概率选取（左上旧钢铁侠一族、朝上一组）、变体、格点值套到本次卡片 |
| `ParticleDriftBackground` | PixelCopy 取快照四周的背景亮度，决定加光或标准 src-over |
| `ParticleDriftInputs` | 主线程上从规格建模型（方向、触点远近、粒子数量与大小） |
| `ParticleDriftRenderer` | GL：compute 按索引闭式算颗粒 → 半分辨率工作面 → 叠到窗口表面 → 卡片残留 |
| `ParticleDriftSession` | GL 线程状态机：等取样与快照 → 上传 → 按进度出帧（出现倒放、左滑跟手） |
| `ParticleFramePacing` | 仅调试包：两种会话共用的出帧统计（请求到第一帧上屏、上屏帧数、间隔），`device/perf_compare.py` 按它对比 |
| `ParticleDriftHardwareImport` | 新建页的零拷贝快照：截图所在的 HardwareBuffer 经 EGLImage 直接绑成纹理，内容分析的样本在 GPU 上取（`drift-gather.comp`） |
| `ParticleHardwareTexture` | 上面那一步的 JNI 绑定（`app/src/main/cpp/particle_hardware_texture.cpp`） |

设置页：`activity_particle_tuning.xml` 的「粒子样式」一组与飘散的「粒子数量」（0.2–1.6 倍）、「粒子大小」（0.5–2 倍）；选飘散时隐藏四项流体专属设置。

真机驱动与分析脚本在 `device/`（只授权 R5CW20BLNKL）：`stage_a_tuning.py`（设置页）、`stage_b_probes.py`（调试探针，虚构内容）、
`stage_b_home.py`（真实首页，只动自己新建的纯数字标题测试记事，结束时在回收站）、`style_rounds.py`（样式选取统计；`--force` 钉住样式录像）、
`perf_compare.py`（流体与飘散同口径的起播延迟与出帧：弹窗、左滑、多选移除、新建页）、`touch_distance_demo.py`（钉住一段参考，近 / 中 / 远三次点击的对照表）、
`appear_tail_check.py`（弹窗出现录屏 + SurfaceFlinger 逐帧时延）、`settings_size_check.py`（设置页的粒子数量与大小）、
`perf_table.py`（把一次 perf_compare 的出帧行按场景分好；`perf_compare.py --async-input` 用 240 Hz 异步注入量跟手）、
`zero_copy_check.py`（新建页零拷贝的逐字节核对）、`gpu_bench.py`（各遍每帧 GPU 耗时的消融基准，调试包 `DriftParityRender.bench`）、
`cadence_record.py`（录屏看屏幕上的实际帧间隔）。

## 与桌面实现逐帧一致（2026-10-05）

`parity/`：同一输入、同一设备时刻，Android 与桌面 `harmony_native.py` 逐像素比较（用户要求「要跟桌面模拟器的效果一模一样」）。

| 文件 | 作用 |
|---|---|
| `parity/drift_parity.py` | 七组输入（含粒子大小 1.6 倍、近距离点击）推到 R5CW20BLNKL，由调试 Activity `DriftParityRenderActivity`（`app/src/debug`）用正式代码离屏出帧；桌面用同一份预乘快照字节出同一批帧，逐帧统计差异、出联系表 |
| `parity/check_inputs.py` | 1404 组（尺寸 × 方向 × 种子 × 数量 × 大小 × 触点）由设备上的 `ParticleDriftModel.random` 生成输入，与桌面的 Python 移植逐位比较；另核对内容分析与预乘 |
| `parity/overview.py` | 五组对照的总览图 |
| `parity/sim_android_sheet.py` | 桌面模拟器「Android 移植口径」的冒烟联系表 |

桌面模拟器的「Android 移植口径」（模型 `harmony-android`）在 `docs/features/dialog-particle-dismiss/tuning-0909-gpt6/android_drift.py`：
输入规则是 Kotlin 的逐项移植（单精度逐位相同），渲染是同一个 `NativeRenderer`。产物在 `tmp/harmony-particle-android-port/parity/`。
