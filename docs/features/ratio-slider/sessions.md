# RatioSlider Sessions

## 2026-06-25 - 设计收敛 + 全量实现

经一次 grill 把"四处比例调节滑条不一致"收敛为统一方案(见 `decisions.md`、`plan.md`),
并按 `execution.md` 完成实现。

### 新增/重命名

- 新增 `views/RatioSlider.kt`:组合控件(`FrameLayout` 内含 `SeekBar` + `RatioTicksView`),
  companion 持有 10 档梯子 + 对数映射 `ratioToProgress/ratioFromProgress` + `snap` + `quantize`;
  实例支持 `setRange/setRangeProvider/refreshRange/setRatio/getRatio/onRatioChanged/
  setAccentBackground/setColors`,内部处理拖动锁范围与 snapping。
- `views/ThingCardRatioTicksView.kt` → 重命名 `views/RatioTicksView.kt`:tick 位置由线性改对数,
  label 上下交错改按可见档位序号。

### 四处迁移(均改用 RatioSlider)

- `SettingsActivity` 抽屉头图 dialog:固定范围 `[MIN_RATIO, MAX_RATIO]`;删 4 个映射/snap 私有函数
  与 `DRAWER_HEADER_RATIO_SLIDER_MAX/SNAP` 常量。
- `DetailActivity` 详情图片 dialog:固定范围 `[MIN/MAX_FULL_SPAN]`;`setRatio` lambda 改为
  `slider.setRatio + cropView.setTargetAspectRatio(slider.getRatio())`;删 5 个函数 + 2 常量。
- `ThingsActivity` 封面裁切 dialog:`createThingCardCropEditorRatioControls` 改用 RatioSlider +
  `setRangeProvider{ getThingCardThumbnailRatioRange() }`;quantize 改 `RatioSlider.quantize`。
- `ThingsActivity` 卡片 panel:`panel_thing_card_appearance.xml` 的
  `FrameLayout(SeekBar+ticks)` → `RatioSlider`;字段/findView/init/bind/主题刷新全部改接
  RatioSlider;删 `bindThingCardRatioTicks`、4 个映射/snap 函数、preset 常量、
  `THING_CARD_RATIO_SLIDER_MAX/SNAP`。

### 关键保留

- `mThingCardActiveRatioDragRange` 与 `getThingCardThumbnailRatioRange` 的 lock 读取**保留**,
  仅供 side-width 滑条拖动锁范围;比例滑条自身的拖动锁改由 RatioSlider 内部 `dragRange` 处理。
- media-background 高度滑条(已 GONE、休眠)对映射的引用(`updateThingCardBackgroundHeight`)
  改走 `RatioSlider.ratioFromProgress`,行为等价。

### 验证

- `:app:assembleDebug` BUILD SUCCESSFUL。
- 全局搜索确认无旧符号(`ThingCardRatioTicksView`、旧 XML id、被删函数/常量)残留。
- 视觉/手感待实机验收。

### 后续可选

- snapping 阈值 28 在对数空间的手感、10 档密度,实机后按需微调。
