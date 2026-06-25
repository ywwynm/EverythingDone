# Thing Background 编辑器重构 — 执行清单

**状态（2026-06-25）：阶段 1–7 全部实现，`:app:assembleDebug` BUILD SUCCESSFUL，
已发布 debug update `202606251258` 到阿里云，等待用户在真机测试。下方清单保留备查。**

勾选项随实现推进更新。编译统一用 `:app:assembleDebug`（见 `.claude/rules/gradle.md`）。

## 阶段 1 — ColorSpectrumBar
- [ ] `BackgroundUtil`（或伴生）加 `spectrumColorAt(t)` / `nearestSpectrumT(color)` / 加权 RGB 距离。
- [ ] `ColorSpectrumBar.kt`：onDraw 画复合渐变带 + handle（内显真实色 + 描边）。
- [ ] 触摸拖拽更新 `t`→color，回调 `onColorChanged(color, fromUser=true)`。
- [ ] `setColor` 外部赋色：`nearestSpectrumT` 定位 handle，handle 填真实色。
- [ ] 尺寸（条高/handle 半径）走 XML 自定义属性 + dimens。

## 阶段 2 — ColorAreaView
- [ ] `view_color_area.xml`：颜色条行（条 + 随机图标 + 从世界取色图标，圆形 ripple）+ R/G/B/Hex 一行。
- [ ] `ColorAreaView.kt`：唯一 `currentColor` + 防回环标志。
- [ ] RGB 逐字符生效（0–255，InputFilter 钳制）；Hex 满 6 位 `#RRGGBB` 生效；非法/空不生效。
- [ ] 任一输入 → 回刷颜色条 handle + 另两类输入 + tint 两图标 + `onColorChanged`。
- [ ] 随机图标：随机色 → 全链路刷新。从世界取色图标：触发 `onRequestPickFromWorld`。
- [ ] RGB/Hex 用 `InputLayout` 样式，accent = 本区当前色。

## 阶段 3 — ThingBackgroundEditor
- [ ] `view_thing_background_editor.xml`：选项卡行 + 纯色页（预置 5×2 + 1 ColorAreaView）+ 渐变页（方向 4×2 + 2 ColorAreaView）。
- [ ] 预置色：复用 `color_picker_fab.xml`，对号 = 当前色 == 预置；拖/改/随机/取色自动取消勾。
- [ ] 渐变方向：8 格内显当前两色小渐变，选中打勾；两色变则刷新。
- [ ] 维护纯色/渐变两套工作态；PURE↔GRADIENT 接力（结束色随机种子、回纯色取起始）。
- [ ] `setBackground` / `getBackground` / `onBackgroundChanged`；切 tab 实时预览、不重随机。

## 阶段 4 — 详情页 BottomSheet（先跑通）
- [ ] `fragment_thing_background_editor.xml` + `ThingBackgroundEditorBottomSheet.kt`，外观类似面板。
- [ ] DetailActivity：替换 `COLOR_EDIT` ColorPicker；打开记 `bgFrom`、实时预览、关闭记一条 `UPDATE_COLOR`。
- [ ] 相机取色回流喂编辑器、不单独记 undo（沿用相机 dialog 内预览判例）。
- [ ] `:app:assembleDebug` 通过；详情页手测：纯色/渐变/拖条/RGB/Hex/随机/取色/undo。

## 阶段 5 — 首页面板内联颜色页
- [ ] `panel_thing_card_appearance.xml` 加颜色页容器（返回箭头标题行 + 内联编辑器），与外观控件互斥显隐。
- [ ] ThingsActivity：调整颜色图标进入颜色页、返回箭头回外观页、取消/确认贯穿会话、更新草稿。
- [ ] `animateLayoutChanges` 高度过渡；替换面板侧 `COLOR_EDIT` ColorPicker 与方向对话框接线。
- [ ] `:app:assembleDebug` 通过；首页手测：进入/返回/确认/取消/草稿生效。

## 阶段 6 — 清理
- [ ] 删 `GradientOrientationDialogFragment.kt` + `fragment_gradient_orientation.xml`。
- [ ] 移除 DetailActivity / ThingsActivity 对它的 import 与调用。
- [ ] `:app:assembleDebug` 通过。

## 阶段 7 — 发布
- [ ] `docs/features/thing-background-editor/debug-updates/update-<时间戳>.md` 写发布日志（中文）。
- [ ] 调对应 gradle 发布任务并传入该日志文件，发布阿里云。
