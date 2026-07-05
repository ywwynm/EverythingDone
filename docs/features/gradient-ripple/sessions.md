# Gradient Ripple — Sessions

## 2026-07-05 — 修正 tab ripple 触点与 drawable 重建

- 用户明确纠正：`GradientRippleDrawable` 必须继续复用背景的线性渐变方向，不能为了修触摸扩散行为而改变 ripple 本身的渐变方向。
- 重新诊断颜色面板“纯色 / 渐变”和计时数字风格“实心 / 描边”tab：问题集中在旧 hotspot 可能把初始化时的 `(0,0)` 当成本次触摸点，以及 tab 状态刷新时反复替换新的 foreground drawable，导致按下后状态重建和卡顿。
- 修复 `GradientRippleDrawable`：未按下状态缓存的 hotspot 只有在 bounds 已可用且接近同一次触摸派发时才采信；bounds 为空或 hotspot 缺失时先兜底，等真实 hotspot 或 bounds 到达后再纠正圆心。保留 `BackgroundUtil.createLinearGradient(...)`，不改 `ThingBackground.orientation`。
- 修复 `ThingBackgroundEditor` 和 `DoingDigitStyleDialogFragment` 的 tab：`styleTab()` 复用已有 `GradientRippleDrawable` 并只调用 `updateBackground(...)`，避免切换纯色/渐变、实心/描边时替换 foreground 导致 ripple 状态丢失。
- `git diff --check` 通过，仅有仓库既有 LF/CRLF 提示；`:app:assembleDebug` 编译通过；已用 `docs/features/gradient-ripple/debug-updates/update-20260705173921.md` 发布阿里云 debug update `202607050940`。未使用 adb。

## 2026-06-27 — Initial implementation

- Confirmed against AOSP source that the platform `RippleDrawable` cannot render a gradient
  ripple (single-colour paint). The user picked form B (gradient ripple surfacing on press)
  over form A (persistent gradient underlay + plain ripple). See `decisions.md`.
- Added `app/.../views/GradientRippleDrawable.kt` and wired unselected-state gradient
  ripples into three places:
  - drawer `ThingStatusSegmentedView` — status segments (正在进行 / 已完成 / 回收站),
    unselected ROUND_RECT (radius = height/2) ripple from `scopeBackground`.
  - drawer `ThingFilterPanel` — the 5 type icons, unselected OVAL ripple on
    `button.background`.
  - `RecurrencePickerAdapter` — weekly/monthly/yearly circular buttons (OVAL) and the
    month-end pill (ROUND_RECT). Added `onViewRecycled` to stop animations.
- Hardened `DisplayUtil.setRippleColorForCardView` to an `is RippleDrawable` check.
- Builds clean (`:app:assembleDebug`). Published a debug update (code 202606271303) to the
  Aliyun channel with `PEAK_ALPHA = 0.36` for on-device tuning. Not committed — awaiting the
  user's visual review.

## 2026-06-27 — Animation tuning after first on-device test

- Feedback: the ripple followed the finger when sliding and could disappear before filling
  the circle; it also felt slower than the system ripple.
- Reworked `GradientRippleDrawable` animation: lock the origin at press (no finger
  tracking); decouple radius and alpha so the radius always fills even on quick
  release/scroll-cancel (alpha-only fade); speed it up (radius 260ms, alpha enter 60ms,
  alpha exit 300ms). Published debug update 202606271314.

## 2026-06-27 — App-wide ripple expansion (batch 1: home ecosystem + settings)

User requested extending coloured/gradient ripples across the whole app (14 items). Cross-cutting
conventions: unselected → colour ripple via `GradientRippleDrawable` (folder/record/accent); selected
→ adaptive neutral ripple via `BackgroundUtil.adaptiveRippleColor(bg)` (light→dark, dark/accent→white).
Added shared helpers `adaptiveRippleColor(bg)` and `fillDrawable(bg)` to BackgroundUtil; gave
`GradientRippleDrawable` a pill mode (`cornerRadiusPx < 0` → height/2) and rectangle mode (`== 0`).

Batch 1 (items 1–5), all compiling:
- Drawer: status/type selected ripple → adaptive; folder + settings/help/about items unselected →
  colour ripple, selected → filled colour row + adaptive ripple + contrast foreground; folder
  expand button → folder-colour ripple. (DrawerNavigationView + ThingsActivity scope plumbing.)
