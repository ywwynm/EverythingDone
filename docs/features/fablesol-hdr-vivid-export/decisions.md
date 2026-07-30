# FableSol HDR Vivid 视频导出决策

## D1（2026-07-30）HDR Vivid 作为独立技术 initiative

HDR Vivid 复用 FableSol 通用导出引擎，但它的 Android API 边界、T.35 载荷、HEVC SEI 注入、
MP4 `cuvv`、联盟工具与认证要求都独立于现有四种 HDR 格式。因此建立单独 feature 目录；
通用导出架构继续由 `fablesol-video-export` 管理，只有可复用的结论才回填到通用文档。

## D2（2026-07-30）采用应用管理的 HDR Vivid 码流创作

Android 公共 API 没有 HDR Vivid profile，也没有写入 `cuvv` 的 `MediaMuxer` 接口。不能把
HDR10+ 的 `PARAMETER_KEY_HDR10_PLUS_INFO` 改作他用。

本功能采用应用管理链路：

`FableSol 线性场景 → BT.2020/PQ → P010 → HEVC Main10 → 应用注入 HDR Vivid SEI → MP4 写入 cuvv`

硬件编码器只负责普通 HEVC Main10。HDR Vivid 身份由应用生成的动态元数据、码流承载和容器
标识共同成立。

## D3（2026-07-30）第一阶段使用统计信息模式

首个竖切只写标准允许的统计信息模式，不先实现 Tone Mapping 曲线参数：

- 先验证逐位语法、逐帧关联、SEI 与 `cuvv`；
- 复用现有整片预分析和 HDR10+ 统计基础；
- 不把“结构能识别”误写成“已获得完整 HDR Vivid 画质收益”。

完整曲线参数、时域平滑和主观画质调节在标准码流与终端识别均通过后继续实现。

## D4（2026-07-30）首版只采用 PQ/HEVC Main10，并保留 HDR10 回退

首版固定使用 BT.2020/PQ、10-bit、limited range 和 HEVC Main10。PQ 基础层继续携带现有
ST 2086、MaxCLL、MaxFALL 与 CICP 信息；不识别 HDR Vivid 的播放端应能够忽略未知动态元数据，
按 HDR10 基础层播放。

暂不把 HLG、AVS2/AVS3、H.264 或其它标准允许的组合纳入第一阶段，避免在尚未验证基本承载前
同时扩大编码和兼容性矩阵。

## D5（2026-07-30）三层证据分别验收

HDR Vivid 支持不能只靠编码成功认定，必须分别记录：

1. **结构证据**：载荷可解析、每帧关联正确、MP4 存在正确 `cuvv`；
2. **终端证据**：认证终端实际识别并进入 HDR Vivid／菁彩 HDR 模式；
3. **回退证据**：非 HDR Vivid 终端正常按 HDR10 播放。

结构检查在开发阶段是实现门禁；正式产品发布策略仍遵循通用导出偏好，不以应用自身无法完成
附加解析为由删除已经完成编码的用户产物。

## D6（2026-07-30）首版只允许显式选择，不加入 HDR 自动档

HDR Vivid 的动态元数据与 `cuvv` 可由应用侧生成，因此“具备 HEVC Main10 编码器”很容易通过
结构探测；但这还不能证明目标播放设备、相册与分享平台会按 HDR Vivid 处理。首版在设置页中
仅作为显式格式出现，不改变既有 `HDR（自动）` 的选择顺序。完成 HDR Vivid 真机播放和主要
分享链路验证后，再单独决定是否加入自动档及其排序。

## D7（2026-07-30）版本 1.0 使用统计信息模式，场景统计在每帧重复

首版按 T/UWA 005.2-1 的版本映射使用：

- `itu_t_t35_country_code = 0x26`；
- `terminal_provide_code = 0x0004`；
- `terminal_provide_oriented_code = 0x0005`；
- `system_start_code = 0x01`；
- `tone_mapping_enable_mode_flag = 0`；
- `color_saturation_mapping_enable_flag = 0`；
- 末尾以 0 补齐到字节边界。

FableSol 是一个连续动画场景，T/UWA 005.1 允许四项统计描述“一帧或一个场景”。因此首版沿用
完整场景预分析，将同一份场景统计写入每个视频访问单元；它既满足每帧携带元数据的传输要求，
也避免 60/120 fps 下逐帧元数据抖动。

## D8（2026-07-30）HEVC Main10 同时保留 P010 优先与 Surface 后备

HDR Vivid 不依赖 Android 的逐帧私有参数接口：应用在编码输出与封装之间注入 SEI。因此它不必
像 HDR10+ 一样强制 P010 输入。候选仍按现有规则优先应用自有 P010；设备不公开标准 P010 时，
允许同格式退到 HEVC Main10 Surface，动态元数据继续由应用在输出侧注入。

## D9（2026-07-30）进入完整曲线阶段并复用 HDR10+ 的内容分析

用户确认 HDR10 基础层回退正常，但当前没有 HDR Vivid 终端可做格式识别，因此先继续完成编码
侧画质能力，终端识别证据保持为未完成项。

完整曲线阶段按以下边界复用现有实现：

- 复用最终线性 BT.2020 画面、GPU 逐帧直方图、MaxRGB 统计、完整 CFD、参考显示峰值和
  HDR10+ 内容感知目标曲线；
