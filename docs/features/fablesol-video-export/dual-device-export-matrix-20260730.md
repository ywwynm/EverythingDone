# FableSol 双机视频导出矩阵验收（2026-07-30）

## 结论

- OPPO PLZ110 与三星 Z Fold4 共完成 23 项基础矩阵、7 项最终回归；全部真实导出成功，音视频
  均可由 FFmpeg 全片严格解码，帧数、PTS、GOP、CICP 与 MP4 `nclx` 一致。
- 23 项基础矩阵里只有 AOSP 软件 AV1 的 HDR VBR 三项出现严重矩形块：OPPO `O09`、三星
  `S02`／`S04`。它们的结构合法，但视觉质量不合格。D191 已把该精确组合的 VBR 从有效能力
  中剔除；最终 APK 的同项均回退 CBR，画面恢复到接近 CQ 基线的质量。
- 没有发现持续或周期性的忽明忽暗。保守阈值标记的少量复核项都只出现孤立音频响应脉冲，
  最坏连续反向步进为 4 次／853 帧，并非反复闪烁。
- HDR Vivid 两项均为 HEVC Main10、BT.2020/PQ、limited range；T/UWA 005 动态元数据覆盖
  853／853 和 427／427 帧，MP4 各有且仅有一个 30 字节 `cuvv`。
- HDR10+ 的 ST 2094-40 元数据覆盖 853／853 帧。杜比视界 8.4 的 `dvvC` 为 Profile 8、
  compatibility ID 4、RPU present、无 EL，RPU 与解析元数据均覆盖 427／427 帧。

## 测试环境与边界

| 项目 | OPPO | 三星 |
|---|---|---|
| 序列号 | `3B1629006YC00000` | `RFCT90LSFGT` |
| 型号 | PLZ110 | SM-F9360（Z Fold4） |
| 测试记事 | `测试音频呀` | `嘿嘿` |
| 附件时长 | 约 7.13 秒 | 约 3.11 秒 |
| 画布 | 1152×1472 | 1152×1472 |

基础矩阵使用 APK SHA-256
`15389addefa164cb102b754fce8f5697fe270750487b7e2f993b51a03727f402`。D191 最终回归使用
`01448a798872922372098e23eca40107316aa3d7ed9c6a57549a9a3d109151e9`，两机安装包一致。
每项测试前完整写入并回读偏好，结束后在 `finally` 恢复原偏好。连接列表里的
`BYZL25052900304881` 不在用户授权范围内，全程未操作。

“各种组合”按 D190 采用能力约束下的风险覆盖矩阵，不对设备明确不可达的连续参数做笛卡尔积。
矩阵覆盖全部实际可用格式、编码器族、60／120 fps、CQ／目标码率、8／10-bit SDR、两种 SDR
映射，并交叉覆盖 B 帧、高复杂度、QP 保护、0.5／2／5／10 秒 GOP、12／24／60 Mbps、
漫反射白、参考显示峰值、高光起点与 HLG 信号范围。

## OPPO 基础矩阵

| ID | 主要组合 | 验收 |
|---|---|---|
| O01 | HDR Vivid、HEVC、120 fps、60 Mbps、0.5 秒 GOP、B 帧、强动态曲线 | 复核通过 |
| O02 | HDR Vivid、HEVC、60 fps、350 尼特白、10 秒 GOP、增强关闭 | 复核通过 |
| O03 | HDR10+、HEVC、120 fps、B 帧、质量保护 | 通过 |
| O04 | 杜比视界 8.4、HEVC、60 fps、HLG 名义范围、24 Mbps | 通过 |
| O05 | HDR10、HEVC、120 fps、800 尼特白、60 Mbps、0.5 秒 GOP | 通过 |
| O06 | HLG、HEVC、120 fps、自动增强范围、B 帧 | 通过 |
| O07 | HLG、HEVC、60 fps、名义范围、12 Mbps、10 秒 GOP | 通过 |
| O08 | HDR10、软件 AV1、60 fps、最高 CQ | 复核通过，画面干净 |
| O09 | HLG、软件 AV1、60 fps、24 Mbps VBR、增强关闭 | 结构通过，旧版视觉失败；最终 CBR 通过 |
| O10 | SDR 原生、HEVC 10-bit、120 fps、0.5 秒 GOP | 通过 |
| O11 | SDR 动态保留高光、H.264 8-bit、120 fps、24 Mbps | 通过 |
| O12 | SDR 稳定保留高光、AV1 8-bit、60 fps、最高 CQ、10 秒 GOP | 复核通过 |
| O13 | SDR 原生、AV1 10-bit、60 fps、12 Mbps VBR | 通过 |

