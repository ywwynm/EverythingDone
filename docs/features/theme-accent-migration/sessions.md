# 主题强调色迁移 — 会话记录

## 2026-06-23 修复渐变 checkbox 的间距与对齐

- `GradientCheckboxDrawable` 新增 footprint（外廓 intrinsic）参数：可见方框仍 18dp，居中放进更大外廓产生留白与对齐偏移；`draw()` 把方框尺寸 clamp 到 `sizePx`（不随外廓放大），`getIntrinsicWidth/Height` 返回 footprint。
- `applyCheckboxAccent` 增 `footprintDp` 参数（默认 `CHECKBOX_DEFAULT_FOOTPRINT_DP=24f`，行为不变）；设置页 `applyGradientCheckBoxes` 传 `CHECKBOX_LABEL_ROW_FOOTPRINT_DP=32f`。ColorPicker（`setCompoundDrawablesRelativeWithIntrinsicBounds`）、Detail、widget 配置走默认值，不受影响。
- 详见 `decisions.md`（2026-06-23）。已编译通过，随 debug update `202606230911` 发布。32dp 为初值，需在设备上对着“?”帮助图标核对中心线对齐后定稿。

## 2026-06-21 第一轮实现

将所有 `app_accent (#FFEB3B)` 引用替换为 Everything-Android 的 accent+accent2 渐变方案 (`#F66048 → #FFAE36, LB_RT`)。

### 新增颜色
- `app_accent` → `#F66048`（原 `#FFEB3B`）
- `app_accent2` → `#FFAE36`（新增）
- `app_accent_representative` → `#FF8040`（新增）

### 新增工具
- `App.defaultAccentBackground` — 集中渐变定义
- `BackgroundUtil.GradientTintDrawable` — ProgressBar 渐变包装器
- `BackgroundUtil.applyProgressBarGradient()` — ProgressBar 渐变
- `BackgroundUtil.applyCheckboxAccent()` — CheckBox 代表色 tint

### 例外（系统单 int API 限制）
- 光标、文本选择手柄、边缘效果

## 2026-06-21 第二轮修复

### CheckBox
`createGradientCheckboxDrawable` 用 `setButtonDrawable` 替换了整个勾选图标，导致勾选标记消失。
改用 `applyCheckboxAccent`（`setButtonTintList`）保持原生 Material 勾选标记，tint 为代表色。

### Settings 蓝色 → 渐变
- `mAccentColor` 从 `blue_deep` 改为 `App.defaultAccentBackground.representativeColor()`
- HelpActivity / SettingsActivity 的 EdgeEffect 同步替换
- 所有 panel 标题 icon 和文字、对话框强调色自动跟随

### Widget 置顶/进行中标记
- `setStickyOrOngoing` 中的 `setAdaptiveIconColor`（黑白自适应）→ `setGradientStickyMarker`（gradient-tinted bitmap）
- 非文件夹模式：accent→accent2 渐变
- 文件夹内模式（待实现）：文件夹颜色

### 开始做事 icon 尺寸
- `initStartDoingTitle` 中 icon 从 intrinsic bounds 改为固定 20dp，避免裁切

### 待跟进
- "提醒可靠性" panel 标题 icon 左侧对齐问题（所有 panel 标题 XML 属性一致，需截图进一步诊断）
- 置顶标记（非 widget）的渐变方向：LB→RT 对于右上角三角形标记，可见区域偏 accent2；可考虑改用专门方向
- 置顶文件夹在 widget 中仍缺少标记

## 2026-06-21 第三轮修复

