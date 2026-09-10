# 0909 桌面粒子消散候选

当前桌面视频与预览使用 **r33**，已移植 Android 的 dialog 消失路径并完成双设备测试。r33 保留钢铁侠的运动改善，适度细化灭霸、科比的卷束以及白色微片明暗；自有弹窗使用同一连续速度积分和综合流场。沿用 107 来源研究和九段参考分析，局部细束仍有差异。最终过程见 `docs/features/dialog-particle-dismiss/tuning-2026-09-10-desktop-android.md`。

## 先看结果

- `videos/index.html`：视频审阅页，可筛选场景、类型和速度，支持逐帧、循环、全屏。
- `videos/`：54 个 MP4，共约 418.62 MiB，全部平铺；旁边的 JPG 是封面。
- `start-preview.ps1`：实时桌面模型，可切换七个场景、拖动时间、调整时长与方向；点击右侧画面按触点方向重播。
- `analysis/all-scenes-final.jpg`：七个场景的阶段总览。
- `analysis/r33-ui-attachment.jpg`、`r33-ui-attachment-image.jpg`、`r33-ui-color.jpg`、`r33-ui-language.jpg`：自有弹窗与 r31 的阶段对照。
- `device-r33/videos/`：三星真实录屏的 1 倍与 0.5 倍共 10 个视频，全部平铺；OPD2515 的真实阶段截图在 `device-r33/9018f404/`。
- `analysis/final-acceptance.json`：桌面、Android、双设备操作、发布包散列及证据限制的汇总；当前调试更新为 **202609091858**。

建议先看添加附件、调整颜色的 `compare-versions` 新旧两栏对比，再看灭霸、科比、钢铁侠的三栏对比与八方向视频。审阅页默认打开添加附件的版本对比。

七场景共用同一个模拟器。照片使用各自校准的释放场、投影流场和部分寿命参数；自有弹窗使用通用释放场、三个参考综合的流场及通用寿命模型。因此自有弹窗已应用共同运动，但不会逐条复制照片中特定材料的路径。

## 速度与素材口径

| 文件名标记 | 内容 | 数量 |
| --- | --- | ---: |
| `animation` | 七个场景的独立动画，两个速度 | 14 |
| `compare-versions` | 照片为华为／r31／r33 三栏，自有弹窗为 r31／r33 两栏，七场景、两个速度 | 14 |
| `compare-phase` | 三个主序列与华为参考按进度对齐，两个速度 | 6 |
| `compare-file` | 保留三段参考文件的原始播放时间，模型 1 秒后停留，两个速度 | 6 |
| `compare-source` | 四个弹窗场景与静态真机截图对照，两个速度 | 8 |
| `eight-directions` | 钢铁侠、两种添加附件背景各自八方向同屏，两个速度 | 6 |

模型基准时长为 1.00 秒，开头停留 0.35 秒，结尾停留 0.50 秒。普通 1 倍视频长 1.85 秒，0.5 倍长 3.70 秒。源文件时间对照因原片区间不同而更长。

所有视频为 H.264、60 帧/秒。模型保存 120 Hz 的独立时刻，0.5 倍速使用中间时刻，非简单复制 60 Hz 视频帧。参考侧只使用录屏实际存在的帧，不虚构新增运动帧。

`compare-phase` 的参考侧被重定时，不是原片文件速度。录屏可能已经慢放，`compare-file` 也不能证明真实设备动画时长。灭霸原片在转黑前截止，终点仍有轻微粒子。

钢铁侠、灭霸、科比用各自的原片前景／背景；灭霸前景改用 1.0 秒的干净卡片，并清理背景中少量尾部粒子。纯色背景的添加附件有同页无弹窗真机截图。语言、颜色及图片背景附件的被遮挡内容做了局部重建，可见背景保持对应；重建不代表真实隐藏内容。

## 运行

使用现有 Python 环境，无需安装新依赖：

```powershell
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 'E:/projects/EverythingDone/docs/features/dialog-particle-dismiss/tuning-0909-gpt6/viewer.py'
```

启动支持视频定位的本机审阅服务：

```powershell
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 'E:/projects/EverythingDone/docs/features/dialog-particle-dismiss/tuning-0909-gpt6/serve_gallery.py'
```

实际端口、地址和进程号写到 `analysis/gallery-server.json`，服务只监听 `127.0.0.1`。也可直接用浏览器打开 `videos/index.html`，或使用常规视频播放器。

在本目录执行导出与验证：

```powershell
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 export_videos.py
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 build_gallery.py
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 verify.py
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 inspect_white_boundary.py
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 trajectory_probe.py --all-visible
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 verify.py --videos
```

可用 `export_videos.py --scenes kobe --kinds comparison --rates 1 .5` 仅重导一组。

## 文件组织与重现

