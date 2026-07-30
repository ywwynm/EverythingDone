# FableSol HDR Vivid 视频导出会话记录

## 2026-07-30 - 建立独立 feature 并启动标准码流竖切

- 用户确认开始实现，并要求 HDR Vivid 使用独立 feature 目录。
- 建立本目录，明确首阶段采用应用管理的 PQ/HEVC Main10 码流、统计信息模式、逐帧 T.35 SEI
  与 MP4 `cuvv`。
- 当前工作区存在用户同步进行的 FableSol 渲染与 Dialog 改动；首阶段优先新增独立位流、
  SEI、MP4 类与 JVM 测试，避免覆盖并行修改。
- 对照 T/UWA 005.1 与 T/UWA 005.2-1 核准版本 1.0 固定字段、0 补齐规则及 30 字节
  `cuvv` 结构。
- 决定首版只开放显式选择，暂不改变 HDR 自动档。
- 完成统计信息载荷生成、HEVC prefix SEI 注入、Annex B／长度前缀兼容和 MP4 `cuvv`
  安全后处理；前置 `moov` 只在相邻 `free` 足够时原位扩展，尾置 `moov` 直接扩展文件尾，
  不移动 `mdat`。
- 接入正式导出与能力矩阵：动态格式强制完整场景预分析；HDR Vivid 对每个视频样本注入并以
  全覆盖为发布门禁，封装完成后写入并回读 `cuvv`。
- 增加显式 `hdr-vivid` 请求模式、`HDRVivid` 文件标签、HEVC Main10 候选、失败分类及
  13 套 locale 的格式说明；HDR 自动档保持原四种格式顺序。
- 定向 89 项测试通过；全量 571 项单元测试通过（1 项既有跳过）。真机播放、平台保留和
  UWA 工具交叉验证仍作为后续证据，不在本会话使用 ADB。
- `:app:assembleDebug` 与带本 feature 更新说明的 `:app:publishDebugUpdate` 均通过；实验版
  更新代码为 `202607301106`，已发布到项目 Debug 更新通道。

## 2026-07-30 - 独立核验首个导出样本

- 只读检查样本
  `E:\projects\fablesol-export-samples-20260729\20260506210537_20260730_195850_464_1_HDRVivid.mp4`；
  SHA-256 为 `3214D5ED8AF097C5E559D4573E39E6D01B8593F2597E9F93EE919BBA4F76DA44`。
- FFmpeg 独立解析器将 471／471 帧均识别为
  `HDR Dynamic Metadata CUVA 005.1 2021 (Vivid)`，每帧恰好一组，完整解码无警告。
- 基础层为 HEVC Main 10、10-bit 4:2:0、BT.2020 非恒定亮度与 SMPTE ST 2084，且保留
  Mastering Display 和 Content Light Level 静态元数据。
- MP4 `hvc1` 样本项内存在 30 字节 `cuvv` 盒；版本映射为 `1`，终端提供商代码为 `4`，
  面向终端提供商代码为 `5`，16 个保留字节均为 `0`。
- 471 帧的四项 MaxRGB 统计值完全相同。该样本已具备可被独立实现识别的 HDR Vivid
  码流与封装结构，但暂未利用逐帧／逐场景元数据变化；官方工具认证与真实 HDR Vivid
  终端显示仍属于更高一层的兼容性证据。

## 2026-07-30 - 完整曲线、逐场景统计与时域平滑

- 用户确认首个样本的 HDR10 基础层回退正常；当前没有 HDR Vivid 设备，因此先完成编码侧
  画质能力，真机识别仍保持为未完成证据。
- 复用 HDR10+ 的最终线性 BT.2020 GPU 逐帧统计、完整 CFD、场景累积器和内容感知目标曲线；
  HDR Vivid 独立实现 Base Parameters 拟合、两段 3Spline、量化与位流序列化。
- Base Curve 对量化后参数执行单调性及参考显示端点门禁，并使用
  `base_param_Delta_mode=3`；3Spline 按附录 A.3 的 `U=6`、`N=8` 推荐流程从场景直方图
  生成。
- 新增逐场景时间线：硬边界清空历史，连续内容的软边界在短窗口内逐元素平均元数据；正式
  编码按输出样本 PTS 查表，兼容 B 帧重排。
- 按附录 A.7 修正 `variance_maxrgb_pq`：在线性域先做 P90−P10，再整体转 PQ。
- 新增完整载荷位级解析、曲线多动态范围门禁、硬／软场景边界、时域平滑及乱序 PTS 回归；
  全量 577 项 JVM 测试通过，0 失败、0 错误、1 项既有跳过，`:app:assembleDebug` 通过。