“复核通过”表示自动化仅因三帧脉冲 P99 的保守阈值进入 `review`，完整解码与结构没有错误，
联系表逐帧复核也没有持续闪烁或空间破坏。

## 三星基础矩阵

| ID | 主要组合 | 验收 |
|---|---|---|
| S01 | HDR10、软件 AV1、60 fps、最高 CQ | 通过，画面干净 |
| S02 | HDR10、软件 AV1、60 fps、24 Mbps VBR、增强关闭 | 结构通过，旧版视觉失败；最终 CBR 通过 |
| S03 | HLG、软件 AV1、60 fps、最高 CQ、名义范围 | 通过，画面干净 |
| S04 | HLG、软件 AV1、60 fps、12 Mbps VBR、自动增强 | 结构通过，旧版视觉失败；最终 CBR 通过 |
| S05 | SDR 原生、硬件 HEVC 8-bit、120 fps、0.5 秒 GOP、B 帧 | 通过 |
| S06 | SDR 动态保留高光、硬件 H.264 8-bit、120 fps、24 Mbps、10 秒 GOP | 通过 |
| S07 | SDR 原生、软件 AV1 10-bit、60 fps、最高 CQ | 通过 |
| S08 | SDR 稳定保留高光、软件 AV1 8-bit、60 fps、12 Mbps VBR | 通过 |
| S09 | SDR 稳定保留高光、硬件 HEVC 8-bit、60 fps、12 Mbps VBR | 通过 |
| S10 | SDR 原生、硬件 H.264 8-bit、60 fps、自动码率 | 通过 |

三星软件 AV1 的 HDR 输出为 full range；OPPO 同类输出为 limited range。两种都是编码器真实
选择，AV1 Sequence Header、FFprobe 与 MP4 `nclx.full_range` 在各自产物中逐项一致，因此
不能把三星的 full range 当作错误改写。三星 `S04` 请求自动增强 HLG，但 D139 的 limited-range
闭环前提不成立，最终诚实回退“HLG 名义范围”。

## 格式身份核验

| 格式 | 核验结果 |
|---|---|
| HDR Vivid O01 | 853 帧；T/UWA 005 元数据 853 帧、31 种载荷；一个 30 字节 `cuvv` |
| HDR Vivid O02 | 427 帧；T/UWA 005 元数据 427 帧、16 种载荷；一个 30 字节 `cuvv` |
| HDR10+ O03 | 853 帧；ST 2094-40 元数据覆盖 853 帧 |
| 杜比视界 8.4 O04 | 一个 `dvvC`；Profile 8、Level 11、compatibility ID 4、BL+RPU、无 EL；RPU 427／427 |
| HDR10 | Main10／AV1 Main 10-bit，BT.2020/PQ；静态元数据与完成态全片统计存在 |
| HLG | Main10／AV1 Main 10-bit，BT.2020/ARIB STD-B67；完成态报告实际名义／扩展范围 |
| SDR | BT.709；请求的 8／10-bit、原生／稳定／动态映射均与实际流一致 |

## 时域与画质

基础矩阵的水体主体亮度统计如下；所有比值均按线性化后的同一区域逐帧计算。

| 设备 | 帧间步进 P99 最大 | 单帧步进最大 | 三帧脉冲 P99 最大 | 连续反向步进最大 | 8 Hz 以上能量比最大 |
|---|---:|---:|---:|---:|---:|
| OPPO | 0.822%（O08） | 1.267%（O12） | 0.576%（O12） | 4／853（O01） | 9.27%（O01） |
| 三星 | 0.345%（S02） | 0.415%（S08） | 0.191%（S08） | 0 | 11.48%（S01） |

