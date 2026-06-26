# 多级清单项 —— 会话历史

## 2026-06-26 - 设计追问（grill-with-docs）

- 为“最多三级的多级清单项”做了一轮完整 grill，逐条敲定数据模型、存储编码、完成语义、缩进交互、
  分级排版、拖拽、各显示界面与新建项层级，全部记入 `decisions.md`。
- 关键结论：层级显式逐项存储、归属由位置派生；存储用“状态位 + 独立层级位”（方案 B）+ 一次性
  数据库迁移（落 `docs/adr/0010`）；完成模型按“组”整组迁移、组内深层项就地完成、完成/取消都向
  下级联子树；禁止孤儿（缩进门控）使“组根 ⟺ 一级项”、允许层级跳空；完成逻辑收敛到
  `CheckListHelper.toggleChecklistItem` 单一真理来源供 7 个界面共享；单一文本输出改标准 Markdown
  任务列表；拖拽用“组根代理 + 子树收束动画”。
- 根 `CONTEXT.md` 新增 Checklist / Checklist Item / Checklist Item Level / Checklist Item Owner /
  Checklist Group Root / Checklist Item Group 等术语与关系。
- 实现取向、分阶段与待调参数见 `plan.md`，实现勾选清单见 `execution.md`；对称增强见 `followups.md`。

## 2026-06-26 - 首个可测版本实现并发布

- 按 7 个阶段实现完毕并发布到阿里云 debug 渠道（update code 202606260306，发布日志
  `debug-updates/update-20260626110549.md`）。
- 落地：存储层级位 + DB v21 迁移；`CheckListHelper.toggleChecklistItem` 委托给新的
  `ChecklistCompletion` 组感知状态机；详情页 `check_list_et.xml` 重排（左反缩进槽 + 右缩进，去掉删除
  图标），`CheckListAdapter` 按级缩进/字号/淡化 + 缩进门控 + 完成委托；`AppWidgetHelper` RemoteViews
  按级缩进/字号/颜色；通知/分享/复制改 Markdown 任务列表；`DetailActivity.moveChecklist` 改为兄弟带内
  子树块移动、撤销走 `UPDATE_CHECKLIST` 内容串。
- **遗留**：拖拽“收束动画 + 角标”未实现（子树移动数据正确、但无收束动画、跨大子树会跳变）；
  各界面缩进步长与 0.9 系数待真机微调。详见 `execution.md` / `grill` 决策。

## 2026-06-26 - 修复对齐与反缩进按钮（update 202606260322）

针对首版反馈修三处：(1) 详情缩进步长改为按图标实际左缘对齐父项文本（扣除 36dp 控件内图标居中留白），
缩进量减小；(2) 缩进/反缩进改用右/左箭头矢量图标 `ic_checklist_indent`/`ic_checklist_outdent`，反缩进按钮
改为锚定父起点 + `applyLevelStyle` 运行时显式 marginStart 定位到状态图标左侧，二级及以上聚焦必现；
(3) 详情与卡片状态图标与文字改垂直居中（小字号不再偏上）。发布日志 `debug-updates/update-20260626112211.md`。

## 2026-06-26 - 缩进调小 + 箭头跟随颜色变小 + 首行对齐（update 202606260342）

第二轮反馈三处：(1) 缩进全面调小——详情每级 24dp、卡片每级约图标宽 ×0.63、widget 每级 13dp；
(2) 缩进/反缩进箭头按该级文字颜色着色（含分级透明度，不再纯白），控件缩到 24dp、箭头约 16dp；
(3) 状态图标与清单项**第一行**文字垂直居中（多行只对齐第一行）——`applyLevelStyle` 与新增 `alignCardRow`
按 `lineHeight` 与图标高算 topMargin 实现，详情与卡片均改。发布日志 `debug-updates/update-20260626114142.md`。

## 2026-06-26 - 已完成区首项门控 + 箭头完成色 + 缩进28dp + 首行视觉对齐（update 202606260405）

第三轮反馈四处：(1) `ownerIndexOf`/`canIndent` 改为遇控制标记即 `break`（区域边界），底部已完成区第一项
缩进按钮隐藏、不再制造跨区孤儿；(2) 箭头 tint 改为按完成状态取色（已完成用 `textColorFinished`，含分级
透明度），与状态图标/文本一致；(3) 详情缩进 24→28dp、缩进/反缩进按钮控件 24→28dp；(4) 首行对齐改用
字体度量 `fontMetrics`（ascent/descent）对齐**视觉中心**而非行高几何中点，新增 `firstLineAlign` 供详情与
卡片共用，widget 文字加 3dp 下压。发布日志 `debug-updates/update-20260626120440.md`。

