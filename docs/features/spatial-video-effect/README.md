# 空间视频效果

状态：静态空间照片效果之后的后续能力，暂不纳入 v1（2026-07-31）。

## 目标

让 Animated Image、Motion Photo 或视频附件在保持原时间线播放的同时，根据用户倾斜或拖动视点
呈现跨帧稳定的交互式 2.5D 景深与视差。

领域术语为 **Spatial Video Effect**。它只表示 EverythingDone 内的交互呈现：

- 不导出 Apple Spatial Video、双目视频或其它新媒体文件；
- 不替换原动态媒体，也不新增附件类型；
- 不能把相邻帧当作互不相关的静态图片独立处理；
- 必须保持深度尺度、轮廓、遮挡和时间戳的跨帧稳定。

## 与空间照片效果的关系

静态 v1 见 [空间照片效果](../spatial-photo-effect/README.md)。空间照片效果只需生成并复用一张深度图；
空间视频效果还需要时序深度、解码同步、流式缓存和持续实时重投影，因此作为独立功能推进。

## 文档

- [偏好](preferences.md)
- [决策](decisions.md)
- [待办](followups.md)
- [会话记录](sessions.md)
