# Thing Card Bottom Status Layout Sessions

## 2026-06-28 - 设计定稿 + 应用内实现（Phase A/B）

设计经 grill-with-docs 全程定稿，决策见 decisions.md，术语见 CONTEXT.md
（Thing Card Bottom Status）。

**Phase A（BaseThingsAdapter，首页/NNA/Doing 共享）—— 已实现、编译通过**
- Issue 1 侧栏置底：`applySideImageProjectionLayout` 在有底部状态块时把
  `llTextContent` 撑到侧图高度，并用 `view_thing_bottom_status_spacer`
  (weight=1) 把状态块顶到底部；`measureThingCardSideTextContentHeight`
  测量时收起 spacer 防干扰。
- Issue 2 媒体背景提示并入底部组：`updateThingCardMediaBackgroundInlineCount`
  改为填充内容列内的 `llInlineMediaAttachment`（位于 spacer 之后、音频之前），
  不再用卡片级 overlay（`llMediaCount`）。`hasThingCardBottomStatus` 纳入内联计数，
  使其与音频/提醒/习惯一起被 spacer 顶到底部。`enlargeHiddenMediaCountLayoutIfNeeded`
  增加 `ivMediaBackground.isGone` 守卫，避免媒体背景下放大内联计数。
- 底部组顺序：提示 → 音频 → 提醒/目标 → 习惯 → 16dp。

**Phase B（编辑器 Issue 3）—— 已实现、编译通过**
- 渲染语义不变。`RatioSlider` 增加 `setEnabled`（禁用 SeekBar + 整体淡化）。
- `ThingsActivity`：新增 `isThingCardMediaBackgroundRatioOverridden()`（媒体背景下
  `所设比例换算高度 < 自然内容高度`）与 `updateThingCardAppearanceRatioOverriddenState()`，
  在 `bindThingCardAppearanceCropControls` 与 `scheduleThingCardAppearanceRatioRangeRefresh`
  两处调用：被内容覆盖时置灰 slider 并显示提示
  `thing_card_appearance_ratio_overridden_hint`（已加 en/zh-rCN/zh-rTW/zh-rHK）。
- 侧栏不进入置灰（30%–60% 宽度始终可调）。

**Phase C（单一/列表 widget，RemoteViews）—— 已实现、编译通过**
- 两个布局都加了内容列静态权重 spacer `view_bottom_status_spacer_widget`
  与媒体背景内联计数 `tv_thing_media_inline_count_widget`（chip 白字样式，自洽不依赖
  自适应着色）：单一 widget `app_widget_thing.xml`、列表行 `app_widget_item_thing.xml`
  （二者共用 `setAppearance`，故两处必须都有这两个 id）。
- `AppWidgetHelper.setImageAttachment`：起始重置 spacer/内联计数；媒体背景模式用
  `setWidgetInlineMediaCount` 显示内联计数并显示 spacer（不再用 overlay
  `tv_thing_media_background_attachment_count`）；侧栏模式显示 spacer。新增
  `setWidgetInlineMediaCount`、常量 `V_BOTTOM_STATUS_SPACER` / `TV_INLINE_MEDIA_COUNT`。
- 单一 widget 根为 match_parent 高度（固定单元格），内容列 match_parent → spacer
  可靠置底。
- 列表行根为 wrap_content：内容列 wrap_content，spacer 无空间分配、状态块未置底。
  曾尝试把内容列改 match_parent 借水平 LinearLayout 重测撑高——在 launcher 的 RemoteViews
  集合里不可靠，无效，已撤回。最终方案：内容列保持 wrap_content；**Android 12+** 用
  `AppWidgetHelper.applyThingsListContentColumnHeight`（`RemoteViews.setViewLayoutHeight`,
  API 31+）把列表行内容列高度显式设为行高（侧栏=侧图槽高，媒体背景=`layoutMinHeight`），
  内容列填满后 spacer 正常置底；**Android 11 及以下** RemoteViews 无设布局高度能力，退化为
  状态块跟随内容。这是 RemoteViews 集合行的固有限制。
  （见 update-20260628195741.md → update-20260628201354.md）

### RemoteViews 注意事项（本次踩坑）
- RemoteViews 不支持裸 `<View>`，占位/spacer 必须用 `<TextView>`（项目里
  `view_private_helper_*`、`view_thing_padding_bottom` 即如此）。误用 `<View>` 会导致
  widget 渲染记事时 inflate 失败、整条记事无法显示。（见 update-20260628193804.md）
- widget 配置候选列表复用 BaseThingsAdapter（Phase A 覆盖），选中后预览走 RemoteViews
  （随 setAppearance 覆盖）。

三阶段均已实现并编译通过。
