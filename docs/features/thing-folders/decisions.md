# Thing Folders Decisions

## 2026-06-27 - 搜索态批量/范围操作刷新必须统一恢复搜索投影

首页搜索模式下，任何会重建列表的批量或范围操作，完成后都必须继续使用当前搜索文本与颜色筛选恢复列表投影。即使底层 `ThingManager` 的某个方法为了普通列表路径调用了 `loadThings()`，Activity 刷新层也必须再按当前搜索态调用 `searchThings(...)`，避免搜索框仍显示关键词但列表跳回当前文件夹全量内容。

涉及文件夹结构或文件夹属性的选择操作（移动到文件夹、置顶、设为私密）应优先让 manager 只修改数据，不直接 reload；随后由 Activity 统一调用搜索感知刷新。涉及记事状态或生命周期的内容操作（完成、删除、恢复、永久删除）可以继续复用 manager 内部的状态变更实现，但 UI 刷新必须走同一条搜索感知路径，空结果也必须显示搜索 no-result，而不是首页空状态。

## 2026-06-27 - 结构操作搜索态提示说明会越过搜索范围

解散文件夹、永久删除文件夹容器、含文件夹的结构性永久删除等结构操作作用于整个文件夹子树。即使入口来自首页搜索结果，这些操作也不只作用于当前搜索范围内可见或命中的条目，因此确认弹窗的隐藏范围提醒必须把搜索范围与状态筛选、类型筛选合并提示。

提示仍复用“该操作作用于整个文件夹子树，包含当前……下看不到的内容。”这一句，不另起一段。搜索态下若同时存在状态/类型筛选，短语合并为“状态筛选（X）和搜索范围”“类型筛选（X）和搜索范围”或“状态和类型筛选（X、Y）以及搜索范围”。

## 2026-06-27 - 搜索结果空提示必须把文件夹卡片算作结果

首页搜索模式中，Thing Folder Card 与 Thing Card 都是搜索结果条目。只要当前混合列表里存在可见文件夹或记事，就不应显示“找不到对象”的 no-result 提示；这个判断不能只依赖 `mThings` 中是否存在非 header 记事。

当列表在搜索态下因完成、删除、恢复、撤销等操作重建时，文件夹投影也必须继续带上当前搜索关键词和颜色过滤条件，避免把搜索范围外的文件夹计入当前结果。

## 2026-06-27 - 首页搜索范围包含文件夹自身标题和颜色

首页搜索模式下，Thing Folder Card 不再只依赖子树中的记事命中来进入搜索结果。文件夹自身标题命中搜索关键词，或文件夹背景色命中当前颜色筛选时，也应作为搜索结果显示。关键词与颜色同时存在时沿用记事搜索的组合语义：标题命中且颜色命中才算文件夹自身命中。

文件夹自身命中只表示该文件夹卡片属于当前搜索结果；它不会把文件夹中所有记事自动扩展为搜索命中的记事。后续内容类动作仍以“实际命中搜索条件的记事”为作用范围。

## 2026-06-27 - 拖拽移入已有文件夹恢复原地左上缩小

本条修正并取代同日较早“`MOVE_TO_FOLDER` 应飞入目标 Folder”的描述。长按拖拽移入已有文件夹时，目标 Folder Card 已经通过 hover 高亮表达“接收者”，被拖拽 overlay 也已经被用户拖到目标里的正确位置，因此提交视觉不应再计算目标 Folder 中心点，也不应飞向目标中心。`MOVE_TO_FOLDER` 应保持释放位置，以 overlay 内容左上角为 pivot 直接缩小；这同时避免 thumbnail-mode 大文件夹因 expanded shadow inset、content rect 和 CardView rect 不一致导致的飞入坐标偏差。

上一版“armed 后释放阶段不再被最后一帧左上角命中误判取消”的修正仍然保留：释放时只校验已 armed 的目标 holder 仍可见、业务候选仍是同一个源和目标。真正拖离目标时，普通 MOVE 帧会先清掉 armed 状态。`CREATE`（拖拽两个记事创建文件夹）仍使用目标 rect 播放合并/飞入目标的提交动画。

## 2026-06-27 - 拖拽飞入与 Dialog 飞入坐标重新拆分

继续复核后确认，上一轮把问题归因到 `RecyclerView` move animation 的 `translationY` 不成立；真正需要修正的是两条路径的语义边界和 Dialog 坐标来源。

实际长按拖拽主要走 `ThingListOverlayDragController`；同日较早曾尝试让 `MOVE_TO_FOLDER` 飞入目标 Folder，但后续 thumbnail-mode 大文件夹反馈证明该方向不合适，已由上一条“原地左上缩小”规则取代。Dialog 移动不是拖拽路径，目标坐标应在确认移动前捕获：用目标 Folder Card 的 `getGlobalVisibleRect` 得到系统实际可见区域，转换到 activity root 坐标，再与当前 `RecyclerView` 可见 viewport 求交，并扣除 selecting mode contextual toolbar 的遮挡。Dialog 动画后续不再在 `notifyItemRemoved` / `notifyItemChanged` 之后重新查找 F 的 holder 或重新取坐标，避免把列表变更期间的中间状态当成飞入终点。

## 2026-06-27 - Dialog 飞入动画终点使用目标文件夹可见区域中心

“移动到文件夹” Dialog 触发的飞入动画不能直接使用目标文件夹卡片的完整中心点，因为目标文件夹可能只有一部分露在当前列表可见区内，也可能被 selecting mode 的 contextual actionbar 覆盖一部分。动画目标应取目标文件夹卡片与当前 `RecyclerView` 可见 viewport 的交集中心，并在计算 viewport 时扣除 contextual toolbar wrapper（含 statusbar spacer 与 shadow）的垂直遮挡。若目标文件夹在扣除遮挡后完全不可见，则不生成飞入目标，Dialog 移动继续按可见源项移除动画或全量刷新兜底。

实现上，`ThingsActivity` 在退出选择模式前先缓存当前列表可见 viewport，避免 `finishCurrentModeWithoutListRefresh()` 隐藏 contextual toolbar 后重新计算时丢失遮挡信息；随后无论目标 holder 是否重排，都用这个缓存 viewport 重新裁剪 Dialog 飞入目标矩形。旧拖拽提交动画不属于本规则，它继续按拖拽路径自身的落点语义处理，避免把 Dialog 的“飞入 F”坐标规则错误套到拖拽提交上。

## 2026-06-27 - “移动到文件夹”Dialog 优先使用可见源项动画和定向通知

通过长按菜单或 contextual toolbar 打开的“移动到文件夹”Dialog，不应只在确认后全量刷新。若目标文件夹和一个或多个被移动条目都在当前屏幕可见，应为每个可见源条目生成 overlay 并飞入目标文件夹；多选时，部分源条目不可见不应阻止其它可见源条目的动画。若目标文件夹不在屏幕可见区域，当前列表中的源条目仍应走 `notifyItemRemoved` 的定向移除动画；只有当前可见区域没有可表达的源或目标变化，或目标文件夹因移动后才新进入当前投影这类 item count 变化无法用纯移除表达时，才退回无入场动画的全量刷新。

实现上，屏幕可见与 adapter 所属是两个独立判断。源条目即使在屏幕外，只要属于当前混合列表，移动后也会减少 adapter item count，因此必须按旧 adapter position 发 `notifyItemRemoved`；目标文件夹可见时再补 `notifyItemChanged` 更新缩略图和计数。单独 `notifyItemChanged(目标文件夹)`不能代表一次会减少列表项数量的移动。

后续反馈确认，多选文件夹、以及记事+文件夹混合多选打开同一个 Dialog 时，默认选中项也必须是源项真实所在的共同文件夹，而不是固定根目录。计算口径为：Thing 取 `folderId`，Thing Folder 取 `parentFolderId`；若所有来源一致则默认选中该来源文件夹，来源不一致才回退当前投影文件夹。

## 2026-06-26 - 拖拽进文件夹需要按卡片间距内缩命中

首页拖拽移动模式中，创建文件夹或移动到文件夹的 hover 目标不应在拖拽卡片左上角刚碰到目标卡片边缘时触发。左上角必须进入目标卡片内缩“首页相邻卡片之间的实际可视间距”后才算命中；该间距由卡片 item margin 推导，当前等价于 16dp。目标卡片过小时按可用宽高自动收缩阈值，避免把命中区域反向挤没。

后续真机反馈表明当前交互实际主要走 `ThingListOverlayDragController` 的 overlay 拖拽路径，而上一版只收紧了 `ThingsActivity` 里的旧 `ItemTouchHelper` 路径。因此两条路径都必须执行同一条规则：命中点按放大后的可见卡片左上角计算，目标卡片内缩距离提高到 2 倍首页相邻卡片可视间距，当前等价于约 32dp。目标过小时仍按可用宽高自动收缩。

为了便于对比手感，内缩距离改回 16dp，并改为独立 dp 资源。实际调整入口为 `app/src/main/res/values/dimens.xml` 的 `folder_drop_target_inset`；两条拖拽路径都读取同一资源。此前短暂使用 multiplier 是为了延续“跟随卡片间距”的表达，但这个值本质上是交互手感参数，直接 dp 更清楚。

## 2026-06-24 - 单个记事 Widget 配置顶部栏跟随文件夹背景

单个记事 Widget 配置界面属于“按文件夹范围浏览并选择具体记事”的入口；打开某个文件夹后，顶部 `statusbar + actionbar` 区域应使用该文件夹的背景，而不是继续固定使用全局 `accent + accent2` 渐变。文件夹背景支持纯色和渐变，文字、返回图标和菜单图标按背景代表色自动选择偏白或偏黑前景色。

根目录仍使用全局 `accent + accent2` 渐变，但它是设计上的暖色渐变，而不是普通文件夹背景。虽然它的代表色可能被亮度算法判为浅色，顶部栏文字和图标仍固定使用偏白前景色；系统状态栏图标也使用浅色模式。进入文件夹后才按文件夹背景亮暗切换前景色和系统状态栏图标模式。

## 2026-06-24 - 单个记事 Widget 配置不显示完全空文件夹

单个记事 Widget 的配置界面用于选择一个可展示的具体 Thing，因此它的文件夹入口只应显示当前可选投影中存在记事内容的文件夹。完全为空、或子树内没有正在进行且符合 Widget 类型范围的记事的文件夹，不在该配置列表中显示。

这不同于 Drawer、移动到文件夹 Dialog 和记事列表 Widget 的范围选择器：那些入口仍按各自语义显示稳定文件夹骨架或可选范围。单个记事 Widget 配置应复用 Widget 专用的文件夹投影过滤，而不是普通首页投影。

## 2026-06-24 - 记事完成、删除、恢复时保留置顶状态

记事的置顶状态应与文件夹一致，视为条目自身状态而不是某个状态列表中的临时排序。完成、删除、从回收站恢复记事时，原本置顶的记事重新分配到同一父级置顶区顶部之前的新负数 `location`，不因状态变更落入非置顶区；原本未置顶的记事仍沿用既有行为，进入目标状态列表的非置顶区顶部。这样先完成置顶 A、后完成置顶 B 时，B 会在“已完成”中排到 A 前面。该规则覆盖单条状态变更、批量选择、文件夹内容递归完成/删除/恢复、详情页返回时的状态变更，以及通知/远程动作触发的完成。

实现上由 `ThingManager` 编排状态变更落位：置顶记事通过 `getLocationForStateChange` / `getLocationsForStateChange` 重新分配当前父级置顶区的下一负数位置，再传给 `ThingDAO.updateState/updateStates` 写库；非置顶记事仍沿用 DAO 里既有的 `getMaxThingLocation()` + `updateHeader` 逻辑。批量状态变更只按非置顶条目数量前移 header，避免置顶条目状态变更影响 header。撤销仍使用原先保存的 `mUndoLocations` 还原。

## 2026-06-24 - 移动与拖拽建夹保留条目自身置顶状态

置顶视为记事/文件夹条目自身状态，而不是只属于来源目录的一次性排序意图。将置顶记事或置顶文件夹移动到其它父级（包括根目录、其它文件夹、当前目录的下级目录），无论入口是拖拽还是“移动到文件夹” Dialog，都保留置顶状态，并在目标父级的置顶区顶部重新落位；未置顶条目同理进入非置顶区顶部。移动不保留来源父级中的相对顺序。

拖拽两个记事创建新文件夹时，新文件夹的置顶状态由两个成员共同决定：任一成员置顶则新文件夹置顶，否则新文件夹未置顶。两个成员移入新文件夹后也分别保留自己的置顶/非置顶状态。已有目标文件夹接收子项时，目标文件夹自身的置顶状态不因子项变化而改变。该规则与解散文件夹时子项按自身置顶状态上移一级的既有语义保持一致。

## 2026-06-24 - 根范围根标签随文件夹存在与否切换，移动 Dialog 列表高度自适应，文件夹菜单加颜色信息

文件夹范围根标签（Drawer 根行、移动到文件夹 Dialog 根节点、记事列表 Widget 配置根行）统一改为条件文案：数据库 `thing_folders` 表没有任何文件夹行时显示"所有记事"（`all_things`），存在任意文件夹行时显示"所有内容"（新增 `all_content`）。判定按整张表计数（`ThingFolderDAO.hasAnyFolder()` 用 `SELECT COUNT(*)`），含已进入回收站的文件夹行，因此口径是"数据库里是否有文件夹"而非"当前是否有未删除文件夹"。

