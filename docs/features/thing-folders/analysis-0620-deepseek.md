# Thing Folders 实现分析 — 2026-06-20

本文档是对 `plan.md` 和 `execution.md` 所列功能的对照分析，识别未完成项、潜在 bug 和设计隐患。

---

## 一、execution.md 中仍未勾选的条目

### Phase 2 — Manager And List Projection

**`Migrate ThingsActivity current-list state from raw limit to list projection`**

部分完成。`ThingManager` 内部同时持有 `mLimit`（原始 int）和 `mProjection`（`ThingListProjection`），但 `ThingsActivity` 等上层调用方仍然通过 `App.getLimit()` / `App.setLimit()` 读写 limit，与 `mProjection` 形成两套状态源。更关键的是 `ThingListProjection.withLimit()` 的实现会**丢弃当前的 folder path**，直接返回 `emptyList()` 的根投影：

```kotlin
fun withLimit(limit: Int): ThingListProjection {
    return ThingListProjection(normalizeLimit(limit), emptyList())
}
```

因此当用户在某个已打开的文件夹内切换目的类型（例如从「正在进行」切换到「笔记」），文件夹路径会静默丢失，用户被拉回根目录。这个行为在产品层面是否合理需要确认；但至少 `withLimit` 丢路径这一事实在本 phase 就应该被记录。

建议：如果产品允许在切换 limit 后仍停留在当前文件夹内，`withLimit` 应改为 `copy(folderPath = folderPath)`（并 trim 私有/已删除文件夹）；如果产品确实需要切换 limit 时回到根目录，代码也应显式清空 folder path 并更新 header/drawer 状态，而不是通过 `emptyList()` 静默重置。

**`Migrate ActivityHeader title/subtitle generation to list projection`**

已完成。`ActivityHeader.updateText()` 和 `updateSubtitle()` 都通过 `ThingManager` 的 projection 和 `mThingListEntries` 计算，没有直接引用 `mLimit`。

**`Keep drawer selection tied only to the built-in destination part of the projection`**

大部分完成，但有一处遗漏：`onBackPressed` 回到上级文件夹时（`ThingsActivity.kt:2208`），调用了 `updateDrawerFolderItems()` 更新文件夹树，但**未调用** `updateCheckedDrawerItemForCurrentProjection()`。如果回退操作导致回到根目录（无 folder path），drawer 的选中项可能停留在上一个文件夹上，与当前实际导航状态不一致。

**`Add direct visible child count query for header subtitle if selected`**

已通过 `ThingManager.getVisibleChildCountsForActivityHeader()` 实现，直接从 `mThingListEntries` 统计可见的直接子条目数。

---

### Phase 3 — Home Adapter And Layout

**`Keep selection/moving dimming consistent`**

功能上已实现（`bindFolderSelectionAppearance()` 施加 dimming），但代码中存在大量 moving scale recovery 相关的 debug 日志（`scheduleMovingCardScaleRecoveryIfReleased` / `tag_thing_card_moving_scale_recovery_token`），说明曾出现过或仍存在移动模式下的缩放恢复抖动。这些日志在非 debug 构建中应被彻底移除或由 feature flag 控制，否则会造成不必要的字符串分配和 logcat 噪音。

---

### Phase 4 — Drag-To-Folder

**`Recompute mixed ordering around the affected range`**

未完成。当前所有文件夹操作（创建、移动、删除、解散）后的列表更新都通过 `loadThings()` → `rebuildThingListEntries()` 完成全量重建，而非针对受影响位置范围的增量更新。功能上正确（全量排序结果与增量一致），但性能上存在浪费；在条目数较大的列表中，频繁操作会让整个列表闪烁。这不是 bug，但属于未完成的优化项。

**`Add undo if selected for v1`**

完全缺失。现有的 `mUndoSnackbar` + `mUndoThings` 仅服务于 Thing 的状态变更（完成/删除/恢复），没有扩展到文件夹操作。以下操作均无 undo：
- 拖拽创建文件夹（取消命名对话框是手动回滚，不是 undo 栈）
- 将 Thing/Folder 移动到文件夹
- 删除文件夹（移动至已删除）
- 解散文件夹（移出全部内容并删除空文件夹）

---

### Phase 7 — Detail

**`Hide or show top-level state according to the confirmed decision`**

无相关实现。当前文件夹视图始终混合展示 Folder Cards 和 Thing Cards，没有「仅显示文件夹/仅显示记事」的切换开关。如果这是已确认的不做切换，该条目应在 execution.md 中标记为不适用（N/A）而非留空。

**`Refresh visible Detail when external folder membership changes`**

未完成。`DetailActivity` 通过 `KEY_LIST_PROJECTION` 接收当前投影并展示文件夹路径，但 Detail 页面打开后，如果文件夹成员关系在外部发生变更（通过 widget 操作、通知快捷操作、备份恢复等），Detail 不会自动刷新。当前仅在 Activity 返回时通过 `onActivityResult` + `isResultListProjectionCurrent` 检查投影一致性，当投影不一致时会**拒绝更新**而非重新加载。

**`Keep screenshot/share UI clean`**

