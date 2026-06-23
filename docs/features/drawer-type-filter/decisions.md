# Drawer Type Filter — Decisions

Decisions from the grill session on 2026-06-21. Numbered in discussion order.

> 注意（2026-06-23）：本文为时间线决策记录，部分早期条目已被后续条目自我推翻
> （如 状态分段控件的“胶囊”视觉最终改为圆形类型按钮、“已归档”状态被放弃只保留
> 正在进行/已完成/回收站）。此外，关于文件夹的部分已被 thing-folders 的**纯骨架模型**
> 取代：文件夹没有删除状态、不会“进入回收站”；内容批量操作**跟随当前类型筛选**。
> 文件夹/回收站的权威行为以 `docs/features/thing-folders/use-cases.md` 为准。

---

## 2026-06-22 - 状态改为单个分段胶囊，移除独立回收站行

状态筛选改为一个分段胶囊控件（`ThingStatusSegmentedView`），上下布局中位于类型圆形按钮行之上。三个状态固定左到右排列：正在进行 / 已完成 / 回收站。选中段展开显示 icon+文字并被填充，其余两段收缩为只显示 icon 并停在两侧；切换有展开/收缩动画。整个胶囊有 Scope 渐变描边，**描边与选中段填充使用同一条横跨整个胶囊宽度的渐变**（父视图统一用一个 shader 绘制轮廓和填充，二者融为一条连续渐变，不分开渲染）。各段触摸有 ripple（前景 RippleDrawable）。

由此，下方独立的“回收站”Drawer 行被移除——回收站成为状态分段的一段。Drawer 的选中行始终是当前 Thing Scope（全部记事根或当前文件夹），不再因状态变化丢失或转移选中项，解决了“点回收站使文件夹列表失去选中项、再点文件夹却进入该文件夹的已删除投影”的怪异交互。

抽屉关闭行为更新（覆盖前一条“状态胶囊点击关闭抽屉”）：状态分段点击**不再关闭抽屉**，以便看到展开/收缩动画并继续组合筛选；类型圆形按钮点击也不关闭；只有 Scope 行（全部记事/文件夹）作为导航关闭抽屉。状态切换仍保留当前 Scope 和类型筛选。

本条取代下面“左右胶囊布局”中关于状态行用两个胶囊、以及更早“状态胶囊关闭抽屉”的描述。类型圆形按钮（全部类型独占一行、记录/提醒、习惯/目标）保持不变。

## 2026-06-22 - Drawer 状态+类型筛选区改为左右胶囊布局

文件夹（Thing Scope）区域下方是“记事状态 + 类型筛选”区域，分为左右两部分。

左侧：记事状态筛选，单选，两行：正在进行、已完成。回收站不在这里，作为独立区域放在筛选区下方。

右侧：记事类型筛选，三行：第一行“全部类型”单独占一行；第二行“记录 / 提醒”；第三行“习惯 / 目标”。其中“全部类型”与后四个的关系沿用既有规则——全部类型为单选/排他，记录/提醒/习惯/目标可多选，全部取消时自动回到全部类型。去掉此前 icon 上方的“记事类型”summary 文本提示。

每个选项用一个胶囊（capsule/pill）呈现，胶囊内含 icon + 文本：

- 未选中：透明内部填充、有轮廓；icon、文本、轮廓颜色在浅色模式偏黑、暗色模式偏白。
- 选中：实心填充、无轮廓；填充色在“全部记事”根范围下为 accent + accent2 渐变，在某个文件夹范围下为该文件夹的颜色（纯色或渐变）；icon 和文本颜色根据填充色自适应为偏黑或偏白。
- 触摸时胶囊需要有 ripple 效果。

该筛选区域的 View 应单独抽离为可复用组件，记事列表 Widget 配置界面复用同一组件。

抽屉关闭行为（2026-06-22 确认，更新旧决议 #15/#26）：类型胶囊点击不关闭抽屉；状态胶囊（正在进行/已完成）点击关闭抽屉（沿用旧决议，作为导航处理）；Scope 行（全部记事/文件夹）和回收站行作为导航关闭抽屉。

选中态分区独立：Thing Scope 区高亮当前 Scope 行，状态区高亮当前状态胶囊，类型区高亮当前类型胶囊，三者互不干扰。进入回收站独立区域时高亮回收站行，Scope/状态区不再高亮。这条取代此前“Drawer 恰好一个选中项”的旧偏好。

