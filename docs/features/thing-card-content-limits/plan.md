# Thing Card Content Limits 计划

## 目标

让记事卡片上「内容文本最多显示几行」「清单项最多显示几个」可调，分两层：
设置页的全局默认，以及首页 contextual 面板里对单条记事的覆盖。

## 范围

生效：首页列表（含搜索）、单一记事 widget 配置页。

不生效：`DoingActivity`、`NoticeableNotificationActivity`、文件夹卡片里的预览
小卡片、桌面小组件（真 RemoteViews）。小组件连**全局默认**也不生效，整块留作
后续单独处理。

## 用词

- 「内容文本」= 记事内容不表达为 Checklist 时的纯文本形态；「清单项」= Checklist
  Item。两者互斥（`CheckListHelper.isCheckListStr(content)` 二选一），所以面板上
  永远只出现一行控件。
- 「正常」「宽」沿用 `thing_card_appearance_span_normal` / `_span_full` 的既有译法。
- 本次不新增 `CONTEXT.md` 术语条目（用户裁定）。

## 数据模型

### 单条覆盖（`ThingCardAppearance`）

新增四个可空 `Int` 字段，`null` = 未设定：

| 字段 | 含义 |
|---|---|
| `contentMaxLinesNormal` | 普通宽度下内容文本的最大行数 |
| `contentMaxLinesFull` | 宽卡片下内容文本的最大行数 |
| `checklistMaxItemsNormal` | 普通宽度下清单项的最大个数 |
| `checklistMaxItemsFull` | 宽卡片下清单项的最大个数 |

两种内容形态各存各的，切换形态时互不干扰。

写入 `toJson()` 与读取 `fromJson()` **都要接**。注意现存反例：`sideMediaWidthPercent`
在 `fromJson` 里读、在 `toJson` 里**不写**，等于永不持久化（ADR 0003 之后它已是死
字段）。新字段别重蹈。

`hasSamePresentationAs` 是 `toJson()` 字符串比较，写进 `toJson` 即自动参与，确认时
能正确检测到变化并落库、刷新、跟着同步走（`thing_card_appearance` 整串同步）。

`VERSION` 从 2 升到 3，无迁移逻辑——`fromJson` 不读这个键，旧版本读到新 JSON 会忽
略未知键并退回默认，无害。

### 全局默认（SharedPreferences + `FrequentSettings`）

四个 `Int` 键，经 `FrequentSettings.getInt(key, defValue)` 读取（惰性缓存），写入时
同时 `editor.putInt` 与 `FrequentSettings.put`，并 `App.setJustNotifyAll(true)`。

初始值：**内容文本 9 / 16，清单项 8 / 12**（普通 / 宽）。其中宽卡片的内容文本由
今天的 14 改为 16，落在偏好数字上，代价是所有宽的纯文本卡片变高两行。

## 取值范围

两者都是 **1–16**，不提供「不限」。首页是瀑布流、widget 配置页同构，必须有上限。

「未设定」的判定：面板滑块停在当前档的全局默认值上即视为未设定（不写字段），标签
标注「（默认）」；拖开即覆盖，拖回即自动解除。

## 渲染接线

### 覆写点

在 `ThingsAdapter` 覆写 `getThingCardContentMaxLines` 与
`getThingCardChecklistMaxItemCount`：先读 `thing.thingCardAppearance` 的覆盖字段，
为空则读全局默认。

`BaseThingsAdapter` 的常量（`NORMAL_TEXT_MAX_LINES = 9`、
`FULL_SPAN_TEXT_MAX_LINES = 14`、`FULL_SPAN_CHECKLIST_MAX_ITEM_COUNT = 12`、
`mChecklistMaxItemCount = 8`）保持不动——它们此后只服务 `DoingActivity` /
`NoticeableNotificationActivity`。

`BaseThingWidgetConfiguration` 的 `ThingCardDelegateAdapter` 继承自 `ThingsAdapter`，
自动跟随；`FolderThingPreviewAdapter` 用 `style.*` 另行覆写，自动排除。

### 清单字号公式修正（`CheckListAdapter.onBindViewHolder`）

原式把上限本身当输入，上限一可调就两个方向都让字变小。改为：

```
visible = min(items.size, maxItemCount)     // maxItemCount == -1 时取 items.size
visible >= 8  ->  14sp
否则          ->  (130 - 4 * visible) / 7 sp
```

阈值 8 变成与用户设定无关的排版常量。顺带修掉现存问题：宽卡片上 10 项清单今天是
12.86sp，比同内容在普通卡片上的 14sp 还小。

**这是一次会改变现有观感的全局改动**，验收时要专门看。