## 2026-06-26 - 反缩进箭头与父级状态图标列对齐（update 202606260501）

按四界面截图复核，反缩进（左）箭头改为把控件居中到**上一级状态图标列**：
`outMargin = (level-2)*step + (状态控件36 − 箭头控件28)/2`，使二级项左箭头落在一级图标列、三级落在
二级图标列，与父级图标视觉中心对齐。垂直首行对齐沿用字体度量方案、本版未改。发布日志
`debug-updates/update-20260626130142.md`。

## 2026-06-26 - 按界面精调缩进与图标上边距（update 202606260619）

按用户实测值精调：详情 L2/L3 +4dp、T2/T3 −1dp；卡片（含文件夹缩略图）L2/L3 +2dp、T2/T3 −1dp；
widget L2/L3 +2dp、T1/T2/T3 +1dp。缩进抽成 `indentForLevel(level, step, extra)`（缩进层级加首段偏移），
反缩进箭头父级列对齐用 `indentForLevel(level-1,...)` 同步；widget 用 `setViewPadding(IV_STATE,…1dp…)`
下移图标。发布日志 `debug-updates/update-20260626141910.md`。

## 2026-06-26 - 缩进与图标上边距再精调（0.5dp 级，update 202606260630）

详情 L2/L3 +2dp（extra 4→6）、T2/T3 +0.5dp；卡片 L2/L3 +1dp（extra 2→3）、T2/T3 +0.5dp；
widget L2/L3 +2dp（extra 2→4）、T1/T2 图标 +1.5dp、T3 图标 +0.5dp。0.5dp 用 `Math.round(0.5f*density)`，
高密度屏会按整数像素四舍五入。发布日志 `debug-updates/update-20260626143021.md`。

## 2026-06-26 - 缩进与图标上边距按级精调（update 202606260637）

详情 L2/L3 +1dp（extra 6→7）、图标微调改逐级（一二级 0、三级 −0.5dp）；卡片 L2/L3 +0.5dp（extra 3→3.5）、
T 不变；widget L2/L3 +2dp（extra 4→6）、图标上边距改逐级（一 2.0dp、二 1.5dp、三 0.5dp）。各级图标上下
微调已改为逐级独立常量，便于后续单独调。发布日志 `debug-updates/update-20260626143719.md`。

## 2026-06-26 - 缩进按 L2/L3 分别精调（update 202606260644）

L2/L3 缩进开始分开调，用 step+extra 两量换算：详情仅 L3 +2dp（step 28→30、extra 7→5）；卡片 L2+0.5/
L3+1（step +0.5dp）；widget 仅 L3 +2dp（step 13→15、extra 6→4）、T1 图标 2.0→2.5dp。L_n=(n-1)*step+extra。
若后续 L2/L3 继续各调各的，可考虑改成逐级独立的缩进 dp 值。发布日志 `debug-updates/update-20260626144344.md`。

## 2026-06-26 - L3 再调 + 详情/widget 缩进改逐级独立值（update 202606260657）

详情 L3 +2dp（L2=35dp、L3=67dp）、卡片 L3 +0.5dp、widget L3 +1dp（L2=19dp、L3=35dp），T 均不变。把
详情与 widget 的缩进从 step+extra 重构为**逐级独立 dp 值**（`detailIndentDp(level)` / widget `when(level)`），
卡片仍图标相对步长。反缩进箭头父级列对齐随新缩进同步。发布日志 `debug-updates/update-20260626145725.md`。

## 2026-06-26 - 禁止层级跳空，消除孤儿/跳空项（update 202606260733）

实测发现缩进会产生跳空（`[1,2,2]` 缩进第一个二级→`[1,3,2]`，三级无二级父项）。**推翻早前“允许跳空”，
改为禁跳空**：(1) `canIndent` 收紧为“仅当存在同级上一个兄弟时可缩进”（自上扫描遇到的第一个不深于自身的
项必须同级）；(2) 新增 `CheckListHelper.normalizeLevels` 自上而下把层级钳到 owner+1，接在 `toCheckListItems`
（覆盖加载/渲染/完成重建）及详情页反缩进、删除之后，自动收回反缩进/删除/旧数据产生的跳空。`CONTEXT.md`
owner/level 术语同步改为“恰好浅一级、禁跳空”，`decisions.md` 记入推翻。卡片缩进同时重构为可调的
`cardIndentExtraDp(level)`。发布日志 `debug-updates/update-20260626153232.md`。

