# RatioSlider 比例调节滑条统一 —— 方案

## 背景与问题

调整图片/视频比例(裁切比例)的 seekbar 出现在 4 处:卡片外观 panel、
卡片封面裁切 dialog、详情图片外观 dialog、抽屉头图裁切 dialog。

现状:

- ticks 渲染 View `ThingCardRatioTicksView` 已被 4 处复用,它只画别人通过
  `setRatios(min, max, ratios, labels)` 传进来的、且落在 `[min,max]` 内的档位
  (`ThingCardRatioTicksView.kt:118`)。
- 但 SeekBar 本体各自 `SeekBar(this)`(panel 在 XML 里),档位列表两套,
  progress↔ratio 映射与 snapping 三套几乎相同的重复代码,吸附阈值都硬编码 `28`。

两套档位列表:

- 卡片(ThingsActivity,常量 `mThingCardRatioPresetValues/Labels`):
  `[1:2, 9:16, 3:4, 1:1, 4:3, 16:9, 2:1]`,上限 2.0。
- 详情/抽屉(DetailActivity / SettingsActivity,函数内硬编码):
  `[1:2, 1:1, 4:3, 3:2, 16:9, 2:1, 65:24]`,上限 65:24≈2.708。

现象:详情/抽屉那套没有 9:16、3:4(所以"1:2 右边直接跳到 1:1");卡片那套没有
3:2、65:24;线性映射下 65:24 出现时会把竖图侧档位挤在最左一小段。

## 收敛设计

### 档位梯子(canonical,10 档)

| 比例 | 1:2 | 9:16 | 2:3 | 3:4 | 1:1 | 4:3 | 3:2 | 16:9 | 2:1 | 65:24 |
|---|---|---|---|---|---|---|---|---|---|---|
| 数值 | 0.5 | 0.5625 | 0.667 | 0.75 | 1.0 | 1.333 | 1.5 | 1.778 | 2.0 | 2.708 |
| log₂ | −1.00 | −0.83 | −0.585 | −0.415 | 0 | +0.415 | +0.585 | +0.83 | +1.00 | +1.437 |

在原卡片+详情并集基础上补 `2:3`(0.667),不补 `24:65`。对数下互为倒数的比例
关于 1:1 位置镜像对称,65:24 为右端孤点。

### 映射:对数

- progress → ratio:`ratio = min · (max/min)^(p/1000)`
- ratio → progress:`p = round(1000 · ln(ratio/min) / ln(max/min))`
- 前提 `min > 0`(比例恒正,各处已 clamp 保证)。
- tick 位置与 seekbar 用同一映射,保证 thumb 落在 tick 上、snapping 自然。

### 各处范围(保留各自语义)

- 卡片 panel / 封面裁切 dialog:**动态**,由卡片几何算出(缩略图高度上下限 →
  可达比例区间),夹在 `[0.1, 10.0]`。`getThingCardThumbnailRatioRange()` 变成
  RatioSlider 的 rangeProvider。
- 详情图片 dialog:固定
  `[DetailAttachmentMediaAppearance.MIN_FULL_SPAN_TARGET_ASPECT_RATIO,
  MAX_FULL_SPAN_TARGET_ASPECT_RATIO]` = `[0.5, 65/24]`。
- 抽屉头图 dialog:固定 `[DrawerHeaderImageCrop.MIN_RATIO, MAX_RATIO]` = `[0.5, 65/24]`。

档位靠 `[min,max]` 过滤显隐:详情/抽屉满范围 → 全显示 10 档;卡片显示子集。

### 组合控件 RatioSlider

新建自包含控件,内部装 ticks 子 view + SeekBar,对外只暴露:

- `setRange(min, max)` / `rangeProvider: () -> Pair<Double, Double>` / `refreshRange()`
- `setRatio(r)` / `getRatio()` / `onRatioChanged(ratio, fromUser)`
- `setAccentBackground(bg, textColor)` / `setColors(tickColor, textColor)`(沿用主题能力)

内部封装:对数定位的 ticks(`[min,max]` 过滤、active 高亮)、SeekBar(max=1000)、
snapping(阈值 28,在 log-progress 空间)、**拖动开始锁定范围**。

### 动态范围刷新语义(卡片)

- slider 持有 rangeProvider。
- 非拖动时,调用点在几何变化(切换显示模式 / 换图 / 换 span)后调
  `slider.refreshRange()`,重查范围并重定位 thumb。
- 拖动 `onStartTrackingTouch` 时采样一次并锁定,拖动期间不随预览几何抖动
  (等价于现在的 `mThingCardActiveRatioDragRange`)。

## 迁移落点

- `panel_thing_card_appearance.xml`(`seek_thing_card_appearance_thumbnail_ratio` 一带,
  约 :384):`SeekBar` + `ThingCardRatioTicksView` → 一个 `RatioSlider`。
- `ThingsActivity.kt`:
  - `bindThingCardRatioTicks`(:3276)、`getThingCardRatioFromProgress`(:3547)、
    `getSnappedThingCardRatioForSeekBar`(:3576)、映射/snapping、常量
    `mThingCardRatioPresetValues/Labels`、`mThingCardActiveRatioDragRange` →
    收进 RatioSlider 删除。
  - `getThingCardThumbnailRatioRange()` → 作为 rangeProvider 传给 panel 与封面裁切两处。
  - `createThingCardCropEditorView` 内的程序化 SeekBar → RatioSlider。
- `DetailActivity.kt`:`createDetailAttachmentRatioControls`(:4665)、
  `bindDetailAttachmentRatioTicks`(:4815)及其映射 → RatioSlider(固定范围)。
- `SettingsActivity.kt`:抽屉头图块(约 :1420)、`drawerHeaderRatioToProgress/FromProgress`
  (:1550/:1560)、`snapDrawerHeaderRatio`(:1566) → RatioSlider(固定范围);
  入参 clamp 沿用 `DrawerHeaderImageCrop.normalizeRatio` 语义。

## 实现注意

- 入参 ratio 一律 clamp 到 `[min,max]`(对齐 Settings 现有 `normalizeRatio`)。
- snapping 阈值暂沿用 `28`(log-progress 空间),实测手感再调。
- ticks 的 label 上下交错改按**可见档位**序号计算,避免过滤后相邻档位落同一排。
- `ThingCardRatioTicksView` 降为 RatioSlider 内部子 view,去掉 "ThingCard" 误导命名
  (拟改 `RatioTicksView`)。
- 各调用点 `onRatioChanged` 中的业务(更新卡片预览 / 详情附件 / 抽屉裁切)保持不动,
  只替换底层管线。

## 验收

- 4 处显示同一套 10 档(各按范围过滤),互为倒数关于 1:1 位置镜像对称。
- 详情/抽屉重新出现 9:16、3:4;卡片范围够宽时出现 3:2、65:24。
- 65:24 出现时不再把竖图侧档位挤扁(对数摊开)。
- 卡片动态范围、拖动锁定手感与现在一致。
- 四处的实际裁切结果(保存的 ratio / 渲染)与改造前等价。