- `renderer.py`：GPU 模拟与合成，预览／导出共用。
- `fields.py`、`fit_reference.py`：少量起点构成的释放时间场及参考拟合。
- `fit_flow.py`：把参考投影运动压缩为低频连续引导场。
- `assets/`：完整的运行素材、系数和已提取参考帧；运行预览不依赖旧实验代码。
- `inputs/`：归档的原始截图和导入素材，含原路径及 SHA-256。
- `prepare.py`：重新导入素材；读取本轮 `inputs/`、原始参考视频目录及 `analysis/thanos-clean-candidate-1.0.png`。正常预览和导出不需要重复运行它。
- `cache/`：可再生成的 120 Hz RGB 大缓存。
- `research/`：107 条有效来源台账、正文、论文和检索证据。
- `analysis/model-qa.json`、`trajectory-qa.json`、`video-qa.json`、`ui-qa-r33.json`：本轮验证记录。
- `analysis/white-boundary-qa.json`：白色区域合成覆盖的细粒与宏观梯度诊断；细粒梯度增加、宏观梯度下降，需要结合实际视频判断，不能将单个指标视为审美结论。
- `analysis/r33-reference-metrics.json`：参考、新版和归档版均按同一 120 Hz 时刻进行颜色分布诊断，不等同于主观相似度。
- `archive/r31/`：本轮比较基准版代码、完整静态运行素材、参数、七场景无损渲染帧；与原交付散列一致，供本轮版本对比使用。更早归档保留。
- `experiment_coherence.py`：基于 r28 归档的运动、寿命和材质消融；不会改写交付视频与缓存。
- `experiment_pigment.py`：基于 r29 归档比较色度寿命、短时面积、去白混色与粒径方案。
- `fit_emission.py`：早、中、晚释放材料的少量速度／寿命拟合试验；未采用，参数未写入运行素材。
- `experiment_boundary.py`：基于 r30 归档的释放错时、初速、白色显示与形状移植试验。
- `fit_release_regions.py`、`fit_motion_regions.py`、`fit_retention_regions.py`：少量区域参数校准，训练与复核时刻分开。
- `verify_material.py` 与 `analysis/material-qa.json` 是 r30 对照 r29 的历史状态一致性检查，不属于 r31 的验证项；r31 已改变出生时刻与部分流场。
- `videos/manifest.json`：视频尺寸、帧数、时长、速度及生成参数散列。

长期中文文档在 `docs/features/dialog-particle-dismiss/`。Android 运行资源在仓库 `shared/particle-dismiss/`，真实 GPU 输入及输出在 `android-fixtures/`、`device-r33/`。三星有系统录屏；OPD2515 的系统录屏输出受设备限制，保留真实阶段截图。此前测试只操作用户指定的两台设备；本次按用户授权整理源码与提交。


## 2026-09-10 迁移与 Git 范围

本轮目录已整体迁入本功能文档目录，视频、截图、缓存和旧版本随源码一起移动。原位置为仓库 `tmp/particle-dismiss-tuning/tuning-0909-gpt6/`。版本 r33 的模型指纹保持不变；迁移前后文件身份、大小及修改时间的核对清单位于 `analysis/relocation-20260910.json`，不入库。

- Git 跟踪根目录模拟器及工具、`analysis/` 与 `research/` 的脚本、`assets/` 的场景和拟合参数。
- `assets/` 中的图像、参考帧和大流场，`inputs/`、`cache/`、`videos/`、`device-r33/`、`android-fixtures/`、`archive/`、Android 基线，以及研究正文、日志和分析产物均仅保留本地。干净克隆不含这些素材，不能直接播放既有视频或运行全部桌面对照；须从本地归档恢复相应目录。
- `shared/particle-dismiss/` 是正式 Android 运行资源，包含约 162 KiB 的半精度综合流场、着色器和资源散列清单，必须入库；Android 构建及单元测试不依赖被忽略的桌面产物。
- 九段原始参考视频继续保留在仓库 `tmp/particle-dismiss-tuning/ref-videos-all/`；`prepare.py` 通过仓库根目录定位读取。预览已有素材不必重新运行准备或拟合脚本。
- 已有分析 JSON 和原始素材来源清单中的绝对路径保留采集时原值；需要查找迁移前本轮产物时，将上述旧目录前缀替换为当前目录，不改写历史证据内容。
- `analysis/apply_*`、`update_*`、`retire_*`、`finalize_*`、`refine_*`、`fix_*` 与 `complete_*` 是阶段操作脚本，可能覆盖参数、代码或记录，保留作过程追溯；不能把它们当成日常验证命令批量重跑。设备脚本会调用 ADB，只在用户授权对应设备测试时使用。

现有环境使用 NumPy、SciPy、Pillow、OpenCV、ModernGL、PySide6，研究抓取另外使用 requests 和 pypdf。视频导出使用 `C:/ffmpeg/bin/`；需要支持计算着色器的桌面 OpenGL。脚本入口以当前目录定位本轮资源，不依赖调用者工作目录。

照片对照仍使用每个参考各自拟合的释放和流场参数，Android 使用通用参数；固定同一参数的跨素材验证尚未完成。照片相似度不能作为通用模型已经达标的证据，详见上级目录的 `followups.md`。
