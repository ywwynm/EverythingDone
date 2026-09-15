# 第一批：功能回归（2026-09-14）

设备与授权同 [phase0-baseline-2026-09-14.md](phase0-baseline-2026-09-14.md)。除应用图标启动外全部是注入
Android 输入系统的真实点击、返回键与连续 MotionEvent；只操作专用记事 `PerfB1-20260914-1`
（以及保存顺序回归自己创建的 `CodexAnimationPerfB1b`），不触碰用户已有记事。

## 1. G0：GPU 输出逐位不变

`ParticleTrajectoryCheck` 改造后同时跑两条对照：

- **消散路径**：参考 = 逐次 Jacobi 的原始求解器且第一批 GPU 各项全关；优化 = 当前配置。61 帧 SHA-256。
- **出现路径**（新增）：同一对照下 `prepareReverse` + 全部 37 个采样帧的 SHA-256。
  2.1（历史合并）、2.4（首生跳过）只在这条路径上生效，消散对照看不到它们。

两个检查在同一进程里串行（`withBaselineGpu` 改的是进程级开关，并行会互相覆盖，
曾经因此得到过一次假通过）。每轮的出现层与消散层各触发一次，即每次配置得到两份结果。

| 设备 | 配置 | 消散 61 帧 | 出现 37 帧 |
| --- | --- | --- | --- |
| OPD2515 / Adreno 829 | 全部关闭（基准自检） | 0 差异 ×2 | 0 差异 ×2 |
| OPD2515 | 大 tile BAND=12 | 0 差异 ×2 | 0 差异 ×2 |
| OPD2515 | 大 tile BAND=16 | 0 差异 ×2 | 0 差异 ×2 |
| OPD2515 | 仅首生跳过 | 0 差异 ×2 | 0 差异 ×2 |
| OPD2515 | **最终发布配置** | 0 差异 ×2 | 0 差异 ×2 |
| SM-S9180 / Adreno 740 | 大 tile BAND=12 | 0 差异 ×2 | 0 差异 ×2 |
| SM-S9180 | 大 tile BAND=16 | 0 差异 ×2 | 0 差异 ×2 |
| SM-S9180 | **最终发布配置** | 0 差异 ×2 | 0 差异 ×2 |
| 两台 | 历史合并（2.1） | **不一致**（详见结果文档） | **不一致** |
| 两台 | 四步合并（2.5 诊断变体） | **不一致** | **不一致** |

不一致的两项已分别撤回与删除。

## 2. 单元测试

`:app:testDebugUnitTest`：**963 项，1 失败，1 跳过**。失败项是既有的
`FableSolCurveFairnessTest.glVertexBuilderUsesTheSharedFairCurvesAndProjectedRowRepair`（音频海浪动画），
与本批无关，本批没有改 `fablesol` 下任何文件。

新增 / 扩充：

- `ParticleBatchOneTest`（7 项）：播放时钟的目标相位（120 Hz 每两拍取一次、一次迟到不改相位）、
  时长缩放补偿的恒等与压缩、涟漪标称寿命常量、历史合并的着色器变换（正文逐字不变 + 五分量写入与
  `history.comp` 一致）、宏注入必须落在 `#version` 之后。
- `ParticleDismissShaderCompileTest`：新增大 tile 的 BAND=8/12/16 三档与历史合并变体的实际 GLSL 编译。

## 3. 动画层覆盖与清理（`ParticleCoverageProbeActivity` + `verify_dialog_coverage.py`）

OPD2515 九种形态全部通过（脚本内含断言：必须实际创建出现层与消散层、GPU 日志出现完成、窗口最终移除）：

| 形态 | 结果 |
| --- | --- |
| plain / transparent / hidden-surface / texture / surface | 出现 + 消散层均创建，GPU 完成，窗口移除 |
| panel（页面内面板） | 同上 |
| floating（透明宿主） | 同上，宿主等动画结束 `hostWaitMs=2083` |
| **surface-quick（准备中快速关闭）** | 出现层在 127 ms 内释放、消散正常播完并移除，无残留 |
| finish-during-capture | 同上，`hostWaitMs=2098` |

SM-S9180 复跑了 surface-quick，同样正常清理（出现层 187 ms 释放，消散完整）。

## 4. 独立 Surface 交接像素回归