## 2026-06-26 - 完成态严格不变量（update 202606260759）

处理“已完成父项挂未完成子项”（缩进未完成项到已完成项下、或原位取消子项造成）。选**严格不变量**
「已完成项的所有下属都已完成」：已完成项一旦有未完成下属，连同其已完成祖先链自动改回未完成，旁系不动。
新增 `CheckListHelper.normalizeCompletion`（自下而上翻转），接在 `toCheckListItems`、`ChecklistCompletion.toggle`、
详情缩进/反缩进、删除之后。副作用：原位取消一个子项现在会连带改回其已完成祖先链。`CONTEXT.md`/`decisions.md`
已记。发布日志 `debug-updates/update-20260626155827.md`。

## 2026-06-26 - 拖拽脱手修复 + 放弃收缩动画（update 202606260814）

用户决定拖拽从简，不做收缩动画/角标。修复拖根节点偶发脱手：根因是整块移动用 `notifyDataSetChanged`
回收了被拖视图，`ItemTouchHelper` 失去手指跟踪。改为整块移动**全程只用 `notifyItemMoved`**（把目标兄弟
子树各项依次移到块另一侧），新增 `moveChecklistItem` 单步辅助。`decisions.md`/`execution.md` 已记。发布日志
`debug-updates/update-20260626161440.md`。

## 2026-06-26 - 移动模式下返回键先退出移动模式（update 202606260829）

清单项移动模式下，系统返回键/手势返回与详情左上角返回箭头都改为**先退出移动模式**，再按才退出详情。
进入/退出抽成 `enterChecklistMoveMode`/`exitChecklistMoveMode`，两处返回入口先判 `isChecklistMoveMode()`。
发布日志 `debug-updates/update-20260626162853.md`。

## 2026-06-26 - 拖拽自动滚动（恒定速度、分割线为界，update 202606260843）

清单 RecyclerView 在 NestedScrollView 内、自身不滚动，ItemTouchHelper 自带自动滚动失效。新增自定义
自动滚动：`CheckListTouchCallback` 加 `onSelectedChanged`/`clearView`/`onChildDraw` 重写，逐帧以恒定速度
滚 `mScrollView`（`startChecklistDragAutoScroll`），`onChildDraw` 给被拖项加 `mDragAutoScrollAccum` 平移补偿
保证不脱手，`clampChecklistAutoScroll` 按 `"3"` 分割线裁边界（拖未完成往下/拖已完成往上到分割线即停）。
嵌套滚动下较微妙，首版待真机验证。发布日志 `debug-updates/update-20260626164317.md`。

## 2026-06-26 - 自动滚动修复 + 返回 icon 直接退出（update 202606260904）

首版自动滚动两个 bug：被拖项消失、只能单向滚——根因是边缘判定用了"被拖视图屏幕位置"（含防脱手补偿
平移），形成正反馈使补偿失控。改为用 `dispatchTouchEvent` 记录的**实时手指屏幕 Y** 判边缘，与补偿解耦，
被拖项贴手指、双向可滚。另：左上角返回 icon 改回**始终直接退出详情**（移动模式仅由系统返回键退出）。
发布日志 `debug-updates/update-20260626170430.md`。

## 2026-06-26 - 自动滚动彻底修复被拖项消失：补偿量改增量、不再累加（update 202606260917）

202606260904 修好了双向滚动，但被拖项滚动后仍消失。真正根因是 `onChildDraw` 的**补偿量双重计数**：旧实现
把每帧滚动量只增不减累加进 `mDragAutoScrollAccum` 再 `dY + 累加值`，而 `ItemTouchHelper` 的 `dY` 在每次手指
MOVE 时已用"手指相对 RecyclerView 的新坐标"重算、其中**已含父容器滚动量**，于是手指一动便 `dY` 一遍、累加
一遍，`translation = 手指位移 + 2×滚动量`，被拖项两倍速飞出屏幕。改为补偿量取 **"当前 scrollY − 上次手指
事件时 scrollY"**（`dispatchTouchEvent` 里记基准），即"自上次手指事件以来父容器又多滚的、`dY` 尚未反映的那
段"；手指不动纯滚动时补偿增长贴手指，手指一动同帧 `dY` 重算、基准刷新回 0，不再双重计数。删除
`mDragAutoScrollAccum`，新增 `mScrollYAtDragTouch`。发布日志 `debug-updates/update-20260626171544.md`。

