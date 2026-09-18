# 选择操作的列表移除动画

状态：已实现；顶栏及半透明阴影遮挡修正已通过双设备验证，发布调试版 `202609181424`（2026-09-18）。

为首页选择模式下的完成、删除、恢复、彻底删除补充列表移除动画，支持单个记事、多记事、多文件夹及混合选择。动画对象依据操作结果和当前屏幕可见范围确定；多个消失的卡片作为一组播放粒子动画。

- [功能偏好](preferences.md)
- [已确认决策](decisions.md)
- [调用链分析与实现核对](analysis.md)
- [验证记录](validation.md)
- [后续事项](followups.md)
- [顶栏与半透明阴影遮挡诊断](chrome-occlusion-2026-09-18.md)
- [最新发布日志](debug-updates/update-20260918222400.md)
- 相关功能：[批量选择操作](../selection-batch-actions/README.md)、[滑动完成动画](../swipe-complete-animation/README.md)、[文件夹语义](../thing-folders/use-cases.md)、[粒子性能专项](../particle-animation-performance/README.md)。
