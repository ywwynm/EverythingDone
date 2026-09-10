# 粒子消散：共同释放与输运

当前桌面预览、导出与 Android 弹窗消失使用同一套材料生成规则、确定性随机算法、综合引导场和微片着色器。正式路径不读取人物专用的 `profile.json`、`flow-profile.json`，不会根据场景名称选择释放或寿命参数。

本轮从用户认可的观测运动控制组提炼一份共同释放场和速度场，合计 804 KiB。两者共用矩形坐标和方向变换，以保留局部释放、输运和可见亮弧之间的关系。所有输入共用这些资源；尺寸、触点方向、源图材质和随机种子决定差异。面板色微片释放后进一步细化，非面板内容保留真实副片配额。该模型学习了参考的形态，不等于华为内部物理实现或逐帧复刻。

本轮实现与验收见 [共同模型记录](../transport-model-2026-09-10.md)。[此前卷曲与内容色](../streams-content-2026-09-10.md)、[统一模型验证](../unified-model-2026-09-10.md) 和 [此前桌面与 Android 记录](../tuning-2026-09-10-desktop-android.md) 保留原有结果，属于历史记录。

## 审阅结果

- `videos/index.html`：本机审阅页，按场景、类型和速度筛选，支持逐帧与循环。
- `videos/`：96 个 H.264 视频，全部平铺；包含全部 17 场景的 1 倍与 0.5 倍动画／对照，以及钢铁侠、两种添加附件背景的八方向同屏。
- `viewer.py`：17 场景实时预览，点击画面改变触点方向，拖动进度或修改时长。
- `analysis/transport-model/`：本轮参数冻结、名称独立性、留出素材、设备独立建材及阶段对照；此前目录保留历史证据。
- `device-transport/`：本轮两台设备的真实操作、阶段截图、GPU 输出和系统录屏；录屏的双速度成片平铺在其 `videos/` 下。离屏 GPU 图像与实际系统录屏不能互相替代。
- `analysis/model-qa.json`、`trajectory-qa.json`、`video-qa.json`：起止帧、轨迹与导出检查。

优先看钢铁侠的“华为／认可控制组／共同模型”，确认提炼后的变化；再看三个人物的版本对照、自有弹窗和留出集。认可控制组是冻结的诊断产物，只作为对照；正式模型不选择人物专用配置。

## 开发集和留出集

开发集是钢铁侠、灭霸、科比、更改语言、调整颜色、添加附件的纯色背景和图片背景，共七个场景。

此前八个输入（独立自然照片、深色文字面板、宽白面板、窄长列表、透明渐变镂空、真实长通知、咖啡照片和有色面板）转为回归集，不重复计作全新素材。

本轮模型冻结后新增灰度照片和横向浅暖弹窗两个输入，未根据结果回调参数。模型指纹为 `b48509b452ea07ff0cfd6e7d5935b80e6d66ac9f4e959408fa6c53194d4377ab`。没有追加场景专用补偿；测试接收器后来补充素材名称不改变正式模型。

通知背景来自后续无通知帧的仿射配准与模糊估计，亮度、透视和边缘仍有重建误差。它验证前景材质适用性，不作为真实隐藏背景或华为运动一致性的证据。其余构造背景也有明确标记。

## 视频口径

| 文件名标记 | 内容 | 数量 |
| --- | --- | ---: |
| `animation` | 17 场景独立动画，两个速度 | 34 |
| `compare-control` | 钢铁侠的认可控制组与共同模型对照，两个速度 | 2 |
| `compare-versions` | 七个原有场景与此前模型对比，两个速度 | 14 |
| `compare-phase` | 三主序列与华为按进度对齐，两个速度 | 6 |
| `compare-file` | 三段参考保留文件时间，两个速度 | 6 |
| `compare-source` | 四个自有弹窗、八个回归输入、两个新输入与静态源素材对照 | 28 |
| `eight-directions` | 钢铁侠、两种附件背景各八方向同屏，两个速度 | 6 |

模型基准为 1 秒，开头停留 0.35 秒、结尾 0.50 秒。普通视频分别长 1.85 秒、3.70 秒。模型以 120 Hz 保存时刻，0.5 倍视频使用中间时刻；全部视频以 60 帧/秒编码。参考侧只使用原片已有帧。