OPPO O01 的连续反向步进集中在 6.400～6.408 秒附近，O12 集中在
6.383～6.417 秒附近；两者都是一次短促事件，不形成周期。最终 CBR 回归中的 O14 与 S11
也各只有一次连续反向步进，分别在 2.383 秒和 2.017 秒。结合全片联系表，没有发现频繁
忽明忽暗。

空间质量检查先发现旧版 `O09`、`S02`、`S04` 的大块矩形量化，再用 CQ、QP 40、8、
逐类型 8、4、1 和 CBR 做对照。QP=1 仍有块且 OPPO HLG 实际升至约 19.43 Mbps，证明不能
继续用更严 QP 掩盖 VBR 问题。最终 CBR 四组相对同机 CQ 基线结果如下：

| 设备／格式 | PSNR | SSIM |
|---|---:|---:|
| OPPO HDR10 | 59.01 dB | 0.999511 |
| OPPO HLG | 57.29 dB | 0.999291 |
| 三星 HDR10 | 57.14 dB | 0.999393 |
| 三星 HLG | 55.93 dB | 0.999119 |

按原设置关闭 QP 保护重跑的 `O09`、`S02`、`S04`，分别与开启保护的 `O15`、`S11`、
`S12` 产生相同 SHA-256 的视频基本流，且完成页统一显示“恒定码率（设备后备）”。最终决定
见 D191。

## 三星无法导出 HDR Vivid 的原因

三星 Z Fold4 并不是缺少所有 HDR 编码能力：它可通过 `c2.android.av1.encoder` 输出
HDR10／HLG AV1 Main 10。边界在 HDR Vivid 的合法承载与输入通路：

- HEVC Main10 没有公开标准 P010 输入；
- 设备虽有 RGB10_A2 EGL 配置，但没有带 `EGL_RECORDABLE_ANDROID` 的配置，不能把 10-bit
  EGL Surface 交给 HEVC 编码器；
- HDR Vivid HEVC Main10 的 120 fps、60 fps 与 CQ 探测分别在编码器初始化阶段失败；
- T/UWA 005.2-1-2026 的 HDR Vivid ES 承载包括 AVC、HEVC、VVC、AVS2、AVS3，不包括 AV1。

因此不能把三星可用的 AV1 HDR 改标为 HDR Vivid。当前能力矩阵隐藏该不可达格式是正确的设备
边界，不需要放宽代码或伪造格式身份。

## 证据与工具

- 样本根目录：
  `E:\projects\fablesol-export-samples-20260729\matrix-20260730-current`
- 基础验证：
  `validation\oppo\matrix-validation.json`、`validation\samsung\matrix-validation.json`
- 逐帧分析与视觉联系表：
  `analysis\oppo`、`analysis\samsung`
- 最终 CBR 回归：
  `oppo-final-O09-CBR`、`oppo-final-O14-CBR`、`oppo-diagnostic-O15-CBR`、
  `samsung-final-S02-CBR`、`samsung-final-S04-CBR`、`samsung-final-S11-CBR`、
  `samsung-final-S12-CBR`
- 自动化工具：
  `tools/run_export_matrix_via_adb.ps1`、`tools/analyze_export_video.py`、
  `tools/validate_export_matrix.py`

## 查证来源

- [T/UWA 005.2-1-2026《高动态范围视频技术 第 2-1 部分：系统集成 视音频封装》](https://www.theuwa.com/upload/ueditor/file/20260430/1777529660131503/c89a2110e375fa07f4037de1a9fd7c6e.pdf)
- [Android `MediaFormat`](https://developer.android.com/reference/android/media/MediaFormat)
- [Android `COLOR_FormatYUVP010`](https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#COLOR_FormatYUVP010)
- [Khronos `EGL_ANDROID_recordable`](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_recordable.txt)
- [AOSP `C2SoftAomEnc.cpp`](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/codec2/components/aom/C2SoftAomEnc.cpp)
- [AV1 Bitstream & Decoding Process Specification](https://aomediacodec.github.io/av1-spec/)
- [AV1 Codec ISO Media File Format Binding](https://aomediacodec.github.io/av1-isobmff/v1.3.0.html)