未处理。`ActivityHeader` 在文件夹内会显示文件夹名称和路径，截图时这些信息会直接进入截图内容。如果用户截屏分享给他人，文件夹名称（包括私有文件夹的标题）会暴露在截图中。`SendInfoHelper.getThingShareInfo()` 完全没有包含文件夹路径信息——导出/分享时不带文件夹上下文。

---

### Phase 8 — State, Search, And Integration

**`Update single-Thing export/share if folder path is selected for v1`**

未完成。`SendInfoHelper.getThingShareInfo()` 生成 Thing 的文本分享内容时，不包含该 Thing 所在的文件夹路径。`ThingExporter.export()` 也不在导出文件中写入文件夹信息。

**`Update backup restore smoke path`**

备份/恢复通过 `BackupHelper` 以整个 `dataDir` 子集 zip 打包的方式实现，文件夹相关的数据库表（`thing_folders`）和列（`things.folder_id`）在 zip 过程中自然包含在内。因此备份在数据层面已覆盖文件夹。但在恢复后没有对文件夹树结构进行完整性校验（例如检查 `parent_folder_id` 指向不存在的记录、`folder_id` 悬空引用等）。如果用户从有文件夹的备份恢复到旧版本（无文件夹支持），应用会因数据库 schema 版本不匹配而 crash——但这是 SQLiteOpenHelper 的正常降级保护行为，不是 bug。

---

## 二、Verification Matrix 中的未勾选项

Verification Matrix 中绝大多数条目仍为未勾选状态（`[ ]`），仅 `:app:assembleDebug passes` 一项为已勾选。这些条目覆盖了从安装、升级、基础交互到边界条件的大量场景。虽然其中很多行为在日常开发中已经通过手动测试覆盖，但缺乏结构化的验证记录使得回归测试的范围不明确。

建议优先验证以下高风险条目：
- 数据库从 v14 升级保留所有现有 Things
- 根 underway 列表在无文件夹时保持与旧版一致
- 文件夹创建/重命名取消的回滚行为
- 私有文件夹内非私有 Thing 移出后恢复正常显示
- 已删除文件夹的子树在非已删除投影中消失
- 永久删除已删除文件夹后子树全部销毁

---

## 三、代码层面的潜在 Bug 和设计隐患

### 3.1 数据一致性风险

**A. `getThingsForEffectiveDeletedFolderProjection()` 仅查询直接子节点**

`ThingDAO.kt:200-262` 查询 `folder_id = currentFolderId` 的 Thing，但**不会**递归查询子文件夹中的 Thing。这意味着在 Deleted 投影中打开一个已删除文件夹时，该文件夹的子文件夹内的 Thing 不会被显示。这与 `getThingsForProjection()` 的行为不一致——后者通过 `LimitForGettingThings` 的过滤机制结合 `rebuildThingListEntries()` 在应用层做递归投影。

**B. `withLimit()` 丢弃 folder path**

已在第一节详述。这是 `ThingListProjection` 的数据不变量被破坏的例子——消费者期望 `withLimit` 只修改 limit 部分，但实际上 setter 返回了一个全新（且缺少数据）的对象。

**C. `rebuildThingsFromListEntries()` 仅提取 ThingEntry**

```kotlin
for (entry in entries) {
    if (entry is ThingListEntry.ThingEntry) {
        things.add(entry.thing)
    }
}
```

这个方法是纯 Thing 列表的重建路径，在 `move()` 操作后被调用。它假定 `mThings` 只应包含 `ThingEntry`，不会丢失 `FolderEntry`——但这意味着 `mThings` 与 `mThingListEntries` 之间存在隐含的类型差异：`mThings` 不含 Folder，`mThingListEntries` 含 Folder。如果有任何旧代码仍然依赖 `mThings` 来做完整列表枚举，它会错过所有文件夹条目。

**D. 移动/删除操作与 `loadThings()` 的并发窗口**

`ThingManager` 的操作方法（`moveThingIntoFolder`、`deleteFolder` 等）在同一个线程上同步执行 DB 写入和 `loadThings()`。虽然在当前单线程调用模式下不存在真正的并发问题，但如果将来引入了异步 DAO 调用（例如 LiveData 或协程），操作 A 的 `loadThings` 可能在操作 B 写入 DB 之后执行，使用过时的列表覆盖更新的状态。

### 3.2 私有文件夹相关

**E. `cancelCreatedFolder()` 不恢复私有认证状态**

当用户取消文件夹创建时，源 Thing 被恢复到各自的原始父文件夹。如果其中某个原始父文件夹是私有文件夹，且用户在创建操作前已经通过了私有内容的认证，取消操作后该认证状态不会被恢复。这意味着被恢复回的 Thing 会暂时被隐藏，直到用户重新认证。

**F. `isEffectivelyPrivate()` 每次调用重新走树**

对于深层嵌套的私有文件夹，`isEffectivelyPrivate()` 和 `isEffectivelyDeleted()` 每次调用都会逐级查询祖先文件夹，产生 N+1 查询。在列表重建期间（`rebuildThingListEntries` 中每个条目都可能触发这个检查），这可能造成显著的数据库压力。