- Home toolbar icons (hamburger/overflow/search-back/colour) → folder colour, selection mode adaptive,
  via traversing the system Toolbar's child views. Overflow popup items stay system default (user call).
- Big folder thumbnail card → folder colour ripple (ThingsAdapter; shared with single-thing widget config).
- Appearance panel pills (card width / folder size / media position) → colour unselected, adaptive
  selected; colour panel pure/gradient tabs → record/folder colour (ThingBackgroundEditor).
- SettingsActivity: all `_as_bt` items → accent gradient (recursive traversal); Two/ThreeOptionsDialog
  action items → coloured ripple from `mAccentBackground ?: accent` (covers drawer-header dialog).
- Published debug update for on-device review. Remaining items 6–14 (Detail + dialogs + widget + search) pending.

## 2026-06-27 — Batch-1 feedback round 1

- Drawer selected folder's expand button ripple → adaptive (was always folder colour).
- Toolbar icons: removed the system ripple layer (`background = null`) so only ours shows
  (was double-layered).
- Appearance/colour panel: change-colour icon, confirm button, cover-source button, colour-page
  back button, random/pick buttons → record/folder colour.
- Settings checkbox (`CompoundButton`) → accent oval ripple.
- `adaptiveRippleColor` whitish alpha deepened `0x29` → `0x40` (≈25%). Published 202606271540.

## 2026-06-27 — Batch-1 feedback round 2

- Toolbar icon ripple was bigger than the system layer, and nav buttons (hamburger / close /
  back) were even larger because their host views are wider. Gave `GradientRippleDrawable` a
  `fixedRadiusPx` mode and switched all toolbar icons to a fixed 20dp radius
  (`TOOLBAR_ICON_RIPPLE_RADIUS_DP`) — decouples ripple size from view bounds so nav == menu
  items == system size. Contextual (selection-mode) icons use `circularRipple(...).setRadius(...)`.
- Drawer selected folder's expand/collapse icon now drawn at full opacity
  (`onColor(bg, 1f)`); unselected keeps the dimmed foreground colour.
- `adaptiveRippleColor` whitish alpha `0x40` → `0x5C` (≈36%); the single tuning point is
  `BackgroundUtil.adaptiveRippleColor`. Published 202606271558.

## 2026-06-28 — Batch-1 feedback round 3

- Toolbar ripple wasn't centred (origin = touch point) and felt small vs the action item's
  touch area. Added `centered` (origin = view centre) and `peakAlphaOverride` params to
  `GradientRippleDrawable`; toolbar icons now use centred fixed-radius ripples, radius
  20dp → 22dp. Selection-mode (contextual) toolbar switched from system `circularRipple` to a
  centred `GradientRippleDrawable` over a PURE adaptive colour with `peakAlphaOverride` = the
  adaptive colour's own alpha — same feel as normal mode, correct per-tone alpha (white 36% /
  black 16%).
- Drawer selected folder's expand chevron still looked translucent because `ic_dropdown.png`
  is only ~54% alpha (max 138/255) and `ImageView.setColorFilter` (SRC_ATOP) preserves that
  cap. Switched the selected branch to `DisplayUtil.opaqueTintDrawable`, which remaps the
  glyph alpha so the max becomes fully opaque; unselected keeps the source PNG's dimming.
  Published 202606271611.
- Follow-up tweak: `centered` should not snap the origin to centre on press. Reworked it to
  start the ripple at the touch point and migrate the origin toward the view centre as the
  radius fills (origin convergence like the native ripple), so it still spreads from the
  finger but ends centred. Toolbar radius 22dp → 21dp. Published 202606271617.

## 2026-06-28 — App-wide ripple expansion (batch 2: items 6–14)

Re-scoped item 6 per the user: it's the **share** TwoOptionsDialog, not the Detail toolbar
overflow (overflow-menu items explicitly deferred). Added a shared
`GradientRippleDrawable.applyAccentRipple(view, bg, fallbackColor)` for the many flat
confirm/option buttons (keeps utils free of view deps — the factory lives in the views layer).

- 条6 DetailActivity.chooseHowToShareThing → pass `getAccentBackground()` to the share dialog.
- 条7 Reminder suite: `DateTimeDialogFragment` tabs / after-unit button / rec-unit button /
  pick-all → note colour; `DateTimePicker` popup rows (shared by quick-remind popup + both unit
  popups) → note colour; `TimeOfDayRecAdapter` new-reminder + delete-x → note colour;
  `RecurrencePickerAdapter` selected circle + month-end pill → adaptive; confirm button.
