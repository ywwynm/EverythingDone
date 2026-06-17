# Current Debug Update Notes

## 2026-06-17 - 统一 Thing 与文件夹混合列表的位置语义

用户继续要求全面检查 `Thing` 的 `position` 和新增 `list_position` 语义，并进一步指出局部变量命名也需要统一，例如什么时候应该叫 `thingPos`、什么时候应该叫 `listPos`、什么时候应该叫 `thingIndex`。这次调试更新延续上一轮文件夹加入后的 position 审计，把命名和实际语义收敛到同一套规则：`thingIndex` 只表示 `ThingManager.getThings()` 里的纯 Thing 下标，`listPosition` 只表示包含 Folder Card 的 mixed RecyclerView adapter position；变更前后或拖拽源/目标位置使用 `oldListPosition`、`newListPosition`、`sourceOldListPosition`、`targetNewListPosition` 等限定名。

本次修改保留 `KEY_POSITION` 的历史协议语义，继续作为纯 Thing 下标传给 `ThingManager.update(...)`、`updateState(...)`、置顶/取消置顶等 manager API；新增和补齐的 `KEY_LIST_POSITION`、`KEY_LIST_PROJECTION` 则用于主界面精确刷新 mixed list。`ThingListProjection.key()` 会把当前内置列表限制和打开的文件夹路径绑定到返回结果上，`ThingsActivity` 只在 projection 一致时信任旧的 list position，否则按 Thing id 重新查找或退回全量刷新，避免从根目录或另一个文件夹返回时通知错误卡片。

文件夹内创建记事也做了进一步保护：`ThingsActivity` 打开创建详情页时会把当前文件夹 id 放进 intent，`DetailActivity` 创建新 Thing 时显式设置 `folderId`。这样即使创建流程期间主列表 projection 发生切换，新记事也会进入用户发起创建时所在的文件夹，而不是被默认创建到根目录。

主列表相关代码已按命名规则清理。`ThingsActivity` 中 Detail result、remote action 回调、undo、swipe 完成、文件夹 drop 提交、drag hover、卡片外观预览、new item 动画等路径统一使用 `thingIndex` 和 `listPosition`；`DetailActivity` 的历史字段 `mPosition` 改为 `mThingIndex`，旁边保留 `mListPosition`；`RemoteActionHelper` 的远程完成/延迟/清单切换参数改为 `thingIndex`，同时广播 pre-mutation `listPosition` 和 projection key；`ThingsAdapter` 的 inline checklist 更新会先把 holder 的 mixed list position 转成纯 Thing index 再调用 `ThingManager.update(...)`，通知刷新仍使用 mixed list position。独立的附件列表、详情清单编辑列表、媒体裁剪 dialog 等局部 adapter 位置没有改名，因为它们不参与 Thing/Folder 主列表投影。

同步更新了 `docs/features/thing-folders/preferences.md` 和 `docs/features/thing-folders/sessions.md`，记录以后主列表相关代码的命名约定。验证状态：`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:assembleDebug`，结果 `BUILD SUCCESSFUL`。请重点复测：详情页返回主界面后是否刷新正确卡片；打开文件夹后创建记事是否仍进入当前文件夹；通知/小组件远程完成或延迟记事后列表是否不再错位；inline checklist 点击是否不会更新错 item；卡片外观封面比例实时预览是否仍正常。

## 2026-06-17 - 修复文件夹加入后的列表位置与卡片外观预览回归

用户继续反馈三个问题：文件夹卡片里的记事数量提示文本虽然布局上和文件夹 icon 左侧对齐，但视觉上稍微偏左，需要增加 2dp 左侧偏移；从记事详情界面更新记事回到主界面后，`notifyItemChanged` 使用的 position 出错，合理推测加入文件夹后相关 position 都需要重新适配；加入文件夹后，调整记事卡片外观里的封面图片比例不能实时预览，图片作为卡片背景时比例滑动条也完全错误，比例标记文本挤在一起。

本次诊断确认，后两个问题同源：Thing Folders 引入了 Folder Card 和 Thing Card 混合列表后，部分路径仍把 `ThingManager` 的纯 `mThings` 下标当作 RecyclerView adapter position 使用。详情页需要纯 Thing 下标调用 `ThingManager.update(...)`，但主界面的 `notifyItemChanged(...)`、`notifyItemRemoved(...)`、`notifyItemMoved(...)` 必须使用混合列表的 adapter position。卡片外观面板也同样错误地把 `getSingleSelectedPosition()` 的纯 Thing 下标保存为 `mThingCardAppearanceSelectedPosition`，导致预览刷新、holder 查找、卡片宽度测量、背景图自然高度测量和比例 range 计算都可能指向错误卡片。

本次修改新增 `Def.Communication.KEY_LIST_POSITION`。`KEY_POSITION` 继续保留原有语义，表示纯 Thing 下标；`KEY_LIST_POSITION` 专门表示打开详情时的 mixed-list adapter position。`DetailActivity.getOpenIntentForUpdate(...)` 接收并回传这个 list position，`ThingsActivity` 返回处理则按 Thing id 重新解析当前可见 list position，找不到时回退为全列表刷新，避免通知错误 item。受影响路径包括详情页同类型更新、跨类型更新、状态变化、置顶/取消置顶、doing/cancel 刷新，以及选择态 toolbar 的置顶/取消置顶操作。

卡片外观面板现在用 `ThingManager.getListPositionForThingId(...)` 记录选中记事的 adapter position，不再使用纯 Thing 下标。这样“调整封面图片比例”的实时预览、封面比例范围、图片作为卡片背景时的高度/比例计算都会针对真实选中的卡片，而不是被前面的文件夹卡片错位影响。

文件夹卡片计数文本也按用户反馈微调：`ThingsAdapter.kt` 中 dedicated count TextView 的左侧 padding 从文件夹 icon 的 16dp 布局起点增加到 18dp，让 `X件记事` 的字形墨迹视觉上更接近 icon 左边缘。

同步更新了 `docs/features/thing-folders/preferences.md`、`docs/features/thing-folders/sessions.md` 和 `docs/features/thing-card-appearance/sessions.md`。验证状态：已执行 `.\gradlew.bat :app:assembleDebug`，结果 `BUILD SUCCESSFUL in 4s`；只有 `ThingsActivity.kt` 里既有的 deprecated override warning。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。请重点复测：文件夹计数视觉对齐、详情页返回后的单项刷新是否命中正确卡片、状态/置顶变化是否不再刷新错位、卡片外观封面比例拖动是否实时预览，以及图片背景模式下比例刻度是否不再挤在一起。

## 2026-06-17 - 修复文件夹计数对齐与创建/重命名文件夹对话框

用户反馈两个问题：文件夹卡片里“有多少件记事”的提示文本，左侧在视觉上没有和上方文件夹 icon 的左侧对齐；创建新文件夹时打开的是平台默认 `AlertDialog`，应改用 app 自己的 `DialogFragment` 风格，并且标题、取消/确定按钮、`EditText` 都要有正确边距和外观。用户进一步补充：创建后的命名对话框里点取消应取消创建文件夹本身，让记事回到原位置和状态；文件夹命名 `EditText` 也要像设置提醒时刻的 DateTime dialog 那样适配文件夹颜色，包含底部横线、选中文字背景和选中/聚焦文字颜色，纯色和渐变色都要适配。

本次修改集中在 Thing Folders。`ThingsAdapter.kt` 不再把文件夹计数复用到普通记事正文 `tvContent` 槽位，而是新增独立的动态 count TextView，插在文件夹 header 下方，使用和文件夹 icon 一致的 16dp 左侧 inset，继续保持原有小字号和次级文本颜色。这样计数文本不再继承普通记事正文区域的视觉偏移。

新增 `ThingFolderNameDialogFragment.kt` 和 `fragment_thing_folder_name.xml`，创建文件夹后的命名和普通重命名文件夹都改为使用 app 的 `BaseDialogFragment` 样式。标题、确定按钮和输入框聚焦态会使用文件夹完整 `ThingBackground` 适配：纯色文件夹使用纯色文字和下划线；渐变文件夹使用 `BackgroundUtil.applyTextBackground(...)` 和 `BackgroundUtil.applyEditTextUnderline(...)` 绘制渐变文字与渐变下划线，并隐藏原生下划线；选中文字背景使用 `DisplayUtil.getLightColor(...)` 得到的文件夹浅色。

创建文件夹后的取消语义也已修正。`ThingsActivity.kt` 把“重命名文件夹”和“创建后命名文件夹”拆成两个入口：普通重命名取消只关闭对话框；创建后命名取消会调用 `ThingManager.cancelCreatedFolder(...)` 回滚刚创建的文件夹。回滚逻辑通过数据库读取当初拖拽的两个记事，将仍位于新文件夹内的记事移回该文件夹的原父级，然后只删除新建的 `thing_folders` 记录。这里没有复用 `deleteForever(...)`，因为那个永久删除路径会删除文件夹内的记事。回滚还加了保守 guard：只有新文件夹当前仍然只包含这次要恢复的记事时，才删除文件夹记录。

同步更新了 `docs/features/thing-folders/preferences.md`、`decisions.md` 和 `sessions.md`，记录文件夹命名对话框样式、颜色适配、取消创建语义，以及文件夹计数对齐规则。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug`，结果 `BUILD SUCCESSFUL in 3s`；只有 `ThingsActivity.kt` 中既有的 deprecated override warning。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。本次准备通过 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"` 发布到阿里云 debug channel。请重点复测：文件夹卡片计数是否和 icon 左侧对齐；创建文件夹命名对话框是否使用 app 自定义样式；纯色和渐变文件夹的标题、确定按钮、输入框文字/下划线/选中背景是否正确；创建后点取消是否回到原来的两个记事卡片状态；普通重命名取消是否不会删除或回滚已有文件夹。

## 2026-06-16 - 修复左右滑动/拖拽卡片时的 Z 轴层级跳变

用户反馈：左右滑动记事 A 时，如果左侧同时有记事 B 和文件夹 C，A 一开始看起来在 B/C 上方，但滑动过程中可能突然跑到 C 下方；如果左侧只有文件夹 C，也可能一开始在 C 下方，之后又突然变到 C 上方。用户期望正在左右滑动或拖拽的卡片始终保持在所有卡片上方。

本次诊断确认，相关路径里存在多个会影响层级的来源：AndroidX `ItemTouchHelper` 会在 active draw 路径中按当时的 sibling elevation 抬高被操作的 item；同时普通卡片按压/释放动画、moving mode、Folder Card surface、RecyclerView item animator 和 Folder-drop feedback 都可能改变卡片的实际 z。单纯依赖 `ItemTouchHelper` 初始抬高，后续 sibling z 变化后就可能出现当前卡片被文件夹卡片盖住或突然改变层级的情况。

本次修正集中在 `ThingsActivity.kt` 和 `ThingsAdapter.kt`。`ThingsTouchCallback.onChildDraw(...)` 在调用 `super.onChildDraw(...)` 之后，会对 active swipe/drag 卡片重新计算当前所有 sibling 的最大 `z`，并通过临时 `translationZ` 把 active 卡片压到所有 sibling 上方。这里没有改 `cardElevation` 的所有权，避免和卡片按压、选择、moving mode、Folder-drop 反馈动画继续争抢同一个属性。手势结束时在 `ItemTouchHelper.clearView(...)` 清掉临时 `translationZ`；ViewHolder 复用绑定时也会重置 `itemView.translationZ = 0f`，避免复用残留。

同步更新 `docs/features/thing-folders/decisions.md`，记录 active list gesture 的临时 z-order 由手势层通过 `translationZ` 管理；同步更新 `docs/features/thing-folders/sessions.md` 记录本轮实现。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL in 3s`；只有 `ThingsActivity.kt` 里既有的 deprecated override warning。已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606161548` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，`debugUpdateCode` 为 `202606161548`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606161548.apk`。当前环境仍没有稳定的真机滑动/拖拽视觉自动化回归 seam。请重点复测：A 左右滑动经过左侧 B/C 时是否始终位于 B/C 上方；A 拖拽经过现有文件夹时是否始终位于所有卡片上方；手势结束后卡片阴影和层级是否恢复正常，没有残留在最上层。

## 2026-06-16 - 加固文件夹拖拽动画，避开 RecyclerView 动画冲突

用户反馈：拖动记事 A 的卡片在记事列表中滑动、触发列表自动滚动或 item 重排列时，A 拖到记事 B 上创建文件夹、或拖到现有文件夹 C 上合并的动画仍可能出现卡片闪烁、位置计算错误、RecyclerView item 动画冲突、文件夹轮廓出现/消失异常、轮廓位置不正确、缩放恢复不连续等问题。用户还特别提醒，项目以前多次尝试 RecyclerView Adapter stable ids 都出现过问题，因此本次不要把修复建立在启用 stable ids 上。

本次采用“局部隔离现有实现”的第一版加固，而不是一次性重写完整 drag-session layer。`ThingsActivity.kt` 中的 Folder-drop 拖拽状态仍然用稳定业务 id 识别源记事和目标对象：`sourceThingId`、`targetThingId`、`targetFolderId`。但是不启用 Adapter stable ids，也不把 adapter position 当成“同一个目标”的核心判定。这样 RecyclerView 在拖拽 gap-filling 或自动滚动时，目标卡片的位置可以变化，Folder-drop 状态仍按业务对象跟踪。

动画边界方面，本次在 armed Folder-drop 前和提交 Folder-drop 前显式结束 RecyclerView 当前 item animator，避免普通 `notifyItemMoved(...)`、remove/change 动画和 Folder-drop 的目标卡片缩放、文件夹轮廓、缩略图文件夹描边同时写同一个 ViewHolder。悬停阶段如果 RecyclerView 正在 computing layout 或 item animator 仍在运行，会推迟/清理 Folder-drop feedback，等列表补位稳定后再重新判断。成功提交时，会先捕获拖动源卡片 overlay，再立刻清掉真实目标卡片上的缩放/轮廓 feedback，让提交 overlay 单独负责最后的视觉收束；普通拖出目标的场景仍保留缩放/轮廓恢复动画，不会把所有位置变化都变成无动画。

位置计算方面，Folder-drop hit-testing 现在使用包含 `translationX/translationY` 的源/目标边界，并跳过 `RecyclerView.NO_POSITION` 的 holder，避免在 item 正在被动画移动或已脱离稳定 adapter position 时使用旧的 layout-only 坐标。

同步更新：`docs/features/thing-folders/decisions.md` 记录本轮明确不启用 Adapter stable ids；`docs/features/thing-folders/followups.md` 把后续 fallback hardening 改成使用稳定业务 id，而不是 stable adapter ids；`docs/features/thing-folders/sessions.md` 记录了本轮实现。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL in 4s`；只有 `ThingsActivity.kt` 里既有的 deprecated override warning。已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606161401` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，`debugUpdateCode` 为 `202606161401`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606161401.apk`。当前环境仍没有稳定的真机拖拽视觉自动化回归 seam。请重点复测：长距离拖动 A 导致列表自动滚动后，再拖到 B/C 上是否还会闪烁或轮廓错位；A 拖入、拖出、重新拖入同一目标时，目标卡片缩放和轮廓是否连续；松手创建/合并文件夹时，真实卡片是否不再和提交 overlay 抢动画。

## 2026-06-16 - 修复创建文件夹轮廓在列表滚动时脱离目标卡片

