# 主题强调色迁移：app_accent → accent/accent2 渐变

将 APP 主题强调色从纯黄色 `#FFEB3B` 替换为 Everything-Android 的 accent+accent2 渐变色，
原则：**只要控件有办法支持渐变，就用渐变**。暗色模式用相同颜色。

---

## 基础定义

| 颜色名 | 旧值 | 新值 |
|--------|------|------|
| `app_accent` | `#FFEB3B` | `#F66048` |
| `app_accent2` | (新增) | `#FFAE36` |
| `app_accent_representative` | (新增) | `#FF8040` |

- 渐变方向：**LB_RT**（左下 `app_accent` → 右上 `app_accent2`）
- 集中定义：`App.defaultAccentBackground`（`ThingBackground.gradient(app_accent, app_accent2, LB_RT)`）
- `light` 和 `night` colors.xml 都用相同值

## 不能渐变的少数例外（只接受单 int 的系统 API）

| 例外 | 替换值 | 原因 |
|------|--------|------|
| 光标（`cursor_et_title.xml` + `CheckListAdapter` + `DetailActivity` 的 `textCursorDrawable`） | `app_accent_representative` | `setTint` / `ColorFilter` 单 int |
| 文本选择手柄（`styles.xml` EditText `colorAccent` + `CheckListAdapter` 的 `setSelectionHandlersColor`） | `app_accent_representative` | Android Editor 内部读 `colorAccent` 单 int |
| 边缘效果（`ImageViewerActivity` `EdgeEffectUtil` + `ThingCardAppearanceSourcePicker` 的 `EdgeEffectUtil`） | `app_accent_representative` | `EdgeEffect.setColor` 单 int |

## 引用总表（按活性 + 处理方式分类）

### 一、占位（运行时被代码覆盖，XML 改默认值即可）

| 位置 | 覆盖来源 | XML 处理 |
|------|----------|----------|
| `include_contextual_toolbar_things.xml:19,26` | `applyContextualStatusBarChrome` → transparent | `@android:color/transparent` |
| `activity_things.xml:134` FAB | `setThingBackground` → `backgroundTintList` | 移除 attribute |
| `app_widget_things_list.xml:12` header | `setThingsListHeaderAppearance` → transparent+bitmap | `@android:color/transparent` |
| `app_widget_thing.xml:433` tv_thing_action | `rv.setTextColor(TV_THING_ACTION, primary)` | 移除 textColor |
| `panel_thing_card_appearance.xml:165,184,507,548` | `applyThingCardAppearanceAccentText` | 移除 textColor |
| `ThingCardVideoCropEditorView.kt:806` Loading paint | `setAccentBackground()` 始终非 null | `thing_black` 或保留 |

### 二、活性 — 用 `App.defaultAccentBackground` 渐变

#### 2a. View 背景

| 位置 | 当前代码 | 变更 |
|------|---------|------|
| `activity_settings.xml:16,23` | XML static → `@color/app_accent` | 代码 onCreate 包裹 statusbar+toolbar 用 `BackgroundUtil.applyBackground(wrapper, App.defaultAccentBackground)` |
| `activity_help.xml:15,22` | 同上 | 同上 |
| `activity_thing_widget_configuration.xml:15,22` | 同上 | 同上 |
| `include_bottom_bar_detail.xml:84` 假 FAB | XML static → `@drawable/bg_fab_circle_accent` | `DetailActivity.initUiStartDoing` 中 `BackgroundUtil.applyBackground(view, App.defaultAccentBackground)`，`bg_fab_circle_accent.xml` 删除 |
| `ThingsActivity.kt:2161` | `ThingBackground.pure(getColor(app_accent))` | `App.defaultAccentBackground` |
| `ThingsActivity.kt:7334` | `ThingBackground.pure(getColor(app_accent))` | `App.defaultAccentBackground` |
| `AppWidgetHelper.kt:638` | `ThingBackground.pure(getColor(app_accent))` | `App.defaultAccentBackground` |
| `DisplayUtil.kt:705` | `ThingBackground.pure(getColor(app_accent))` | `App.defaultAccentBackground` |
| `ThingsActivity.kt:3622` | `ThingBackground.pure(getColor(app_accent))` | `App.defaultAccentBackground` |