该决议取代前述第 5、8、10、11 条中关于状态项位置、类型行分隔线样式、icon 上方 summary 文本的描述。

---

## 2026-06-22 - 保留 DELETED 命名，回收站是 DELETED 的 UI 呈现

不把代码里的 `DELETED` 重命名为 Trash/Trashed。`Def.ThingStatus.UNDERWAY/FINISHED/DELETED` 三者都保留，代码语义和产品语义都用 DELETED；“回收站”只是 DELETED 在中文 UI 上的标签。新模型中“回收站是独立区域”指的是它在 Drawer 中不再作为状态胶囊出现，而是放在筛选区下方的独立目的地行，内部仍是 `Def.ThingStatus.DELETED`。状态胶囊区只含正在进行（UNDERWAY）和已完成（FINISHED）。

---

## 2026-06-22 - 记事列表 Widget 支持状态维度

记事列表 Widget 配置加入状态维度，与 App 主体的“Scope × 状态 × 类型”三维对齐。Widget 状态只含正在进行（UNDERWAY）和已完成（FINISHED），不含回收站——桌面 Widget 指向回收站没有意义，且 use-cases W3 已规定指向已删除文件夹的 Widget 应持久回退到“全部记事”。

实现需启用 `COLUMN_STATUS_APP_WIDGET`，并打通 DB schema、DAO 插入、Widget 配置 UI 和 Widget service 的状态读写。Widget 配置 UI 复用从 Drawer 抽离的状态+类型胶囊筛选组件。此条取代此前“首个文件夹 Widget 切片状态固定为 Underway”的决议。

---

## 2026-06-22 - “全部记事”是范围根选项

Drawer 顶部的文件夹区域改为 Thing Scope 选择区。该区域中的根选项中文显示为“全部记事”，语义是不限定 Thing Folder，而不是忽略状态和类型的总览。

记事列表由三个并列维度共同决定：Thing Scope（全部记事或某个文件夹）、记事状态（正在进行、已完成、回收站）和记事类型（全部类型、记录、提醒、习惯、目标）。例如“全部记事 + 已完成 + 提醒”显示所有文件夹范围内已完成的提醒；“文件夹 A + 正在进行 + 全部类型”显示文件夹 A 中正在进行的所有类型记事。

这条决议取代此前“正在进行位于顶部并作为文件夹树根目录”的 Drawer 语义；“正在进行”应归入状态筛选区，而不是文件夹区域。

## 2026-06-22 - 状态切换保留当前范围

Drawer 中切换记事状态时应保留当前 Thing Scope 和当前类型筛选。例如“文件夹 A + 正在进行 + 全部类型”切到“已完成”后，应变成“文件夹 A + 已完成 + 全部类型”，而不是退回“全部记事 + 已完成 + 全部类型”。

如果当前文件夹范围在目标状态下不可进入，例如已删除文件夹范围切回非回收站状态，则直接退回“全部记事”范围，并保留用户选择的目标状态和类型筛选。

## 2026-06-22 - “已归档”是新状态，“回收站”是独立区域

Drawer 的状态筛选区只包含“正在进行 / 已完成 / 已归档”。“已归档”是新的用户可见状态，表示保留但从日常进行/完成列表中收起的内容；它不能复用现有回收站数据。

“回收站”不再作为状态筛选胶囊出现，而是放在筛选区域下方的独立 Drawer 区域。回收站展示 Trashed Things 和 Trashed Thing Folders，用于恢复或永久删除；它不影响文件夹范围列表，也不改变 Thing Scope 的选择规则。

数据库中的现有删除语义应继续归属回收站。实现时可以重命名代码里的旧 `DELETED` 语义为更准确的 Trash/Trashed 命名，但不能把现有删除数据解释为已归档。

Thing Folders 本身也支持归档，但必须区分“文件夹自身已归档”和“文件夹因为包含已归档内容而出现在已归档投影中”。后者只是 Archive Projection Folder：它作为路径容器承载命中的已归档子项，不代表文件夹自身已归档。

用户只有在 Drawer 选中“正在进行”状态时，才能在某个文件夹范围内继续创建新 Thing；新 Thing 的自身状态是“正在进行”，不会仅因为当前文件夹曾在已归档投影中出现而自动归档。