用户反馈：文件夹轮廓需要始终紧贴着目标记事卡片；当拖动轨迹比较长、涉及到屏幕滑动时，触发创建文件夹动画后，轮廓可能会随着屏幕滑动而和对应记事卡片分离。

本次诊断确认，上一版把轮廓改成固定中心后，实际上是把轮廓固定在创建那一刻的 RecyclerView 坐标快照上。这个模型可以避免按当前 `scaleX/scaleY` 重算导致的右下偏移，但在 RecyclerView 自动滚动时，目标卡片 B 的 layout 位置会变化，轮廓却仍留在旧坐标，于是发生分离。

本次修正为“相对 B 的未缩放 layout 中心固定，而不是相对 RecyclerView 某个坐标快照固定”：`FolderDropOutlineDecoration` 现在保留目标卡片引用，并在每次 `onDraw(...)` 时重新计算目标卡片当前在 RecyclerView 内、不受 scale transform 影响的 layout bounds。这样列表滚动或 item translation 发生时，轮廓会跟着 B 的当前位置移动；但轮廓的几何仍然不使用 B 当前的 `scaleX/scaleY`，进场和退场仍只通过 `FolderDropOutlineDrawable.progress` 改变描边粗细和透明度。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL in 3s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已发布 debug update `202606161316`，远端 `latest.json` 已回读确认，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606161316.apk`。当前环境仍没有稳定的真机拖拽视觉自动化回归 seam。请重点复测：长距离拖动触发列表滚动后，A 拖到 B 时文件夹轮廓是否仍紧贴 B；A 拖出 B 时轮廓是否保持相对 B 的位置退场；A 经过轮廓区域时，轮廓仍应被 A 的卡片遮住。

## 2026-06-16 - 修正创建文件夹轮廓 bounds 固定中心

用户反馈：上一版为了让文件夹轮廓退场可见，把轮廓 bounds 按目标卡片当前 `scaleX/scaleY` 每帧重算，结果轮廓会往记事卡片右下角偏离。用户指出这里不应该根据 B 当前的 scale 重新计算，因为轮廓和记事卡片应该始终共享同一个中心，动画只需要让轮廓粗细从 0 变成设定粗细，或者从设定粗细变成 0。

本次修正采用固定中心模型：创建待创建文件夹轮廓时，只计算一次目标卡片在 RecyclerView 内、不受 scale transform 影响的 layout bounds；轮廓 bounds 以这个 layout bounds 为基准，和 B 卡片共享同一个中心。为了保留轮廓与缩小后的 B 卡片之间的固定视觉间隙，代码只在创建固定 bounds 时使用一次目标缩小比例；之后进场和退场都不再根据 B 的当前 `scaleX/scaleY` 重新计算位置。`FolderDropOutlineDrawable.progress` 继续负责动画，控制描边粗细和透明度从 0 到目标值、或从目标值回到 0。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL in 3s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已发布 debug update `202606161251`，远端 `latest.json` 已回读确认，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606161251.apk`。当前环境仍没有稳定的真机拖拽视觉自动化回归 seam。请重点复测：A 拖到 B 时轮廓是否仍与 B 共享中心、不再向右下偏移；A 拖出 B 时轮廓退场是否只表现为描边变细/变淡；A 经过轮廓区域时，轮廓仍应被 A 的卡片遮住。

## 2026-06-16 - 修复创建文件夹轮廓退场被目标卡片遮住

用户反馈：记事 A 拖动到记事 B 时，文件夹轮廓的出现动画已经有了，但完全看不到轮廓消失动画。用户指出这应该和 B 卡片从缩小恢复到正常大小的 `scaleX/scaleY` 动画类似，可以使用同一套逻辑。

本次重新诊断后确认，问题不只是“退场动画有没有启动”，而是轮廓的绘制层级和目标卡片恢复动画产生了遮挡。上一版为了让拖动中的 A 能遮住轮廓，把待创建文件夹轮廓从 `RecyclerView.overlay` 移到 `RecyclerView.ItemDecoration.onDraw(...)`，也就是绘制在所有 child card 下方。这样 A 能遮住轮廓，但 B 从 `scale=0.92` 恢复到 `scale=1f` 时，也会把仍停在旧 bounds 上的轮廓盖住，所以用户看起来就像轮廓立刻消失。

本次把轮廓退场改成更接近卡片 scale 恢复的模型：轮廓仍然用 `progress` 从当前值动画到 `0f`，但 `FolderDropOutlineDecoration` 不再使用固定 bounds，而是在每次 `onDraw(...)` 时根据目标卡片当前的 `scaleX/scaleY` 重新计算轮廓位置，让轮廓始终贴在 B 当前视觉边界外侧。这样 B 放大的同时，轮廓也会跟着外移并淡出/收细，不会被 B 的卡片表面提前盖住。清理当前轮廓时也会先把 decoration 从 active highlight 槽位脱钩，再让这一次退场动画自己播完并一次性移除，避免后续重复清理不断重启动画。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL in 3s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已发布 debug update `202606161240`，远端 `latest.json` 已回读确认，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606161240.apk`。当前环境仍没有稳定的真机拖拽视觉自动化回归 seam。请重点复测：A 拖到 B、轮廓出现后再拖出 B 时，B 放大恢复的同时轮廓是否能同步退场；A 经过轮廓区域时，轮廓仍应被 A 的卡片遮住。

## 2026-06-16 - 修复创建文件夹轮廓层级与退场动画

用户反馈两个小问题：记事 A 拖动到记事 B 时，B 卡片缩小/放大有动画，但待创建文件夹轮廓的出现/消失没有动画；同时 A 的卡片会挡住 B，但文件夹轮廓不会被 A 挡住。

本次诊断确认原因是待创建文件夹轮廓画在 `RecyclerView.overlay` 上。overlay 位于 RecyclerView children 之上，所以拖动中的 A 无法遮住轮廓；并且旧清理路径直接从 overlay 移除 drawable，退场自然没有动画。本次把创建文件夹轮廓从 `RecyclerView.overlay` 改为一个不占 offset 的 `RecyclerView.ItemDecoration`，并在 `onDraw(...)` 中绘制。`onDraw(...)` 在 child card 绘制之前执行，因此轮廓会位于所有卡片下方，拖动中的 A 会自然遮住它。

动画方面，保留原有 `FolderDropOutlineDrawable.progress` 进场动画；退场时不再直接移除，而是把 decoration 的 progress 从当前值动画到 `0f`，动画结束后再 remove item decoration。退场移除也加了 token guard，避免旧退场动画被新轮廓打断后误删新的 decoration。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL in 2s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已发布 debug update `202606161220`，远端 `latest.json` 已回读确认，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606161220.apk`。当前环境仍没有稳定的真机拖拽视觉自动化回归 seam。请重点复测：A 拖到 B 时轮廓进场/退场是否都有动画；A 经过轮廓区域时，轮廓是否会被 A 的卡片遮住。

## 2026-06-16 - 文件夹拖拽恢复动画改为可中断重定向

用户反馈：上一版在任何情况下都会有明显跳变，因为 cancel 兜底里直接设置了 `scaleX/scaleY = 1f`。这说明“cancel 就同步最终值”的策略不对；很多 cancel 实际上是因为新的高亮/恢复动画接管了同一个目标，此时旧动画不能再写旧终点。

本次继续查阅资料后，把恢复逻辑改成可中断 retarget 方案，而不是无条件 cancel 兜底。核心是给每个目标卡片的 scale 动画加 generation token：新动画开始前先递增 token，再 cancel 旧动画。旧动画的 `onAnimationCancel(...)` 发现自己的 token 已过期，就不会同步旧目标值，因此不会把卡片先跳到 `scale=1f`；新动画会从当前视觉 scale 继续动画到新的目标 scale。只有当前最新动画自己正常结束或未被新动画接管而取消时，才同步最终目标值。

缩略图模式文件夹描边也做了同样处理：每个 content view 有独立 token，并记录当前描边宽度。新的描边动画从当前记录的视觉描边宽度开始，而不是每次固定从普通宽度或高亮宽度开始，因此快速进出多个文件夹时，描边也会连续过渡，不会先跳到旧终点再启动新动画。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL in 3s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606161209` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，`debugUpdateCode` 为 `202606161209`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606161209.apk`。当前环境仍没有稳定的真机多目标拖拽视觉自动化回归 seam。请重点复测：快速穿过多个文件夹目标时，缩放和描边是否从当前状态连续过渡，而不是出现跳到正常态或高亮态再继续动画。

## 2026-06-16 - 给文件夹拖拽恢复动画增加结束/取消兜底

用户提出新的修复方案：文件夹目标恢复动画仍然要播放，但可以给恢复动画加 `withEndAction(...)` 回调，在正常播放结束时同步最终状态；如果动画被 cancel，则在 `onAnimationCancel(...)` 中同步赋值兜底，保证视觉状态正确。

本次核对 Android 官方文档后确认方案可行，但需要注意 `ViewPropertyAnimator.withEndAction(...)` 只会在动画正常结束时运行，动画 cancel 时不会运行。因此本次修改采用双路径兜底：目标卡片 scale 恢复动画正常结束时通过 `withEndAction(...)` 同步 `scaleX/scaleY=1f`；如果恢复动画被后续高亮动画取消，则通过 `AnimatorListenerAdapter.onAnimationCancel(...)` 同步到相同最终状态。`onAnimationEnd(...)` 里也会同步一次，并清理 listener，避免旧恢复 listener 影响后续新的高亮动画。

缩略图模式文件夹的描边恢复也做了同样处理：每个 content view 仍然使用独立 `ValueAnimator`，并在 `onAnimationEnd(...)` 和 `onAnimationCancel(...)` 中都同步最终描边宽度。新的高亮 scale 动画开始前会显式清理旧 listener 和 end action，避免已取消的恢复动画残留回调影响新的文件夹 drop 动画。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL in 2s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606161159` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，`debugUpdateCode` 为 `202606161159`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606161159.apk`。当前环境仍没有稳定的真机多目标拖拽视觉自动化回归 seam。请重点复测：多文件夹拖拽路径下，恢复动画是否仍然流畅；如果快速进入/离开不同文件夹目标，最终是否不会残留缩小或加粗状态。

## 2026-06-16 - 多文件夹拖拽目标恢复改回动画

用户指出：上一版为了保证多文件夹路径下所有目标都恢复，直接设置了 `scaleX/scaleY` 和描边宽度，这不符合期望；恢复仍然应该是动画。

本次修改保留“记录本轮拖拽中所有被文件夹 drop 高亮过的目标”的稳定性修复，但把恢复方式改回动画。清理 pending 文件夹 drop 时，每个曾经缩小过的目标卡片都会用现有文件夹目标动画时长动画回到 `scale=1f`，不再直接设置 `scaleX/scaleY`。缩略图模式文件夹的描边恢复也不再直接重置为普通宽度，而是从高亮描边宽度动画回普通描边宽度。

为了让多个缩略图模式文件夹能同时恢复，`ThingsActivity.kt` 里把原本单一的 `highlightedThumbnailFolderTargetAnimator` 改成按 content view 记录的 animator map。这样拖动轨迹经过多个缩略图文件夹时，每个文件夹的描边恢复动画彼此独立，不会被后一个目标的恢复动画取消。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL in 3s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606161015` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，`debugUpdateCode` 为 `202606161015`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606161015.apk`。当前环境仍没有稳定的真机多目标拖拽视觉自动化回归 seam。请重点复测：多文件夹拖拽路径下，所有曾经缩小/加粗的文件夹是否会以动画恢复，而不是直接跳回正常状态。

## 2026-06-16 - 修复多文件夹拖拽路径下文件夹卡片缩小残留

用户反馈：当前版本相对稳定，但当拖动记事 A 的轨迹经过多个文件夹，并且触发过文件夹合并动画以及 RecyclerView 自动补位时，仍然有可能出现某个文件夹最后一直保持缩小状态，即使此时 A 的左上角并不在该文件夹卡片内部。

本次修改集中在 `ThingsActivity.kt`。上一版虽然已经把高亮恢复从“按 adapter position 找回目标”改成“记录实际 `CardView`”，但状态仍然只保存最后一个目标。多文件夹拖拽路径里，多个文件夹可能先后被缩小/加粗；RecyclerView 补位和 ViewHolder 位置变化会让最后一个目标引用不能覆盖此前所有已经触发过动画的目标。本次把文件夹 drop 高亮状态改为“本轮拖拽触碰过的目标集合”：每个被缩小的目标卡片、每个缩略图模式文件夹的描边 content 都会被记录。

清理 pending 文件夹 drop 时，现在会取消所有已记录目标卡片上的 ViewPropertyAnimator，并直接把它们的 `scaleX/scaleY` 设回 `1f`，不再依赖恢复动画一定能播放完成。缩略图模式文件夹的描边也会立即重置为普通宽度。并且，同一目标的早退条件进一步收紧：只有当前记录集合里确实只有这一张目标卡片时，才允许继续保持高亮；如果集合里还残留其它目标，会先统一恢复，再重新高亮当前目标。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL in 3s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606161009` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，`debugUpdateCode` 为 `202606161009`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606161009.apk`。当前环境仍没有稳定的真机多目标拖拽自动化回归 seam。请重点复测：A 的拖动轨迹连续经过多个文件夹、触发文件夹合并反馈和 RecyclerView 自动补位后，所有曾经缩小的文件夹是否都能恢复正常大小。

## 2026-06-16 - 修复文件夹拖拽后缩放残留和排序持久化不一致

用户继续反馈三点：文件夹标题 top margin 改为 1dp；记事 A 拖动到另一个记事 B 或文件夹 C 上并继续拖动、触发 RecyclerView 自动补位后，B/C 仍可能缩小后不恢复；拖动轨迹里包含文件夹或触发过创建/合并文件夹动画后，停止拖动时首页看到的记事顺序可能和数据库中的顺序不一致，表现为退出 APP 再打开后列表顺序变化。

本次诊断确认一个关键排序问题：上一版为了降低文件夹 drop 灵敏度，在 `onMove(...)` 遇到文件夹候选目标时会消费该帧，但这个候选帧并没有真的执行 `mThingManager.move(...)` / `notifyItemMoved(...)`。旧代码却仍然在这个分支写入 `finalFrom/finalTo`，导致 `clearView(...)` 后续调用 `updateLocations(finalFrom, finalTo)` 时，可能把一个“视觉上经过文件夹候选目标、但 manager 列表并没有真实移动到那里的范围”写进数据库，于是内存顺序和重启后的数据库顺序不一致。本次修复后，只有真实普通拖动换位分支才会更新 `finalFrom/finalTo`；文件夹 hover candidate 只负责消费该帧和文件夹 drop 状态，不参与排序持久化。

缩放残留方面，本次继续加固高亮恢复：更新文件夹 drop 高亮时，不再只比较 adapter position 和 action，还会比较实际解析到的目标 `CardView`。如果 RecyclerView 补位过程中同一个 position 已经对应另一张卡片，旧的被缩小卡片会先恢复，再决定是否高亮新目标。`onMove(...)` 遇到文件夹候选时，如果 RecyclerView 正在 computing layout、item animator 正在运行，或者候选目标已经和当前 armed pending drop 不一致，也会立即清除旧 pending 高亮，避免等待下一帧 `onChildDraw(...)` 才恢复。文件夹标题 top margin 已从 2dp 改为 1dp。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL in 2s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606160958` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，`debugUpdateCode` 为 `202606160958`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606160958.apk`。当前环境仍没有稳定的真机拖拽自动化回归 seam。请重点复测：B/C 缩小后继续拖动是否能恢复；拖动路径经过文件夹候选但最后只是普通调序或回原位时，结束后的列表顺序是否和重启 APP 后一致；文件夹标题 1dp 偏移是否合适。

