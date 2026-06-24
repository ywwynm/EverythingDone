# 动图播放 — 会话记录

## 2026-06-24 — 设计访谈 + 实现 + 发布

- 经 grill-with-docs 访谈逐项敲定:候选判定按扩展名 `{gif, webp}`;全屏预览走 Drawable 播放;详情定制模式与卡片用自定义 Glide `MediaCropTransformation` 复用 `renderCrop` 逐帧裁切——只对 Animated Image 源开分支,静态烘焙路径不动;裁切 dialog 与 widget 配置页预览保持静态;滚动性能用 M1(仅靠 Glide 屏外暂停)。
- 立 [ADR-0007](../../adr/0007-animated-image-playback-scoped-per-surface.md);根目录 `CONTEXT.md` 增加 **Animated Image** / **Animated Playback** 术语。
- 实现六处改动(`AttachmentHelper`、新 `MediaCropTransformation`、`ImageViewerActivity`、`ImageAttachmentAdapter`、`BaseThingsAdapter`、`BaseThingWidgetConfiguration`),`:app:assembleDebug` 通过,发布 debug update `202606241041` 到阿里云待用户真机测试。
- 第一轮真机测试反馈两点:#1 widget 选择记事页 GIF 不播放(误把动画开关加在了选择列表上,已修,发布 `202606241206`);#2 列表 widget 图片比例不对——经用户隔离确认**普通图片也一样**,属列表 widget 既有通用比例问题、与本功能无关,记入 [remote-thing-card-appearance/followups.md](../remote-thing-card-appearance/followups.md),按用户意见暂不处理。
- 用户确认全部正常后,代码改动连同文档(含本 sessions、`memory/preferences.md`)一并提交到 kotlin 分支(未推送);按用户要求多次撤回重提,最终 commit 仅署名 Claude Opus 4.8(`preferences.md` 的示例里同时列出 Claude 与 GPT 两种格式备查)。
