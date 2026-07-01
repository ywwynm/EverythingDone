# Edge-to-Edge 记事列表 决策记录

ThingsActivity 记事列表的 edge-to-edge（沉浸式）滚动：Activity Header 折叠进
actionbar 之后，继续下滑把"状态栏底色条＋actionbar"整体上移隐藏，让 Thing Card
列表从状态栏一直铺到导航栏；上滑再让顶部 chrome 回来。仅 NORMAL 与 MOVING 模式生效，
SELECTING 与搜索（`App.isSearching`）不生效。

## 现状事实（探查所得，非决策）

- 顶部 chrome 两层：`view_status_bar`（不透明 surface 色底条）＋ 其下 `actionbar`
  （透明底 Toolbar）。`rv_things` 从 actionbar 之下起（`marginTop=actionBarSize`）。
- Activity Header 是浮层 `rl_header`（标题/副标题），随列表滑动缩放＋上移，约 90dp
  时折叠进 actionbar 中央，由 `ActivityHeader.updateAll()` 每帧驱动；列表首项是一个
  高度动态的 header spacer。
- 底部已经是 edge-to-edge：`navigationBarColor` 主题里已是 transparent，
  `rv_things` `clipToPadding=false` 且底部有导航栏高度的滚动 padding，卡片本就滑到
  导航栏之下。本特性主要新增的是**顶部**行为。
- 模式：`ModeManager` 只有 NORMAL / MOVING / SELECTING；搜索是独立的 `App.isSearching`。

## 决策

### 2026-07-01 — 隐藏/显示交互模型：连续跟手＋松手吸附

顶部 chrome 的上移量与"超出折叠点后的滑动量"1:1 联动（下滑上移、上滑回落），松手时
按当前位置/速度吸附到"全显示"或"全隐藏"两个末态。
理由：最贴近原生 Material 行为，且与现有 Activity Header 折叠的逐帧连续动画同构，
可以复用现有 `onScrolled` 驱动 ＋ `onScrollStateChanged` 吸附，改动收敛。
放弃的备选：方向阈值切换（固定时长动画整体移出/移回）——实现更简单，但跟手感弱、
需额外防抖，且与现有连续折叠不同构。

### 2026-07-01 — 状态栏可读性：保护性渐变罩

进入沉浸态后，状态栏区域不做成完全透明，而是保留一层很轻的半透明渐变罩（顶部稍深、
向下渐透），卡片仍从其下隐约透出，但时钟/电量/信号图标在任意卡片颜色下都保持可读。
理由：最稳、实现最简，与常见 status bar protection 做法一致。
放弃的备选：①完全透明＋图标按 surface 亮暗固定——深色/高饱和卡片滑到图标背后会丢
对比度；②动态采样翻转图标明暗——最润但实现最复杂、性能与稳定性风险最高。

### 2026-07-01 — 折叠与隐藏解耦（enterAlways，小标题 pinned）

"折叠"（大标题→小标题）由**滚动位置**驱动，只有滑回列表顶部才重新展开大标题；
"隐藏/显示"（顶部 chrome 上移/回落）由**滚动方向**驱动（enterAlways：任意上滑即按
比例回落）。因此在列表深处上滑让 actionbar 回来时，只呈现已折叠的小标题（当前状态名/
文件夹名，居中在 actionbar 里）＋阴影，与今天的折叠态一致。
理由：与 Material 折叠工具栏语义一致，避免大标题在列表中段反复弹出。
实现含义：需要把"折叠进度 headerCollapseProgress"与新的"chrome 隐藏位移量"拆成两个
独立量，前者复用现有位置驱动逻辑，后者新增方向驱动逻辑。

### 2026-07-01 — 模式切换与复位策略

沉浸态只属于 NORMAL / MOVING。整套策略：
- 进入搜索（`App.isSearching`）：搜索框就在 actionbar 里，强制显示常规 chrome、退出沉浸。
- 进入选择（SELECTING）：`contextual_toolbar` 覆盖顶部，列表按非沉浸呈现（内容不钻到
  toolbar 之下）。
- NORMAL ↔ MOVING：保留当前 chrome 隐藏位移（长按进入拖动是连续体验，不打断）。
- 列表重载 / 切换范围（抽屉换状态、开/关文件夹、搜索结果返回）：滚到顶并复位为
  "展开＋显示"。
