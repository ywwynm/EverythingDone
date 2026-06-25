# RatioSlider 实现执行清单

按阶段推进,每阶段尽量自洽。最后统一 `:app:assembleDebug`。

> **状态(2026-06-25):全部阶段已实现,`:app:assembleDebug` BUILD SUCCESSFUL。**
> 待用户实机/模拟器验收视觉与手感。

## 关键调用点定位(已核对)

| 处 | 文件:行 | 现状 |
|---|---|---|
| 卡片 panel | `panel_thing_card_appearance.xml:379-396`;`ThingsActivity` 字段 :276/:279行附近、findView :1949-1952、listener :2721-2751、bind :3239-3245、映射/snap :3539-3587、常量 :10773-10774 | XML 内 `SeekBar`+`ThingCardRatioTicksView`,动态范围 |
| 卡片封面裁切 dialog | `ThingsActivity.createThingCardCropEditorRatioControls :4582-4674`;quantize :4209-4211 | 程序化 `SeekBar`+ticks,动态范围 |
| 详情图片 dialog | `DetailActivity.createDetailAttachmentRatioControls :4665-4764`;ticks bind :4815-4827、映射/snap :4829-4880、常量 :5173-5174;调用方 :4162、数据类 :4511 | 程序化,固定范围 `[0.5, 65/24]` |
| 抽屉头图 dialog | `SettingsActivity :1421-1488`;映射/snap :1550-1592、常量 :2090-2091 | 程序化,固定范围 `[0.5, 65/24]` |

附加事实:
- media-background 高度滑条 `mSeekThingCardAppearanceBackgroundHeight` 已被
  `:3646` 设为 GONE,listener 只在 `fromUser` 响应 → 休眠,`:4982`
  `getThingCardRatioFromProgress(heightPercent)` 实际不触发。**不动它**。
- `mThingCardActiveRatioDragRange` 还被 side-width 滑条 listener(:2704/:2714)用来
  锁 range。**保留该字段、保留 side-width 用法、保留 `getThingCardThumbnailRatioRange`
  里的 lock 读取(:3305)**;只移除比例滑条自己设它的地方(:2736/:2748/:4626/:4638)。

## 阶段 0 - 新增共享组件

- [ ] 重命名 `ThingCardRatioTicksView.kt` → `RatioTicksView.kt`(类名 `RatioTicksView`)。
  - `onDraw` 的 `fraction` 由线性改对数:`(ln(ratio) - ln(min)) / (ln(max) - ln(min))`,
    `min <= 0 || logRange <= 0` 时不画。
  - label 上下交错由绝对 index 改为**可见档位计数**(只对画出来的 tick 递增)。
- [ ] 新增 `RatioSlider.kt`(`FrameLayout`,内含 `SeekBar` + `RatioTicksView`)。
  - companion:`SLIDER_MAX=1000`、`SNAP_PROGRESS_DISTANCE=28`、
    `PRESET_RATIOS`/`PRESET_LABELS`(10 档)、`ratioToProgress`/`ratioFromProgress`(对数)、
    `snap`(范围内最近档位,阈值 28)、`quantize`。
  - 实例 API:`setRange` / `setRangeProvider` / `refreshRange` / `setRatio` / `getRatio` /
    `onRatioChanged: ((Double)->Unit)?` / `setAccentBackground` / `setColors`。
  - 内部:listener 仅 `fromUser` 触发 `onRatioChanged`;`onStartTrackingTouch` 采样并锁
    `dragRange`,`onStopTrackingTouch` 清锁并 `refreshRange()`;`setRatio/setRange/refreshRange`
    在 `dragRange != null`(拖动中)时 no-op,避免重入抖动;programmatic 改 progress 用
    `suppressListener` 防递归,不触发 `onRatioChanged`。

## 阶段 1 - SettingsActivity(最简,先验证组件)

- [ ] `:1421-1488` 整块 `SeekBar`+ticks+frame 换成一个 `RatioSlider`:
  `setAccentBackground(accent, hint)`、`setRange(MIN_RATIO, MAX_RATIO)`、
  `setRatio(normalizeRatio(crop.ratio))`、`onRatioChanged = { cropEditorView.setTargetAspectRatio(it) }`。