## 2026-06-26 - 拖拽子树收束/展开动画 + 二三级项滚动边界（update 202606260935）

把早前从简放弃的"组根代理 + 子树收束"补上，并同时解决二三级项越界问题——两者本是一套架构：

- **收束/展开**：`onSelectedChanged` 拖拽开始即 `beginChecklistDrag` 把被拖项子树从 `mItems` 临时移除
  （`notifyItemRangeRemoved`，删除动画 + 下方上滑 = 收束），拖拽中只剩父项单行作代理；`clearView` 时
  `endChecklistDrag` 把暂存的 `mCollapsedChildren` 插回代理落点之后（`notifyItemRangeInserted` = 展开）。
  `onMove` 改调 `moveChecklistDragProxy`：代理是单行，在同 owner 同级兄弟间整段跨越目标兄弟子树
  （`subtreeEndIndexOf`）。整段拖拽前后内容串合并记一次 `UPDATE_CHECKLIST`（`mDragBeforeContent`），
  取代旧 `moveChecklist` 的逐 onMove 记录；旧 `moveChecklist` 仅留给 `MOVE_CHECKLIST` 撤销/重做分支。
- **边界**：`moveChecklistDragProxy` 中相邻项非同级即拒绝，二三级项天然被限制在父子树内；
  `clampChecklistAutoScroll` 由"分割线为界"泛化为"边界项为界"——level≥2 用 owner 子树
  （上界=owner 头部、下界=`subtreeEndIndexOf(owner)`），level 1 仍用分割线/区域；边界项未布局（不在视口）
  时不裁、允许滚动把它带进来。
- 风险记：`beginChecklistDrag`/`endChecklistDrag` 在 `onSelectedChanged`/`clearView` 内改 adapter，均非布局期、
  安全；进程被杀于拖拽中可能丢子项（与既有 live 修改风险一致，未额外兜底）。发布日志
  `debug-updates/update-20260626173502.md`。

## 2026-06-26 - 收束/展开动画 + 胶囊轮廓；修边界往下失效与目标项横跳（update 202606261005）

三处反馈：

- **收束无动画**：根因是 `mRvCheckList.itemAnimator` 一直 `= null`（[DetailActivity.kt:823]，为编辑输入不闪动）。
  新增 `ChecklistDragItemAnimator`（继承 `DefaultItemAnimator`）**仅在移动模式期间**挂载
  （`enter/exitChecklistMoveMode` 切换 `itemAnimator`/`null`）：`animateRemove`=顶部 pivot 的 scaleY→0+渐隐
  （向上收缩）、`animateAdd`=反向（向下展开）、`animateMove` 用 `allowMoveAnimation` 门控且被拖代理永不做
  move 动画——拖拽 onMove 重排瞬时（不与跟手冲突），仅收束/展开期间（`begin/endChecklistDrag` 里置 true、
  postDelayed 260ms 复位）让下方兄弟平滑滑动。`isRunning`/`endAnimations` 计入自管动画防提前回收。
- **胶囊轮廓**：`startChecklistCapsule` 在被拖项 `itemView.overlay` 加 `GradientDrawable` 描边（圆角=半高的
  胶囊，范围 `ivState.left..et.right`，色=`et.currentTextColor`），`ValueAnimator` 渐显；松手
  `fadeOutChecklistCapsule` 渐隐移除。新增字段 `mCapsuleHostView`/`mCapsuleDrawable`。
- **边界往下失效**（问题 2）：旧 `clampChecklistAutoScroll` 用边界项屏幕几何，而收束后边界项常是贴手指的
  代理本身、几何受补偿平移污染。改为按 `canChecklistProxyMove(pos, down)`（与重排边界同源）判停——该方向
  无同级兄弟即不滚。签名去掉 svTop/svBottom。
- **目标项横跳**（问题 3）：`moveChecklistDragProxy` 加 `fingerPastSubtreeMid`——手指越过目标兄弟整棵子树
  中线才跨越，跨完手指落在另一侧、不反向触发，消振荡。
- 发布日志 `debug-updates/update-20260626180445.md`。

## 2026-06-26 - 轮廓改圆角矩形修右/下裁切 + 落位重叠兜底（update 202606261018）