**G. 粘性文件夹的图标着色依赖父文件夹存在**

`tintFolderStickyIcon()` 在 `parentFolderId != null` 时会查询父文件夹来获取背景色。如果父文件夹已被删除但子文件夹的 `parentFolderId` 未更新（这在 cleanup 逻辑之外可能发生），`getFolderById` 返回 null，fallback 到当前文件夹自身的颜色。逻辑上有容错，但非空断言 `parentFolderId!!` 的代码风格暗示开发者假设这里不会为 null，实际路径可能存在边缘 case。

### 3.3 导航和状态同步

**H. 返回键不更新 drawer 选中项**

已在第一节详述。`onBackPressed` → `openParentFolder()` → `updateDrawerFolderItems()` 但没有 `updateCheckedDrawerItemForCurrentProjection()`。

**I. `isResultListProjectionCurrent` 使用字符串比较**

```kotlin
resultProjection == mThingManager!!.getProjection().key()
```

`key()` 生成 `"$limit:${folderPath.joinToString("/")}"` 的字符串。这种设计有两个问题：(1) 字符串拼接是潜在的 GC 压力源；(2) 基于字符串的比较不如结构化比较语义清晰。如果将来修改 `key()` 格式，所有依赖方都需要同步更新。一个 `data class` 的 `equals` 或显式的 `isProjectionMatch` 方法会更有语义、更安全。

### 3.4 导出/分享

**J. 分享和导出不含文件夹路径**

`SendInfoHelper.getThingShareInfo()` 仅提取标题、内容、提醒/习惯/目标信息，不提取该 Thing 所在的文件夹路径。用户通过分享发送给他人时，接收方完全不知道这个 Thing 在文件夹体系中的位置。同样，`ThingExporter` 的导出文件（txt/zip）也不包含文件夹上下文。

### 3.5 性能相关

**K. `getVisibleFolderEntry` / `getVisibleFolderPosition` 线性扫描**

这两个方法在拖拽落点匹配和文件夹掉落提交时被调用，对 `mThingListEntries` 做完整线性扫描。在条目数非常大（几百条以上）时可能造成可感知的延迟。由于当前调用频次不高（每个拖拽操作最多几次），不是紧急问题，但值得在后续切片中用 HashMap 做 O(1) 查找。

**L. 每次文件夹操作后全量 `notifyDataSetChanged()`**

文件夹创建、删除、移动、解散、重命名等操作后都调用 `mAdapter.notifyDataSetChanged()`（或通过 `ThingsAdapterWrapper` 的等价路径）。对于仅有位置调整的操作（如移动一条 Thing），应使用 `notifyItemMoved` + 局部更新而非全量刷新，以保留 RecyclerView 的动画效果并减少不必要的 rebind。

---

## 四、总结

### 已完成的核心功能
- 数据模型（ThingFolder、ThingFolderCardPresentation、ThingListEntry、ThingListProjection）
- DAO 层（CRUD、层级查询、计数、有效状态计算）
- 列表混合渲染（Thing Cards + Folder Cards，摘要/缩略图两种模式）
- 拖拽创建文件夹/移入文件夹
- Overlay 拖拽系统（替代 ItemTouchHelper 的部分）
- 文件夹导航（打开、返回、路径分段点击）
- Header 文件夹路径显示
- Drawer 文件夹树
- 私有文件夹的继承隐私 + 认证范围
- 文件夹删除/恢复/永久删除
- 所有内置目的类型的文件夹投影
- Detail 文件夹路径展示

### 仍需完成或修复的较高优先级项
1. `withLimit()` 丢弃 folder path → 修复或明确产品决策
2. 返回键后 drawer 选中项不同步 → 补充 `updateCheckedDrawerItemForCurrentProjection()` 调用
3. `getThingsForEffectiveDeletedFolderProjection` 不递归子文件夹 → 修复或替换为使用标准投影方法
4. 文件夹操作缺少 undo 机制 → 对删除和移动操作添加 snackbar undo
5. export/share 不含文件夹路径 → 在分享/导出文本中加入文件夹信息

### 仍需完成或修复的中低优先级项
6. 移动/删除/解散操作后的全量列表更新 → 改用增量更新
7. `cancelCreatedFolder()` 不恢复私有认证状态 → 存储并恢复认证上下文
8. N+1 查询 `isEffectivelyPrivate`/`isEffectivelyDeleted` → 添加缓存或批量查询
9. 字符串比较 `isResultListProjectionCurrent` → 改为结构化比较
10. Detail 不自动刷新外部文件夹成员变更 → 添加数据变更监听
11. 线性扫描 `getVisibleFolderEntry`/`getVisibleFolderPosition` → HashMap 查找
12. 截图分享时 header 中的文件夹信息可能泄露 → 评估是否需要脱敏

### 已确认的不做项（按计划明确排除）
- 多父归属 / 别名 / 标签
- 文件夹在 drawer 中作为独立项目（抽屉只有内置目的和外置文件夹树）
- 文件夹提醒/习惯/目标行为
- 文件夹感知的 widget（v1 不做）
- 导入单个导出的 Thing zip 时的文件夹归属
