# Thing Card Content Limits 待办

## 桌面小组件的行数与条数上限（2026-09-19 记）

本次完全不动桌面小组件：`app_widget_item_thing.xml` 里写死的 `maxLines="9"`、
`AppWidgetHelper` 里写死的 8 项都保留，**全局默认也不对它生效**。用户裁定后续
单独处理。届时要同步改 `estimateWidgetHeightDp` 与 `estimateWidgetChecklistHeightDp`
的高度估算，否则小组件高度会算错。

## 文件夹卡片外观面板里调预览行数（2026-09-19 记）

文件夹卡片里的预览小卡片用 `FolderThingPreviewAdapter` 的 `style.contentMaxLines`
（紧凑 2 / 普通 3）与 `style.checklistMaxItems`（3 / 4）。本次不给它加可调入口。

## 0 档与「不限」档（2026-09-19 记）

取值范围定为 1–16。曾考虑过 0 档（完全不显示内容文本 / 清单，只留标题与封面，与
封面来源的「不显示」同构）与「不限」档，本次都不做。0 档需要真正 `GONE` 掉控件并
重算 padding、分隔线与卡片最小高度，不是把 `maxLines` 设成 0 就完事。

## `ThingCardAppearance.sideMediaWidthPercent` 是死字段（2026-09-19 发现）

`fromJson` 里读、`toJson` 里不写，等于永不持久化。ADR 0003 之后侧边宽度改由目标
宽高比推导，这个字段已无用。可以清理，但与本功能无关，单独处理。

## 清单项字号放大后首行不折、其余折行（2026-09-20 实测，既有问题）

魅族 20 Infinity 上实测：清单项字号超过 14sp 时（`visible < 8` 走
`(130 − 4×visible)/7` 那一支），同样长度的条目里**第一条不折行、其余折两行**。
uiautomator 的 bounds 与像素测量都证实**字号完全相同**（三行字高都是 46px），
差别在可用宽度：首行墨迹 427px 放得下，其余却折了。

**不是本次改动引入的**：新建一条本来就只有 4 项、没有任何覆盖的清单（走默认上限
8、`visible=4` → 16.29sp），呈现出逐像素相同的不对称（首行 tv 高 81px、其余
160px）。上限可调只是让这个既有现象更容易被碰到。

字号再大一档（上限 2 → 17.43sp）时所有条目都折行，不对称消失——像是在 16.29sp
这一档上可用宽度差了几个像素的边界效应。`check_list_tv.xml` 的结构是
`LinearLayout(match_parent)` 里放 `ll_check_list_tv(wrap_content)` + 一个
`weight=1` 的占位 `TextView`，怀疑与带 weight 兄弟时 wrap_content 子项的测量有关。
需要单独排查。

## 面板里其余滑动条仍是旧的对齐与 ripple（2026-09-20 记）

本轮只把「内容文本的最大显示行数 / 清单项的最大显示个数」这一组改成了新的对齐口径
（轨道内缩与滑块外探各让半个半径）并去掉了 ripple。面板里另外四条——封面图片宽度
（`seek_thing_card_appearance_side_width`）、视频帧、背景遮罩、背景高度——仍是系统
默认内边距 + `EverythingDoneTheme.Things` 的默认 ripple，与新控件不一致。

没有一并改是因为用户只点名了新控件，且那几条属于既有行为。要统一的话，改动就是
给它们同样加上 `thing_card_appearance_slider_bleed` 的负边距、`background="@null"`，
并在绑定时调 `alignThingCardAppearanceSliderToTextEdges`——注意它们分别在
`ll_thing_card_appearance_side_width`、`ll_thing_card_appearance_video_frame`、
`ll_thing_card_appearance_background_controls` 里，后者是水平布局（标签在左），
外扩的那一层要对应调整。

## 侧边封面下内容被裁、省略行消失（2026-09-20 实测，既有问题）

**现象**：卡片宽度为「宽」+ 封面放左/右时，如果清单项文本长到会在窄内容区里折行，
卡片算出来的高度容不下全部内容——最后一条被压成十几 px，末尾的省略行整个消失。用户
看到的是「只有两三条」而不知道还有更多。

**不是本次改动引入的。** 关键对照（魅族 20 Infinity，20 项清单，封面右置、宽度 41%）：

| 上限 | 字号 | 卡片高 | 实际渲染 | 省略行 |
|---|---|---|---|---|
| 8 | 14sp（**新旧公式完全相同**） | 1064 | 6（第 6 条仅 32 px） | 无 |
| 3 | 16.86sp（新公式） | 588 | 3 | 有 |
| 8 | 14sp | 1064 | 6 | 无 |
| 3 | 16.86sp | 588 | 2（第 3 条仅 10 px） | 无 |