这取代 2026-06-22"移动到文件夹 Dialog 根标签改为'全部记事'"：移动 Dialog 根节点不再用 `all_things_scope`（"全部记事"），改用上述条件文案；Widget 配置根行也从 `R.string.underway`（"正在进行"）改为同一条件文案，使三处一致。

移动到文件夹 Dialog 的文件夹列表区域改为高度自适应：列表高度随行数增长（行高 48dp，与 `FolderTreeHolder` 一致），最多 6 行后封顶滚动，做法参照记事列表 Widget 配置的 `updateScopePickerHeight`。上下分界线沿用原有的 license Dialog 式逻辑（顶端隐藏顶线、底端隐藏底线、中间两线都显示、不可滚动时都隐藏），不改判定方式，仅去掉固定 280dp。

打开文件夹后的 overflow 菜单（正在进行/已完成/回收站三套 `menu_things_*`）新增"颜色信息"项（`act_color_info`），复用记事详情页的 `ColorInfoDialogFragment`，传入当前文件夹背景（`getCurrentFolderBackgroundForChrome()`，纯色或渐变）。只在打开文件夹时可见，逻辑放在 `configureCurrentFolderMenu`。

首页根目录新建记事 FAB 的触摸 ripple 不再预览随机新记事颜色：根目录（accent+accent2 渐变）固定用偏白 ripple（`0x3DFFFFFF`，即 24% 白，与文件夹分支 `onColor(..., 0.24f)` 同 alpha）；文件夹内维持按文件夹代表色 `onColor` 偏白/偏黑。原因是 accent 渐变代表色在亮度阈值上属"浅色"，`onColor` 会给偏黑，但设计上该暖色渐变配白色 ripple 更协调，故根目录直接固定偏白。这与 2026-06-20"create-Thing FAB 用文件夹背景"的方向一致，只调整 ripple 取色。

## 2026-06-23 - 文件夹内容删除按当前状态执行，不再跨状态删除未删除内容

“删除文件夹中所有记事”属于当前状态下的内容操作：在“正在进行”状态只删除文件夹子树中正在进行的记事；在“已完成”状态只删除文件夹子树中已完成的记事；回收站中的“永久删除文件夹中所有记事”只永久删除回收站里的记事。它仍然跟随当前类型筛选，且不移动或删除文件夹容器。

这取代了此前“正在进行状态下删除文件夹中所有未删除记事（正在进行 + 已完成）”的语义。原因是用户期望状态分段彼此独立，当前状态下的内容操作不应连带影响其它状态中不可见的内容。

## 2026-06-23 - 全宽缩略图文件夹卡片的列数与缩略图数量随屏幕自适应

全宽（`SPAN_FULL`）缩略图模式文件夹卡片瀑布流（`createFolderThumbnailMasonryView`）的列数不再固定为 3（原 `FOLDER_THUMBNAIL_FULL_SPAN_COLUMN_COUNT`），改为“首页记事列表列数 + 1”。列数在构建时动态读取当前 `StaggeredGridLayoutManager.spanCount`，因此横竖屏切换后随 `onConfigurationChanged` 的 `notifyDataSetChanged` 全量重绑自动适应，无需额外监听。普通宽度（`SPAN_NORMAL`）缩略图卡片维持单列竖排、不受影响。

| 场景 | 首页列数 | 瀑布流列数 | 显示缩略图数 |
|---|---|---|---|
| 手机竖屏 | 2 | 3 | 6 |
| 手机横屏 | 3 | 4 | 6 |
| 平板竖屏 | 3 | 4 | 8 |
| 平板横屏 | 4 | 5 | 10 |

缩略图数量规则：手机固定 6 个（手机横屏向下空间有限，铺满 2 行会过高，故维持约 1.5 行的 6 个）；平板为 `2 ×列数`（保持约 2 行的饱满度）。手机/平板用 `DisplayUtil.isTablet` 区分，与列表 span 的判断口径一致。

实现上把“取数上限”和“显示数量”解耦：DAO 取数上限（`effectiveThumbnailPreviewLimit` 全宽分支）放宽到能覆盖最大显示数（≥10）的固定值，避免把屏幕信息下沉到数据层；adapter 渲染时按上述屏幕规则 `take(显示数量)`。“+N”省略号仍按真实总数 `thumbnailEntryCount` 判断。

## 2026-06-22 - “完成文件夹中所有记事”递归完成 + 习惯/目标三选项 dialog

新增“完成文件夹中所有记事”操作：递归完成文件夹子树（含所有子文件夹）中**所有类型**的正在进行记事，不受当前类型筛选影响。入口：

- 文件夹卡片长按 contextual menu（正在进行投影）新增该项。
- 打开文件夹后工具栏的“全部完成”（`act_finish_all`）在文件夹内重命名为“完成文件夹中所有记事”，并改为递归处理整个子树（之前只处理当前层级可见项）。
- 首页根目录工具栏的“全部完成”同理，从根递归完成整棵树中所有正在进行记事（排除已在回收站/已删文件夹下的）。

交互：点击先弹确认 Dialog（显示影响的记事数量，标题/确认按钮用文件夹色或 accent+accent2 渐变）。若命中集合包含习惯/目标，确认后弹现有三选项 dialog（`ThreeActionsAlertDialogFragment`）——“去掉习惯/目标”只完成非习惯/目标项，“继续”完成全部，取消则不动。该三选项 dialog 改用 `setTitleBackground`/`setContinueBackground` 适配文件夹色/accent 渐变（之前用随机纯色），选择模式下的 `alertForHabitGoal` 也一并适配。

与 Phase C“完成当前筛选下的内容”的关系：后者按当前类型筛选完成，仅在存在自定义类型筛选时显示在 Folder Card 菜单中，避免与“完成文件夹中所有记事”重复。

## 2026-06-22 - 解散文件夹的语义与确认计数和删除区分

解散文件夹与删除文件夹是不同的操作，互不相关：

- 删除文件夹 = 把文件夹及其内容移入回收站，可恢复。确认文案的影响计数只统计“未在回收站”的内容（自身状态为正在进行/已完成的记事 + 未删除的子文件夹），因为已经在回收站里的子项并不是被这次删除“新移入回收站”的。
- 解散文件夹 = 把文件夹容器本身彻底删除（正在进行、已完成、回收站里都不再有它，不是移入回收站），并把里面的所有内容按各自原状态移到上一级目录，包括原本就在回收站里的内容。因此确认文案的影响计数统计整个子树的全部内容（含已在回收站的），因为它们都会被移动；`getDirectUserThings`/`getChildFolders` 不按状态过滤，已确认会连带搬移已删子项。

计数实现：删除/解散都在确认 Dialog 显示子文件夹数和记事数，但删除用 `countNonDeletedDescendant*`，解散与永久删除用 `countAllDescendantThings`/`countDescendantFolders`。永久删除统计全部，因为它确实销毁整个子树（含已删内容）。

## 2026-06-22 - 恢复文件夹连带恢复独立删除的后代，依赖删除前状态持久化

恢复一个 Trashed Thing Folder 时，按整个物理子树处理：自身因祖先被删而“有效删除”的后代回到各自 stored state；自身 state 已是 DELETED 的后代（在删该文件夹之前被单独扔进回收站的）也跟随一起恢复，恢复到它各自的“删除前状态”（Pre-Trash State），而不是统一恢复为正在进行。后代不是取祖先文件夹的状态——已完成的后代恢复后仍是已完成。

这要求持久化每个 Thing 的“删除前状态”。该能力并非为本决议新增：2026-06-22“从回收站恢复单个 Trashed Thing 回到 Pre-Trash 状态”已经独立要求它。当前代码恢复一律置 UNDERWAY，且 `finishTime` 在“已完成→正在进行”时不清零、不能可靠推断删除前状态，因此需要显式记录（新增一列，可随 thing-folders 的 DB v15 一起加）。单个 Trashed Thing 恢复与整夹恢复共用这套机制，语义一致。

本决议明确：恢复文件夹后该文件夹子树里不再残留被单独删除的内容，因此恢复后该文件夹不会再作为 Projection Folder 留在回收站。

## 2026-06-22 - 创建新 Thing 从不预设类型，创建后类型筛选重置为全部类型

在任意 Scope + 正在进行下创建新 Thing 时，创建流程始终中性，不因当前类型筛选预设新 Thing 的类型；类型完全由用户在详情页设定（提醒时间、重复等）。这作废 use-cases L9 中“把当前单一类型筛选作为默认编辑意图”的建议。

创建完成回到列表后，类型筛选持久重置为“全部类型”（保留当前 Scope 和正在进行状态），确保新建记事无论是什么类型都立即可见。

## 2026-06-22 - 回收站中自身已删的文件夹用 icon 内嵌删除图标区分

回收站列表里区分两种 Folder Card：自身已进入回收站的 Trashed Thing Folder 在其 folder icon 内部嵌入一个小的删除图标（复用 Drawer 私密文件夹 icon 内嵌锁的 `FolderIconDrawable` 做法）；只是承载已删后代的 Projection Folder 使用普通 folder icon、无徽标。Trashed Thing Folder 提供恢复/永久删除整个子树的操作，Projection Folder 只能打开。

## 2026-06-22 - 非正在进行状态下禁用一切拖拽到文件夹

在“已完成 / 回收站”状态下，长按拖拽只做重排或进入选择模式，既不触发拖拽创建文件夹，也不触发拖拽移动到已有文件夹。需要整理历史内容时用显式“移动到文件夹”操作（仅“已完成”提供；“回收站”不提供移动，需先恢复）。拖拽到文件夹的全部手势只在“正在进行”状态可用。

## 2026-06-22 - 移动到文件夹 Dialog 根标签改为“全部记事”

移动到文件夹 Dialog 的根目标节点标签从“正在进行”改为“全部记事”，与 Drawer 的 Thing Scope 根和 use-cases.md M5 一致。移动是结构操作，与记事状态无关，根节点表示“移出所有文件夹、放到根范围”，不应再使用状态名。本条作废 2026-06-19“Move-to-Folder 根标为正在进行”的表述。代码层面 `MoveToThingFolderDialogFragment` 当前使用 `R.string.underway`，需改为新的“全部记事”字符串。

## 2026-06-22 - “全部记事”是文件夹范围根选项

文件夹相关导航中的根选项中文显示为“全部记事”，它表示不限定 Thing Folder 的 Thing Scope。它不等同于“正在进行”，也不等同于忽略状态和类型的总览。

当前列表应由 Thing Scope、记事状态和记事类型共同决定。选择“全部记事”后，列表仍然只显示当前状态和当前类型筛选命中的内容；选择某个 Thing Folder 后，列表显示该文件夹范围内命中当前状态和类型筛选的内容。

这条决议取代此前“Underway acts as the root directory”的表述；“正在进行”是状态筛选，不再承担文件夹树根目录的身份。

## 2026-06-22 - 状态筛选保留文件夹范围

切换记事状态时应保留当前 Thing Scope。用户在某个 Thing Folder 内切换“正在进行 / 已完成 / 回收站”时，是查看同一文件夹范围内不同状态的内容，而不是离开该文件夹。

如果当前 Thing Folder 在目标状态下不可进入，则直接退回“全部记事”范围。典型场景是用户正在查看回收站中的已删除文件夹，随后切换到“正在进行”或“已完成”：目标投影不应继续停留在这个已删除文件夹内，而应显示“全部记事”范围下的目标状态内容。

## 2026-06-22 - 回收站不改变文件夹范围列表

Drawer 的文件夹范围区域始终显示“全部记事”和所有未进入回收站的 Thing Folders，并且完全不受状态筛选或类型筛选影响。这样用户先选文件夹再切状态，或先切状态再选文件夹，都会得到同一个 Thing Scope + 状态 + 类型组合。

已进入回收站的 Thing Folders 不出现在 Drawer 的文件夹范围区域中。它们只作为回收站区域的列表内容出现，用户从回收站列表中进入、恢复或永久删除这些 Trashed Thing Folders。

“已归档”是新的状态筛选项，“回收站”是独立区域；现有删除数据不应被解释为已归档内容。

Thing Folders 本身也支持归档，但必须区分两种情况：文件夹自身被归档，以及文件夹只是因为其子树包含已归档 Things 或已归档子文件夹而出现在已归档投影中。后者不改变文件夹自身状态，只让它作为已归档投影中的路径容器出现。

只有在 Drawer 选中“正在进行”状态时，才允许在文件夹范围内创建新 Thing；新 Thing 的自身状态为“正在进行”，不会因为文件夹在已归档投影中作为路径容器出现而自动归档。

已归档投影中的路径容器不能提供“取消归档文件夹”这类只适用于文件夹自身状态的操作。Folder Card 视觉上需要区分“自身已归档”和“只是包含已归档命中内容”。创建入口只在“正在进行”状态中可用；“已完成 / 已归档 / 回收站”中应隐藏或禁用创建入口。

文件夹自身归档后，其子树进入归档语义。归档文件夹在 Drawer 文件夹区域中的可见性、以及是否允许在该范围内创建新的正在进行 Thing，仍需进一步确认。

## 2026-06-22 - 作废：文件夹不需要独立归档语义

前述“归档文件夹”分支作废。归档与完成在当前产品语义中重复，不应新增独立归档状态。文件夹相关操作应围绕“正在进行 / 已完成 / 回收站”重新定义，其中“完成文件夹”表示把文件夹子树从正在进行列表中收起并保留其结构。