- 按用户要求重新定义迁移边界：可通过 shader、自绘 Drawable、RemoteViews bitmap、包装 Drawable 呈现渐变的控件，不再使用 `representativeColor()` 作为最终视觉 fallback。
- 设置页所有 panel 标题文字和图标统一使用 `App.defaultAccentBackground` 渐变；提醒可靠性标题 icon 增加视觉左移；开始做事标题 icon 缩小到 18dp，避免裁切。
- 设置页打开的 Chooser、Alert、Loading、PatternLock、TwoOptions dialog 改为传递 `ThingBackground`，使标题、确认按钮、RadioButton、ProgressBar 等沿渐变路径渲染。
- `BackgroundUtil.applyCheckboxAccent()` 改为自绘 checkbox checked-state，选中填充支持纯色和渐变；详情页快速提醒、设置页、widget 配置页 checkbox 均走该路径。
- 详情页右下角开始做事 fake FAB 改用 oval 渐变背景，避免应用渐变后变成方形。
- 记事列表 widget 的置顶/进行中标记改为 RemoteViews bitmap 渲染：根范围使用 app accent 渐变，文件夹范围使用目标文件夹背景；folder item 新增置顶标记。
- Drawer、移动到文件夹 dialog、记事列表 widget 配置范围列表都改用 `ThingsSorter.compareByLocationAndSticky()`，保证每个层级内置顶文件夹排在前面。
- 移除了设置页、统计页、帮助详情等可见 UI 对旧 `blue_deep` 和 `app_accent_representative` 的引用；系统单色边界改用 accent 起始色。
- 验证：`./gradlew.bat :app:compileDebugKotlin` 通过，仅有既有 deprecated override 警告。
- 发布：已创建 `docs/features/theme-accent-migration/debug-updates/update-20260621201553.md` 并通过 `:app:publishDebugUpdate` 发布到阿里云，debug update code 为 `202606211216`。
# 2026-06-21 第七轮修复
- Radio chooser item 的 `drawablePadding` 从 4dp 调整为 8dp，保持 radio 左缘与 dialog 标题文本左缘对齐，同时恢复 radio 图标与右侧文字之间的舒适间距。
- 验证：`./gradlew.bat :app:assembleDebug` 通过。
- 发布：已创建 `docs/features/theme-accent-migration/debug-updates/update-20260621225748.md` 并通过 `:app:publishDebugUpdate` 发布到阿里云，debug update code 为 `202606211458`。

# 2026-06-21 第六轮修复
- Radio chooser item 的左侧 padding 改为 `app_chrome_dialog_title_margin_horizontal`，让 radio 图标左缘重新对齐 dialog 标题文本左缘。
- `FloatingActionButton.setThingBackgroundWithAdaptiveIcon()` 不再给 icon 使用 alpha=1 的纯黑/纯白；亮背景使用 54% 黑，暗背景使用 86% 白。统计页、关于页 FAB 随此修复，首页文件夹内 FAB 也同步使用同一非纯色前景规则。
- 排查选择模式 contextual actionbar：标题、关闭图标、菜单 item 和 overflow 都走同一个 alpha 前景色，并通过 `DisplayUtil.opaqueTintDrawable()` 保留 alpha，不使用纯黑/纯白。
- 记事列表 widget 的置顶文件夹标识从内容行移到 folder item 根 `FrameLayout` 的 `top|end` overlay，紧贴右上角并不再挤占文件夹标题/锁图标布局。
- 验证：`./gradlew.bat :app:assembleDebug` 通过。
- 发布：已创建 `docs/features/theme-accent-migration/debug-updates/update-20260621224335.md` 并通过 `:app:publishDebugUpdate` 发布到阿里云，debug update code 为 `202606211444`。

# 2026-06-21 第五轮修复
- ProgressBar 渐变实现不再包装系统 indeterminate/progress drawable，改为复用视频封面裁切加载层的思路：indeterminate 使用自绘旋转圆弧和 `SweepGradient`，horizontal progress 使用自绘轨道和渐变进度条，避免系统 drawable mask 在加载 dialog 中不可见。
- `BackgroundUtil.applyCheckboxAccent()` 保留真实渐变绘制，同时为勾选/取消勾选加入 160ms 状态动画；记事详情和设置页 checkbox 不再是静态切换。
- Chooser dialog 的“radio”实际是 TextView compound drawable，不是系统 `RadioButton`；已改为自绘渐变 radio drawable，并在 RecyclerView rebind 时对选中/取消选中加入动画。
- 首页新建记事 FAB、统计页 FAB、关于页 FAB 增加 foreground ripple；解决渐变在 `onDraw()` 中覆盖 Material background ripple 导致触摸反馈不可见的问题。
- 验证：`./gradlew.bat :app:assembleDebug` 通过。
- 发布：已创建 `docs/features/theme-accent-migration/debug-updates/update-20260621223129.md` 并通过 `:app:publishDebugUpdate` 发布到阿里云，debug update code 为 `202606211432`。

# 2026-06-21 第四轮修复
- 设置页更换 Drawer 头图的 `TwoOptionsDialogFragment` 不再传入 app accent 渐变，保留其它设置 dialog 的渐变入口。
- `BackgroundUtil.applyCheckboxAccent()` 支持传入未选中描边色；记事详情页快速提醒 checkbox 在未选中时使用右侧提醒文字的自适应前景色，选中时仍使用 `accent -> accent2` 渐变。
- `BackgroundUtil.GradientTintDrawable` 补齐 wrapped drawable 的 bounds、state、level、visible 与 callback 转发，并同时包装 `indeterminateDrawable` 与 `progressDrawable`，修复 loading ProgressBar 在 tint 路径下不可见的问题。
- `LoadingDialogFragment`、debug 更新下载 dialog 的 ProgressBar 全部改走 `applyProgressBarGradient()`；debug 更新相关标题/按钮从 `app_pink` 单色改为默认 app accent 渐变。
- 统计页和关于页的粉色 FAB 改为默认 app accent 渐变，并通过自定义 `FloatingActionButton.setThingBackgroundWithAdaptiveIcon()` 让 icon/ripple 根据背景亮度自适应。
- 验证：`./gradlew.bat :app:assembleDebug` 通过。
- 发布：已创建 `docs/features/theme-accent-migration/debug-updates/update-20260621220646.md` 并通过 `:app:publishDebugUpdate` 发布到阿里云，debug update code 为 `202606211407`。