四处视觉细节：

- **轮廓下/右边裁切、右侧盖不住文本**：根因是 `startChecklistCapsule` 直接用 `et.right`，而 `et` 在内层
  `LinearLayout` 里、该坐标非 itemView 坐标系（少算图标列偏移），下边 `+pad` 又超出 itemView 被 overlay
  裁。改为 `getLocationOnScreen` 换算到 itemView 坐标系（`eLoc[0]-iLoc[0]+et.width` 取右缘），矩形四边
  `coerceIn` 到 itemView 内并留出 `strokeW`，下/右描边完整、右侧覆盖文本。
- **胶囊→圆角矩形**：`cornerRadius` 由半高改固定 `10f*density`。
- **落位后项停两行间重叠**：动画残留 translation/scale 未归零。新增 `resetChecklistChildTransforms`
  （遍历 rv child 复位 translation/scale/alpha，仅 `mDraggedChecklistVH==null` 时执行），`endChecklistDrag`
  末尾 `postDelayed 300ms` 调用兜底。
- 发布日志 `debug-updates/update-20260626181807.md`。

## 2026-06-26 - 多行文字轮廓底加留白（update 202606261026）

多行时 itemView 高度恰好被文字占满、内部无余量，轮廓 `bottom` 又被夹在 itemView 内，于是切进文字底、
无留白；直接加大 `bottom` 会超出 itemView 被 overlay 裁掉。改法：移动模式期间 `mRvCheckList.clipChildren=false`
（+ `clipToPadding=false`，退出恢复），让被拖项轮廓可在 itemView 下方溢出而不被裁；`startChecklistCapsule`
新增 `padBottom=4dp`，`bottom = 文字底 + padBottom`、coerce 上限放宽到 `v.height + padBottom`。发布日志
`debug-updates/update-20260626182551.md`。

## 2026-06-26 - 轮廓四边视觉 padding 对称（update 202606261039）

右/下 padding 比左/上窄。根因：左/上的视觉留白真实来源是 `ivState`（36dp）控件比里面 icon（`scaleType=center`、
实际更小）多出的单边留白（约 6dp）+ basePad；右/下无此控件只剩 basePad。改 `startChecklistCapsule`：取
`ivState.drawable.intrinsicWidth/Height` 算 `insetX/insetY`，四边到“内容真实视觉边缘”（icon 扣控件留白、文字
扣 `et.totalPaddingRight/Top/Bottom`）的 padding 统一为 `basePad + inset`——右=`textVisualRight+padX`、
下=`max(iconVisualBottom,textVisualBottom)+padY`，左/上沿用（经控件留白天然得到同值）。发布日志
`debug-updates/update-20260626183907.md`。

## 2026-06-26 - 代码审查修复：四处旧格式假设（update 202606261122）

外部审查（GPT）发现四处在“状态位+层级位+文本”迁移后未更新的旧格式假设，逐一核实属实并修复：

- **SIMPLE_FCLI 汇总行**（`CheckListAdapter` 第 ~254）：`"1"+文本` → `"11"+文本`（补层级位），否则
  渲染 `textOf` 的 `substring(2)` 丢首字符、首字符为 1/2/3 还被误当层级。
- **详情页“N 项已完成”头部统计**（`CheckListAdapter` 第 ~433）：原数整列表 `[0]=='1'`，会含顶部就地
  完成的二/三级项；改为只数 `"4"` 头部之后（底部已完成区）的 `isFinished` 项。
- **截图控制行恢复**（`ScreenshotHelper` 第 ~402）：`getLastUnfinishedItemIndex+1` 在“未完成父+就地完成
  子”结构上会把 `"2"/"3"` 插到父与已完成子之间、错位分界；改用分隔标记 `"3"`/`"4"` 定位（与截图前移除
  对称）。`getLastUnfinishedItemIndex` 用于判断“有无未完成项”的 line 332 语义仍对、未动。
- **空文本转清单聚焦**（`DetailActivity` 第 ~1714）：`items[0]=="0"` → `CheckListHelper.isEmptyItem(items[0])`
  （空项新格式 `"01"`），恢复空清单自动聚焦首项。

另排查同类模式（`startsWith("0"/"1")`、`substring(1)`、`[0]=='0'`）确认无其他遗漏（其余命中为附件 type
前缀、状态渲染判断、迁移转换 `migrateToLeveledFormat`，均正确）。发布日志
`debug-updates/update-20260626192133.md`。
