# Thing Card Content Limits 决策

## 2026-09-19 - 上限分两层：全局默认 + 单条覆盖

正文最大行数与清单最大条数同时具备全局默认值（设置页）与单条记事覆盖值
（首页 contextual 面板的卡片外观页）。单条覆盖存进 `thing_card_appearance`
JSON，与卡片宽度、封面参数同级；未覆盖时留空，取全局默认。

## 2026-09-19 - 两层都按普通 / 全跨分两档

现有默认值本来就随卡片宽度分档（正文 9 / 14，清单 8 / 12），这个差异保留。
因此全局默认是 4 个值，单条覆盖是 4 个可空字段。面板只编辑当前 span 那一档，
在面板里切换卡片宽度会当场换成另一档的取值。

## 2026-09-19 - 取值范围 1–16，不提供「不限」

首页瀑布流与单一记事 widget 配置页（`BaseThingWidgetConfiguration`，走的是
`MixedThingsAdapter` → `ThingsAdapter` 同一条卡片渲染路径）都必须受限，不给
「不限」档。`DoingActivity` 这类单卡片全屏页保持现有的不限行为，不跟随这两个
上限。

## 2026-09-19 - 清单字号公式与上限脱钩

`CheckListAdapter.onBindViewHolder` 原公式把上限本身当成输入：

```
size >= maxItemCount  ->  14sp
否则                  ->  (130 - 4 * size) / 7 sp      // size 为清单总项数
```

上限一旦可调，调大调小都会让字号变小（5 项卡片上限从 8 调到 3：15.71sp →
14sp；10 项卡片上限从 8 调到 16：14sp → 12.86sp），且今天已经导致全跨卡片上
10 项清单的字号（12.86sp）小于同内容的普通卡片（14sp）。

改为：自变量取 `min(总项数, 上限)`，即卡片上实际渲染的条数；14sp 的阈值固定为
常量 8，与用户设定的上限无关。语义回到原意——卡片上东西少就大一点。

## 2026-09-19 - 面板控件形态与顺序

控件形态复用「封面图片宽度」那一组：24dp 标签 + 40dp SeekBar，标签随内容形态
切换文案与读数。正文与清单互斥，永远只出现一行。

面板顺序调整为：卡片宽度 → 正文行数 / 清单条数 → 封面整组（来源、视频帧、
媒体位置、缩略图形状、封面宽度、遮罩）。卡片宽度始终在最上方。

同时把外观主体也包一层 `NestedScrollView`。`ScrollAwareColumn` 只收缩「第一个
可见的 NestedScrollView 子项」，主体页今天没有这样的子项，内容超高时走
`total.coerceAtMost(avail)`，底部取消/确定按钮行会被裁掉；补上之后按钮行恒可见。

## 2026-09-19 - 「未设定」= 滑块停在全局默认值

面板滑块的位置等于当前档的全局默认值时就不写覆盖字段，标签标注「（默认）」；
拖开即覆盖，拖回即自动解除。不加额外档位、不加隐性长按手势。代价是无法「明确
锁定在 9」——以后改全局默认，这条会跟着走。

## 2026-09-19 - 生效范围：首页列表 + 单一记事 widget 配置页

两者共用同一条渲染路径（`BaseThingWidgetConfiguration` 的
`ThingCardDelegateAdapter : ThingsAdapter(...)`），因此覆写点放在 `ThingsAdapter`
即可精确覆盖这两处，并天然排除：

- `DoingActivity` / `NoticeableNotificationActivity`：直接继承 `BaseThingsAdapter`，
  保持现有行为（正文 9 / 14，清单不限）。
- 文件夹卡片里的预览小卡片：`FolderThingPreviewAdapter` 用 `style.*` 另行覆写。
- 桌面小组件（真 RemoteViews）：本次完全不动，保持写死的 `maxLines="9"` 与 8 项。
  **全局默认值也不对它生效**，整块留作后续单独处理。

## 2026-09-19 - 实时预览零接线

`updateThingCardAppearanceDraft` 把草稿直接写回内存中的 `Thing`
（`thing.thingCardAppearance = preparedDraft`）再重绑列表项，取消时还原。新字段
只要落在 `ThingCardAppearance` 里，面板拖动即可实时预览，不需要额外的预览通道。

## 2026-09-19 - 设置页入口与文案

设置页新增「记事卡片外观设置」，点开进入**独立 Activity**（照
`SpatialPhotoSettingsActivity` / `ParticleTuningActivity` 的控件与布局样式：56dp
的「标题 16sp + 右侧读数 14sp」行、全宽 `SeekBar`、12sp 的说明行、左右 16dp）。

文案：

- 二级页四项 —— 「记事卡片宽度为正常时，内容文本的最大显示行数」、「记事卡片
  宽度为宽时，内容文本的最大显示行数」、「记事卡片宽度为正常时，清单项的最大
  显示个数」、「记事卡片宽度为宽时，清单项的最大显示个数」。「正常」「宽」与
  面板里的 `thing_card_appearance_span_normal` / `_span_full` 用词一致。
- 面板内 —— 「内容文本的最大显示行数」、「清单项的最大显示个数」。