已归档投影中的路径容器不能暴露“取消归档文件夹”这类只适用于文件夹自身归档状态的操作。Folder Card 需要区分文件夹自身已归档和只是承载已归档命中内容两种情况。创建入口只在“正在进行”状态下可用；“已完成 / 已归档 / 回收站”中应隐藏或禁用创建入口。

文件夹自身归档后，其子树进入归档语义。归档文件夹在 Drawer 文件夹区域中的可见性、以及是否允许在该范围内创建新的正在进行 Thing，仍需进一步确认。

## 2026-06-22 - 作废：不引入独立归档状态

前述“已归档”分支作废。归档与完成在当前产品语义中重复，不应新增独立归档状态。Drawer 的日常状态筛选应回到“正在进行 / 已完成”两个状态；“已完成”承担从正在进行列表中收起并保留内容的语义。

“回收站”仍然保持独立区域语义，用于展示 Trashed Things 和 Trashed Thing Folders，并支持恢复或永久删除。回收站不是“完成”的别名，也不应参与日常状态筛选胶囊。

## 2026-06-22 - 文件夹范围与回收站的稳定边界

已确认的工作模型：Thing Folder 本身没有完成状态；“完成文件夹”表示递归完成其内容，而不是把容器本身标为完成。Drawer 的文件夹范围区域、移动到文件夹 Dialog、以及记事列表 Widget 配置中的文件夹范围选择器都显示所有未进入回收站的 Thing Folders，不受正在进行/已完成状态筛选和类型筛选影响。

回收站是独立的状态投影。~~Thing Folder 本身进入回收站后，从这些正常范围选择器中消失~~——**已被纯骨架模型取代（2026-06-23）**：文件夹没有删除状态，永远留在范围选择器里（只有解散/永久删除才会消失）。文件夹内的 Things 进入回收站后，该文件夹仍留在范围选择器中，并在回收站投影里以 Projection Folder 显示其已删记事；列表投影（含回收站）不显示没有命中内容的文件夹。范围选择器（Drawer/移动 Dialog/Widget 配置）仍不受状态/类型筛选影响——但**列表里的 Folder Card 受筛选影响**（子树无命中记事时隐藏）。

### 1. Type items become filters, not navigation targets

The four type items (记录/提醒/习惯/目标) change from mutually-exclusive
navigation destinations to a multi-select filter overlay on the current status
+ folder view. They combine with the folder selection above and the 已完成/已删除
status switches below.

### 2. Status switching: mutually exclusive three-way

正在进行 / 已完成 / 已删除 are mutually exclusive (single-select). Only one is
active at a time. This replaces the old limit-based routing where each type had
its own underway variant.

### 3. 正在进行 stays at the top

正在进行 remains an independent clickable item at the top of the Drawer, above
the folder tree. Clicking it navigates back to UNDERWAY status without resetting
the type filter.

### 4. Folder selection unchanged

The folder tree remains single-select and unchanged in behaviour. 正在进行 and
the folder section need no UI changes.

### 5. New Drawer structure (top to bottom)

```
Header
─────────────────
正在进行
─────────────────
[Folder tree]
───────────────── divider
[Summary text: "全部类型" / "记录/提醒" …]
[全部] [记录] [提醒] [习惯] [目标]
───────────────── divider
已完成 / 回收站
─────────────────
Settings / Help / About
```

### 6. Type icons rendered as a RecyclerView item (option A)

A new `ItemKey` variant and dedicated `TypeFilterHolder` inside
`DrawerNavigationView.DrawerAdapter`. Inflates a horizontal LinearLayout with 5
ImageViews, not a standalone header/footer view.

### 7. Type filter persists across status switches (option A)

Switching from 正在进行 to 已完成 to 已删除 keeps the type filter mask active.
e.g. selecting "提醒 + 习惯" then tapping 已完成 shows finished reminders and habits.

### 8. Visual style: Drawer row with separators (option C)

The type-filter row looks like a normal Drawer item but with divider lines above
and below, marking it as a distinct filter section. Icon tinting uses
`app_chrome_drawer_item_foreground` (light-on-dark Drawer background).

### 9. "All" icon semantics match widget config

Tapping 全部 resets to all types (mask = 0). When the last specific type is
deselected, auto-returns to 全部. 全部 is visually "selected" only when no
specific types are active.

### 10. Dividers: both above and below (option C)

The existing divider lines that separate the type section from folders above and
完成/删除 below are preserved, bracketing the type-filter row as a distinct area.

### 11. Summary text above icons