- 本轮没有连接设备、没有使用 ADB。已通过 `:app:publishDebugUpdate` 发布阿里云 Debug
  更新 `202607301305`；远端 `latest.json`、远端 APK 与本地发布副本的大小、SHA-256 和
  发布说明均已核对一致。
- 下一份新导出样本需要再用 FFmpeg 全帧核对 Base Parameters、两段 3Spline 与逐场景变化。

## 2026-07-30 - 独立核验完整曲线新样本

- 只读检查
  `E:\projects\fablesol-export-samples-20260729\20260506210537_20260730_210754_903_1_HDRVivid.mp4`；
  文件大小 19,714,313 字节，SHA-256 为
  `83A51F3B3C5CE9FB7EEBB7F1996D37AE4193F90485EF2BBCEF2C4110BE210E56`。
- 视频为 1152×1472、120 fps、HEVC Main 10、10-bit 4:2:0、limited range、
  BT.2020 non-constant、PQ；静态层为 MaxCLL 1948 nit、MaxFALL 160 nit。音视频均由
  FFmpeg 完整解码，0 警告、0 错误。
- 471／471 帧各有且仅有一组
  `HDR Dynamic Metadata CUVA 005.1 2021 (Vivid)`；PTS 从 0 到 352500，步长恒为 750，
  没有重复或倒退。
- 每帧均有一组 Base Parameters 和两段 3Spline。统计量为
  minimum／average／variance／maximum MaxRGB = 1871／2250／2194／3376；目标显示峰值
  代码为 3388。按 PQ 反算分别约为 59.5／150.2／131.5／1945.6 nit，目标约 1998.6 nit。
- Base Parameters 为 `m_p=1638`、`m_m=10`、`m_a=1023`、`m_b=0`、`m_n=10`、
  `K1/K2/K3=1/1/1`、`Delta_mode=3`、`Delta=0`。目标峰值高于内容峰值，因此这组参数按
  标准缩放后接近 identity；两段 3Spline 的 strength code 都是 128，也就是最接近 0 的
  中性修正。
- 两段 3Spline 的原始码值分别为
  `(TH, Delta1, Delta2)=(614,256,563)` 与 `(2711,540,125)`；按标准缩放后，第一段覆盖
  约 0.150～0.350 PQ，第二段覆盖约 0.662～0.825 PQ，后者终点与场景峰值一致。
- 全片只有 1 份唯一 HDR Vivid 载荷。这不是注入失败：该样本只有 3.925 秒，小于默认 5 秒
  场景上限，且连续动画没有越过硬／软边界阈值。它证明完整曲线结构已经进入正式文件，但不能
  实证非平凡压缩曲线或跨场景时域平滑。
- MP4 中只有一个 30 字节 `cuvv`，位于 HEVC sample entry：版本 1、终端提供商 4、面向
  终端提供商 5、16 个保留字节全零。当前 HEVC 流 `has_b_frames=0`，因此本样本也没有实际
  覆盖 B 帧乱序。
- 下一份验收样本应将参考显示峰值设为约 350 nit，并使用超过 10 秒、明暗／强弱段落明显的
  录音；预期能同时看到非 identity Base Curve、多份场景载荷及软边界过渡帧。

## 2026-07-30 - 产品化设置与 OPPO 真机导出验收

- HDR Vivid 选中时新增“参考显示峰值”、快捷参考值和“高光起点”的可见入口；设置摘要与
  导出结果按“是否使用应用创作 Tone Mapping 曲线”统一判断，不再写死为仅 HDR10+。
- 色彩模式中 HDR Vivid 移到两种 SDR 之后，成为整个列表最后一项，同时继续排除在
  `HDR（自动）` 之外。
- 中国大陆名称改为“国产标准HDR Vivid”，香港／台湾使用繁体“國產標準HDR Vivid”；
  其余 10 套 locale 使用本地化后的“中国标准 HDR Vivid”含义。13 套说明同步更新为当前
  Base Parameters、两段 3Spline、逐场景与时域平滑实现。
- 全量 581 项 JVM 测试通过，0 失败、0 错误、1 项既有跳过；`:app:assembleDebug` 通过。
- 按用户明确授权，只连接 OPPO `3B1629006YC00000`（PLZ110，zh-Hans-CN），保留数据覆盖
  安装 Debug APK。从首条 content 为“测试音频呀”的记事进入详情，点击
  `20260729001238.wav` 附件卡片本体而非播放按钮，打开 FableSol 音频对话框。
