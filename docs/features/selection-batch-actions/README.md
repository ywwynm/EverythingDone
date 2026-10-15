# Selection Batch Actions（多选批量动作）

状态：批量动作已实现；2026-10-15 将普通 ActionBar 的全部完成／恢复／删除／彻底删除接入相同退场流程，三机隔离回归通过，已发布阿里云 debug `202610141632` 并核验远端 APK。此前媒体遮罩、缓存卡片、子控件恢复和四列补位修复保留。

## 当前动画流程

确认窗口完整关闭 → 被移除的可见项播放退场 → 粒子和整页前景释放 → 选择工具栏退场、未选项颜色恢复、列表补位 → 布局稳定后恢复输入。全不可见选择和关闭系统动画走同一业务与收尾入口。保留 SurfaceView。

- [媒体与子控件恢复的证据和回归](appearance-recovery-2026-10-13.md)
- [普通工具栏全部操作的接入与三机验证](toolbar-removal-2026-10-15.md)
- [完成／恢复／删除后的身份排查：三机六轮未复现错删](identity-investigation-2026-10-15.md)
- [批量转场顺序的证据与回归](transition-2026-10-13.md)
- [使用与动画要求](preferences.md)
- [隔离数据的实机回归方法](checks/README.md)

## Goal

让首页选择模式（SELECTING / contextual toolbar）的批量动作对"多文件夹"和"记事+文件夹混合"
选择全面可用，并补齐对"≥2 项"始终缺失的"置顶 / 设为私密"批量动作。

核心模型：批量动作采用"按每项类型分别执行对应语义"——同一个动作词对选中集中的每一项
执行其类型对应的操作（记事走状态操作，文件夹走内容操作 / 结构操作）。

## 设计起点（2026-06-23 的历史情况）

- 选择状态存在每个对象上：`Thing.selected` / `ThingFolder.selected`；模型已支持混合选择。
- `ThingManager` 已有 `getSelectedThings` / `getSelectedFolders` / `getSelectedFolderCount` 等。
- 闸门在 `ModeManager.updateMenuItemsForFolderSelection`：动作被 `singleFolderOnly`
  （1 文件夹 0 记事）或 `!hasSelectedFolder`（无文件夹）卡住。
- 因此"≥2 文件夹"或"记事+文件夹混合"时几乎无动作；"置顶/私密/卡片外观"对"≥2 项"从不可用。

## Documents

- `decisions.md` - grilling 过程中确认的设计决策。

## Related Global Records

- 领域语言：`CONTEXT.md`（Thing、Thing Folder、内容操作 / 结构操作等）。
- 文件夹语义权威矩阵：`docs/features/thing-folders/use-cases.md`。
- 选择模式工具栏 chrome：`docs/features/home-contextual-toolbar/`。