#### 2b. 渐变文本

| 位置 | 当前代码 | 变更 |
|------|---------|------|
| `drawer_header.xml:45` tv_dh_completion_rate | XML textColor | `DrawerHeader.updateCompletionRate()` 末尾调用 `BackgroundUtil.applyTextBackground(view, App.defaultAccentBackground)` |
| `snackbar_undo.xml:31` bt_undo | XML textColor | `Snackbar.show()` 中调用 `BackgroundUtil.applyTextBackground(mBtUndo, App.defaultAccentBackground)` |
| `activity_about.xml:64,73` tv_ywwynm/tv_everything_done | XML textColor | `AboutActivity.onCreate()` 中调用 `BackgroundUtil.applyTextBackground` |
| `MoveToThingFolderDialogFragment.kt:237` | `textView.setTextColor(getColor(app_accent))` | 当 mAccentBackground==null 时使用 `BackgroundUtil.applyTextBackground(textView, App.defaultAccentBackground)` |

#### 2c. 图标 / Tint

| 位置 | 当前代码 | 变更 |
|------|---------|------|
| `ThingsActivity.kt:798` 工具栏图标（暗色无文件夹） | `ThingBackground.pure(getColor(app_accent))` | `App.defaultAccentBackground`（已是 ThingBackground，tint 链原生支持） |
| `BaseThingsAdapter.kt:654` sticky 图标 | `ColorStateList.valueOf(getColor(app_accent))` | `BackgroundUtil.tintDrawable` 配合 `App.defaultAccentBackground` |
| `ThingsAdapter.kt:535` 根文件夹粘性图标 | `ColorStateList.valueOf(getColor(app_accent))` | 同上 |
| `ThingsActivity.kt:3622` 颜色按钮图标 | `ThingBackground.pure(getColor(app_accent))` | `App.defaultAccentBackground` |
| `MoveToThingFolderDialogFragment.kt:243` 选中行背景 | `ThingBackground.pure(getColor(app_accent))` | `App.defaultAccentBackground` |

#### 2d. FAB fallback

| 位置 | 当前代码 | 变更 |
|------|---------|------|
| `ThingsActivity.kt:2227` | `fab.setThingBackground(null, appAccent)` | `fab.setThingBackground(App.defaultAccentBackground, appAccentRep)` — 直接传入渐变背景 |

#### 2e. CheckBox 选中态

| 位置 | 当前 | 变更 |
|------|------|------|
| `styles.xml:70` Detail.CheckBox | `colorAccent="@color/app_accent"` | 移除 colorAccent；`DetailActivity` 中 `cb_quick_remind.buttonDrawable = createGradientCheckboxDrawable()` |
| `activity_settings.xml` 12 个 CheckBox | Theme `colorAccent=blue_deep` | `SettingsActivity` 中遍历设置 gradient buttonDrawable |
| `EverythingDoneTheme.Settings` | `colorAccent="@color/blue_deep"` | 移除 colorAccent |

#### 2f. 卡片外观面板的 accentColor fallback

| 位置 | 当前代码 | 变更 |
|------|---------|------|
| `ThingsActivity.kt:3733` | `getColor(app_accent)` | `App.defaultAccentBackground.representativeColor()`（面板内 pill / checkbox 等用单色） |

#### 2g. ColorPicker / Picker

| 位置 | 触发条件 | 变更 |
|------|---------|------|
| `ColorPicker.kt:402` | 暗色 HUE_BUCKET 全部颜色 | `App.defaultAccentBackground.representativeColor()` |

#### 2h. ProgressBar（通过 GradientTintDrawable 包装）

