# Thing Background 编辑器重构（Thing Background Editor）

重构详情页与首页卡片外观面板里用于改变 Thing 颜色的 `COLOR_EDIT` 型
ColorPicker，替换为带「纯色 / 渐变」两个选项卡、颜色条、RGB/Hex 输入、
随机取色与从世界取色的新编辑器。搜索界面的 `HUE_BUCKET` 型 ColorPicker
不在本次范围内。

## 范围

- 影响：详情页 [DetailActivity](../../../app/src/main/java/com/ywwynm/everythingdone/activities/DetailActivity.kt) 的颜色编辑器、
  首页 [ThingsActivity](../../../app/src/main/java/com/ywwynm/everythingdone/activities/ThingsActivity.kt) 卡片外观面板里的颜色编辑器。
- 不影响：搜索界面的色相筛选 ColorPicker（`Def.PickerType.HUE_BUCKET`）。
- 现有死代码：`Def.PickerType.COLOR_HAVE_ALL` / `COLOR_NO_ALL` 全项目无实例化。

## 现状要点

- 旧 `COLOR_EDIT` ColorPicker 是 `PopupPicker`（PopupWindow）子类，宽 128dp，
  内容为 2 列 RecyclerView，无法承载新设计。
- 数据模型 `ThingBackground` 已支持 `PURE`（单色）/ `GRADIENT`（双色 + 8 方向）
  并以 JSON 持久化，与「纯色 / 渐变」两选项卡天然对应。
- 8 个渐变方向已存在（4 正向 + 4 斜向），独立的
  `GradientOrientationDialogFragment` 仅被颜色编辑器流程调用。
- 「从世界取色」= 既有的 `CameraColorSamplingDialogFragment`（相机取色）。
- RGB/Hex 输入参照「某个时刻」tab 的 `InputLayout`（浮动 label + accent 下划线）。

## 相关全局记录

- 领域语言：`CONTEXT.md` 的 Thing Background / Thing Foreground /
  Thing Background Information 条目。
- 相邻 feature：`docs/features/color-system-migration/`、
  `docs/features/detail-color-sampling/`、`docs/features/thing-card-appearance/`、
  `docs/features/popup-picker-insets/`。

## 文档

- `decisions.md` — 设计决策（grill-with-docs 过程中逐条确认）。