回收站仍然保持独立语义，用于恢复或永久删除 Trashed Things 和 Trashed Thing Folders。回收站不是“完成”的别名，也不应改变稳定文件夹范围列表。

## 2026-06-22 - 文件夹没有完成状态，只有内容完成

已确认的工作模型：Thing Folder 本身没有完成状态；“完成文件夹”表示递归完成其内容，而不是把容器本身标为完成。Drawer 文件夹范围区域、移动到文件夹 Dialog、以及记事列表 Widget 配置中的文件夹范围选择器都显示所有未进入回收站的 Thing Folders，不受正在进行/已完成状态筛选和类型筛选影响。

Thing Folder 本身进入回收站后，从正常范围选择器中消失，只在回收站内容列表中出现。混合状态子树中的完成、删除、解散、移动、合并/拖拽创建等操作以“文件夹是稳定组织骨架，状态筛选只决定内容投影”为基础，具体规则见后续 2026-06-22 决策。

## 2026-06-22 - 文件夹操作的隐藏影响需要确认

已确认的推荐模型是“文件夹是稳定组织骨架，状态筛选只决定内容投影”。正在进行、已完成、回收站都应复用同一棵文件夹树作为组织上下文；Thing Folder 本身不因为出现在已完成或回收站投影中就拥有已完成或已删除状态。

范围选择器和内容列表必须区分：Drawer 文件夹区域、移动到文件夹 Dialog、记事列表 Widget 配置显示完整的可用文件夹范围；普通内容列表只显示当前状态和类型筛选命中的 Things，以及包含命中后代的 Projection Folders。完全没有命中内容的文件夹不应作为普通内容列表里的空 Folder Card 出现。

回收站也遵守投影路径规则。删除 A 时，回收站根列表显示 A，D 作为 A 内部的后代出现，不与 A 并列显示；如果只删除 A 内的 B，则回收站根列表显示 A 作为路径容器，进入 A 后显示 B。A 作为路径容器出现不代表 A 本身被删除。

回收站中的 Folder Card 需要区分 Trashed Thing Folder 和 Projection Folder。前者表示文件夹本身已进入回收站，可恢复或永久删除整个子树；后者只是未删除文件夹在回收站投影中的路径容器，不应直接暴露“恢复整个文件夹”或“永久删除整个文件夹”这类会误伤正常文件夹的操作。

从回收站恢复单个 Trashed Thing 时，应恢复到它的 Pre-Trash Thing State，而不是一律恢复为正在进行。例如正在进行的 B 被删除后恢复为正在进行；已完成的 C 被删除后恢复为已完成。这样“恢复”保持撤销删除的语义，不额外改变完成状态。

如果文件夹操作会影响当前状态筛选、类型筛选或当前层级中不可见的内容，必须先显示确认 Dialog。Dialog 至少应说明操作对象、操作影响的是整个物理文件夹子树还是当前筛选命中的 Things，以及当前筛选命中数量、其它状态或类型的内容数量、子文件夹数量等影响范围摘要。

删除文件夹、解散文件夹、移动文件夹、拖拽移动已有 Folder Card、回收站中恢复文件夹、永久删除文件夹，都按整个物理文件夹子树处理，因此需要确认。完成文件夹或从已完成投影恢复文件夹内容时，只处理当前筛选命中的 Things；如果入口是 Folder Card 或文件夹头部操作，确认文案必须明确“当前筛选下的内容”，不能暗示整个文件夹容器拥有完成状态。

当前 Thing Scope 失效时，不能只在本次渲染临时退回“全部记事”。普通导航状态和记事列表 Widget 配置都应持久改写为“全部记事 + 原状态筛选 + 原类型筛选”，这样 Drawer 选中态、标题、点击行为和配置页回显保持一致。

恢复 Trashed Thing Folder 时，优先恢复到原父文件夹。如果原父文件夹已不存在或仍在回收站，则恢复到原路径上最近的正常祖先；如果没有正常祖先，则恢复到“全部记事”根范围。若恢复位置不是原父文件夹，确认 Dialog 应说明实际恢复位置。

## 2026-06-20 - Folder-scoped AppWidget create-return uses one refresh path

When a create flow launched from a Folder-scoped Things-list AppWidget returns
to the home list, `ThingsActivity` should treat it as an external Folder
projection refresh, not as a same-list item insertion. Opening the target Folder
already loads the projection and notifies the adapter, so the create-return path
must not also run the ordinary new-Thing `notifyItemInserted` or one-shot
created-card animation. The projection refresh may still use the ordinary
Things appearing animation, because that animation belongs to the single
projection rebind and does not add a second adapter insertion signal.

## 2026-06-20 - Merge Folder AppWidget behavior into Things-list AppWidgets

The folder widget concept should be implemented by extending the existing
Things-list AppWidget family rather than by adding a separate dedicated Folder
AppWidget provider. A Things-list AppWidget can target either a built-in root
projection or a selected Thing Folder projection, and it should offer both List
and Grid display modes where the AppWidget platform supports them.

The existing settings button in the Things-list AppWidget header remains the
configuration entry point. Reopening settings should allow users to change the
target projection, including which Thing Folder is selected, and switch between
List and Grid display modes for that same AppWidget instance.

The Things-list AppWidget configuration model should keep three independent
choices: target scope, type filters, and display mode. Target scope is root or
a selected Thing Folder. Type filters are represented by a horizontal row of
All, Note, Reminder, Habit, and Goal icons. All is exclusive, while the
specific type icons support multi-select combinations such as Reminder plus
Habit. Display mode is List or Grid. This preserves the same folder projection
semantics as the home list and supports widgets such as "all Things in this
Folder" and "Reminders and Habits in this Folder" without adding a separate
Folder-specific widget type.

The type filter must always resolve to a valid non-empty selection. If the user
deselects every specific type icon, the configuration should return to All
automatically rather than saving an intentionally empty AppWidget.

Grid display mode should derive its column count from the AppWidget width
rather than exposing a separate column-count setting. Narrow widgets can use one
column, medium widgets two columns, and wide widgets three columns, with exact
thresholds tuned during launcher/device testing.

Grid display mode should preserve existing Thing Card Span Mode where possible:
Things configured as full-span Thing Cards should occupy a full widget grid row.
This likely requires rendering Grid mode as a row-oriented RemoteViews
collection rather than as a plain AppWidget `GridView`, because individual
`GridView` items do not reliably span multiple columns. Thing Folder entries
remain summary Folder Cards rather than large Folder previews.

Grid-mode rows should preserve card content rather than clipping. When a row
contains multiple normal-span cards, each card keeps its own wrap-content
height, the row height follows the tallest card, and shorter cards stay
top-aligned rather than being stretched to match.

Grid-mode item packing should preserve the current mixed-list order. Normal
cards fill the current row in order. A full-span card first closes any
partially filled normal row, then occupies a full row by itself before normal
row packing resumes. Thing Folder summary cards participate in this packing
according to their Folder Card span mode.

Grid display mode should preserve rich Thing Card information as far as the
RemoteViews platform permits. It should not intentionally collapse Things into
minimal tiles when a supported RemoteViews approximation of the normal widget
Thing Card can carry title, content, checklist, media, reminder, habit, state,
sticky, doing, and privacy surfaces. Thing Folder entries are the exception:
inside Things-list AppWidgets, Folders should render as summary Folder Cards
instead of reproducing the in-app large thumbnail Folder Card with nested
previews.

Thing Folder entries inside a desktop Things-list AppWidget should open the
app at the corresponding Thing Folder projection when tapped. The AppWidget
itself should not maintain an internal Folder navigation stack, change its
header to the tapped Folder, or implement an AppWidget-local back/up affordance.

The Things-list AppWidget header should open the app at the widget's full
configured projection, including the target scope and built-in type filter. The
existing settings button in that header remains the way to reopen
configuration and does not share the header's navigation behavior.

Things-list AppWidget privacy should follow home-list semantics while keeping
the desktop surface conservative. Private Thing Folder entries show the stored
Folder name, Folder icon, and lock affordance, but no child previews. Private
Things and Things under a private ancestor use the existing protected Thing
widget presentation. Opening a private Folder projection from an AppWidget, or
selecting one in the configuration flow, requires the existing private Folder
authentication path.

The first folder-aware Things-list AppWidget slice keeps state fixed to
Underway. The configuration should not add Finished or Deleted projection
choices yet; those states can be added later as a separate AppWidget expansion.

Folder-aware Things-list AppWidget configuration should use explicit database
fields instead of extending the legacy negative `thing_id` encoding. The
`app_widget` record should keep enough independent data to represent root or
Folder target scope, selected type-filter mask, and List/Grid display mode
while preserving existing alpha and style settings. Existing Things-list widget
records should migrate to root scope, their current single built-in limit as
the equivalent type filter, and List display mode.

When migrating existing Things-list AppWidgets, legacy `ALL_UNDERWAY` should
map to the exclusive All type filter rather than to all current specific type
icons. This preserves the open-ended "all underway Things" meaning.

Things-list AppWidget item ordering should follow the same mixed Thing/Folder
ordering as the corresponding home projection, including sticky Things and
sticky Thing Folders. List and Grid display modes change layout only; they do
not introduce a separate widget-specific sort order.

The Things-list AppWidget configuration Folder picker should show all
non-deleted Thing Folders rather than filtering the tree by the current type
icon selection. Folder scope and type filters are independent choices, so an
otherwise valid Folder should not disappear while the user changes type icons.

The create button in a Things-list AppWidget should not force the new Thing
type from the widget's type filter. Creation should use the existing Detail
create flow, where the Thing's final type is determined by the reminder time,
repeat settings, and other fields the user sets while creating it.

When a Things-list AppWidget targets a Thing Folder, its create button should
open creation in that Folder scope. The Folder scope affects where the new Thing
is created, while the widget's type filter still does not force the Thing type.

Things-list AppWidget header titles should show the configured scope and type
filters directly. Root + All shows the Underway title. Root + multiple specific
types joins the selected type names with `/`, for example `Reminder/Habit`.
Folder + All shows the Folder name. Folder + specific type filters shows
`Folder name · Reminder/Habit` using the selected type names.

Things-list AppWidget headers use the app accent for root scopes and the
selected Thing Folder's pure colour or gradient for Folder scopes. Header
foreground should adapt to the rendered header background, and the existing
header transparency setting still applies.

In Grid mode, each card slot inside a row should own its click fill-in intent.
The row is only a RemoteViews container and should not handle item clicks
itself. Thing slots open Detail; Folder slots open the app at that Folder
projection.

Grid rows should keep stable column widths by filling incomplete rows with
transparent, non-clickable empty slots.

The Things-list AppWidget header keeps its settings and create buttons in both
List and Grid display modes. The configuration does not need a live AppWidget
preview in this slice. The List/Grid display-mode choice should not use radio
controls; it should follow the Thing Card appearance panel's compact row
pattern with a left label and two text options that have pill-shaped touch
ripples and a visible selected state.

The Things-list AppWidget transparency slider primarily controls each content
card's own background transparency, including Thing cards and Folder summary
cards. The existing top-bar transparency checkbox applies that same alpha to
the widget header; without it, the header remains opaque.

Existing Things-list AppWidget configuration state maps legacy Underway to All,
legacy single type limits to the matching specific type icon, and new widgets
to root scope, All, List mode, and the existing default alpha/style settings.
Type icon ordering is fixed as All, Note, Reminder, Habit, and Goal. Header
type-name joining uses this fixed order, not click order.

A Folder-scoped Things-list AppWidget shows direct child Things and direct
child Folder summary cards. It does not recursively flatten descendants.
Things-list AppWidget item interactions are click-only; Folder and Thing rows
do not provide widget-level long-press menus or card actions.

The Things-list AppWidget configuration picker follows the Drawer model for its
root row: the Underway root row is selectable, has no trailing expand/collapse
icon, and the first Folder level is always visible beneath it. Folder row bodies
select that Folder as the widget scope, while trailing expand/collapse icons
show or hide child Folders. The picker shows the first Folder level by default,
expands the configured Folder's ancestor path when editing an existing widget,
and does not need search in this slice.

Single-Thing AppWidget configuration remains a Thing selector. It should become
Folder-aware only as navigation: Folder rows appear so users can browse into a
Thing Folder, the configuration title and back/up behavior follow the current
Folder, and only Thing rows can be selected as the single widget target.
Each Folder projection in Single-Thing AppWidget configuration shows child
Thing Folders and direct Things in the same mixed order as the home list. Folder
rows are navigation entries, while only Thing rows can be selected as widget
targets.
Things-list AppWidget configuration is a separate configuration surface, not
the single-Thing card picker, so Folder target selection for list widgets needs
its own UI control.

AppWidget media transparency must be rendered into the bitmap that is assigned
to the RemoteViews `ImageView`. Launcher/runtime handling of
`ImageView.setImageAlpha` is not reliable enough for widget preview or desktop
widget media. This applies to media-background cards and foreground image/video
thumbnail placements.

RemoteViews Thing and Folder cards must keep their foreground affordances
luminance-adaptive. Small icons, audio attachment icons, habit/reminder/state
icons, privacy locks, and dashed separators should use black-side resources or
tints on light cards and white-side resources or tints on dark/media-background
cards. Folder summary cards in Things-list AppWidgets should use the same
rounded card clipping as Thing cards.

