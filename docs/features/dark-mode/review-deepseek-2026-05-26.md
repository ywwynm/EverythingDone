# 暗色模式适配 Review

## 概览

本次改动涉及 ~85 个文件，覆盖主题系统、语义颜色体系、所有 Activity/Fragment/Adapter/View 的颜色引用迁移、以及 Settings 中的暗色模式开关 UI。整体方向正确，改动模式统一，从硬编码颜色和旧 `black_*p` / `white_*p` 资源迁移到新的 `app_chrome_*` 语义颜色体系。

核心链路：
- `App.onCreate()` → `AppearanceUtil.applyDefaultNightMode()` → `AppCompatDelegate.setDefaultNightMode()`
- `AppearanceUtil.isDarkMode(context)` 供所有需要运行时判断的代码点使用
- `values/colors.xml` (light) + `values-night/colors.xml` (dark) 提供双套语义颜色
- 主题从 `Theme.AppCompat.Light.*` 升级到 `Theme.AppCompat.DayNight.*`

---

## 发现的问题

### 1. (中) `EverythingDoneTheme.Things` 未升级为 DayNight

**位置**: `values/styles.xml:3`, `values-v19/styles.xml:4`, `values-v21/styles.xml:4`

ThingsActivity 的主题仍然是 `parent="Theme.AppCompat.NoActionBar"`（既非 Light 也非 DayNight）。虽然 ThingsActivity 本身通过 `EverythingDoneTheme.Things.Toolbar`（DayNight）叠加了 Toolbar 主题，且 `android:windowBackground` 指向 `@color/bg_activity_things`（已有 dark 变体），但 Activity 级别的非 DayNight 主题意味着：

- 该 Activity 下的默认 Dialog、PopupWindow、ContextMenu 等系统控件的默认样式不会跟随暗色模式变化
- 如果后续有新加的依赖主题默认值的 View，不会自动适配

**建议**: 改为 `parent="Theme.AppCompat.DayNight.NoActionBar"` 以保持一致性，然后验证 homepage 的 window background 仍正确解析。

### 2. (中) `HelpActivity` 主题已升级为 DayNight 但未声明 `uiMode` configChanges

**位置**: `AndroidManifest.xml:227`

HelpActivity 使用 `EverythingDoneTheme.Help`（已改为 DayNight），但 `configChanges` 未包含 `uiMode`。这意味着 follow-system 暗色模式变化时 Activity 会被系统重建，导致：
- 如果用户正在查看某条 Help detail（`HelpDetailFragment`），Fragment 回退栈丢失
- RecyclerView 滚动位置丢失

**建议**: 要么加入 `uiMode` 到 `configChanges` 并在 `onConfigurationChanged` 中刷新 UI（类似 NoticeableNotificationActivity），要么确认重建是可接受的行为。AboutActivity 同理。

### 3. (中) `DoingActivity` 声明了 `uiMode` 但主题未升级 + 无代码处理

**位置**: `AndroidManifest.xml:461-469`, `values/styles.xml:132`

DoingActivity 的 manifest 中加了 `configChanges="uiMode"`，但其主题 `EverythingDoneTheme.Doing` 仍然是 `Theme.AppCompat.Light.NoActionBar`。且 DoingActivity 没有 `onConfigurationChanged` 覆盖。这意味着：
- 暗色模式切换时 Activity 不重建（因为 configChanges 拦截了）
- 但也没有任何代码去响应这个变化（主题本身也不会自动切换，因为还是 Light）
- FAB 按钮颜色硬编码（`#4CAF50`、`#2196F3`），不会随暗色模式变化

**建议**: 如果 Doing 界面不想做暗色适配（透明背景 + 壁纸的特殊 UI），则应该加 `android:forceDarkAllowed="false"` 并移除无意义的 `configChanges="uiMode"`。如果要做适配，需要升级主题并添加代码处理。

### 4. (低) `HelpActivity` 和设置页黄色 Toolbar 的 `colorControlNormal` 硬编码为 `@color/black_54p`

**位置**: `values/styles.xml:115`, `values-v19/styles.xml:83`, `values-v21/styles.xml:83`

HelpActivity 和 `AppAccentToolbar` 的 `colorControlNormal` 设置为 `@color/black_54p`（`#8A000000`）。按决策文档，黄色 accent toolbar 在暗色模式下也应保持黑色控件。但目前这个值**不随暗色模式变化**，在暗色模式下如果 Toolbar 背景变成了暗色（虽然当前仍然是黄色），这个颜色就会不可见。

当前状态下这是正确的——黄色 Toolbar 在两种模式下都使用暗色控件。但这是一个"隐式正确"而非"显式正确"：如果未来 Toolbar 背景改为跟随暗色模式，这个值也需要跟着变。

