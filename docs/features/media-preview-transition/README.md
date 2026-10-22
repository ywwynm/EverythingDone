# 详情附件与媒体预览的连续转场

2026-10-21 开始实施。详情页附件展开至图片／视频预览，返回时按当前附件身份收回；保留现有 Activity、裁剪、缩放、播放与 HDR 显示能力。

- [需求与验证范围](preferences.md)
- [实现决策](decisions.md)
- [单指上下滑动返回](interactive-dismiss-2026-10-22.md)
- [松手返回的停顿与惯性衔接](return-inertia-2026-10-22.md)
- [返回惯性距离与速度曲线调研](motion-curve-research-2026-10-22.md)
- [弹簧返回的实现与验证](spring-return-2026-10-22.md)
- [实机验证与性能](validation-2026-10-21.md)
- [第二轮延迟、几何与闪烁诊断](diagnosis-2026-10-21-second-pass.md)
- [卓易通清晰度回归](resolution-regression-2026-10-21.md)
- [真实大图展开延迟优化](opening-latency-2026-10-21.md)
- [无内嵌预览的大图展开](opening-without-thumbnail-2026-10-21.md)
- [动态 WebP 裁剪与播放修复](animated-webp-2026-10-21.md)
- [HDR 动态照片播放中返回拉伸](motion-return-stretch-2026-10-22.md)
- [大视频预览图到首帧的黑屏交接](video-startup-2026-10-22.md)
- [后续检查](followups.md)
- 相关功能：[首页详情转场](../thing-detail-transition/README.md)、[附件外观](../detail-attachment-media-appearance/README.md)、[动态照片](../motion-photo-playback/README.md)、[HDR 显示](../hdr-media-display/README.md)。