Things-list AppWidget configuration type filters should keep the circular icon
touch targets from the app chrome. The panel also shows a live summary label,
for example `Thing type: All` or `Thing type: Reminder/Habit`, above the five
type icons.

## 2026-06-20 - Folder navigation restores parent scroll state

Opening a child Folder projection should keep the existing top-start behavior
for the newly opened Folder, but returning to the parent projection should
restore the parent's previous RecyclerView layout state. `ThingsActivity`
therefore caches `LayoutManager.onSaveInstanceState()` by
`ThingListProjection.key()` before leaving a projection and restores that state
after the parent projection has reloaded and rebound.

The scroll cache is Activity-local only. It should not become persisted Folder
state because it represents transient navigation context, not the user's
stored Folder data.

The restore must be applied synchronously to the `LayoutManager` before the
next frame is drawn, with the Activity Header updated from the restored first
visible adapter position in a pre-draw callback. A posted restore is too late:
it can allow one frame where the parent projection is rebound at the top or
with an expanded Header before jumping to the saved position.

The same restore path should also disable `ThingsAdapter`'s regular Things
appearing animation for that rebind. The visible issue is not smooth scrolling:
the appearing animation can replay when returning to an already-seen parent
projection, making cards animate from an initial state instead of simply
rendering at the restored scroll offset.

Gradient actionbar icon tint should use the same visual strength as pure-colour
toolbar icon tint. The gradient path renders the drawable into a bitmap mask,
normalizes the mask's maximum alpha to opaque, then fills only that icon mask
with the Folder gradient. This prevents old semi-transparent toolbar assets
from looking washed out while still avoiding tint over the whole touch target.

## 2026-06-20 - Activity Header Thing counts are recursive

Activity Header subtitles should keep Folder count as the visible direct child
Folder count, but Thing count should represent the full current subtree. For
root projections this means direct root Things plus Things inside every visible
root Folder subtree. For Folder projections this means direct Things in the
current Folder plus Things in every descendant Folder that matches the current
projection/filter.

The header should reuse the mixed-list `ThingListEntry.FolderEntry` recursive
count instead of issuing a separate database query. This keeps the Activity
Header count aligned with Folder Card count filtering and avoids adding another
counting path.

The create-Thing FAB should use the `vec_ic_create_thing` vector copied from
Everything-Android. Start-doing affordances should use the
`vec_ic_start_thing` vector copied from Everything-Android while preserving the
existing view/layout sizes at each call site.

## 2026-06-20 - Folder projections tint the surrounding chrome

Folder projection chrome should carry the current Folder identity without
making the list surface look like a large colour block. The muted Folder
surface should therefore move even closer to `bg_activity_things`; only toolbar
icons, the Activity Header Folder title, the create-Thing FAB, and contextual
selection chrome use the stronger Folder pure colour or gradient.

For normal in-Folder mode, the create-Thing FAB uses the Folder background and
the Home actionbar icons use the Folder colour/gradient tint. For contextual
selecting mode, the contextual toolbar plus its status-bar spacer use the
Folder colour/gradient as the background; its title and icons use a dark or
light foreground chosen from the Folder background's representative luminance.

Root Activity Header subtitles should use the same direct-child Folder/Thing
count text as Folder projections. Zero-count segments are omitted in both
contexts.

## 2026-06-20 - Folder projections share the muted Folder surface

The muted Folder surface should lean more strongly toward the app's list
background than the first implementation. `BackgroundUtil.mutedSurfaceBackground`
now uses a smaller Folder-accent blend so thumbnail Folder interiors and Folder
projection screens remain visually close to `bg_activity_things`.

When ThingsActivity is showing a Folder projection, the main list surface and
status-bar spacer should use the same muted Folder surface as large
thumbnail-mode Folder Cards. Root projections should restore plain
`bg_activity_things`. The Activity Header Folder title should use the Folder's
own pure colour or gradient text fill, while the subtitle should omit zero
direct-child count segments instead of showing `0 folders` or `0 things`.

## 2026-06-20 - Thumbnail Folder interior fills are muted Folder surfaces

The native-elevation strategy remains the current implementation for
thumbnail-mode Folder Cards and their drag overlays. The interior fill no
longer needs to be the exact `bg_activity_things` colour, though; it should be
a muted version of the Folder background so the surface still reads as close to
the surrounding list background while carrying a small amount of the Folder's
own colour identity.

`BackgroundUtil.mutedSurfaceBackground(...)` owns this derivation. It blends the
current theme's list background toward the Folder background by a small amount,
using a slightly stronger tint in dark mode so the hue remains visible against
`#121212`. For gradient Folder backgrounds, both gradient stops are blended
toward the same list surface and the original gradient orientation is preserved.

The list card and `DragOverlayImageView` must use the same derived
`ThingBackground`. This keeps transparent-looking interior areas visually
consistent before and during overlay drag, while preserving the cheaper native
`CardView` / View elevation path.

## 2026-06-20 - Thumbnail Folder shadows prefer native elevation over true transparency

The outside-only transparent shadow decision below is superseded for current
implementation. Real-device testing showed that drawing a
`MaterialShapeDrawable` compat shadow through a RecyclerView decoration and
clipping it to an outside-only path is too expensive during list scroll and
overlay drag. The result does preserve true transparent interior pixels, but it
does not meet the interaction performance requirement.

Thumbnail-mode Folder Cards should therefore return to the cheaper native
elevation strategy: the `CardView` itself uses the same normal and dragging
elevation as ordinary Thing Cards, and its otherwise empty interior is filled
with `bg_activity_things` to cover the inner half of the native outline shadow.
This makes the interior transparent-like against the current list background
but not alpha-transparent.

Thumbnail-mode Folder drag overlays should follow the same trade-off. They use
native View elevation on expanded overlay bounds with an inset rounded
`Outline`, draw `bg_activity_things` inside the content rect, and then draw the
captured bitmap. This keeps the native shadow look and avoids per-frame compat
shadow, `clipPath`, or software-layer work.

`ThingListOverlayDragController` still belongs in the `managers` package rather
than the `activities` package.

## 2026-06-20 - Transparent thumbnail Folder shadows use an outside-only layer

The previous thumbnail Folder Card shadow strategy that filled the transparent
Folder surface with `bg_activity_things` is superseded. User testing showed
that the blank interior area must be truly transparent in both the normal list
card and the drag overlay; matching the list background is not equivalent.

Android's View shadow model still makes native elevation a poor fit for this
specific surface: the shadow is derived from the View `Outline`,
`clipToOutline` clips content rather than the shadow, and `View.draw(Canvas)`
does not capture real-time shadows or outline clipping into the overlay bitmap.
A single transparent elevated `CardView`/`ImageView` can therefore leak the
inner half of the shadow through transparent pixels.

Thumbnail-mode Folder Cards should keep their real `CardView` background
transparent and set native `cardElevation` / `maxCardElevation` to `0f`. The
visible outer lift is drawn by `ThumbnailFolderCardShadowDecoration`, which
uses `MaterialShapeDrawable` compat elevation under RecyclerView children and
clips the drawable with an even-odd outside-only rounded path. The card content
remains transparent because the inner rounded rect is never painted.

Thumbnail-mode Folder drag overlays use the same outside-only shadow helper.
`DragOverlayImageView` keeps expanded bounds for shadow overflow, clips the
shadow to the area outside the content rounded rect, then clips and draws the
transparent bitmap content inside that rounded rect. Ordinary Thing Cards and
summary Folder Cards continue to use the native elevation path.

`ThingListOverlayDragController` belongs in the `managers` package rather than
the `activities` package. It still communicates with `ThingsActivity` through
the existing Host contract.

## 2026-06-20 - In-Folder header spacer updates are not scroll state

The in-Folder Activity header may change its visible title width and line
count while collapsing into the Toolbar, but the RecyclerView's invisible
header spacer must represent the expanded header's stable occupied space. It
must not be updated from header layout changes that happen during scrolling.

Spacer height requests should be emitted only from explicit expanded-header
refresh points such as Folder navigation, header text changes, or reset after
configuration/search transitions. Applying the spacer to the Thing list adapter
must also wait until the RecyclerView is idle and not computing layout, because
`notifyItemChanged(0)` during `StaggeredGridLayoutManager.fill(...)` can make
RecyclerView try to attach an already-attached header holder and crash.

The collapsed Folder title's vertical centering should be computed from the
current visible title layout, including the capped two-line collapsed title
height, rather than from the expanded header block height.

The header collapse controller should clamp scroll distance to the current
header spacer height instead of resetting to expanded state when scroll exceeds
the legacy 102dp header height. Staggered-grid scroll callers should also use
the minimum visible adapter position across all spans, not only span 0, when
deciding whether the invisible header spacer is still the first visible item.
Otherwise the title, subtitle alpha, and actionbar shadow can jump between
expanded and collapsed states at the spacer boundary.

## 2026-06-19 - Folder privacy, sticky placement, and move dialog rules

Private Thing Folders must protect every operation that reveals or changes
private containment. Opening the card-appearance panel for a Private Thing
Folder, dragging a Thing or Folder into a Private Thing Folder, moving a Thing
or Folder into or out of private containment, expanding a Private Thing Folder
in a move target tree, and swiping a private Thing Card all require the same
password or fingerprint verification path before the operation proceeds.

Sticky Things and sticky Thing Folders share one mixed sticky section at the top
of the home list. They are not separated by type, and toggling sticky state
should update the visible list immediately rather than requiring an app restart.
When a card is dragged in Moving mode, no insertion line should be shown before
the sticky section's first entry because ordinary reordering cannot place an
entry ahead of sticky content.

Folder-scoped sticky placement is independent from root sticky placement. A
Thing or Folder can be sticky inside its current Thing Folder, should be placed
first within that Folder projection, and should use the containing Folder's
pure colour or gradient for the top-right sticky indicator. If the item leaves
that Folder for any reason, its Folder-scoped sticky state must be cleared.
Thumbnail-mode Folder Cards must also render the indicator for sticky child
Things or child Folders in their preview content.

Move-to-Folder UI must use the app's custom `DialogFragment` style rather than
a platform dialog. The dialog title is `移动到文件夹`, the content mirrors the
Drawer Folder tree row model with Folder icon, title, expand/collapse affordance,
and indentation, and the root destination is labelled `正在进行`. The current
parent starts selected, confirm performs the move, and moving a Folder into
itself or one of its descendants is forbidden. The title and confirm action use
the moved Thing or Folder's own pure colour or gradient.

## 2026-06-19 - Folder long press parity and thumbnail overlay shadow

Folder Cards should not swallow long press gestures while the list is already
in selecting mode. The Folder branch should follow the same non-normal-mode
fallback as Thing Cards and call `ModeManager.backNormalMode(listPosition)`.

Thumbnail-mode Folder Cards have a transparent interior, and Android's native
elevation shadow is tied to a `RenderNode` outline rather than to bitmap alpha.
Android documentation and AOSP source show that elevation draws the shadow from
the View's `Outline`, `clipToOutline` clips content rather than the shadow, and
there is no public API to ask the platform to draw only the exterior half of a
shadow. The overlay should therefore keep platform View elevation for the real
outer shadow, use an expanded overlay image view with a content inset, and set
an inset rounded `Outline` matching the real card content rect.

The internal shadow that appears behind transparent thumbnail Folder content
should be hidden by drawing the home/list background as a rounded rect inside
the content rect before drawing the captured transparent bitmap. This preserves
the platform elevation look outside the card outline without maintaining custom
stroke, gradient, or bitmap shadow approximations. Very tall thumbnail Folder
overlays must still avoid full-overlay software layers, full-size
`saveLayer(...)` cleanup, and single oversized texture uploads; the overlay
bitmap should be tile-drawn when it exceeds the safe tile size, so a single
oversized bitmap texture does not make the drag visual disappear.

The same principle applies to thumbnail-mode Folder Cards in the list, not only
to their drag overlay. They should keep ordinary Thing Card normal and dragging
elevation, but the CardView fill should use the home/list background colour
rather than transparent pixels or the Folder background. The content outline
can remain transparent-looking and use the Folder colour/gradient for its
stroke, while the opaque list-background fill hides the inner half of the
platform shadow.

All overlay drag geometry should continue to use the content card rect, not the
expanded shadow view bounds. Finger offsets, Folder-drop top-left hit testing,
create-Folder merge targets, reorder settle targets, and release-in-place
targets should therefore convert between outer overlay coordinates and the
inner content rect so the card content still aligns top-left to top-left and
bottom-right to bottom-right.

## 2026-06-19 - Drag overlay owns enlarged lift scale

The overlay drag bitmap should be captured from the source card's normal view
content, but the session overlay itself should render at the same enlarged
Moving-mode lift scale as the visible dragged card.

After the bitmap is captured and the overlay is attached, the real source
holder should immediately cancel its scale animation, clear any moving-scale
recovery token, reset `scaleX`/`scaleY` to `1f`, and remain only as a
transparent normal-size layout placeholder. Later reveal should therefore not
play a second ViewHolder shrink animation. The overlay should carry the
enlarged visual scale during drag, use the enlarged visible frame for pointer
offset and top-left Folder-drop hit testing, and animate down to the final
normal-size holder frame on reorder or release-in-place settle.

For successful reorder settle, the final source ViewHolder alpha should be
restored only after the overlay movement animation has ended and the overlay
view has been removed from the overlay parent.

