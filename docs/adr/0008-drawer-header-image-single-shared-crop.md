# Drawer Header Image 用单一共享裁切，不按界面拆分（Drawer Header Image uses one shared crop, not per-surface presentations）

**Drawer Header Image**（同时显示在导航抽屉与统计界面顶部的用户自选头图）只存**一套** `Drawer Header Image Crop`——一个比例 + 一个裁切中心 + 一个缩放——抽屉与统计强制同形。**不**像 `Thing Card Media` / `Detail Attachment` 那样按界面（presentation）各存一份。

## 为什么

项目既有惯例是 per-presentation：`ThingCardAppearance` 与 `DetailAttachmentMediaAppearance` 都给同一张图的不同呈现（缩略图 / 侧栏 / 媒体背景 / grid / fullSpan）各存独立的比例与裁切。照搬这套，头图本可以给"抽屉"和"统计"各存一套。

但两者的处境不同：

- Thing Card 的多种 presentation 会在**同一处同时可见**（一张卡上既有缩略图又可能切换侧栏/背景），所以必须能分别取景。
- 抽屉头图与统计头图是**分时查看的两块独立屏幕**，用户一次只看到一个。为同一张图维护两套取景，收益小、却要多一道编辑、并把数据模型从一个对象变成一个 map。

用户的诉求始终是"这就是一张图，同时影响统计界面"。单一共享裁切贴合这个心智模型，编辑一次即可，两处天然一致。

## 取舍

- **放弃**：抽屉与统计各自独立取景的能力（例如抽屉用宽幅、统计用偏方）。若日后需要，需把 `KEY_DRAWER_HEADER_CROP` 从单对象迁成 per-surface map，并加迁移。
- **换取**：更简单的模型（一段 `{ratio,centerX,centerY,scale}` JSON）、一次编辑、以及与"它就是一张图"一致的体验。

## 影响

- 存储为单个 `DrawerHeaderImageCrop`（`KEY_DRAWER_HEADER_CROP`），非 keyed map。
- 两个界面用各自的目标尺寸（抽屉 ~320dp 宽、统计满屏宽）渲染**同一套** crop；因为 `Drawer Header Image Crop` 的 center/scale/ratio 都与分辨率无关，所见即所得在两处一致。
- 与 ADR-0004（Detail attachment 用独立 per-source/per-presentation 模型）方向相反——这是刻意的，原因如上。
- 未来要做 per-surface 头图，从本 ADR 出发即可，无需怀疑当初为何没按惯例拆分。
