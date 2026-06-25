# 决策记录

来源：2026-06-25 grill-with-docs 会话。

## 2026-06-25

1. **术语保留 "Drawer Header Image"**。虽然它同时驱动统计界面头部，用户决定不改名。已在 CONTEXT.md 记录"名字虽叫抽屉头图、实际跨抽屉+统计共享"这层语义。

2. **选图照搬 DetailActivity 流程，不一律复制**。优先 `UriPathConverter.getLocalPathName`（引用原图路径、不复制），解析失败再用 `FileUtil.copyUriToFile` 兜底复制到 `应用私有目录/temp`。原图被外部移动/删除时回退默认图（沿用现有行为）。
   - 纠正：用户遇到的"不支持网络图片"误报，根因就是 `getLocalPathName` 返回 null 而 Settings 没有兜底；兜底复制即修复点本身。

3. **可调宽高比（方案 B）**，不是只在固定 16:9 框里平移缩放。
   - 头图显示高度 = 宽度 × 所选比例；这是为了让裁切所见即所得（界面框比例必须等于裁切比例，否则界面 centerCrop 会二次裁切）。
   - 统计界面所有几何都从 `mHeaderHeight` 派生，改其计算即自动流过 FAB 与顶栏渐隐；抽屉头图 `wrap_content + adjustViewBounds + centerCrop` 自适应、"完成率"浮层 `gravity=bottom` 贴底，版面无需改。

4. **比例+裁切单一共享**（非 per-presentation）。抽屉与统计强制同形，因为二者是分时查看的独立屏幕，不像 Thing Card 需在同一处并存多种取景。与项目 per-presentation 惯例相反，单独立 ADR-0008。

5. **再次调整裁切走独立入口**。Settings 那一行的弹窗改为三项：`默认 / 选择图片 / 调整裁切`（默认状态隐藏"调整裁切"）。"选择图片"后自动进编辑器。

6. **比例控件 = 连续滑块**，默认 16:9（编辑器内 `targetAspectRatio`=宽/高≈1.778），范围复用 `DetailAttachmentMediaAppearance` fullSpan 的 **[0.5, 2.708]**（不抬高下限，给足自由）。

7. **存储与迁移**。`KEY_DRAWER_HEADER` 保持原样存图片来源；新增 `KEY_DRAWER_HEADER_CROP` 存 `{ratio, centerX, centerY, scale}` JSON。老用户缺 crop 键时取默认值，渲染与现状一致，零观感变化。备份随 prefs 自动覆盖。

8. **只收图片 + 动图按 ADR-0007 播放**。选图范围仍只图片。纠正了对"固定裁切→单帧"规则的理解：单帧只针对裁切编辑器、RemoteViews、HDR 基帧、视频缩略图；应用内视图界面（含本头图）通过 `MediaCropTransformation` 逐帧套裁切仍然播放动图。已据此收紧 CONTEXT.md 该条措辞。
