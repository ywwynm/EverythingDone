# FableSol HDR Vivid 视频导出

本目录单独记录 FableSol 音频可视化动画导出为 HDR Vivid 视频的调研、决策、实现与验证。

HDR Vivid 与现有 HDR10+、HDR10、HLG、杜比视界 8.4 共用 FableSol 离线重新渲染、应用自有
P010、音视频同步和发布管线，但它没有 Android 公共编码 profile，也不能通过现有
`MediaCodec` 动态元数据参数直接生成。应用需要自行完成以下标准承载：

1. 从最终 BT.2020/PQ 场景测量 HDR Vivid 动态元数据；
2. 将元数据封装为 `user_data_registered_itu_t_t35`，逐视频帧注入 HEVC SEI；
3. 在 MP4 视频轨 `VisualSampleEntry` 中写入 `cuvv` box；
4. 保持 PQ/HEVC Main10 基础层可作为 HDR10 播放；
5. 分别验证标准结构、HDR Vivid 终端识别和非 HDR Vivid 终端回退。

通用视频导出架构仍以
[`fablesol-video-export`](../fablesol-video-export/README.md) 和
[`ADR-0018`](../../adr/0018-fablesol-visualization-video-offline-render.md) 为准。本目录只保存
HDR Vivid 特有的标准、封装、兼容性与产品决策，避免继续扩大通用导出文档。

## 当前阶段

标准码流竖切、完整曲线和 FableSol 导出接线已经完成：

- PQ、BT.2020、limited range、HEVC Main10；
- 逐帧 CFD、逐场景统计，以及完整 Base Parameters 与两段 3Spline；
- 硬场景边界清空历史，软边界按短窗口平均元数据元素；
- 编码器按输出样本 PTS 查询载荷，兼容 B 帧重排；
- 每个 HEVC 视频 access unit 都注入 T.35 prefix SEI；
- 发布前在 MP4 视频 SampleEntry 中写入并回读 `cuvv`；
- JVM 单元测试覆盖统计语义、量化曲线门禁、场景边界、时域平滑、位级载荷、SEI 与 MP4 box；
- 能力探测实际编码一帧，并同时验证 SEI 覆盖和 `cuvv`；
- 设置页仅开放显式 HDR Vivid，不改变 HDR 自动档。

此前统计信息模式的首个 471 帧样本已由 FFmpeg 独立解析器完成全帧检查，HDR10 基础层回退
也已由用户确认。完整曲线新样本
`20260506210537_20260730_210754_903_1_HDRVivid.mp4` 同样通过 471／471 帧检查：每帧恰好
一组 HDR Vivid 元数据，Base Parameters、两段 3Spline 和 `cuvv` 都可被 FFmpeg 独立解析。
不过该样本的参考显示峰值约 1999 nit，高于约 1946 nit 的内容峰值，因此曲线按设计接近
identity；3.925 秒连续动画也只形成一个场景载荷。仍需用低参考峰值、较长且明暗分段明显的
样本验证非平凡压缩曲线和跨场景平滑。

尚未完成的外部证据包括 UWA 工具交叉解析、HDR Vivid 终端识别以及相册／分享平台保留性
测试；当前手上没有 HDR Vivid 设备，且 UWA 工具暂时无法取得。完成这些证据前，本功能不进入
HDR 自动档。

## 标准与工具依据

- [GB/T 46269.1-2025《高动态范围（HDR）视频技术 第1部分：元数据及适配》](https://std.samr.gov.cn/gb/search/gbDetailed?id=3DBA2132857C0D16E06397BE0A0A8119)
  已于 2026-03-01 实施。
- [T/UWA 005.1-2024《高动态范围（HDR）视频技术 第1部分：元数据及适配》](https://www.theuwa.com/upload/ueditor/file/20231201/1701422733982197/1f614389efed723f703e38eeb0943a76.pdf)
  用于核对动态元数据语法与附录 A 的统计量计算。
- [T/UWA 005.2-1-2026《高动态范围（HDR）视频技术 第2-1部分：应用指南 系统集成》](https://www.theuwa.com/upload/ueditor/file/20260430/1777529660131503/c89a2110e375fa07f4037de1a9fd7c6e.pdf)
  用于核对 HEVC T.35 承载、版本映射和 MP4 `cuvv`。
- [UWA 技术资料说明](https://theuwa.com/tech/faq.html)列出了 HDR Vivid 编码／解码参考代码、
  编码转码工具及元数据提取工具；参考代码与技术资料需要按联盟流程申请。
- [FFmpeg HDR Vivid 元数据解析器](https://ffmpeg.org/doxygen/trunk/dynamic__hdr__vivid_8c_source.html)
  用于不依赖 UWA 工具的字段顺序、位宽和全帧解析交叉检查。