## 2026-06-16 - 降低文件夹拖拽判定灵敏度并修复目标卡片缩放残留

用户继续反馈四点：文件夹标题还需要再往下 2dp；当 A、B 分别在列表第一/第二位时，拖动 B 到 A 上触发创建文件夹动画后继续往左拖出，A 卡片没有恢复正常大小；拖动 A 到 B 上时很容易先触发 RecyclerView 的 item 移动，B 移到第一位后，待创建文件夹轮廓还留在 B 原本的位置；因此希望文件夹创建/合并判断不要过于灵敏，最好等 RecyclerView 补位稳定后再判断。

本次修改集中在 `ThingsActivity.kt` 和 `ThingsAdapter.kt`。文件夹卡片标题的 top margin 改为 `2dp`。拖拽状态机现在把“几何上命中某个文件夹候选目标”和“已经可以显示动画/松手提交的 pending drop”分开：拖动卡片左上角进入目标卡片后，会先记录 hover candidate；只有同一个源记事、同一个目标记事/文件夹、同一个目标 adapter position、同一个动作稳定超过短延迟后，才 armed 成真正的 pending drop，并显示缩放/轮廓反馈。若 RecyclerView 正在 computing layout 或 item animator 正在运行，则会继续延后 armed，避免文件夹判断和拖拽补位动画抢状态。

同时修复高亮恢复路径：以前取消高亮时按 adapter position 重新查找目标卡片，拖拽补位后这个 position 可能已经指向别的 item，导致真正被缩小的卡片没有恢复。本次改为记录实际被高亮的 `CardView`，拖出候选区域时直接恢复这张 View 的 scale；缩略图模式文件夹的描边恢复也同样记录实际 content view。`onMove(...)` 不再直接创建 pending drop，只在遇到潜在文件夹目标时暂时消费该帧，真正的文件夹反馈由 active drag frame 的稳定候选确认负责。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL in 3s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606160944` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，`debugUpdateCode` 为 `202606160944`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606160944.apk`。当前环境仍没有稳定的真机拖拽自动化回归 seam。请重点复测：B 拖到 A 后再拖出时 A 是否恢复正常大小；A 拖到 B 时是否不再出现 B 已补位但轮廓留在旧位置；文件夹 drop 动画是否不会明显迟钝；文件夹标题下移 2dp 后是否合适。

## 2026-06-16 - 细化文件夹拖拽动效、回原位选择状态和数量字号

用户继续反馈五点：缩略图模式文件夹在拖入候选时，轮廓变粗的同时卡片也应缩小；A 拖到 B 创建文件夹时，文件夹轮廓与 B 卡片的横向/纵向间隔看起来不一致，像是受 B 宽高比影响；A 在第一个位置、第二个位置是摘要模式文件夹 C 时，快速拖到 C 右下方且不松手，有时 C 会闪到 A 的位置，随后把 A 拖回原位再松手又没有进入选择模式；文件夹名称上方 margin 现在略小；文件夹卡片上的记事数量字号应与普通图片/视频/音频数量提示一致，并希望确认这些数量提示在不同场景下的字号规则。

本次修改集中在 `ThingsActivity.kt` 和 `ThingsAdapter.kt`。拖到现有文件夹时，现在摘要模式和缩略图模式都会播放文件夹卡片缩小动画；缩略图模式额外把描边从普通宽度动画到更粗的描边。A 拖到 B 创建文件夹时，待创建文件夹轮廓不再使用 B 的原始卡片边界自然形成间隔，而是根据缩小后的 B 卡片外扩一个固定视觉 gap 来计算 `RecyclerView.overlay` drawable bounds，因此横向和纵向间隔不再随 B 的宽高比变化。

拖拽状态机也做了收紧：当本次拖拽已经进入过文件夹候选状态，随后拖出候选目标时，会先清除候选并消费这一帧，不再立即落入普通 `notifyItemMoved(...)` 换位分支，避免 C 闪到 A 的位置；松手时会根据拖拽源 Thing 的最终列表位置是否回到起始位置来判断是否进入选择模式，因此“拖出去又拖回原位再松手”会回到选择模式。文件夹卡片排版方面，标题取消上一版的负 top margin，仅保留 `includeFontPadding=false`；记事数量显式设为 `11sp`、单行显示，与普通附件数量提示的默认字号一致。

数量提示字号审计结果：普通 inline 图片/视频数量和音频数量在正常状态都是 `11sp`；当卡片几乎只剩附件数量提示时，代码会把 inline 图片/视频数量和音频数量放大到 `18sp`。图片/视频直接显示在卡片图片区域或作为媒体背景时，数量提示走单独 overlay 文本，但默认也是 `11sp`。也就是说，文件夹数量现在对齐的是普通小号数量提示，而不会跟随“只有附件计数”的放大状态。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL in 2s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606160903` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，`debugUpdateCode` 为 `202606160903`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606160903.apk`。当前环境仍没有稳定的真机拖拽自动化回归 seam。请重点复测：A 拖到缩略图模式文件夹时是否同时缩小和加粗轮廓；A 拖到 B 时轮廓 gap 的横纵视觉距离是否一致；A 拖到 C 右下区域再拖回原位松手是否进入选择模式且 C 不闪到 A 的位置；文件夹标题上方距离和数量字号是否符合预期。

## 2026-06-16 - 修复文件夹拖拽高亮规则与拖出目标时闪退

用户继续反馈三点：第一，拖动记事 A 到记事 B 时才需要“B 卡片缩小 + 文件夹轮廓出现 + 轮廓与 B 卡片有间隙”的创建文件夹动画；拖动记事 A 到现有文件夹 C 时不应使用这套动画，而是摘要模式文件夹卡片缩小、缩略图模式文件夹轮廓变粗。第二，文件夹 icon 视觉上比文件夹名称第一行略高，数量文本应和 icon 左对齐，而不是和名称列左对齐。第三，A 拖到 B 或 C 触发文件夹相关动画后，不松手继续拖出目标卡片，会在 OnePlus Android 16 上闪退，堆栈为 `ContentFrameLayout contains null child at index ... when traversal in dispatchGetDisplayList`。

本次诊断认为闪退最可能来自上一版拖拽高亮实现：为了制造“轮廓和卡片之间的间隙”，代码在 active drag 过程中频繁向 activity 根 `ContentFrameLayout` 添加/移除一个高亮 `View`。当用户拖入目标后又拖出目标时，这个 root child 的移除可能和系统渲染遍历同帧交错，触发 `ContentFrameLayout contains null child`。本次修改不再把拖拽高亮作为根布局子 View 添加/移除：拖动 A 到 B 创建文件夹时，外圈轮廓改为 `RecyclerView.overlay` 上的 `Drawable`，仍使用 B 的原始卡片边界绘制，因此 B 缩小时会保留轮廓间隙；拖动 A 到现有文件夹时完全不走这个创建轮廓路径。

现有文件夹的反馈规则已拆开：如果目标文件夹是摘要模式，只播放目标文件夹卡片缩小动画；如果目标文件夹是缩略图模式，卡片不缩小，只把内部描边从普通宽度动画到更粗的描边，拖出目标后再动画恢复。文件夹卡片布局同步调整：数量 `TextView` 改回和文件夹 icon 左边距一致；名称 `TextView` 关闭额外 font padding，并给一点负的 top margin，让 icon 和第一行标题的视觉对齐更自然。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL in 2s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606160815` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，`debugUpdateCode` 为 `202606160815`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606160815.apk`。当前环境仍没有稳定的真机拖拽自动化回归 seam；请重点复测：A 拖到 B 后轮廓是否仍有间隙；A 拖到摘要模式文件夹时是否只缩小；A 拖到缩略图模式文件夹时是否只加粗轮廓；拖入目标后再拖出目标是否不再闪退；文件夹 icon、标题第一行、数量左对齐是否更符合预期。

## 2026-06-16 - 调整文件夹卡片布局、拖拽轮廓间隙和私密文件夹范围

用户反馈三点：拖动记事 A 到记事 B 时，B 周围的待创建文件夹轮廓需要和 B 卡片本身之间留出明显间隙；文件夹卡片的文件夹 icon 太大且居中，应该缩小后放到左上角，文件夹名称放在右侧最多两行，数量显示为 `X件记事`；设置私密文件夹前必须先检查是否已设置应用密码，进入上一层私密文件夹并通过认证后，内部记事和子文件夹应正常显示真实卡片和真实名称。

本次修改集中在 `ThingsActivity.kt`、`ThingsAdapter.kt`、`ThingManager.kt`、`ActivityHeader.kt` 和 `DetailActivity.kt`。拖拽候选目标的轮廓不再画在目标 `CardView.foreground` 上，而是作为根视图 overlay 使用目标卡片原始尺寸绘制；目标卡片缩小动画继续播放，因此轮廓与卡片主体之间会自然出现间隙，纯色和渐变文件夹背景仍会被用于轮廓颜色。高亮取消时会先清理 overlay，再尝试恢复目标卡片动画，避免目标 holder 被回收时残留轮廓。文件夹卡片 header 改为小号 icon + 右侧标题列，标题最多两行，数量文字左边距对齐标题列；简中数量文案改为 `%1$d件记事`。

私密文件夹方面，`ThingManager` 新增已认证私密文件夹 path 范围：打开受保护文件夹并认证成功后，把该文件夹记为当前路径内已认证；进入其后代时继续保持正常显示，返回到它外层或切换 drawer 目标后自动收敛认证状态。首页文件夹卡片、普通记事卡片、缩略图点击、文件夹移动目标、header 路径和详情页“位于哪个文件夹”都改为读取这个范围；因此在已认证私密文件夹内部，私密文件夹名称和内部内容会正常显示，但不会把其它路径的私密内容全局展开。设置文件夹为私密前，现在会检查 `KEY_PRIVATE_PASSWORD`；未设置应用密码时显示“无法设置为私密记事文件夹”的提示。

验证状态：沙箱内首次执行 `E:\projects\EverythingDone\gradlew.bat :app:assembleDebug --console=plain` 时被 `.gradle/configuration-cache/configuration-cache.lock` 访问拒绝拦住；按项目规则提权重跑 `.\gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL`。补充 overlay 清理边缘情况后再次执行 `.\gradlew.bat :app:assembleDebug --console=plain`，结果 `BUILD SUCCESSFUL in 2s`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606160743` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，`debugUpdateCode` 为 `202606160743`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606160743.apk`。当前环境仍没有稳定的真机拖拽/私密文件夹视觉自动化测试 seam；请重点复测：拖到记事 B 时轮廓和卡片是否有间隙；文件夹卡片 icon、标题、数量布局是否符合预期；未设置应用密码时能否阻止设为私密文件夹；进入私密文件夹认证后，内部记事/子文件夹/header/详情页路径是否显示真实名称。

## 2026-06-16 - 修复缩略图文件夹卡片透明底与文字颜色

用户反馈：文件夹切到显示缩略图的模式后，浅色模式下卡片外轮廓没问题，但内部会出现 `CardView` elevation 阴影或不是完全透明；标题、文件夹 icon、记事数量文字仍按文件夹颜色底来算对比度，而不是按首页灰白背景来算，所以颜色不对；并且透明状态不稳定，滑动列表、回到该文件夹卡片后，内部可能又变成文件夹本身的颜色。

本次诊断确认问题在 `ThingsAdapter` 的 Folder Card 复用路径。summary 模式通过 `BackgroundUtil.applyCardBackground(...)` 把文件夹纯色/渐变写到 `CardView.background`；thumbnail 模式只调用了 `setCardBackgroundColor(Color.TRANSPARENT)`，没有清掉 `CardView.background` 上可能从 summary 模式复用来的 `GradientDrawable`，所以滚动复用后会重新露出文件夹颜色。thumbnail 模式也仍保留了普通卡片的 `cardElevation`，触摸动画还会再次改变 elevation；文字和 icon 则继续使用文件夹背景色作为 `textColorPrimary(...)` / `textColorSecondary(...)` 的计算基准。

本次修改：thumbnail 模式现在会显式把外层 `CardView.background` 替换成透明圆角 drawable，同时把 `cardElevation` 和 `maxCardElevation` 都设为 `0f`，内层只保留透明填充、文件夹颜色描边的 `llContent` outline。新增 `tag_thing_folder_thumbnail_surface` 标记，触摸按下/松开动画遇到 thumbnail 文件夹卡片时只做 scale，不再改 elevation；普通记事卡片和 summary 文件夹卡片 bind 时会恢复这个 tag 和普通 `maxCardElevation`。thumbnail 模式的文件夹标题、文件夹 icon、数量文字、置顶 icon 现在用 `bg_activity_things` 作为对比基准，因此浅色模式会偏黑、暗色模式会偏白，不再按文件夹颜色底来选色。

验证状态：已执行 `E:\projects\EverythingDone\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`，结果 `BUILD SUCCESSFUL in 1s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已执行 `E:\projects\EverythingDone\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606160528` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，状态 200，`debugUpdateCode` 为 `202606160528`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606160528.apk`。当前 agent 环境仍没有稳定的真机滚动/复用视觉回归测试 seam；请重点复测：浅色模式下 thumbnail 文件夹卡片内部是否完全透明且无 elevation 阴影；滚动离开再回来是否仍保持透明；暗色模式下标题、icon、数量文字是否变为适合暗色背景的浅色。

## 2026-06-16 - 继续修复拖入文件夹前先回原位的问题

用户测试 debug update `202606160352` 后反馈：把记事拖到文件夹或另一个记事上时，仍然会先回到本来的位置，然后才进入文件夹。这说明上一版虽然做了 overlay 和定向列表更新，但 overlay 的创建时机仍然太晚。

本次继续诊断 `ItemTouchHelper` 生命周期：`clearView(...)` 并不是“手指刚松开”的最早时机，它可能发生在 AndroidX `ItemTouchHelper` 已经启动甚至完成默认 drag recovery 之后。上一版在 `clearView(...)` 里截取拖拽卡片并隐藏真实 View，因此真实 View 仍可能先执行一段回到原位的默认动画，用户就会看到“先回原位、再进入文件夹”。

本次修改集中在 `ThingsActivity.kt`：拖拽开始时记录 active drag ViewHolder；真实拖动帧中持续记录拖拽卡片在 root 坐标系里的最后位置；当存在 pending Folder drop 且 `ItemTouchHelper` 即将计算 drag recovery duration 时，`getAnimationDuration(...)` 会先准备 overlay snapshot、隐藏真实 source item view，并对这次 pending drop 的默认 drag recovery 返回 `0L`。这样默认回收动画启动前，真实卡片已经不可见，overlay 会从用户最后拖动位置开始播放进入目标文件夹/目标记事的动画。`clearView(...)` 仍然作为业务提交点，继续负责按 source Thing id 和 target Thing/Folder id 创建文件夹或移入文件夹，并触发定向 `notifyItemRemoved(...)` / `notifyItemChanged(...)`。