术语上用「内容文本」与「清单项」，不用「正文」。

## 2026-09-19 - 全局默认初始值 9 / 16 / 8 / 12

内容文本普通 9、宽 16；清单项普通 8、宽 12。宽卡片的内容文本由今天的 14 改为
16（落在偏好数字上，且等于取值范围上限），代价是所有宽的纯文本卡片变高两行。

## 2026-09-19 - 设置页按内容形态分两组

组标题「内容文本的最大显示行数」「清单项的最大显示个数」，组内两行是「卡片宽度
为正常时」「卡片宽度为宽时」。行标题 8 字，56dp 单行 + 右侧读数放得下；按卡片宽度
分组的话行标题 11 字，德语会挤。入口放在「界面」分组内、「在记事卡片中调整清单项
的勾选状态」之前，让记事卡片相关的三条成簇。

## 2026-09-19 - 面板读数用「标签 · 数值（默认）」

沿用面板现有的单行「标签 · 读数」形态（如 `Cover image width · 42%`）。未覆盖时
在数值后追「（默认）」，这是「未设定」唯一的可见线索。

## 2026-09-19 - 不新增 CONTEXT.md 术语

用户裁定：这次不往个人术语表里加条目。「内容文本」「清单项」只作为界面文案与本
功能文档内的用词。

## 2026-09-20 - 推翻「等于全局默认即视为未设定」，改为拖动即记录

上一条（2026-09-19）的做法是：滑块停在当前档的全局默认值上就不写覆盖字段。用户
裁定不要这个行为——**只要拖动过，就按最终拖到的值记录**，哪怕它恰好等于当前的全局
默认。「（默认）」标识因此只表示「这一档从未设置过」（字段为 null）。

## 2026-09-20 - 滑动条两端按「轨道内缩与滑块外探各让一半」对齐

用户指出对齐口径应当与粒子动画设置页一致：**滑块拉到两端时，滑块外缘要比同区块里
其它文本的左右缘各多出一点**，而不是让轨道本身与文本对齐。沿用
`ParticleTuningActivity.SLIDER_EDGE_BALANCE = 0.5f`：内边距 = 行内边距 + 半个滑块
半径，于是轨道内缩半个半径、滑块外探半个半径。

设置页直接照搬即可。**面板里多一层约束**：滑块中心恒停在 `paddingStart` 与
`width − paddingEnd` 上，滑块外缘会超出 SeekBar 自身 bounds 而被父容器削成半圆
（实测确实如此）。仅靠 `clipChildren="false"` 不足以解决，最终做法是让
**每一层都不超出自己的父 bounds**：

```
NestedScrollView   负边距 -5dp                        → 面板内容区外扩半个半径
  ll_tca_appearance_body   padding 5dp, clipToPadding=false  → 各组位置不变
    ll_..._content_limit   负边距 -5dp + padding 5dp          → 与主体同宽，标签位置不变
      SeekBar              负边距 -5dp + 代码设 padding = 半径 → 与组同宽，滑块不被裁
```

负边距必须写在布局 XML 里：在代码里改 `MarginLayoutParams` 实测不生效。

实测（内容边界为参照）：魅族轨道内缩 4.9 dp、滑块外探 4.9–5.1 dp；三星 4.8 / 4.8–5.1；
粒子动画设置页的轨道左端与新设置页同为 74 px，滑块外探同为 19 px。

## 2026-09-20 - 滑动条统一去掉 ripple

面板与设置页用的是同一个控件、同一套 `DisplayUtil.setSeekBarBackground`，观感差异来自
主题：`EverythingDoneTheme.Things` 继承 `Theme.AppCompat.NoActionBar`、**没有覆盖**
`colorControlHighlight`，用 AppCompat 的默认高亮；而 `EverythingDoneTheme.Settings`
把它覆盖成了 `app_chrome_ripple`（`#1A000000` / `#24FFFFFF`），淡到几乎看不见。

按「统一且暂时不要 ripple」处理：两处的 SeekBar 都设 `android:background="@null"`。
实测按下态与常态逐像素完全相同（差异 0 个像素）。

## 2026-09-20 - 改这两个上限不再触发列表滚动

`updateThingCardAppearanceDraft(bindPanel = true)` 会走
`requestThingCardAppearancePanelSpaceUpdate()` → 列表底部留白更新 →
`ensureThingCardAppearanceSelectedCardVisible()`，把选中卡片顶到列表最顶，并且
`getThingCardAppearancePanelListPaddingBottom` 为了顶得上去会先撑大底部留白——表现为
改一次行数，列表先往上滑露出 activity header 再滑回来。封面那几个参数不改变卡片顶部
位置，`scrollDelta ≈ 0` 时提前 return，所以看不到这个现象。

这一组控件不改变面板自身高度、也没有别的控件依赖它的取值，因此全程
`bindPanel = false`，读数在回调里自己更新。实测拖动前后 header 与卡片 bounds 逐项相同。

## 2026-09-20 - 设置页不加说明文字

原先每组下方有一行 12sp 的说明（「只影响记事卡片。详情页始终显示……」）。用户裁定
画蛇添足，去掉，相应的 string 也从 13 个 locale 里删除。