# 2026-06-21 第八轮修复
- `ThingCardRatioTicksView` 新增 `ThingBackground` 输入，缩略图比例刻度和详情附件比例刻度不再把强调色压成单色，纯色/渐变都按同一绘制路径支持。
- 详情截图生成的 `LoadingDialogFragment` 改为接收当前记事背景，沿用已有可见的渐变 ProgressBar 路径。
- 搜索里的选择颜色图标改为通过 `MenuItem` 重设整枚渐变/纯色位图图标；“全部颜色”不再保留旧 sentinel 的透明度，暗色模式下使用默认 accent 渐变。
- 缩略图文件夹卡片移除 `setCardBackgroundColor(representativeColor)`，内容层边框改为可绘制纯色和渐变的 `GradientStrokeDrawable`。
- 缩略图文件夹拖放目标描边改用 `GradientStrokeDrawable`，支持文件夹纯色和渐变背景，并保留原有高亮描边宽度动画。
- 验证：`./gradlew.bat :app:assembleDebug` 通过，仅有既有 deprecated override 警告。
- 发布：已创建 `docs/features/theme-accent-migration/debug-updates/update-20260621233915.md` 并通过 `:app:publishDebugUpdate` 发布到阿里云，debug update code 为 `202606211539`。

# 2026-06-21 第九轮修复
- `ThingCardRatioTicksView` 新增当前 ratio 状态；当 slider 吸附到某个 tick 时，仅该 tick 的文字使用对应纯色或渐变强调色，未命中的 tick 保持原有提示色。
- 记事卡片外观 slider、记事卡片裁切 dialog slider、详情附件裁切 dialog slider 均同步当前 snapped ratio 到 tick view，初始值和外部回写也复用 snap 逻辑。
- 搜索颜色按钮的默认态重新改为跟左侧返回按钮同色；显式选择“全部颜色”时才显示默认 app accent 渐变，具体色桶仍使用对应纯色且去掉旧透明度。
- `BackgroundUtil` 抽出可用于普通 compound drawable 的渐变 checkbox 工厂，ColorPicker popup 内“全部颜色”的左侧 checkbox 选中态改为 `accent -> accent2` 渐变，并保留选中/取消动画。
- 验证：`./gradlew.bat :app:assembleDebug` 通过，仅有既有 deprecated override 警告。
- 发布：已创建 `docs/features/theme-accent-migration/debug-updates/update-20260621235608.md` 并通过 `:app:publishDebugUpdate` 发布到阿里云，debug update code 为 `202606211556`。

# 2026-06-22 第十轮修复
- 诊断用户提供的 `crash_20260621235933.log`：搜索页 ColorPicker 点击任意选项崩溃的直接原因是 `ColorPickerAdapter` 开启了 stable IDs，但局部刷新后所有 ViewHolder 仍使用默认 `NO_ID=-1`，RecyclerView 报出 “Two different ViewHolders have the same stable ID”。
- `ColorPickerAdapter` 为“全部颜色”、分隔项和普通颜色项补充唯一 `getItemId()`，保留上一轮为了 checkbox 动画加入的局部刷新，同时避免 stable ID 冲突。
- 拖拽记事到另一个记事以创建新文件夹时，pending Folder outline 本来已经使用随机 `ThingBackground`，但 `FolderDropOutlineDrawable` 的渐变 shader 使用局部 `0..width/height` 坐标，绘制到卡片实际 bounds 后会被 `CLAMP` 成单一端点色，看起来像纯色。已将 shader 坐标改为跟随 bounds，使随机渐变真正显示在 stroke 上。
- 验证：`./gradlew.bat :app:assembleDebug` 通过，仅有既有 deprecated override 警告。
- 发布：已创建 `docs/features/theme-accent-migration/debug-updates/update-20260622000639.md` 并通过 `:app:publishDebugUpdate` 发布到阿里云，debug update code 为 `202606211607`。