While the overlay session is active, the transparent source placeholder must be
enforced on every RecyclerView pre-draw, not only when the source holder is
first hidden or reattached. Adapter rebinds such as `notifyItemMoved(...)`,
`notifyItemChanged(...)`, or delayed full-list rebinds may reset holder alpha to
`1f`; the overlay controller remains responsible for restoring the source
holder to transparent placeholder state before the frame is drawn.

That enforcement should not depend only on the source's current list position,
because RecyclerView pre-layout during `notifyItemMoved(...)` can temporarily
keep the source holder at its old slot while the data list already reports the
new slot. Bound Thing/Folder card roots therefore carry their stable business
id as a view tag, and the overlay controller scans visible children by that tag
to keep every currently attached source holder transparent. The placeholder
reset also applies to the inner card view that owns Moving-mode scale, not only
to the RecyclerView item root.

The overlay drag visual should carry the same elevated card shape as the
Moving-mode source card. Moving mode already raises selected normal Thing and
Folder Cards to `thing_card_dragging_elevation` while scaling them to `1.11f`.
Because Android elevation shadows are not reliably rasterized by
`View.draw(Canvas)` into the content bitmap, the overlay view itself should use
the dragging elevation plus the card corner outline so the shadow is rendered
with the enlarged moving card.

For reorder settle, the overlay must not resolve its final target from
RecyclerView's pre-layout state. After `notifyItemMoved(...)`, the data list may
already report the source at its new position while the transparent source
holder is still attached at the old slot for predictive layout. The overlay
settle animation should therefore wait until RecyclerView has no pending adapter
updates, is not computing layout, has no running item animator, the holder at
the source's final adapter position is bound to the same stable business id,
and that holder's layout rectangle is stable across consecutive frames.

Transparent placeholder enforcement must not cancel the root `itemView`
animation while a reorder settle is in progress. RecyclerView's item animator
uses that root view animation to move the transparent source holder from the old
slot to the final slot. The overlay controller may keep setting the root alpha
to `0f`, but only the inner card view's app-owned Moving-mode scale/elevation
recovery animation should be cancelled.

For overlay-owned reorder settle, RecyclerView should keep ownership of the
surrounding cards' item-move animation. The overlay controller should not force
an immediate full-list refresh to bypass the predictive layout gap, because
that removes the other cards' re-layout animation and can disturb scroll
position. Instead, the commit path should use `notifyItemMoved(...)`; the
overlay should wait until RecyclerView has had at least the item move duration
to start and finish, wait an additional short post-animation grace window, no
item animator is running, scrolling is idle, no adapter updates are pending,
and the final source holder rect is stable across multiple frames before
playing the dragged-card settle animation.

Because `StaggeredGridLayoutManager` keeps a lazy adapter-position-to-span
assignment cache, a moved item's holder rect can look stable before the final
gap-free span assignment has been recalculated. The overlay controller should
therefore request simple animations for the next layout and invalidate
StaggeredGrid span assignments immediately after the successful
`notifyItemMoved(...)` commit, before RecyclerView consumes that adapter update.
That lets the span correction coalesce into the same post-layout animation
pass. Once that final layout rect is available on pre-draw, the overlay should
start on the next animation frame with the same move duration, so the dragged
overlay and RecyclerView's final arrangement animate together.

For a successful drop into an existing Thing Folder, the commit overlay should
not fly into the target Folder Card center. Because the target Folder Card is
already visually highlighted as the receiver, the dragged overlay should keep
its release position, use a top-left pivot, and shrink directly to
`scaleX/scaleY=0`. RecyclerView remains responsible for the source removal and
gap-closing movement of the other cards through targeted adapter notifications.

The full-list rebind used to restore normal colours after exiting Moving mode
must be delayed until RecyclerView has consumed the targeted Folder-drop
adapter update, completed layout, and finished any running `ItemAnimator`
animations. Running that rebind while `notifyItemRemoved(...)` is still
producing move animations can cancel the remaining gap-closing animation and
make the list appear to jump.

When overlay reorder commits near the bottom of the staggered grid, SGLM's
predictive pre-layout can produce an intermediate post rect that still belongs
to the old span assignment. A visible target card may therefore animate
straight down to make room for the moved card, then jump sideways after a later
gap-correction or full-list rebind recalculates the final span. For the single
layout pass after an overlay reorder commit, `ThingsStaggeredLayoutManager`
should suppress predictive item animations while still requesting simple item
animations and clearing span assignments. RecyclerView can then record existing
visible child frames as the pre-layout information and animate them directly to
the final non-predictive SGLM span assignment.

## 2026-06-19 - Overlay release targets use layout-space card frames

Overlay drag release animations should align the overlay bitmap frame to the
final card holder's untransformed layout frame: top-left to top-left and
bottom-right to bottom-right.

The controller must not derive this frame by combining
`View.getLocationOnScreen()` with raw `width`/`height` when the card may still
have transient `scaleX`/`scaleY` from Moving-mode lift or recovery animations.
That mixes a transformed top-left with untransformed dimensions and can make
the overlay land offset from the final ViewHolder. Reorder and release-in-place
targets should therefore compute holder coordinates by walking layout
`left`/`top` values up to the overlay root while ignoring scale and, for final
settle targets, ignoring RecyclerView item-animation translations.

## 2026-06-19 - Reorder insertion line stays near the target card

The overlay-drag reorder insertion line should indicate the final target card
edge, not the geometric midpoint of an arbitrarily large masonry gap.

For an insert-before candidate, draw the line a small fixed distance above the
target card. For an insert-after candidate, draw it a small fixed distance
below the target card. If another visible card in the same horizontal span
leaves too little space, the line may be clamped toward the local gap midpoint
so it does not overlap either card. Large gaps created by tall Things or
Folders should not pull the line far away from the target card.

The insertion line thickness should be specified in dp, not raw pixels, so it
has the same visual weight across different display densities.

## 2026-06-18 - Overlay reorder release settles with RecyclerView move animation

When an overlay drag session commits an ordinary reorder, RecyclerView should
own the surrounding card rearrangement and the session overlay should remain
the visible dragged card until the moved source reaches its final layout slot.

On release, the commit path should perform the data move plus
`notifyItemMoved(...)`, keep the real source holder transparent as the layout
placeholder, wait for RecyclerView to lay out the moved source in its final
slot, and then animate the overlay from the release position to that final
source holder layout rectangle. The overlay settle duration should match the
RecyclerView item move duration so the gap-making animation and the dragged
card landing animation read as one coordinated motion.

The source holder should be revealed only after the overlay settle finishes,
with transient drag scale and tags reset before it becomes visible. If the
final source holder cannot be resolved after layout retries, the overlay may
fade out and request a targeted source rebind as a recovery path rather than
committing another reorder or forcing a scroll back to the original source.

The overlay snapshot may include the card's long-press Moving-mode enlarged
scale. Its release animation should therefore shrink the overlay back toward
the normal card scale while it lands, and should wait until RecyclerView item
animations are finished before revealing the real source holder. Revealing the
holder while it still has a running move translation, or while the overlay is
still visually enlarged, can create a final-frame jitter.

Successful reorder release should reveal the source holder resolved by stable
Thing/Folder identity after the final layout and item-animation cleanup, not
blindly restore the original `sourceView` reference captured at drag start.
That original holder reference may be stale after a move, and restoring its
alpha too early can show the real card underneath the overlay for one frame.

The reorder overlay's final target is the moved source holder's final
`itemView` layout rectangle as a bitmap frame. The lift-time bitmap may already
contain any long-press card scale inside that frame, so the controller should
not divide the whole overlay frame by the captured card scale when calculating
the final `x`/`y`. Mixing the inner card scale into the outer bitmap-frame
target can make the overlay land with a visible coordinate offset.

Any full-list rebind used only to restore cards from Moving-mode colours after
an overlay reorder must wait until the overlay drag session is no longer active.
A delayed `notifyDataSetChanged()` that runs while the overlay is still settling
can reveal the real moved card underneath the overlay and make RecyclerView's
rearrangement look like a separate jump.

When a dragged card returns to its original or equivalent list position, the
release should still animate the overlay back to the original source slot
before entering selecting mode. Returning to the original position is not a
fade-out case; it should preserve the same spatial continuity as a successful
reorder settle.

## 2026-06-15 - Initial requested product shape

Thing Folders will be created from the home list by dragging one Thing onto
another after long-pressing a Thing. A Thing Folder can be named, appears on the
home list as a Folder Card, supports nested folders, shows its path in the home
header when opened, and is referenced from the Detail screen of contained
Things.

The initial request included showing created folders in the drawer. That part
is superseded by the later drawer decision below.

## 2026-06-15 - Domain term

Use **Thing Folder** in docs and code-facing product language, not "note
folder", because the project glossary defines **Thing** as the cross-type item
that can be a note, reminder, habit, goal, task, or related item.

## 2026-06-15 - Thing Folder membership is a strict tree

Thing Folder membership is a strict tree. A Thing may belong to at most one
Thing Folder, and a Thing Folder may have at most one parent Thing Folder. The
feature will not implement multi-parent aliases, shortcuts, or tag-like shared
membership.

## 2026-06-15 - Thing Folders are list projections, not drawer items

Superseded by the 2026-06-17 Drawer folder tree decision below.

Thing Folders are not shown as drawer items. The drawer stays focused on
built-in global destinations such as Underway, Notes, Reminders, Habits, Goals,
Finished, and Deleted.

The current list state is a built-in drawer destination plus an optional Thing
Folder Path projection. Opening a Thing Folder Card keeps the current built-in
drawer destination selected and adds or extends the folder path projection.
Selecting any built-in drawer destination resets the folder path projection and
opens that built-in destination at its root.

Header text shows the built-in destination at root and shows the projected path
when inside folders, for example `Finished / Folder / Child Folder`.

## 2026-06-17 - Drawer shows the Underway Folder tree

The Drawer should show the non-deleted Thing Folder tree directly under the
Underway item and above the Note item. Underway acts as the root directory.

The first Folder level is always visible because the Underway root is always
expanded. Deeper Folder levels are shown only when their parent Folder is
expanded. Folders with child Folders show a trailing dropdown affordance; tapping
that affordance toggles expansion without changing the selected destination.

Tapping a Folder row opens that Folder within the Underway projection. The
Drawer has one checked item at a time: an opened Folder row, Underway root, or
one of the other built-in destinations.

Folder hierarchy is represented by a small indentation that begins at the
Folder icon. Folder icons should use each Folder's own background, supporting
both pure colours and gradients. The Note group starts after a separator below
the Folder tree.

## 2026-06-18 - Home Drawer uses an app-owned navigation view

The home Drawer no longer relies on Material `NavigationView` for its item
rows. Folder tree requirements need precise control over indentation, title
width, trailing expand/collapse affordances, row animation, selected state, and
view recycling. The home Drawer should use the app-owned
`DrawerNavigationView`, backed by typed rows and a RecyclerView adapter, while
continuing to reuse the existing `DrawerHeader`, `DrawerLayout`, and toolbar
toggle behavior.

## 2026-06-15 - Folder Card counts are recursive and include private Things

The count shown on a Thing Folder Card is a recursive descendant Thing count,
not only a direct-child count. It includes Things inside nested Thing Folders.

Private Things are included in the count even when private content is hidden.
This matches the existing home header count behavior, which counts Things by
type and state without filtering out private Things. Folder thumbnails and
inline previews must still avoid exposing private content when private content
is hidden.

## 2026-06-15 - Finished and deleted Things preserve folder membership

Finished and deleted Things keep their Thing Folder membership. The Finished
and Deleted built-in destinations should show corresponding Thing Folder Cards
when a folder contains descendant Things matching that destination.

Restoring a deleted Thing should return it to the same folder membership it had
before deletion unless the containing folder has itself been permanently
removed in a later feature slice.

## 2026-06-15 - Folder Cards open within the active built-in destination

When a Thing Folder Card appears inside a built-in destination, tapping its
non-thumbnail area opens that folder as a projection within the same built-in
destination. For example, tapping a Folder Card in Finished opens the folder's
Finished projection and keeps Finished selected in the drawer. Tapping a Folder
Card in Deleted opens the folder's Deleted projection and keeps Deleted
selected in the drawer.

## 2026-06-15 - Built-in destinations show matching Folder Cards

Every built-in destination can show Thing Folder Cards. Underway, Notes,
Reminders, Habits, Goals, Finished, and Deleted should each show a Folder Card
when that folder recursively contains Things matching the active built-in
destination.

The Folder Card count is computed using the active built-in destination's
matching descendants. Opening that Folder Card keeps the active built-in
destination selected and opens the matching folder projection.

## 2026-06-15 - Folders support sticky, mixed ordering, and privacy

Thing Folders support sticky placement, manual ordering, and privacy.

Folder Cards and Thing Cards share the same mixed ordering space inside a root
or folder projection. Users can reorder Folder Cards among Thing Cards.

Sticky Folder Cards participate in the same top-of-list sticky area as sticky
Thing Cards. A sticky Folder Card stays sticky as a folder property, not only in
one built-in projection.

Thing Folders can be private. Private Folder Cards protect access and contained
previews when private content is hidden. The original hidden-title behavior is
superseded by the 2026-06-17 Private Folder Card title decision below.

## 2026-06-15 - Private folder privacy inherits for display and access

A Private Thing Folder makes all descendant Things and child folders effectively
private for display and access while they remain inside that folder. This
effective privacy protects list cards, thumbnails, search results, path display,
and folder navigation.