上限 = 8 时字号与旧公式一模一样，照样被裁，所以与字号公式的修改无关。而且同一条件
重复跑结果不同（上限 3 两次的条目高分别是 83 与 165），说明侧边布局里
**「侧边媒体宽度 ↔ 内容高度」的迭代本身就不稳定**——内容先按较宽的宽度测（不折行、
高度小），侧边媒体据此算出较宽的宽度，内容区随之变窄、真正折行时高度已经定死。
`BaseThingsAdapter.SIDE_IMAGE_PROJECTION_MAX_ITERATIONS = 6` 就是为收敛它而设的，
在这种输入下没收敛。

新功能只是让这个既有状态更容易被碰到（上限调小 → 字号变大 → 更易折行）。修的话要
动侧边投影的迭代，范围远超本功能，单独处理。

### 2026-09-20 诊断：根因是 RecyclerView 的手动测量不随宽度更新

在 `getSideImageProjection` 的迭代、`measureThingCardSideTextContentHeight` 的预测量、
以及布局沉淀之后各加一条日志，魅族 20 Infinity 上复现并抓取。结论与之前记的
「迭代不收敛」不同：

```
measure textW=729 -> h=1064  rvChildren=9 rvMeasuredH=872 rvItems=9
measure textW=745 -> h=1064  rvChildren=9 rvMeasuredH=872 rvItems=9
measure textW=880 -> h=1064  rvChildren=9 rvMeasuredH=872 rvItems=9
```

**`textW` 从 729 变到 880，测出的高度恒为 1064、其中 RecyclerView 那段恒为 872。**
872 正好等于它当时的实际高度 `rvH`。也就是说预测量拿回来的不是「按这个宽度重新折行后
的高度」，而是「上一次布局宽度下的高度」。

排除了两种可能：

- **不是测量缓存**。递归 `forceLayout()` 之后重测，`forced=1064` 与原值完全相同。
- **不是数据未就绪**。`rvItems=9`、`rvChildren=9`、`rvComputing=false`，adapter 数据和
  子 View 都在。

再把 RecyclerView **单独**按目标宽度强制 measure：

```
rvW=652  childH=91,91,91,91,91,91,91,91,88   ->  rvSolo=872  rvSoloForced=872
```

子项高度仍然报 91，那是更宽的宽度下不折行的高度；窄宽度下实际会折成 140。所以
**手动 measure 一个已经布局过的 RecyclerView，拿不到按新宽度重新折行后的高度**。

后果：不动点方程 `w = clamp(ratio × H(W − w))` 里的 `H` 实际退化成了常数函数，迭代
第二次就「收敛」，但收敛到的是基于过时内容高度的解。侧边宽度因此偏大，内容区偏窄，
条目真正折行时卡片高度已经定死，末尾的条目和省略行被挤掉。

正文（纯 `TextView`）不受影响，已由 24 组测试验证（同一上限下八种形态高度完全一致、
随行数线性）。**只有清单走 RecyclerView 的这条路不可靠。**

### 可选修法

| | 做法 | 代价 |
|---|---|---|
| A | 清单高度自己算：把 `CheckListAdapter` 的字号公式、上下边距、缩进、图标宽度与省略行抽成可独立计算的高度函数，用 `StaticLayout` 按目标宽度算每项行数 | 根治且结果确定；但排版规则要在渲染与测高两处保持同步，需用同一份常量收敛风险。工作量中等偏大 |
| B | 去掉不动点迭代，侧边宽度直接用用户设定的百分比 | 最简单，顺带删掉一整块复杂逻辑；但改变现有观感，调过比例的卡片都会变样，属产品决策 |
| C | 两步布局：先按当前值布局，布局后检测溢出再调整侧边宽度重布局一次 | 不必复制排版规则；但每张这类卡片两次布局，滚动时有性能代价，且可能出现一帧跳变 |
| D | 只对清单跳过宽度自适应：内容是清单时侧边宽度直接用百分比，纯文本时保持现有迭代 | 精准对应问题所在，改动与风险都最小，正文卡片观感完全不变；代价是两条路径、语义不统一 |

### 2026-09-20 尝试 A 的结果：只解决了一半，根因还深一层

按 A 做了一轮：把 `CheckListAdapter` 的字号公式、行上边距、按级缩进、首行对齐抽成
companion 里的共用函数（渲染与测高同一份），新增 `measureCardContentHeight`
用 `StaticLayout` 按目标宽度算每项行数，再在
`BaseThingsAdapter.measureThingCardSideTextContentHeight` 里把预测量结果中
RecyclerView 那一段替换成计算值。

**部分见效**：上限 8 与 3 从"被裁、无省略行"变成了"条目数正确 + 省略行在"。
但上限 6 仍然不对（渲染 4 条、末条被压成 128 px）。加日志定位后拿到这组：

