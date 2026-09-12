# 粒子消散：修正共同剥离场的内部孤立细缕

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
- `archive/before-motion-continuity/` 保存停滞修复前已发布模型的七场景无损帧，`archive/rejected-low-speed/` 保存被否决候选的四个对照输入。
- `archive/observed-approved/` 冻结认可控制组，`archive/transport-before-variation/` 保存随机化前已提交模型的七场景无损帧；更早归档继续保留。内部目录名只用于追溯。
- `research/` 保留此前 107 条来源研究，原始九段视频仍在仓库 `tmp/particle-dismiss-tuning/ref-videos-all/`。无需重新下载或拟合才能预览。
- `prepare.py`、`fit_*`、`experiment_*` 及 `analysis/apply_*`、`update_*`、`retire_*`、`finalize_*`、`refine_*` 是历史准备／实验脚本，可能覆盖素材或旧参数，不能批量当作当前验证命令运行。`analysis/prepare_holdouts.py` 和 `prepare_streams_holdouts.py` 只用于首次建立对应留出集，不能重复用于覆盖验证输入。
- `analysis/distill_transport.py` 记录共同场的离线提炼过程；`promote_transport_model.py`、`port_transport_android.py`、`update_transport_gallery.py` 是已完成的一次性迁移，不能重复执行。`review_distilled_transport.py` 和原控制组脚本针对迁移前接口，当前对照直接读取冻结帧。`prepare_transport_holdouts.py` 只用于首次建立相应留出集。`archive_before_variation.py` 已完成本轮基线归档，不能重跑覆盖。
- 本目录由原 `tmp/particle-dismiss-tuning/tuning-0909-gpt6/` 整体迁入，原文件保留；旧分析中的绝对路径属于采集历史。迁移清单在本地 `analysis/relocation-20260910.json`。

依赖 NumPy、SciPy、Pillow、OpenCV、ModernGL、PySide6；留出照片来自 scikit-image 本地数据。视频工具在 `C:/ffmpeg/bin/`，桌面 GPU 需支持计算着色器。大型产物继续由本目录的 `.gitignore` 排除。