上间距沿用 `mt = (34 - 2 * baseSize) / 3` 的既有关系，自变量同步换成 `visible`。

### 实时预览

`updateThingCardAppearanceDraft` 把草稿直接写回内存中的 `Thing` 再重绑那一条，
取消时还原。新字段落在 `ThingCardAppearance` 里即自动获得实时预览，无需额外通道。

拖动滑块会连续改变卡片高度、触发瀑布流重排——面板已有让被编辑卡片保持可见的逻辑
（`mThingCardAppearanceSelectedListPosition` / `mThingCardAppearanceVisibility*`），
验收时确认它在整行整行跳变的情况下仍然稳。

## 面板改动（`panel_thing_card_appearance.xml` + `ThingsActivity`）

### 顺序调整

```
卡片宽度（span）
内容文本的最大显示行数 / 清单项的最大显示个数     ← 新增，二选一
封面图片整组：来源 → 视频帧 → 媒体位置 → 缩略图形状 → 封面宽度 → 遮罩
```

卡片宽度始终在最上方。

### 新控件

复用「封面图片宽度」那一组的形态：24dp 标签 + 40dp `SeekBar`。标签单行，文案
`%1$s · %2$d` / `%1$s · %2$d（默认）`。

显隐：记事内容为空时整组 `GONE`；内容是纯文本时显示行数那一版，是清单时显示条数
那一版。文件夹卡片外观面板（`openFolderCardAppearancePanel`）不显示这一组。

### 外观主体补 `NestedScrollView`

`ScrollAwareColumn` 只收缩「第一个可见的 `NestedScrollView` 子项」。主体页今天没有
这样的子项，`total > avail` 时走 `total.coerceAtMost(avail)`，**底部取消/确定按钮行
会被裁掉**。主体已约 450dp（宽 + 侧边封面时最高），再加 64dp 更贴线。把主体也包一层
`NestedScrollView`，与颜色页对称，按钮行恒可见。

## 设置页改动

「界面」分组内，**紧靠「在记事卡片中调整清单项的勾选状态」之前**新增入口
「记事卡片外观设置」，点开进入新的独立 Activity。

新 Activity 照 `SpatialPhotoSettingsActivity` / `ParticleTuningActivity` 的控件与
布局样式：56dp 的「标题 16sp 靠左 + 读数 14sp 靠右」行、全宽 `SeekBar`、12sp 的
`app_chrome_on_surface_hint` 说明行、左右 16dp 内边距。

按内容形态分两组：

```
内容文本的最大显示行数
    卡片宽度为正常时        [9]
    卡片宽度为宽时          [16]
清单项的最大显示个数
    卡片宽度为正常时        [8]
    卡片宽度为宽时          [12]
```

任一值变化即 `App.setJustNotifyAll(true)`，首页 `onResume` 消费并重绑列表。

## 需要新增的字符串

设置页入口、Activity 标题、两个组标题、两个行标题（正常 / 宽）、面板两个标签、
两个读数格式串（普通 / 带「（默认）」）、`SeekBar` 的 contentDescription。
共 14 个 locale（`values` + 13 个）。

## 验收

设备：魅族 20 Infinity `392QBFED2226B`、三星 SM-S9180 `R5CW20BLNKL`。
**看实际界面，不只看日志。不动用户自己的记事——先建虚构测试记事，测完移入回收站。**

要覆盖的点：

1. 纯文本记事：面板拖动 → 行数实时变化；确定后落库；重进首页仍生效。
2. 清单记事：同上；并确认字号在上限改变前后符合新公式（少项变大、≥8 项恒 14sp）。
3. 在面板里切换卡片宽度：读数换到另一档，两档互不影响。
4. 滑块拖回全局默认值 → 标签回到「（默认）」，且字段真的没写进 JSON。
5. 改全局默认 → 返回首页立即生效，且未覆盖的记事跟着变、已覆盖的不变。
6. 单一记事 widget 配置页跟随；`DoingActivity`、文件夹预览、桌面小组件不跟随。
7. 面板最高的那种组合（宽 + 侧边封面）下按钮行没有被裁。
8. 清单字号公式改动对现有卡片的观感影响——这是全局可见变化，要专门比对。

## 不做 / 后续

- 桌面小组件（真 RemoteViews）的行数与条数上限：本次完全不动，后续单独处理，
  届时要同步改 `estimateWidgetHeightDp` / `estimateWidgetChecklistHeightDp`。
- 文件夹卡片外观面板里调「每条预览显示几行」。
- 0 档（完全不显示内容文本 / 清单）与「不限」档。