验证状态：已执行 `E:\projects\EverythingDone\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`，结果 `BUILD SUCCESSFUL in 3s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已执行 `E:\projects\EverythingDone\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606160403` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，状态 200，`debugUpdateCode` 为 `202606160403`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606160403.apk`。当前 agent 环境仍没有稳定的真机拖动自动化回归测试 seam；请重点复测：A 拖入现有文件夹 C 时是否彻底看不到回原位；A 拖到 B 创建文件夹时是否彻底看不到回原位；拖入目标后移出再松手是否仍不误触发。

## 2026-06-16 - 修复记事拖入文件夹/合并成文件夹的松手动画

用户继续反馈：记事 A 拖动到文件夹 C 或记事 B 上并松手后，虽然数据最终会变成正确状态，但 A 会先被 `ItemTouchHelper.clearView(...)` 拉回原来的位置；移入文件夹时缺少“A 缩小并进入文件夹、文件夹更新、列表补位”的连续动画，创建文件夹时也缺少“A 与 B 合并、B 位置变成新文件夹、A 原位置补位”的连续动画。

本次诊断确认：前几版已经让 pending drop 的业务状态在松手时可靠保留下来，但释放阶段仍沿用 `clearView(...)` 的默认视觉清理路径，以及 `ModeManager.backNormalMode(...)` 的延迟整表刷新路径。默认清理会先把真实拖拽 View 复位，延迟整表刷新又会覆盖 RecyclerView item animator 能表达的局部变化，所以用户会看到“先回原位，然后突然变成最终状态”。

本次修改集中在 `ThingsActivity.kt`、`ModeManager.kt` 和 `ThingsAdapter.kt`：释放 pending Folder drop 时，先把拖拽卡片绘制成一个临时 overlay，隐藏真实 source view，再调用 `clearView(...)`，这样默认复位不会被用户看到；overlay 会从松手位置缩小并移动到目标文件夹或目标记事卡片中心。成功拖入现有文件夹后，先更新 `things.folder_id` 并重建混合列表，再用 `notifyItemRemoved(sourcePosition)` 触发 A 原位置补位，同时 `notifyItemChanged(folderPosition)` 更新目标文件夹计数/缩略图。成功拖动 A 到 B 创建文件夹时，先用默认标题创建新文件夹并让新 Folder Card 出现在 B 的位置，再用同样的定向 removal/change 通知表现 A 消失和 B 位置变成文件夹；overlay 动画结束后再弹出重命名 dialog。`ModeManager` 增加了一个不触发整表刷新的 moving-mode 退出路径，避免成功 drop 后整表 appearing animation 抢掉局部动画。`ThingsAdapter.onBindViewHolder(...)` 也会重置根 itemView 的 visibility/alpha/scale，避免隐藏过的 source ViewHolder 复用到其它卡片时残留不可见状态。

验证状态：已执行 `E:\projects\EverythingDone\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`，结果 `BUILD SUCCESSFUL in 22s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已执行 `E:\projects\EverythingDone\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606160352` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，状态 200，`debugUpdateCode` 为 `202606160352`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606160352.apk`。当前 agent 环境仍没有稳定的真机拖动自动化回归测试 seam；请重点复测：A 拖入现有文件夹 C 后是否不再先回原位，而是缩小进入 C，并且列表补位；A 拖到 B 创建文件夹时，B 的位置是否变成新文件夹、A 的原位置是否补位；拖入目标后再移出目标时，是否不会误创建或误移入。

## 2026-06-16 - 继续修复记事文件夹拖放松手不生效

用户反馈上一版 debug update `202606160240` 后，“动画出现后松手不创建文件夹/不移入文件夹”的问题仍然存在。上一版已经移除了 `clearView(...)` 中的二次坐标判断，但问题继续存在，说明 pending drop 仍可能在 `clearView(...)` 之前被清掉。

本次继续诊断 `ItemTouchHelper` 拖拽生命周期：`onChildDraw(...)` 在松手后的恢复动画阶段仍可能以 `ACTION_STATE_DRAG` 被调用，但此时 `isCurrentlyActive=false`，拖动 View 的坐标已经不再代表用户手指释放前的位置。旧逻辑没有区分 active 与 recovery frame，所以恢复帧会再次执行“左上角命中检测”，发现拖动卡片已经回到原位后清除 pending drop，最终 `clearView(...)` 仍然拿不到待创建/移入的业务状态。

本次修改：`onChildDraw(...)` 现在只在 `isCurrentlyActive=true` 的真实拖动帧里更新或清除 pending Folder drop；松手后的非 active 恢复帧不再改变 pending drop。为了进一步降低位置漂移风险，pending drop 也从单纯记录 source/target adapter position 改为记录 source Thing id，以及 target Thing id 或 target Folder id；释放时按 id 重新解析当前对象并执行创建文件夹或移入文件夹。旧的 position-only 拖放 helper 已移除，避免后续再次走到不稳定路径。

验证状态：已执行 `E:\projects\EverythingDone\gradlew.bat :app:assembleDebug`，结果 `BUILD SUCCESSFUL in 3s`。已执行 `E:\projects\EverythingDone\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606160305` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，状态 200，`debugUpdateCode` 为 `202606160305`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606160305.apk`。当前 agent 环境仍没有稳定的真机拖动自动化回归测试 seam；请重点复测：目标动画出现后立刻松手是否能打开新建文件夹命名 dialog；拖到现有文件夹后松手是否能把记事移入该文件夹；拖入目标后再移出目标、松手时是否不会误触发。

## 2026-06-16 - 修复记事文件夹拖放释放与撤销闪烁

用户测试 debug update `202606160225` 后反馈两个问题：第一，记事 A 拖动到记事 B 或文件夹 C 上时，目标动画已经出现，但松手后没有创建文件夹或移入文件夹，而是 A 回到原位并进入选择模式；第二，当记事列表第一个位置是文件夹时，滑动完成第二个位置的记事，再点击“未完成”撤销后，文件夹本身会闪烁一下。

本次诊断确认拖放问题发生在 `ItemTouchHelper.Callback.clearView(...)` 的释放阶段。拖动过程中的候选状态和动画已经正确建立，但释放时旧代码又重新用 View 坐标判断一次；此时拖动 View 可能已经被 `ItemTouchHelper` 恢复或坐标不再代表最后一帧拖动位置，于是候选 drop 被清除，并落入“没有移动则进入选择模式”的旧分支。修复后，释放阶段会消费拖动过程中最后一个仍然有效的 pending Folder drop；如果用户在松手前把 A 移出目标卡片，拖动帧里的命中检测仍会先清除 pending 状态，因此不会误触发创建或移入。

撤销闪烁问题来自混合列表 position 映射：滑动完成时保存给状态恢复逻辑的是原始 `mThings` 位置，但有文件夹卡片时 adapter position 与 raw Thing index 不再一致。旧的 undo 分支直接 `notifyItemInserted(position)` 或 `notifyItemChanged(position)`，当第一个可见卡片是文件夹时，就会误刷新文件夹卡片。修复后，撤销完成/习惯完成时会根据被恢复的 Thing id 重新查询当前混合列表位置，再通知 adapter；找不到精确位置时回退到 `notifyDataSetChanged()`，避免误刷文件夹。

验证状态：已执行 `E:\projects\EverythingDone\gradlew.bat :app:assembleDebug`，结果 `BUILD SUCCESSFUL in 5s`。已执行 `E:\projects\EverythingDone\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606160240` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，状态 200，`debugUpdateCode` 为 `202606160240`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606160240.apk`。当前 agent 环境没有稳定的真机拖动自动化回归测试 seam，请重点复测：动画出现后松手是否创建文件夹；拖到现有文件夹后松手是否移入；把 A 移出目标再松手是否仍回到普通排序/选择行为；列表第一个可见卡片是文件夹时，滑动完成第二个记事并撤销，文件夹是否不再闪烁。

## 2026-06-16 - 修复记事文件夹拖拽动画与混合列表交互

用户测试记事文件夹主功能后反馈：拖动记事创建文件夹的目标动画不明显，且触发条件应改为“正在拖动的记事卡片左上角进入目标卡片”；拖动记事到现有文件夹时应把记事移入该文件夹，而不是创建新文件夹；文件夹卡片也应参与列表出现动画，打开文件夹后内部列表也应播放出现动画；有文件夹时创建新记事会闪烁且新卡片不可见，重启后才出现；有文件夹后记事卡片左右滑动完成/开始做事失效。

本次修改把拖放判定从重叠阈值改为拖动卡片左上角命中目标卡片：只有左上角位于另一个记事卡片内部时才进入“创建文件夹”候选状态，移出后会清除候选状态并回到普通排序/选择流程。创建候选状态会让目标卡片缩小，并绘制一圈与待创建文件夹背景一致的文件夹轮廓；这个背景在候选状态创建时随机生成，并支持纯色和渐变，最终创建出来的文件夹沿用同一个背景。拖动记事到现有文件夹卡片时，现在会进入“移入文件夹”候选状态，目标文件夹卡片会缩小并显示更粗的轮廓，释放后把记事移入该文件夹；如果左上角不在文件夹卡片内部，则不会触发移入。

混合列表交互也同步修复：文件夹卡片现在参与首页列表出现动画；打开文件夹、从路径跳转或返回父文件夹时，会重新播放当前文件夹内列表出现动画。新建记事时，`ThingManager` 会把新记事归入当前文件夹投影，并在数据变更后重建 Thing/Folder 混合列表；首页使用新记事在混合列表中的位置执行插入和 ShiningBorder 动画，修复有文件夹时新建记事闪烁、不可见、需要重启才显示的问题。左右滑动完成记事/开始做事也已恢复：文件夹卡片本身不可滑动，但混合列表中的普通记事卡片继续支持原来的左右滑动动作。

验证状态：已执行 `E:\projects\EverythingDone\gradlew.bat :app:assembleDebug`，结果 `BUILD SUCCESSFUL in 16s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已执行 `E:\projects\EverythingDone\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606160225` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`；发布后回读远端 metadata，状态 200，`debugUpdateCode` 为 `202606160225`，APK URL 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606160225.apk`。请重点测试：拖动记事 A 的左上角进入/离开记事 B 时的动画与最终创建行为；拖动记事进入现有文件夹时是否移入文件夹；打开文件夹后的列表出现动画；有文件夹时新建记事是否立即出现并播放动画；混合列表中普通记事的左右滑动是否恢复。

## 2026-06-16 - 新增记事文件夹主功能测试版

用户要求新增一项重大的“记事文件夹”功能：在首页长按记事后，可以把一个记事拖到另一个记事上创建文件夹并命名；文件夹支持摘要卡片和缩略图卡片两种展示，支持宽卡片、置顶、混在记事列表里调整顺序、私密、嵌套；打开文件夹后首页 header 显示文件夹路径，路径片段可点击跳转；详情页显示记事所在文件夹。后续讨论中确认：文件夹不作为 drawer item 展示；drawer 仍只保留内置列表；当前列表状态是内置 destination 加可选文件夹路径；文件夹计数递归统计子文件夹内的记事并包含隐藏的私密记事；已完成/删除的记事保留原文件夹隶属；已完成和回收站列表也显示对应文件夹；删除文件夹会把整个文件夹子树移动到回收站，永久删除则删除子树和其中记事。

本次实现包含数据库和模型基础：数据库升级到 v15，新增 `things.folder_id` 与 `thing_folders` 表；新增 `ThingFolder`、`ThingFolderCardPresentation`、`ThingListProjection`、`ThingListEntry`；`ThingDAO` 与 `ThingFolderDAO` 支持文件夹路径、递归计数、缩略图种子、有效私密、有效删除、父子移动、状态更新和永久删除。`ThingManager` 现在可以加载 Thing/Folder 混合列表，创建文件夹，移动 Thing 或 Folder，重命名文件夹，切换展示模式/宽度/私密/置顶，并把混排顺序分别持久化到 `things.location` 和 `thing_folders.location`。

首页实现了 Folder Card：摘要模式是实心卡片并显示文件夹图标、标题和递归数量；缩略图模式是描边卡片，显示可点击进入详情的子记事缩略图，并避免在隐藏私密内容时泄露私密标题或预览。文件夹卡片可点击进入当前内置列表下的文件夹投影；长按可重命名、切换摘要/缩略图、切换普通/宽卡片、设为私密、置顶/取消置顶、调整卡片顺序、移动到其它文件夹、删除/还原/永久删除。拖拽创建文件夹加入了重叠阈值和目标高亮，避免普通排序和“拖到一起成文件夹”直接抢同一个手势。选择若干 underway 记事后，也可以通过菜单移动到某个非删除文件夹或根目录，用于测试从私密文件夹移出后继承私密状态是否消失。

详情页新增文件夹路径显示；ActivityHeader 支持文件夹路径标题和可点击路径片段；drawer 选择内置列表会清空当前文件夹路径。搜索、颜色过滤、已完成、回收站、备注/提醒/习惯/目标列表均接入文件夹投影；Things-list widgets 暂不渲染文件夹卡片，保持现有 Thing-only 行为。同步新增并持续更新 `docs/features/thing-folders/` 下的 plan、execution、decisions、sessions 和 followups 文档。

验证状态：已多次执行 `.\gradlew.bat :app:assembleDebug` 并通过，最后一次结果为 `BUILD SUCCESSFUL in 3s`；`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。当前 agent 环境尚未做真机/模拟器完整交互矩阵测试，发布后请重点测试：升级后旧记事是否仍正常显示；拖动两个记事创建文件夹；文件夹摘要/缩略图/宽卡片/置顶/私密；嵌套文件夹和路径点击；已完成/回收站中的文件夹；删除、还原、永久删除文件夹；私密文件夹内外移动时内容是否泄露。

## 2026-06-15 - 修复长截图分享到完事儿创建记事时无法读取的问题
用户继续反馈：在分享记事生成的长截图时，如果在系统分享 dialog 里选择“完事儿”的“创建记事”，新建记事无法读取到这张长截图。

本次诊断确认问题在接收方路径，而不是截图生成本身。上一版 `ScreenshotHelper.ShareCallback` 已经把截图作为 FileProvider `content://` URI 放进 `EXTRA_STREAM`、`ClipData` 和 intent `data`，并授予读取权限；但 `DetailActivity.setupThingFromIntent()` 作为分享接收方时，只调用 `UriPathConverter.getLocalPathName(...)`，只有能拿到本地文件路径时才写入附件。FileProvider URI 本来就是面向流读取的 `content://`，不保证暴露 `_data` 列，因此分享到同一个 app 的创建记事入口时，URI 有效、文件也存在，但旧接收逻辑可能因为解析不出本地路径而直接丢掉附件。

本次修改集中在 `DetailActivity.kt`：外部分享接收现在会从 `EXTRA_STREAM`、intent `data` 和 `ClipData` 中读取 URI；多附件分享会对这些来源去重；单附件和多附件都走同一个 `getTypePathNameFromIncomingShare(...)` 辅助逻辑。该逻辑保留原先可直接解析本地路径的路径；如果解析不到路径，则根据 `ContentResolver.getType(uri)` 或分享 Intent 的 MIME type 推断后缀，通过 `FileUtil.copyUriToFile(...)` 把 URI 流复制到 app 的临时媒体文件区，再生成现有的附件 type/path/name token。这样既能修复“长截图分享回完事儿”的 FileProvider URI，也能提升其它外部 app 以纯 `content://` 分享图片、视频、音频时的兼容性。