Effective inherited privacy does not rewrite a descendant's own stored private
state. If a non-private Thing is moved out of a Private Thing Folder, it becomes
non-private again unless it is private by its own stored title/state.

## 2026-06-16 - Authenticated private folder scope reveals descendants

Opening a Private Thing Folder after authentication creates an authenticated
folder scope for that open projection. Within that scope, descendant Things and
child folders are displayed normally, including real folder names and normal
Thing/Folder cards, because the user has already authenticated to enter the
private container.

The authenticated folder scope is projection-specific rather than a global
"show all private content" mode. Private content outside the opened private
folder remains protected unless the existing global private-content flow has
enabled it.

Setting a Thing Folder private requires the app private password to exist, using
the same prerequisite as setting a Thing private.

## 2026-06-16 - Folder drag hardening avoids Adapter stable ids

Do not enable RecyclerView Adapter stable ids as part of Folder-drop animation
hardening. The project has previously seen stable-id changes interact badly
with mixed projections, broad `notifyDataSetChanged()` rebinds, ViewHolder
visual state, and RecyclerView gesture animations.

Folder drag state may still use stable business ids such as `sourceThingId`,
`targetThingId`, and `targetFolderId` internally. That identity should remain a
drag-session invariant, not an Adapter stable-id contract.

## 2026-06-16 - Active list gestures own temporary z-order

During active Thing-list swipe or drag gestures, the touched card should remain
above all sibling Thing and Folder Cards. The gesture layer owns this temporary
z-order through transient `translationZ`; normal `cardElevation` remains owned
by card touch, selection, moving-mode, and Folder-drop feedback animations.

The temporary z-order must be reset in `ItemTouchHelper.clearView(...)` and
when ViewHolders are rebound, so recycled cards do not keep an old gesture
layer.

## 2026-06-17 - Folder naming uses app DialogFragment semantics

Thing Folder creation and rename naming prompts use a custom app
DialogFragment instead of a platform-default AlertDialog. The dialog adapts its
title, confirm button, and EditText focus treatment to the Folder background,
including gradient-aware text and underline drawing.

Canceling the naming dialog that appears after drag-creating a Folder cancels
the creation itself. The two source Things are reparented back to the Folder's
original parent projection, and only the newly-created Folder record is removed.
This rollback path must not use permanent Folder deletion because that operation
deletes contained Things.

## 2026-06-17 - Thumbnail Folder Cards use interaction-stripped Thing Card previews

Thumbnail-mode Thing Folder Cards should render child previews that stay close
to complete Thing Card presentation, rather than a title/content-only
substitute. The child preview is compacted through content constraints and may
simplify unusually dense type-specific surfaces, but it should preserve the key
visible Thing Card semantics such as empty-title handling, checklist previews,
and image or video Thing Card Media.

Child previews support only tapping to open the child Thing. Nested checklist
toggles, long-press actions, selection, dragging, and other Thing Card
interactions are not active inside Folder Card previews.

For Things with image or video media, child previews strictly reuse Thing Card
Media presentation, including the selected source, crop, target aspect ratio,
video frame, image placement, media background presentation, and full-span Thing
Card presentation where applicable. Media-heavy child previews may use more
vertical space than text-only previews, but the preview should avoid hard
clipping and instead stay compact through preview-specific constraints such as
text max-lines, smaller typography, checklist item limits, simplified Habit
detail, and media target sizing.

Inside a full-span thumbnail-mode Folder Card, a full-span child Thing preview
also spans the full preview width rather than being squeezed into one masonry
column.

Inside a normal-span thumbnail-mode Folder Card, a full-span child Thing preview
cannot become wider than the Folder Card, but it still keeps full-span Thing
Card internal presentation within that one-column preview.

Habit child previews keep the core Habit summary but may omit dense Habit
record details, such as the last-five-record surface, inside constrained Folder
Card previews.

Normal-span Folder Cards show a one-column preview list capped at three Things.
Full-span Folder Cards show a three-column masonry preview capped at six
Things. Both surfaces show a small bottom ellipsis when additional matching
descendants are not rendered. The ellipsis is an explicit Folder-open target
and should stay visually compact, similar in footprint to the checklist
"more items" ellipsis.

## 2026-06-17 - Thumbnail child previews scale rendered card chrome

Thumbnail child previews continue to bind through the real Thing Card or Folder
Card paths first, then apply a preview-only visual scale to the rendered child
card tree. This post-bind scale covers card chrome that does not flow through
content-specific preview hooks, including titles, folder icons, media/audio
count labels and icons, reminder/habit/goal timing labels and icons, private,
sticky, and doing indicators, and TextView compound drawables. Checklist
preview rows also receive preview-specific text sizing and icon scaling through
their own adapter because their row views can be created inside the nested
RecyclerView path.

The post-bind scale is not a replacement for content policy. Preview-specific
hooks still own content max-lines, checklist item limits and text size, Habit
detail simplification, media surface sizing, nested-interaction stripping, and
child Folder summary-mode forcing.

The preview scale applies separately to spacing and to visual content. Internal
padding, margins, and fixed bottom spacer heights are compacted so the child
card does not keep ordinary list-card whitespace, but the same spacing scale
should be used for ordinary vertical top/bottom gaps to avoid flipping which
side looks larger. Actual Thing Card Media surfaces are excluded from icon
scaling so side media panels and media backgrounds remain edge-to-edge, but
their container margins still participate in thumbnail spacing compaction.

Folder thumbnail child previews preserve the ordinary dynamic content text-size
relationship before applying thumbnail limits. The preview may clamp the
computed size so short content remains larger than long content without
becoming full-size ordinary-list typography.

Because preview compaction changes the final rendered target size after the
normal Thing Card bind path has run, Folder thumbnail child previews reapply
Thing Card Media crop once the preview card is posted with its compacted
dimensions. The crop reapply path must preserve the media presentation kind and
prefer the current rendered media target size when available: side media uses
side-panel crop, foreground thumbnails use thumbnail crop, and media
backgrounds use media-background crop.

Child preview adapters reuse the parent list adapter's Thing Card Media bitmap
cache. This keeps the existing media-load cache effective for Folder thumbnail
previews, even though each child preview is bound through a constrained
adapter.

Thumbnail preview containers allow child-card shadow overflow by disabling
parent clipping. If a child preview shadow looks clipped, prefer fixing the
container clipping boundary over lowering preview elevation again.

## 2026-06-17 - Nested Folder previews use direct child entries

Thumbnail-mode Folder Cards preview direct child entries, not a flattened list
of all recursive descendant Things. Direct child Folders that match the current
projection appear in the preview as summary-mode Folder Cards, and tapping such
a child Folder preview opens that Folder through the same privacy/authentication
path as an ordinary Folder Card.

The Folder Card count label combines direct child Folder count and recursive
matching Thing count. In Chinese this is `X个文件夹，Y件记事`; if either count is
zero, omit that segment.

Child preview cards inside thumbnail-mode Folder Cards use reduced elevation so
their shadows fit the existing compact preview spacing. The compact ellipsis
does not reserve a large bottom margin.

## 2026-06-17 - Thumbnail preview spacing and media crop ratios

Thumbnail-mode Folder Cards use a fixed 12dp gap between the Folder count label
and the first child preview, matching the spacing that previously felt correct
on full-span Folder Cards. Child preview cards use a 7dp vertical item gap in
both normal-span one-column previews and full-span masonry previews. Masonry
rows own the first-child top gap so a row margin and a child margin do not add
up into a doubled gap.

Folder thumbnail child previews must preserve the saved Thing Card Media crop
ratio in addition to crop center and user scale. Foreground thumbnails and
side-panel media use `ThingCardThumbnailCrop.sourceAspectRatio`; media
background previews use the source presentation's media-background target
aspect ratio. This applies to images and video frames.

## 2026-06-17 - Moves insert first and empty folders were removed

When a Thing or Thing Folder moves into a different Thing Folder, moves back to
its previous parent, or moves back to root, the moved entry becomes the first
item in that target root or target Folder's corresponding sticky or non-sticky
section, preserving the entry's existing sticky state. The move should not
preserve the entry's old relative order from its source container.

Superseded on 2026-06-21: Empty Thing Folders are now valid user-owned
containers, so moves should no longer automatically delete a Thing Folder merely
because the source Folder became structurally empty.

## 2026-06-21 - Empty Thing Folders are valid containers

Users may create and keep Empty Thing Folders. Moving Things or child Thing
Folders out of a Thing Folder should preserve the now-empty Folder rather than
deleting it automatically.

## 2026-06-17 - Private Folder Cards keep visible titles

Private Thing Folder Cards still show the Folder's stored title when private
content is hidden. The card may keep a lock indicator and must continue to hide
contained thumbnail previews until authentication, but the Folder name itself is
not treated as protected card content.

## 2026-06-17 - Folder long-press uses drag and selection mode

Long-pressing a Folder Card should no longer open a multi-action dialog. It
should mirror the Thing Card long-press interaction: the Folder can be dragged,
dropped into another Folder, or released back in place to enter selecting mode.

Folder sticky state should be controlled through the existing contextual
selection menu. Folder card appearance should reuse the selection menu's
appearance action, with Folder-specific labels and controls.

Private Folder toggling should be reachable from both the current Folder
projection overflow menu and the selection contextual menu. The same privacy
entry should also be available for Thing Cards so the privacy affordance stays
consistent between Things and Folders.

Folder dissolve and delete actions should be available from the selection
contextual menu and the current Folder overflow menu. Both operations must
confirm through the app's custom DialogFragment surface before mutating data.

Outside Deleted, deleting a Folder moves the Folder and its contained subtree
into the Deleted state. Inside Deleted, the corresponding action text and
operation become permanent delete, which recursively destroys the Folder subtree
and contained Things.

## 2026-06-18 - Full-session drag overlay owns moving visuals

The next hardening pass for long Thing and Folder drags should treat the
session overlay as the single moving visual authority from active drag start
until release or cancellation. The RecyclerView child should no longer be the
live moving card during the drag session; the list should instead provide
layout feedback, hover/drop candidates, auto-scroll, and final mutation
commit.

This supersedes the narrower Folder-drop commit overlay model for the active
drag visual. The existing commit overlay captured only the final Folder-drop
animation and still depended on ItemTouchHelper moving the real child during
the drag. A full-session overlay is intended to decouple the finger-following
visual from ViewHolder detach/recycle behavior during long auto-scrolling
drags through large cards and folders.

The overlay drag session must preserve the existing Thing and Folder drag
feature surface: dragging either a Thing Card or a Folder Card, release-in-place
selection-mode entry, creating a new Folder by dropping a Thing onto another
Thing, adding/moving Things or Folders into an existing Folder, and ordinary
reordering.

During an active overlay drag session, the list should not mutate the
underlying order with live `move(...)` calls or `notifyItemMoved(...)` events.
It should keep the original list as the data source, render only visual
candidate feedback such as insertion position and Folder-drop hover state, and
commit the final reorder or Folder-drop mutation once the pointer is released.

Thing and Folder Card drag sessions should no longer be owned by
`ItemTouchHelper.startDrag(...)`. ItemTouchHelper may continue to support other
list gestures such as swipe, but long-press drag should move to an app-owned
drag session controller that tracks pointer movement, overlay position,
auto-scroll, candidate targets, cancellation, release, and final commit.

On pointer release, the overlay drag session should resolve outcomes in this
order: valid Folder drop, valid reorder, release-in-place selection-mode entry,
then cancellation/interruption cleanup. Moving away and returning to the
original position should be treated as release-in-place and enter selection
mode. Cancellation paths such as `ACTION_CANCEL`, window focus loss, activity
pause, or data-source invalidation should cleanly restore the list without
committing Folder drop, committing reorder, or entering selection mode.

Release-in-place selection should select the original source object by stable
business id, not by the release point or a stale adapter position. A Thing drag
selects the original Thing id; a Folder drag selects the original Folder id. If
the current mixed-list position is available, the session should enter
selection mode at that position. If the object still exists but is not
currently visible, selection state should still be restored by id and surfaced
through rebind rather than selecting another visible card.

The overlay rewrite must preserve existing visible interaction animation
quality rather than replacing all effects with abrupt state changes. Drag start
lift/scale, finger-following motion, Folder-drop hover feedback, commit
animation, reorder settlement, selection-mode entry, and cancellation/recovery
should keep animation ownership explicit so recycled ViewHolders do not keep
stale scale, alpha, outline, elevation, or selection tint.

During an active overlay drag session, the real source card should remain in
the RecyclerView only as a layout placeholder and should be fully transparent,
not dimmed or partially visible. This prevents the user from seeing two copies
of the same Thing or Folder Card while preserving masonry/list layout stability
around large source cards. The transparent placeholder must be non-interactive
and all visibility, alpha, scale, elevation, outline, and selection-tint state
must be restored on release, cancellation, data invalidation, and ViewHolder
rebinding.

The full-session drag overlay should initially render from a full-size bitmap
snapshot of the source card captured at drag start. The overlay should not
construct and bind a second live Thing or Folder Card view during the drag. A
bitmap keeps the drag visual identical to the source card at lift time while
avoiding duplicate adapter state, nested preview interactions, media loading,
and live child-view state during the session. The first implementation should
not downsample large source cards; if capture fails or the source view has an
invalid size, the session should not start.