- **从 SELECTING / 搜索返回 NORMAL：复位为"显示"**（actionbar 可见、不自动隐藏），
  保留当前滚动位置；想再沉浸需再次下滑。放弃"恢复进入前的沉浸位移"——需多存状态，
  且关闭选择后 chrome 立刻又滑走显得跳。

### 2026-07-01 — FAB 保持现状、与 actionbar 各自独立

`FloatingActionButton.attachToRecyclerView()` 已自带滚动监听：`dy>0`（下滑）
`hideToBottom()`、`dy<0`（上滑）`showFromBottom()`——从列表顶部起就生效、离散
200ms 切换，独立于 Header 折叠。本特性不改它：actionbar 用新的"折叠后连续跟手＋吸附"，
FAB 沿用现有即时隐现。二者都响应滚动方向，进入时机不完全同步（FAB 先走），但沉浸态
（下滑到底）下 FAB 天然也是隐藏的，结果一致。
放弃的备选：把 FAB 与 actionbar 统一成一体、都在折叠后才进退——更整体，但要改 FAB
现有"即时隐藏"手感，属额外行为变更。

### 2026-07-01 — 规范命名：Immersive Thing List / Home Chrome Retraction

概念定名为 **Immersive Thing List**（沉浸式记事列表，那个"顶部 chrome 收起、列表全铺"的
呈现态），配套行为 **Home Chrome Retraction**（顶部 App Chrome 随滚动收起/回落）。
不沿用"edge-to-edge"作专名——窗口层早已 edge-to-edge（`setDecorFitsSystemWindows(false)`、
导航栏透明、列表底部已铺到导航栏之下），复用该词会与新状态混淆。术语已写入根目录
`CONTEXT.md`（含 **Activity Header** 的补充定义与一条 Flagged Ambiguity）。

### 2026-07-01 — 实现路线：手写滚动驱动（不迁 CoordinatorLayout）→ ADR-0013

复用现有 `ActivityHeader` / `onScrolled` / decor inset chain，新增一个沉浸控制器驱动顶部浮层
`translationY`；`rv_things` 改铺满全高 + `paddingTop=状态栏+actionBar` 预留，使内容始终绘制
在顶部 chrome 之下、收起即露出。放弃迁移 CoordinatorLayout / AppBarLayout——需重写高度定制的
ActivityHeader、DrawerLayout 集成、contextual toolbar 浮层及大量既有滚动联动，风险与工作量大。
理由与影响详见 [ADR-0013](../../adr/0013-immersive-thing-list-manual-scroll-chrome-retraction.md)。

### 2026-07-01 — 原地改单项保留沉浸态

原地完成 / 删除 / 恢复单项（`updateUIAfterStateUpdated`，保留滚动位置）**保留** Home Chrome
Retraction——滑到深处对单项操作后顶栏不弹回。实现：`refreshActivitySurfaceAndHeader` 加
`resetRetraction` 形参，仅该路径传 `false`；其余刷新（切范围 / 换状态 / 开关文件夹 / 进出选择或
搜索）仍复位为显示。并在 `ActivityHeader.updateHeader` 加不变量：`progress<1` 时强制 retraction=0，
使"删项使列表变短、不再完全折叠"时安全退回，杜绝"顶栏已隐藏但标题已展开"的错乱。
（修订了初版"原地改单项一并复位为显示"的宽松实现。）

### 2026-07-01 — 真机反馈修复：scrim 高度与 actionbar 不透明

首个真机版暴露两处，均因新方案下 chrome 与内容的层叠关系变化：

1. **状态栏 scrim 覆盖大半屏**：`view_status_bar` 原是 `DrawerLayout` 的内容子 View，而
   DrawerLayout 会把内容子 View **强制测量成整屏高**（忽略代码设的 `height=SB`）。旧方案里它被
   `fl_things` 盖住、只露顶部 SB 所以无碍；移到 `fl_things` 之上后整屏高的渐变露出、覆盖大半屏。
   修法：把 `view_status_bar` 移进 `fl_things`（FrameLayout 尊重 `height=SB`）。SB 高的 scrim 在
   静止态是"surface 罩在同色 surface 上"、本就不可见，"一开始不该显示 scrim"随之满足，无需再给
   scrim 做动态透明度。

2. **actionbar 透明、上滑露出时透出卡片**：actionbar 一直是透明的，旧方案靠 rv 的 top padding
   使卡片不进 actionbar 区；新方案卡片会滑到 actionbar 之下，显示时透出卡片。修法：给 actionbar
   设**不透明 surface 背景**（`applyThingsActivitySurfaceBackground` 里 `applyBackground(actionbar,…)`），
   与状态栏/列表同色——显示时是实心栏、收起时随 translationY 上移滑走、露出其下卡片。