# 2026-06-22 第十一轮修复
- 搜索颜色 picker 不再保留“未选择颜色”的默认状态；进入搜索页时直接选中“全部颜色”。
- 搜索颜色 toolbar icon 在默认/全部颜色状态下统一使用 `accent -> accent2` 渐变，不再根据浅色/暗色模式改成返回按钮同色。
- `beginSearchThings()` 只在异常未选中状态下兜底选中“全部颜色”，移除 `mDontPickSearchColor` 旧逻辑。
- 验证：`./gradlew.bat :app:assembleDebug` 通过，仅有既有 deprecated override 警告。
- 发布：已创建 `docs/features/theme-accent-migration/debug-updates/update-20260622001415.md` 并通过 `:app:publishDebugUpdate` 发布到阿里云，debug update code 为 `202606211614`。

# 2026-06-22 第十二轮修复
- 修复 `RadioChooserAdapter` 迁移到渐变 radio 后的单选回归：旧实现会在更新 `mPickedPosition` 前先刷新旧 item，导致旧 item 重新绑定时仍按选中态绘制，随后新 item 又被刷新为选中态，界面上可能出现多个视觉选中。
- `pick()` 改为先记录旧位置、更新唯一选中位置，再分别刷新旧 item 和新 item；重复点击当前项时强制重绑当前 item，用于纠正 holder 复用或动画中断造成的文字/radio 临时不同步。
- 点击监听不再额外手动刷新当前 `mPickedPosition`，只校验有效 adapter position 后交给 `pick()` 统一处理，避免文字高亮和 radio 高亮走不同刷新路径。
- 验证：最小状态机复现旧逻辑会留下 `old=0,1` 的多选视觉状态，新逻辑只保留 `new=1`；`./gradlew.bat :app:assembleDebug --console=plain` 通过。
- 发布：已创建 `docs/features/theme-accent-migration/debug-updates/update-20260622100254.md` 并通过 `:app:publishDebugUpdate` 发布到阿里云，debug update code 为 `202606220203`。

# 2026-06-22 第十三轮修复
- 用户反馈上一轮仍未修复；重新排查确认刷新顺序不是唯一问题，真正与“加入渐变 radio tint 后才出现”的现象强相关的是 `GradientRadioDrawable` 的动画生命周期。
- `GradientRadioDrawable.jumpToCurrentState()` 原本只取消 animator，不会把 `checkedProgress` 推到真实 checked/unchecked 终态；当 TextView compound drawable 或 RecyclerView 在重绑/状态跳转时打断动画，radio 会永久停在反状态，从而出现文字高亮、radio 未高亮，或反向不同步。
- `GradientRadioDrawable` 新增 checked 终态记录和 `targetProgress`，`jumpToCurrentState()` 取消动画后立即恢复到真实终态；同时 `RadioChooserAdapter` 的动画触发改为选中变化 payload，不再根据 ViewHolder 上一次绑定状态决定，避免滚动复用或普通重绑触发反向动画。
- 验证：`./gradlew.bat :app:assembleDebug --console=plain` 通过，仅有既有 deprecated override 警告。
- 发布：已创建 `docs/features/theme-accent-migration/debug-updates/update-20260622101219.md` 并通过 `:app:publishDebugUpdate` 发布到阿里云，debug update code 为 `202606220213`。

# 2026-06-22 第十四轮修复
- 重新确认用户观察到的“短文本只显示部分渐变、长文本更接近完整渐变”现象：问题不是短文本在自身宽度内吃满渐变，而是 `applyTextBackground()` 旧实现混用了 TextView 外层 view 坐标和 TextView 绘制文字时的 Layout 内部坐标。
- `applyTextBackground()` 改为等待 pre-draw 后基于最终 text/layout/compound drawable 状态计算 shader，避免 RecyclerView bind 后立即使用旧 Layout；文字 shader 只使用 `Layout.getLineLeft/Right/Top/Bottom` 的内部坐标，不再叠加 `totalPaddingLeft/Top`。
- 新增 `CompoundDrawableGradientMode.NONE / SEPARATE / COMBINED`。默认 `SEPARATE`，即普通 compound drawable 与文字分别按自己的范围应用渐变；`COMBINED` 支持把图标与文字当作整体共享一段渐变。
- `GradientCheckboxDrawable` 与 `GradientRadioDrawable` 标记为已自行处理渐变，避免全局 compound drawable 逻辑把动画 drawable 再转成静态 bitmap。`RadioChooserAdapter` 先设置 radio drawable，再按文字自身范围应用渐变文本。
- 验证：`git diff --check`、调试日志残留检查、`./gradlew.bat :app:assembleDebug --console=plain` 均通过。
- 发布：已创建 `docs/features/theme-accent-migration/debug-updates/update-20260622104646.md` 并通过 `:app:publishDebugUpdate` 发布到阿里云，debug update code 为 `202606220247`。
