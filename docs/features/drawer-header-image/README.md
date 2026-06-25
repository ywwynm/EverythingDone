# Drawer Header Image（抽屉/统计头图）

让"Drawer Header Image"——同时显示在导航抽屉与统计界面顶部的那张用户自选头图——能用 DetailActivity 那套稳健流程选图，并支持调整比例与裁切区域（平移+缩放），裁切对两个界面共享同一套。

## 范围

- 修复 SettingsActivity 选本地图却误报"不支持网络图片"的问题（根因：只用 `getLocalPathName`，content URI 解析不出路径就放弃）。
- 新增裁切编辑：可调宽高比（连续滑块，默认 16:9，范围 [0.5, 2.708]）+ 平移 + 缩放，复用 `ThingCardCropEditorView` 与 `MediaCropAppearanceDialogFragment`。
- 同一套比例+裁切同时驱动抽屉头图与统计头图（**单一共享**，非 per-presentation）。
- 头图支持动图（GIF/动态 WebP）逐帧裁切播放；裁切编辑器内只显示单帧。

## 关键术语

见根目录 `CONTEXT.md`：**Drawer Header Image**、**Drawer Header Image Crop**。

## 文档

- `decisions.md` — 逐条设计决策（grill 会话结论）
- `plan.md` — 实现方案与步骤
- `execution.md` — 落地进度与笔记
- `docs/adr/0008-drawer-header-image-single-shared-crop.md` — "单一共享裁切"决定（与项目 per-presentation 惯例相反）
- `debug-updates/` — 阿里云 debug 发布日志
