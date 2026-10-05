# 粒子动画桌面模拟器与参考对照

**当前统一入口：[最新实时预览](http://localhost:8666/latest.html)。** 默认展示最新已发布的算法，版本更新后自动刷新；页面内可打开对应预渲染对照。后续不再让用户辨认开发端口或实验目录，发布流程见 [live/README.md](live/README.md)。下文的 8652／8653 是后台及历史地址。

## 2026-10-04 历史缓存清理

按用户要求删除约 1.21 TB 的可重建数组，本目录降至约 44.46 GB。原始参考、素材、全部代码／参数／视频、当前 `analysis/match-cache/takeover-20261004/` 及实时预览依赖均保留；8652／8653 已复核正常。

`handoff-review` 仅保留 V104m、V130、V134 的完整帧，其他变体保留参数、测量与缩略图；`codec` 保留 MP4 和 JSON，移除重复解码数组。归档保留 `observed-approved`、`before-rim-flow` 和交接基准，其余顶层渲染帧已清理，源码／输入资源未删。历史文档中的缓存路径不保证仍有完整数组，需按保存的脚本和参数重算；详细清单见[清理记录](../cache-cleanup-2026-10-04.md)。

后续实验优先保存成片、关键帧和轻量结果，仅让当前实验与少量必要基准保留完整数组；不要批量重建全部历史缓存。本次没有安装自动清理任务，也没有修改算法。

## 2026-10-04：接手后的桌面对照与实时预览

当前入口：[实时预览](http://localhost:8652/)；[预渲染三栏对照](http://localhost:8652/review/)。启动方式仍为 `python live/live_server.py 8652`，使用仓库约定的 `everythingdone` 环境；完整命令和测试见 [live/README.md](live/README.md)。

- 三项可选模型：Android 同规则基准、只减少粒子的消融、多起点候选。候选粒子总数约减少 62.5%，新参考布局改善，但尚未通过旧钢铁侠弧边等全部回归，不能作为已验收的正式替代版。
- 预渲染与实时共用 `desktop_inputs.make_renderer()`，共十二段参考、四个自有弹窗。参考输入使用早期人工起点，随机输入由共同分布生成，不再读取逐段拟合速度、前沿或样本段。
- 序列选择无需播放即显示首帧；页面居中、响应式布局。支持暂停、拖动进度、锁定种子、重放和保持输入切换模型。
- `python analysis/export_takeover_review.py` 生成原片／基准／候选的 121 帧三栏视频、关键帧及连续性图，输出在 `analysis/match-cache/takeover-20261004/`。算法结论见[接手复核](../takeover-2026-10-04.md)；外部资料见[调研](../research-2026-10-04-unified-model.md)。
- 下节为接手前历史入口说明。`live_inputs.py`、`spec-live.txt` 仍保留，但当前实时服务不执行它们。

## 2026-10-04 接手前：Claude 实时预览（历史记录）

`python live/live_server.py 8652`（或 `.claude/launch.json` 的 `dialog-dismiss-live`），浏览器打开 http://localhost:8652/ 。
在本机 GPU 上实时渲染当前的真机等价版本（`live/spec-live.txt`，算法有进展即同步更新），MJPEG 推流到网页。画面分两组：

- **自有弹窗**（添加附件 · 纯色 / 图片背景、更改应用语言、调整颜色）：点击弹窗外、手机屏幕内的任意位置关闭，方向 = 弹窗中心指向触点，触点离边缘越远位移越大；
  「返回键关闭」没有触点，方向固定左上。起点布局与整体运动从十段参考里随机抽一段（页面上可固定），旋转到本次方向。
- **华为参考序列**（钢铁侠、灭霸、白衣人物等十段）：左边原片、右边实时渲染，两边按同一进度播放。三种播放：
  · 点击右栏任意位置：真机等价（这一段的起点布局与整体快慢旋转到触点方向，统一风，前沿起伏随机）；
  · 「按原片的方向播放」：原片那一次的方向、起点与这一段逐段拟合的湍流样子（10-02 允许作为这一次的随机实现），前沿起伏随机；
  · 「逐段拟合对照」：前沿起伏也用这一段拟合出的值，只用来验证模型结构（两层目标的第 1 层）。
  状态栏的「运动」「消融前沿起伏」两行写明当前用的是哪一种。各段整体快慢都按颗粒云 PIV 速度比校正（`analysis/speed-cal-v1.json`、`speed-cal-fit-v1.json`）。
- 逐次输入（`live/live_inputs.py`，不读华为原片的画面）：旋转量按样本段颗粒在出生后 0.3 进度内的位移方向对齐触点；所有颗粒共用整体初速与加速度（统一风），
  只有逐颗快慢离散；前沿起伏按 `analysis/field-stats-v53-f31.json` 的十段统计随机生成。每次点击覆盖 `analysis/` 下的保留版本表
  `kin-uvt-v900`、`release-param-v900`、`release-oracle2-v900`。
- 变体文件每次点击重读（改了不用重启）；改了 `live_server.py` / `live_inputs.py` 要重启。渲染超采样 2 倍（与离线对照相同），灭霸约 30～40 帧 / 秒。
- 播完停 0.8 秒后回到弹窗显示时的画面；静止时每 0.25 秒重发当前帧（浏览器的 MJPEG 要等到下一段才显示上一段，只发一帧时画面是空的）。
- 重叠提亮在 GPU 上合成（与 `handoff_variants` 的 CPU 版逐像素相差 ≤ 1 灰阶）。
- 离线复现预览页的画面：scratchpad 的 `live_sheet.py`（联系表）、`live_crop.py`（原分辨率局部），用 901 号表，不影响预览页。

## 2026-09-27：接手后的共同条件场研究

入口：[共同模型与华为原片](http://127.0.0.1:13070/shared-field.html)，[逐段测量及限制](http://127.0.0.1:13070/shared-field-report.html)。本轮只针对华为十二段参考；自有弹窗暂不调参，Android 保持现状。

- `python viewer.py --shared-field`：当前候选的可交互桌面预览。加 `--capture` 可检查离屏界面。
- `python analysis/export_shared_candidate.py --details --differences --previous analysis/match-cache/proportional-curl-before`：十二段原片、此次调整前和本轮候选的三栏对比，另有原尺寸局部及十段有效背景的固定色阶差异，合计 68 个视频，全部平铺在 `videos/`，各有 1 倍和 0.5 倍。省略 `--previous` 时中间列采用 Claude 接手候选。
- `python analysis/report_shared_candidate.py`：从导出清单生成测量报告；`python analysis/check_shared_candidate.py --huawei-only` 检查起点顺序不变性、微扰连续性和共同流场先验；`python analysis/check_surface_transfer.py --gpu` 检查实际候选的空间索引、有限状态、结束残留和随机访问回放。
- 实现：`learned_release.py`、`coupled_transport.py`、`conditional_material.py`、`shared_transport.py`、`surface_transfer.py`，三份 `conditional-*.json` 及 `surface-transfer-rules.json`。所有序列共用函数及权重，输入采用 `OBSERVED_SITES`，不采用 Claude 后续逐段细化的起点速率／线段长度。
- 方法和拒绝的候选见[接手实验记录](../takeover-method-2026-09-27.md)。释放采用渲染后同口径的闭环校准；输运采用可微粗粒化实验，再由实际 GPU 完整颗粒验证。颗粒粗化与数量反比处理，避免用整体加白补覆盖。
- `origin_volume.py`、`analysis/study_origin_volume.py` 是未采用的深度隆起和边缘抑制消融；不能把它们当作正式候选已经启用了 Z 轴云团。起点边界外覆盖已加入共同校准，鼓起形态仍有差距。
- 本轮柔和度改动根据传播梯度计算交接宽度，原表面与颗粒逐渐交接；共同释放时序、输运重新校准，颗粒尺度和明暗沿用上轮。旧钢铁侠流场先验保留，但最终覆盖与出生时刻也受新交接影响，不再声称最终图像与旧版逐像素相同。
- 后续共同输运采用固定的多尺度几何特征及速度场积分；释放增加共用的有界时间曲线。旧钢铁侠标准布局同样允许校准，不再被严格锁定为旧实现。`analysis/check_integrated_transport.py` 检查训练／渲染两端的积分单位、一致性及采样雅可比；正值仅表明该修正场在检查范围内未穿插，不代表视觉上不会形成致密细缕。
- `analysis/fit_shared_transport.py --teacher` 只输出离线对应诊断，不能用于运行；`--distill` 将其投影回不含素材身份的共同函数。`cloud_material.py` 的数量、寿命和提亮消融未通过，不接入默认模型。
- 2026-09-28：`particle_coverage.py` 把同一密度修正分配给颗粒数量和面积，并以足迹／不透明度互补柔化颗粒。`particle-coverage-rules.json` 为共同规则；`analysis/check_particle_coverage.py` 验证零配置等价、结束残留、有限状态及回放。
- `particle_cohorts.py` 与 `particle-cohort-weights.json` 分别处理同一位置的新生、成熟、末期颗粒覆盖，仍是所有参考共用的几何与时空条件函数。`analysis/check_particle_cohorts.py --candidate particle-cohort-weights.json` 验证关闭时的像素等价、运动状态不变、首尾帧、回放和起点连续性；`analysis/verify_shared_exports.py` 同时核对当前代码／权重、成片身份、速度和解码。
- `surface_coherence.py` 与 `surface-coherence-weights.json` 联合校准局部的原表面／运动片交接时刻和过渡宽度，不改变粒子运动、颜色或采样数量。所有输入共用一份双输出函数，仍兼容旧单输出权重。交接时钟的解析导数受正值约束；实际表面覆盖另用 GPU 逐实例回读检查，不能把时钟单调直接当作覆盖单调的证明。`analysis/check_surface_coherence.py --candidate surface-coherence-weights.json` 检查零配置、原状态、首尾、回放和输入连续性。
- `analysis/fit_surface_handoff.py` 用真实 GPU 图像的局部响应联合校准时刻和宽度；`analysis/check_surface_handoff.py --candidate <权重文件>` 检查实际表面覆盖。`surface_handoff.py` 保留实验入口，其被采用的运行规则已并入 `surface_coherence.py`。颗粒数量／面积／反射联合拟合虽进一步降低部分 RGB 误差，但部分序列的颗粒指标退步，未接入默认模型；`particle_population.py`、`analysis/fit_particle_distribution.py`、`analysis/study_fine_reflection.py` 仅供研究。
- 早期共同空间释放基、固定阈值微片细分、曝光积分和多频段颗粒统计的实验没有通过联合验收，未启用。`analysis/study_particle_exposure.py`、`analysis/fit_grain_statistics.py` 只作诊断；后者降低部分纹理差却损伤结构，不能直接将其输出作为默认配置。
- `analysis/study_particle_sampling.py` 区分固定输运下的绘制细分与局部散布；`analysis/study_material_resolution.py` 检查真实材料网格加密，`--motion` 检查共同携带速度；`analysis/study_shared_compositing.py` 只检验合成空间。均为未采用消融，默认预览与导出不读取这些结果。
- 连续形态复核使用 `analysis/check_cloud_evolution.py` 的云团占用、质心、前后沿与展宽时序，并直接查看原片；PIV 的颗粒真实位移标定已失败，相关消融只保留为诊断记录，不作为验收。`analysis/review_shared_exports.py` 从最终编码成片取整幅手机画面复看。
- `analysis/anchor_transport.py` 用真实 GPU 图像校准输运拟合的展开点；`--refinement` 约束相对既有形变的变化。只降低近似拟合损失不构成采用依据，需另渲未采样时刻及完整动画。`particle_optics.py`、`particle_footprint.py` 及对应研究脚本保留为消融工具，不是按素材切换的模型。
- 2026-09-28：`particle_fission.py` 与 `scatter_material.py` 将投影偏大的运动片连续细分，并联合校准局部光学覆盖、反射与色度；共同权重为 `particle-scatter-weights.json`。原有模拟运动不变，细粒绘制包含共同的小范围散布。十段 733 帧的结构、颗粒差与云团占用差均改善，另外两段只作可观测区目视对照。复核命令为 `python analysis/check_scatter_material.py --weights particle-scatter-weights.json --baseline analysis/match-cache/fine-material-before`；不能把误差下降当作近像素级一致。残差输运因局部负雅可比未采用。

- 2026-09-28 早期连续运动修正：固定材料的 GPU 路径证实，后置时变位置修正造成了额外同步侧摆。`analysis/diagnose_motion_continuity.py` 直接追踪实际中心，`--fine` 另查细分颗粒；`analysis/fit_fine_transport_response.py` 将十二段的加速度、逆风速度与多余横向往返纳入共同校准。当次只替换 `conditional-transport-weights.json`，密度函数及基础状态保持一致；后续比例修正已允许基础积分场变化，需使用 `analysis/check_joint_refinement.py --allow-flow-change` 检查。原片形状与静态误差仍须同时检查，不能以运动指标减少代替视觉验收。详见[连续运动记录](../motion-continuity-2026-09-28.md)。

- 2026-09-28 材质连续性修正：共同的 `particle-scatter-weights.json` 限制实际材料沿途覆盖、反射和色度的重复起伏，几何输入与三项输出不变，粒子位置不变。`analysis/check_material_paths.py` 按完整三维纹理复核独立抽样；统计的是控制量，不是最终像素亮度。逐格容量诊断不进入运行时。拟合、未采用结果和完整误差见 [材质诊断记录](../material-contrast-2026-09-28.md)。

这是桌面研究路径，小型条件函数尚未提炼成最终移动端允许的实现。参考已参与校准，不再称留出；目前仍未达到逐像素一致，不能只用整体误差下降验收局部粒子运动。

- 2026-09-28 最新共同细化：`analysis/fit_motion_material_joint.py` 联合校准现有位置与材质函数；`--native-path-clock --cohort-synchrony-guard` 直接使用 120 Hz 实际轨迹及足量的持续可见材料。最终仍由独立的 GPU 全路径、细分颗粒和完整原片验证，不能把拟合器约束通过当作动画正确。
- `analysis/prepare_uncertain_grain_cache.py` 仅为背景未知的新 2／3 加入可观测高频纹理，不启用其 RGB 损失。`analysis/prepare_grain_responses.py` 用真实 GPU 小扰动校准材质响应，`analysis/check_grain_responses.py` 核实近似，`analysis/check_joint_path_proxy.py` 核实位置增量与真实路径；它们都只生成忽略目录下的研究缓存，不进入运行模型。
- 最新比例修正位于 `conditional_material.py`：`curl_balance=1` 使附加卷动与引导采用同一个布局缩放系数，0 为原行为。当前更新这一共同规则及运动／材质两份权重，其余五份不变。围绕新流场生成缓存时，准备、微扰和联合拟合均须传同一 `--root`／`--base-root`，不能沿用旧流场缓存。`analysis/check_image_evolution.py` 另比较排除训练帧后的原片画面变化，报告实际独立帧间距；这不是原片粒子速度测量。

## 2026-09-27：第二版共同模型与逐帧对齐（桌面候选）

审阅入口：[第二版 · 华为原片逐帧对照](http://127.0.0.1:13070/v2-review.html)（`videos/v2-review.html`）。视频文件名以 `v2-` 开头，
与此前的成片平铺在同一目录；每组都有 1 倍和 0.5 倍。类型包括原片对照、完整动画、像素差、原片／此前认可版／第二版三栏、
八方向、六个随机种子。方法、证据与未解决的问题见[逐帧对齐记录](../huawei-match-2026-09-26.md)。

- 模型：`dissolve_v2.py`（`V2Rules` 为所有素材共用的一套常数，默认值即当前采用值）；参考重放的输入在 `v2_inputs.py` 与
  `v2_inputs.json`（起点、方向、触点远近的测量值及其有界细化）。
- 实时预览：`python viewer.py --v2`（可加 `--rules <规则 JSON>`）。固定参考输入时左侧原片按独立画面序号对齐。
- 成片：`python analysis/export_v2_suite.py --extra`；数值检查：`python analysis/verify_v2.py`（只证明运行约束）。
- 测量与拟合：`analysis/match_refs.py`（原片独立画面帧库）、`match_observe.py`（同口径观测）、`match_compare.py`（逐帧对照与联系表）、
  `match_fit.py`（CMA-ES 联合拟合全局常数，离线数值搜索）、`match_inputs.py`（逐段输入有界细化）、`match_eval.py`（结构性改动的并行对照）、
  `match_streaks.py`（运动拖影方向）、`review_v2.py` / `review_all_v2.py` / `review_dialogs_v2.py`（目视审阅图）。
  中间产物在被 Git 忽略的 `analysis/match-cache/`。
- 解除时刻（前沿形状与先后）单独测量与拟合：`analysis/release_integrity.py`（紧容差的原图完整度，原片与模型同口径；
  `--sheet <标签>` 出对照图，`--fit <标签>` 拟合前沿规则与起点，`--refine` 只细化留出素材的起点）。
  另有 `material_tracks.py`（按颜色追踪材料去向）与 `subject_masks.py`（主体图，只作假设检验）。
- 致密带位置：`analysis/structure_bands.py --label <标签>`（原片与模型各自取成带的高密度颗粒区，比较双向平均最近距离；
  红＝只有原片有、青＝只有模型有、白＝重合）。块级误差对“有没有弧形亮带、成股的流”不敏感，这一项补上。
- 渲染进循环的起点细化：`analysis/site_render_fit.py --label <标签> --rules <规则 JSON> --inputs <起点 JSON>`
  （全局规则固定，逐段只细化起点；`MATCH_FIT_BAND` 加致密带项，`SITE_RI_WEIGHT` 加解除时刻实测约束）。
  同口径比较几套配置：`analysis/compare_configs.py --configs 名称=规则JSON[::起点JSON] ...`（起点 JSON 可带 `touch`）。
- 颗粒运动：`analysis/activity.py`（窗口内亮度变化面积与颗粒面积之比，反映颗粒走了多远）、
  `analysis/speed_age.py`（32 像素块相位相关位移，按「解除后经过的时间」分档的颗粒速度），原片与模型同口径。
  `match_fit.py` 可用 `MATCH_FIT_ACT` 加活跃度项；`release_integrity.py` 可用 `RELEASE_WINDOW` 改为窗口完整度。
- 损失的离散噪声：单段约 3、九段平均约 0.6（解除场在粗网格上求，起点挪动跨格时结构会变），比较配置时按这个量级判断。
- 调试用环境变量：`V2_SITES=<拟合结果 JSON>` 临时替换参考重放的起点，不改 `v2_inputs.json`；
  `RELEASE_LAG_SCALE=0` 让解除时刻拟合不用滞后图，`RELEASE_LINE=1` 细化线段起点的沿边长度。
- 手机上运行的仍是一组常数与三段着色器，没有模型权重或推理；Android 本轮未改。


## 2026-09-26：新增七段参考的桌面候选

当前入口是 [新增参考对照](http://127.0.0.1:13070/new-references.html)，对应 `videos/new-references.html`。七段新增、旧钢铁侠／灭霸／科比／蜘蛛侠及白底弹窗共 15 个素材放在同一页；96 个视频平铺在 `videos/`，包含 1 倍、0.5 倍、原片对照、像素差、代表素材八方向和相同起点迁移。

**当前尚未达到逐像素一致。** 这是未替换 Android 正式模型的桌面候选；新参考中后段的形状、颗粒覆盖和流动仍有明显差距。详细输入、采用与否的试验、测量限制见[新增参考建模记录](../new-reference-model-2026-09-26.md)。下方保留的是 9 月 12 日历史状态，不应理解为此次复刻已验收。

- 实时预览：`python viewer.py --fronts`。固定输入用于原片对照；“换一种效果”采样新起点；点击背景可改触点。未加此参数仍进入此前预览。
- 导出：`python analysis/export_reference_suite_0926.py --extra`。
- 回归检查：`python analysis/verify_reference_model_0926.py`。
- 成片检查：`python analysis/verify_reference_exports_0926.py`。容器／数值通过不等于视觉通过。

这次的源码和小型场景描述可以跟踪；`videos/`、参考 MP4、PNG、逐帧数组和研究缓存继续由 Git 忽略。

## 历史记录：修正共同剥离场的内部孤立细缕

最新诊断和实现见[内部细缕定位记录](../filament-localization-2026-09-12.md)。上次扩大随机响应没有消除宽材料带被压成窄线的共同机制，本轮加入连续的局部压缩反馈；`videos/peel-compression.html` 包含原录像准确定位图及 12 组完整画面修复对照。固定材料来源回归脚本为 `analysis/verify_peel_compression.py`，实际双设备检查入口为 `analysis/run_peel_device_checks.py`。

当前桌面预览、导出与 Android 弹窗消失使用同一套材料生成规则、确定性随机算法、综合引导场和微片着色器。正式路径不读取人物专用的 `profile.json`、`flow-profile.json`，不会根据场景名称选择释放或寿命参数。

已提交基线从用户认可的观测运动控制组提炼出共同释放场和速度场。后续停滞修复处理把静态背景和无观测区当成零速度的问题，延续相邻流动并平滑接缝。所有输入共用这些资源，尺寸、触点位置、源图材质和随机种子决定差异。该模型学习了参考的形态，不等于华为内部物理实现或逐帧复刻。

当前版本处理用户三段真机录像中的早期斜线、弯月细缕及后段聚集：剥离响应采用独立随机量，平均力度不变；同时用整数散列统一桌面和 Android 的颗粒网格，消除 GPU 三角函数近似造成的覆盖差异。完整动画留出帧结构误差下降约 1.91%，原始 RGB 误差上升约 0.25%，尚未达到对华为近像素一致。运行端没有人物专用遮罩、诊断像素框或逐帧轮廓。详见[本轮诊断与验证](../device-filament-origins-2026-09-12.md)。

阿里云更新 `202609120532` 已发布，两台指定设备已安装发布包并复测。170 视频、18 组设备素材对照、真实关闭及远端 APK 身份由 `analysis/device-filament-origins/acceptance.json` 记录。

此前的弧边展开、随机起始区域、自由边界连续流动和触点远近响应继续保留。真实触点按该方向可用屏幕背景归一化，在 0.55～1.45 内同时改变相对出生位置的位移和速度，默认系数为 1，不形成目标吸收点。共同场与可信度仍为 996 KiB，两端显式插值。[此前弧边分离](../curve-split-2026-09-11.md)、[共同形态](../flow-family-2026-09-11.md)和[被否决的强汇聚候选](../targeted-release-2026-09-11.md)保留历史结果。

## 审阅结果

- `videos/index.html`：本机审阅页，按场景、类型和速度筛选，支持逐帧与循环。
- `videos/`：170 个本轮 H.264 视频，全部平铺；128 个主要视频、36 个补充视频、6 个真实弹窗素材的修复前后视频。保留 18 场景双速度动画／对照，以及钢铁侠、两种附件背景和颜色弹窗八方向同屏。
- `videos/rim-flow.html`：本轮重点入口。先看三组真实弹窗素材的修复前后，再看钢铁侠完整画面、原始像素与结构差、局部弧边和近中远对照。原录像种子未知，前三组使用相同素材复现相近释放布局，不声称逐粒子重放。
- `videos/flow-family.html`：四参考、三素材共同输入、双方向三距离、八方向和此前模型对照，均已更新为本轮模型。
- `viewer.py`：18 场景实时预览，点击画面改变触点方向和距离，也可独立调节边缘外距离；默认每轮变化，可固定种子。
- `videos/flow-continuation.html`：九个重点输入的前后慢放，包含画廊实际种子、用户补充位置及上下左右代表方向。
- `analysis/motion-field-extension/`：全部 226 组标注和 7 个画廊输入的状态对照、853 条长标线及四个额外输入的回归记录。没有重渲染全部标注视频。
- `device-release-distribution/`：两台指定设备各九组输入的独立建材与 GPU 对照，包含三份用户录像素材、早期 0.25 和后段 0.667 进度、实际网格随机值，以及真实弹窗近远、返回和层清理。旧设备目录属于历史结果，离屏 GPU 图像与实际屏幕显示不能互相替代。
- `analysis/model-qa.json`、`video-qa.json`：本轮起止帧、方向与视频容器检查。旧 `trajectory-qa.json`、`analysis/random-variation/` 与 `device-variation/` 属于此前验证。

本轮优先看钢铁侠 0.43～0.65 的横向弧边与下方拖尾，再联合检查四参考及三素材四组形态。示例种子经观测筛选，用于展示可能性，不计作留出；运行时按共同随机规则生成。距离触点全部在素材所示手机屏幕内、控件外；高弹窗的实际斜向角度由坐标反推。钢铁侠两方向近／中／远的中位实际位移约为短边 0.14／0.20／0.27；这些数字只作回归记录，验收页提供同尺度播放与同位置切换。返回键仍使用左上与默认强度。固定视频重播保持同一组种子，桌面预览与 Android 每次关闭生成新实例。

## 开发集和留出集

开发集是钢铁侠、灭霸、科比、更改语言、调整颜色、添加附件的纯色背景和图片背景，共七个场景；本轮加入用户提供的钢铁侠向上参考，共八个研究输入。新增参考经过相机运动及透视配准，前景由两个时刻合成；常规视频使用种子 2，联合形态示例使用种子 489。它参与了设计分析，不计作留出输入。

此前八个输入（独立自然照片、深色文字面板、宽白面板、窄长列表、透明渐变镂空、真实长通知、咖啡照片和有色面板）转为回归集，不重复计作全新素材。

此前冻结后加入的灰度照片和横向浅暖弹窗，与其余八个留出素材作为十个回归输入，不再次计为全新素材。当前模型指纹为 `fb6958ec803fce91e52368e0e08b90df9d5093ac0bc667832cc9d2344fbbc314`。本轮 234 组状态、18 个屏幕内距离输入、14 个跨语言单测、12 组标准设备对照和 6 组录像素材设备对照位于 `analysis/device-filament-origins/`；没有重新渲染 226 组历史标注视频。发布包身份与复测结果由该目录的 `acceptance.json` 绑定。

通知背景来自后续无通知帧的仿射配准与模糊估计，亮度、透视和边缘仍有重建误差。它验证前景材质适用性，不作为真实隐藏背景或华为运动一致性的证据。其余构造背景也有明确标记。

## 视频口径

| 文件名标记 | 内容 | 数量 |
| --- | --- | ---: |
| `animation` | 18 场景独立动画，两个速度 | 36 |
| `compare-control` | 钢铁侠的认可控制组与共同模型对照，两个速度 | 2 |
| `compare-versions` | 八个研究场景与此前模型对比，两个速度 | 16 |
| `compare-phase` | 三主序列及新增向上参考按进度对齐，两个速度 | 8 |
| `compare-file` | 四段参考保留文件时间，两个速度 | 8 |
| `compare-source` | 四个自有弹窗、十个回归输入与静态源素材对照 | 28 |
| `seed-variants` | 八个研究素材，同一方向六个种子同屏，两个速度 | 16 |
| `eight-directions` | 钢铁侠、两种附件背景和颜色各八方向同屏，两个速度 | 8 |
| `touch-distances` | 钢铁侠、附件、颜色各双方向三距离同屏，两个速度 | 6 |
| `family-reference` | 四段参考各展示一组共同规则输入，两个速度 | 8 |
| `flow-family` | 钢铁侠、附件、颜色原样使用四组方向和种子，两个速度 | 6 |
| `curve-split` | 钢铁侠整体与局部放大，参考／调整前／当前三栏 | 2 |
| `rim-detail` | 钢铁侠横向弧边固定区域放大，参考／调整前／当前三栏 | 2 |
| `distance-focus` | 三素材、左上和向上各近中远单行对照，两个速度 | 12 |
| `surface-whole` | 钢铁侠完整画面，参考／调整前／当前三栏 | 2 |
| `upper-corner` | 钢铁侠左上角的同位置对照 | 2 |
| `pixel-difference` | 钢铁侠参考、共同模型、原始 RGB 差与结构差 | 2 |
| `dialog-release-filament` | 三份用户真机素材的修复前后，两个速度 | 6 |

模型基准为 1 秒，开头停留 0.35 秒、结尾 0.50 秒。普通视频分别长 1.85 秒、3.70 秒。模型以 120 Hz 保存时刻，0.5 倍视频使用中间时刻；全部视频以 60 帧/秒编码。参考侧只使用原片已有帧。

进度对齐不代表原片速度；参考录屏可能已慢放，文件时间不能证明华为实机时长。灭霸参考在转黑前截止，末尾仍有轻微粒子。语言、颜色和图片背景附件存在遮挡区重建，纯色背景附件有真实无弹窗背景。

## 运行和验证

使用 `C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe -X utf8`，在本目录执行下列脚本：

```text
viewer.py
serve_gallery.py 13070
export_videos.py
analysis/export_flow_family.py
build_gallery.py
analysis/build_flow_family_review.py
analysis/build_rim_review.py
verify.py
verify.py --videos
analysis/export_release_filament_review.py
analysis/verify_release_distribution.py
analysis/final_frame_calibration.py --analysis-dir device-filament-origins --device-dir device-release-distribution
analysis/final_frame_calibration.py --analysis-dir device-filament-origins --device-dir device-release-distribution --published
```

可用 `export_videos.py --scenes kobe --kinds comparison --rates 1 .5` 单独导出一组。导出清单记录完整素材与代码散列；更改素材后须重新生成对应验收清单，不能只改标记。

`analysis/common-shape-calibration.json` 保存本轮有限尺度校准系数及资源身份；该轻量参数记录入库，原始参考、搜索记录、差异图、视频和设备大产物继续忽略。只调整差异视频说明或统计区域时，可用 `analysis/export_flow_family.py --pixel-only` 重导两个文件；模型改变后必须完整重导。

`export_android.py` 从当前桌面着色器导出 GLES 资源，并生成仅包含 PNG、尺寸、方向和种子的设备输入。`analysis/generate_unified_fixtures.py` 生成 JVM 跨语言回归材料。Android 读取仓库 `shared/particle-dismiss/` 下的规则和资源，不依赖桌面素材。

仅在获得对应设备授权后运行 `analysis/validate_unified_devices.py <设备序列号> --scenes ironman attachment color --output device-flow-fast-clock`；它安装本地 APK、启动应用到前台、触发独立建材并拉取证据。两台完成后运行 `analysis/compare_unified_devices.py --scenes ironman attachment color --device-dir device-flow-fast-clock --report-dir analysis/motion-field-extension/device-fast-clock`。半透明 PNG 的预乘往返会造成颜色量化：不透明材料严格对照，半透明寿命差须小于一个 120 Hz 样本。离屏提交耗时不代表实际显示帧率。

`analysis/validate_streams_ui.py <设备序列号> --output device-variation` 验证真实返回、按钮、八方向和连续关闭；`analysis/validate_unified_publish.py <设备序列号> --output device-variation --back-direction 135` 安装并核验当前发布包。返回键固定左上（模型角度 135°），真实触点关闭继续跟随触点。本轮实际验证了返回键，未单独注入预测性返回手势。`analysis/validate_variation_ui.py <设备序列号> --count 3 --output device-flow-fast-clock` 可只做三次真实连续关闭。

## 文件和 Git 范围

- `renderer.py`、`unified_model.py` 是当前模拟器；共享参数在仓库 `shared/particle-dismiss/rules.properties`，共同场为 `common-release.f32` 和 `common-flow.f16`。`fields.py` 只保留历史拟合与诊断用途，不进入正式运行。
- `assets/` 的场景 JSON 入库；图片、参考帧、`inputs/`、`cache/`、`videos/`、`device-*/`、`android-*-fixtures/` 和诊断产物仅保留本地。干净克隆需要恢复素材才能运行全部桌面对照。
- `shared/particle-dismiss/` 是正式运行资源，规则、996 KiB 共同场与可信度、着色器和字节散列清单必须入库。
- `archive/before-rim-flow/` 保留本轮调整前代码、共享资源和八素材帧，供相同种子前后对照；不能覆盖。`archive/before-curve-split/` 为上一轮基线，`archive/before-targeted-release/` 是加入向上参考之前的基线；`archive/rejected-target-attraction/` 保存被否决的过强汇聚候选；均为历史记录。
- `archive/before-motion-continuity/`、`archive/rejected-low-speed/` 保留停滞修复前与被否决候选的历史记录，其顶层无损帧已在 2026-10-04 清理。
- `archive/observed-approved/` 仍保留完整认可控制组；`archive/transport-before-variation/` 及更早归档保留代码、资源和已有成片，顶层逐帧缓存已清理。内部目录名只用于追溯。
- `research/` 保留此前 107 条来源研究，原始九段视频仍在仓库 `tmp/particle-dismiss-tuning/ref-videos-all/`。无需重新下载或拟合才能预览。
- `prepare.py`、`fit_*`、`experiment_*` 及 `analysis/apply_*`、`update_*`、`retire_*`、`finalize_*`、`refine_*` 是历史准备／实验脚本，可能覆盖素材或旧参数，不能批量当作当前验证命令运行。`analysis/prepare_holdouts.py` 和 `prepare_streams_holdouts.py` 只用于首次建立对应留出集，不能重复用于覆盖验证输入。
- `analysis/distill_transport.py` 记录共同场的离线提炼过程；`promote_transport_model.py`、`port_transport_android.py`、`update_transport_gallery.py` 是已完成的一次性迁移，不能重复执行。`review_distilled_transport.py` 和原控制组脚本针对迁移前接口，当前对照直接读取冻结帧。`prepare_transport_holdouts.py` 只用于首次建立相应留出集。`archive_before_variation.py` 已完成本轮基线归档，不能重跑覆盖。
- 本目录由原 `tmp/particle-dismiss-tuning/tuning-0909-gpt6/` 整体迁入，原文件保留；旧分析中的绝对路径属于采集历史。迁移清单在本地 `analysis/relocation-20260910.json`。

依赖 NumPy、SciPy、Pillow、OpenCV、ModernGL、PySide6；留出照片来自 scikit-image 本地数据。视频工具在 `C:/ffmpeg/bin/`，桌面 GPU 需支持计算着色器。大型产物继续由本目录的 `.gitignore` 排除。