**建议**: 当前行为符合决策文档，暂不需要改动。但建议加注释说明这是刻意的非语义颜色选择。

### 5. (低) `ImageViewerActivity` 主题未做暗色适配

**位置**: `values/styles.xml:65`, `values-v19/styles.xml:27`

ImageViewer 主题仍然是 `Theme.AppCompat.Light.NoActionBar`。ImageViewer 是图片/视频查看器，通常需要暗色背景，但不依赖主题（它自行管理 status bar 透明 + 图片全屏）。不改可能是有意为之。

**建议**: 如果确定不做暗色适配，至少加 `android:forceDarkAllowed="false"`（v21+）防止系统强转暗色。当前只加了 `dialogTheme` 指向 DayNight dialog。

### 6. (低) `CheckListAdapter` 展开/收缩图标旋转动画被移除

**位置**: `CheckListAdapter.kt` diff 中 `ivExpandShrink` 的点击处理

旧代码在点击展开/收缩时有一个 180° 旋转动画（`animate().rotation(...)`）。新代码直接 toggle `mExpanded` 后调用 `notifyChecklistStructureChanged()`，动画丢失。

**建议**: 如果这是有意的简化，可以接受。否则可以恢复旋转动画。

### 7. (低) `CheckListAdapter` 所有局部 notify 替换为全量 notifyDataSetChanged

**位置**: `CheckListAdapter.kt` diff

`notifyItemInserted` / `notifyItemRemoved` / `notifyItemChanged` 被统一替换为 `notifyChecklistStructureChanged()` → `notifyDataSetChanged()`。这意味着 RecyclerView 的 item 动画（插入/移除的淡入淡出）全部丢失。

**建议**: 如果 checklist 的增删动画不重要，这个简化是合理的。如果希望保留动画，可以用 `DiffUtil` 或精确的 notify 调用。

### 8. (低) `ColorPicker` tint mode 从 `SRC_ATOP` 改为 `SRC_IN`

**位置**: `ColorPicker.kt:355`

搜索模式下的 toolbar icon tint，PorterDuff Mode 从 `SRC_ATOP` 改为 `SRC_IN`：
- `SRC_ATOP`: 在源图像非透明处绘制目标颜色，保留源 alpha
- `SRC_IN`: 在源图像非透明处绘制目标颜色，使用目标 alpha

对于 toolbar icon（PNG with baked-in alpha）：`SRC_IN` + `opaqueTintDrawable`（已在代码中标准化 alpha）的组合是正确的。但如果有其他路径直接 tint 而没有走 `opaqueTintDrawable`，`SRC_IN` 可能导致图标变淡。

**建议**: 当前使用场景正确（HUE_BUCKET + ALL_COLOR_SENTINEL 分支），但建议在其他 tint 路径上也统一用 `opaqueTintDrawable` 而不是直接 `setColorFilter`。

### 9. (低) `DetailActivity` 的 `onConfigurationChanged` 中 `dismissDetailDialogFragmentsForAppearance` 遍历了所有已知 DialogFragment tag

**位置**: `DetailActivity.kt:1869-1893`

这是一种"穷举式"的 dismiss 策略——只 dismiss 已知 tag 列表中的 Fragment。如果有新增的 DialogFragment 类型但忘记加入这个列表，在暗色模式切换时它就**不会被 dismiss 和重建**，从而保持旧的（错误的）DayNight 资源。

**建议**: 考虑用 `fragmentManager.fragments` 遍历所有已添加的 Fragment 并 dismiss `DialogFragment` 子类，而不是维护硬编码 tag 列表。或者至少在添加新 DialogFragment 时有明确的 checklist（已在决策文档中记录，但代码层面没有强制）。

### 10. (低) `TwoOptionsDialogFragment` 使用 `compoundDrawables` 而非 `compoundDrawablesRelative`

**位置**: `TwoOptionsDialogFragment.kt:68-69`

使用了 `view.compoundDrawables` 而非 `view.compoundDrawablesRelative`。这与 XML 中使用的 `android:drawableLeft` 一致（LTR 场景），但项目整体已转向 `Relative` 变体。在 RTL 语言下可能出现图标位置问题。

**建议**: 改为 `compoundDrawablesRelative` 以保持一致性。当前影响较小因为 TwoOptionsDialogFragment 的图标很小且不太可能在 RTL 下使用。

---

## 值得注意的设计决策（非问题，仅记录）

1. **`opaqueTintDrawable` 方法** — 将 PNG 图标的 alpha 通道按目标颜色的 alpha 做比例缩放，解决了 PNG baked-in alpha 导致的暗色图标偏淡问题。这是正确的处理方式。

