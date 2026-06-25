# Thing Background 编辑器重构 — 实施计划

依据 [decisions.md](decisions.md) 与 [ADR-0005](../../adr/0005-thing-background-editor-color-model.md)。
硬性约束：**控件间距/尺寸全部写在 XML 或 dimens，不在 kt 里设置**。

## 新增文件

### 自定义 View（`app/src/main/java/com/ywwynm/everythingdone/views/`）

- `ColorSpectrumBar.kt`：黑→彩虹→白复合渐变颜色条 + 可拖 handle（内显真实色）。
  - `setColor(Int)` / `getColor(): Int`；拖拽时 `t→color`（沿曲线）；外部赋色时 `color→t`（采样最近点）。
  - `onColorChanged: (Int, fromUser: Boolean) -> Unit` 回调。
  - 配色曲线与采样在 `BackgroundUtil`（或本类伴生）实现：`spectrumColorAt(t)`、`nearestSpectrumT(color)`。
- `ColorAreaView.kt`：一个色区 = `ColorSpectrumBar` + 随机图标 + 从世界取色图标 + 一行 R/G/B/Hex。
  - 内部唯一 `currentColor` + 防回环标志；任一输入改之，单向回刷其余视图 + tint 两图标。
  - `setColor` / `getColor`；`onColorChanged`；`onRequestPickFromWorld`（宿主弹相机）。
  - RGB/Hex 复用 `InputLayout` 样式（浮动 label + accent 下划线）。
- `ThingBackgroundEditor.kt`：选项卡 + 纯色页（预置色 + 1 个 `ColorAreaView`）/ 渐变页（8 方向 + 2 个 `ColorAreaView`）。
  - 维护「纯色工作态」「渐变工作态（起始/结束/方向）」；切 tab 接力、不重随机。
  - `setBackground(ThingBackground)` / `getBackground()`；`onBackgroundChanged(ThingBackground)`；
    `onRequestPickFromWorld(slot)`（slot=纯色/起始/结束）由宿主接相机。

### Fragment（`.../fragments/`）

- `ThingBackgroundEditorBottomSheet.kt`：`BottomSheetDialogFragment`，承载 `ThingBackgroundEditor`，
  外观类似卡片外观面板（圆角抬升）。对外 `setInitialBackground` / `setOnBackgroundChangedListener` /
  `setOnPickFromWorldListener` / `setOnDismissCommit`。

### 布局（`app/src/main/res/layout/`）

- `view_color_area.xml`：`<merge>`，ColorAreaView 用——颜色条行（条 + 两圆形图标）+ R/G/B/Hex 一行。
- `view_thing_background_editor.xml`：`<merge>`，编辑器用——选项卡行 + 纯色页容器 + 渐变页容器。
- `fragment_thing_background_editor.xml`：BottomSheet 内容（顶部把手/标题 + 编辑器）。
- 预置色/方向格子复用现有 `color_picker_fab.xml`。

### 资源

- `drawable/`：颜色条 handle thumb；从世界取色图标（优先复用现有相机/取色图标，没有则新增）。
  随机图标复用 `ic_random_color`。
- `values/dimens.xml`：编辑器各间距/尺寸（条高、handle 半径、图标尺寸、行距等）。
- `values/strings.xml`（及各 `values-*`）：`纯色`/`渐变` 选项卡、`起始色`/`结束色`、`R`/`G`/`B`/`Hex`、
  `调整颜色`、`从世界取色`（替换"从相机取色"）、相关 content description。

## 修改文件

- `activities/DetailActivity.kt`：用 `ThingBackgroundEditorBottomSheet` 取代 `COLOR_EDIT` 的 `ColorPicker`；
  打开记 `bgFrom`、实时预览、关闭记一条 `UPDATE_COLOR`；相机取色回流改为喂编辑器、不再单独记 undo；
  删去 `setOnChangeOrientationListener` + `GradientOrientationDialogFragment` 接线。
- `activities/ThingsActivity.kt`：卡片外观面板的颜色编辑改为内联「颜色页」就地切换；
  调整颜色图标进入、返回箭头退出、取消/确认贯穿面板会话、更新草稿；删去面板侧的方向对话框接线。
  搜索的 `HUE_BUCKET` `ColorPicker` 不动。
- `res/layout/panel_thing_card_appearance.xml`：新增「颜色页」容器（标题行含返回箭头 + 内联编辑器），
  与现有外观控件同级、互斥显隐；`animateLayoutChanges` 做高度过渡。

## 删除文件（新编辑器跑通后）

- `fragments/GradientOrientationDialogFragment.kt` 及 `res/layout/fragment_gradient_orientation.xml`。
- 同步移除 DetailActivity / ThingsActivity 对它的 import 与调用。

## 暂不处理（followup）

- `Def.PickerType.COLOR_HAVE_ALL` / `COLOR_NO_ALL` 及 `ColorPicker.kt` 内 `COLOR_EDIT` 分支变为
  未实例化的死代码——见 [followups.md](followups.md)，单独清理。
- "从世界取色" 各语言文案——见 [followups.md](followups.md)。

## 实施顺序

1. `ColorSpectrumBar` → 2. `ColorAreaView` → 3. `ThingBackgroundEditor`
→ 4. 详情页 BottomSheet 跑通 → 5. 首页面板内联颜色页 → 6. 删 `GradientOrientationDialogFragment`
→ 7. `:app:assembleDebug` 修复 → 8. 发布阿里云。
