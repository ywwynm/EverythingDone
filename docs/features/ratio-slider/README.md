# RatioSlider 比例调节滑条

把"调整图片/视频比例(裁切比例)"的 seekbar 滑条统一成一个共享组合控件
`RatioSlider`,消除目前 4 处各自实现带来的档位、映射、snapping 不一致。

## 状态

设计已收敛 + 全量实现(2026-06-25),`:app:assembleDebug` 通过。**待实机验收**。
实现细节见 [sessions.md](sessions.md)。

## 涉及的 4 处调用点

1. 卡片外观 panel(ThingsActivity)
2. 卡片封面图片/视频裁切 dialog(ThingsActivity)
3. 详情界面图片外观 dialog(DetailActivity)
4. 设置界面抽屉头图裁切 dialog(SettingsActivity)

## 文档地图

- [plan.md](plan.md) —— 背景、收敛设计、迁移落点、验收。
- [decisions.md](decisions.md) —— 逐条决策与理由。

## 相关 feature

- `thing-card-appearance`
- `detail-attachment-media-appearance`
- `drawer-header-image`
- `thing-card-media-target-geometry`
