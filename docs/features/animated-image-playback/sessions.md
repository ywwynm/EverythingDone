# 动图播放 — 会话记录

## 2026-10-15：普通滑动与首帧回显

- 补齐普通 DRAGGING／SETTLING 后复现历史请求暂停和 M2 停播；按用户新要求移除，改为真实附着／离屏控制，补齐未附着预取目标的晚交付处理。
- GIF 已裁切首帧与静态封面共用原有界缓存，改善返回时画面；120ms 延迟加载圈经坏 GIF → 慢静态回退红绿验证，首帧像素和内容失效检查通过。
- 三机普通滑动与 97 卡全列表长按往返通过，原数据未变，隔离包已清理。已发布并核验 debug `202610150359`，未创建 Git 提交。详见 [调查记录](../animated-video-cover/regular-scroll-investigation-2026-10-15.md)。

## 2026-10-15：动图内存与回收修正

- 实测逐帧裁切未用 BitmapPool、首页 RecycledViewPool 中的 GIF 仍解码。补齐输出复用和正式回收清理；保留已有预裁切、透明度、颜色、播放和滚动行为。
- 通过 48 组设备像素比较、120 帧复用及真实回收生命周期红绿回归。容量试验和完整三机持续拖动证据归档在 [动态视频封面内存调查](../animated-video-cover/memory-investigation-2026-10-15.md)。

## 2026-06-24 — 设计访谈 + 实现 + 发布

- 经 grill-with-docs 访谈逐项敲定:候选判定按扩展名 `{gif, webp}`;全屏预览走 Drawable 播放;详情定制模式与卡片用自定义 Glide `MediaCropTransformation` 复用 `renderCrop` 逐帧裁切——只对 Animated Image 源开分支,静态烘焙路径不动;裁切 dialog 与 widget 配置页预览保持静态;滚动性能用 M1(仅靠 Glide 屏外暂停)。
- 立 [ADR-0007](../../adr/0007-animated-image-playback-scoped-per-surface.md);根目录 `CONTEXT.md` 增加 **Animated Image** / **Animated Playback** 术语。
- 实现六处改动(`AttachmentHelper`、新 `MediaCropTransformation`、`ImageViewerActivity`、`ImageAttachmentAdapter`、`BaseThingsAdapter`、`BaseThingWidgetConfiguration`),`:app:assembleDebug` 通过,发布 debug update `202606241041` 到阿里云待用户真机测试。
- 第一轮真机测试反馈两点:#1 widget 选择记事页 GIF 不播放(误把动画开关加在了选择列表上,已修,发布 `202606241206`);#2 列表 widget 图片比例不对——经用户隔离确认**普通图片也一样**,属列表 widget 既有通用比例问题、与本功能无关,记入 [remote-thing-card-appearance/followups.md](../remote-thing-card-appearance/followups.md),按用户意见暂不处理。
- 用户确认全部正常后,代码改动连同文档(含本 sessions、`memory/preferences.md`)一并提交到 kotlin 分支(未推送);按用户要求多次撤回重提,最终 commit 仅署名 Claude Opus 4.8(`preferences.md` 的示例里同时列出 Claude 与 GPT 两种格式备查)。
