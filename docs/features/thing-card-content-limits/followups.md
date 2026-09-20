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