- 条8 `AddAttachmentDialogFragment` 4 items → note colour (reads `getAccentBackground()`).
- 条9 `AudioAttachmentAdapter` card (rounded) + 3 icon buttons (oval) → note colour, recycle stop.
- 条10 `ImageAttachmentAdapter` container (rect) + edit/delete (oval) → note colour (new
  `setAccentBackground`, fed from DetailActivity); crop dialog width pills (DetailActivity
  `bindDetailAttachmentAppearanceChoice` / `applyDetailAttachmentAppearanceSelectedPill`):
  unselected note colour, selected adaptive over the filled pill.
- 条11 confirm-button ripple = confirm text colour across Alert / ThreeActionsAlert / Chooser /
  ColorInfo / AttachmentInfo / HabitRecord / HabitDetail / LongText / ThingFolderName /
  ThingBackgroundEditorBottomSheet / PatternLock / DebugUpdate; radio options
  (`RadioChooserAdapter`) ripple = confirm colour. ThingDoing CTA + AudioRecord FAB left as-is
  (not flat confirm buttons).
- 条12 `MoveToThingFolderDialogFragment`: row fill/ripple now use **each row's own folder
  colour** (was the moved item's), selected → adaptive, expand icon → folder colour / adaptive,
  confirm → accent.
- 条13 `ThingsListWidgetConfiguration`: scope rows per-folder colour (Drawer/条12 style); type
  icons (条1 style); status/display pills unselected scope-folder colour, selected adaptive,
  root→white; the two checkbox rows → accent. Added `currentScopeBackground()` +
  `refreshScopeDependentChrome()` so type/status/display ripples follow the selected scope.
- 条14 `ColorPicker.bindAllColor`: "all colours" item unselected → accent gradient ripple,
  selected unchanged (no ripple).

All compile clean; published 202606271711. Not committed.

## 2026-06-28 — Batch-2 feedback round 1 (7 items)

- **Item 7 (core, most important)**: ripples vanished instantly on tap instead of playing out.
  Root causes: a quick release cut the alpha fade-in before it peaked, and the framework's
  `jumpToCurrentState` reset an in-flight fade. Reworked `GradientRippleDrawable`: split alpha
  into in/out animators with a `pendingExit` flag — releasing mid-fade-in defers the fade-out
  until the ripple has reached full alpha, so a single tap always plays fill+fade to
  completion; `jumpToCurrentState` no longer interrupts a running/ pending fade-out; and
  `applyAccentRipple` now reuses an existing instance (updateBackground) so list rebinds
  (notifyDataSetChanged) don't discard a running ripple.
- Item 1: DetailActivity actionbar icons (ib_back + menu items + overflow) → adaptive ripple
  (`adaptiveRippleColor(getAccentBackground())`, centred, fixed 21dp) via new
  `applyDetailToolbarIconRipples()`, called after both `tintMenuIcons` sites. (Overflow popup
  rows stay system default.)
- Item 2: `AudioAttachmentAdapter` 3 buttons → rectangular (fill) ripple, not oval.
- Items 3 & 4: `createDetailAttachmentAppearanceButton` confirm now uses note colour text
  (`applyTextBackground`, gradient-capable) + note ripple; `createDetailAttachmentAppearanceIconButton`
  (Detail video play/pause/stop) and `ThingsActivity.createThingCardCropEditorIconButton` (home
  cover video crop play/pause/stop) → note-colour rectangular ripple.
- Item 5: `MoveToThingFolderDialogFragment` selected-row chevron → `opaqueTintDrawable`
  (ic_dropdown PNG is ~54% alpha; SRC_IN/ATOP can't make it opaque). Icon image/tint moved
  into `bindExpand` (selected = opaque, unselected = dimmed).
- Item 6: search "all colours" ripple was being created but cut instantly (dismiss-on-click +
  item 7); resolved by the core fix. Picked state keeps no ripple per spec.

Published 202606271751. Not committed.

## 2026-06-28 — Batch-2 feedback round 2 (8 items)

- Item 2/3 shape correction: crop-dialog video play/pause/stop reverted to **circular**
  (last round made them rect for audio-parity, but the user wants media-crop controls circular;
  audio buttons stay rect).
- Item 1: `ThingCardAppearanceSourcePicker` option rows → note-colour row ripple
  (`applyAccentRowRipple`).
- Item 2: appearance-panel `mBtThingCardAppearancePreciseCrop` (crop cover) → note-colour pill
  foreground ripple; home crop dialog confirm (`createThingCardCropEditorButton`) → note-colour
  pill foreground ripple.
- Item 3: confirm-button ripple shape — `applyAccentRipple` changed from rect to **pill**
  (cornerRadiusPx -1) so all dialog confirm/action buttons match the cancel pill; added
  `applyAccentRowRipple` (rect) for list rows (`RadioChooserAdapter`). Detail crop dialog
  confirm (`createDetailAttachmentAppearanceButton`) given the standard padding + text size so
  its spacing/width/edge-margin match other dialogs.
- Item 4: share dialog (`TwoOptionsDialogFragment`) option icons → `tintDrawableOpaque`
  (the gradient-but-opaque variant) instead of `tintDrawable` (which preserved the PNG's ~54%
  alpha → faded).
- Item 5: `AboutActivity` toolbar nav/menu/overflow icons → accent gradient (opaque via
  `tintDrawableOpaque`) + accent gradient ripple (`applyAboutToolbarChrome`); version text →
  gradient.
- Item 6: `ThingsActivity.getHomeActionbarIconTintBackground` returns accent at root in **both**
  light and dark mode (was gray in light mode).
- Item 7: Settings/Help toolbar title + nav/overflow icons → whitish
  (`onColor(accent, ON_ALPHA_PRIMARY)`) on the accent-gradient bar.
- Item 8: FAB icons white on accent — `FloatingActionButton.setThingBackgroundWithAdaptiveIcon`
  now judges light/dark by the full `ThingBackground` (accent gradient → dark → white icon/ripple),
  covering the stats share + about support FABs; home root create FAB icon set to `white_86p`;
  Detail start-doing button icon → `white_86p` + light ripple.

Published 202606280207. Not committed.

## 2026-06-28 — Batch-2 feedback round 3 (4 items)

- Re-added `BackgroundUtil.applyToolbarIconRipples(toolbar, radiusDp, factory)` (factory-based,
  no utils→views dep) — now shared by Settings/Help/About.
- Item 1: Help feedback menu icon tinted whitish in `onCreateOptionsMenu`; About toolbar title
  → accent gradient (About's actionbar is on a light `app_chrome_surface`, so gradient — matching
  its icons — not literal white; noted to the user).
- Item 2: Settings + Help toolbar icons → whitish ripple (accent-adaptive GradientRippleDrawable,
  centred, fixed radius) via the shared helper.
- Item 3: `MoveToThingFolderDialogFragment` — title + confirm text colour and confirm ripple now
  follow the **selected target folder** (new `selectedFolderBackground()` + `updateAccentChrome()`
  called from `onCreateView` and `selectRow`). Selected row reworked to mirror Drawer: solid fill
  (`fillDrawable`, was translucent 0x22) + adaptive ripple + contrast (onColor) folder icon / name /
  expand chevron. `bindIcon`/`bindTitle` now take (rowBg, selected).
- Item 4: drawer header image foreground ripple → accent gradient (`GradientRippleDrawable` on the
  inflated `drawer_header` FrameLayout).

Published 202606280425. Not committed.

## 2026-06-28 — Move dialog root colouring + drop passed-in accent

- `MoveToThingFolderDialogFragment.selectedFolderBackground()`: root ("all") → `App.defaultAccentBackground`
  (accent gradient) for title / confirm text / confirm ripple; folder → its own colour. The dialog no
  longer uses the host-supplied `mAccentBackground` at all — `setAccentBackground` kept as a no-op for
  caller compatibility, field removed, overscroll edge colour switched to accent. Published 202606280434.

## 2026-06-28 — Batch-3 feedback (3 items: checkbox ripple + widget chrome + folder-list unification)

- 条1 设置界面 checkbox ripple 偏小：`GradientRippleDrawable` 现支持 `shapeOval + fixedRadiusPx`
  的「居中固定半径圆形裁剪」（半径可超出控件 bounds）；新增 companion
  `applyCheckboxRipple(checkbox, bg)`（半径 `CHECKBOX_RIPPLE_RADIUS_DP = 20dp`、`centered=true`，
  并把所在行 `clipChildren/clipToPadding` 关掉，波纹才能画到 checkbox bounds 外）。`SettingsActivity`
  的 `applySettingsItemRipples` CompoundButton 分支改用该方法。原内切圆 ~16dp → 20dp。
- 条2 记事列表 widget 配置：标题、确定按钮（文字 + 胶囊 ripple）、透明度滑条、两个 checkbox
  （box + 自身圆形 ripple）及其所在 item 的触摸 ripple，全部随文件夹列表区域所选范围（文件夹色 /
  根目录 accent 渐变）实时着色。新增 `updateScopeChrome()`，在 `onCreate` 末尾与
  `refreshScopeDependentChrome()` 调用；确定按钮 ripple 用 `foreground = GradientRippleDrawable(scope,
  胶囊)` 覆盖 `installAppChromeDialogActionButton` 设的中性 ripple。新增字段 `mTvTitle/mTvConfirm/
  mRlSimpleView/mRlAlphaHeader`，移除早期固定 `mAccentBackground` 上色。
- 条3 三处文件夹列表区域（Drawer / 移动到文件夹 dialog / widget 配置）配色统一到 Drawer：
  - widget `ScopeAdapter` 重写配色：选中行 `selectedFillRipple`（半透明）→ Drawer 式
    `scopeRowBackground`（实色 `fillDrawable` 填充 + 自适应波纹 + 白 mask）；选中前景 `applyTextBackground`
    渐变文字 → `onColor(bg,.86)` 对比纯色；文件夹 icon 选中 → `pure(onColor)` 对比色（原一直用文件夹
    自身色）；「全部」icon / 展开 icon / 标题 未选中 `black_54p`(54%) → `app_chrome_drawer_item_foreground`
    (69%)；展开 icon 选中 → `opaqueTintDrawable(onColor 1f)` 满不透明（原一直 `black_54p`）。
  - 移动到文件夹 dialog：未选中标题色 `app_chrome_on_surface_secondary`(54%) →
    `app_chrome_drawer_item_foreground`(69%)，与 Drawer 统一（其余颜色第六批已对齐）。

`:app:assembleDebug` 通过。未发布、未提交。

## 2026-06-28 — 文件夹列表区域固定色集中到 ColorConstants

- 新增 `views/ColorConstants.kt`，内含 `ColorConstants.FolderList` 单一来源，集中三处文件夹列表区域
  （Drawer / 移动到文件夹 dialog / widget 配置）相对固定的配色：`unselectedForeground`（未选中前景 =
  `app_chrome_drawer_item_foreground`）、`disabledForeground`（不可选目标）、`selectedForeground`
  （选中对比前景 = `onColor(bg, .86)`）、`selectedExpandIcon`（选中展开箭头满不透明 = `onColor(bg, 1f)`）、
  `selectedRipple`（选中行自适应波纹 = `adaptiveRippleColor(bg)`）。
- `DrawerNavigationView` / `MoveToThingFolderDialogFragment` / `ThingsListWidgetConfiguration` 三处
  原本内联的这些颜色全部改为引用 `ColorConstants.FolderList`，以后统一改一处即可。Drawer 删除已无引用的
  `getDrawerItemForegroundColor()`。文件夹自身色（未选中图标 / 波纹、选中实色填充）仍由各行 ThingBackground
  直接给出，不进此对象。`:app:assembleDebug` 通过。

## 2026-06-28 — 根目录展开/收缩按钮：Drawer + widget 配置

移动到文件夹 dialog 的根目录早已有「收起/展开所有顶层文件夹」的 chevron；Drawer 与记事列表 widget
配置缺这个按钮（顶层文件夹恒展开）。本次补齐，三处行为一致：

- widget `ScopeAdapter`：新增 `rootExpanded`（默认展开）；`rebuildVisibleItems` 据其决定是否铺顶层；
  抽取 `bindExpandButton(holder,rowBg,selected,expanded,visible,onToggle)` 供根目录行与文件夹行共用；
  新增 `toggleRootExpanded()`（旋转动画 + `notifyItemRangeInserted/Removed`）。
- `DrawerNavigationView`：`DrawerItem` 加 `rootToggle`；新增 `setOnRootExpandClickListener`；`submitItems`
  /adapter 加 `animatedRootToggle` 贯穿；`bind()` 展开段改为 `showsToggleSlot = isFolder || (rootToggle &&
  hasChildFolders)`，根目录行复用 `hasChildFolders`/`folderExpanded` 渲染 chevron，点击走 root 监听。
- `ThingsActivity`：新增 `mDrawerRootExpanded`（默认展开）；`updateDrawerFolderItems` 加 `animatedRootToggle`
  参数、按其决定是否 `appendDrawerFolderItems`、给「全部」目的地行设 `rootToggle/rootHasChildFolders/
  rootExpanded`；`createDrawerDestinationItem` 扩展这三参；新增 `toggleDrawerRootExpanded()` 接监听；
  `expandDrawerFolderAncestors` 顺带置 `mDrawerRootExpanded=true`（导航到文件夹要能看见它）；
  `findVisibleDrawerFolderKey` 在根目录收起时回退到「全部」选中态（与折叠父文件夹时高亮最近可见祖先一致）。

`:app:assembleDebug` 通过。未发布、未提交。

## 2026-06-28 — 撤销：三处根目录都不要展开/收缩按钮

上一条的根目录 chevron 方案按反馈撤掉：Drawer、移动到文件夹 dialog、记事列表 widget 配置三处的
「全部 / 根目录」行右侧都不显示展开/收缩按钮，顶层文件夹恒展开。

- 撤回 `DrawerNavigationView`（`rootToggle` / `rootExpandClickListener` / `animatedRootToggle` /
  `consumeRootToggleAnim` 及 bind 中 `showsToggleSlot` 全部移除，恢复 `isFolder` 版本）与
  `ThingsActivity`（`mDrawerRootExpanded` / `toggleDrawerRootExpanded` / `setOnRootExpandClickListener`
  及 `updateDrawerFolderItems`、`createDrawerDestinationItem`、`expandDrawerFolderAncestors`、
  `findVisibleDrawerFolderKey` 的相关改动）。
- widget `ScopeAdapter` 撤回 `rootExpanded` / `toggleRootExpanded`，根目录行恢复为 expand INVISIBLE、
  `rebuildVisibleItems` 恒展开顶层；`bindExpandButton` 助手保留（文件夹行仍在用）。
- 移动到文件夹 dialog 移除原有的 `mRootExpanded` 根目录折叠：根目录行 `hasChildren=false`（无 chevron）、
  顶层文件夹恒展开，`toggleExpanded` 去掉 `folder==null` 分支。

`:app:assembleDebug` 通过。未发布、未提交。

## 2026-06-28 — NoticeableNotificationActivity：类型标题渐变 + 按钮记事色 ripple

- 标题里「记事类型」文字由单色（`mThing.getColor()` 代表色）改为按记事背景上色：渐变记事用渐变 shader
  （新增 `GradientTextSpan`，复用 `BackgroundUtil.createLinearGradient`，与左侧类型图标一致），纯色记事用
  其颜色；「• 时间」部分仍为灰色提示色（不受 shader 影响——span 分 run 渲染）。
- 关闭按钮（右上角）+ 卡片下方 action 按钮（完成 / 开始做事 / 延迟提醒等）触摸 ripple 由中性
  `installAppChromeCircleRipple` 改为记事色：`foreground = GradientRippleDrawable(thingBackground,
  shapeOval=true)`（40dp 方形 → 圆形波纹；渐变记事则渐变波纹）。

`:app:assembleDebug` 通过。未发布、未提交。

## 2026-06-28 — widget 配置类型 icon 亮/暗色适配（撤回 c6ade5d6 后追加）

- 上一条提交 c6ade5d6 已 `git reset --soft HEAD~1` 撤回（改动仍暂存），追加本修复后再一起提交。
- 记事列表 widget 配置的 5 个记事类型 icon，未选中态原用恒定 `black_54p`（暗色模式下黑图标几乎不可见），
  改为主题自适应的 `app_chrome_drawer_item_foreground`（亮 #B0000000 / 暗 #B0FFFFFF）+ `opaqueTintDrawable`
  满不透明，与 Drawer 的 `ThingFilterPanel` 一致。类型按钮映射加入 iconRes（`Triple(mask, ImageView, iconRes)`），
  每次从原始 drawable 重新着色，避免反复着色累积。选中态（范围色渐变图标 + 现有填充）保持不变。

`:app:assembleDebug` 通过。