- 真机设置页实测“国产标准HDR Vivid”位于色彩模式最后；选中后“参考显示峰值”、
  “参考值”和“高光起点”同时可见。使用参考显示峰值 400 nit、高光起点 90%、120 fps、
  HEVC 10-bit 硬件编码和 VBR 成功导出
  `20260729001238_20260730_214623_451_2_HDRVivid.mp4`。
- 样本大小 23,940,457 字节，SHA-256 为
  `B3BCB4C0EA01204614D3789E59411D6BC96629B596FE8DEF7836B6C18AFB1DE4`；视频为
  1152×1472、120 fps、HEVC Main 10、10-bit 4:2:0 limited、BT.2020 non-constant、
  PQ，853 帧、7.108 秒，音视频完整解码无错误。
- 853／853 帧均有且仅有一组 FFmpeg 可识别的
  `HDR Dynamic Metadata CUVA 005.1 2021 (Vivid)`；目标显示峰值代码 2672，按 PQ
  反算约 399.72 nit。每帧均启用 Base Parameters 与两段 3Spline，低参考峰值使 Base
  Curve 明确不再是 identity。
- 载荷共有 31 份唯一值：第 0～599 帧保持第一组参数；第 600～628 帧逐帧平滑；第
  629～852 帧保持第二组参数。边界位于 5.000 秒，过渡持续约 0.242 秒，实证默认 5 秒软
  分段和 29 帧时域平滑生效。
- PTS 从 0 到 639000，帧间恒为 750，没有重复或倒退。MP4 的 HEVC sample entry 内只有
  一个 30 字节 `cuvv`：版本 1、提供商 4、面向终端提供商 5、16 个保留字节全零。四帧
  视觉抽查未见黑帧、冻结或布局异常。
- 该 OPPO 本轮只作为编码导出设备；没有据此声称其显示链路进入 HDR Vivid 播放模式。
- 已通过 `:app:publishDebugUpdate` 发布阿里云 Debug 更新 `202607301353`。远端
  `latest.json`、远端 APK 和本地发布副本大小均为 22,169,088 字节，SHA-256 均为
  `15389addefa164cb102b754fce8f5697fe270750487b7e2f993b51a03727f402`；远端发布
  说明与本轮日志首个 `##` 节的 817 个字符逐字一致。

## 2026-07-30 - 三星 Z Fold4 不可用原因复核

- 按用户授权连接三星 `RFCT90LSFGT`（Z Fold4 `SM-F9360`，Android 16），保留数据覆盖安装
  Debug 更新 `202607301353`。从 content 为“嘿嘿”的记事进入详情，点击
  `20260519011532.wav` 音频附件卡片本体打开 FableSol 对话框。
- 设置页第一轮真实能力探测仅开放 HDR10／HLG 的 60 fps 软件 AV1 Main10；HDR Vivid
  不显示。随后停止应用、备份并只删除 HDR 能力缓存，再重新进入设置完成第二轮探测，结果
  一致，排除旧缓存。
- 新矩阵中 HDR Vivid／HEVC Main10／VBR 的 120 fps 失败为 `IllegalStateException`，
  60 fps 失败为 `CodecException`，诊断串
  `android.media.MediaCodec.error_neg_-2147483648`；CQ 的 60／120 fps 均无候选。
- 同机独立事实为：标准 P010 ByteBuffer 输入不支持；4 个 RGB10_A2 EGL 配置中带
  `EGL_RECORDABLE_ANDROID` 的为 0；普通 HEVC 10-bit 的 HDR10、HLG 与 SDR 同样不能
  通过真实编码，8-bit HEVC／H.264 则可用。这说明断点在 10-bit HEVC 输入，不在 HDR
  Vivid 的 SEI 注入或 `cuvv` 后处理。
- T/UWA 005.2-1-2026 定义 AVC、HEVC、VVC、AVS2、AVS3 的 HDR Vivid ES 承载，没有
  AV1。该机唯一可用的软件 AV1 HDR 通路不能改标成 HDR Vivid；当前不改代码，继续让能力
  矩阵隐藏不可达格式。
- HDR Vivid 相关实现、资源、测试与本 feature 文档已选择性提交为
  `95a3245d7476df79976c01a0448075af44d14406`：
  `feat: add HDR Vivid video export / 新增 HDR Vivid 视频导出`，并使用
  `Co-authored-by: Codex <codex@openai.com>`。提交未包含工作区内同步进行的分享截图、
  Spatial Photo 与 memory 改动。
