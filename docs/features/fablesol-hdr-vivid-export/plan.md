# FableSol HDR Vivid 视频导出计划

## 第一阶段：标准码流竖切

- [x] 从官方标准核对统计信息模式的字段、量化、补位规则与 T.35 外层标识。
- [x] 实现独立 HDR Vivid 统计模型和位级载荷生成器。
- [x] 实现 HEVC access unit 解析与 HDR Vivid prefix SEI 注入。
- [x] 实现 MP4 `cuvv` box 生成与安全写入。
- [x] 增加载荷、SEI、emulation-prevention、长度前缀和 MP4 box 单元测试。
- [x] 仅开放显式 HDR Vivid 选择，不改变既有 HDR 自动档。

## 第二阶段：接入 FableSol 导出

- [x] 为请求模型、稳定标识、文件名与格式说明增加 HDR Vivid。
- [x] 在正式编码前从最终线性 BT.2020 场景生成所需统计。
- [x] 将同一连续场景的元数据关联到每个视频 access unit。
- [x] 在发布前完成 `cuvv` 后处理并报告实际产物。
- [x] 让能力矩阵验证完整 HDR Vivid 码流，而不是查询不存在的 Android profile。

## 第三阶段：验证和产品化

- [x] 使用 UWA 元数据提取工具或等价解析器验证所有视频帧；首个 471 帧样本已由 FFmpeg
  独立解析器完成全量检查。
- [ ] 在支持 HDR Vivid 的华为／荣耀终端确认格式识别。
- [x] 在非 HDR Vivid HDR10 终端确认基础层回退；用户已确认首个导出样本回退正常。
- [ ] 验证相册、分享与常见平台是否保留 SEI 和 `cuvv`。
- [ ] 完成显式格式验证后，决定加入 HDR 自动顺序的条件。
- [ ] 确认公开品牌名称、标识和认证要求。

## 后续画质阶段

- [x] 实现完整 Tone Mapping Base Parameters 与两段 3Spline 参数模式。
- [x] 加入逐场景统计、跨帧稳定性、曲线量化门禁与按 PTS 关联。
- [x] 用 FFmpeg 全帧解析完整曲线新样本，确认 471／471 帧均携带 Base Parameters、两段
  3Spline，且 MP4 含合法 `cuvv`。
- [ ] 以低于内容峰值的参考显示峰值导出较长、多明暗段样本，实证非平凡 Base Curve、
  多场景载荷与软边界时域平滑。
- [ ] 补充覆盖完整 3Spline 求值过程的独立接收端模型回归测试。
- [ ] 对比统计信息模式、完整曲线模式、HDR10+ 与 HDR10 的真实终端画质。