The overlay snapshot should copy only the card state already visible on screen.
It should not reveal hidden private content, force authentication, expand
protected Folder previews, or otherwise alter privacy/authenticated projection
semantics. A hidden private card should drag as its protected visible card; an
authenticated visible card should drag as currently rendered.

Folder-drop candidates during the overlay drag session should continue to use
the dragged overlay card's top-left corner: a Folder drop is considered only
when that top-left point is inside an existing eligible Thing or Folder Card.
The overlay should preserve the lift-time finger-to-card offset so this
top-left hit-test matches the existing drag behavior. Folder-drop
candidates should still require a short stable hover before arming, so moving
quickly across a valid Thing or Folder target does not accidentally create or
enter a Folder-drop state.

Pointer location should still drive finger tracking, edge-zone auto-scroll,
and ordinary reorder candidate calculation. Folder-drop hit-testing is the
exception because it is based on where the dragged card enters the target card.

The first overlay-drag reorder feedback should be an insertion line rather
than live card displacement. The insertion line should use the dragged Thing or
Folder Card's own `ThingBackground`, rendering a solid line for pure colours
and a gradient line for gradient backgrounds.

The insertion line should attach to the visible edge of the candidate card
rather than trying to preview the full future staggered-grid layout. Inserting
before a visible candidate draws the line at that card's top edge; inserting
after a visible candidate draws it at that card's bottom edge. The line width
should match the candidate card width, or span the content width for full-span
positions. If the candidate edge is off-screen, the session should temporarily
hide the insertion line and continue dragging/auto-scrolling. Folder-drop hover
feedback should hide the reorder insertion line while armed.

Ordinary reorder candidates should be computed from the pointer's current
relationship to visible cards. A pointer inside the upper half of a card means
insert before that card; a pointer inside the lower half means insert after
that card. A pointer in visible whitespace should use the nearest visible card
edge. The transparent source placeholder and the header/list position `0`
should not be valid reorder targets. Folder-drop hover state should suspend
reorder candidate updates while armed.

Final reorder commits should derive the mutation once at release time from the
stable source id plus the final target id and before/after relationship. The
commit path should resolve current source and target list positions, adjust for
the source removal when the source originally appears before the target, and
then perform one data move plus one list update. Returning to the original or
an equivalent position should enter selection mode instead of committing a
move. The drag session should not carry forward accumulated `from`/`to`
positions from intermediate drag frames.

Reorder candidates may retain the most recent target stable id plus
before/after relationship while that target edge is temporarily off-screen, but
the insertion line should be hidden when no visible target edge can anchor it.
Release-time reorder must resolve the retained target id against the current
mixed list again; if the target can no longer be found or no longer accepts the
source move, the session must not commit a stale reorder.

Folder-drop hover animations should keep the existing visual language while
moving lifecycle ownership into the overlay drag session controller. Creating a
Folder from two Things should keep the target-card shrink plus pending Folder
outline feedback. Dropping a Thing or Folder into an existing Folder should
keep the Folder-card shrink, outline, and content-alpha feedback. Entering,
leaving, canceling, and committing hover state should be centralized so every
target touched during the session can animate back cleanly without stale scale,
outline, alpha, background, or selection tint.

Folder-drop armed state should require a currently visible target. If the
target holder scrolls off-screen, is detached or recycled, or the overlay
top-left point no longer remains inside that target, the session should leave
the Folder-drop hover state and animate the target feedback back. Releasing
over an invisible or stale target must not commit a Folder drop.

Release animations should reuse the active session overlay instead of taking a
new post-release snapshot. Folder-drop commits should animate the session
overlay into the target Thing or Folder Card before/while the list reflects the
merge. Reorder commits should settle the overlay toward the final insertion
edge before the list update reveals the moved source in its final position.
Release-in-place selection should animate the overlay back to the transparent
source placeholder and then reveal the real card in selection mode. Cancellation
should restore or fade the overlay back without committing data or entering
selection mode.

Release and cancellation cleanup must not depend on the original source
ViewHolder still being attached. If the source placeholder is still visible,
the session may animate the overlay back and restore that holder directly. If
the source holder has been detached or recycled, cleanup should resolve the
source by stable business id and use targeted rebind/list refresh to clear any
transparent placeholder state. The session should not force-scroll back to the
source merely to play a recovery animation.

Long-press drag startup should keep the current product rhythm: a confirmed
long press enters Moving mode, then starts the overlay drag session only if the
pointer is still active and the source holder can produce the initial bitmap
snapshot. If the user has already released by the startup check, the interaction
should enter selection mode rather than creating a drag session. If the source
holder is unavailable or cannot be captured, the code should not start a partial
session.

An active overlay drag session should be an exclusive gesture. It should track
only the pointer that started the drag, pause card clicks, nested long-presses,
swipe handling, and drawer gestures for the session, and use one cleanup path
for pointer-up, cancellation, Activity pause, and session invalidation.
ItemTouchHelper should remain the owner of ordinary Thing Card swipe behavior,
but it should no longer own Thing/Folder Card drag behavior or receive swipe
control while an overlay drag session is active.

The Things list ItemTouchHelper should expose only swipe flags after the
overlay drag rewrite. Drag flags should be zero for Thing and Folder Cards so
future code cannot accidentally re-enter the old `startDrag(...)` path.

The overlay drag controller should live outside `ThingsActivity.kt` in a new
focused Kotlin file because the Activity is already too large. The extraction
should keep the drag-session state, overlay rendering, auto-scroll, insertion
line, and release/cancel coordination in the controller while using a narrow
host contract back into `ThingsActivity` for existing business operations,
adapter updates, mode transitions, and Folder-drop helpers.

The extracted controller should communicate with `ThingsActivity` through a
small explicit Host interface instead of directly depending on broad Activity
internals. The Host should expose only the operations required for the drag
session, such as current mixed-list lookup, source/target validation, hover
feedback hooks, final reorder/drop/selection/cancel commits, mode transitions,
and access to the RecyclerView plus overlay parent. The controller should not
become a second Activity-shaped class with copied managers and broad private
state access.

While an overlay drag session is active, `ThingsActivity.dispatchTouchEvent(...)`
should be the top-level event source for drag move, release, and cancellation.
Inactive sessions should not intercept normal event dispatch. Once active, the
controller should consume the tracked pointer's move/up/cancel events before
they reach child item views or ItemTouchHelper, so release can still be observed
when the pointer moves outside the RecyclerView's bounds.

Auto-scroll during the overlay drag session should be owned by the app-owned
drag session controller rather than by ItemTouchHelper's out-of-bounds drag
scrolling. Edge-zone scrolling should have a bounded speed, recompute
pointer-based Folder-drop and reorder candidates after scroll movement, keep
the overlay tied to the current pointer position, and continue the session even
when the original or candidate ViewHolder is detached or recycled.

Starting an overlay drag session should stop any existing RecyclerView fling or
ordinary scroll. While the session is active, the only allowed list scrolling
source should be the drag controller's own edge-zone auto-scroll. Normal finger
scrolling, inertial scrolling, nested scrolling, and semantic external scrolls
should not run concurrently with the drag session. External semantic scrolls or
list refreshes should cancel the session before they change the list, while
controller-owned auto-scroll must not cancel merely because ViewHolders detach
or recycle.

Overlay drag sessions should treat stable business identity as authoritative
and list positions as derived frame-local values. The session should record the
source kind, source Thing or Folder id, original mixed-list position, source
background, and relevant projection/container context at drag start. Candidate
positions and targets should be recomputed from the current list and pointer
location on each frame. Release-time commits should revalidate source and
target identities before mutating data, so a stale adapter position cannot move
or merge the wrong Thing or Folder after long scrolling, rebinding, or list
refresh.

External changes that alter the current mixed-list semantics should cancel the
overlay drag session instead of committing or entering selection mode. Examples
include search/projection/drawer-destination changes, folder-path changes,
mode-level changes not owned by the session, Activity pause, private-content
visibility changes, and adapter refreshes caused by unrelated data mutations.
Pure view-layer churn such as scrolling, ViewHolder recycle/detach, and item
animation completion should not cancel the session.

The overlay drag implementation may keep file-backed debug logging behind a
feature flag that defaults to disabled. Logs should use the generic
`DebugFileLogger` path and record session-level events such as start metadata,
candidate changes, Folder hover enter/armed/leave, auto-scroll transitions,
release outcome, cleanup state, and invalidation cancellation reason. The log
should not emit every move frame by default.

The overlay drag rewrite should replace the old ItemTouchHelper drag path
directly instead of keeping a feature-flagged fallback. Once the overlay session
owns Thing/Folder drag, obsolete ItemTouchHelper drag state and callbacks should
be removed or reduced to swipe-only code rather than maintained as a second
drag implementation.

After reviewing modern Android alternatives, the full-session overlay drag
should remain a custom View-based controller rather than adopting platform
`startDragAndDrop(...)`, Jetpack `DropHelper`, or Compose drag-and-drop APIs.
ItemTouchHelper's documented drag model still moves the existing ViewHolder and
can end early when that ViewHolder leaves the layout, which is the failure mode
being fixed. Platform and Compose drag-and-drop APIs are oriented around
ClipData-style data transfer and drop targets rather than this app's mixed
RecyclerView reorder, Folder-drop, release-in-place selection, custom
auto-scroll, and animation requirements. The implementation should therefore
use ordinary View touch handling plus an overlay bitmap visual, while keeping
ItemTouchHelper for swipe only.

## 2026-06-17 - Private Folder Cards hide counts

Private Thing Folder Cards should keep the stored Folder title visible while
private content is hidden, but they should not show child Thing or child Folder
counts. The protected card should keep the Folder icon beside the title and
show a lock indicator below the title area instead of count text.

## 2026-06-15 - Deleting a folder moves the folder subtree to Deleted

Deleting a Thing Folder moves that folder to the Deleted destination while
preserving the folder subtree and all Thing Folder memberships. The deleted
folder appears as a Folder Card in Deleted, and opening it shows that subtree's
deleted projection.

Folder deletion is modeled as folder state, not as immediately rewriting every
descendant Thing's stored state. Descendants become effectively deleted for
display/navigation while they remain inside a Deleted Thing Folder. Restoring
the deleted folder restores the folder structure and makes descendants visible
again according to their own stored states.

Permanent deletion is the operation that actually destroys folder records. It
must clean up contained deleted Things and child folders through the selected
permanent-delete flow.

## 2026-06-15 - Permanent folder deletion deletes the subtree and contents

Permanently deleting a Deleted Thing Folder permanently deletes the entire
folder subtree and its contained Things, including descendants that are only
effectively deleted because of the deleted ancestor folder.

The permanent-delete action should not restore, ungroup, or reparent those
descendants first. From the user's point of view, permanently deleting a folder
from Deleted means permanently deleting that container and everything inside it.

## 2026-06-15 - List widgets do not render folders in v1

Things-list widgets should not render Thing Folder Cards in the first
implementation. They should keep the current Thing-based behavior and remain
compile-safe.

Folder-aware RemoteViews rendering, folder projection intents, private folder
handling, and deleted-folder handling are deferred to a later widget-specific
slice.

## 2026-06-20 - Widget Folder follow-up polish

Single-Thing AppWidget configuration should reuse the home-list `ThingsAdapter`
Thing binding for selectable Thing rows rather than maintaining a separate
`BaseThingsAdapter` approximation. The configuration layer may still provide
its own data source, click behavior, and Folder-auth state, but media placement,
media backgrounds, crop geometry, private Thing presentation, sticky markers,
and ordinary Thing Card layout should come from the shared home card path.

Things-list AppWidget Grid mode should bind click fill-in intents to each
visible grid slot container. The row RemoteViews object exists only to pack
multiple cards into one collection item; it should not decide which Thing or
Folder opens after a tap.

AppWidget alpha should affect media-backed Thing Cards across RemoteViews.
Foreground thumbnails, side media panels, and media-background cards should use
the corresponding RemoteViews `ImageView` alpha so preview updates and launcher
updates follow the same path, while pure-colour and gradient cards can continue
to render their background bitmap with alpha baked in.

## 2026-06-20 - Single-Thing widget configuration parity

Single-Thing AppWidget configuration card delegates must be bound to the
configuration RecyclerView as their host when they are used through the mixed
Thing/Folder adapter. Home-card media sizing depends on the host RecyclerView
width, so delegate binding must not rely only on `onAttachedToRecyclerView`.

Single-Thing AppWidget preview should remain a `RemoteViews` preview instead of
switching to an in-app `card_thing` rendering path. Rounded preview clipping
should be fixed at the widget root by combining a rounded background outline,
`clipToOutline`, and the API 31+ RemoteViews outline-radius action where
available.

Large Folder previews inside the Single-Thing AppWidget configuration should
keep home-card visuals while exposing configuration-specific taps: tapping a
Thing thumbnail selects that Thing for preview, and tapping a Folder thumbnail
opens that Folder.

## 2026-06-21 - Filtered Folder Cards require matching Things

Structurally Empty Thing Folders remain valid user-owned containers and should
not be auto-deleted, but state/type-filtered list projections should not show a
Folder Card unless that Folder subtree contains at least one Thing matching the
current status and type filter.

This keeps Empty Thing Folders preservable in the data model while preventing a
filtered Things list from showing Folder Cards that have no relevant Things for
the current projection.

## 2026-06-23 - 文件夹操作确认弹窗：统一四段式 + 筛选提醒