最小复现：真实移除独立 Surface、保留原 Window，比较「保护画面」与「撤掉保护的对照」。

| 设备 | 场景 | 保护后 > 2/255 的像素比例 | 保护后最大通道误差 | 无保护对照的变化比例 |
| --- | --- | --- | --- | --- |
| OPD2515 | 海浪测试窗（surface-handoff） | **0** | 1/255 | 98.5% |
| OPD2515 | 真实音频播放窗（audio-play-handoff） | **0** | 1/255 | 98.2% |
| SM-S9180 | 海浪测试窗 | 9.7e-06 | 3/255 | 98.7% |
| SM-S9180 | 真实音频播放窗 | 8.2e-06 | 3/255 | 98.0% |

与已发布版本记录的口径一致（无保护约 98%、保护后 > 2/255 为零）。三星上有约百万分之十的像素
落在 3/255，仍在脚本自带的 < 0.1% 判据内。

## 5. 新建保存的三阶段顺序

`device_creation_order.py`（真实工具栏保存 + `NewItemAppearanceProbeActivity` 逐帧观察）：

```
{"title": "CodexAnimationPerfB1b", "sawOverlay": true, "sawPresentedEnd": true,
 "earlyVisibleFrames": 0, "overlappingItemAnimationFrames": 0,
 "firstVisibleMs": 2298, "firstOverlayMs": 1415, "passed": true,
 "initialCount": "2 folders, 17 things", "finalCount": "2 folders, 18 things"}
```

`earlyVisibleFrames=0` 表示完整卡片一帧都没有早于粒子出现；`overlappingItemAnimationFrames=0`
表示腾位动画与粒子不重叠。顺序「腾位 → 粒子 → 完整卡片」成立。

## 6. 左滑

- **按住跨过原点再拉回不崩**：右边缘按下 → 左拖到 60% 宽 → 右拖越过原点 24 px → 再回到 60% → 回原点
  抬手（CANCEL）。两台设备上进程 pid 不变、记事仍在、记事数不变。
  （越过原点的幅度必须小：再往右会被识别成右滑「开始做」，那是另一条业务路径。）
- **60% → 20% → 60% 同相位画面**：同一次连续手势里两次停在 60%，各截一张图比较。
  同一次停顿内的两张图在状态栏以下**逐像素相同**（最大通道差 0），说明停住时画面确实是静止的；
  但两次「回到 60%」之间仍有约 0.7% 的像素不同（最大通道差 237，集中在粒子云内）。
  **这不是本批引入的**：把第一批全部开关关掉（等价于改动前行为）重跑，同一指标是约 1.0%。
  即现状本来就不是严格的同相位可重入，本批略好一点。已记入 followups。

## 7. 弹窗四条关闭路径 + 残留检查

OPD2515，每条路径关闭后等 1.6 s 再查：

| 路径 | 弹窗关闭 | 残留 `ParticleDismissGl` 线程 | 进程 |
| --- | --- | --- | --- |
| 返回键 | 是 | 0 | 未重启 |
| 点窗口外 | 是 | 0 | 未重启 |
| 取消按钮（调色弹窗） | 是 | 0 | 未重启 |
| 确定按钮（调色弹窗） | 是 | 0 | 未重启 |

## 8. 连开关 10 次 × 3 轮的内存与线程

OPD2515，同一详情页连续开关添加附件弹窗 30 次：

| 采样点 | Graphics（KB） | `ParticleDismissGl` 线程 |
| --- | --- | --- |
| 基线 | 41232 | 0 |
| 第 1 轮后 | 41220 | 0 |
| 第 2 轮后 | 41228 | 0 |
| 第 3 轮后 | 41228 | 0 |
| 结束等待后 | 41228 | 0 |

Graphics 内存没有持续增长，动画层线程每次都完整退出。

## 9. 观察器与探针的适配

动画层改挂 `ViewGroupOverlay` 之后，遍历 DecorView 子节点再也找不到它。原来这样枚举的五处
（透明宿主的延迟 finish、debug 性能观察器、覆盖/重复/过渡/新建四个 debug 探针）全部改查
`ParticleDismissOverlay.activeIn(activity)` 登记表。本文各项回归就是在改完之后跑的
（`overlays` 计数正常、保存顺序探针重新拿到结果），可作为改动生效的证据。