- [ ] 删除 `drawerHeaderRatioToProgress/FromProgress`、`snapDrawerHeaderRatio`、
  `getSnappedDrawerHeaderRatioForSeekBar`、常量 `DRAWER_HEADER_RATIO_SLIDER_MAX/SNAP`。
- [ ] 调整 import(去 `ThingCardRatioTicksView`,加 `RatioSlider`)。

## 阶段 2 - DetailActivity

- [ ] `createDetailAttachmentRatioControls` 主体换 `RatioSlider`(固定范围
  `[MIN_FULL_SPAN, MAX_FULL_SPAN]`);返回 `DetailAttachmentRatioControls(view=container, setRatio={ slider.setRatio(it); cropView.setTargetAspectRatio(slider.getRatio()) })`;
  `onRatioChanged = { cropView.setTargetAspectRatio(it) }`。保留 label + container + 可见性逻辑(:4167)。
- [ ] 删除 `bindDetailAttachmentRatioTicks`、`getDetailAttachmentRatioProgress/FromProgress`、
  `snapDetailAttachmentRatio`、`getSnappedDetailAttachmentRatioForSeekBar`、常量
  `DETAIL_ATTACHMENT_RATIO_SLIDER_MAX/SNAP`。
- [ ] import 调整。

## 阶段 3 - ThingsActivity 封面裁切 dialog

- [ ] `createThingCardCropEditorRatioControls` 主体换 `RatioSlider`:
  `setRangeProvider { getThingCardThumbnailRatioRange().let { it.minRatio to it.maxRatio } }`、
  `setAccentBackground(...)`、`setRatio(initialAspectRatio)`、
  `onRatioChanged = { cropView.setTargetAspectRatio(it) }`。
- [ ] `:4209-4211` quantize 改 `RatioSlider.quantize(rawTargetAspectRatio, range.min, range.max)`
  (`range = getThingCardThumbnailRatioRange()`)。
- [ ] 移除该处对 `mThingCardActiveRatioDragRange` 的设置(:4626/:4638)。

## 阶段 4 - ThingsActivity 卡片 panel(最复杂)

- [ ] XML `:379-396` 的 `FrameLayout(SeekBar+ticks)` → 一个
  `<com.ywwynm.everythingdone.views.RatioSlider android:id="@+id/v_thing_card_appearance_ratio_slider" .../>`(高 52dp)。
- [ ] 字段:`mSeekThingCardAppearanceThumbnailRatio` + `mVThingCardAppearanceThumbnailRatioTicks`
  → `mThingCardAppearanceRatioSlider: RatioSlider?`;findView(:1949-1952)同改。
- [ ] init(:2721-2751)删比例滑条 listener,改为设 `setRangeProvider{...}` +
  `onRatioChanged = { updateThingCardActiveTargetAspectRatio(it) }`。**side-width listener
  的 :2704/:2714 不动**。
- [ ] bind(:3239-3245)改 `slider.setAccentBackground(...); slider.refreshRange(); slider.setRatio(aspectRatio)`;
  删 `bindThingCardRatioTicks`。
- [ ] 主题列表(:3716-3722)移除 `mSeekThingCardAppearanceThumbnailRatio`。
- [ ] 删 `getThingCardRatioProgress/FromProgress`、`snapThingCardRatio`、
  `getSnappedThingCardRatioForSeekBar`、`bindThingCardRatioTicks`、常量
  `THING_CARD_RATIO_SLIDER_MAX/SNAP`、preset 常量 `mThingCardRatioPresetValues/Labels`。
  - 注意 `:4982` 休眠的高度滑条若还引用 `getThingCardRatioFromProgress`,改为本地
    线性换算或直接走 `getThingCardBackgroundHeightRatio`,保持其原行为;确认无其它残留引用。

## 阶段 5 - 收尾

- [ ] 全局搜残留:`ThingCardRatioTicksView`、被删函数/常量名,确保无引用。
- [ ] `:app:assembleDebug` 通过。
- [ ] 自查验收(见 plan.md):4 处同一套 10 档、对数摊开、详情/抽屉回归 9:16/3:4、
  卡片动态范围+拖动锁定不变、保存的 ratio 等价。