| 位置 | 当前代码 | 变更 |
|------|---------|------|
| `BaseThingsAdapter.kt:2953` | `indeterminateDrawable.setColorFilter(getColor(app_accent), SRC_IN)` | `BackgroundUtil.applyProgressBarGradient(pbLoading, App.defaultAccentBackground)` |
| `ImageAttachmentAdapter.kt:337` | 同上 | 同上 |
| `ImageViewerActivity.kt:120` | 同上 | 同上 |
| `LoadingDialogFragment.kt:62` | `indeterminateDrawable.setColorFilter(mAccentColor, SRC_IN)` | 若 `mAccentBackground` 非 null 用渐变否则 representative |

**`GradientTintDrawable`**：自定义 Drawable 包装器，用 `canvas.saveLayer` + `PorterDuff.Mode.SRC_IN` 将原 indeterminate 动画绘制为 alpha mask，叠加 LinearGradient 着色。每次 `draw()` 动态创建（轻量，无每帧 Bitmap 分配开销）。

#### 2i. ThingCardAppearanceSourcePicker

| 位置 | 触发条件 | 变更 |
|------|---------|------|
| `ThingCardAppearanceSourcePicker.kt:40` | 构造函数传 null | `accentBackground?.representativeColor() ?: App.defaultAccentBackground.representativeColor()` |

### 三、活性 — representative 例外（系统限制）

| 位置 | 用途 | 变更 |
|------|------|------|
| `cursor_et_title.xml:7` | 光标 solid color | `@color/app_accent_representative` |
| `CheckListAdapter.kt:874,534` | 光标 drawable | `App.defaultAccentBackground.representativeColor()` |
| `DetailActivity.kt:1299` | 内容区光标 | `App.defaultAccentBackground.representativeColor()` |
| `styles.xml:65` EditText colorAccent | 选择手柄 | `@color/app_accent_representative` |
| `CheckListAdapter.kt:528` | 选择手柄 | `App.defaultAccentBackground.representativeColor()` |
| `ImageViewerActivity.kt:101` | ViewPager 边缘效果 | `App.defaultAccentBackground.representativeColor()` |
| `ThingCardAppearanceSourcePicker.kt` edge effect | EdgeEffectUtil | `representativeColor()` |

### 四、BackgroundUtil 新增工具方法

```kotlin
/** 渐变着色 ProgressBar 的 indeterminate drawable。
 * 用 saveLayer + SRC_IN 将原旋转动画作为 alpha mask 叠加渐变。 */
fun applyProgressBarGradient(pb: ProgressBar, bg: ThingBackground)

/** 对 CheckBox 的 buttonDrawable 应用渐变（替换原生 colorAccent）。 */
fun createGradientCheckboxDrawable(bg: ThingBackground, context: Context): StateListDrawable
```

## 实现顺序

1. **颜色定义** — `colors.xml` × 2 + `App.kt defaultAccentBackground` + `BackgroundUtil` 新方法
2. **Styles / Drawable** — `styles.xml` 3 处 theme + `cursor_et_title.xml`
3. **Layout XML 清理** — 12 个文件中的 `@color/app_accent` → 移除或 transparent
4. **ThingsActivity** — 6 处 fallback 替换
5. **DetailActivity** — 假 FAB + CheckBox + 光标
6. **SettingsActivity** — statusbar/toolbar 渐变 + 12 CheckBox 渐变
7. **HelpActivity / BaseThingWidgetConfiguration** — statusbar/toolbar 渐变
8. **AboutActivity / DrawerHeader / Snackbar** — 渐变文本
9. **Adapter** 组 — 图标 tint + ProgressBar 渐变 + 选择句柄/光标
10. **ImageViewerActivity / LoadingDialogFragment / MoveToThingFolderDialogFragment / ColorPicker / ThingCardAppearanceSourcePicker**
11. **编译验证** — `assembleDebug`