A label above the 5 icons shows the current selection: "全部类型" (none specific),
"记录" (single), "记录/提醒" (two), "记录/提醒/目标" (three), or
"记录/提醒/习惯/目标" (all four). Format matches the existing widget-config
pattern.

### 12. Type filter triggers data refresh via ThingManager

Tapping a type icon calls `ThingManager.setTypeFilterMask(mask)` and refreshes
the current active status query (正在进行/已完成/已删除 + current folder +
type mask). Reuses the existing `ThingDAO.getThingsForTypeFilterProjection()` path.

### 13. Method name: drop "Underway" qualifier

`setUnderwayTypeFilterMask` → `setTypeFilterMask` because the filter now applies
to all status values, not just UNDERWAY.

### 14. Type filter NOT persisted across app restarts

Resets to 全部 on each cold start. No database persistence for the Drawer's
type filter mask.

### 15. Type icon taps do NOT close the Drawer (option A)

Users can multi-select types without the Drawer closing after each tap. Only
导航 items (正在进行, folders, 已完成, 已删除, Settings, Help, About) close it.

### 16. ActivityHeader title: status or folder name, not type

The header shows the current status name or folder name. It does NOT include the
type filter selection. e.g. "工作" (folder) or "已完成" (status).

### 17. DrawerHeader location text: folder name > status name (option A)

If inside a folder, show the folder name. Otherwise show the status name
("正在进行" / "已完成" / "回收站"). Does not include type filter.

### 18. limit → status + typeFilterMask: in-scope, done in one pass

Remove `Def.LimitForGettingThings` constants (0-6). Replace with:
- `STATUS_UNDERWAY = 0`, `STATUS_FINISHED = 1`, `STATUS_DELETED = 2`
- `typeFilterMask` (Int bitmask, same constants as `ThingWidgetInfo`)

### 19. DAO layer: status + typeFilterMask both passed

New/modified DAO methods accept both `status` and `typeFilterMask`, replacing
the old `limit` parameter. `typeFilterMask` works for all status values including
FINISHED and DELETED.

### 20. NOTIFY_EMPTY: 7 variants kept, remapped

- UNDERWAY: 5 (generic + note/reminder/habit/goal type-specific)
- FINISHED: 1 (generic)
- DELETED: 1 (generic)
- `Thing.getLimits()` removed alongside `limit`. Call sites use status + mask directly.

### 21. ThingWidgetInfo: status + typeFilterMask

Widget info stores both fields explicitly instead of deriving status from `limit`.

### 22. ThingListProjection: status + typeFilterMask (option A)

`status: Int` and `typeFilterMask: Int` as independent fields. `typeFilterMask`
works for all status values (including FINISHED).

### 23. ThingManager: mStatus + mTypeFilterMask

`mLimit` field removed. `setLimit()` → `setStatus()`. `setUnderwayTypeFilterMask()`
→ `setTypeFilterMask()`.

### 24. menu_drawer.xml: remove 4 type item IDs

`drawer_note`, `drawer_reminder`, `drawer_habit`, `drawer_goal` menu IDs removed
(only referenced in ThingsActivity.kt and menu_drawer.xml; the drawable PNGs
referenced by StatisticActivity and settings layout stay). `drawer_underway`,
`drawer_finished`, `drawer_deleted` remain.

### 25. TypeFilterHolder: dedicated ViewHolder with LinearLayout

A new view type in `DrawerAdapter` that inflates a horizontal LinearLayout
containing 5 ImageViews + the summary TextView above. To avoid duplication,
reuse or extract the widget config's bitmask toggle logic.

### 26. Drawer close behaviour: only 导航 items

| Action | Closes Drawer |
|---|---|
| Type icon tap | No |
| 正在进行 / 已完成 / 回收站 | Yes |
| Folder tap | Yes |
| Settings / Help / About | Yes |

### 27. Type-filter empty states are projection-only placeholders

When a custom type filter is active, `NOTIFY_EMPTY` rows are not treated as
members of the selected type. The list projection checks each selected concrete
type separately:

- a direct visible Thing of that type counts as content;
- a visible child Folder whose descendants contain that type also counts as
  content;
- only when both checks are empty does the projection add a transient
  `NOTIFY_EMPTY_*` card for that type.

These cards are built in memory for the current projection and are not inserted
into the database. ActivityHeader counts continue to count only real Things and
matching Folder descendants, so empty placeholders do not inflate type counts.
