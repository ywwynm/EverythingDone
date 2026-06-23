# Selection Batch Actions（多选批量动作）

Status: grilling / 设计中（2026-06-23 起）。

## Goal

让首页选择模式（SELECTING / contextual toolbar）的批量动作对"多文件夹"和"记事+文件夹混合"
选择全面可用，并补齐对"≥2 项"始终缺失的"置顶 / 设为私密"批量动作。

核心模型：批量动作采用"按每项类型分别执行对应语义"——同一个动作词对选中集中的每一项
执行其类型对应的操作（记事走状态操作，文件夹走内容操作 / 结构操作）。

## 现状（2026-06-23 核实）

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