```
rvW=880  rvPadL=21 rvPadR=56   ->  rv 内容宽 803
rowW=426          <- 行本身只有 426
tvW=342   textWidth=424.0  lines=2
```

文本实测宽 424，在计算用的 568 可用宽下**确实该是单行**——**计算没有错**。错的是
**RecyclerView 的子行本身也停留在旧宽度**：rv 已经 803 宽，子行仍是 426，于是文本按
342 去折行，明明放得下也折成两行。

也就是说这个缺陷有两层，而 A 只覆盖了第一层：

1. 预测量拿不到按新宽度的高度 —— 已可用计算绕开；
2. **子行的渲染本身就是按旧宽度折的** —— 高度算得再准，画出来的还是旧的。

试过在侧边布局应用之后按新宽度 `rv.post { notifyDataSetChanged() }` 重排（用 tag 记
上次宽度、只在变化时重绑），**无效且有害**：`updateCardForContent` 每次绑定都会
`setItems`（内含 notify）并重设 adapter，post 的重绑被盖掉；tag 存在复用的 ViewHolder
上还会跨记事残留。加上之后上限 8 从「好」变成了「坏」，已整体回退。

本轮代码已回退到 `9f3711fe`，工作区只留文档。下一步要么找到让子行可靠跟随宽度重排的
办法（第 2 层），要么转向 G / E 那种把「内容高度 ↔ 侧边宽度」依赖整个消掉的方向——
依赖消掉之后，第 2 层也就不存在了。

### 2026-09-20 修好了：卡片清单改用 LinearLayout，不再用 RecyclerView

用户提出换掉 RecyclerView，实测这是对的方向——它把两层问题**一次**消掉：LinearLayout
的子项测量是同步的，`measure(EXACTLY(w), UNSPECIFIED)` 会真的按新宽度重测所有子行，既
不必自己算折行（第 1 层），也不会出现"子行停留在旧宽度"（第 2 层）。卡片上最多十几条，
本来就不需要回收，RecyclerView 在这里只带来了这个缺陷。

新增 `views/ThingCardChecklistView`：竖向 LinearLayout 逐行放 `check_list_tv`，行视图按需
增减并复用。排版规则（基准字号、行上边距、按级缩进、首行对齐）提到
`CheckListAdapter` 的 companion 里，由新视图与适配器**共用同一份**；条目预处理
（去控制行、折叠已完成区）与前景色也一并提成静态函数。

`BaseThingsAdapter.onChecklistAdapterInitialized` 换成
`onThingCardChecklistWillBind(holder, checklist, thing)`，子类改为设
`checklist.fixedIconScale` / `checklist.itemClickCallback`。改动集中在这一个绑定点，
首页、文件夹预览卡片、DoingActivity、提醒全屏页、widget 配置页全部受益。

**验证**（魅族 20 Infinity，测试记事 921100 / 921200，验完移入回收站）：

- 侧边封面 **左 / 右 × 上限 2/3/4/5/6/8/11 共 14 个组合，全部**条目数正确且省略行在，
  卡片高度随上限单调（508 / 588 / 672 / 756 / 846 / 1064 / 1337）。此前同样的矩阵里
  12 个不符。
- 普通形态（封面在上）渲染与改动前一致：图标、按级缩进、字号、省略行都对。
- 项数不超上限时不出省略行（3 项 + 默认上限 8 → 渲染 3 条、无省略行）。
- 交互：设置关闭时点清单区进详情页；临时开启「在记事卡片中调整清单项的勾选状态」与
  「折叠已完成的清单项」后，点条目能勾选、勾选后折叠进已完成区且不误进详情页。
  **两个设置验完已改回原值 false 并读回核对。**

**未在设备上验证**：多级清单的缩进渲染（详情页的缩进按钮默认隐藏，触发路径不明确），
以及 `DoingActivity` / 提醒全屏页的 `maxItemCount = -1` 分支（启动计时会改动用户的
"正在做"状态）。前者的缩进、层级字号比例、层级配色、二三级图标上移都是原样搬过来的，
且 `cardItemIndentPx` 现在由新旧两处共用同一个函数，不存在两处不一致的风险。

### 待清理：`CheckListAdapter` 的 TEXTVIEW 形态已无使用点

卡片清单改用 `ThingCardChecklistView` 之后，`CheckListAdapter.TEXTVIEW` 及其
`TextViewHolder`、`setEventForTextViewItem(More)`、`removeItemsForTextView` 的实例版等
都没有调用方了，本类当前只服务详情页的 EDITTEXT 形态。已在常量上加注释标明，删除留作
单独一轮（改动面大、且要重新验证详情页）。`BaseThingsAdapter` 里那个已无人使用的
`mCheckListAdapters` 缓存已随本次删除。
