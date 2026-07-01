# Motion Photo 播放（动态照片）

让各厂商的 **Motion Photo**（动态照片 / 实况照片）在应用内正确播放。

## 一句话

Motion Photo 被建模为**图片附件的一种本性**（同一文件、动态能力按界面分级呈现），复用既有"视频→派生 GIF"与"按界面分级播放"管线；卡片/详情播派生 GIF，全屏保 HDR 静图、按住播真视频。

## 结论

- 建模、检测、播放、范围等设计见 [decisions.md](decisions.md) 与 [ADR-0014](../../adr/0014-motion-photo-as-image-capability.md)。
- 术语 **Motion Photo** 见根目录 [CONTEXT.md](../../../CONTEXT.md)。

## 范围（v1）

- **实测声明**：OPPO + 三星（JPEG 与 HEIC）；小米/Pixel 架构上大概率可用但不声明、不测试。
- **排除**：VIVO（独立视频文件，需权限，见 [followups.md](followups.md)）；拍摄、互通格式转换、动态编辑。

## 文档

- [decisions.md](decisions.md) — 七项设计决定与理由（D1–D7）
- [plan.md](plan.md) — 设计概览与验收标准
- [execution.md](execution.md) — 分阶段实现 / 验证清单
- [followups.md](followups.md) — VIVO、HEIC box 精解、小米/Pixel 扩展等待办