进度对齐不代表原片速度；参考录屏可能已慢放，文件时间不能证明华为实机时长。灭霸参考在转黑前截止，末尾仍有轻微粒子。语言、颜色和图片背景附件存在遮挡区重建，纯色背景附件有真实无弹窗背景。

## 运行和验证

使用 `C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe -X utf8`，在本目录执行下列脚本：

```text
viewer.py
serve_gallery.py 13070
export_videos.py
build_gallery.py
verify.py
trajectory_probe.py --all-visible
verify.py --videos
analysis/check_unified_contract.py --report-dir analysis/transport-model
```

可用 `export_videos.py --scenes kobe --kinds comparison --rates 1 .5` 单独导出一组。导出清单记录完整素材与代码散列；更改素材后须重新生成对应验收清单，不能只改标记。

`export_android.py` 从当前桌面着色器导出 GLES 资源，并生成仅包含 PNG、尺寸、方向和种子的设备输入。`analysis/generate_unified_fixtures.py` 生成 JVM 跨语言回归材料。Android 读取仓库 `shared/particle-dismiss/` 下的规则和资源，不依赖桌面素材。

仅在获得对应设备授权后运行 `analysis/validate_unified_devices.py <设备序列号> --output device-transport`；它安装本地 APK、启动应用到前台、触发独立建材并拉取证据。两台完成后运行 `analysis/compare_unified_devices.py --device-dir device-transport --report-dir analysis/transport-model`。半透明 PNG 的预乘往返会造成颜色量化：不透明材料严格对照，半透明寿命差须小于一个 120 Hz 样本。离屏提交耗时不代表实际显示帧率。

`analysis/validate_streams_ui.py <设备序列号> --output device-transport` 验证真实返回、按钮、八方向和连续关闭；`analysis/validate_unified_publish.py <设备序列号> --output device-transport --back-direction 135` 安装并核验当前发布包。返回键固定左上（模型角度 135°），真实触点关闭继续跟随触点。本轮实际验证了返回键，未单独注入预测性返回手势。

## 文件和 Git 范围

- `renderer.py`、`unified_model.py` 是当前模拟器；共享参数在仓库 `shared/particle-dismiss/rules.properties`，共同场为 `common-release.f32` 和 `common-flow.f16`。`fields.py` 只保留历史拟合与诊断用途，不进入正式运行。
- `assets/` 的场景 JSON 入库；图片、参考帧、`inputs/`、`cache/`、`videos/`、`device-*/`、`android-*-fixtures/` 和诊断产物仅保留本地。干净克隆需要恢复素材才能运行全部桌面对照。
- `shared/particle-dismiss/` 是正式运行资源，规则、804 KiB 共同场、着色器和字节散列清单必须入库。
- `archive/observed-approved/` 冻结认可控制组，`archive/streams-before-edge-roll/` 保存此前发布模型的七场景无损帧；更早归档继续保留。内部目录名只用于追溯。
- `research/` 保留此前 107 条来源研究，原始九段视频仍在仓库 `tmp/particle-dismiss-tuning/ref-videos-all/`。无需重新下载或拟合才能预览。
- `prepare.py`、`fit_*`、`experiment_*` 及 `analysis/apply_*`、`update_*`、`retire_*`、`finalize_*`、`refine_*` 是历史准备／实验脚本，可能覆盖素材或旧参数，不能批量当作当前验证命令运行。`analysis/prepare_holdouts.py` 和 `prepare_streams_holdouts.py` 只用于首次建立对应留出集，不能重复用于覆盖验证输入。
- `analysis/distill_transport.py` 记录共同场的离线提炼过程；`promote_transport_model.py`、`port_transport_android.py`、`update_transport_gallery.py` 是已完成的一次性迁移，不能重复执行。`review_distilled_transport.py` 和原控制组脚本针对迁移前接口，当前对照直接读取冻结帧。`prepare_transport_holdouts.py` 只用于首次建立本轮留出集。
- 本目录由原 `tmp/particle-dismiss-tuning/tuning-0909-gpt6/` 整体迁入，原文件保留；旧分析中的绝对路径属于采集历史。迁移清单在本地 `analysis/relocation-20260910.json`。

依赖 NumPy、SciPy、Pillow、OpenCV、ModernGL、PySide6；留出照片来自 scikit-image 本地数据。视频工具在 `C:/ffmpeg/bin/`，桌面 GPU 需支持计算着色器。大型产物继续由本目录的 `.gitignore` 排除。