验证状态：`git diff --check` 已通过，仅有仓库既有的 LF/CRLF warning；`.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过。当前 agent 环境没有可直接确认 OPPO/系统分享 dialog 的真机交互回路，发布后请重点测试：从某个记事生成长截图，系统分享面板选择“完事儿/创建记事”，新建记事是否能显示该长截图附件。

## 2026-06-15 - 修复长截图分享时系统分享面板预览空白的兼容性问题

用户反馈：使用长截图分享某个记事时，截图生成完成并弹出系统分享 dialog 后，图片预览好像是空白的，不确定是 OPPO ColorOS 的问题，还是应用需要额外设置预览缩略图。

本次诊断先查阅 Android 官方文档：`ACTION_SEND` 分享图片应提供具体 MIME type 和 `EXTRA_STREAM`；FileProvider 分享的 `content://` URI 需要通过 `FLAG_GRANT_READ_URI_PERMISSION` 授予临时读权限；Android Intent 文档还说明，从 Jelly Bean 开始可通过 `ClipData` 提供发送数据，以配合 URI 读权限。对照项目实现，`ScreenshotHelper.ShareCallback` 已经使用 `ACTION_SEND`、`image/jpeg`、`EXTRA_STREAM` 和读权限，实际分享链路基础正确；但它没有显式设置 `ClipData`、intent `data`、`EXTRA_TITLE`，也没有把读权限加到 chooser intent 上。对于系统 Sharesheet 预览而言，它需要在用户选择目标 app 之前读取并解码图片，ColorOS 可能对 `ClipData`/chooser 授权更敏感，或者对超高长图 JPEG 的预览解码更容易失败。

本次修改集中在 `ScreenshotHelper.kt`：生成截图的 `content://` URI 现在同时写入 `EXTRA_STREAM`、`ClipData` 和 intent `data`，保留 `image/jpeg` MIME type；分享标题会写入 `EXTRA_TITLE`；原始 send intent 和 `Intent.createChooser(...)` 返回的 chooser intent 都添加 `FLAG_GRANT_READ_URI_PERMISSION`。这不会改变最终分享出去的长截图文件，只是让系统分享面板的预览读取路径更完整、更兼容。