文件夹的所有确认弹窗（完成/恢复文件夹中所有记事、删除、解散、永久删除、还原文件夹）
统一为「正文 + 条件提醒」结构：正文给出动作、范围（含所有子文件夹）、影响计数与去向/可
逆性；提醒单独成行，用于说明内容操作受当前类型筛选限制，或结构操作会触及当前筛选下看不到的内容。

- 计数口径分两档：内容态操作（完成/恢复记事）只报「N件记事」；结构态操作（删除/解散/永
  久删除/还原）报「X个子文件夹、Y件记事」，为 0 的段省略。数字与文字之间不留空格。
- 提醒判定按操作 family 分开：
  - 内容态：动作跟随当前类型筛选；当存在具体类型筛选时，统一提醒「本次操作仅作用于当前类型筛选
    （X）的记事，其他类型的记事不受影响」。不再暗示会覆盖全部类型。
  - 结构态：类型 + 状态两维度独立判定，提醒按命中维度拼成「状态和类型筛选
    （已完成、记录/目标）」/「状态筛选（…）」/「类型筛选（…）」，两维度都未超出则不显示。
- 还原 Trashed Folder 此前无确认弹窗，本次补齐 `showRestoreThingFolderDialog`，与其它
  结构态操作一致。
- 后续统一文案模型后，实现迁移到 `HomeActionWordingHelper`：状态内容操作由
  `stateActionWording` 生成正文、子文件夹提醒、类型筛选提醒；结构操作由
  `structuralActionWording` 接收 `hiddenScopeClauseForStructural` 的结果后生成正文。
  旧的 `appendFilterScopeReminder` 路径已删除。

## 2026-06-23 - 批量操作文案：工具栏「全部X」vs 长按文件夹「文件夹中所有记事」

工具栏（actionbar）的批量动作与长按文件夹的 contextual menu 动作，文案语义分开：

- 工具栏（根目录与文件夹内统一）：「全部完成」/「全部恢复」/「全部删除」/「全部永久删除」。
  其中已完成态的恢复从「恢复全部记事」改为「全部恢复」；回收站态从「清空回收站」改为
  「全部永久删除」（根目录与进入文件夹后都一致）。`configureCurrentFolderMenu` 不再在文件夹
  内把工具栏标题覆盖成文件夹措辞。
- 长按文件夹 contextual menu：「完成文件夹中所有记事」/「恢复文件夹中所有记事」，沿用文件夹
  措辞。
- `confirmFinishAllThingsInScope` / `confirmUnfinishAllThingsInScope` 增加 `titleRes` 参数，
  弹窗标题随入口（工具栏 vs contextual）变化；正文随作用域变化（根目录用不含「该文件夹」的
  `*_root_confirm`，文件夹内用 `*_in_folder_confirm`）。

（已解决）工具栏四个批量动作统一为同构：「全部完成」「全部恢复」「全部删除」「全部永久删除」
都有确认弹窗、递归整棵子树、全类型、带类型筛选提醒。`act_delete_all`（已完成→回收站）走新增的
`confirmTrashAllFinishedInScope` → `ThingManager.trashThings`；`act_delete_all_forever`
（回收站→永久）走 `confirmDeleteForeverAllInScope` → `ThingManager.deleteThingsForever`；
二者复用已验证的 `changeFolderSubtreeContentState` 通道（记录 `state_before_delete`、对
`DELETED_FOREVER` 物理删行、内部 `loadThings()`），不再用绑定可见列表的 `handleUpdateStates`。
`getTrashedThingsInScope` 扩展为支持根目录（`folder==null` 时取
`getAllUserThingsByState(DELETED)`）。

## 2026-06-23 - 内容类批量操作跟随当前类型筛选（取代"全类型+警告提醒"）

用户决定：在有类型筛选时，内容类批量操作只作用于该类型；"全部类型"时才全类型。
取代了同日早先"内容操作恒为全类型、用提醒警告更大范围"的设计。

适用操作（5 个）：全部完成 / 完成文件夹中所有记事、全部恢复 / 恢复文件夹中所有记事、
回收站恢复、全部删除、全部永久删除。实现：`getUnderwayThingsInScope` /
`getFinishedThingsInScope` / `getTrashedThingsInScope` 改为用 `getActiveTypeFilterMask()`
（文件夹子树查询传入掩码，根目录用 `matchesActiveTypeFilter` 过滤），不再硬编码
`TYPE_FILTER_ALL`。

提醒文案随之翻转：内容操作在有具体类型筛选时，提醒由"覆盖全部类型…"改为安抚式
`folder_op_scope_only_type`「本次操作仅作用于当前类型筛选（X）的记事，其他类型的记事不受影响」；
"全部类型"时无提醒。

容器类操作（解散 / 删除 / 永久删除 / 还原文件夹）维持全类型、全状态——容器本身没有类型，
"只删某类型"会退化成内容操作。其提醒仍是警告式（`folder_op_reminder_subtree`：含当前
状态/类型筛选下看不到的内容）。至此 warn（容器、更大范围）与 reassure（内容、更小范围）
两类提醒语义清晰分离。

## 2026-06-23 - 纯骨架模型:取消文件夹删除状态,删除/还原文件夹改为内容操作

用户决策(全面转向):文件夹是稳定骨架、不再拥有自己的删除状态。软删除("删除文件夹"=
`updateFolderState(DELETED)`)只是翻了个状态标记、并未改变结构,违背"文件夹=骨架、状态正交"
原则。真正改变结构的是移动、解散、永久删除。

变更:
- "删除文件夹" → "删除文件夹中所有记事"(`confirmTrashFolderContent` → `trashThingsPreservingState`):
  递归把子树里所有进行中+已完成的记事移入回收站(按各自状态记录 `state_before_delete`),
  容器原地不动。**跟随当前类型筛选**(用户指定),内容操作=安抚式提醒。
- "还原文件夹" 入口移除;回收站里文件夹的恢复统一走已存在的"恢复文件夹中所有记事"
  (`confirmRestoreTrashedThingsInScope`,Projection Folder 路径)。`restoreSelectedFolderIfNeeded`/
  `showRestoreThingFolderDialog` 删除;`act_restore_selected` 只处理记事。
- 文案:`delete_thing_folder` 改为"删除文件夹中所有记事";去掉 `restore_thing_folder_confirm`。
- 回收站靠"含已删记事的 Projection Folder"投影显示(`getFolderEntriesForTypeFilterProjection`
  已支持),与软删除视觉等价:删完记事后文件夹从首页消失(无匹配记事)、在回收站出现可恢复。
- 迁移 DBHelper v20 `migrateFoldersToSkeletonModel`:把现有"被删祖先隐藏"的子树记事置为
  DELETED(记录删除前态),再把所有 DELETED 文件夹状态清回 UNDERWAY。DATABASE_VERSION 19→20。
- 低风险策略:`isEffectivelyDeleted`/Trashed Folder 查询层保留但休眠(迁移后无文件夹处于
  DELETED 态)。`deleteFolder`/`restoreFolder` 管理方法变为死代码(仅死的 showThingFolderActions
  仍引用 restoreFolder),待清理。

保留为结构操作:移动、解散、**永久删除文件夹**(`deleteFolderForever`,回收站里仍可对文件夹
触发,销毁整个容器+全部内容)。**待确认**:永久删除文件夹在新模型下是跨投影的(一个文件夹
可能同时有回收站记事和别处的非删除记事),从回收站对它"永久删除整个文件夹"会一并销毁别处内容,
目前靠警告式提醒告知;是否要改为只永久删除该文件夹回收站里的记事,待定。

遗留:死代码 `showThingFolderActions` 整块(含 `addThingFolderAction`/
`showFinishFolderContentDialog`/`showRestoreFolderContentDialog`/`FOLDER_ACTION_*` 常量/
`restore_thing_folder` 等串)已完全无人调用,建议后续整块删除。

## 2026-06-23 - 菜单职责厘清:工具栏=内容、overflow=结构、空范围隐藏

去重并按实际情况设置菜单项:
- **职责分离**:在文件夹内,内容批量操作只由工具栏承担(全部完成/全部恢复/全部删除/
  全部永久删除,作用于当前范围的当前状态);overflow 只放结构操作(设私密/移动/解散/
  永久删除文件夹)。移除了 overflow 里会与工具栏"全部删除"重复的"删除文件夹中所有记事"。
  对某个文件夹的"删除其全部记事(跨状态)"改为从上一层长按该文件夹卡片触发(contextual)。
- **空范围隐藏**:工具栏的全部完成/恢复/删除/永久删除/排序,在当前投影没有可见内容时
  (如空文件夹、空的某状态视图)隐藏。判据 `ThingManager.hasVisibleProjectionContent()`。
- **结构永久删除文件夹**只在回收站视图的 overflow 出现;回收站工具栏的"全部永久删除"=
  永久删除当前范围回收站记事(内容),二者区分明确,与回收站长按菜单的二分一致。

遗留(待确认):回收站视图的工具栏没有"全部恢复"(只能逐个选中或从上层长按文件夹恢复),
是否补一个 toolbar 级"全部恢复"。

## 2026-06-23 - 回收站工具栏补"全部恢复" + 返回键回默认(返回退出不保留筛选)

- 回收站视图工具栏新增"全部恢复"(act_restore_all):递归恢复当前范围回收站记事到删除前状态
  (跟随类型筛选),与已完成视图对称(恢复+永久删除)。`confirmRestoreTrashedThingsInScope`
  加 `titleRes` 参数 + 根目录正文 `restore_all_trashed_root_confirm`;`act_restore_all`
  处理按状态分流(已完成→unfinish,回收站→restore-trashed)。
- 返回键:在根目录且筛选非默认(状态≠正在进行 或 有具体类型筛选)时,按返回先
  `resetRootProjectionToDefault()` 回到正在进行+全部类型+根目录,而不是退出;已是默认才退出
  (双击退出逻辑不变)。
- "按返回键退出后再打开不保留类型筛选"由上一条直接实现:非默认时返回只重置不退出,所以真正
  退出时必然已是正在进行+全部类型+根目录,下次(温启动)打开即为默认。**按 Home 退到后台保留
  筛选**(用户明确要求),走的是后台路径、不触发重置。因此不需要任何后台检测/会话级重置机制
  (此前一版加的 `ActivityLifecycleCallbacks` 已撤销,因为它会在 Home 时也清掉筛选)。

## 2026-06-23 - "（含所有子文件夹）"按实际子树显隐 + 正在进行文件夹内补"删除文件夹中所有记事"

1. 内容操作确认弹窗里的"（含所有子文件夹）"原为各文件夹正文写死、根目录从不带,导致无子文件夹
   却显示、根目录有子文件夹却不显示。改为按受影响记事是否真的落在子文件夹动态判定:把子句
   `scope_includes_subfolders`(（含所有子文件夹中的记事）)挂在计数后面"共%1$d件%2$s",
   `subfolderClause(folder, things)` = `things.any { it.folderId != folder?.id }` 时返回子句、
   否则空。一句子句同时适用根目录与文件夹。涉及 11 条内容正文(改为 %1$d 计数 + %2$s 子句)。
2. 正在进行视图下,文件夹内 overflow 此前没有删除项(已完成视图被工具栏"全部删除"覆盖才隐藏,
   但正在进行视图工具栏无删除项)。现 `act_delete_current_folder` 在正在进行也可见、标题
   "删除文件夹中所有记事"(走 `confirmTrashFolderContent`),并在 menu_things_underway.xml 调到
   overflow 首项;回收站仍为"永久删除文件夹"(结构);已完成不显示(避免与工具栏"全部删除"重复)。

## 2026-06-27 - 搜索态文件夹导航保留搜索筛选

搜索结果中打开文件夹、返回上级文件夹、点击文件夹路径段时，都应维持当前搜索模式，并继续使用搜索框文本和颜色筛选当前文件夹投影。实现上由 `ThingManager` 的文件夹导航方法支持只更新 `ThingListProjection`、暂不立即 `loadThings()`，再由 `ThingsActivity` 根据 `App.isSearching` 选择普通加载或按当前搜索条件重新查询。

搜索态下系统返回键和左上角导航按钮在非根目录优先返回上级文件夹，回到根目录后才退出搜索；导航按钮的 content description 随当前投影在“返回上级文件夹”和“离开搜索界面”之间切换。

## 2026-06-27 - 搜索态长按拖拽与选择模式保持普通列表语义

搜索结果中的记事和文件夹在“正在进行”视图下长按时，应与普通列表一致：先进入拖拽，拖到另一个记事可创建文件夹，拖到文件夹可移动进去，释放在原处才进入选择模式并选中源项。搜索态不再把文件夹长按直接改为选择模式。

拖拽创建/移动文件夹会改变文件夹结构，但返回列表时仍保持当前搜索文本和颜色筛选。实现上，拖拽提交路径先用 `reload=false` 或 `loadThingsNow=false` 修改数据，再由 `ThingsActivity` 用当前搜索条件重建列表；搜索态下拖拽提交后使用全量 rebind，避免局部 notify 与搜索过滤后的列表形状不一致。

搜索模式刚打开、用户还没有输入文本也没有选择颜色时，也必须立即建立一份真实的搜索投影，而不是只切换 UI 状态。否则 `mThingListEntries` 仍显示旧卡片、`mThings` 却为空，会造成记事选择计数为 0、contextual menu 不显示记事动作、拖拽投放找不到源记事。进入搜索模式时应直接按空关键词和全部颜色执行一次搜索，让可见卡片和底层数据集合保持一致。