2. **`BaseDialogFragment` 的 DayNight 上下文创建** — 通过 `inflater.cloneInContext(ContextThemeWrapper(activity, R.style.EverythingDoneTheme_Dialog))` 给每个 dialog 提供 DayNight 主题上下文。同时 `onStart()` 中显式设置 window background 为 semantic 颜色，防止 DayNight 主题下的默认背景不一致。

3. **Dialog 固定宽度策略** — `ThingDoingDialogFragment` 和 `DateTimeDialogFragment` 覆盖 `getDialogWindowWidthPx()` 返回精确 dp 值，绕过 DayNight 主题下 AppCompat 可能施加的 `windowMinWidth`。

4. **`DetailActivity` 的 overlay 重建策略** — follow-system 暗色变化时不重建整个 Activity（避免数据丢失），而是 dismiss 所有 App Chrome overlay（dialog/popup/picker）并用新的 DayNight 资源重建。Thing-background 拥有的 body 部分保持不动。

5. **`NoticeableNotificationActivity` 作为混合表面** — 外层 dialog shell 使用 App Chrome 颜色，内嵌 thing card 遵循 Thing Background 优先级，符合决策文档。

6. **`forceDarkAllowed=false`** — 所有 v21+ 主题加了此属性，阻止 Android 系统级别的强制暗色转换（它会盲目反转颜色，破坏 Thing Background 的自定义渲染）。

7. **`SettingsActivity` 的 `uiMode` 处理** — 选择了"存储当前设置状态后 recreate"的方案，因为 Settings 页面状态复杂（多组 CheckBox/Ringtone/下拉选项），就地刷新风险太高。

---

## 未在 diff 中出现的潜在遗漏

以下资源在代码库中存在硬编码颜色，但**本次 diff 未覆盖**——它们可能已经正确（不需要改），也可能是遗漏：

| 资源 | 硬编码颜色 | 是否需要暗色适配 |
|------|-----------|-----------------|
| `drawable/underline.xml` | `#FFFFFF` | 低优先级，用于 EditText 下划线 |
| `drawable/ripple_white.xml` | `#36FFFFFF` | 不需要，白色 ripple 与暗色模式兼容 |
| `drawable/image_cover_top.xml` | `#20000000` | 不需要，图片覆盖层始终为暗色 |
| `drawable/ic_random_gradient.xml` | `#FFFFFFFF`, `#33FFFFFF` | 低优先级，用于随机颜色图标 |
| `drawable/ic_random_color.xml` | `#FFFFFFFF` | 低优先级 |
| `drawable/bottom_bar_detail_shadow.xml` | `#36000000` | 不需要，阴影始终为暗色 |
| `drawable/dashed_line_check_list_separator.xml` | `#42FFFFFF` | 可能需要，白色虚线在暗色背景下会变亮 |
| `drawable/actionbar_shadow.xml` | `#40000000` | 不需要，阴影始终为暗色 |
| `drawable/dashed_line_card.xml` | `#89FFFFFF` | 可能需要 |
| `layout/check_list_et.xml:61` | `textColorHint="#5CFFFFFF"` | 可能需要改语义颜色 |
| `layout/check_list_et.xml:62` | `textColor="@color/white_76p"` | 可能需要改语义颜色 |
| `layout/drawer_header.xml:33` | `textColor="#FFFFFF"` | 不需要，drawer header 覆盖在图片上 |
| `layout/activity_statistic.xml:78` | `textColor="#FFFFFF"` | 不需要，白色文字在有色 Toolbar 上 |
| `layout/activity_doing.xml:211,245` | `backgroundTint="#4CAF50"`, `#2196F3` | 可能需要适配（Doing FAB 按钮）|
| `layout/include_actionbar_detail.xml:22` | `popupTheme="@style/Theme.AppCompat.Light"` | **可能需要改** — Detail overflow menu 的 popup theme 仍是 Light |

### `include_actionbar_detail.xml` 的 popupTheme 问题

Detail 页面的 Toolbar overflow popup 主题仍为 `Theme.AppCompat.Light`。在暗色模式下，overflow menu 的弹出列表会以浅色背景显示，与其他暗色 UI 不协调。

**建议**: 改为 `@style/EverythingDoneTheme.Dialog` 或单独的 DayNight popup theme。

---

## 总结

改动范围大但模式一致，核心架构正确。上述 10 个问题中：

- **建议必须修复**: 问题 1（Things 主题）、问题 3（DoingActivity 主题）
- **建议修复**: 问题 2（Help/About configChanges）、问题 10（include_actionbar_detail popupTheme）
- **可以接受但值得注意**: 问题 6（动画丢失）、问题 7（notifyDataSetChanged）、问题 8（SRC_IN）
- **设计考虑**: 问题 9（穷举 dismiss）可后续优化