### 2026-07-01 — 推翻"保护性渐变罩"：状态栏占位与 actionbar 连体不透明、一起收起

真机反馈：actionbar 上滑显示时，其上方状态栏若仍是半透明 scrim 会透出卡片、且与实心 actionbar
割裂。改为——**状态栏占位 `view_status_bar` 与 actionbar 连体**：同为不透明 surface 背景，并纳入
`ActivityHeader.applyRetractionTransforms`／`reset` 一起随 Home Chrome Retraction 上移隐藏、下移回落。
这**推翻了本特性早期"保留一层半透明保护罩"的决策**（那条 grill 结论）。
- 代价：完全沉浸（顶栏全收起）时，系统状态栏图标落在裸卡片上、无遮罩保护。用户在真机反馈中明确
  选择"连体"优先；图标可读性若后续需要，再单独考虑（例如仅沉浸态下加一层独立保护罩）。已同步
  `CONTEXT.md` 的 **Home Chrome Retraction**。

### 2026-07-01 — 沉浸态专属保护罩：用层叠顺序实现（无透明度动画）

按用户反馈"只在完全沉浸态下再叠一层独立保护罩"。新增固定视图 `view_status_bar_scrim`（SB 高、
surface 派生渐变），关键是**层叠顺序**：它在列表之上、但在连体顶栏（`view_status_bar` 与 actionbar）
之下。于是——显示/静止态被不透明的连体状态栏底色盖住而天然不可见；顶栏收起后连体底色滑走，才把
它露出、保护系统图标落在裸卡片上时的可读性。因此**不需要任何透明度动画**，纯靠 z 序做到"只在沉浸
态显示"。
- 它自身不平移（不随 chrome 收起）；仅在进入选择模式时随 home chrome 一并隐藏（`setHomeChromeVisible`
  也管它），避免 contextual 滑入过程中因连体底色被隐藏而短暂露出。
- 渐变：**两档线性** `[顶 230 → 底 0]`（alpha，0..255）。初版用了三档 `[230,140,0]`，中间那档使
  上半段近似实心带、只有下半段淡出，底部会有隐约分界线；改两档后从上到下匀速淡到底 0，衔接干净。
  顶部 alpha 可按视觉再调（用户已手动设为 214）。

### 2026-07-01 — 沉浸态按投影记忆、随滚动位置一起恢复

进文件夹再返回上一级（或面包屑跳回祖先）时，若之前在该投影处于沉浸态，返回后应仍沉浸。做法：
新增 `mProjectionRetractionStates`（projectionKey → retraction 位移），在 `saveCurrentProjectionScrollState`
里与滚动位置一起记忆；`restoreProjectionScrollStateOrTop` 恢复滚动时，把记忆的沉浸位移传给
`requestActivityHeaderStateRefreshBeforeDraw`，在 pre-draw 的 `updateAll` 之后、**仅当已完全折叠时**
应用（否则维持显示）。这让 retraction 与既有"按投影记忆滚动位置"行为对齐——之前我在刷新时一律
复位为显示，与滚动位置被恢复相矛盾。回顶部/无记忆的分支仍复位为显示。

### 2026-07-01 — 进入选择模式直接显示 contextual，不先显示 home actionbar

真机反馈：进入选择模式会"先显示 home actionbar、再滑入 contextual toolbar"，两次进入动画。根因是
contextual toolbar 从顶部 `-100%p` 滑入（360ms）会露出下方 home chrome，且 actionbar 改不透明后更明显，
再叠加此前"进入选择即 `setRetractionOffset(0)`"把沉浸时隐藏的 home actionbar 弹出。
改为：`ActivityHeader.setHomeChromeVisible(false)` 在进入选择时**整体隐藏 home 顶部 chrome**（状态栏占位
＋actionbar＋标题），contextual 滑入过程不再露出 home actionbar；退出选择时 `setHomeChromeVisible(true)`
随 contextual 滑出而恢复，并复位 retraction 为显示。进入选择不再复位 retraction（避免多余弹出动画）。
`reset()` 也一并把 home chrome 恢复可见，作为"完全展开显示"的不变量。

### 2026-07-01 — 折叠滚动距离跟随 header 高度（= spacer 高 − 12dp 余量），修多行标题间距 ＋ 提前沉浸