- HDR Vivid 独立完成 T/UWA 005.1 Base Parameters 拟合、3Spline 参数生成、量化、逐帧
  T.35 载荷和独立校验，不复用 ST 2094-40 的载荷字段；
- 离线预分析按亮度分布检测硬场景变化，并为连续内容设置有限的最大分析段长度；硬场景边界
  清空时域历史，连续分析段之间对最终元数据元素做短窗平滑，避免参数闪烁；
- 编码器根据输出样本的 PTS 从预分析时间线选择载荷，以兼容 B 帧重排，不能再持有一份全片
  固定载荷。

## D10（2026-07-30）量化后拟合 Base Curve，并按软硬边界分别处理历史

完整曲线使用以下确定规则：

- 以场景 `max(MaxSCL)` 作为 HDR Vivid Base Curve 的源端点，目标端点为用户选择的参考显示峰值；
- 复用 HDR10+ 内容感知目标映射作为拟合目标，但在 HDR Vivid 的 `m_p`、`m_m`、`m_n`、
  `m_a` 参数族中搜索，并对**量化后的参数**执行单调性与端点误差门禁；
- 使用 `base_param_Delta_mode=3`，让目标显示峰值与参数集匹配时直接采用这组曲线，避免接收端
  再按其它 Delta 模式改写；
- 两段 3Spline 按 T/UWA 005.1 附录 A.3 的 `U=6`、`N=8` 推荐流程，从完整场景直方图选择
  低亮区和最高亮区阈值及强度；
- 硬场景变化由相邻帧平均 PQ 与多分位分布共同判定，边界处清空时域历史；连续内容按有限
  最大段长或累计漂移形成软边界，并在短窗口内逐元素平均元数据；
- 默认最短场景约三分之一秒、最长分析段五秒、软边界过渡约四分之一秒，均按实际帧率换算；
- `variance_maxrgb_pq` 严格按附录 A.7 在线性域计算 P90−P10 后再整体转 PQ。

## D11（2026-07-30）显式展示曲线参数，并把 HDR Vivid 放在色彩模式末位

HDR Vivid 的完整曲线与 HDR10+ 共享参考显示峰值和高光起点这两个创作参数。设置页、导出
摘要和完成结果必须按“该格式是否使用应用创作的 Tone Mapping 曲线”判断，而不能再只判断
HDR10+；HDR Vivid 选中时同时显示：

- 参考显示峰值滑杆及快捷值；
- 高光起点；
- 与两种格式一致的动态元数据参数说明。

色彩模式的总体顺序调整为：

`HDR（自动）→ HDR10+ → 杜比视界 8.4 → HDR10 → HLG → 原生 SDR → SDR 色调映射 → HDR Vivid`

其中 HDR Vivid 的名称按地区区分：中国大陆、香港、台湾显示“国产标准”的本地字形，其它
语言／地区显示本地化后的“中国标准”。这只是显式选项的产品排序与名称变化，
`AUTO_ORDER` 仍保持 HDR10+、杜比视界 8.4、HDR10、HLG 四项。

## D12（2026-07-30）三星 Z Fold4 不显示 HDR Vivid 是编码输入硬边界

在三星 Z Fold4 `SM-F9360`（序列号 `RFCT90LSFGT`、Android 16）上，以 Debug 更新
`202607301353` 连续完成两轮真实能力探测；第二轮先停止应用、单独删除
`fablesol_hdr_export_capability.xml` 能力缓存再重新探测，用于排除旧缓存和单次 codec
资源争用。两轮结论一致：

- HDR10 与 HLG 只在 `c2.android.av1.encoder` 的 60 fps 软件 AV1 Main10 通路通过；
- `c2.qti.hevc.encoder` 虽广告 HEVC Main10 和所需画布的 60／120 fps 能力，但没有列出标准
  P010 ByteBuffer 输入；应用的独立 P010 验证也明确失败；
- EGL 共找到 4 个 RGB10_A2 配置，其中带 `EGL_RECORDABLE_ANDROID` 的配置为 0；
- HDR Vivid 的 HEVC Main10 VBR 60／120 fps 均在编码阶段失败，CQ 两档没有合法候选；失败
  尚未到达 HDR Vivid SEI 或 `cuvv` 校验。

因此该设备没有能把应用的 10-bit PQ 画面送入 HEVC Main10 编码器的通路。HDR Vivid 不出现
是能力矩阵的正确结果，不是格式排序、缓存或动态元数据实现缺陷；不得退到 8-bit，也不得把
唯一可用的软件 AV1 HDR 通路标成 HDR Vivid。

T/UWA 005.2-1-2026 第 7.1 节规定了 AVC/H.264、HEVC/H.265、VVC/H.266 以及 AVS2／AVS3
的 HDR Vivid ES 承载，没有规定 AV1 承载。当前产品阶段继续只生成已经完成端到端验证的
HEVC Main10 版本；即使以后增加 AVC 或 VVC，仍须先有设备实际提供的 10-bit 编码输入和完整
码流／容器验证，不能仅凭 Profile 广告开放。

依据：

- [T/UWA 005.2-1-2026《应用指南 系统集成》](https://www.theuwa.com/upload/ueditor/file/20260430/1777529660131503/c89a2110e375fa07f4037de1a9fd7c6e.pdf)
  第 7.1 节；
- [Android `COLOR_FormatYUVP010` API](https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#COLOR_FormatYUVP010)；
- [Khronos `EGL_ANDROID_recordable`](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_recordable.txt)。
