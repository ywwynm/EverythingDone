# Thing Background 编辑器重构 — 待办

技术上可行但暂缓 / 需要并行处理的事项。

## 首版实现后待评估（2026-06-25，等真机测试反馈）

- **首页连续改色的重绑成本**：`updateThingCardAppearanceBackgroundDraft` 在每次编辑器颜色变化
  （含拖拽颜色条的每一步）都会调用 `bindThingCardAppearancePanel()`/`bindFolderCardAppearancePanel()`
  全量重绑面板，连续拖拽时可能偏重。若卡顿，考虑加一条只刷新背景预览与 accent tint 的轻量路径。
- ~~**渐变页高度 + 软键盘**~~：已在 2026-06-25 反馈修正中解决——加入 `MaxHeightNestedScrollView`
  限高滚动容器，渐变页过高/软键盘弹出时可滚动。如真机上滚动手感或封顶高度 `tbe_editor_max_height`
  需要微调，再调整。
- `ColorPicker.kt` 仍保留 `COLOR_EDIT` 分支，但已无任何实例化（只有 `HUE_BUCKET` 在用）；
  可随下面的死代码清理一并裁剪。

## 死代码清理（与本次解耦）

- `Def.PickerType.COLOR_HAVE_ALL` / `COLOR_NO_ALL` 全项目无实例化，`ColorPicker.kt`
  里仍有对应分支。与本次重构无关，单独清理，避免混入本次改动。决定于 2026-06-25。

## 多语言文案

- "从世界取色" 标签：`act_pick_color_from_camera` 在 `values`（英文 "Pick from world"）与
  `values-zh-rCN`（"从世界取色"）已经就位，无需再改。其它 locale（ja/ko/ru/pt/it/hi/fr/es/de、
  zh-rTW/HK）的该 key 是否仍是"从相机"语义需抽查，必要时统一为"从世界"。
- 本次新增字符串（`color_editor_tab_pure/gradient`、`color_channel_r/g/b/hex`、
  `cd_random_color`、`color_editor_back`）只补了 `values` 与 `values-zh-rCN`，
  其余 locale 暂走英文回退，后续可补译。