真机反馈：打开名字较长（标题多行）的文件夹，上滑到标题归位 actionbar 时，第一张卡片离 actionbar 明显
比单行标题时远，且**标题行数越多越远**；沉浸判定也可能提前触发（卡片还没贴齐就开始收起顶栏）。

根因：标题折叠进度由 `scrollY / 固定 90dp` 驱动（progress 在 90dp 处到 1），但 header spacer 高度随标题
行数增长。第一张卡片到 actionbar 的距离 = `spacer高 − scrollY`；标题在 `scrollY=90dp` 归位时，该距离 =
`spacer高 − 90dp`，随标题行数（spacer 增高）线性变大。`isFullyCollapsed()`（progress≥1）驱动 Home Chrome
Retraction，故沉浸也在卡片仍远离时提前触发——同一根因。

改法：`getTitleCollapseScrollY()` 从固定 `90dp` 改为 **`getHeaderSpacerScrollY() − 12dp`**。语义：第一张卡片
在 `scrollY = spacer高` 处贴到 actionbar，让标题恰在其前 `12dp`（新常量 `TITLE_DOCK_RESIDUAL_DP`）完成折叠；
于是"标题归位"时卡片到 actionbar 的距离恒为 `12dp`，与标题高度无关、与单行时一致，随后 12dp 内卡片贴齐并
淡入 actionbar 阴影。`12dp` 正是历史常量的还原：默认 spacer `102dp` = 折叠 `90dp` + 阴影淡入 `12dp`，故单行/根
标题下折叠距离回到 `90dp`，行为无回归。沉浸判定随折叠距离一并后移，改为在卡片真正贴齐后才触发。

配套：`updateAll` 里三处"强制折叠"的硬编码 `(90*density)` 改为 `ceil(getTitleCollapseScrollY())`（用 ceil 保证
progress 取到 1，避免多行时折叠距离含小数、`toInt` 下取整让 `isFullyCollapsed` 永远为 false、retraction 失效）；
阴影淡入公式的硬编码 `90` 改用动态折叠点 `titleAndShadowScrollY`、区间用 `TITLE_DOCK_RESIDUAL_DP`。

放弃的备选：「折叠距离 = 90dp + (标题行数−1)×行高」。数值核算发现单行 spacer 被 `coerceAtLeast(102dp)` 托底
（实测 computed≈81dp<102），该式会让多行折叠距离**超过** spacer 最大滚动量，progress 永远到不了 1、折叠无法
完成——是回归。改用「spacer 高 − 固定余量」后对所有标题高度都精确、且无需单独测量标题行数。

### 2026-07-01 — actionbar 到第一张卡片的间距统一为 16dp（= 卡片间距）

真机反馈：搜索态停在顶部时首卡到 actionbar 的间距，比非搜索折叠态（标题归位那一刻）小；且非搜索那个间距比
卡片之间的 16dp 还大一些。根因是这两处间距各来自零散魔法数：搜索态 header spacer 固定 6dp、非搜索折叠余量
`TITLE_DOCK_RESIDUAL_DP` 为 12dp，加上卡片自带的 8dp 上边距，分别得 14dp 与 20dp，彼此不一致、也不等于 16dp。

决策（经询问用户，选定 16dp = 卡片间距）：让两处间距都等于卡片之间的间距 16dp。统一由一个 8dp 单位
（`thing_card_outer_spacing`）推导——卡片自带 8dp 上边距，再在 actionbar 之下预留 8dp，合计 16dp：
- 非搜索折叠态：`TITLE_DOCK_RESIDUAL_DP` 由 12dp 改为 8dp。折叠距离 = spacer 高 − 8dp；标题归位（progress=1）
  那一刻首卡到 actionbar = 8dp(余量) + 8dp(卡片上边距) = 16dp，且对所有标题行数恒定（沿用上一条「spacer − 余量」
  模型，仅改常量）。默认 spacer 102dp 下折叠距离由 90dp 变 94dp、阴影淡入区间由 12dp 变 8dp（均随该常量联动）。
- 搜索态：`ThingsAdapter.getActivityHeaderSpacerHeight()` 的搜索分支 spacer 由 6dp 改为 `thing_card_outer_spacing`
  (8dp)，首卡到 actionbar = 8dp + 8dp = 16dp。

放弃 8dp（= 卡片到屏幕边距）方案：需把折叠余量压到近 0，阴影淡入区间随之趋零、不够平滑；16dp 与卡片纵向
间距同律，视觉节奏最统一，也正好把用户觉得偏大的非搜索间距收到 16dp。
