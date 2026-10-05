# 2026-10-04 历史缓存清理

用户要求清理本功能再次在 E 盘积累的大量缓存，只保留必要内容。本次仅删除限定目录中可重建的历史图像帧和逐粒子状态数组，未改动画代码、共享资源或 Android 实现。

## 占用与结果

- 模拟器目录清理前约 1,171.623 GiB，清理后 41.408 GiB（约 44.46 GB）。
- 删除 4,446 个文件，逻辑大小 1,130.215 GiB（约 1.214 TB），没有删除失败。
- E 盘实际释放 1,204,589,621,248 字节（约 1.20 TB）；清理前可用约 201.23 GB，清理后约 1.406 TB。逻辑文件大小与磁盘实际释放量分别记录。
- 主要原因是数千个历史变体保留完整未压缩帧，编码对照又为同一成片保留一份解码数组；这些不是当前实时预览的运行输入。

## 删除范围

以下路径均相对于 `tuning-0909-gpt6/`，仅删除已核实的数据数组，未删除整个目录。

| 范围 | 文件数 | 逻辑大小 GiB |
| --- | ---: | ---: |
| `analysis/match-cache/handoff-review`，排除三个关键版本 | 2,512 | 779.525 |
| `analysis/match-cache/codec` 的重复解码数组 | 913 | 186.692 |
| `cache` 的历史完整渲染帧 | 161 | 58.771 |
| `archive` 的非必要顶层渲染帧 | 148 | 52.909 |
| `analysis/new-references-0926/render-cache` | 51 | 17.727 |
| 边缘流动、连续性、帧差、密度、表面交接等旧实验生成数组 | 661 | 34.590 |

最后一项包括 `edge-flow-support`、`motion-continuity`、`frame-difference`、`motion-field-extension`、`recorded-filament-trace`、`device-filament-origins`、`density-timing`、`front-coherence`、`filament-continuity`、`surface-transfer`。保留其中以参考／设备／原始输入命名的采样帧；模型权重、场、材料和参数数组未按扩展名一并删除。

## 保留内容

- 十二段原片逐帧库、原始视频、`new-references-0926` 的输入与测量，以及全部 `assets/`。
- 当前 `analysis/match-cache/takeover-20261004/` 的预渲染成片、材质对照与关键帧；实时服务需要的参考库和素材。
- 所有源码、参数、拟合权重、记录、MP4、JPEG、PNG；`configs`、`spark-transport`、小型历史基线。
- `handoff-review/V104m`、`V130`、`V134`：用于追溯逐段拟合、后续随机化与密度方案的必要完整帧，合计约 9.63 GiB。
- `archive/observed-approved`、`before-rim-flow`、`handoff-UIJzSD-baseline`：认可控制组、必要回归基准和交接素材。
- 当前 `tmp/dialog-takeover-20261004` 的研究输出和软件渲染备用环境；`tmp/particle-dismiss-tuning` 中的原始参考与 `tmp/particle-perf` 设备证据。

## 执行与复核

清理前确认没有离线渲染任务写入；两个实时服务继续运行。每个删除对象均校验绝对路径、父目录非链接、大小／修改时间及 Git 未跟踪且被忽略。使用 PowerShell 的 `Remove-Item -LiteralPath` 逐文件删除，没有递归删除整个目录。

清理后核对全部 41,634 个保留文件的大小／修改时间，以及其中 10,428 个源码、参数等文件的 SHA-256，均未变化。8652 与 8653 的 `/api/status`、16 场景清单、当前 JPEG、`/review/`、`/review/material/index.html` 共十个 HTTP 检查通过，JPEG 可正常解码。

完整盘点、删除清单、执行结果与验证结果保存在仓库本地 `tmp/dialog-cache-cleanup-20261004/` 的 `inventory.json`、`delete-manifest.json`、`deletion-result.json`、`verification.json`。清理脚本仅面向这份已核实清单，不能将过期清单当作通用清理命令重跑。

## 后续约定

历史文档中的部分 `.npy` 路径已无实体，需要时依据保留代码和参数重新生成；不是所有历史脚本都能直接读取原缓存。后续研究优先留下成片、关键帧和轻量测量，仅保留当前实验及少量关键基准的完整数组，避免再次累积所有变体。本次没有添加定时任务或自动删除逻辑。