验证状态：`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，结果 `BUILD SUCCESSFUL in 10s`，发布 debug update `202606150347` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。发布后回读远端 `latest.json`，状态 200，`Content-Type` 为 `application/json`，首字符 ASCII 为 123（`{`），`debugUpdateCode` 为 `202606150347`，`releaseNotesLength` 为 1233，确认 metadata 仍是 JSON object 且只包含本次当前条目。当前 agent 环境没有 OPPO ColorOS 设备可直接复现系统分享面板预览；如果用户测试后仍为空白，下一步是给 Sharesheet 单独生成小尺寸 preview thumbnail URI，同时继续通过 `EXTRA_STREAM` 分享完整长图。

## 2026-06-15 - 继续收紧调整记事卡片外观入口与标题

用户继续反馈三个问题：第一，虽然“调整记事卡片外观”的顺序已经提前，但“完成选中的记事”的 icon 仍然显示在 contextual toolbar 上，导致 toolbar 太拥挤；第二，点击“调整记事卡片外观”后出现的面板标题也应该改为“调整记事卡片外观”；第三，新入口 icon 的边框可以稍微粗一点。

本次修改：`menu_contextual_underway.xml` 中的 `act_finish_selected` 已改为 `showAsAction="never"`，并移除 `android:icon`，因此“完成选中的记事”会回到 overflow 中只显示文字，不再占用 toolbar icon 位；`thing_card_appearance_panel_title` 在默认英文、简繁中文、德语、西语、法语、印地语、意大利语、日语、韩语、葡萄牙语和俄语资源中都同步为与入口一致的文案；`act_adjust_card_appearance.xml` 的卡片外框 stroke 从 `1.6` 加粗到 `1.8`，内部滑杆保持原有粗细。

验证状态：`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，结果 `BUILD SUCCESSFUL in 8s`，发布 debug update `202606150309` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。发布后回读远端 `latest.json`，状态 200，`Content-Type` 为 `application/json`，首字符 ASCII 为 123（`{`），`debugUpdateCode` 为 `202606150309`，`releaseNotesLength` 为 734，确认 metadata 仍是 JSON object 且只包含本次当前条目。

## 2026-06-15 - 调整记事卡片外观入口顺序、图标和文案

用户反馈：长按记事卡片进入选择/搜索上下文后，“自定义卡片外观”入口在 contextual menu 后几个选项里，希望把它提前到目前“完成选中的记事”的位置，并把“完成选中的记事”顺序后移；同时给该入口设置一个好看的 icon，并把文案改为“调整记事卡片外观”，完成国际化。

本次修改集中在首页 contextual toolbar 与 Thing Card Appearance 入口：`menu_contextual_underway.xml` 中的 `act_customize_card_appearance` 已移动到 `act_finish_selected` 前，并改为 `showAsAction="always"`；当 `ModeManager` 判断该入口可见时，它会出现在原来“完成选中的记事”的 toolbar action 位置，“完成选中的记事”紧随其后。若该入口不可见，“完成选中的记事”仍会自然占回原来的可见位置。`menu_contextual_finished.xml` 与 `menu_contextual_deleted.xml` 中同一个入口也补上了 icon 引用，保持资源一致。

新增 `app/src/main/res/drawable/act_adjust_card_appearance.xml`，图形为 24dp 的“卡片 + 调整滑杆”单色 vector，使用 contextual toolbar 现有的 `black_54p` 视觉层级。保留既有资源 key `act_customize_card_appearance` 和代码 id，避免不必要的 Kotlin 逻辑改名；仅把用户可见文案改为默认英文 `Adjust thing card appearance`、简中 `调整记事卡片外观`，并同步更新繁中、德语、西语、法语、印地语、意大利语、日语、韩语、葡萄牙语和俄语资源。

验证状态：`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，结果 `BUILD SUCCESSFUL in 10s`，发布 debug update `202606150256` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。发布后回读远端 `latest.json`，状态 200，`Content-Type` 为 `application/json`，首字符 ASCII 为 123（`{`），`debugUpdateCode` 为 `202606150256`，`releaseNotesLength` 为 1096，确认 metadata 仍是 JSON object 且只包含本次当前条目。

## 2026-06-15 - 修复 debug 更新 metadata 的 releaseNotes 过大问题

用户反馈：App 内检查更新提示 `Expected BEGIN_OBJECT but was STRING at line 1 column 1 path`，怀疑发布到服务器的更新 JSON 有问题。

本次诊断先回读远端 `http://120.25.194.207/everythingdone-updates/debug/latest.json`，确认当前远端响应状态为 200、`Content-Type` 为 `application/json`，响应首字符确实是 `{`，本地 `app/build/outputs/update-debug-apk/latest.json` 也是合法 JSON object；`BuildConfig.DEBUG_UPDATE_METADATA_URL` 也指向正确的 debug metadata 地址。继续排查后发现，`publishDebugUpdate` 在使用 `-PdebugUpdateNotesFile=memory/debug-update-notes.md` 时，会把整份长期历史 notes 文件写进 `releaseNotes`，导致当前 `latest.json` 膨胀到约 90KB，并且包含大量历史发布记录。虽然这不是严格意义上的 JSON 语法错误，但会让更新弹窗拿到远超当前版本所需的 metadata，且增加缓存、代理或 App 端处理异常的风险。

本次修改集中在 `app/build.gradle`：新增 `currentDebugUpdateNotes(...)`，当 notes 文本里存在多个 `##` 条目时，只提取顶部第一条当前发布说明写入 `latest.json`；同时在写出 metadata 前用 `JsonSlurper` 解析生成内容，确认顶层必须是 JSON object，否则直接让发布任务失败。同步更新 `.agents/rules/gradle.md` 与 `docs/features/debug-update-channel/preferences.md`，明确 `debugUpdateNotesFile` 会保留历史但只发布顶部当前条目。

验证状态：`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606150245` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。发布后回读本地和远端 `latest.json`，两者长度均为 3313，首字符 ASCII 为 123（`{`），`debugUpdateCode` 为 `202606150245`，`releaseNotesLength` 为 1247，确认顶层 JSON object 正常且 `releaseNotes` 只包含当前条目。

## 2026-06-15 - 将附件/卡片裁切编辑器迁到自定义 DialogFragment

用户指出：Detail 里的图片/视频外观 dialog，以及首页“自定义卡片外观”里“裁切封面图片/视频”的 dialog，不应继续由 Activity 直接创建 raw `android.app.Dialog`。本项目偏好使用 `fragments` 目录下的自定义 `DialogFragment`，通常经由 `BaseDialogFragment` 统一 dialog title、window background、宽度和生命周期。

本次修改：新增单一 `MediaCropAppearanceDialogFragment`，复用 `BaseDialogFragment` 的 window/titleless/background/width 生命周期路径。`DetailActivity` 与 `ThingsActivity` 不再直接构造 `Dialog`，而是通过同一个 Fragment 的不同 request key 打开媒体裁切外观 dialog，并由 Fragment 负责 tag、window width 和 view destroy cleanup。原本高度依赖 Activity 草稿状态的内容视图、crop/video-frame/ratio 控件、confirm/cancel 逻辑仍保留在 Activity 的 Host 回调中，避免改变 Detail 附件外观保存链路和首页 Thing Card Appearance 草稿链路；这一步已经把“3 个 wrapper”的方案收敛成一个自定义 DialogFragment。

同步记录了跨功能偏好：以后应用内功能 dialog 优先使用 `fragments` 下的自定义 `DialogFragment` / `BaseDialogFragment`，不要在 Activity 中直接 new raw `android.app.Dialog`。

验证状态：已执行 `git diff --check`，结果通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`，结果 `BUILD SUCCESSFUL in 1s`。随后执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606150235` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。

## 2026-06-14 - 继续收紧附件/卡片裁切 dialog 间距与视频文案

用户继续反馈：Detail 设置图片/视频外观 dialog 中，图片/视频预览区域距离其它区域的上下 margin 仍然偏大；首页长按进入“自定义卡片外观”后，“裁切封面图片/视频”的 dialog 应与 Detail 外观 dialog 保持同一组数值；两个地方的“封面图片/视频比例”“图片/视频显示比例”提示文字颜色偏深，应参考“图片显示宽度”的提示颜色；视频场景下“图片显示宽度”也应改为“视频显示宽度”。

本次代码确认：这两个 dialog 不是同一个 dialog 类。Detail 附件外观 dialog 在 `DetailActivity.kt` 中手写构建；首页 Thing Card Appearance 的精确裁切 dialog 在 `ThingsActivity.kt` 中手写构建。它们共用底层 `ThingCardCropEditorView` / `ThingCardVideoCropEditorView`，但 dialog chrome、margin 和 label 是两套代码，因此这次手动对齐两处数值。

本次修改：Detail 和首页裁切 dialog 的 title/content 横向 margin 统一为 `24dp`，title top margin 统一为 `24dp`，preview top margin 统一收紧到 `6dp`，video controls top margin 统一收紧到 `6dp`，ratio controls top margin 统一收紧到 `6dp`，action row top margin 统一为 `16dp`。两个 ratio label 的颜色都从 `app_chrome_on_surface_secondary` 改为 `app_chrome_on_surface_hint`，与“图片显示宽度/视频显示宽度”一致，并继续通过 App Chrome 资源适配浅色/暗色模式。Detail 视频附件现在显示“视频显示宽度”；首页视频裁切 dialog 的比例 label 可显示“封面视频比例”。已补齐默认英文、简中、繁中及其它现有 locale 的新增字符串，并为非中文 locale 补上本次新增 video cover ratio 的本地化翻译，避免只显示英文兜底。

验证状态：已执行 `git diff --check`，结果通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`，结果 `BUILD SUCCESSFUL in 7s`。随后执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606141559` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。

## 2026-06-14 - Detail 附件外观文案/间距与首页卡片外观入口修正

用户反馈四个问题：Detail 图片/视频附件外观 dialog 不应继续显示泛化的“附件外观”；视频编辑时比例 label 不应显示“图片显示比例”；dialog 标题左侧和顶部 margin 偏小、预览区域上下 margin 偏大；首页已完成记事长按后不应提供“自定义卡片外观”；从搜索中打开自定义卡片外观 UI 后按返回，应回到搜索上下文，而不是直接退出搜索状态。

本次修改集中在 `DetailActivity.kt`、`ThingsActivity.kt`、`ModeManager.kt` 和各 locale `strings.xml`：Detail 附件外观 dialog 现在根据媒体类型显示“图片外观”或“视频外观”，比例 label 根据媒体类型显示“图片显示比例”或“视频显示比例”。标题 start/end margin 改为 `24dp`，title top margin 改为 `24dp`，取代原来的 root `18dp` top padding；预览区域 top margin 从 `16dp` 收紧到 `10dp`，视频帧控制区 top margin 从 `12dp` 收紧到 `8dp`，比例控制区 top margin 从 `12dp` 收紧到 `8dp`，底部 action row top margin 从 `20dp` 收紧到 `16dp`。首页卡片外观入口现在排除 `Thing.FINISHED`；搜索状态下关闭 Thing Card Appearance panel 时不再自动退出 selecting mode，避免把搜索上下文一起收掉。

国际化方面，新增了图片/视频外观标题与图片/视频比例 label 的资源；非默认 locale 也补齐了 Detail 附件外观相关行文，避免 dialog 局部回退到英文。请重点测试：Detail 图片/视频附件外观 dialog 的标题、视频比例 label、整体上下间距；Finished 列表和搜索结果中的已完成记事长按菜单是否不再出现“自定义卡片外观”；搜索状态中打开卡片外观 panel 后按返回/取消是否仍保留搜索上下文。

验证状态：首次 `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 暴露了法语资源中 `l'image` 的 Android string 转义问题；已改为 `l\'image` 后重新执行同一 assemble，结果 `BUILD SUCCESSFUL in 9s`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606141538` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。

## 2026-06-08 - 修复视频附件外观编辑器切换宽度后 loading 不消失

用户反馈：对于视频附件，在 Detail 附件外观编辑器里切换“正常”和“宽”之后，预览区域会一直显示加载圈。

本次诊断确认：Detail 编辑器切换“正常/宽”时会调用 `loadActivePresentationIntoEditor()`，视频分支会再次调用 `ThingCardVideoCropEditorView.setCropVideo(...)`，以载入另一套 presentation 的 ratio/crop。`setCropVideo(...)` 原实现每次都会把 `firstFrameVisible` 置为 false 并 `setLoadingVisible(true)`，然后调用 `preparePlayer(...)`。但当同一个 `ThingCardVideoCropEditorView` 已经有 `MediaPlayer` 时，`preparePlayer(...)` 会因为 `player != null` 直接返回，不会触发新的 `onPrepared`、`onSeekComplete` 或首帧更新逻辑来关闭 loading。因此切换正常/宽后 loading 被打开，但没有收尾事件，表现为加载圈一直转。

本次修改集中在 `ThingCardVideoCropEditorView.kt`：`setCropVideo(...)` 现在会先判断视频源是否变化；如果源变化则释放旧 player 并重新 prepare。如果同一视频源已有 player 且已经 prepared，就只更新 ratio/crop/frame、隐藏 loading，并执行 `seekTo(...)`，不再重启播放器准备流程；如果 player 存在但还没 prepared，则保持 loading，等待原 prepare 流程完成。这让 Detail 切换“正常/宽”能够复用已有播放器，同时避免 loading 状态卡住。该修复也让首页 Thing Card Appearance 未来如果二次调用 `setCropVideo(...)` 时更稳。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`，结果 `BUILD SUCCESSFUL in 3s`；随后执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606081448` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。尚未做真机视觉验证。请重点测试：视频附件外观编辑器中反复切换“正常/宽”、拖动图片显示比例 slider、切换后再拖动裁切中心，以及播放/暂停状态下切换 presentation，预览 loading 是否会正常消失。

## 2026-06-08 - 修复自动保存丢失 Detail 附件外观草稿

用户指出一个关键自动保存 bug：`DetailActivity.saveAfterOnPause()` 会在 `onPause` 时自动保存当前编辑内容，它直接把 title、content、attachment、Thing Card span/placement 和颜色写入 `mThing`，随后调用 `ThingManager.create/update` 或 `ThingDAO.update`。但 Detail 附件外观编辑器确认后的 crop/fullSpan/ratio/videoFrame 修改只存在于草稿 `mDetailAttachmentMediaAppearance` 中，`saveAfterOnPause()` 没有把该草稿同步到 `mThing.detailAttachmentMediaAppearance`，因此用户编辑附件 appearance 后切到其他 app 触发自动保存，会把旧 appearance 写入数据库，导致本次 appearance 编辑丢失。

本次诊断复查了正常保存路径 `returnToThingsActivity -> createOrUpdateThing -> createThing/updateThing`，该路径会执行 `mDetailAttachmentMediaAppearance = normalizedDetailAttachmentMediaAppearance(attachment)` 并写回 `mThing.detailAttachmentMediaAppearance`。对比确认 `saveAfterOnPause()` 是独立保存路径，之前没有执行同样的 normalize-and-write-back 步骤。

本次修改集中在 `DetailActivity.kt`：新增 `applyDetailAttachmentMediaAppearanceDraftToThing(attachment)`，内部先用当前附件列表 normalize `mDetailAttachmentMediaAppearance`，再写入 `mThing.detailAttachmentMediaAppearance`。`saveAfterOnPause()` 在写入 `mThing.attachment`、`thingCardSpanMode`、`thingCardImagePlacement` 后、任何 `ThingManager.create/update` 或 `ThingDAO.update` 调用前调用该 helper。`createThing(...)` 和 `updateThing(...)` 也改为调用同一个 helper，避免正常保存和自动保存再次分叉。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`，结果 `BUILD SUCCESSFUL in 5s`；随后执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606081353` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。当前没有合适的本地单元测试 seam 能真实覆盖 Android 生命周期里的 `onPause -> saveAfterOnPause -> ThingManager/DAO` 路径；这次用代码链路核对和 assemble 作为反馈 loop。请重点测试：打开 Detail 后编辑附件 appearance 并 confirm，直接切到其他 app 触发自动保存，再杀回/重新进入时，crop/fullSpan/ratio/videoFrame 是否保留。

## 2026-06-08 - 修正 Detail 附件裁切持久化与渲染重应用

用户继续反馈两点：第一，“图片显示宽度”提示文字在用户设备上会换成两行，希望 label 宽度再大一些；第二，调整裁切中心仍然不生效，并建议对照首页记事卡片自定义外观的实现，分清到底是数据没有写进数据库，还是显示链路没有应用 crop。

本次诊断对照了首页 `ThingsActivity.openThingCardCropEditor()`、`updateThingCardCurrentCrop()` 与 `BaseThingsAdapter.loadThingCardImage()` / `applyThingCardMediaCrop()` 的链路。首页做法有两个关键点：外观编辑时先更新独立 draft，不提前污染原始 `Thing`；缩略图渲染时把 load key、目标尺寸和 crop 存成 `ImageView` tag 上的 render request，Glide 默认 target 更新 drawable 后再 post 应用 matrix，并且同一图片源复用时会直接重套当前 crop。对照 Detail 实现后确认了两个问题：Detail 编辑确认和删除附件时会提前把 `mThing.detailAttachmentMediaAppearance` 改成 draft 值，而保存时 `Thing.noUpdate(...)` 又拿 `mThing` 作为旧值比较，这会让只改 Detail 附件外观的场景被误判为 no-update，从而不写数据库；同时 `ImageAttachmentAdapter` 之前只在 Glide 成功回调的一次时机应用 crop，没有像首页那样保存 render request，也没有在同源重绑、crop 变化但图片源不变时立即重应用 matrix。

本次修改：`DetailActivity` 中 `setDetailAttachmentMediaAppearanceFromJson(...)`、`applyDetailAttachmentMediaAppearance(...)` 和图片/视频附件删除路径不再提前写回 `mThing.detailAttachmentMediaAppearance`，只维护 `mDetailAttachmentMediaAppearance` 作为 Detail draft；只有 `createThing(...)` / `updateThing(...)` 真正保存时，才 normalize 当前附件 source 并写回 `mThing`。这样 `Thing.noUpdate(...)` 会正确把旧 `mThing.detailAttachmentMediaAppearance` 与新的 Detail draft 比较，只改裁切中心也会触发数据库更新。UI 上，“图片显示宽度” label 列宽从 76dp 增加到 104dp，并设置单行显示。

显示链路方面，`ImageAttachmentAdapter` 新增 `tag_detail_attachment_image_load_key` 与 `tag_detail_attachment_image_render_request`，自定义 Detail 附件缩略图现在保存与首页类似的 render request，包含 load key、目标宽高和 crop。绑定时如果图片源和尺寸没变但 crop 变了，会直接对现有 drawable 重套 matrix；Glide 加载完成后返回默认 target 路径，并在 post 中根据当前 render request 应用 crop，避免被 drawable 更新或 RecyclerView 复用时机覆盖。legacy 未自定义附件仍使用原来的 Glide `centerCrop()` 路径。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`，结果 `BUILD SUCCESSFUL in 5s`；随后执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606081259` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。尚未做真机视觉验证。请重点测试：只调整 Detail 附件裁切中心后保存/返回/重新进入是否仍保留；确认后当前 Detail 缩略图是否立即变化；同一图片改多次 crop 是否不需要换图也能刷新；“图片显示宽度”是否保持单行；删除/撤销删除附件外观是否仍正常。

## 2026-06-08 - 修正详情页附件外观编辑器 UI 与裁切中心生效问题

用户测试详情页图片/视频附件自定义外观后反馈两类问题：第一，编辑器 UI 不应直接展示 `full-span` 术语，而应像首页“自定义卡片外观”一样使用 pill 按钮选择；提示文字应为“图片显示宽度”，选项为“正常”和“宽”，比例拖动条上方文字应为“图片显示比例”；标题需要适配当前记事 background，并顺带检查暗色模式。第二，调整裁切中心后看起来没有生效。

本次诊断确认：详情页编辑器之前临时使用 `CheckBox + Full-span` 文案控制 full-span，视觉和首页卡片外观的 pill 选择不一致；标题也只是普通 `app_chrome_on_surface_primary` 文本色，没有像卡片外观精确裁切 dialog 那样使用当前记事 background/accent。暗色模式相关的 App Chrome surface、on-surface 文本色和 ripple 已有 `values-night` 资源，本轮继续使用这些资源，不硬编码浅色。裁切中心问题的最可能原因在 `ImageAttachmentAdapter`：自定义模式下 `onResourceReady()` 里先 `post` 应用 matrix，但返回 `false`，Glide 随后仍会执行默认的 `setImageDrawable()`，容易覆盖我们刚设置的 matrix/scaleType，因此 Detail 缩略图最终看起来仍像普通居中裁切。

本次修改：`DetailActivity` 的 Detail 附件外观编辑器新增与 Thing Card Appearance 同风格的宽度 pill 行，显示“图片显示宽度 / 正常 / 宽”，选中态使用当前记事 background 或纯色 accent 填充，文字按背景亮暗自动切换黑/白；未选中态使用 App Chrome secondary 文本色。full-span 技术词不再出现在可见 UI。比例控制的 label 改为“图片显示比例”。编辑器 root 改为使用 `bg_app_chrome_surface_elevated_rounded`，dialog window 背景改为透明，标题改为通过 `BackgroundUtil.applyTextBackground(...)` 或 accent color 适配当前记事 background。`values/strings.xml` 与 `values-zh-rCN/strings.xml` 已同步更新。

裁切中心修复集中在 `ImageAttachmentAdapter`：自定义 Detail 附件缩略图加载完成后，listener 现在先手动 `setImageDrawable(resource)`，再在当前 item 仍匹配时应用 Detail crop matrix，并返回 `true` 拦截 Glide 默认 target 更新；legacy 非自定义模式仍走原来的 Glide `centerCrop()` 和默认返回路径。这样用户在编辑器里拖动得到的 `centerX/centerY/scale` 会保留到 Detail 缩略图渲染端。

验证状态：已执行 `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`，结果 `BUILD SUCCESSFUL in 8s`；随后执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606081231` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。尚未做真机视觉验证。请重点测试详情页附件外观编辑器的宽度 pill UI、标题在纯色/渐变记事 background 下的颜色、暗色模式 surface/text/ripple、图片和视频附件裁切中心是否会在确认后立即影响 Detail 缩略图，以及 legacy 未自定义附件是否仍保持旧布局。

## 2026-06-08 - 详情页图片/视频附件支持自定义显示外观

用户希望 DetailActivity 中带图片/视频附件的记事，也能像首页“自定义卡片外观”一样调整媒体显示外观：位于第一个位置的图片/视频可以设置是否 full-span、目标宽高比、裁切区域和裁切缩放；其余图片/视频保持 1:1 网格显示，但可以调整裁切中心和缩放比例。用户在方案确认中补充了多个关键约束：单附件当前默认 full-span 的设计保持不变；不要把概念命名为 first，而是命名为 full-span；full-span 比例范围允许从 1:2 到 65:24（Hasselblad XPan 超宽画幅）；fullSpan crop 可以从已有 grid crop 种子化，但目标比例仍可保持 1:1；grid 的 ratio 也保存，给未来扩展留空间；不需要 reset；不设置裁切缩放上限。

本次实现新增 Detail 专用附件媒体外观模型 `DetailAttachmentMediaAppearance`，并在 `things` 表增加 `detail_attachment_media_appearance` 字段，数据库版本升级到 14。外观数据按附件 source key 保存，包含弱文件身份 `fileSize/lastModified`、`fullSpanEnabled`、共享的 `videoFrameMs`，以及 `grid` / `fullSpan` 两套 presentation；每套 presentation 保存 `targetAspectRatio` 和 crop 的 `centerX/centerY/scale`。附件变更时会通过 `ThingCardMediaHelper.getMediaSourceKeysFromAttachment(...)` 只保留当前仍存在的 source；删除图片/视频附件时会移除对应外观，并把 before/after JSON 写入 undo action，保证撤销删除时可以恢复。备份/恢复通过数据库字段自然携带该外观；`ThingExporter` 的 txt/zip 导出仍保持原有单向内容导出，不额外输出外观 JSON。

详情页渲染改动集中在 `DetailActivity` 和 `ImageAttachmentAdapter`。没有保存过 Detail 附件外观的旧记事仍走原来的布局逻辑：单媒体保持当前 full-width 4:3，多媒体保持原有网格；这样历史记事不会因为新增字段默认值而改变显示。存在当前附件的 saved appearance 后，列表进入自定义布局：单附件固定 full-span 且不能关闭；多附件中只有当前位置 0 的附件可以启用 full-span，启用后占满首行，其余附件从下一行继续 1:1 网格显示；非首位附件始终按 1:1 网格显示，但可编辑裁切中心和缩放。长按拖拽重排保持原逻辑，不自动改写外观：如果原 first full-span 附件被移走，它不会把 full-span/crop 继承给新的第一项；如果一个原本保存了 full-span 外观的附件被移动到第一位，则会按自身保存的 fullSpan 配置显示。

本次新增的编辑 UI 使用 App Chrome Dialog，视觉和交互参考首页“自定义卡片外观”。图片附件使用 `ThingCardCropEditorView` 预览裁切，视频附件使用 `ThingCardVideoCropEditorView`，并复用 play/pause/stop、seekbar 和视频帧选择能力。第一个多附件会显示 full-span 开关；单附件不显示可关闭的开关但可编辑 full-span 比例和 crop；普通网格附件只显示 grid crop 编辑。ratio 滑条支持 1:2、1:1、4:3、3:2、16:9、2:1、65:24 等刻度；grid 当前渲染仍固定 1:1，但已保存 grid ratio 字段。确认编辑会写入 `ThingAction.UPDATE_DETAIL_ATTACHMENT_MEDIA_APPEARANCE`，支持撤销/重做；取消编辑不会污染当前记事草稿。

代码与资源主要涉及：`app/src/main/java/com/ywwynm/everythingdone/model/DetailAttachmentMediaAppearance.kt`、`Def.kt`、`DBHelper.kt`、`Thing.kt`、`ThingDAO.kt`、`ThingAction.kt`、`DetailActivity.kt`、`ImageAttachmentAdapter.kt`、`app/src/main/res/layout/attachment_image.xml`、`values/strings.xml` 和 `values-zh-rCN/strings.xml`。同步更新了 `docs/features/detail-attachment-media-appearance/`、`docs/adr/0004-detail-attachment-media-appearance.md`、`CONTEXT.md`、`memory/decisions.md`、`memory/preferences.md` 与 `memory/sessions.md`。

验证状态：已经执行 `.\gradlew.bat :app:assembleDebug` 并通过，最终输出 `BUILD SUCCESSFUL in 2s`；`git diff --check` 也已通过，仅有仓库既有 LF/CRLF warning 与本机 `.config/git/ignore` 权限 warning。尚未在真机/模拟器上做视觉测试。用户本轮要求使用 Gradle task 往阿里云发布一个版本，已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606081117` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试详情页单附件默认 full-span 是否保持旧行为、多附件首位 full-span 开关、非首位 1:1 crop、图片和视频裁切预览、视频帧选择、删除/撤销删除、拖拽重排后 full-span 外观不错误继承，以及 1:2 到 65:24 的极端比例显示。

## 2026-06-07 - 优化首页记事卡片图片重复显示

用户提供了 DeepSeek 的代码审查结果，并反馈首页记事列表支持不同封面比例后，图片首次加载完成后再滚动回来仍经常出现图片区域空白，需要等待 Glide 再次填充。用户期望首次加载可以等待，但已加载过的同一记事卡片再次出现时应直接显示图片。

本次诊断确认：`BaseThingsAdapter.loadThingCardImage()` 在复用到不同图片 source 的 `ImageView` 时会先 `clear(imageView)`，然后再启动 Glide request。即使 `mLoadedThingCardImageKeys` 已隐藏 loading spinner，`ImageView` 仍会在 Glide 回调前短暂为空。DeepSeek 提到的 `dontTransform()` 不适合移除，因为当前 Thing Card media crop 由应用自己的 matrix 渲染负责；移除它会破坏自定义裁剪中心和缩放行为。`ThingsActivity` 滚动时暂停 Glide request 的逻辑本轮也不改，先聚焦已加载后再次显示的问题。

本次修改集中在 `app/src/main/java/com/ywwynm/everythingdone/adapters/BaseThingsAdapter.kt`：新增 adapter 级 `LruCache<String, Bitmap>`，容量按设备 memory class 计算并限制在 8MB 到 24MB；cache key 统一包含 media path、文件大小、lastModified、目标宽高和 `videoFrameMs`，media background 额外使用 `background:` 前缀。普通缩略图和 media background 在启动新的 Glide request 前先查 adapter bitmap cache，命中时取消旧 request、立即 `setImageBitmap()`、应用当前 crop matrix 并跳过 Glide reload；未命中时保留现有 same-source placeholder、`dontTransform()` 和 `dontAnimate()` 流程。成功加载的 `BitmapDrawable` 会复制为软件 bitmap 后写入 cache，GIF 或非 bitmap drawable 仍走原 Glide 行为。

同步更新 `docs/features/thing-card-media-target-geometry/sessions.md`，记录本次首页卡片媒体 bitmap reuse cache 的范围和保留项。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；`.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过。用户随后明确要求发布测试包，已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606071039` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页普通 top/bottom 图片卡、full-span left/right 图片卡、media background 卡片滚远再滚回时是否直接显示已加载图片，并确认隐藏私密记事、无图片记事和图片加载失败路径没有旧图残留。

## 2026-06-07 - 用户手调后提交搜索 HUE_BUCKET ColorPicker 间距

用户在上一版基础上又做了一次本地视觉调整，并要求直接 Git 提交。

本次提交采用用户最终手调后的状态：搜索页实际使用的仍是 `Def.PickerType.HUE_BUCKET`；第一行 hue bucket FAB 的 top margin 为 8dp，仍小于原始 16dp；最后一行 hue bucket FAB 的 bottom margin 为 12dp；`HUE_BUCKET` 的 RecyclerView 固定高度保持 256dp。同步清理了不再使用的局部 margin 变量。

同步更新 `docs/features/popup-picker-insets/preferences.md` 与 `sessions.md`，确保文档记录的是最终提交值，而不是上一版发布测试时的 18dp/262dp。用户本轮只要求提交，因此没有在最终手调后重新发布 debug update。

## 2026-06-07 - 搜索 HUE_BUCKET ColorPicker 间距回调到中间值

用户确认上一版已经改到实际搜索使用的 `HUE_BUCKET`，但要求把第一行 FAB 的 top margin 从 0 恢复到比最开始小一些，底部 margin 也恢复到比当前小一些，并考虑是否需要调整整个 popup 高度。

本次修改：`HUE_BUCKET` 第一行 FAB 的 top margin 从 0dp 调整为 8dp，仍小于最初的 16dp；最后一行 FAB 的 bottom margin 从 20dp 调整为 18dp，仍略大于左侧 FAB 到 popup 左侧的 16dp margin；`HUE_BUCKET` 的 RecyclerView 固定高度从 256dp 调整为 262dp，用来吸收行高增加并保留底部留白。

同步更新 `docs/features/popup-picker-insets/preferences.md` 与 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070448` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 的第一行 FAB 间距、底部留白和 popup 总高度。

## 2026-06-07 - 修正搜索 ColorPicker 实际使用的 HUE_BUCKET 间距

用户反馈：即使手动把 `COLOR_HAVE_ALL` 的 `params.height` 改成 400dp，搜索 ColorPicker 的高度和间距仍然没有变化；要求仔细检查是否还有其它地方更新高度和 margin，并明确实现目标：缩小“全部颜色”和第一行 FAB 的间距，放大最后一行 FAB 和 popup 底部的间距，底部间距要比左侧 FAB 到 popup 左侧的 16dp margin 稍大一点。

本次诊断：搜索页实际创建的是 `ColorPicker(this, window.decorView, Def.PickerType.HUE_BUCKET)`，不是 `COLOR_HAVE_ALL`。因此之前调整 `COLOR_HAVE_ALL` 的 fixed height 或 margin，不会影响正在测试的首页搜索 ColorPicker。搜索 popup 里的“全部颜色”是 `HUE_BUCKET` 的 all-filter row，下方 FAB 是 8 个 hue bucket，分成 4 行。

本次修改：恢复 `COLOR_HAVE_ALL` 的高度和 margin 基线；改为调整 `HUE_BUCKET`。第一行 hue bucket FAB 的 top margin 改为 0dp，让它更贴近“全部颜色”；最后一行 hue bucket FAB 的 bottom margin 改为 20dp，比左侧 FAB 到 popup 左侧的 16dp margin 稍大一点。`HUE_BUCKET` 的 RecyclerView 固定高度保持 256dp。

同步更新 `docs/features/popup-picker-insets/preferences.md` 与 `sessions.md`，记录首页搜索 ColorPicker 对应的是 `HUE_BUCKET`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070439` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 的第一行 hue bucket FAB 间距和底部留白。

## 2026-06-07 - 直接修正搜索 ColorPicker FAB 间距

用户反馈上一版仍然很怪，并明确要求：第一行 FAB 的 top margin 直接改成 0，最后一行 margin 加大。

本次修改：`COLOR_HAVE_ALL` 第一行两个 FAB 的 top margin 从 4dp 改为 0dp；最后一行两个 FAB 的 bottom margin 从 16dp 改为 24dp；搜索 ColorPicker 的 RecyclerView 固定高度继续保持 312dp。这样“全部颜色”和第一行 FAB 之间会明显收紧，最后一行 FAB 到 popup 底部的留白也会明确加大。

同步更新 `docs/features/popup-picker-insets/preferences.md` 与 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070430` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 的第一行 FAB 贴近程度和底部留白。

## 2026-06-07 - 重新调整搜索 ColorPicker 底部留白来源

用户测试 `202606070415` 后反馈视觉区别不大，并指出 FAB 的高度、margin 本身没问题；因为 ColorPicker 的 RecyclerView 高度是写死的，要增加底部留白更应该增大 RecyclerView 固定高度，同时第一行 FAB top margin 仍然要减小。

本次诊断：`COLOR_HAVE_ALL` 由一个 48dp 的“全部颜色”行，加 10 个颜色 FAB 组成的 5 行双列网格构成。上一版只是把第一行 FAB 上方 4dp 挪到最后一行 FAB 的 bottom margin，整体内容高度基本不变，RecyclerView 固定高度仍是 304dp，所以实际底部留白变化不明显。

本次修改：`COLOR_HAVE_ALL` 的 RecyclerView 固定高度从 304dp 增加到 312dp；第一行 FAB 的 top margin 保持从 8dp 减到 4dp；最后一行 FAB 的 bottom margin 恢复普通的 16dp，不再把最后一行 item 自己撑大。这样底部新增空间来自 RecyclerView viewport，而不是来自 FAB item margin。

同步更新 `docs/features/popup-picker-insets/preferences.md` 与 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070426` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 的整体高度、全部颜色到第一行 FAB 的间距，以及最后一行 FAB 到 popup 底部的留白。

## 2026-06-07 - 微调搜索 ColorPicker 的垂直间距

用户反馈：搜索时的 ColorPicker 里，“全部颜色”和下方 FAB 网格之间的间距稍微大了点，而最下方 FAB 和 popup 底部的间距又稍微小了一点。

本次修改：只调整 `Def.PickerType.COLOR_HAVE_ALL`，不影响详情页改色 ColorPicker、Hue bucket picker 或 COLOR_EDIT picker。第一行 FAB 的 top margin 从 8dp 改为 4dp，让“全部颜色”和 FAB 网格之间更紧一点；最后一行 FAB 的 bottom margin 从 16dp 改为 20dp，让 popup 底部留白更足一点。由于上方减少的 4dp 正好移动到底部，搜索 ColorPicker 的总高度保持不变。

同步新增 `docs/features/popup-picker-insets/preferences.md`，并更新 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070415` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 的“全部颜色”到第一行 FAB 间距、最后一行 FAB 到 popup 底部间距，以及 popup 总体高度是否仍稳定。

## 2026-06-07 - ColorPicker 改为从 anchor 右上角出现

用户确认 `202606070356` 中自定义卡片外观选择封面来源 popup 的位置 OK，并提出最后一个调整：ColorPicker 不要再从 anchor center 显示，改为直接从 anchor 右上角显示，但动画方向保持不变。

本次修改：`ColorPicker` 继续使用 `PopupPicker.installContentSurfaceScaleTransition(1, 0)`，因此 popup surface 仍然从自身右上角向左下展开、反向收起。定位上，`ColorPicker.show()` 的有效 anchor 分支从 `showAsDropDown(anchor, -anchor.width / 2, -anchor.height / 2, Gravity.END)` 改为 `showAsDropDown(anchor, 0, -anchor.height, Gravity.END)`，让 popup 的右上角直接落在 trigger view 的右上角，而不是 trigger view 的中心。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070400` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 与详情页改色 ColorPicker 的出现锚点和出现/隐藏动画方向。

## 2026-06-07 - 修正 source picker 右下角锚点偏下

用户测试 `202606070343` 后反馈：自定义卡片外观选择封面来源 popup 仍然没有从对应 TextView 的右下角出现，而是比右下角更偏下。

本次诊断：上一版已经去掉了 source picker 的 8dp screen-margin clamp，但它仍然使用 `Gravity.BOTTOM | Gravity.START`，并且按 `mParent.height - navBottom` 计算 bottom reference。与此同时 content-surface popup 已经统一设置 `PopupWindow.isClippingEnabled = false`。在这个组合下，bottom gravity 可能按完整 parent/window bottom 结算，继续减 `navBottom` 会让 popup bottom 比目标 anchor bottom 低一个系统栏 inset 左右，视觉上就是“更偏下方一些”。

本次修改：`ThingCardAppearanceSourcePicker` 不再使用 bottom gravity 做右下角定位，改为先测量 popup 宽高，再使用 `Gravity.TOP | Gravity.START`：`x = anchorRight - popupWidth`，`y = anchorBottom - popupHeight`。这样 popup 的右下角和 source TextView 的右下角是直接几何对齐，不再受 bottom gravity / navbar compensation 语义影响。出现和隐藏动画仍保持从右下向左上展开、反向收起。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070356` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试自定义卡片外观选择封面来源 popup 的右下角锚点是否已经贴到对应 TextView 右下角。

## 2026-06-07 - 修正 content surface popup 的精确锚点

用户测试 `202606070330` 后提出两个问题：
1. 自定义卡片外观选择封面来源 popup 的右下角锚点不像预期中的 pill 触摸区域右下角；
2. DateTimeDialog “一段时间之后” tab 里的 time-type popup 出现锚点高于对应 pill 左上角，而“重复” tab 因为 dialog 更高没有同样问题。用户询问是否与 pill 的 `clipToOutline` 或 dialog 高度限制有关，以及是否有不改 dialog 高度的更好办法。

本次诊断：`BackgroundUtil.installPillRipple(...)` 会清掉 background、设置 foreground ripple，并用 `clipToOutline` 把绘制裁成 pill，但这不会改变 TextView 的 measured bounds；所以 pill 触摸区域仍然是整个 TextView 矩形，不是 outline 另算出来的小区域。source picker 的偏差主要来自上一版为防越界加的 8dp margin/clamp。DateTimePicker “一段时间之后” tab 的问题则来自我们把 time-type popup 的 y offset clamp 到 `mParent.height - popupHeight`；after tab 的 dialog 内容更矮，popup 放不下时被我们自己提前上移了。重复 tab 内容更高，所以没有触发这个 clamp。

本次修改：
- `PopupPicker.installContentSurfaceScaleTransition(...)` 对 content-surface transition popup 统一设置 `mPopupWindow.isClippingEnabled = false`，让较矮的 dialog tab 可以让 popup 越出局部内容高度，而不需要改 dialog 高度。
- `DateTimePicker` 改为使用精确 anchor offset：quick-remind 的 popup bottom 直接对齐 anchor TextView 垂直中心，time-type 的 popup top 直接对齐 anchor TextView top，不再按父容器高度做可容纳性 clamp。
- `ThingCardAppearanceSourcePicker` 改为 popup right/bottom 直接对齐 anchor TextView right/bottom，不再额外套 8dp screen-margin clamp。首尾 item 的 8dp row margin 保持不变。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070343` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试自定义卡片外观选择封面来源 popup 的右下角锚点、DateTimeDialog “一段时间之后” tab 的 time-type 左上角锚点，以及这几个 popup 的隐藏动画。

## 2026-06-07 - 统一剩余 popup picker 的 content surface transition

用户反馈：app 里还有多处使用原本的 `PopupWindow` window animation，需要全部改掉，并指定三类 popup 的出现位置与动画方向：
1. quick-remind：popup 出现在对应 TextView 左侧，Y 方向居中，从左下向右上展开；
2. time-type：popup 出现在对应 TextView 左上角，从左上向右下展开；
3. 自定义卡片外观选择封面图片：popup 出现在对应 TextView 右下角，从右下向左上展开；同时 popup 内第一项/最后一项分别增加 top/bottom margin，参考 quick-remind。

本次修改：
- `DateTimePicker` 内按 picker type 分开处理 transition 和定位。`AFTER_TIME` 继续使用 content surface 左下角 pivot `(0, 1)`，popup 左边缘对齐 anchor TextView 左侧，底边对齐 anchor 垂直中心，并保留 bottom system-bar inset 补偿。time-type popup 改为 content surface 左上角 pivot `(0, 0)`，popup 左上角对齐 anchor TextView 左上角。
- `ThingCardAppearanceSourcePicker` 不再使用 `QuickRemindPickerAnimation`，改为 `popupAnimStyle = 0` 并调用 `installContentSurfaceScaleTransition(1, 1)`，让可见圆角 surface 从右下角向左上展开/反向收起。定位改为 popup 右下角对齐 anchor TextView 右下角，并保留 bottom system-bar inset 补偿。
- `ThingCardAppearanceSourcePicker` 的 adapter 在首项增加 8dp top margin、末项增加 8dp bottom margin，保持和 quick-remind picker row spacing 一致。
- 删除剩余旧 popup window animation style/resource：`QuickRemindPickerAnimation`、`quick_remind_picker_show.xml`、`quick_remind_picker_hide.xml`。此前已删除 `ColorPickerAnimation` 与 `TimeTypePickerAnimation`。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070330` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试详情页 quick-remind、DateTimeDialog 的 time-type 下拉、自定义卡片外观选择封面来源 popup，以及三者的出现/隐藏动画和点击外部/BACK 隐藏动画。

## 2026-06-07 - DateTimePicker 改用左下角 content surface transition

用户确认上一版 ColorPicker 的 content-surface transition 方案可以接受，并要求把 DateTimePicker 也按同样思路改掉：popup 从左下向右上展开，但出现位置可以是对应 TextView 的左侧，而不是水平居中；Y 方向仍以 TextView 垂直中心为基准。

本次修改：把可复用的 content surface scale transition 抽到 `PopupPicker.installContentSurfaceScaleTransition(...)`。ColorPicker 改为继续使用这个共享 helper，pivot 为右上角 `(1, 0)`。DateTimePicker 不再使用 `PopupWindow` window animation，改为 `popupAnimStyle = 0`，清空 `PopupWindow` background，并通过共享 helper 安装 pivot 为左下角 `(0, 1)` 的 `enterTransition` / `exitTransition`，让可见圆角 popup surface 从左下向右上展开和收起。

DateTimePicker 的定位也同步调整：不再按旧动画资源里的内部 pivot 计算，也不再水平居中到 anchor TextView。现在先读取 anchor TextView 的 `getLocationInWindow(...)`，X 使用 TextView 左侧，Y 使用 TextView 垂直中心；popup 左边缘对齐该 X，popup 底边对齐该 Y。`AFTER_TIME` 分支仍保留 bottom system-bar inset 补偿，再换算为 `Gravity.BOTTOM` 所需的 y offset；time-type 分支使用 `Gravity.TOP`。为了避免旧 window animation 方案回流，删除了只剩 DateTimePicker 使用的 `TimeTypePickerAnimation`、`time_type_picker_show.xml` 与 `time_type_picker_hide.xml`；`QuickRemindPickerAnimation` 仍保留，因为 `ThingCardAppearanceSourcePicker` 还在使用。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070321` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试详情页 quick-remind DateTimePicker、DateTimeDialog 中的 time-type picker、出现锚点、出现动画、隐藏动画，以及点击外部/BACK 的隐藏动画。

## 2026-06-07 - ColorPicker 改用 content surface transition，避开 window animation 锚点漂移

用户测试 `202606070304` 后反馈：恢复 `PopupWindow` window animation 后，动画层级对了，但又变成不是从 anchor center 显示 popup。用户判断 window animation 可能直接影响出现锚点，并要求搜索网页寻找更好的解决方案。

本次检索与诊断：查阅 Android Developers `PopupWindow` 文档和 AOSP `PopupWindow.java` 源码后确认，`setAnimationStyle(...)` 使用的是 `windowEnterAnimation` / `windowExitAnimation`，AOSP 会把它写入 `WindowManager.LayoutParams.windowAnimations`。同时 `PopupWindow` 会使用 `PopupDecorView`，有 background 时还会包一层 background view，并为 elevation 设置 surface inset。因此 window animation 的百分比 pivot 作用在 window/decor/surface bounds 上，不一定等同于可见圆角 picker surface 的右上角；这解释了为什么 final position 可以正确，但动画视觉 origin 会偏离 anchor center。

本次修改：ColorPicker 不再使用 window animation，也删除旧的 `R.style.ColorPickerAnimation`、`color_picker_show.xml` 和 `color_picker_hide.xml`。ColorPicker 现在传入 `popupAnimStyle = 0`，清空 `PopupWindow` background，保留 `mContentView` 上的圆角 elevated background 作为唯一可见 popup surface，并通过 `PopupWindow.enterTransition` / `exitTransition` 安装 `ColorPickerSurfaceTransition`。该 transition 直接缩放可见 content surface，pivot 固定在 surface 右上角；定位仍使用已验证的 `showAsDropDown(anchor, -anchor.width / 2, -anchor.height / 2, Gravity.END)`，让 surface 右上角对应 anchor center。因为使用的是 PopupWindow transition 而不是手写 show 后 content 动画，`dismiss()`、点击外部、BACK 等平台 dismiss 路径也能触发 exit transition。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`，记录 ColorPicker 不再使用 window animation 做 anchor-origin surface motion。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070312` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试 ColorPicker 的出现锚点、出现动画、隐藏动画，以及点击外部/BACK 的隐藏动画。

## 2026-06-07 - 恢复 ColorPicker 的 popup 窗口级动画

用户测试上一版 `202606070258` 后反馈：位置终于对了，但动画不对。当前表现是 popup surface 直接出现，没有窗口动画；只有里面的 content 从右上向左下缩放出现；隐藏时 popup 也直接消失。用户需要的是 popup 本身的出现/隐藏动画，而不是 content 动画。

本次修改：撤销 ColorPicker 的 content-level scale/alpha animation，恢复 `R.style.ColorPickerAnimation` 作为 `PopupWindow` window animation style，让 popup surface 本身负责 show/hide 动画。保留已经验证位置正确的 `showAsDropDown(anchor, -anchor.width / 2, -anchor.height / 2, Gravity.END)` 定位路径，以及显式写回 `mPopupWindow.width/height` 的做法。`color_picker_show.xml` / `color_picker_hide.xml` 仍保持 `pivotX="100%"`、`pivotY="0%"`，让窗口动画从 popup 右上角展开/收起。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`，记录 content animation 是已废弃方案，正式方案应保留 PopupWindow window animation。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070304` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试 ColorPicker 的位置、出现动画和隐藏动画。

## 2026-06-07 - ColorPicker 改为 content 级别展开动画

用户纠正：ColorPicker 的触发点是 toolbar menu item 的 icon view，不应继续从 TextView/文字布局角度解释。结合线上检索和 Android 官方文档，`PopupWindow.setAnimationStyle(...)` 走的是 window enter/exit animation，动画作用在 popup window/decor 层；而不是 picker content 本身。这个 window/decor 层可能包含平台 surface inset、shadow 或窗口管理调整，因此即使 anchor 定位 API 没问题，窗口动画 pivot 仍可能和实际 picker surface 的视觉右上角不一致。

本次修改：ColorPicker 不再使用 `R.style.ColorPickerAnimation` 作为 `PopupWindow` window animation style，而是传入 `0` 禁用 ColorPicker 的窗口级动画。定位仍保留上一版的 `showAsDropDown(anchor, -anchor.width / 2, -anchor.height / 2, Gravity.END)`，让平台负责 anchor 坐标换算。popup 放置后，直接在 `mContentView` 上执行 scale/alpha enter animation：`pivotX = mContentView.width`、`pivotY = 0`，从 `scaleX/scaleY/alpha = 0` 动画到 `1`，让实际 picker surface 从自身右上角展开。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`，记录 ColorPicker 的动画层从 `PopupWindow` window animation 改为 picker content animation。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070258` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 与详情页改色 ColorPicker 的出现锚点。

## 2026-06-07 - ColorPicker 改用 PopupWindow 平台 anchor 定位

用户继续反馈：ColorPicker 仍然不像是从 anchor view 中心出现，并追问是否 anchor view 的计算位置不对。本次进一步阅读 Android 36 `PopupWindow` 源码：`showAsDropDown(...)` 内部会用 `anchor.getLocationOnScreen()` 减去 app root 的 screen location 来得到 app-window 坐标；而上一版 `ColorPicker` 是手动用 `getLocationInWindow()` 再传给 `showAtLocation(...)`。在 edge-to-edge、多窗口或 popup child-window parent frame 场景中，这种手算坐标系可能和平台内部转换不完全一致。

本次修改：`ColorPicker.show()` 在测量 popup content 后，将测得的 width/height 显式写回 `mPopupWindow`，这样 `showAsDropDown(..., Gravity.END)` 的 right-align 能使用绝对尺寸。有效 anchor 存在时改为调用 `showAsDropDown(anchor, -anchor.width / 2, -anchor.height / 2, Gravity.END)`，等价于让 popup top-right corner 钉到 anchor view center，但坐标转换交给 `PopupWindow` 平台路径处理。无有效 anchor 时才保留 `showAtLocation(...)` 右上兜底。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`，记录 ColorPicker 的 top-right-corner 模型应使用平台 anchor positioning。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070247` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 与详情页改色 ColorPicker 的出现锚点。

## 2026-06-07 - ColorPicker 改为 popup 右上角向左下展开

用户提出新的判断：`ColorPicker` 的 anchor view 一般位于屏幕右上角，如果沿用当前从 popup 顶部偏中间位置展开的动画，popup 右侧可能先超出屏幕范围，然后被系统或定位 clamp 自动修正，导致实际看到的出现锚点仍偏左上。这个判断符合上一版 `202606070232` 仍未校准好的现象。

本次修改：`ColorPicker` 不再使用 `86%, 10%` 的内部 animation pivot，也不再使用 `0.25 * anchorSize` 的视觉补偿。`color_picker_show.xml` 和 `color_picker_hide.xml` 改为 `pivotX="100%"`、`pivotY="0%"`，让动画明确从 popup 右上角向左下展开。`ColorPicker.show()` 的定位公式同步改为将 popup 的 top-right corner 直接钉到 anchor view 的 window-relative center，并对 x/y offset 做 parent window 内的 clamp，避免 popup 右边先越界再被系统修正。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`，记录 ColorPicker 从内部 pivot/视觉补偿模型切换到 top-right-corner 模型。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070239` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 与详情页改色 ColorPicker 的出现锚点。

## 2026-06-07 - ColorPicker popup 锚点右下校准

用户远程测试上一版 `202606070222` 后反馈：`ColorPicker` 的 popup 出现锚点仍然偏左上。因为本地无法稳定复现 Android `PopupWindow` 的实际动画视觉，只能通过代码级定位公式和用户反馈做小步校准；本轮没有改动 `DateTimePicker`。

本次修改：`ColorPicker.show()` 在读取 anchor view 的 window-relative center 后，额外将目标点向右下移动 `0.25 * anchor.width` 与 `0.25 * anchor.height`，再按 `color_picker_show` 的 popup animation pivot `(0.86, 0.10)` 换算为 `showAtLocation(...)` offset。这样保持 window-relative、anchor-driven 的定位模型，同时专门对冲 ColorPicker 目前看到的左上偏差。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`，记录这次为 ColorPicker-only visual correction。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070232` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 与详情页改色 ColorPicker 的 popup 出现锚点。

## 2026-06-07 - 修正 ColorPicker / DateTimePicker popup 的 anchor 中心定位

用户反馈：`ColorPicker` 的 popup 现在看起来像是出现在对应 icon view 的左上角，而不是以该 view 的中心作为出现位置；`DateTimePicker` 的 popup 也有类似问题。用户希望两个 picker 都改为围绕对应 view 的中心来定位，并在修改后直接发布 debug update 方便远程测试。

本次诊断：复查了 `PopupPicker` 及其两个子类的 `showAtLocation(...)` 坐标语义。原实现仍混有旧的边缘/魔数定位规则：`ColorPicker` 以 toolbar action 的右边缘推导 x offset，`DateTimePicker` 的 quick-remind 分支让 popup bottom 贴到 anchor 中线，time-type 分支则仍有旧的 x/y 魔数。结合现有动画资源，新的规则改为读取 anchor 的 window-relative 中心点，并让 popup 自身动画 pivot 对齐到该中心点。

本次修改：
- `PopupPicker.kt` 新增共享 helper：测量当前 popup content 尺寸、读取 anchor center、根据 anchor center + popup animation pivot 计算并夹取 window-relative offset。
- `ColorPicker.kt` 删除剩余的 display-global fallback 坐标计算，不再使用 `getLocationOnScreen()` / `getDisplaySize()` 推导位置；有效 anchor 存在时按 `color_picker_show` 的 pivot `(0.86, 0.10)` 对齐到 anchor center，无有效 anchor 时仍兜底贴窗口右侧。
- `DateTimePicker.kt` 的 quick-remind 和 time-type popup 都改为按 anchor center + 对应动画 pivot 定位；quick-remind 分支保留 bottom system-bar inset 补偿，再把计算出的 popup top 换算为 `Gravity.BOTTOM` 所需的 y offset。
- 更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`，记录本次规则修正：`DateTimePicker` quick-remind 不再以 “popup bottom = anchor center” 作为最终规则，而是以 anchor center 对齐 popup animation pivot。

验证与发布：`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070222` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点远程测试首页搜索模式的 ColorPicker、详情页改色 ColorPicker、详情页 quick-remind DateTimePicker，以及 DateTimeDialog 中时间单位 DateTimePicker 的 popup 出现位置。

用户最初反馈：昨晚通过 `WindowInsets` 修复了首页进入搜索/选择模式后
contextual toolbar 与 statusbar 区域的外观问题，但怀疑 app 里还有不少旧的
system bar 处理方式，例如直接估算 statusbar 或 navigation bar 高度，而不是读取
实机当前 window insets。

前置修正：上一轮已经继续修复首页 contextual toolbar 的 statusbar strip 退出动画。
`view_contextual_status_bar` 保持在 `rl_contextual_toolbar` 内，由父级统一控制
show/hide 和进入/退出动画，避免退出选择态时 statusbar strip 提前 invisible 造成闪烁。

本轮分析：用 `grill-with-docs` 方式核对了 `CONTEXT.md`、Android 16 migration 文档、
`home-contextual-toolbar` 与 `popup-picker-insets` feature docs，并扫描代码。结论是这次
应作为 App Chrome 的 system-bar inset 技术债清理，而不是引入
Everything-Android 的 `BaseActivity.latestWindowInsets` 架构。原因是当前 EverythingDone
已经有 `DisplayUtil.chainDecorInsetsCallback(...)`，并且它包含 IME animation 时序处理；
直接换成 Activity 基类协议会扩大改动面，并可能和现有 helper-based padding/margin 更新冲突。

本轮修改：
- 在 `docs/features/system-bar-insets/` 新增 feature 文档，记录分析、范围决定、follow-up
  与实现会话。
- 删除 `DisplayUtil.getStatusbarHeight(...)`、`DisplayUtil.getNavigationBarHeight(...)` 和
  `DisplayUtil.hasNavigationBar(...)`，不再保留旧的 system bar resource/display-size 估算 helper。
- 在 `DisplayUtil` 中新增 runtime top inset 相关 helper，用 `WindowInsetsCompat.Type.systemBars()`
  与 `displayCutout()` 读取当前窗口 inset。
- 将 `DisplayUtil.chainDecorInsetsCallback(...)` 从简单 list 改为 keyed callback map，让同一
  target/helper 的重复注册变成覆盖而不是累积，避免重复叠加 bottom inset。
- 更新 `DetailActivity` 和 `StatisticActivity`，由 statusbar spacer 的 inset callback 缓存当前
  top inset，并用于滚动阈值、actionbar shadow、image cover、bottom bar shadow 等计算。
- 更新 `ImageViewerActivity`，用 runtime top inset helper 设置全屏 viewer 的 actionbar top margin。
- 更新 `ThingsActivity`，移除对旧 statusbar resource height fallback 的依赖，改读当前 window top inset。
- 更新 `BaseThingWidgetConfiguration`，移除 configuration change 中重复注册 bottom inset margin 的路径，
  并在退出 preview/config 状态时清理 bottom inset margin helper。
- 更新 `ColorPicker` 与非 `AFTER_TIME` 的 `DateTimePicker` top-gravity popup placement，将旧
  statusbar height 读取替换为当前 top inset，保留原有 window-relative / multi-window 坐标模型。

验证状态：
- `rg` 确认 `app/src/main` 下已经没有 `getStatusbarHeight`、`getNavigationBarHeight`、
  `hasNavigationBar`、`status_bar_height`、`navigation_bar_height` 或未使用的
  `getCurrentBottomSystemInset` 引用。
- 沙箱内 `:app:assembleDebug` 曾因 Kotlin daemon 访问用户目录被拒绝而失败；提升权限后
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过。
- 最终 `git diff --check` 已通过，仅有仓库已有的 LF/CRLF warning。
- 本次用户明确授权通过 Gradle `:app:publishDebugUpdate` 将 APK 与相关文件上传到阿里云服务器；
  已使用 `memory/debug-update-notes.md` 作为 debug update notes 发布 debug update `202606070204`。
  远端 debug channel metadata：`http://120.25.194.207/everythingdone-updates/debug/latest.json`。

建议测试重点：详情页滚动阴影与图片附件顶部 cover、统计页标题/FAB 变化、图片查看器 toolbar、
Widget 配置 preview 底栏、首页 contextual toolbar 的进入/退出 statusbar strip 动画，以及
ColorPicker / DateTimePicker 的 top popup 位置。
