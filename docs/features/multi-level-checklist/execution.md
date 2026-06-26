# Multi-level Checklist Items Execution

实现阶段的勾选清单。设计权威见 `plan.md` / `decisions.md` / `docs/adr/0010`，迭代历史见 `sessions.md`。
当前状态：**全部实现完成**，经多轮真机视觉微调与逻辑修复（禁跳空、完成态严格不变量、拖拽脱手/自动滚动/
横跳/重叠修复），已并入一次大提交。拖拽最终**做了**收束/展开动画 + 圆角矩形轮廓（推翻早前“从简放弃”），
但不做数量角标。

## Implementation Checklist

### 存储与迁移
- [x] 清单串解析/序列化纳入层级位（`item[1]`，文本 `substring(2)`；控制标记 `2/3/4` 仍单字符）。
- [x] 新增 DB 版本 21 与迁移：扫描 `isCheckListStr` 的 Thing，给每个真实项插入层级位 `1`。
- [x] `toContentStr` 等导出/解析路径跳过层级位。
- [x] 排查并修正所有把 `substring(1)` 当文本起点的旧调用。

### 完成状态机（组感知）
- [x] `CheckListHelper.toggleChecklistItem` 委托给新增的 `ChecklistCompletion.toggle`：级联子树 + 按组根整组
      迁移 + 取消回流 + 强制组根取消不横扫兄弟。
- [x] 顶/底边界判定改用 `3`/`4` 分隔位，替换“第一个 `1`”式逻辑。
- [x] 详情页适配器完成切换复用同一共享核心，仅保留 UI 事务。
- [x] `normalizeCompletion` 维护“已完成项的所有下属都已完成”严格不变量。

### 详情页编辑交互
- [x] 右侧删除图标改缩进按钮；左侧新增反缩进槽位与按钮（`ic_checklist_indent`/`ic_checklist_outdent`）。
- [x] 缩进门控（禁孤儿 + 禁跳空：需存在同级上一兄弟）、按边界隐藏、聚焦门控（缩进常显、箭头聚焦显）。
- [x] 缩进几何最终用**逐级独立 dp 值**（`detailIndentDp`），子项状态图标对齐父项文本；反缩进按钮对齐父级
      状态图标列。
- [x] 新建项层级：回车继承当前行层级、底部添加行给一级、有子项回车按 `pos+1` 插入。
- [x] 退格成为唯一删除入口；删带子项的项只删单项、子项经 `normalizeLevels` 重算归属。
- [x] 缩进/完成/拖拽的撤销改为内容串 before/after。
- [x] 缩进/反缩进在顶/底任意区域均可用。

### 分级排版
- [x] 三个独立比例常量（`LEVEL_SIZE_RATIO` / `LEVEL_ALPHA_RATIO_UNFINISHED` / `LEVEL_ALPHA_RATIO_FINISHED`，
      均 0.9 起步），应用于详情/卡片/widget。

### 显示界面
- [x] 卡片 TextView（首页/widget 配置页/大文件夹缩略图/DoingActivity）：缩进 + 分级字号/透明度。
- [x] RemoteViews（单一/列表 widget）：缩进 + `setTextViewTextSize` + 按级颜色。
- [x] 首页/widget 点击完成走共享状态机（含整组迁移）。
- [x] 截断按项数 + `...`；SIMPLE_FCLI 只折叠分隔线以下的底部已完成区。
- [x] DoingActivity、大文件夹缩略图、通知的多级显示。

### 单一文本输出
- [x] `toContentStr` 带标记输出改标准 Markdown 任务列表（`- [ ] / - [x]` + 每级 2 空格）；空前缀保持拍平。

### 拖拽（最终做了收束/展开动画）
- [x] 同 owner 同级兄弟带内重排，整棵子树随动；不改 owner/层级、不跨完成边界。
- [x] 整块移动只用 `notifyItemMoved`，修复拖根节点脱手（不用 `notifyDataSetChanged`）。
- [x] **组根代理 + 收束/展开动画**：拖拽开始收起子树只剩父项代理跟手（`beginChecklistDrag` +
      `ChecklistDragItemAnimator` 的 `animateRemove`），松手插回展开（`endChecklistDrag` + `animateAdd`）；
      `onMove` 改 `moveChecklistDragProxy` 单行代理跨越目标子树。
- [x] **圆角矩形轮廓**：被拖项 overlay 描边（文字色、10dp 圆角、四边视觉对称），渐显/渐隐。
- [x] **自定义自动滚动**：RecyclerView 嵌套在 NestedScrollView、自身不滚，逐帧滚父容器 + 补偿平移防脱手 +
      手指 Y 判边 + 按可移动性（父子树/区域边界）裁边。
- [x] **防横跳**：`fingerPastSubtreeMid` 手指越过目标子树中线才跨越。
- [x] **落位重叠兜底**：`resetChecklistChildTransforms` 复位动画残留变换。
- [~] 数量角标：按用户决定**不做**（从简）。
